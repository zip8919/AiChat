package xyz.zip8919.app.aichat;

import android.os.Bundle;
import android.view.View;
import android.webkit.WebView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * F5 主题预览页（交互方案 8、实现方案 8.5）。
 *
 * <p>用真实组件样例而非截图；只用 1 个 WebView 实例展示气泡与代码块，
 * 放在 ScrollView 之外避免嵌套滚动。切换浅/深与「当前主题/内置默认」时
 * 按 token 重新设色，预览页没有流式输出，重灌 WebView 是安全的。
 */
public class ThemePreviewActivity extends ThemedActivity {

    private ThemeRepository repo;
    private AppearanceManager am;
    private String themeId;
    private boolean darkMode;
    private boolean useTheme = true;

    private View root;
    private ScrollView scroll;
    private TextView title, cardTitle, cardText, bubbleUser, bubbleAi, code, info;
    private LinearLayout card;
    private Button btnMode, btnSource, btnNormal, btnPrimary, btnApply, btnBack;
    private EditText input;
    private Spinner spinner;
    private Switch switchView;
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_theme_preview);
        repo = ThemeRepository.getInstance();
        am = AppearanceManager.getInstance(this);
        String extra = getIntent().getStringExtra("theme_id");
        themeId = (extra == null || extra.length() == 0) ? am.getThemeId() : extra;
        darkMode = am.isDark();
        bindViews();
        spinner.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"Spinner 选中态"}));
        int percent = getIntent().getIntExtra("percent", 100);
        if (percent != 100) {
            title.setText("主题预览 · 显示 " + percent + "%");
        }
        wire();
        render();
    }

    private void bindViews() {
        root = findViewById(R.id.pv_root);
        // 预览页要能展示「与当前外观相反」的浅/深与主题配色，整块样例由 render()
        // 自行设色；若交给 Applier 重刷，onResume 会把预览结果覆盖掉。
        root.setTag(R.id.ac_no_recolor, Boolean.TRUE);
        scroll = (ScrollView) findViewById(R.id.pv_scroll);
        title = (TextView) findViewById(R.id.pv_title);
        card = (LinearLayout) findViewById(R.id.pv_card);
        cardTitle = (TextView) findViewById(R.id.pv_card_title);
        cardText = (TextView) findViewById(R.id.pv_card_text);
        bubbleUser = (TextView) findViewById(R.id.pv_bubble_user);
        bubbleAi = (TextView) findViewById(R.id.pv_bubble_ai);
        code = (TextView) findViewById(R.id.pv_code);
        info = (TextView) findViewById(R.id.pv_info);
        btnMode = (Button) findViewById(R.id.btn_toggle_mode);
        btnSource = (Button) findViewById(R.id.btn_toggle_source);
        btnNormal = (Button) findViewById(R.id.pv_btn_normal);
        btnPrimary = (Button) findViewById(R.id.pv_btn_primary);
        btnApply = (Button) findViewById(R.id.btn_apply);
        btnBack = (Button) findViewById(R.id.btn_back);
        input = (EditText) findViewById(R.id.pv_input);
        spinner = (Spinner) findViewById(R.id.pv_spinner);
        switchView = (Switch) findViewById(R.id.pv_switch);
        webView = (WebView) findViewById(R.id.pv_webview);
    }

    private void wire() {
        btnMode.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                darkMode = !darkMode;
                render();
            }
        });
        btnSource.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                useTheme = !useTheme;
                render();
            }
        });
        btnApply.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (themeId == null || themeId.length() == 0) {
                    toast("当前没有自定义主题可应用");
                    return;
                }
                am.setThemeId(themeId);
                toast("已应用，返回外观页后点保存才会写盘");
                finish();
            }
        });
        btnBack.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
    }

    /** 三层覆盖在这里只需要「主题 → 内置」两级：预览不叠加用户显式覆盖，避免误判效果。 */
    private int getToken(String token) {
        if (useTheme && themeId != null && themeId.length() > 0) {
            String v = repo.themeValue(themeId, token, darkMode);
            Integer c = ThemeTokens.parseColor(v);
            if (c != null) return c.intValue();
        }
        return ThemeTokens.defaultColor(token, darkMode);
    }

    private void render() {
        btnMode.setText(darkMode ? "深色 ⇄" : "浅色 ⇄");
        btnSource.setText(useTheme ? "当前主题 ⇄" : "内置默认 ⇄");

        int bg = getToken(ThemeTokens.AC_BG);
        int surface = getToken(ThemeTokens.AC_SURFACE);
        int text = getToken(ThemeTokens.AC_TEXT);
        int text2 = getToken(ThemeTokens.AC_TEXT_SECONDARY);
        int primary = getToken(ThemeTokens.AC_PRIMARY);
        int userBubble = getToken(ThemeTokens.AC_BUBBLE_USER);
        int aiBubble = getToken(ThemeTokens.AC_BUBBLE_AI);
        int codeBg = getToken(ThemeTokens.AC_CODE_BG);
        int codeText = getToken(ThemeTokens.AC_CODE_TEXT);
        int link = getToken(ThemeTokens.AC_LINK);

        root.setBackgroundColor(bg);
        scroll.setBackgroundColor(bg);
        title.setTextColor(text);
        card.setBackgroundColor(surface);
        cardTitle.setTextColor(text);
        cardText.setTextColor(text2);
        btnNormal.setBackgroundColor(surface);
        btnNormal.setTextColor(text);
        btnPrimary.setBackgroundColor(primary);
        btnPrimary.setTextColor(0xFFFFFFFF);
        input.setBackgroundColor(surface);
        input.setTextColor(text);
        spinner.setBackgroundColor(surface);
        switchView.setTextColor(text);
        bubbleUser.setBackgroundColor(userBubble);
        bubbleUser.setTextColor(text);
        bubbleAi.setBackgroundColor(aiBubble);
        bubbleAi.setTextColor(text);
        code.setBackgroundColor(codeBg);
        code.setTextColor(codeText);
        info.setTextColor(text2);

        renderWebView(bg, text, userBubble, codeBg, codeText, link);
    }

    private void renderWebView(int bg, int text, int bubble, int codeBg, int codeText, int link) {
        String html = "<html><head>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>"
                + "body{background:" + ThemeCssBuilder.hexOf(bg) + ";color:"
                + ThemeCssBuilder.hexOf(text) + ";font-size:12px;padding:8px;margin:0;}"
                + ".b{background:" + ThemeCssBuilder.hexOf(bubble) + ";padding:6px;margin:4px 0;"
                + "border-radius:4px;color:" + ThemeCssBuilder.hexOf(text) + ";}"
                + "pre{background:" + ThemeCssBuilder.hexOf(codeBg) + ";color:"
                + ThemeCssBuilder.hexOf(codeText) + ";padding:6px;white-space:pre-wrap;word-wrap:break-word;}"
                + "a{color:" + ThemeCssBuilder.hexOf(link) + ";}"
                + "</style></head><body>"
                + "<div class='b'>用户：消息气泡在 WebView 里的样子</div>"
                + "<div>AI：这是一段回复，含 <a href='#'>链接</a> 与代码块。</div>"
                + "<pre>val x = 1  // 代码块样例</pre>"
                + "</body></html>";
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    private void toast(String s) {
        AcToast.show(this, s);
    }
}
