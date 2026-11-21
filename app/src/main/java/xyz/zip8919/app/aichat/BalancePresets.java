package xyz.zip8919.app.aichat;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 余额查询的平台适配：每个平台一条路径或一套签名规则 + 一套解析规则。
 *
 * <p>为什么不能统一：余额接口从来不是标准 OpenAI 协议的一部分，各家返回结构完全不同。
 * DeepSeek 是 {@code {is_available, balance_infos:[{currency,total_balance,...}]}}；
 * 一些中转站干脆返回 {@code {"data":{"quota":123456}}}，单位还是「点」而不是「元」。
 * 用一个「万能解析」去猜字段名，结果就是余额显示成 0。
 *
 * <p>所以做成：<b>预设</b>（已知平台，路径与解析都预置好）+ <b>通用兜底</b>
 * （按常见字段名依次找，找到就显示）。用户在设置页选平台即可，也能自己填路径。
 *
 * <p>腾讯云是唯一一个「不查余额接口、改走云 API 签名」的平台：它的推理地址
 * 按 API Key 查不到余额，账户余额与套餐余量只能用 SecretId/SecretKey 调
 * {@code billing} / {@code tokenhub} 云接口，见 {@link TencentCloudApi}。
 */
final class BalancePresets {

    private static final String TAG = "BalancePresets";

    /** 走云 API 签名的预设 id；提供商设置页据此显示签名相关字段。 */
    static final String ID_TENCENT = "tencent_cloud";

    /** 一个平台预设。 */
    static final class Preset {
        final String id;
        final String label;
        /** 余额接口路径，相对 Base URL。 */
        final String path;
        /** 该平台的说明，显示在设置页下方。 */
        final String hint;

        Preset(String id, String label, String path, String hint) {
            this.id = id;
            this.label = label;
            this.path = path;
            this.hint = hint;
        }

        /** 用云 API 签名查询，不需要余额接口路径。 */
        boolean signed() {
            return ID_TENCENT.equals(id);
        }
    }

    /**
     * 内置预设。第一项必须保持为 {@code deepseek}：
     * 历史上余额查询只支持 DeepSeek，老配置迁移时默认落到它。
     */
    private static final Preset[] BUILTIN = {
            new Preset("deepseek", "DeepSeek 官方", "/user/balance",
                    "返回总余额、赠金余额与充值余额"),
            // 旧配置里存的是推理地址的余额路径，那条路恒为 404；
            // 现在改为用 SecretId/SecretKey 调云 API 取真实余额。
            new Preset(ID_TENCENT, "腾讯云（余额 / 用量 / Token Plan）", "",
                    "用 SecretId 与 SecretKey 查询账户可用余额、TokenHub 用量与计费方式、"
                            + "Token Plan 套餐额度；长按「查询余额」可查看各接口的诊断信息"),
            new Preset("openai_compat", "通用 OpenAI 兼容平台", "/dashboard/billing/credit_grants",
                    "适用于大多数中转站的额度接口"),
            new Preset("siliconflow", "硅基流动 SiliconFlow", "/v1/user/info",
                    "返回账户余额（单位：元）"),
            new Preset("custom", "自定义", "",
                    "自己填写余额接口路径，按常见字段自动解析"),
    };

    private BalancePresets() {
    }

    static Preset[] all() {
        return BUILTIN;
    }

    static Preset byId(String id) {
        if (id == null || id.length() == 0) return BUILTIN[0];
        for (Preset p : BUILTIN) {
            if (p.id.equals(id)) return p;
        }
        // 1.21.12 及以前的「腾讯云 TokenHub」预设 id：升级后自动切到能查真实余额的通道，
        // 否则老配置还在查一个不存在的接口
        if ("tencent_tokenhub".equals(id)) return byIdOf(ID_TENCENT);
        return BUILTIN[0];
    }

    private static Preset byIdOf(String id) {
        for (Preset p : BUILTIN) {
            if (p.id.equals(id)) return p;
        }
        return BUILTIN[0];
    }

    /** 平台选择下拉里显示的文字（顺序与 all() 一致）。 */
    static String[] labels() {
        String[] out = new String[BUILTIN.length];
        for (int i = 0; i < BUILTIN.length; i++) out[i] = BUILTIN[i].label;
        return out;
    }

    static int indexOf(String id) {
        if (id != null) {
            for (int i = 0; i < BUILTIN.length; i++) {
                if (BUILTIN[i].id.equals(id)) return i;
            }
            if ("tencent_tokenhub".equals(id)) return indexOf(ID_TENCENT);
        }
        return 0;
    }

    /** 解析结果：成功时 fields 有内容，失败时 error 说明原因。 */
    static final class Result {
        boolean ok;
        /** 已格式化的「名称：值」行，直接可显示。 */
        final List<String[]> fields = new ArrayList<String[]>();
        String error;
        /** 补充说明，显示在字段下方。 */
        String note;
        /** 原始响应，供「查看原 JSON」使用。 */
        String raw;

        void add(String name, String value) {
            if (value == null) return;
            String v = value.trim();
            if (v.length() == 0) return;
            fields.add(new String[] { name, v });
        }

        /** 纯文本形式，用于复制/日志。 */
        String asText() {
            if (!ok) return error == null ? "" : error;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < fields.size(); i++) {
                if (i > 0) sb.append('\n');
                sb.append(fields.get(i)[0]).append("：").append(fields.get(i)[1]);
            }
            return sb.toString();
        }
    }

    /**
     * 解析余额响应。
     *
     * <p>先按预设走专用解析，再用通用规则补齐/兜底。任何一步失败都不抛异常——
     * 余额页要能显示「解析失败 + 原始 JSON」，让用户自己判断是接口不对、
     * 还是这个平台压根不提供余额查询。
     */
    static Result parse(String presetId, String body) {
        Result r = new Result();
        r.raw = body;
        if (body == null || body.trim().length() == 0) {
            r.error = "响应为空";
            return r;
        }
        JSONObject json = null;
        try {
            json = new JSONObject(ApiClient.sanitizeJson(body));
        } catch (Throwable t) {
            LogUtil.w(TAG, "parse: not a JSON object: %s", t);
        }

        if (json != null) {
            // 空串也要当 deepseek：老配置保存下来的 balance_preset 是 ""，
            // 只判 null 会让它一路落到通用兜底 —— 而通用兜底只在顶层找字段，
            // DeepSeek 的余额在 balance_infos 数组里，于是一条都解析不出来，
            // 界面上只剩「账户状态：可用」这一行。
            String pid = (presetId == null || presetId.trim().length() == 0)
                    ? "deepseek" : presetId.trim();
            if (!applyPreset(pid, json, r)) {
                // 预设选错 / 用户把别家的 key 填在「自定义」里：按返回结构认一遍。
                // 顺序按「结构最独特」排，DeepSeek 的 balance_infos 不会误命中别家。
                String[] order = { "deepseek", "siliconflow", "openai_compat" };
                for (int i = 0; i < order.length; i++) {
                    if (order[i].equals(pid)) continue;
                    if (applyPreset(order[i], json, r)) break;
                }
            }
            // 各家都不认识时走通用兜底：自定义平台与结构有变化的老平台都靠它
            if (r.fields.isEmpty()) parseGeneric(json, r);
        }

        if (r.fields.isEmpty()) {
            // 一个字段都没解析出来：给出可读的原因，而不是空白的对话框
            String err = json == null ? "返回内容不是 JSON" : "未能从返回内容中识别出余额字段";
            String msg = json == null ? null : firstErrorMessage(json);
            r.error = msg != null ? (err + "：" + msg) : err;
            r.ok = false;
        } else {
            r.ok = true;
        }
        return r;
    }

    /** 服务端在错误体里给的提示（{"error":{"message":"..."}} 之类）。 */
    private static String firstErrorMessage(JSONObject json) {
        try {
            JSONObject err = json.optJSONObject("error");
            if (err != null) {
                String m = err.optString("message", "");
                if (m.length() > 0) return m;
                m = err.optString("msg", "");
                if (m.length() > 0) return m;
            }
            String m = json.optString("message", "");
            if (m.length() > 0) return m;
            m = json.optString("msg", "");
            if (m.length() > 0) return m;
        } catch (Throwable ignored) {
            // 错误体结构不固定，取不到就退回通用文案
        }
        return null;
    }

    /** 按预设 id 跑一遍专用解析；返回是否解析出了字段。 */
    private static boolean applyPreset(String id, JSONObject json, Result r) {
        int before = r.fields.size();
        if ("deepseek".equals(id)) parseDeepSeek(json, r);
        else if ("siliconflow".equals(id)) parseSiliconFlow(json, r);
        else if ("openai_compat".equals(id)) parseOpenAiCompat(json, r);
        return r.fields.size() > before;
    }

    // ---- 各平台解析 ----

    /** DeepSeek：{@code {is_available, balance_infos:[{currency,total_balance,topped_up_balance,granted_balance}]}} */
    private static void parseDeepSeek(JSONObject json, Result r) {
        if (json.has("is_available")) {
            r.add("账户状态", json.optBoolean("is_available", false) ? "可用" : "不可用");
        }
        JSONArray infos = json.optJSONArray("balance_infos");
        if (infos == null) {
            // 个别中转站把它写成单个对象而不是数组，同结构照收
            JSONObject only = json.optJSONObject("balance_infos");
            if (only != null) {
                infos = new JSONArray();
                infos.put(only);
            }
        }
        if (infos == null || infos.length() == 0) return;
        for (int i = 0; i < infos.length(); i++) {
            JSONObject info = infos.optJSONObject(i);
            if (info == null) continue;
            String cur = info.optString("currency", "CNY");
            String suffix = infos.length() > 1 ? ("（" + cur + "）") : "";
            // 用 pick 而不是 optString：金额有时是数字，optString 会得到 "110.0"
            r.add("总余额" + suffix, pick(info, "total_balance", "total_balance_cny"));
            r.add("赠金余额" + suffix, pick(info, "granted_balance"));
            r.add("充值余额" + suffix, pick(info, "topped_up_balance"));
        }
    }

    /** 硅基流动：{@code {data:{balance, chargeBalance, totalBalance}}} */
    private static void parseSiliconFlow(JSONObject json, Result r) {
        JSONObject data = json.optJSONObject("data");
        JSONObject src = data != null ? data : json;
        r.add("账户余额", pick(src, "balance", "totalBalance", "chargeBalance"));
        r.add("赠送余额", pick(src, "chargeBalance"));
        r.add("账户状态", pick(src, "status"));
    }

    /** 通用额度接口：{@code {total_granted, total_used, total_available}} 或 {@code {data:{quota}}} */
    private static void parseOpenAiCompat(JSONObject json, Result r) {
        JSONObject data = json.optJSONObject("data");
        JSONObject src = data != null ? data : json;
        r.add("总额度", pick(src, "total_granted", "total_quota", "total"));
        r.add("已用额度", pick(src, "total_used", "used"));
        r.add("剩余额度", pick(src, "total_available", "remaining", "quota", "balance"));
    }

    /**
     * 通用兜底：按常见字段名在两层结构里找。
     *
     * <p>只在一层嵌套内找（顶层 + 常见的 data/result/account 子对象），
     * 不做递归——余额响应里还会有 usage 明细，递归下去会把「本月已用 12.3」
     * 当成余额显示出来，比不显示更误导。
     */
    private static void parseGeneric(JSONObject json, Result r) {
        JSONObject[] scopes = scopesOf(json);
        String bal = pickIn(scopes, "balance", "total_balance", "remaining_balance", "available_balance",
                "total_available", "remain_quota", "remaining_quota", "quota", "credit", "credits");
        if (bal != null) r.add("账户余额", bal);
        String used = pickIn(scopes, "used_balance", "total_used", "used_quota", "used");
        if (used != null) r.add("已用额度", used);
        String granted = pickIn(scopes, "granted_balance", "free_balance", "gift_balance", "granted");
        if (granted != null) r.add("赠送余额", granted);
        String cur = pickIn(scopes, "currency", "unit");
        if (cur != null) r.add("币种", cur);
        String status = pickIn(scopes, "is_available", "status", "state");
        if (status != null) r.add("账户状态", status);
    }

    private static JSONObject[] scopesOf(JSONObject json) {
        List<JSONObject> list = new ArrayList<JSONObject>();
        list.add(json);
        // Response 是腾讯云云 API 的固定外壳，有的网关会原样透传；
        // 不认它就只能看见一个空对象。
        String[] keys = { "data", "result", "account", "billing", "info", "Response" };
        for (String k : keys) {
            JSONObject sub = json.optJSONObject(k);
            if (sub != null) list.add(sub);
        }
        return list.toArray(new JSONObject[0]);
    }

    private static String pickIn(JSONObject[] scopes, String... names) {
        for (JSONObject s : scopes) {
            String v = pick(s, names);
            if (v != null) return v;
        }
        return null;
    }

    /**
     * 取第一个存在且非空的字段，按原样转成字符串。
     *
     * <p>先精确匹配，再忽略大小写找一遍：腾讯云云 API 的字段是 {@code Total} /
     * {@code Used} 这种大驼峰，按小写名永远匹配不到。
     *
     * <p>数字统一去掉多余的 {@code .0}：额度多是整数，直接 {@code optString}
     * 会得到 {@code 12345.0}，看着像精度错乱。
     */
    private static String pick(JSONObject json, String... names) {
        if (json == null) return null;
        for (String n : names) {
            if (!json.has(n) || json.isNull(n)) continue;
            String s = stringOf(json.opt(n));
            if (s != null) return s;
        }
        java.util.Iterator<String> it = json.keys();
        while (it.hasNext()) {
            String k = it.next();
            for (String n : names) {
                if (!k.equalsIgnoreCase(n) || json.isNull(k)) continue;
                String s = stringOf(json.opt(k));
                if (s != null) return s;
            }
        }
        return null;
    }

    /** 把任意 JSON 值转成展示用的字符串；空值返回 null。 */
    private static String stringOf(Object v) {
        if (v == null) return null;
        String s;
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                s = String.valueOf((long) d);
            } else {
                s = String.valueOf(d);
            }
        } else if (v instanceof Boolean) {
            s = ((Boolean) v) ? "可用" : "不可用";
        } else {
            s = String.valueOf(v);
        }
        return s.trim().length() > 0 ? s.trim() : null;
    }
}
