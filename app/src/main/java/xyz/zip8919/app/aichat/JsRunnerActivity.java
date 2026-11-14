package xyz.zip8919.app.aichat;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;
import java.util.regex.Matcher;

/**
 * Runs a JavaScript snippet inside a WebView (Chromium 30 on API 19) and shows
 * the console output. Also used as the live preview surface for html / svg code
 * blocks handed over from the conversation WebView.
 *
 * <p>执行状态是怎么算出来的（v1.12.0 重写）：包装脚本接管了
 * {@code setTimeout / setInterval / clearTimeout / clearInterval}，维护一个
 * 「未完成的异步任务数」。同步代码跑完时若仍有挂起的定时器，就报「正在执行」，
 * 等到最后一个定时器结束才报「已完成」。此前是同步代码一跑完就报完成，
 * 于是 {@code setTimeout(…, 5000)} 会显示「已执行，无 console 输出（33 ms）」——
 * 程序其实还在跑，界面已经宣布结束了。
 */
public class JsRunnerActivity extends ThemedActivity {

    private static final String TAG = "JsRunnerActivity";

    static final String EXTRA_CODE = "js_code";
    static final String EXTRA_LANG = "js_lang";

    /** 状态机。只在主线程读写。 */
    private static final int ST_IDLE = 0;
    private static final int ST_RUNNING = 1;
    private static final int ST_DONE = 2;
    private static final int ST_ERROR = 3;
    private static final int ST_ABORTED = 4;

    private WebView webView;
    private FrameLayout host;
    private EditText codeInput;
    private TextView outputView;
    private ScrollView outputScroll;
    /** 「输出 · ▶ 正在执行… / ✓ 已完成」：没有它，用户点完运行只能看到一个空页面。 */
    private TextView statusView;
    private Button runBtn;
    private Button abortBtn;

    private String lang = "js";
    private final StringBuilder output = new StringBuilder();
    /**
     * 输出缓冲里当前的行数（与 {@code output} 内容严格同步）。
     *
     * <p>原实现每次追加一行都把整个缓冲区（最多 64 K 字符）从头扫一遍数换行，
     * 超 1000 行时还要再扫一遍找裁剪点。跑一个 5000 行的 demo 脚本就是
     * 5000 × 64 K ≈ 3 亿次 charAt，主线程累计几百毫秒，而且随输出量线性变长。
     * 改为增量维护，只有真的裁剪过才重算一次。
     */
    private int outputLines = 1;
    /** 本次运行的起始时间，用于回报耗时。 */
    private long runStartedAt;
    /** 本次运行是否出过错。 */
    private boolean runHadError;
    /** 第一条错误信息，状态行里直接显示，省得用户翻输出。 */
    private String firstError;
    /** 本次运行是否已经报告过结果，避免 done() 与页面加载重复刷新状态。 */
    private boolean runReported;
    /** 运行环境是否回过话：一个字都没回，多半是脚本死循环或内核注入失败。 */
    private boolean bridgeEverCalled;

    private int state = ST_IDLE;
    private int dotTick;

    private final Handler ui = new Handler(Looper.getMainLooper());

    /** 状态行上的闪烁点：每 400ms 推进一格（. → .. → ...），同时刷新已耗时。 */
    private final Runnable ticker = new Runnable() {
        public void run() {
            if (state != ST_RUNNING) return;
            dotTick++;
            int n = dotTick % 3;
            String dots = n == 0 ? "." : (n == 1 ? ".." : "...");
            long el = System.currentTimeMillis() - runStartedAt;
            String head = isMarkup() ? "渲染结果 · ▶ 加载中" : "输出 · ▶ 正在执行";
            setStatus(head + dots + "  " + fmtDuration(el), false);
            ui.postDelayed(this, 400);
        }
    };

    /**
     * 收到页面加载完成后再问一次 JS：到底还有没有挂起的任务。
     * 用 {@code loadUrl("javascript:")} 而不是 evaluateJavascript——后者要 API 19。
     */
    private final Runnable heartbeat = new Runnable() {
        public void run() {
            if (state != ST_RUNNING || webView == null) return;
            try {
                webView.loadUrl("javascript:try{window.__check&&window.__check();}catch(e){}");
            } catch (Throwable t) {
                LogUtil.w(TAG, "heartbeat failed: %s", t);
            }
            // 死循环的脚本不会回应，再探一次，仍无回音就交给用户按「中止」
            if (!bridgeEverCalled) ui.postDelayed(this, 1500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_js_runner);
        LogUtil.i(TAG, "onCreate");

        host = (FrameLayout) findViewById(R.id.js_webview_host);
        codeInput = (EditText) findViewById(R.id.js_code_input);
        outputView = (TextView) findViewById(R.id.js_output);
        outputScroll = (ScrollView) findViewById(R.id.js_output_scroll);
        statusView = (TextView) findViewById(R.id.js_output_title);

        Intent intent = getIntent();
        String code = intent == null ? null : intent.getStringExtra(EXTRA_CODE);
        String extraLang = intent == null ? null : intent.getStringExtra(EXTRA_LANG);
        if (extraLang != null && !extraLang.isEmpty()) {
            lang = extraLang.toLowerCase(Locale.US);
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

        runBtn = (Button) findViewById(R.id.js_run_button);
        runBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { runCurrent(); }
        });

        abortBtn = (Button) findViewById(R.id.js_abort_button);
        abortBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { abortRun(); }
        });
        abortBtn.setEnabled(false);

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
            public void onClick(View v) {
                output.setLength(0);
                outputLines = 1;
                outputView.setText("");
                if (state == ST_DONE || state == ST_ERROR) setStatus("输出 · 已清空", false);
            }
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

        webView = createWebView();
        host.addView(webView);

        if (!code.isEmpty()) {
            runCurrent();
        } else {
            outputView.setText("（等待运行）");
            setStatus("输出 · 未运行", false);
        }
    }

    private boolean isMarkup() {
        return "html".equals(lang) || "svg".equals(lang);
    }

    private WebView createWebView() {
        WebView wv = new WebView(this);
        wv.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        wv.getSettings().setJavaScriptEnabled(true);
        wv.getSettings().setDomStorageEnabled(true);
        wv.setBackgroundColor(0xFFFFFFFF);
        wv.addJavascriptInterface(new ConsoleBridge(), "Console");
        wv.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (isMarkup()) {
                    if (state != ST_RUNNING) return;
                    runReported = true;
                    state = ST_DONE;
                    long ms = System.currentTimeMillis() - runStartedAt;
                    stopTicker();
                    setRunningUi(false);
                    setStatus("渲染结果 · ✓ 已加载 · " + fmtDuration(ms), false);
                    return;
                }
                // 不再在这里直接报「已完成」：页面加载完只说明同步代码跑过了，
                // setTimeout 之类的挂起任务可能还没开始。改成向 JS 问一次真实状态。
                if (state != ST_RUNNING) return;
                ui.removeCallbacks(heartbeat);
                ui.postDelayed(heartbeat, 600);
            }
        });
        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage msg) {
                if (msg == null) return true;
                // console.log 走的是注入的 Console 桥，不会再到这里；
                // 能落到这里的只有解析期错误（语法错），try/catch 抓不到，必须转出来。
                String m = msg.message();
                if (msg.messageLevel() == ConsoleMessage.MessageLevel.ERROR
                        || (m != null && m.contains("SyntaxError"))) {
                    appendOutput("ERROR: " + m);
                }
                return true;
            }
        });
        return wv;
    }

    private boolean outputFlushPending;

    /**
     * 输出刷新合帧。
     *
     * <p>原来每一行 console.log 都做一次 {@code setText(整段 ≤64KB)} + 全量重排 +
     * fullScroll：1000 行就是 1000 次全文排版，4.4 单核上能累计到数十秒。
     * 这里只把「追加 + 截尾」留在每次调用（纯内存操作），真正的 setText 与滚动
     * 合并到 50ms 一次，肉眼无差别。
     */
    private final Runnable flushOutput = new Runnable() {
        public void run() {
            outputFlushPending = false;
            if (isFinishing() || isDestroyed() || outputView == null) return;
            outputView.setText(output.toString());
            scrollOutputToBottom();
        }
    };

    private void appendOutput(final String line) {
        if (line == null) return;
        bridgeEverCalled = true;
        runOnUiThread(new Runnable() {
            public void run() {
                if (isFinishing() || isDestroyed()) return;
                // 首次输出前，先抹掉「（无输出）」这类占位
                if (output.length() == 0) {
                    outputView.setText("");
                }
                if (output.length() > 0) {
                    output.append('\n');
                    outputLines++;
                }
                for (int ci = 0; ci < line.length(); ci++) {
                    if (line.charAt(ci) == '\n') outputLines++;
                }
                output.append(line);
                if (line.startsWith("ERROR")) {
                    runHadError = true;
                    if (firstError == null) {
                        firstError = line.substring("ERROR".length()).trim();
                        if (firstError.startsWith(":")) firstError = firstError.substring(1).trim();
                    }
                }
                trimOutput();
                if (!outputFlushPending) {
                    outputFlushPending = true;
                    outputView.postDelayed(flushOutput, 50);
                }
            }
        });
    }

    /**
     * 每来一行输出都滚到底。
     *
     * <p>必须 {@code post}：{@code setText} 之后 TextView 还没重新测量，此时滚动的
     * 是旧的（更矮的）内容高度，等于没滚。放到下一帧才拿得到新高度。
     * 之前完全不滚，而 setText 会把 ScrollView 的 scrollY 重置成 0，
     * 于是每来一行就把视图拽回顶部。
     */
    private void scrollOutputToBottom() {
        if (outputScroll == null) return;
        outputScroll.post(new Runnable() {
            public void run() {
                outputScroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    /**
     * 限制输出缓冲：超过 65536 字符时删除开头降到约 32768（尽量按行界对齐），
     * 超过 1000 行时删除最前面的行至 1000 行内，始终保留最近输出。
     */
    private void trimOutput() {
        boolean trimmed = false;
        if (output.length() > 65536) {
            int cut = output.length() - 32768;
            int nl = output.indexOf("\n", cut);
            if (nl != -1 && nl < output.length() - 1) {
                cut = nl + 1;
            }
            output.delete(0, cut);
            trimmed = true;
            // 字符裁剪已经删掉了大段内容，行数必须先按新内容重算：
            // 拿裁剪前的行数去算 skip，会一路删到最后一个换行，只剩最后一行。
            outputLines = countLines(output);
        }
        if (outputLines > 1000) {
            int skip = outputLines - 1000;
            int cut = 0;
            for (int i = 0; i < output.length() && skip > 0; i++) {
                if (output.charAt(i) == '\n') {
                    cut = i + 1;
                    skip--;
                }
            }
            if (cut > 0) {
                output.delete(0, cut);
                trimmed = true;
            }
        }
        // 只有真的裁过才重算行数：正常路径（未超限）零扫描。
        if (trimmed) outputLines = countLines(output);
    }

    private void runCurrent() {
        final String code = codeInput.getText().toString();
        if (code.trim().isEmpty()) {
            AcToast.show(this, "请输入要运行的代码");
            return;
        }
        output.setLength(0);
        outputLines = 1;
        outputView.setText("");
        runHadError = false;
        firstError = null;
        runReported = false;
        bridgeEverCalled = false;
        runStartedAt = System.currentTimeMillis();
        state = ST_RUNNING;
        dotTick = 0;
        ui.removeCallbacks(heartbeat);
        startTicker();
        setRunningUi(true);
        LogUtil.i(TAG, "run: lang=%s len=%d", lang, code.length());

        if (isMarkup()) {
            setStatus("渲染结果 · ▶ 加载中...", false);
            webView.loadDataWithBaseURL(null, code, "text/html", "UTF-8", null);
            return;
        }
        setStatus("输出 · ▶ 正在执行...", false);
        webView.loadDataWithBaseURL(null, buildRunnerHtml(code), "text/html", "UTF-8", null);
    }

    /**
     * 中止当前运行。
     *
     * <p>JS 没有「停止」这种能力：{@code while(true){}} 会一直占着渲染线程，
     * {@code stopLoading()} 停不了它，{@code loadUrl("about:blank")} 只会排队等着
     * 也轮不上。唯一能真正掐断的办法是销毁这个 WebView 再建一个新的——
     * 连同它的 JS 上下文一起丢掉。
     */
    private void abortRun() {
        if (state != ST_RUNNING) {
            AcToast.show(this, "当前没有正在运行的程序");
            return;
        }
        long ms = System.currentTimeMillis() - runStartedAt;
        state = ST_ABORTED;
        runReported = true;
        stopTicker();
        setRunningUi(false);
        setStatus("输出 · ■ 已中止 · " + fmtDuration(ms), true);
        appendOutput("[已中止] 用户中止了本次运行");
        ui.removeCallbacks(heartbeat);
        LogUtil.i(TAG, "abortRun after %dms", ms);
        recreateWebView();
    }

    private void recreateWebView() {
        if (webView != null) {
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) parent.removeView(webView);
            try {
                webView.removeJavascriptInterface("Console");
                webView.stopLoading();
            } catch (Throwable ignored) {
            }
            try {
                webView.destroy();
            } catch (Throwable t) {
                LogUtil.w(TAG, "destroy failed: %s", t);
            }
            webView = null;
        }
        webView = createWebView();
        if (host != null) host.addView(webView);
    }

    private String buildRunnerHtml(String userCode) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\">")
          .append("<style>html,body{margin:0;padding:0;background:#fff;")
          .append("font:12px monospace;}</style></head><body>")
          .append("<script>")
          // ---- 输出桥 ----
          .append("function __log(o){try{var s;")
          .append("if(o===undefined){s='undefined';}")
          .append("else if(o===null){s='null';}")
          .append("else if(typeof o==='object'){try{s=JSON.stringify(o);}catch(e){s=String(o);}}")
          .append("else{s=String(o);}")
          .append("if(window.Console&&window.Console.log){window.Console.log(s);}}catch(e){}}")
          // ---- 异步任务计数：这是「还在跑」的唯一判据 ----
          .append("var __pending=0,__done=false,__hasErr=false,__settle=0,__timers={},__ivals={};")
          .append("var __t0=Date.now(),__last=__t0;")
          .append("function __mark(){__last=Date.now();}")
          .append("var __ot=window.setTimeout,__oi=window.setInterval,")
          .append("__oct=window.clearTimeout,__oci=window.clearInterval;")
          .append("function __reportError(e,where){__hasErr=true;")
          .append("__log('ERROR: '+((e&&e.message)?e.message:String(e))+(where?(' @'+where):''));}")
          .append("function __bridge(n){try{var C=window.Console;")
          .append("if(C&&C[n]){C[n].apply(C,Array.prototype.slice.call(arguments,1));}}catch(e){}}")
          .append("function __finish(){if(__done){return;}__done=true;")
          .append("__bridge('done',__last-__t0);}")
          // 同步代码跑完时调用：还有挂起的定时器就报「正在执行」，否则等 200ms
          // 让 Promise 微任务刷完再收尾，避免 await 之后的输出落在「已完成」后面
          .append("function __check(){if(__done){return;}")
          .append("if(__pending>0){__bridge('pending',__pending);return;}")
          .append("if(__settle){return;}")
          .append("__settle=__ot.call(window,function(){__settle=0;")
          .append("if(__pending>0){__bridge('pending',__pending);return;}__finish();},200);}")
          .append("window.setTimeout=function(fn,ms){")
          .append("if(typeof fn!=='function'){return __ot.apply(window,arguments);}")
          .append("var args=Array.prototype.slice.call(arguments,2),id;__pending++;")
          .append("id=__ot.call(window,function(){")
          .append("if(__timers[id]){delete __timers[id];__pending--;}")
          .append("try{fn.apply(null,args);}catch(e){__reportError(e);}__mark();__check();},ms||0);")
          .append("__timers[id]=true;return id;};")
          .append("window.clearTimeout=function(id){")
          .append("if(__timers[id]){delete __timers[id];__pending--;__check();}")
          .append("return __oct?__oct.call(window,id):0;};")
          .append("window.setInterval=function(fn,ms){")
          .append("if(typeof fn!=='function'){return __oi.apply(window,arguments);}")
          .append("__pending++;var id=__oi.call(window,function(){")
          .append("try{fn();}catch(e){__reportError(e);}__mark();},ms||0);")
          .append("__ivals[id]=true;return id;};")
          .append("window.clearInterval=function(id){")
          .append("if(__ivals[id]){delete __ivals[id];__pending--;__check();}")
          .append("return __oci?__oci.call(window,id):0;};")
          // ---- console / alert / 未捕获异常 ----
          .append("console={log:__log,info:__log,warn:__log,error:__log,debug:__log};")
          .append("function alert(m){__log('alert: '+m);}")
          .append("window.onerror=function(m,src,l,c,e){")
          .append("__reportError(e||m,l?('第'+l+'行'):'');return true;};")
          .append("window.__check=__check;")
          .append("</script>")
          // 用户代码单独一个 script 块：语法错误只会让这一块失效，
          // 后面的收尾块仍然执行，否则状态会永远停在「正在执行」
          .append("<script>try{").append(escapeScriptEnd(userCode))
          .append("\n}catch(e){__hasErr=true;")
          .append("__log('ERROR: '+((e&&e.message)?e.message:String(e)));}")
          .append("</script>")
          .append("<script>try{__mark();__check();}catch(e){}</script>")
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
            AcToast.show(this, "没有输出可复制");
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("aichat", text));
            AcToast.show(this, "已复制输出");
        }
    }

    private void pasteIntoInput() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null
                || cm.getPrimaryClip().getItemCount() == 0) {
            AcToast.show(this, "剪贴板为空");
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
            AcToast.show(this, "代码内容为空，无法保存");
            return;
        }
        // 复用导出扩展名映射：不再把 html/svg 之外的语言（py/go/md…）一律写成 .js
        String ext = ExportHelper.extForLang(lang);
        String name = "code_" + System.currentTimeMillis() + "." + ext;
        String path = StorageManager.getInstance().saveExport(name, code);
        LogUtil.i(TAG, "saveToExports: lang=%s path=%s", lang, path);
        AcToast.show(this, path == null ? "保存失败" : ("已保存到 " + path));
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
    }

    /**
     * 退到后台就停掉闪烁点与心跳。
     *
     * <p>不停的话，用户跑一段带 {@code setInterval} 的脚本后按 Home：状态行每
     * 400ms 刷新一次 TextView（每次都走 measure/layout），心跳每 1.5s 往 WebView
     * 塞一个 {@code javascript:} URL。后台十分钟 = 1500 次 setText + 400 次 JS 注入，
     * 4.4 单核机上就是持续唤醒 + 明显掉电；脚本若是死循环，每次 loadUrl 还会在
     * 渲染线程后面排队，队列只增不减。
     */
    @Override
    protected void onStop() {
        super.onStop();
        ui.removeCallbacks(ticker);
        ui.removeCallbacks(heartbeat);
    }

    @Override
    protected void onStart() {
        super.onStart();
        // 计时用的是墙钟（runStartedAt），回来直接续上，不会跳变
        if (state == ST_RUNNING) {
            startTicker();
            if (!bridgeEverCalled) ui.postDelayed(heartbeat, 600);
        }
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
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

        /**
         * 异步任务数变化。>0 说明还没跑完，保持「正在执行」；
         * 真正收尾由 {@link #done()} 做，这里只保证别提前报完成。
         */
        @JavascriptInterface
        public void pending(int n) {
            bridgeEverCalled = true;
            if (n > 0) LogUtil.d(TAG, "pending tasks: %d", n);
        }

        /** 包装脚本判定「所有任务都结束了」才回调这里，参数是脚本自己算的净耗时。 */
        @JavascriptInterface
        public void done(final long jsElapsedMs) {
            bridgeEverCalled = true;
            ui.post(new Runnable() {
                public void run() {
                    finishRun(jsElapsedMs);
                }
            });
        }
    }

    private void finishRun(long jsElapsedMs) {
        if (state != ST_RUNNING || runReported || isFinishing()) return;
        runReported = true;
        // 用脚本自报的耗时：它不含那 200ms 的收尾等待，也不含页面加载，
        // 更接近「这段代码跑了多久」；拿不到才退回墙钟
        long ms = jsElapsedMs >= 0 ? Math.max(1, jsElapsedMs)
                : (System.currentTimeMillis() - runStartedAt);
        int lines = output.length() == 0 ? 0 : countLines(output);
        stopTicker();
        setRunningUi(false);
        if (runHadError) {
            state = ST_ERROR;
            setStatus("输出 · ✗ " + shortError() + " · " + fmtDuration(ms), true);
        } else if (lines == 0) {
            state = ST_DONE;
            setStatus("输出 · ✓ 已完成，无 console 输出 · " + fmtDuration(ms), false);
            if (output.length() == 0) outputView.setText("（执行完成，但没有 console.log 输出）");
        } else {
            state = ST_DONE;
            setStatus("输出 · ✓ 已完成 · " + lines + " 行 · " + fmtDuration(ms), false);
        }
        LogUtil.i(TAG, "run done: lines=%d error=%s elapsed=%dms", lines, runHadError, ms);
    }

    /** 状态行只有一行，错误信息截到 26 字，全文仍在输出区。 */
    private String shortError() {
        String e = firstError == null ? "执行出错" : firstError;
        e = e.replace('\n', ' ').trim();
        if (e.length() > 26) e = e.substring(0, 26) + "…";
        return e;
    }

    private static int countLines(CharSequence text) {
        int n = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') n++;
        }
        return n;
    }

    /** 短任务报毫秒，长任务报秒，超过一分钟报分秒——一眼能看出慢在哪。 */
    static String fmtDuration(long ms) {
        if (ms < 1000) return ms + " ms";
        if (ms < 60000) return String.format(Locale.US, "%.1f s", ms / 1000.0);
        long s = ms / 1000;
        return (s / 60) + "m" + (s % 60) + "s";
    }

    private void startTicker() {
        ui.removeCallbacks(ticker);
        ui.post(ticker);
    }

    private void stopTicker() {
        ui.removeCallbacks(ticker);
    }

    private void setRunningUi(boolean running) {
        if (abortBtn != null) abortBtn.setEnabled(running);
        if (runBtn != null) runBtn.setText(running ? "重新运行" : "运行");
    }

    /** 状态行同时承担「是否运行过」的提示，出错时转红。 */
    private void setStatus(String text, boolean error) {
        if (statusView == null) return;
        statusView.setText(text);
        statusView.setTextColor(error ? 0xFFC62828 : 0xFF333333);
    }
}
