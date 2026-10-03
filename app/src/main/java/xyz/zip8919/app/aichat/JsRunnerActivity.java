package xyz.zip8919.app.aichat;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.regex.Matcher;

/**
 * Runs a JavaScript snippet inside a WebView (Chromium 30 on API 19) and shows
 * the console output. Also used as the live preview surface for html / svg code
 * blocks handed over from the conversation WebView.
 */
public class JsRunnerActivity extends Activity {

    private static final String TAG = "JsRunnerActivity";

    static final String EXTRA_CODE = "js_code";
    static final String EXTRA_LANG = "js_lang";

    private WebView webView;
    private EditText codeInput;
    private TextView outputView;
    private String lang = "js";
    private final StringBuilder output = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_js_runner);
        LogUtil.i(TAG, "onCreate");

        webView = (WebView) findViewById(R.id.js_webview);
        codeInput = (EditText) findViewById(R.id.js_code_input);
        outputView = (TextView) findViewById(R.id.js_output);

        Intent intent = getIntent();
        String code = intent == null ? null : intent.getStringExtra(EXTRA_CODE);
        String extraLang = intent == null ? null : intent.getStringExtra(EXTRA_LANG);
        if (extraLang != null && !extraLang.isEmpty()) {
            lang = extraLang.toLowerCase();
        }
        if (code == null) {
            code = "";
        }
        codeInput.setText(code);
        codeInput.setSelection(code.length());
        LogUtil.i(TAG, "onCreate: lang=%s codeLen=%d", lang, code.length());

        Button back = (Button) findViewById(R.id.js_back_button);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });

        Button runBtn = (Button) findViewById(R.id.js_run_button);
        runBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { runCurrent(); }
        });

        // The parent ScrollView (js_output_scroll) already scrolls the output,
        // and ScrollingMovementMethod would replace the movement method that
        // setTextIsSelectable() installs, which silently disables text
        // selection (long-press showed no handles).
        outputView.setTextIsSelectable(true);

        Button clearCodeBtn = (Button) findViewById(R.id.js_clear_code_button);
        clearCodeBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { codeInput.setText(""); }
        });

        Button clearOutBtn = (Button) findViewById(R.id.js_clear_output_button);
        clearOutBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { output.setLength(0); outputView.setText(""); }
        });

        Button copyBtn = (Button) findViewById(R.id.js_copy_button);
        copyBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { copyOutput(); }
        });

        Button pasteBtn = (Button) findViewById(R.id.js_paste_button);
        pasteBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pasteIntoInput(); }
        });

        Button saveBtn = (Button) findViewById(R.id.js_save_button);
        saveBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { saveToExports(); }
        });

        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.setBackgroundColor(0xFFFFFFFF);
        webView.addJavascriptInterface(new ConsoleBridge(), "Console");
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage msg) {
                if (msg != null) {
                    appendOutput(msg.message());
                }
                return true;
            }
        });

        if (!code.isEmpty()) {
            runCurrent();
        } else {
            outputView.setText("（等待运行）");
        }
    }

    private void appendOutput(final String line) {
        runOnUiThread(new Runnable() {
            public void run() {
                if (output.length() > 0) {
                    output.append('\n');
                }
                output.append(line);
                trimOutput();
                outputView.setText(output.toString());
            }
        });
    }

    /**
     * 限制输出缓冲：超过 65536 字符时删除开头降到约 32768（尽量按行界对齐），
     * 超过 1000 行时删除最前面的行至 1000 行内，始终保留最近输出。
     */
    private void trimOutput() {
        if (output.length() > 65536) {
            int cut = output.length() - 32768;
            int nl = output.indexOf("\n", cut);
            if (nl != -1 && nl < output.length() - 1) {
                cut = nl + 1;
            }
            output.delete(0, cut);
        }
        int lines = 1;
        for (int i = 0; i < output.length(); i++) {
            if (output.charAt(i) == '\n') {
                lines++;
            }
        }
        if (lines > 1000) {
            int skip = lines - 1000;
            int cut = 0;
            for (int i = 0; i < output.length() && skip > 0; i++) {
                if (output.charAt(i) == '\n') {
                    cut = i + 1;
                    skip--;
                }
            }
            if (cut > 0) {
                output.delete(0, cut);
            }
        }
    }

    private void runCurrent() {
        final String code = codeInput.getText().toString();
        if (code.trim().isEmpty()) {
            Toast.makeText(this, "请输入要运行的代码", Toast.LENGTH_SHORT).show();
            return;
        }
        output.setLength(0);
        outputView.setText("");
        LogUtil.i(TAG, "run: lang=%s len=%d", lang, code.length());

        if ("html".equals(lang) || "svg".equals(lang)) {
            webView.loadDataWithBaseURL(null, code, "text/html", "UTF-8", null);
            return;
        }

        String html = buildRunnerHtml(code);
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    private String buildRunnerHtml(String userCode) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\">")
          .append("<style>html,body{margin:0;padding:0;background:#fff;}</style></head><body>")
          .append("<script>")
          .append("function __log(o){try{var s;")
          .append("if(o===undefined){s='undefined';}")
          .append("else if(o===null){s='null';}")
          .append("else if(typeof o==='object'){try{s=JSON.stringify(o);}catch(e){s=String(o);}}")
          .append("else{s=String(o);}")
          .append("if(window.Console&&window.Console.log)window.Console.log(s);")
          .append("}catch(e){}}")
          .append("function alert(m){if(window.Console&&window.Console.log)window.Console.log('alert: '+m);}")
          .append("console={log:__log,info:__log,warn:__log,error:__log,debug:__log};")
          .append("</script>")
          .append("<script>try{").append(escapeScriptEnd(userCode))
          .append("\n}catch(e){__log('ERROR: '+e);}</script>")
          .append("</body></html>");
        return sb.toString();
    }

    /**
     * 用户代码里的 "</script"（不分大小写）会提前结束包装脚本块，转义成 "<\/script"
     * ——在 JS 字符串/正则里与原文完全等价，HTML 解析器却不再视为结束标签。
     */
    private static String escapeScriptEnd(String code) {
        return code.replaceAll("(?i)</script", Matcher.quoteReplacement("<\\/script"));
    }

    private void copyOutput() {
        String text = outputView.getText().toString();
        if (text.isEmpty()) {
            Toast.makeText(this, "没有输出可复制", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("aichat", text));
            Toast.makeText(this, "已复制输出", Toast.LENGTH_SHORT).show();
        }
    }

    private void pasteIntoInput() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null
                || cm.getPrimaryClip().getItemCount() == 0) {
            Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence text = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
        String s = text == null ? "" : text.toString();
        codeInput.setText(s);
        codeInput.setSelection(s.length());
    }

    private void saveToExports() {
        String code = codeInput.getText().toString();
        if (code.trim().isEmpty()) {
            Toast.makeText(this, "代码内容为空，无法保存", Toast.LENGTH_SHORT).show();
            return;
        }
        // 复用导出扩展名映射：不再把 html/svg 之外的语言（py/go/md…）一律写成 .js
        String ext = MainActivity.exportExtForLang(lang);
        String name = "code_" + System.currentTimeMillis() + "." + ext;
        String path = StorageManager.getInstance().saveExport(name, code);
        LogUtil.i(TAG, "saveToExports: lang=%s path=%s", lang, path);
        Toast.makeText(this, path == null ? "保存失败" : ("已保存到 " + path),
                Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        // 释放 WebView 与注入的 JS 接口，否则每次进出都泄漏一个 WebView + ConsoleBridge（持有本 Activity）
        if (webView != null) {
            try {
                webView.removeJavascriptInterface("Console");
                webView.stopLoading();
                webView.destroy();
            } catch (Throwable t) {
                LogUtil.w(TAG, "onDestroy: webView release failed: %s", t);
            }
            webView = null;
        }
        super.onDestroy();
    }

    private class ConsoleBridge {
        @JavascriptInterface
        public void log(final String msg) {
            appendOutput(msg);
        }
    }
}
