package xyz.zip8919.app.aichat;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 系统提示词预设的仓库。
 *
 * <p>数据落在设置页同一个 SharedPreferences（{@code system_presets}，JSON 数组），
 * 保持与老版本完全同构 —— 升级后原有预设原样可用，回退旧版本也不会读到看不懂的结构。
 *
 * <p>单独抽出来是因为现在有三个地方动这份数据：设置页（保存当前 / 选择）、
 * 预设管理页（新建 / 修改 / 删除）、以后可能还有导入导出。各自解析一遍 JSON
 * 迟早出现「一处写进去、另一处看不见」。
 */
final class PresetStore {

    /** 与 SettingsActivity.PREFS_NAME / KEY_PRESETS 保持一致，改一处必须改两处。 */
    static final String PREFS_NAME = "aichat_settings";
    static final String KEY_PRESETS = "system_presets";
    static final int MAX_PRESETS = 20;

    /** [0]=名称 [1]=提示词内容。用两条数组而不用 Map，是因为要保留用户排序。 */
    static final class Preset {
        String name;
        String prompt;

        Preset(String name, String prompt) {
            this.name = name;
            this.prompt = prompt;
        }
    }

    private PresetStore() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, 0);
    }

    static List<Preset> load(Context ctx) {
        List<Preset> out = new ArrayList<Preset>();
        try {
            String json = prefs(ctx).getString(KEY_PRESETS, "[]");
            JSONArray arr = new JSONArray(json == null || json.length() == 0 ? "[]" : json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Preset(o.optString("name", ""), o.optString("prompt", "")));
            }
        } catch (Exception e) {
            LogUtil.e("PresetStore", "load failed: " + e.getMessage(), e);
            return new ArrayList<Preset>();
        }
        return out;
    }

    static boolean save(Context ctx, List<Preset> list) {
        try {
            JSONArray arr = new JSONArray();
            for (Preset p : list) {
                JSONObject o = new JSONObject();
                o.put("name", p.name == null ? "" : p.name);
                o.put("prompt", p.prompt == null ? "" : p.prompt);
                arr.put(o);
            }
            return prefs(ctx).edit().putString(KEY_PRESETS, arr.toString()).commit();
        } catch (Exception e) {
            LogUtil.e("PresetStore", "save failed: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 「新预设」→「新预设2」→「新预设3」…：重名时自动往后找第一个空位。
     *
     * <p>不写成「新预设1」是因为编号从 2 开始更符合中文习惯（第一个就是名字本身）。
     */
    static String nextName(Context ctx) {
        return nextName(load(ctx), Strings.get(R.string.presetstore_001));
    }

    static String nextName(List<Preset> list, String base) {
        String b = base == null || base.trim().length() == 0 ? Strings.get(R.string.presetstore_002) : base.trim();
        if (!contains(list, b, null)) return b;
        for (int n = 2; n < 10000; n++) {
            String cand = b + n;
            if (!contains(list, cand, null)) return cand;
        }
        return b + System.currentTimeMillis();
    }

    /** 是否重名；{@code self} 是编辑态的自己，不算重名。 */
    static boolean contains(List<Preset> list, String name, Preset self) {
        if (name == null) return false;
        String n = name.trim();
        for (Preset p : list) {
            if (p == self) continue;
            if (n.equals(p.name == null ? "" : p.name.trim())) return true;
        }
        return false;
    }

    static int indexOf(List<Preset> list, Preset target) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == target) return i;
        }
        return -1;
    }
}
