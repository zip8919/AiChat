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
 * <p><b>金额</b>（费用中心口径，按量付费看这里）：
 * <ul>
 *   <li>{@code billing / DescribeAccountBalance}：账户可用余额、冻结、欠费。</li>
 * </ul>
 *
 * <p><b>TokenHub 用量与套餐</b>：
 * <ul>
 *   <li>{@code tokenhub / DescribePaymentSummary}：计费方式汇总（后付费是否开通、限额）；</li>
 *   <li>{@code tokenhub / DescribeInferPageOverview}：推理服务页 KPI 汇总（近期用量）；</li>
 *   <li>{@code tokenhub / DescribeTokenPlanPersonalPackage}：Token Plan 个人版套餐额度。</li>
 * </ul>
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
    }

    private TencentCloudApi() {
    }

    /**
     * 查账户余额。
     *
     * @param secretId  SecretId（AK 开头）
     * @param secretKey SecretKey
     * @param region    地域，billing 接口用空串（全局接口），传空即可
     */
    static Result accountBalance(String secretId, String secretKey, String region) {
        Result r = call(secretId, secretKey, region,
                "billing.tencentcloudapi.com", "billing", "2023-07-01",
                "DescribeAccountBalance", new JSONObject(), false);
        r.raw("账户余额 DescribeAccountBalance", r.body);
        return r;
    }

    /**
     * 查 TokenHub 的用量与计费概览（按量付费主要看这一块）。
     *
     * <p>两个接口都是只读汇总，不传业务参数（按当前主账号聚合）：
     * <ul>
     *   <li>{@code DescribePaymentSummary}：计费方式汇总；</li>
     *   <li>{@code DescribeInferPageOverview}：推理服务页 KPI 汇总。</li>
     * </ul>
     * 任一接口不被账号支持（未开通、无权限、入参不匹配）时只记一条原因，
     * 不影响另一个接口的结果。
     */
    static Result tokenHubUsage(String secretId, String secretKey, String region) {
        Result merged = new Result();
        String[][] jobs = {
                { "计费方式汇总", "DescribePaymentSummary" },
                { "推理用量汇总", "DescribeInferPageOverview" },
        };
        String firstErr = null;
        for (int i = 0; i < jobs.length; i++) {
            Result r = call(secretId, secretKey, region,
                    "tokenhub.tencentcloudapi.com", "tokenhub", "2026-03-22",
                    jobs[i][1], new JSONObject(), true);
            merged.raw(jobs[i][0] + " " + jobs[i][1], r.body);
            if (r.ok) {
                merged.fields.addAll(r.fields);
                merged.probe(jobs[i][0], jobs[i][1], "可用",
                        r.fields.isEmpty() ? "返回里没有可展示的字段" : ("返回 " + r.fields.size() + " 项"));
            } else {
                String why = r.error == null ? "未知原因" : r.error;
                merged.probe(jobs[i][0], jobs[i][1], "不可用", why);
                if (firstErr == null) firstErr = jobs[i][0] + "：" + why;
            }
        }
        merged.ok = !merged.fields.isEmpty();
        if (!merged.ok && firstErr != null) merged.error = firstErr;
        merged.note = "用量按当前计费周期汇总，与腾讯云控制台的统计口径一致。";
        return merged;
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
     * 合并三组查询的结果：有字段的进正文，没字段的进「说明」，全部进诊断。
     *
     * <p>合并顺序固定为「账户余额 → TokenHub 用量 → Token Plan」：用户最关心
     * 的「还有多少钱」必须第一眼看到，套餐额度是补充。
     */
    static Result collect(String secretId, String secretKey, boolean diagnostic) {
        Result all = new Result();
        Result bal = accountBalance(secretId, secretKey, "");
        Result usage = tokenHubUsage(secretId, secretKey, "ap-guangzhou");
        Result plan = tokenPlanPackage(secretId, secretKey, "ap-guangzhou");

        all.fields.addAll(bal.fields);
        all.fields.addAll(usage.fields);
        all.fields.addAll(plan.fields);
        all.probes.addAll(bal.probes);
        all.probes.addAll(usage.probes);
        all.probes.addAll(plan.probes);
        all.raws.addAll(bal.raws);
        all.raws.addAll(usage.raws);
        all.raws.addAll(plan.raws);

        all.code = bal.code > 0 ? bal.code : (usage.code > 0 ? usage.code : plan.code);
        all.url = bal.url;
        all.body = bal.body != null ? bal.body : (usage.body != null ? usage.body : plan.body);

        all.ok = !all.fields.isEmpty();
        if (!all.ok) {
            all.error = bal.error != null ? bal.error
                    : (usage.error != null ? usage.error : plan.error);
        }
        StringBuilder note = new StringBuilder();
        if (bal.note != null) note.append(bal.note);
        // 没开通用量统计或没买套餐都是常态：说清「没查到什么」比留白有用
        if (usage.fields.isEmpty() && usage.error != null) {
            append(note, "TokenHub 用量：" + usage.error);
        }
        if (plan.fields.isEmpty() && plan.error != null) {
            append(note, "Token Plan：" + plan.error);
        }
        if (usage.fields.size() > 0 && usage.note != null) append(note, usage.note);
        if (plan.fields.size() > 0 && plan.note != null) append(note, plan.note);
        if (diagnostic) {
            append(note, "诊断模式：逐个接口的调用结果与原始返回见下方，可复制反馈。");
        } else {
            append(note, "长按「查询余额」可查看各接口的诊断信息。");
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
            if ("billing".equals(service)) {
                parseBalance(resp, out);
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

    /** {@code DescribeAccountBalance}：Balance 为分单位整数。 */
    private static void parseBalance(JSONObject resp, Result out) {
        if (resp.has("Balance")) {
            out.add("可用余额", money(resp.opt("Balance")) + " 元");
        }
        out.add("账户状态", resp.has("RealName") ? (resp.optInt("RealName", 0) == 1 ? "已实名" : "未实名") : null);
        if (resp.has("FreezeAmount")) {
            String f = money(resp.opt("FreezeAmount"));
            if (!"0.00".equals(f)) out.add("冻结金额", f + " 元");
        }
        if (resp.has("OweAmount")) {
            String o = money(resp.opt("OweAmount"));
            if (!"0.00".equals(o)) out.add("欠费金额", o + " 元");
        }
    }

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

    /** 分单位整数 → 元，两位小数。 */
    private static String money(Object v) {
        try {
            long fen = (long) Double.parseDouble(String.valueOf(v));
            return String.format(Locale.US, "%d.%02d", fen / 100, Math.abs(fen % 100));
        } catch (Throwable t) {
            return String.valueOf(v);
        }
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
