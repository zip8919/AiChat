package xyz.zip8919.app.aichat;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

/**
 * 腾讯云的余额与套餐余量查询：TC3-HMAC-SHA256 签名的 POST 调用。
 *
 * <p>为什么余额要走这条路：TokenHub 的推理域名只开放对话类接口，
 * 按 API Key 查不到任何余额。账户余额与 Token Plan 余量只存在于腾讯云云 API，
 * 需要 SecretId + SecretKey 现场签名，密钥不能离开设备。
 *
 * <p>本类只跑只读接口，分两组：
 *
 * <p><b>金额</b>（费用中心口径，TokenHub 后付费扣的就是这里的钱）：
 * <ul>
 *   <li>{@code billing / DescribeAccountBalance}：现金 / 赠送 / 可用 / 欠费 / 冻结，授信额度；</li>
 *   <li>{@code billing / DescribeVoucherInfo}：待使用的代金券余额（扣费时最先扣它）；</li>
 *   <li>{@code billing / DescribeCostSummaryByProduct}：本月 TokenHub 实际扣了多少钱。</li>
 * </ul>
 * TokenHub 没有单独的钱包：按量付费的扣费顺序是「代金券 → 赠送金 → 现金，
 * 信用客户最后用授信额度」，所以这几段合起来才是「还能用多少」。
 * 只显示现金余额会在有代金券或赠送金时把余额显示得偏低。
 *
 * <p><b>TokenHub 用量与套餐</b>：
 * <ul>
 *   <li>{@code tokenhub / DescribePaymentSummary}：计费方式汇总；</li>
 *   <li>{@code tokenhub / DescribeEndpointList}：每个模型的免费额度用量、是否已转后付费；</li>
 *   <li>{@code tokenhub / DescribeUsageRankList}：近 7 天 token 用量；</li>
 *   <li>{@code tokenhub / DescribeTokenPlanPersonalPackage}：Token Plan 个人版套餐额度。</li>
 * </ul>
 * 免费额度用完就意味着每一笔调用都从余额扣钱，所以它和余额必须一起看，
 * 只有余额数字看不出「还能撑多久」。
 * 不要在这里加任何会改动账户的接口。
 *
 * <p><b>为什么是「探测式」而不是写死解析</b>：腾讯云的用量/计费类接口字段经常
 * 按版本增减，且个人账号与企业账号返回的结构并不一致（未开通的项常常整段缺失）。
 * 这里逐个调、逐个把返回里的数字与字符串摊成「名称：值」，某个接口不可用就
 * 记一条原因继续下一个，最终显示「有什么就显示什么」，不会因为一个接口不支持
 * 就整页空白。诊断模式还会把每个接口的原始返回一并列出，便于核对字段。
 *
 * <p>签名按官方 TC3 文档实现：规范请求串 → 待签串 → 派生签名密钥 → 授权头。
 * 时间戳必须用 UTC，且与腾讯云服务器偏差超过 5 分钟会被拒（AuthFailure.SignatureExpire），
 * 所以返回里遇到签名过期要明确提示用户校准设备时间，而不是笼统报「查询失败」。
 */
final class TencentCloudApi {

    private static final String TAG = "TencentCloudApi";

    /** 查询结果：字段行 + 原始响应 + 错误说明，交给 {@link BalanceDialog} 展示。 */
    static final class Result {
        boolean ok;
        String error;
        String note;
        /** 原始响应体（含错误码），供「原始返回」查看。 */
        String body;
        /** 解析后的 Response 对象；接口失败时为 null。 */
        JSONObject json;
        int code;
        String url;
        java.util.List<String[]> fields = new java.util.ArrayList<String[]>();
        /** 逐接口的调用结果（名称、动作、状态、说明），诊断模式直接展示。 */
        java.util.List<String[]> probes = new java.util.ArrayList<String[]>();
        /** 逐接口的原始返回（标题 + 响应体），诊断模式用。 */
        java.util.List<String[]> raws = new java.util.ArrayList<String[]>();

        void probe(String name, String action, String status, String detail) {
            probes.add(new String[] { name, action, status, detail });
        }

        void raw(String title, String body) {
            if (body != null && body.length() > 0) raws.add(new String[] { title, body });
        }

        void add(String name, String value) {
            if (value == null) return;
            String v = value.trim();
            if (v.length() == 0) return;
            fields.add(new String[] { name, v });
        }

        /** 取字段的数值，缺失或不是数字时返回 0。 */
        double num(String key) {
            if (json == null) return 0;
            Object v = json.opt(key);
            if (v == null || JSONObject.NULL.equals(v)) return 0;
            try {
                return Double.parseDouble(String.valueOf(v));
            } catch (Throwable t) {
                return 0;
            }
        }

        /**
         * 置顶字段：可用额度与待使用代金券，这两项决定「现在还能不能继续调用」。
         *
         * <p>按名称挑而不是按位置挑：代金券为 0 时那行不会出现，按位置会把
         * 「账户可用余额」顶到最前，和它下面那行重复。
         */
        java.util.List<String[]> leadFields() {
            java.util.List<String[]> out = new java.util.ArrayList<String[]>();
            for (int i = 0; i < fields.size(); i++) {
                if (isLead(fields.get(i)[0])) out.add(fields.get(i));
            }
            return out;
        }

        /** 其余账户明细：排在其他组的数字之后。 */
        java.util.List<String[]> restFields() {
            java.util.List<String[]> out = new java.util.ArrayList<String[]>();
            for (int i = 0; i < fields.size(); i++) {
                if (!isLead(fields.get(i)[0])) out.add(fields.get(i));
            }
            return out;
        }

        private static boolean isLead(String name) {
            return name.startsWith("TokenHub 可用额度") || name.startsWith("待使用代金券");
        }
    }

    private TencentCloudApi() {
    }

    /**
     * 查 TokenHub 后付费还能用的钱。
     *
     * <p>TokenHub 按量付费从腾讯云账户实时扣费，扣费顺序为
     * 「代金券 → 赠送金（+收益转入）→ 现金充值，信用客户最后用授信额度」，
     * 所以这里把三段都取出来，按顺序排在正文最前面；只报现金余额会低估可用额度。
     *
     * <p>两个接口都是只读，任一不可用（无权限、未开通）只记一条原因，不影响另一个。
     *
     * @param secretId  SecretId（AKID 开头）
     * @param secretKey SecretKey
     * @param region    billing 接口用空串（全局接口）
     */
    static Result accountBalance(String secretId, String secretKey, String region) {
        Result bal = call(secretId, secretKey, region,
                "billing.tencentcloudapi.com", "billing", "2018-07-09",
                "DescribeAccountBalance", new JSONObject(), false);
        Result voucher = call(secretId, secretKey, "ap-guangzhou",
                "billing.tencentcloudapi.com", "billing", "2018-07-09",
                "DescribeVoucherInfo", voucherParams(), true);

        Result merged = new Result();
        merged.code = bal.code > 0 ? bal.code : voucher.code;
        merged.url = bal.url;
        merged.body = bal.body;
        merged.raw("账户余额 DescribeAccountBalance", bal.body);
        merged.raw("代金券 DescribeVoucherInfo", voucher.body);

        // 余额接口是主口径：它失败才整体算失败
        merged.ok = bal.ok;
        if (!bal.ok) {
            merged.error = bal.error;
            merged.note = "账户余额取不到，TokenHub 后付费可用额度也查不到。";
            return merged;
        }
        long cash = (long) bal.num("CashAccountBalance");
        long income = (long) bal.num("IncomeIntoAccountBalance");
        long present = (long) bal.num("PresentAccountBalance");
        long freeze = (long) bal.num("FreezeAmount");
        long owe = (long) bal.num("OweAmount");
        long creditLimit = (long) bal.num("CreditAmount");
        boolean hasCredit = bal.ok && creditLimit > 0;

        // 1) 代金券：只说「待使用」的余额，已用/过期的钱已经花不出去
        long voucherLeft = -1;
        if (voucher.ok) {
            JSONObject vo = voucher.json;
            long units = voucherUnitDivisor(vo.optString("Unit", ""));
            JSONObject sum = vo.optJSONObject("BalanceSum");
            long total = 0;
            if (sum != null) total = (long) (sum.optDouble("TotalBalance", 0) / units);
            else total = (long) (vo.optDouble("TotalBalance", 0) / units);
            long expired = sum != null ? (long) (sum.optDouble("Overdue", 0) / units) : 0;
            long used = sum != null ? (long) (sum.optDouble("Used", 0) / units) : 0;
            // 总余额含已用与已过期；两者都为 0 时直接用总余额（部分账号不返回明细）
            voucherLeft = (expired > 0 || used > 0) ? Math.max(0, total - used - expired) : total;
            merged.add("待使用代金券", moneyFen(voucherLeft) + " 元");
        }

        long cashUntilCredit = cash + income + present + (voucherLeft > 0 ? voucherLeft : 0);
        if (hasCredit) {
            merged.add("TokenHub 可用额度（含授信）",
                    moneyFen(cashUntilCredit + creditLimit) + " 元");
        } else {
            merged.add("TokenHub 可用额度（不含代金券）", moneyFen(cash + income + present) + " 元");
        }
        // 腾讯云口径的可用余额，与控制台费用中心显示一致，便于核对
        long avail = (long) bal.num("RealBalance");
        merged.add("账户可用余额", moneyFen(avail) + " 元");
        merged.add("现金充值余额", moneyFen(cash) + " 元");
        if (present > 0) merged.add("赠送金余额", moneyFen(present) + " 元");
        if (income > 0) merged.add("收益转入余额", moneyFen(income) + " 元");
        if (hasCredit) merged.add("授信额度", moneyFen(creditLimit) + " 元");
        if (freeze > 0) merged.add("冻结金额", moneyFen(freeze) + " 元");
        if (owe > 0) merged.add("欠费金额", moneyFen(owe) + " 元");

        StringBuilder note = new StringBuilder();
        note.append("TokenHub 按量付费没有单独的钱包，每次调用从腾讯云账户扣费；"
                + "扣费顺序为代金券 → 赠送金 → 现金，信用客户最后用授信额度。");
        if (!voucher.ok && voucher.error != null) {
            append(note, "代金券：" + voucher.error);
        }
        if (hasCredit) {
            append(note, "「含授信」即账户余额彻底用完后还能靠授信额度继续调用的部分。");
        }
        if (owe > 0) {
            append(note, "账户已欠费，充值为正后 TokenHub 推理服务自动恢复；"
                    + "暂停超过 15 天，在线推理服务会被删除。");
        }
        if (freeze > 0) {
            append(note, "冻结金额是开通后付费或领取免费包时预冻结的，"
                    + "删除对应模型的推理服务后回到账户。");
        }
        merged.note = note.toString();
        return merged;
    }

    /**
     * {@code DescribeVoucherInfo} 的入参：只取待使用、且能抵后付费的券。
     *
     * <p>不传 PayMode 时会把预付费专用券也算进来，那些券用不到 TokenHub 的后付费账单上。
     */
    private static JSONObject voucherParams() {
        JSONObject p = new JSONObject();
        try {
            p.put("Limit", 100);
            p.put("Offset", 1);
            p.put("Status", "unUsed");
            p.put("PayMode", "postPay");
        } catch (Exception ignored) {
            // put 只在 key 为 null 时抛，这里是常量
        }
        return p;
    }

    /** 代金券金额单位换算：micro 即 1e-8 元换算成分要再乘 1e6。 */
    private static long voucherUnitDivisor(String unit) {
        if (unit == null || unit.length() == 0 || "cent".equalsIgnoreCase(unit)) return 1;
        if ("micro".equalsIgnoreCase(unit)) return 1000000L;
        return 1;
    }

    /**
     * 查 TokenHub 的用量与免费额度（「还能用多久」主要看这一块）。
     *
     * <p>三块只读数据，缺一块都会让人误判：
     * <ul>
     *   <li>{@code DescribePaymentSummary}：后付费开通了几项、免费包领了几项；</li>
     *   <li>{@code DescribeEndpointList}：每个模型的免费额度用了多少、是否已转后付费；</li>
     *   <li>{@code DescribeUsageRankList}：近 7 天实际吃了多少 token。</li>
     * </ul>
     * 任一接口不被账号支持（未开通、无权限、入参不匹配）时只记一条原因，
     * 不影响其余接口的结果。
     *
     * <p>不再调用 {@code DescribeInferPageOverview}：它在只开了后付费的账号上恒返回
     * {@code InternalError}，每次查余额都白耗一次往返。
     */
    static Result tokenHubUsage(String secretId, String secretKey, String region) {
        Result merged = new Result();
        Result pay = call(secretId, secretKey, region,
                "tokenhub.tencentcloudapi.com", "tokenhub", "2026-03-22",
                "DescribePaymentSummary", new JSONObject(), true);
        merged.raw("计费方式汇总 DescribePaymentSummary", pay.body);
        merged.probe("计费方式汇总", "DescribePaymentSummary",
                pay.ok ? "可用" : "不可用", pay.ok ? null : pay.error);

        // 1) 免费额度：按模型给余量，用尽的要标出来——用尽即开始扣现金
        Result ep = call(secretId, secretKey, region,
                "tokenhub.tencentcloudapi.com", "tokenhub", "2026-03-22",
                "DescribeEndpointList", new JSONObject(), true);
        merged.raw("推理服务列表 DescribeEndpointList", ep.body);
        merged.probe("推理服务列表", "DescribeEndpointList",
                ep.ok ? "可用" : "不可用", ep.ok ? null : ep.error);
        String freeSummary = parseFreeQuota(ep, merged);

        // 2) 近 7 天用量
        Result used = call(secretId, secretKey, region,
                "tokenhub.tencentcloudapi.com", "tokenhub", "2026-03-22",
                "DescribeUsageRankList", usageParams(7), true);
        merged.raw("近 7 天用量 DescribeUsageRankList", used.body);
        merged.probe("近 7 天用量", "DescribeUsageRankList",
                used.ok ? "可用" : "不可用", used.ok ? null : used.error);
        parseWeekUsage(used, merged);

        merged.ok = !merged.fields.isEmpty();
        StringBuilder note = new StringBuilder();
        if (freeSummary != null) append(note, freeSummary);
        if (!merged.ok) {
            merged.error = pay.error != null ? pay.error
                    : (ep.error != null ? ep.error : used.error);
        }
        merged.note = note.length() > 0 ? note.toString() : null;
        return merged;
    }

    /**
     * {@code DescribeEndpointList}：把每个模型的免费额度摊成一行摘要。
     *
     * <p>{@code ChargeDetail} 是一段 JSON 字符串，里面才有 {@code FreeQuota}；
     * 字段名按实际返回取值，不按文档猜。
     *
     * @return 给说明区的一句话；没有免费额度信息时返回 null
     */
    private static String parseFreeQuota(Result ep, Result out) {
        if (!ep.ok || ep.json == null) return null;
        org.json.JSONArray list = ep.json.optJSONArray("EndpointSet");
        if (list == null) return null;
        StringBuilder exhausted = new StringBuilder();
        StringBuilder left = new StringBuilder();
        int totalTokens = 0;
        int usedTokens = 0;
        boolean any = false;
        for (int i = 0; i < list.length(); i++) {
            JSONObject e = list.optJSONObject(i);
            if (e == null) continue;
            String name = e.optString("EndpointName", e.optString("ModelName", ""));
            JSONObject quota = chargeDetail(e.optString("ChargeDetail", ""));
            if (quota == null) continue;
            any = true;
            long total = (long) quota.optDouble("TotalQuota", 0);
            long usedQ = (long) quota.optDouble("UsedQuota", 0);
            totalTokens += total;
            usedTokens += usedQ;
            boolean done = total > 0 && usedQ >= total;
            boolean postpaid = e.optBoolean("PaymentEnabled", false);
            if (done) {
                if (exhausted.length() > 0) exhausted.append("、");
                exhausted.append(name);
                if (postpaid) exhausted.append("（已转后付费）");
            } else {
                if (left.length() > 0) left.append("、");
                left.append(name).append(" 剩 ").append(wan(total - usedQ));
            }
        }
        if (!any) return null;
        StringBuilder sb = new StringBuilder();
        if (exhausted.length() > 0) {
            sb.append("免费额度已用完：").append(exhausted)
              .append("，这部分调用开始从账户余额扣费。");
        }
        if (left.length() > 0) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("免费额度仍有剩余：").append(left).append(" tokens。");
        }
        // 正文只放一句总量，明细在说明里，不挤占金额
        if (totalTokens > 0) {
            out.add("免费额度已用", wan(usedTokens) + " / " + wan(totalTokens) + " tokens");
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /** {@code ChargeDetail} 是 JSON 字符串，取出里面的 {@code FreeQuota}。 */
    private static JSONObject chargeDetail(String raw) {
        if (raw == null || raw.length() == 0) return null;
        try {
            return new JSONObject(ApiClient.sanitizeJson(raw)).optJSONObject("FreeQuota");
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * {@code DescribeUsageRankList} 的入参。
     *
     * <p>参数约束按实际接口试出来的，与文档不一致：{@code Dimension} 只认小写
     * {@code model/apikey/endpoint}，{@code Period} 是 int64 且只能取
     * 60 / 300 / 3600 / 86400，时间必须是 RFC3339（带 T 与 Z）。
     */
    private static JSONObject usageParams(int days) {
        JSONObject p = new JSONObject();
        try {
            p.put("Dimension", "endpoint");
            p.put("Period", 86400);
            p.put("StartTime", isoDay(-(days - 1), "T00:00:00Z"));
            p.put("EndTime", isoDay(0, "T23:59:59Z"));
        } catch (Exception ignored) {
            // put 只在 key 为 null 时抛，这里是常量
        }
        return p;
    }

    /** 相对今天偏移若干天，返回 {@code yyyy-MM-dd} 拼上给定的时分秒（接口要求 RFC3339）。 */
    private static String isoDay(int offsetDays, String time) {
        long t = System.currentTimeMillis() + offsetDays * 24L * 3600L * 1000L;
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(t)) + time;
    }

    /** 近 N 天用量：末尾的 {@code TotalStats} 是区间合计。 */
    private static void parseWeekUsage(Result used, Result out) {
        if (!used.ok || used.json == null) return;
        JSONObject stats = used.json.optJSONObject("TotalStats");
        if (stats == null) return;
        double total = stats.optDouble("TotalToken", 0);
        if (total <= 0) return;
        out.add("近 7 天用量", wan((long) total) + " tokens");
        out.add("近 7 天输入", wan((long) stats.optDouble("InputTotalToken", 0)) + " tokens");
        out.add("近 7 天输出", wan((long) stats.optDouble("OutputTotalToken", 0)) + " tokens");
    }

    /** token 数按万缩写：一屏里靠前的数字要能一眼看出量级。 */
    private static String wan(long n) {
        if (n < 10000) return String.valueOf(n);
        return trimZero(Math.round(n / 1000.0) / 10.0) + " 万";
    }

    /**
     * 查本月 TokenHub 实际扣了多少钱。
     *
     * <p>{@code DescribeCostSummaryByProduct} 按产品汇总，TokenHub 的产品码是 {@code p_lmp}；
     * 同一份返回里还会有一条「月度计费精度差异」，那是账面冲销，不属于 TokenHub 消耗。
     */
    static Result tokenHubCost(String secretId, String secretKey) {
        Result r = call(secretId, secretKey, "",
                "billing.tencentcloudapi.com", "billing", "2018-07-09",
                "DescribeCostSummaryByProduct", monthParams(), true);
        Result out = new Result();
        out.code = r.code;
        out.url = r.url;
        out.body = r.body;
        out.raw("本月扣费 DescribeCostSummaryByProduct", r.body);
        if (!r.ok || r.json == null) {
            out.error = r.error;
            out.probe("本月扣费", "DescribeCostSummaryByProduct", "不可用", r.error);
            return out;
        }
        org.json.JSONArray data = r.json.optJSONArray("Data");
        String cost = null;
        if (data != null) {
            for (int i = 0; i < data.length(); i++) {
                JSONObject item = data.optJSONObject(i);
                if (item == null) continue;
                if ("p_lmp".equalsIgnoreCase(item.optString("BusinessCode", ""))) {
                    cost = item.optString("RealTotalCost", "0.00");
                    break;
                }
            }
        }
        out.ok = true;
        out.probe("本月扣费", "DescribeCostSummaryByProduct", "可用", null);
        if (cost != null) {
            out.add("本月 TokenHub 扣费", cost + " 元");
        } else {
            out.note = "本月还没有产生 TokenHub 费用。";
        }
        return out;
    }

    /** {@code DescribeCostSummaryByProduct} 需要 BeginTime/EndTime（不是 StartTime）。 */
    private static JSONObject monthParams() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        long now = System.currentTimeMillis();
        JSONObject p = new JSONObject();
        try {
            String month = f.format(new Date(now)).substring(0, 7);
            p.put("BeginTime", month + "-01");
            p.put("EndTime", f.format(new Date(now)));
            p.put("Offset", 0);
            p.put("Limit", 50);
        } catch (Exception ignored) {
            // put 只在 key 为 null 时抛，这里是常量
        }
        return p;
    }

    /** 查 Token Plan 个人版套餐额度（含通用与混元两个产品类型）。 */
    static Result tokenPlanPackage(String secretId, String secretKey, String region) {
        Result merged = new Result();
        String[] types = { "personal", "personal-hunyuan" };
        String firstErr = null;
        for (int i = 0; i < types.length; i++) {
            JSONObject p = new JSONObject();
            try {
                p.put("ProductType", types[i]);
            } catch (Exception ignored) {
                // put 只在 key 为 null 时抛，这里是常量，不可能发生
            }
            Result r = call(secretId, secretKey, region,
                    "tokenhub.tencentcloudapi.com", "tokenhub", "2026-03-22",
                    "DescribeTokenPlanPersonalPackage", p, true);
            merged.raw("Token Plan " + types[i], r.body);
            if (r.ok) {
                // 没买过这个系列时接口回「未购买」，属于正常结果，不当失败
                merged.fields.addAll(r.fields);
                merged.probe("Token Plan " + types[i], "DescribeTokenPlanPersonalPackage",
                        "可用", r.fields.isEmpty() ? "未购买" : ("返回 " + r.fields.size() + " 项"));
            } else {
                String why = r.error == null ? "未知原因" : r.error;
                merged.probe("Token Plan " + types[i], "DescribeTokenPlanPersonalPackage",
                        "无套餐", why);
                if (firstErr == null) firstErr = why;
            }
        }
        merged.ok = !merged.fields.isEmpty();
        if (!merged.ok) merged.error = firstErr == null ? "未获取到套餐信息" : firstErr;
        merged.note = "套餐额度按当前周期统计，用尽后需等下一个订阅周期恢复。";
        return merged;
    }

    /**
     * 合并各组的查询结果：有字段的进正文，没字段的进「说明」，全部进诊断。
     *
     * <p>正文按「能不能继续用」排序，不按接口来源排：
     * 可用额度 → 本月已扣费 → 近 7 天用量 → 免费额度 → 账户明细 → Token Plan。
     * 余额数字单独看没有意义——本月扣了 0.56 元、余额 2.32 元，才知道还够用多久。
     */
    static Result collect(String secretId, String secretKey, boolean diagnostic) {
        Result all = new Result();
        Result bal = accountBalance(secretId, secretKey, "");
        Result cost = tokenHubCost(secretId, secretKey);
        Result usage = tokenHubUsage(secretId, secretKey, "ap-guangzhou");
        Result plan = tokenPlanPackage(secretId, secretKey, "ap-guangzhou");

        // 顺序即展示顺序：额度、扣费、用量、免费额度，最后才是账户明细与套餐
        all.fields.addAll(bal.leadFields());
        all.fields.addAll(cost.fields);
        all.fields.addAll(usage.fields);
        all.fields.addAll(bal.restFields());
        all.fields.addAll(plan.fields);
        all.probes.addAll(bal.probes);
        all.probes.addAll(cost.probes);
        all.probes.addAll(usage.probes);
        all.probes.addAll(plan.probes);
        all.raws.addAll(bal.raws);
        all.raws.addAll(cost.raws);
        all.raws.addAll(usage.raws);
        all.raws.addAll(plan.raws);

        all.code = bal.code > 0 ? bal.code : (cost.code > 0 ? cost.code : usage.code);
        all.url = bal.url;
        all.body = bal.body != null ? bal.body : cost.body;

        all.ok = !all.fields.isEmpty();
        if (!all.ok) {
            all.error = bal.error != null ? bal.error
                    : (usage.error != null ? usage.error : cost.error);
        }
        StringBuilder note = new StringBuilder();
        if (bal.note != null) append(note, bal.note);
        // 没开通用量统计或没买套餐都是常态：说清「没查到什么」比留白有用
        if (usage.fields.isEmpty() && usage.error != null) {
            append(note, "TokenHub 用量：" + usage.error);
        }
        if (plan.fields.isEmpty() && plan.error != null) {
            // 用 TokenHub 按量付费、不买 Token Plan 才是常态，这句不能像报错
            append(note, "Token Plan：当前账户未持有套餐，按量付费直接从账户余额扣费，不影响使用。");
        }
        if (usage.note != null) append(note, usage.note);
        if (cost.note != null) append(note, cost.note);
        if (plan.fields.size() > 0 && plan.note != null) append(note, plan.note);
        // 金额没查到但用量查到了：正文有内容也会被认为成功，失败原因必须落在说明里
        if (bal.fields.isEmpty() && bal.error != null) {
            append(note, "账户余额：" + bal.error);
        }
        if (diagnostic) {
            append(note, "诊断模式：逐个接口的调用结果与原始返回见下方，可复制反馈。");
        } else {
            append(note, "长按提供商可查看各接口的诊断信息。");
        }
        all.note = note.toString();
        return all;
    }

    /**
     * 诊断视图：逐接口的调用结果 + 每个接口的原始返回，拼成可复制的一段文本。
     *
     * <p>字段名以腾讯云实际返回为准，不靠文档猜：这里是唯一能确认
     * 「量到底在哪个字段里」的地方。
     */
    static String diagnosticText(Result r) {
        if (r == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("接口调用结果\n");
        for (int i = 0; i < r.probes.size(); i++) {
            String[] p = r.probes.get(i);
            sb.append(p[0]).append("（").append(p[1]).append("）：")
              .append(p[2]);
            if (p[3] != null && p[3].length() > 0) sb.append(" —— ").append(p[3]);
            sb.append('\n');
        }
        for (int i = 0; i < r.raws.size(); i++) {
            String[] x = r.raws.get(i);
            sb.append('\n').append("== ").append(x[0]).append(" ==\n").append(x[1]).append('\n');
        }
        return sb.toString();
    }

    private static void append(StringBuilder sb, String line) {
        if (line == null || line.length() == 0) return;
        if (sb.length() > 0) sb.append('\n');
        sb.append(line);
    }

    /**
     * 发一次签名请求。
     *
     * @param quotaTolerant true 表示「未购买 / 资源不存在」不算错误，用于套餐接口的探测
     */
    private static Result call(String secretId, String secretKey, String region,
            String host, String service, String version, String action,
            JSONObject params, boolean quotaTolerant) {
        Result out = new Result();
        String sid = secretId == null ? "" : secretId.trim();
        String skey = secretKey == null ? "" : secretKey.trim();
        if (sid.length() == 0 || skey.length() == 0) {
            out.error = "请先填写腾讯云 SecretId 与 SecretKey";
            return out;
        }
        if (!sid.startsWith("AKID")) {
            out.error = "SecretId 一般以 AKID 开头，请确认填的是访问密钥而不是 API Key";
            return out;
        }
        String reg = region == null ? "" : region.trim();
        out.url = "https://" + host + "/";
        HttpURLConnection conn = null;
        try {
            String payload = params.toString();
            long ts = System.currentTimeMillis() / 1000L;
            String date = utcDate(ts);
            String contentType = "application/json; charset=utf-8";

            // 1) 规范请求串
            String canonicalHeaders = "content-type:" + contentType + "\n"
                    + "host:" + host + "\n"
                    + "x-tc-action:" + action.toLowerCase(Locale.US) + "\n";
            String signedHeaders = "content-type;host;x-tc-action";
            String hashedPayload = sha256Hex(payload);
            String canonicalRequest = "POST\n/\n\n" + canonicalHeaders + "\n"
                    + signedHeaders + "\n" + hashedPayload;

            // 2) 待签串
            String credentialScope = date + "/" + service + "/tc3_request";
            String stringToSign = "TC3-HMAC-SHA256\n" + ts + "\n" + credentialScope + "\n"
                    + sha256Hex(canonicalRequest);

            // 3) 派生签名密钥
            byte[] kDate = hmac("TC3" + skey, date);
            byte[] kService = hmac(kDate, service);
            byte[] kSigning = hmac(kService, "tc3_request");
            String signature = hmacHex(kSigning, stringToSign);

            String authorization = "TC3-HMAC-SHA256 Credential=" + sid + "/" + credentialScope
                    + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;

            URL url = new URL(out.url);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", authorization);
            conn.setRequestProperty("Content-Type", contentType);
            conn.setRequestProperty("Host", host);
            conn.setRequestProperty("X-TC-Action", action);
            conn.setRequestProperty("X-TC-Version", version);
            conn.setRequestProperty("X-TC-Timestamp", String.valueOf(ts));
            if (reg.length() > 0) conn.setRequestProperty("X-TC-Region", reg);
            // 老机型不让系统做代理压缩，拿到的必须是明文 JSON
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);
            if (conn instanceof HttpsURLConnection) {
                TlsCompat.apply((HttpsURLConnection) conn);
            }
            byte[] bytes = payload.getBytes("UTF-8");
            conn.getOutputStream().write(bytes);
            conn.getOutputStream().close();

            int code = conn.getResponseCode();
            out.code = code;
            InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            String body = readAll(in);
            out.body = body;
            LogUtil.i(TAG, "%s HTTP %d body=%s", action, code, LogUtil.preview(body, 400));
            if (body == null || body.length() == 0) {
                out.error = "接口没有返回内容（HTTP " + code + "）";
                return out;
            }
            JSONObject json = new JSONObject(ApiClient.sanitizeJson(body));
            JSONObject resp = json.optJSONObject("Response");
            if (resp == null) {
                out.error = "返回内容无法识别";
                return out;
            }
            String errCode = resp.optString("Error", "");
            JSONObject err = resp.optJSONObject("Error");
            if (err != null) errCode = err.optString("Code", "");
            if (errCode.length() > 0) {
                String msg = err != null ? err.optString("Message", "") : "";
                if (quotaTolerant && isNotFound(errCode)) {
                    out.ok = false;
                    out.note = null;
                    out.error = "未购买该套餐";
                    return out;
                }
                out.error = describe(errCode, msg);
                return out;
            }
            out.json = resp;
            if ("billing".equals(service)) {
                // 金额接口的字段由调用方按扣费顺序拼装，这里只摊平兜底
                out.ok = true;
            } else if ("DescribeTokenPlanPersonalPackage".equals(action)) {
                parsePackage(resp, action, out);
            } else {
                // 用量 / 计费类接口：字段按版本变动，统一摊平，不写死字段名
                flatten(resp, "", out);
            }
            out.ok = !out.fields.isEmpty();
            if (!out.ok) out.error = "接口返回里没有可展示的额度字段";
            return out;
        } catch (java.net.SocketTimeoutException e) {
            LogUtil.w(TAG, "%s timeout: %s", action, e);
            out.error = "连接腾讯云超时，请检查网络后重试";
            return out;
        } catch (Exception e) {
            LogUtil.e(TAG, "%s exception: %s", action, e.getMessage(), e);
            out.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            return out;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 套餐接口在「没买过」时返回的错误码，属于正常情况。 */
    private static boolean isNotFound(String code) {
        return code.indexOf("ResourceNotFound") >= 0
                || code.indexOf("ResourceUnavailable") >= 0
                || code.indexOf("ResourceInsufficient") >= 0;
    }

    /** 把云 API 的错误码翻成「发生了什么 + 怎么办」。 */
    private static String describe(String code, String msg) {
        String detail = msg == null || msg.length() == 0 ? "" : ("（" + msg + "）");
        if (code.indexOf("AuthFailure.SignatureExpire") >= 0) {
            return "签名已过期：设备时间与标准时间偏差过大，校准系统时间后再查" + detail;
        }
        if (code.indexOf("AuthFailure.SecretIdNotFound") >= 0) {
            return "SecretId 不存在：确认复制完整，且密钥未被删除或禁用" + detail;
        }
        if (code.indexOf("AuthFailure.SignatureFailure") >= 0) {
            return "签名校验不通过：SecretKey 与 SecretId 不是同一对，重新复制一次" + detail;
        }
        if (code.indexOf("AuthFailure") >= 0) {
            return "身份验证失败：检查 SecretId 与 SecretKey 是否正确" + detail;
        }
        if (code.indexOf("UnauthorizedOperation") >= 0) {
            return "这对密钥没有该接口的权限，可在腾讯云访问管理中授予只读权限" + detail;
        }
        if (code.indexOf("RequestLimitExceeded") >= 0) {
            return "请求太频繁，稍后再查" + detail;
        }
        if (code.indexOf("InternalError") >= 0) {
            return "腾讯云接口暂时不可用，稍后重试" + detail;
        }
        return code + detail;
    }

    // ---- 各接口返回解析 ----

    /** {@code DescribeTokenPlanPersonalPackage}：额度字段按接口原值显示，积分常带小数。 */
    private static void parsePackage(JSONObject resp, String action, Result out) {
        String status = resp.optString("PackageStatus", "");
        if (status.length() == 0 && !resp.has("TotalCredits")) {
            // 这个产品类型没返回有效套餐：不写一行空标题，交给上层判定为「未购买」
            return;
        }
        String type = "personal-hunyuan".equalsIgnoreCase(resp.optString("ProductType", ""))
                ? "混元 Token Plan" : "通用 Token Plan";
        out.add(type + " · 状态", describeStatus(status));
        String total = number(resp.opt("TotalCredits"));
        String used = number(resp.opt("TotalUsed"));
        if (used != null) out.add(type + " · 已用额度", used);
        if (total != null) out.add(type + " · 总额度", total);
        if (total != null && used != null) {
            try {
                double left = Double.parseDouble(total) - Double.parseDouble(used);
                out.add(type + " · 剩余额度", trimZero(left));
            } catch (Throwable ignored) {
                // 接口给的不是数字（极少见）：不显示剩余，避免算出错值
            }
        }
        String cycle = number(resp.opt("CycleCredits"));
        if (cycle != null) out.add(type + " · 单周期额度", cycle);
    }

    /**
     * 把一份返回体摊成「名称：值」行。
     *
     * <p>只收两类叶子：数字（额度、次数、百分比）与短字符串（状态、计费方式、
     * 到期时间）。布尔值只在为 true 时收（{@code "后付费已开通：是"} 有用，
     * 一屏的 false 只会把真正的数字挤下去）。空串、null、长文本一律跳过。
     *
     * <p>嵌套对象拼成「父 / 子」，数组按序号展开（最多前 5 项）：用量汇总常有
     * 「按模型/按日」的数组，全展开会把弹窗撑到几十屏，只取前几项足以说明情况。
     */
    private static void flatten(JSONObject obj, String prefix, Result out) {
        if (obj == null) return;
        java.util.Iterator<?> it = obj.keys();
        while (it.hasNext()) {
            String key = String.valueOf(it.next());
            // RequestId 只是排障用的关联号，不属于「额度」
            if ("RequestId".equals(key)) continue;
            Object v = obj.opt(key);
            String name = prefix.length() == 0 ? key : (prefix + " / " + key);
            if (v == null || JSONObject.NULL.equals(v)) continue;
            if (v instanceof JSONObject) {
                JSONObject sub = (JSONObject) v;
                // 一行里塞太多字段会盖住真正的金额，浅层展开即可
                if (depth(name) < 3) flatten(sub, name, out);
                continue;
            }
            if (v instanceof org.json.JSONArray) {
                org.json.JSONArray arr = (org.json.JSONArray) v;
                for (int i = 0; i < arr.length() && i < 5; i++) {
                    Object e = arr.opt(i);
                    if (e instanceof JSONObject) {
                        flatten((JSONObject) e, name + "[" + i + "]", out);
                    } else if (e != null && !JSONObject.NULL.equals(e)) {
                        out.add(name + "[" + i + "]", leaf(e));
                    }
                }
                continue;
            }
            String text = leaf(v);
            if (text == null) continue;
            out.add(name, text);
        }
    }

    /** 名称里有几段（用「 / 」分隔），用来限层。 */
    private static int depth(String name) {
        int n = 1, i = 0;
        while ((i = name.indexOf(" / ", i)) >= 0) {
            n++;
            i += 3;
        }
        return n;
    }

    /** 叶子值 → 展示文本；不值得展示时返回 null。 */
    private static String leaf(Object v) {
        if (v instanceof Boolean) {
            return ((Boolean) v).booleanValue() ? "是" : null;
        }
        if (v instanceof Number) {
            return trimZero(((Number) v).doubleValue());
        }
        String s = String.valueOf(v).trim();
        if (s.length() == 0 || s.length() > 60) return null;
        // ISO8601 时间只留到分钟，秒与毫秒对「什么时候到期」没有意义
        if (s.length() >= 19 && s.charAt(4) == '-' && s.charAt(10) == 'T') {
            return s.substring(0, 16).replace('T', ' ');
        }
        return s;
    }

    private static String describeStatus(String s) {
        if (s == null || s.length() == 0) return "";
        if ("active".equalsIgnoreCase(s)) return "生效中";
        if ("processing".equalsIgnoreCase(s)) return "处理中";
        if ("used_up".equalsIgnoreCase(s)) return "额度已用尽";
        if ("isolated".equalsIgnoreCase(s)) return "已隔离（欠费）";
        if ("stopped".equalsIgnoreCase(s)) return "已停止";
        if ("destroyed".equalsIgnoreCase(s)) return "已销毁";
        if ("unpurchased".equalsIgnoreCase(s)) return "未购买";
        if ("failed".equalsIgnoreCase(s)) return "创建失败";
        return s;
    }

    /** 分单位整数 → 元，两位小数；负值也能正确输出。 */
    private static String moneyFen(long fen) {
        return String.format(Locale.US, "%d.%02d", fen / 100, Math.abs(fen % 100));
    }

    /** 数字去掉无意义的 .0，保留接口给的精度。 */
    private static String number(Object v) {
        if (v == null || JSONObject.NULL.equals(v)) return null;
        try {
            double d = Double.parseDouble(String.valueOf(v));
            return trimZero(d);
        } catch (Throwable t) {
            return String.valueOf(v);
        }
    }

    private static String trimZero(double d) {
        if (d == Math.floor(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
        String s = String.format(Locale.US, "%.4f", d);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    // ---- 签名原语 ----

    private static String utcDate(long seconds) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(seconds * 1000L));
    }

    private static byte[] hmac(String key, String msg) throws Exception {
        return hmac(key.getBytes("UTF-8"), msg);
    }

    private static byte[] hmac(byte[] key, String msg) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(msg.getBytes("UTF-8"));
    }

    private static String hmacHex(byte[] key, String msg) throws Exception {
        return hex(hmac(key, msg));
    }

    private static String sha256Hex(String s) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        return hex(md.digest(s.getBytes("UTF-8")));
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xFF;
            if (b < 16) sb.append('0');
            sb.append(Integer.toHexString(b));
        }
        return sb.toString();
    }

    private static String readAll(InputStream in) {
        if (in == null) return null;
        StringBuilder sb = new StringBuilder();
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
        } catch (Exception e) {
            LogUtil.w(TAG, "read body failed: %s", e.getMessage());
        }
        return sb.toString();
    }
}
