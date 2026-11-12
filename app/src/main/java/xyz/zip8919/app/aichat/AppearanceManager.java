package xyz.zip8919.app.aichat;

import android.app.Activity;
import android.content.Context;

/**
 * 外观系统唯一门面。UI 层只跟它打交道，不直接碰磁盘。
 *
 * <p>双态：{@code working} 是内存当前态（所有控件改动、临时保存都写这里），
 * {@code committed} 是上次永久保存的磁盘快照。dirty = 两者不同。
 * 临时保存只更新 working；应用被杀后下次启动只读 committed，自动回到上次保存。
 *
 * <p>每次改动自增 {@code revision}，ThemedActivity 用它判断是否需要重刷当前页，
 * 避免每次 onResume 都无谓遍历 View 树（老设备 Cortex-A7 上要省这份开销）。
 */
public final class AppearanceManager {

    private static final String TAG = "AppearanceManager";
    private static AppearanceManager instance;

    /** P2 注入：原生 View 重刷实现。P0/P1 为 null，applyTo() 是 no-op。 */
    public interface Applier {
        void apply(Activity activity, AppearanceManager manager);
    }

    /** P2 注入：WebView 侧样式刷新（换 style 容器 / setTextZoom）。 */
    public interface ChangeListener {
        void onAppearanceChanged(AppearanceManager manager);
    }

    private AppearanceState working;
    private AppearanceState committed;
    private boolean tempApplied;
    private volatile long revision;
    /**
     * {@link #working} 里最常被<b>后台线程</b>读取的两个值的快照。
     *
     * <p>公式渲染（{@link MathRenderer}）、消息 HTML 构建都在后台线程跑，它们要读
     * 当前明暗与主题。{@code working} 不是不可变对象，写入既不原子也不保证跨线程
     * 可见 —— 切主题那一瞬间后台线程可能读到旧值，渲染出的公式/气泡就是上一个主题的
     * 配色，而且会被缓存住。表现正是「公式要重启软件才变回正常」。
     *
     * <p>这两个字段只承载「布尔 + 不可变字符串」，volatile 足够保证读到完整值。
     */
    private volatile boolean darkSnapshot;
    private volatile String themeIdSnapshot = "";
    private Applier applier;
    private ChangeListener changeListener;

    /**
     * 多页面监听。{@link #setChangeListener} 是单槽位（MainActivity 的 WebView 刷新用），
     * 这里额外维护一份列表给所有存活的 ThemedActivity —— 否则切深浅色时只有当前页
     * 因为 onResume 才重刷，其他已入栈页面要重进 APP 才生效（用户实测到的「需重进 APP」）。
     */
    private final java.util.List<java.lang.ref.WeakReference<ChangeListener>> listeners =
            new java.util.ArrayList<java.lang.ref.WeakReference<ChangeListener>>();

    private AppearanceManager() {
        AppearanceState loaded = AppearanceStore.getInstance().load();
        this.committed = loaded;
        this.working = loaded.copy();
        this.tempApplied = false;
        this.revision = 1;
        this.darkSnapshot = working.dark;
        this.themeIdSnapshot = working.themeId == null ? "" : working.themeId;
        LogUtil.i(TAG, "init: revision=%d dark=%s theme=%s percent=%d",
                revision, working.dark, working.themeId, working.displayPercent);
    }

    public static synchronized AppearanceManager getInstance(Context ctx) {
        // 进程里第一次取用时确保 StorageManager 拿到 appContext，避免无参 getInstance()
        // 在路径未初始化时创建出错误的 basePath
        if (ctx != null) StorageManager.getInstance(ctx.getApplicationContext());
        if (instance == null) instance = new AppearanceManager();
        return instance;
    }

    public static synchronized AppearanceManager getInstance() {
        if (instance == null) instance = new AppearanceManager();
        return instance;
    }

    // ---- 注入钩子 ----

    public void setApplier(Applier a) {
        this.applier = a;
    }

    public void setChangeListener(ChangeListener l) {
        this.changeListener = l;
    }

    /** 每个 ThemedActivity 在 onCreate 注册、onDestroy 注销；内部用弱引用，不怕泄漏。 */
    public void addChangeListener(ChangeListener l) {
        if (l == null) return;
        synchronized (listeners) {
            for (int i = listeners.size() - 1; i >= 0; i--) {
                ChangeListener c = listeners.get(i).get();
                if (c == null) listeners.remove(i);
                else if (c == l) return;
            }
            listeners.add(new java.lang.ref.WeakReference<ChangeListener>(l));
        }
    }

    public void removeChangeListener(ChangeListener l) {
        if (l == null) return;
        synchronized (listeners) {
            for (int i = listeners.size() - 1; i >= 0; i--) {
                ChangeListener c = listeners.get(i).get();
                if (c == null || c == l) listeners.remove(i);
            }
        }
    }

    public long getRevision() {
        return revision;
    }

    /**
     * 「会改变已渲染内容颜色」的签名：明暗 + 主题 id + 参与出图的 token。
     *
     * <p>公式是预渲染 PNG，字色在出图那一刻就画死了；{@link MessageHtmlRenderer}
     * 的 HTML 缓存又只按正文做 key。两者都必须在签名变化时整体失效，否则就是
     * 「切了深浅色，公式还是上一个主题的颜色，要重启才变」。
     *
     * <p>只放颜色项，不放显示大小/字号：那两项变化时重灌整页会白丢滚动位置。
     */
    public String colorSignature() {
        boolean dark = isDark();
        return (dark ? "d" : "l") + "|" + getThemeId()
                + "|" + getEffectiveColor(ThemeTokens.AC_TEXT, dark)
                + "|" + getEffectiveColor(ThemeTokens.AC_BG, dark)
                + "|" + getEffectiveColor(ThemeTokens.AC_SURFACE, dark)
                + "|" + getEffectiveColor(ThemeTokens.AC_CODE_BG, dark)
                + "|" + getEffectiveColor(ThemeTokens.AC_CODE_TEXT, dark);
    }

    /** 由 ThemedActivity 在 setContentView / onResume 时调用。 */
    public void applyTo(Activity activity) {
        if (applier == null) return;
        try {
            applier.apply(activity, this);
        } catch (Throwable t) {
            // 主题注入出错不能让页面崩溃，最坏情况是这一页没换色
            LogUtil.w(TAG, "applyTo failed: " + t.getMessage());
        }
    }

    private void bump() {
        revision++;
        // 任何一次外观改动都会走到这里，快照在这里统一发布：
        // 先写快照再通知监听器，保证监听器（可能触发后台渲染）读到的已经是新值。
        darkSnapshot = working.dark;
        themeIdSnapshot = working.themeId == null ? "" : working.themeId;
        if (changeListener != null) {
            try {
                changeListener.onAppearanceChanged(this);
            } catch (Throwable t) {
                LogUtil.w(TAG, "changeListener failed: " + t.getMessage());
            }
        }
        // 通知所有存活页面立刻重刷，不必等 onResume、更不必重进 APP
        java.util.List<ChangeListener> snapshot;
        synchronized (listeners) {
            snapshot = new java.util.ArrayList<ChangeListener>(listeners.size());
            for (int i = listeners.size() - 1; i >= 0; i--) {
                ChangeListener c = listeners.get(i).get();
                if (c == null) listeners.remove(i);
                else snapshot.add(c);
            }
        }
        for (int i = 0; i < snapshot.size(); i++) {
            try {
                snapshot.get(i).onAppearanceChanged(this);
            } catch (Throwable t) {
                LogUtil.w(TAG, "page listener failed: " + t.getMessage());
            }
        }
    }

    // ---- 标量 ----

    public boolean isDark() {
        return darkSnapshot;
    }

    public void setDark(boolean v) {
        if (working.dark == v) return;
        working.dark = v;
        bump();
    }

    public String getThemeId() {
        return themeIdSnapshot == null ? "" : themeIdSnapshot;
    }

    public void setThemeId(String id) {
        String v = id == null ? "" : id;
        if (v.equals(getThemeId())) return;
        working.themeId = v;
        bump();
    }

    public int getDisplayPercent() {
        return working.displayPercent;
    }

    public void setDisplayPercent(int v) {
        int n = AppearanceState.clampPercent(v);
        if (working.displayPercent == n) return;
        working.displayPercent = n;
        bump();
    }

    public int getFontSizeStep() {
        return working.fontSizeStep;
    }

    public void setFontSizeStep(int v) {
        int n = AppearanceState.clampStep(v);
        if (working.fontSizeStep == n) return;
        working.fontSizeStep = n;
        bump();
    }

    // ---- 显式覆盖 ----

    public String getExplicit(String key, boolean dark) {
        return working.explicitFor(dark).get(key);
    }

    public boolean isOverridden(String key, boolean dark) {
        String v = getExplicit(key, dark);
        return v != null && v.length() > 0;
    }

    public void setExplicit(String key, String value, boolean dark) {
        if (key == null || value == null) return;
        working.explicitFor(dark).put(key, value);
        bump();
    }

    public void clearExplicit(String key, boolean dark) {
        if (key == null) return;
        working.explicitFor(dark).remove(key);
        bump();
    }

    public void clearAllExplicit() {
        working.explicitLight.clear();
        working.explicitDark.clear();
        bump();
    }

    /**
     * 复制某一明暗套的全部显式覆盖，供逐属性编辑页做「取消 = 原样回滚」。
     *
     * <p>不能拿 {@link #discard()} 代替：那是回滚到<b>上次落盘</b>，会把用户
     * 在外观页做的一堆未保存改动一起冲掉。
     */
    public java.util.Map<String, String> snapshotExplicit(boolean dark) {
        return new java.util.HashMap<String, String>(working.explicitFor(dark));
    }

    /** 用快照整体替换某一明暗套的显式覆盖（取消编辑时回滚）。 */
    public void restoreExplicit(boolean dark, java.util.Map<String, String> snapshot) {
        java.util.Map<String, String> m = working.explicitFor(dark);
        m.clear();
        if (snapshot != null) m.putAll(snapshot);
        bump();
    }

    // ---- 三层覆盖判定 ----

    /**
     * V(X) = EXPLICIT ? user[X] : (theme defines X ? theme[X] : builtin[X])。
     * P0 阶段主题层未接入（themeValue 返回 null），等价于「显式 > 内置默认」，
     * 在无主题时行为完全正确；P1 只需把 themeValue() 接到 ThemeResolver。
     */
    public String getEffective(String key, boolean dark) {
        String explicit = getExplicit(key, dark);
        if (explicit != null && explicit.length() > 0) return explicit;
        String theme = themeValue(key, dark);
        if (theme != null && theme.length() > 0) return theme;
        return builtinValue(key, dark);
    }

    public String getEffective(String key) {
        return getEffective(key, working.dark);
    }

    public int getEffectiveColor(String token, boolean dark) {
        String v = getEffective(token, dark);
        Integer c = ThemeTokens.parseColor(v);
        return c == null ? ThemeTokens.defaultColor(token, dark) : c.intValue();
    }

    public int getEffectiveColor(String token) {
        return getEffectiveColor(token, working.dark);
    }

    private String builtinValue(String key, boolean dark) {
        if (ThemeTokens.isValid(key)) return ThemeTokens.defaultHex(key, dark);
        if (ThemeTokens.FONT_UI.equals(key) || ThemeTokens.FONT_MONO.equals(key)) return "system";
        return null;
    }

    /** 从当前选中的自定义主题里取该 key 在指定明暗下的值；未选主题 / 未定义返回 null。 */
    private String themeValue(String key, boolean dark) {
        return ThemeResolver.themeValue(working.themeId, key, dark);
    }

    /** 当前选中主题的名称，供 UI 显示「主题为 X」；无主题返回 null。 */
    public String getActiveThemeName() {
        return ThemeResolver.themeName(working.themeId);
    }

    /** UI 标注「已覆盖」用：返回该 key 的完整判定结果。 */
    public ThemeResolver.Resolved resolve(String key, boolean dark) {
        return ThemeResolver.resolve(this, key, dark);
    }

    // ---- 状态机 ----

    /**
     * 是否处于「内置默认外观」：100% / 标准档 / 浅色 / 无主题 / 无显式覆盖 / 系统字体。
     * ThemeApplier 用它做零回归短路——默认态且从未定制过时，一行都不碰现有 View。
     */
    public boolean isAppearanceDefault() {
        return working.displayPercent == AppearanceState.DEFAULT_PERCENT
                && working.fontSizeStep == AppearanceState.DEFAULT_STEP
                && !working.dark
                && getThemeId().length() == 0
                && working.explicitLight.isEmpty()
                && working.explicitDark.isEmpty()
                && "system".equals(getEffective(ThemeTokens.FONT_UI))
                && "system".equals(getEffective(ThemeTokens.FONT_MONO));
    }

    /**
     * 颜色是否处于「完全内置默认」：浅色、无主题、无任何显式颜色覆盖。
     *
     * <p>与 {@link #isAppearanceDefault()} 的区别是<b>忽略显示大小与字体</b>。
     * 列表底色、下拉文字这类「按 token 反查」的位置必须用它判断：只改了显示大小时
     * 若按 token 重刷，{@code #F0F0F0}、{@code #FFF8E1} 这些近似色会被拉齐成纯色，
     * 属于肉眼可见的回归。
     */
    public boolean isColorDefault() {
        return !working.dark
                && getThemeId().length() == 0
                && working.explicitLight.isEmpty();
    }

    public boolean isDirty() {
        return !working.sameAs(committed);
    }

    public boolean isTempApplied() {
        return tempApplied;
    }

    public boolean isClean() {
        return !isDirty() && !tempApplied;
    }

    /** 临时保存：只留在内存，磁盘不动。 */
    public void applyTemp() {
        tempApplied = true;
        bump();
        LogUtil.i(TAG, "applyTemp: memory only");
    }

    /** 永久保存：内存态拷为快照 + 异步原子写盘。 */
    public void applyPermanent() {
        committed = working.copy();
        tempApplied = false;
        AppearanceStore.getInstance().save(committed);
        bump();
        LogUtil.i(TAG, "applyPermanent: saved to disk");
    }

    /** 放弃改动，回到上次永久保存的值。 */
    public void discard() {
        working = committed.copy();
        tempApplied = false;
        bump();
        LogUtil.i(TAG, "discard: reverted to committed");
    }

    /** 重置到内置默认；不删字体/主题文件；状态为 DIRTY，仍需点永久保存才写盘。 */
    public void reset() {
        working = AppearanceState.defaults();
        tempApplied = false;
        bump();
        LogUtil.i(TAG, "reset: to builtin defaults (still dirty)");
    }

    /** 进程冷启动后调用：把工作态对齐到磁盘快照（正常由构造器完成，这里供测试/重启恢复）。 */
    public void reloadFromDisk() {
        AppearanceState loaded = AppearanceStore.getInstance().load();
        committed = loaded;
        working = loaded.copy();
        tempApplied = false;
        bump();
    }
}
