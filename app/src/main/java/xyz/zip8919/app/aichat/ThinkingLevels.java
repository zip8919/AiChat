package xyz.zip8919.app.aichat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 思考档位的定义、排序与「落档」（把全局档位换成模型真正接受的档位）。
 *
 * <p><b>为什么不止三档</b>：各家对推理强度的取值并不统一。OpenAI 有
 * {@code minimal} 与 {@code xhigh}，DeepSeek 与 Anthropic 走 {@code max}，
 * 界面上原来只有「低 / 中 / 高」三档，探测也只探三档的话，只认 {@code max}
 * 的模型会被判成「不支持思考」，实际能用却用不上。
 *
 * <p><b>档位顺序</b>：{@link #ORDER} 从省到费：minimal &lt; low &lt; medium &lt; high
 * &lt; xhigh &lt; max。所有比较都基于这个下标，不依赖字符串。
 *
 * <p>本类全是纯函数，不碰配置、不碰网络，便于单独验证。
 */
final class ThinkingLevels {

    static final String OFF = "off";

    /** 六档，按推理强度从低到高。下标即优先级，比较一律用它。 */
    static final List<String> ORDER = Arrays.asList(
            "minimal", "low", "medium", "high", "xhigh", "max");

    /** 界面文案。键与 {@link #ORDER} 一致，加 off。 */
    private static final String[] LABELS = {Strings.get(R.string.thinkinglevels_001), Strings.get(R.string.thinkinglevels_002), Strings.get(R.string.thinkinglevels_003), Strings.get(R.string.thinkinglevels_004), Strings.get(R.string.thinkinglevels_005), Strings.get(R.string.thinkinglevels_006)};

    private ThinkingLevels() {
    }

    /** 是否为内置六档之一（不含 off）。 */
    static boolean isBuiltin(String level) {
        return ORDER.contains(level);
    }

    /** 档位下标；非内置档位返回 -1。 */
    static int indexOf(String level) {
        return level == null ? -1 : ORDER.indexOf(level.trim().toLowerCase(Locale.US));
    }

    /** 中文档位名，用于列表与结果提示。未知值原样返回。 */
    static String label(String level) {
        int i = indexOf(level);
        if (i >= 0) return LABELS[i];
        return level == null ? "" : level;
    }

    /** 把 {@code low,high} 这种存储格式拆成列表（去空、去重、按档位顺序排列）。 */
    static List<String> parse(String csv) {
        List<String> out = new ArrayList<String>();
        if (csv == null || csv.trim().length() == 0) return out;
        String[] parts = csv.split(",");
        for (String p : parts) {
            String v = p == null ? "" : p.trim().toLowerCase(Locale.US);
            if (v.length() > 0 && !out.contains(v)) out.add(v);
        }
        sort(out);
        return out;
    }

    /** 序列化为 {@code low,high}；空集合返回空串。 */
    static String join(List<String> levels) {
        if (levels == null || levels.isEmpty()) return "";
        List<String> copy = new ArrayList<String>(levels);
        sort(copy);
        StringBuilder sb = new StringBuilder();
        for (String l : copy) {
            if (l == null || l.length() == 0) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(l);
        }
        return sb.toString();
    }

    /** 升序排（省 → 费）；未知档位排在最后，保持相对顺序。 */
    static void sort(List<String> list) {
        java.util.Collections.sort(list, new java.util.Comparator<String>() {
            public int compare(String a, String b) {
                int ia = indexOf(a);
                int ib = indexOf(b);
                if (ia < 0) ia = Integer.MAX_VALUE;
                if (ib < 0) ib = Integer.MAX_VALUE;
                return ia - ib;
            }
        });
    }

    /** 中文串，如「低、高、最高」。 */
    static String describe(List<String> levels) {
        if (levels == null || levels.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String l : levels) {
            if (sb.length() > 0) sb.append('、');
            sb.append(label(l));
        }
        return sb.toString();
    }

    /**
     * 落档：把用户选的全局档位换成模型真正接受的档位。
     *
     * <p>规则（用户要求「自动落到最近可用档」）：
     * <ol>
     *   <li>全局是 off，或模型没探测过（可用集合为空）→ 原样返回，行为与旧版一致</li>
     *   <li>全局档在可用集合里 → 原样返回</li>
     *   <li>否则取「距离最近」的可用档：先看低于全局的最高档，再看高于全局的最低档，
     *       两头距离相同时选<b>低</b>的那个（省 token 且更少超时）</li>
     * </ol>
     *
     * @param global 全局档位，可能为 null / "off"
     * @param available 模型的可用档位集合，可为空
     * @return 实际要下发的档位
     */
    static String resolve(String global, List<String> available) {
        if (global == null || global.length() == 0) return global;
        if (OFF.equalsIgnoreCase(global)) return global;
        if (available == null || available.isEmpty()) return global;
        String g = global.trim().toLowerCase(Locale.US);
        if (available.contains(g)) return g;

        int gi = indexOf(g);
        if (gi < 0) {
            // 全局是自定义档位、内外都不认识：既然模型有明确的可用集合，落到它最高的那档
            return highest(available);
        }
        String below = null;
        String above = null;
        for (String l : available) {
            int li = indexOf(l);
            if (li < 0) continue;
            if (li < gi && (below == null || li > indexOf(below))) below = l;
            if (li > gi && (above == null || li < indexOf(above))) above = l;
        }
        if (below == null && above == null) return highest(available);
        if (below == null) return above;
        if (above == null) return below;
        // 距离相等时取低档
        return (gi - indexOf(below)) <= (indexOf(above) - gi) ? below : above;
    }

    /** 集合里强度最高的档位；没有可识别档位时返回集合第一项。 */
    static String highest(List<String> available) {
        if (available == null || available.isEmpty()) return "";
        String best = null;
        for (String l : available) {
            int li = indexOf(l);
            if (li < 0) continue;
            if (best == null || li > indexOf(best)) best = l;
        }
        return best == null ? available.get(0) : best;
    }

    /**
     * 档位 → {@code reasoning_effort} 取值。
     *
     * <p>六档直接同名字段下发：OpenAI 认 minimal/low/medium/high，DeepSeek 与
     * Anthropic 认 max，{@code xhigh} 在部分端点上是别名。下发原值而不是压成
     * high/max，探测才能逐档验证「这个词这个端点收不收」。
     */
    static String reasoningEffort(String level) {
        String v = level == null ? "" : level.trim().toLowerCase(Locale.US);
        if (v.length() == 0 || OFF.equals(v)) return null;
        return v;
    }

    /**
     * 档位 → {@code thinking_budget} 数值（boolean 型端点的预算写法）。
     *
     * <p>这套端点的档位本就只是「预算大小」，探测时若逐档发同一个数值，
     * 六档结果必然一致，等于没探。所以每档给一个可区分的预算，
     * 让端点自己的下限校验（如 Anthropic 要求 ≥1024）能真正体现出来。
     */
    static int budget(String level) {
        int i = indexOf(level);
        switch (i) {
            case 0: return 1024;   // minimal
            case 1: return 2048;   // low
            case 2: return 4096;   // medium（与旧版一致）
            case 3: return 8192;
            case 4: return 16384;
            case 5: return 32768;  // max（旧版 high 的预算）
            default: return 4096;
        }
    }
}
