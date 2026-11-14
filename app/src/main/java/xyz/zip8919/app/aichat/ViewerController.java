package xyz.zip8919.app.aichat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 全屏预览器：图片 / 表格 / HTML·SVG 代码，三种模式共用
 * {@code R.layout.dialog_image_viewer} 的骨架与同一套缩放、旋转、背景 JS。
 *
 * <p>从 MainActivity 抽出（约 830 行）。原先这些状态直接躺在 Activity 字段里：
 * 三个 AlertDialog、三个 WebView、三份旋转角/背景色、当前图片列表，
 * Activity 的 onDestroy 完全不知道它们的存在，只能靠 WebView 自己回收。
 * 现在由本类独占持有，生命周期收敛到 {@link #destroy()} 一处。
 *
 * <p>注意：页面里的 CSS 类名（viewer-rot / preview-wrap / table-wrap / img-cell）
 * 和 JS 函数名（viewerZoom / viewerSetBg）被内联脚本与聊天页共享，改动会连带
 * 破坏已渲染消息里的按钮绑定，不要重命名。
 */
final class ViewerController {

    private static final String TAG = "ViewerController";

    /**
     * 预览页顶部工具栏（viewer_top_left / viewer_close 各 44dp + 4dp 边距）的高度。
     * 预览 WebView 铺满整个 dialog，页面内容必须下移这么多，否则会被工具栏盖住。
     */
    private static final int TOOLBAR_INSET_PX = 52;

    private final Activity activity;

    // ---- 图片查看器状态 ----
    private AlertDialog imageDialog;
    private WebView imageWebView;
    private TextView imageCounter;
    private Button imagePrev, imageNext;
    private List<ImageInfo> imageList;
    private int imageIndex;
    private int imageRotation;

    // ---- 统一背景色（ARGB）----
    // 四类查看器共用一份，由 ViewerBg 持久化。用户手动选过色后全局生效；
    // 旋转 / 重新 load / 重开查看器都从这里读，不再回到按类型给的默认值。
    private int bgColor = ViewerBg.DEFAULT;

    // ---- 表格查看器状态 ----
    private AlertDialog tableDialog;
    private WebView tableWebView;
    private int tableRotation;

    // ---- 代码预览状态 ----
    private AlertDialog codeDialog;
    private WebView codeWebView;
    private int codeRotation;
    private String codeLang;
    private String codeText;

    // ---- 文件查看器状态（附件「查看文件」）----
    private AlertDialog docDialog;
    private WebView docWebView;
    private int docRotation;
    private String docName;
    private String docLang;
    /** 完整正文：预览/运行永远用完整版，截断只影响「源码」这一屏。 */
    private String docCode;
    private boolean docSourceMode;
    /** 文档 HTML 构建代际：后台构建完成时若已被更新的请求取代，直接丢弃。 */
    private int docLoadSeq;
    private Button docPreviewBtn;

    ViewerController(Activity activity) {
        this.activity = activity;
    }

    /** 图片项的数据载体，由聊天页 JS 传过来的 JSON 反序列化。 */
    private static final class ImageInfo {
        String src;
        String alt;
        String svg;   // 纯矢量 SVG 的原始标记（模型生成）
        String type;  // "raster" 或 "svg"
    }

    // ==================== 图片 ====================

    void showImage(int index, String imagesJson) {
        LogUtil.i(TAG, "showImage: idx=%d jsonLen=%d",
                index, imagesJson == null ? -1 : imagesJson.length());
        if (imageDialog != null && imageDialog.isShowing()) {
            imageDialog.dismiss();
        }

        List<ImageInfo> images = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(imagesJson);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                ImageInfo info = new ImageInfo();
                info.src = obj.optString("src", "");
                info.alt = obj.optString("alt", "");
                info.type = obj.optString("type", "raster");
                info.svg = obj.optString("svg", "");
                images.add(info);
            }
        } catch (Exception e) {
            AcToast.show(activity, "无法加载图片列表");
            return;
        }

        if (images.isEmpty() || index < 0 || index >= images.size()) {
            AcToast.show(activity, "图片不可用");
            return;
        }

        imageList = images;
        imageIndex = index;
        imageRotation = 0;
        // 默认背景：SVG 多为透明底深色线条，用白底；位图用黑底。用户手动选过色后
        // （ViewerBg 持久化）一律用那个色，旋转/重载/重开都不再回退到这里的默认。
        ImageInfo first = images.get(index);
        bgColor = ViewerBg.effective(activity,
                "svg".equals(first.type) ? 0xFFFFFFFF : 0xFF000000);

        View view = activity.getLayoutInflater().inflate(R.layout.dialog_image_viewer, null);
        imageWebView = (WebView) view.findViewById(R.id.viewer_webview);
        imageCounter = (TextView) view.findViewById(R.id.viewer_counter);
        imagePrev = (Button) view.findViewById(R.id.viewer_prev);
        imageNext = (Button) view.findViewById(R.id.viewer_next);
        Button zoomOut = (Button) view.findViewById(R.id.viewer_zoom_out);
        Button zoomIn = (Button) view.findViewById(R.id.viewer_zoom_in);
        Button rotate = (Button) view.findViewById(R.id.viewer_rotate);
        Button reset = (Button) view.findViewById(R.id.viewer_reset);
        Button bgToggle = (Button) view.findViewById(R.id.viewer_bg_toggle);
        Button close = (Button) view.findViewById(R.id.viewer_close);

        configureWebView(imageWebView, bgColor);

        imagePrev.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (imageIndex > 0) {
                    imageIndex--;
                    loadCurrentImage();
                }
            }
        });
        imageNext.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (imageList != null && imageIndex < imageList.size() - 1) {
                    imageIndex++;
                    loadCurrentImage();
                }
            }
        });
        zoomOut.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { evalJs(imageWebView, "viewerZoom(0.8)"); }
        });
        zoomIn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { evalJs(imageWebView, "viewerZoom(1.25)"); }
        });
        rotate.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                imageRotation = (imageRotation + 90) % 360;
                loadCurrentImage();
            }
        });
        reset.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                imageRotation = 0;
                loadCurrentImage();
            }
        });
        bgToggle.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openBgPicker(); }
        });
        bindExport(view, imageWebView, "image", new ViewerExport.DirectSource() {
            public String get() { return currentBitmapSource(); }
        });
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (imageDialog != null) imageDialog.dismiss();
            }
        });

        final AlertDialog dlg = AcDialog.builder(activity)
                .setView(view)
                .setCancelable(true)
                .create();
        // 捕获创建时的引用：dismiss() 的回调是 post 到主线程执行的，用户关掉预览
        // 后立刻点开另一张图时，这个回调会落在新实例创建之后。若按字段清理，
        // 被销毁的是新 WebView、被置空的是新 dialog —— 表现为白屏且关不掉。
        final WebView ownedWv = imageWebView;
        dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            public void onDismiss(DialogInterface dialog) {
                if (ownedWv != null) {
                    try { ownedWv.destroy(); } catch (Exception ignored) {}
                }
                if (imageWebView == ownedWv) imageWebView = null;
                if (imageDialog == dlg) {
                    imageDialog = null;
                    imageList = null;
                }
            }
        });
        imageDialog = dlg;
        imageDialog.getWindow().setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        imageDialog.getWindow().setBackgroundDrawable(new ColorDrawable(bgColor));
        // Activity 已结束还 show()：WindowManager$BadTokenException，主线程未捕获 = 闪退。
        // JS 桥回调是延迟派发的，点完「放大」立刻按返回就会走到这里（表格那条
        // 调用链在 MainActivity 侧还没有 try/catch 兜着）。
        if (activity.isFinishing()) return;
        imageDialog.show();
        loadAfterLayout(imageWebView, new Runnable() {
            public void run() { loadCurrentImage(); }
        });
    }

    private void loadCurrentImage() {
        if (imageList == null || imageCounter == null || imageWebView == null) return;

        ImageInfo info = imageList.get(imageIndex);
        LogUtil.d(TAG, "loadCurrentImage: idx=%d/%d type=%s src=%s",
                imageIndex, imageList.size(), info.type, LogUtil.preview(info.src, 120));
        imageCounter.setText((imageIndex + 1) + "/" + imageList.size());
        imagePrev.setEnabled(imageIndex > 0);
        imageNext.setEnabled(imageIndex < imageList.size() - 1);

        String bgColor = ViewerBg.hex(ViewerController.this.bgColor);
        String rotateCss = rotateTransform(imageRotation);

        String html;
        if ("svg".equals(info.type) && info.svg != null && !info.svg.isEmpty()) {
            // 纯矢量 SVG：不做 flex 居中，自然流式并可滚动
            String svgHtml = info.svg;
            html = "<!DOCTYPE html><html><head>" +
                    "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0,user-scalable=yes\">" +
                    "<style>" +
                    "*{margin:0;padding:0;}" +
                    "html,body{width:100%;min-height:100%;background:" + bgColor + ";" +
                    "overflow:auto;-webkit-transform-origin:0 0;transform-origin:0 0;}" +
                    ".svg-wrap{width:100%;max-width:100%;" + rotateCss + "}" +
                    ".svg-wrap svg{width:100%;height:auto;max-width:100%;display:block;}" +
                    "</style>" +
                    "<script>var vZoom=1;function viewerZoom(f){vZoom=Math.min(5,Math.max(0.1,vZoom*f));" +
                    "var t='scale('+vZoom+')';" +
                    "document.body.style.webkitTransform=t;document.body.style.transform=t;}" +
                    ViewerBg.SET_BG_JS + "</script>" +
                    "</head><body>" +
                    "<div class=\"svg-wrap\">" + svgHtml + "</div>" +
                    "</body></html>";
        } else {
            // 位图。图片装在一个按旋转轴交换过尺寸的盒子里：90/270 时可用宽度
            // 是视口高度，否则宽公式（如 400x42）会被压到 226px 视口的 95%，
            // 再被 rotate 变换裁掉——即"旋转后被截断"。
            String src = MessageHtmlRenderer.escAttr(info.src);
            String alt = MessageHtmlRenderer.escAttr(info.alt);
            boolean swapped = (imageRotation == 90 || imageRotation == 270);
            int boxW = swapped ? imageWebView.getHeight() : imageWebView.getWidth();
            int boxH = swapped ? imageWebView.getWidth() : imageWebView.getHeight();
            if (boxW <= 0) boxW = 226;
            if (boxH <= 0) boxH = 871;
            float fill = swapped ? 0.62f : 0.95f;
            int maxW = Math.max(1, (int) (boxW * fill));
            int maxH = Math.max(1, (int) (boxH * fill));
            html = "<!DOCTYPE html><html><head>" +
                    "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0,user-scalable=yes\">" +
                    "<style>" +
                    "*{margin:0;padding:0;}" +
                    "html{width:100%;height:100%;}" +
                    "html,body{background:" + bgColor + ";overflow:auto;" +
                    "-webkit-transform-origin:0 0;transform-origin:0 0;}" +
                    "body{width:100%;height:100%;display:table;}" +
                    ".img-cell{display:table-cell;vertical-align:middle;text-align:center;width:100%;height:" + boxH + "px;}" +
                    "img,svg{display:inline-block;vertical-align:middle;max-width:" + maxW + "px;" +
                    "max-height:" + maxH + "px;" + rotateCss + "}" +
                    "</style>" +
                    "<script>var vZoom=1;function viewerZoom(f){vZoom=Math.min(5,Math.max(0.1,vZoom*f));" +
                    "var t='scale('+vZoom+')';" +
                    "document.body.style.webkitTransform=t;document.body.style.transform=t;}" +
                    ViewerBg.SET_BG_JS +
                    "</script>" +
                    "</head><body>" +
                    "<div class=\"img-cell\"><img src=\"" + src + "\" alt=\"" + alt + "\"></div>" +
                    "</body></html>";
        }

        imageWebView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    // ==================== 表格 ====================

    void showTable(String tableHtml) {
        LogUtil.i(TAG, "showTable: htmlLen=%d", tableHtml == null ? -1 : tableHtml.length());
        if (tableDialog != null && tableDialog.isShowing()) {
            tableDialog.dismiss();
        }
        if (tableHtml == null || tableHtml.isEmpty()) {
            AcToast.show(activity, "表格内容为空");
            return;
        }

        tableRotation = 0;
        bgColor = ViewerBg.effective(activity, 0xFFFFFFFF);

        View view = activity.getLayoutInflater().inflate(R.layout.dialog_image_viewer, null);
        tableWebView = (WebView) view.findViewById(R.id.viewer_webview);

        // 隐藏图片专有控件
        view.findViewById(R.id.viewer_prev).setVisibility(View.GONE);
        view.findViewById(R.id.viewer_next).setVisibility(View.GONE);
        view.findViewById(R.id.viewer_counter).setVisibility(View.GONE);

        Button zoomOut = (Button) view.findViewById(R.id.viewer_zoom_out);
        Button zoomIn = (Button) view.findViewById(R.id.viewer_zoom_in);
        Button rotate = (Button) view.findViewById(R.id.viewer_rotate);
        Button reset = (Button) view.findViewById(R.id.viewer_reset);
        Button bgToggle = (Button) view.findViewById(R.id.viewer_bg_toggle);
        Button close = (Button) view.findViewById(R.id.viewer_close);

        configureWebView(tableWebView, bgColor);

        zoomOut.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { evalJs(tableWebView, "viewerZoom(0.8)"); }
        });
        zoomIn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { evalJs(tableWebView, "viewerZoom(1.25)"); }
        });
        rotate.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                tableRotation = (tableRotation + 90) % 360;
                loadTableContent(tableHtml);
            }
        });
        reset.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                tableRotation = 0;
                loadTableContent(tableHtml);
            }
        });
        bgToggle.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openBgPicker(); }
        });
        bindExport(view, tableWebView, "table", null);
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (tableDialog != null) tableDialog.dismiss();
            }
        });

        final AlertDialog tDlg = AcDialog.builder(activity)
                .setView(view)
                .setCancelable(true)
                .create();
        // 同上：dismiss 回调可能迟到，只清理自己持有的那份资源
        final WebView ownedTableWv = tableWebView;
        tDlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            public void onDismiss(DialogInterface dialog) {
                if (ownedTableWv != null) {
                    try { ownedTableWv.destroy(); } catch (Exception ignored) {}
                }
                if (tableWebView == ownedTableWv) tableWebView = null;
                if (tableDialog == tDlg) tableDialog = null;
            }
        });
        tableDialog = tDlg;
        tableDialog.getWindow().setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        // Activity 已结束还 show()：WindowManager$BadTokenException，主线程未捕获 = 闪退。
        // JS 桥回调是延迟派发的，点完「放大」立刻按返回就会走到这里（表格那条
        // 调用链在 MainActivity 侧还没有 try/catch 兜着）。
        if (activity.isFinishing()) return;
        tableDialog.show();

        loadAfterLayout(tableWebView, new Runnable() {
            public void run() { loadTableContent(tableHtml); }
        });
    }

    private void loadTableContent(String tableHtml) {
        if (tableWebView == null) return;

        LogUtil.d(TAG, "loadTableContent: len=%d rot=%d", tableHtml.length(), tableRotation);
        String rotateCss = "";
        String rotateJs = "";
        if (tableRotation != 0) {
            rotateCss = "-webkit-transform:rotate(" + tableRotation + "deg);"
                    + "transform:rotate(" + tableRotation + "deg);";
            // 让包裹层成为一个足够大的正方形，覆盖旋转后的可视区域
            // （API 18 对 CSS transform 不产生滚动溢出）。
            rotateJs = "<script>" +
                    "(function fix(a){" +
                    "var w=document.querySelector('.table-wrap');" +
                    "var t=w&&w.querySelector('table');" +
                    "if(!t)return;" +
                    "var ow=t.offsetWidth,oh=t.offsetHeight;" +
                    "if((!ow||!oh)&&a<20){setTimeout(function(){fix(a+1);},50);return;}" +
                    "var s=Math.max(ow,oh);" +
                    "w.style.width=s+'px';" +
                    "w.style.height=s+'px';" +
                    "var m=document.querySelector('meta[name=viewport]');" +
                    "if(m)m.setAttribute('content','width='+s+',user-scalable=yes');" +
                    "})(0);" +
                    "</script>";
        }

        String bgColor = ViewerBg.hex(ViewerController.this.bgColor);
        String html = "<!DOCTYPE html><html><head>" +
                "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0,user-scalable=yes\">" +
                "<style>" +
                "*{margin:0;padding:0;}" +
                "html,body{background:" + bgColor + ";padding:8px;overflow:auto;" +
                "-webkit-transform-origin:0 0;transform-origin:0 0;}" +
                ".table-wrap{display:inline-block;" + rotateCss + "}" +
                "table{border-collapse:collapse;font-size:14px;}" +
                // 字体色显式固定、单元格背景透明跟随 body：切背景只变底色，字体颜色不变；
                // th 不再写死 #f0f0f0，否则深色背景下表头留一块浅灰。
                "th,td{border:1px solid #ddd;padding:8px 12px;text-align:left;" +
                "color:#24292e;background:transparent;}" +
                "th{font-weight:bold;white-space:nowrap;}" +
                "td{white-space:nowrap;}" +
                "</style>" +
                "<script>var vZoom=1;function viewerZoom(f){vZoom=Math.min(5,Math.max(0.1,vZoom*f));" +
                "var t='scale('+vZoom+')';" +
                "document.body.style.webkitTransform=t;document.body.style.transform=t;}" +
                ViewerBg.SET_BG_JS + "</script>" +
                "</head><body>" +
                "<div class=\"table-wrap\">" + tableHtml + "</div>" +
                rotateJs +
                "</body></html>";

        tableWebView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    // ==================== HTML / SVG 代码预览 ====================

    void showCode(String lang, String code) {
        LogUtil.i(TAG, "showCode: lang=%s len=%d", lang, code == null ? -1 : code.length());
        if (codeDialog != null && codeDialog.isShowing()) {
            codeDialog.dismiss();
        }
        if (code == null || code.isEmpty()) {
            AcToast.show(activity, "代码内容为空");
            return;
        }

        codeRotation = 0;
        bgColor = ViewerBg.effective(activity, 0xFFFFFFFF);
        codeLang = lang;
        codeText = code;

        View view = activity.getLayoutInflater().inflate(R.layout.dialog_image_viewer, null);
        codeWebView = (WebView) view.findViewById(R.id.viewer_webview);

        view.findViewById(R.id.viewer_prev).setVisibility(View.GONE);
        view.findViewById(R.id.viewer_next).setVisibility(View.GONE);
        view.findViewById(R.id.viewer_counter).setVisibility(View.GONE);

        Button zoomOut = (Button) view.findViewById(R.id.viewer_zoom_out);
        Button zoomIn = (Button) view.findViewById(R.id.viewer_zoom_in);
        Button rotate = (Button) view.findViewById(R.id.viewer_rotate);
        Button reset = (Button) view.findViewById(R.id.viewer_reset);
        Button bgToggle = (Button) view.findViewById(R.id.viewer_bg_toggle);
        Button close = (Button) view.findViewById(R.id.viewer_close);

        configureWebView(codeWebView, bgColor);

        zoomOut.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { evalJs(codeWebView, "viewerZoom(0.8)"); }
        });
        zoomIn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { evalJs(codeWebView, "viewerZoom(1.25)"); }
        });
        rotate.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                codeRotation = (codeRotation + 90) % 360;
                loadCodePreview();
            }
        });
        reset.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                codeRotation = 0;
                loadCodePreview();
            }
        });
        bgToggle.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openBgPicker(); }
        });
        bindExport(view, codeWebView, "code", null);
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (codeDialog != null) codeDialog.dismiss();
            }
        });

        final AlertDialog cDlg = AcDialog.builder(activity)
                .setView(view)
                .setCancelable(true)
                .create();
        final WebView ownedCodeWv = codeWebView;
        cDlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            public void onDismiss(DialogInterface dialog) {
                if (ownedCodeWv != null) {
                    try { ownedCodeWv.destroy(); } catch (Exception ignored) {}
                }
                if (codeWebView == ownedCodeWv) codeWebView = null;
                if (codeDialog == cDlg) {
                    codeDialog = null;
                    codeText = null;
                    codeLang = null;
                }
            }
        });
        codeDialog = cDlg;
        codeDialog.getWindow().setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        // AlertDialog 的窗口默认带 FLAG_ALT_FOCUSABLE_IM。系统判断「需不需要输入法」
        // 时只看原生 EditText，看不到 WebView 里的 <input>，于是这个 flag 一直不被清掉，
        // 页面里的输入框点上去永远不弹键盘、也收不到字符。
        // 显式设置 softInputMode 会清掉该 flag，键盘才随焦点出现。
        codeDialog.getWindow().setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        // Activity 已结束还 show()：WindowManager$BadTokenException，主线程未捕获 = 闪退。
        // JS 桥回调是延迟派发的，点完「放大」立刻按返回就会走到这里（表格那条
        // 调用链在 MainActivity 侧还没有 try/catch 兜着）。
        if (activity.isFinishing()) return;
        codeDialog.show();

        loadAfterLayout(codeWebView, new Runnable() {
            public void run() { loadCodePreview(); }
        });
    }

    private void loadCodePreview() {
        if (codeWebView == null || codeText == null) return;

        LogUtil.d(TAG, "loadCodePreview: lang=%s len=%d rot=%d",
                codeLang, codeText.length(), codeRotation);
        String bgColor = ViewerBg.hex(ViewerController.this.bgColor);

        // 包裹层的真实盒子尺寸：旋转 90/270 时需要按交换后的轴给显式宽高
        int boxW = codeWebView.getWidth();
        int boxH = codeWebView.getHeight();
        if (boxW <= 0) boxW = activity.getResources().getDisplayMetrics().widthPixels;
        if (boxH <= 0) boxH = activity.getResources().getDisplayMetrics().heightPixels;
        String rotateCss = rotateTransformWrap(codeRotation, boxW, boxH);
        // 侧转 90/270 时包裹层的内容盒变成竖版（宽 = boxH）。声明了
        // width="600" 的 SVG 会被自身 max-width:100% 按更窄的盒子压缩成一条，
        // 所以把旋转后内容盒的真实宽度告诉页面，让 SVG 能撑满。
        boolean sideways = (codeRotation == 90 || codeRotation == 270);

        String html;
        if ("svg".equals(codeLang)) {
            html = buildCodePreviewHtml(codeText, codeLang, bgColor, rotateCss, false,
                    sideways ? boxH : 0);
        } else {
            String trimmed = codeText.trim().toLowerCase();
            boolean isFullDoc = trimmed.startsWith("<!doctype") || trimmed.startsWith("<html");
            html = buildCodePreviewHtml(codeText, codeLang, bgColor, rotateCss, isFullDoc,
                    sideways ? boxH : 0);
        }

        codeWebView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    private String buildCodePreviewHtml(String code, String lang, String bgColor,
            String rotateCss, boolean isFullHtmlDoc, int sideW) {
        String zoomJs =
                "var vZoom=1;" +
                "function viewerZoom(f){vZoom=Math.min(5,Math.max(0.1,vZoom*f));" +
                "var t='scale('+vZoom+')';" +
                "document.body.style.webkitTransform=t;document.body.style.transform=t;}" +
                ViewerBg.SET_BG_JS;

        if ("svg".equals(lang)) {
            // 把 SVG 包进最小 HTML 页。
            // 工具栏是覆盖在 WebView 之上的，从 y=0 开始的内容会被挡住，
            // 所以整页下移工具栏高度。
            // 旋转施加在 SVG 自己的包裹层上：旋转内层而祖先仍按未旋转的盒子裁剪，
            // 会把溢出的部分裁掉（"旋转后空白/截断"），因此旋转后的盒子两轴都给显式尺寸。
            String wrapCss = rotateCss.isEmpty() ? "" : rotateCss + "!important;";
            String svgW = sideW > 0 ? "width:100%;max-width:none;" : "max-width:100%;";
            return "<!DOCTYPE html><html><head>" +
                    "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0,user-scalable=yes\">" +
                    "<style>" +
                    "*{margin:0;padding:0;}" +
                    "html{width:100%;height:100%;background:" + bgColor + ";" +
                    "overflow:hidden;-webkit-transform-origin:0 0;transform-origin:0 0;}" +
                    "body{width:100%;height:100%;background:" + bgColor + ";" +
                    "overflow:hidden;-webkit-transform-origin:0 0;transform-origin:0 0;" +
                    "padding:" + TOOLBAR_INSET_PX + "px 0 0 0;" +
                    "-webkit-box-sizing:border-box;box-sizing:border-box;}" +
                    ".preview-wrap{display:block;width:100%;overflow:visible;" + wrapCss + "}" +
                    ".preview-wrap svg{" + svgW + "height:auto;display:block;}" +
                    "</style>" +
                    "<script>" + zoomJs + "</script>" +
                    "</head><body>" +
                    "<div class=\"preview-wrap\">" + code + "</div>" +
                    "</body></html>";
        }

        if (isFullHtmlDoc) {
            // 完整 HTML 文档自带 body/html 规则，直接旋转 body 会和旋转补偿打架，
            // 整个页面滑出屏幕（"旋转后空白"）。改为把文档内容包进一个由我们
            // 完全控制几何的容器里。
            String wrapCss = ".viewer-rot{"
                    + (rotateCss.isEmpty() ? "" : rotateCss + "!important;")
                    + "-webkit-transform-origin:0 0 !important;transform-origin:0 0 !important;"
                    + "}" +
                    // 工具栏覆盖在 WebView 上，文档从 y=0 开始会藏到它后面。
                    // 文档自带 body 规则，所以用 !important 强制下移。
                    "body{padding-top:" + TOOLBAR_INSET_PX + "px !important;" +
                    "-webkit-box-sizing:border-box !important;box-sizing:border-box !important;}";
            String inject = "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0,user-scalable=yes\">"
                    + "<style>" + wrapCss + "</style>"
                    + "<script>" + zoomJs + "</script>";
            String doc;
            // 不再 code.toLowerCase()：那会给整篇文档生成一份小写副本（100KB 的模型输出
            // 就是 200KB 临时 char 数组，一次一份），而这里连着判两次。
            // 静态 Pattern 带 (?i) 一样是不区分大小写，且只扫到第一个命中为止。
            if (P_HEAD.matcher(code).find()) {
                doc = P_HEAD.matcher(code).replaceFirst(
                        "$0" + Matcher.quoteReplacement(inject));
            } else if (P_HTML.matcher(code).find()) {
                doc = P_HTML.matcher(code).replaceFirst(
                        "$0<head>" + Matcher.quoteReplacement(inject) + "</head>");
            } else {
                doc = "<!DOCTYPE html><html><head>" + inject
                        + "</head><body>" + code + "</body></html>";
            }
            if (rotateCss.isEmpty()) {
                return doc;
            }
            // 只包裹 body 内容，旋转施加在我们的容器上
            // 原来先用 doc.matches("(?is).*<body[^>]*>.*</body>.*") 判一次全文，
            // 再 replaceFirst 两次（每次各自就地编译 Pattern）。matches 那次是
            // 整篇回溯扫描，而 replaceFirst 找不到时本来就等于不替换——
            // 判据完全多余。这里改成两次 find()，省掉一整趟全文匹配。
            if (P_BODY_OPEN.matcher(doc).find() && P_BODY_CLOSE.matcher(doc).find()) {
                doc = P_BODY_OPEN.matcher(doc).replaceFirst("$1<div class=\"viewer-rot\">");
                doc = P_BODY_CLOSE.matcher(doc).replaceFirst("</div></body>");
            }
            return doc;
        }

        // HTML 片段：包进最小页面
        return "<!DOCTYPE html><html><head>" +
                "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0,user-scalable=yes\">" +
                "<style>" +
                "*{margin:0;padding:0;}" +
                "html,body{width:100%;min-height:100%;background:" + bgColor + ";" +
                "overflow:auto;-webkit-transform-origin:0 0;transform-origin:0 0;}" +
                "body{padding:" + TOOLBAR_INSET_PX + "px 0 0 0;" +
                "-webkit-box-sizing:border-box;box-sizing:border-box;}" +
                ".preview-wrap{" + rotateCss + "}" +
                "</style>" +
                "<script>" + zoomJs + "</script>" +
                "</head><body>" +
                "<div class=\"preview-wrap\">" + code + "</div>" +
                "</body></html>";
    }

    // 文档注入用的静态 Pattern：原来每一处 replaceFirst/matches 都会就地编译一次，
    // 且为了「不区分大小写」先对整篇文档做 toLowerCase 副本。静态预编译 + (?i) 既
    // 省掉整篇小写副本，也省掉每次进预览/旋转 90° 时的 4 次 Pattern.compile。
    private static final java.util.regex.Pattern P_HEAD =
            java.util.regex.Pattern.compile("(?i)<head[^>]*>");
    private static final java.util.regex.Pattern P_HTML =
            java.util.regex.Pattern.compile("(?i)<html[^>]*>");
    private static final java.util.regex.Pattern P_BODY_OPEN =
            java.util.regex.Pattern.compile("(?is)(<body[^>]*>)");
    private static final java.util.regex.Pattern P_BODY_CLOSE =
            java.util.regex.Pattern.compile("(?is)</body>");

    // ==================== 文件查看器（附件） ====================

    /**
     * 附件「查看文件」：默认显示带高亮的源码，HTML / SVG 右上角可切到渲染预览，
     * JS 右上角是「运行」。
     *
     * @param name 文件名（标题栏显示，也用来判断语言）
     * @param lang {@link FileKinds#languageOf} 的结果，空串表示当纯文本
     */
    void showDocument(String name, String lang, String code) {
        LogUtil.i(TAG, "showDocument: name=%s lang=%s len=%d",
                name, lang, code == null ? -1 : code.length());
        if (docDialog != null && docDialog.isShowing()) {
            docDialog.dismiss();
        }
        if (code == null || code.length() == 0) {
            AcToast.show(activity, "文件内容为空");
            return;
        }

        docName = name == null ? "" : name;
        docLang = lang == null ? "" : lang;
        docCode = code;
        docRotation = 0;
        bgColor = ViewerBg.effective(activity, 0xFFFFFFFF);
        // 默认看源码：用户点「查看文件」的意图多半是「这几行到底写了什么」，
        // 想看成品的再点一下右上角的预览。
        docSourceMode = true;

        View view = activity.getLayoutInflater().inflate(R.layout.dialog_image_viewer, null);
        docWebView = (WebView) view.findViewById(R.id.viewer_webview);
        docPreviewBtn = (Button) view.findViewById(R.id.viewer_preview);
        TextView title = (TextView) view.findViewById(R.id.viewer_title);

        // 这三个是图片/表格专用的：单文件没有上一张下一张
        view.findViewById(R.id.viewer_prev).setVisibility(View.GONE);
        view.findViewById(R.id.viewer_next).setVisibility(View.GONE);
        view.findViewById(R.id.viewer_counter).setVisibility(View.GONE);
        // 附件预览器同样支持背景色修改（用户明确要求「包括文件预览器」）。
        // 源码页/源码高亮的字色是固定的，深色背景下对比度会变差——这是用户要的
        // 「字体颜色不变」的取舍，不做自动反色。
        Button bgToggle = (Button) view.findViewById(R.id.viewer_bg_toggle);
        bgToggle.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openBgPicker(); }
        });
        bindExport(view, docWebView, "doc", null);

        title.setText(docName);
        title.setVisibility(View.VISIBLE);
        // 标题右侧要让开「预览」按钮；没有预览按钮时只让开关闭按钮，
        // 否则长文件名的尾部会被压到 ✕ 底下（GONE 的锚点规则会被忽略）。
        RelativeLayout.LayoutParams titleLp =
                (RelativeLayout.LayoutParams) title.getLayoutParams();
        titleLp.addRule(RelativeLayout.LEFT_OF, R.id.viewer_close);

        Button zoomOut = (Button) view.findViewById(R.id.viewer_zoom_out);
        Button zoomIn = (Button) view.findViewById(R.id.viewer_zoom_in);
        Button rotate = (Button) view.findViewById(R.id.viewer_rotate);
        Button reset = (Button) view.findViewById(R.id.viewer_reset);
        Button close = (Button) view.findViewById(R.id.viewer_close);

        final boolean canPreview = FileKinds.isPreviewable(docName, docLang);
        final boolean canRun = FileKinds.isRunnable(docLang);
        if (canPreview || canRun) {
            docPreviewBtn.setText(canRun ? "运行" : "预览");
            docPreviewBtn.setVisibility(View.VISIBLE);
            // 预览按钮占的位置要一起让出来，否则标题会压在按钮下面
            titleLp.addRule(RelativeLayout.LEFT_OF, R.id.viewer_preview);
            docPreviewBtn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (canRun) {
                        // JS 没有「渲染」这回事，扔给已有的脚本运行器（带控制台与报错）
                        activity.startActivity(MainActivity.newJsRunnerIntent(
                                activity, docCode, docLang));
                        return;
                    }
                    docSourceMode = !docSourceMode;
                    docPreviewBtn.setText(docSourceMode ? "预览" : "源码");
                    loadDocument();
                }
            });
        }

        configureWebView(docWebView, bgColor);

        zoomOut.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { evalJs(docWebView, "viewerZoom(0.8)"); }
        });
        zoomIn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { evalJs(docWebView, "viewerZoom(1.25)"); }
        });
        rotate.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                docRotation = (docRotation + 90) % 360;
                loadDocument();
            }
        });
        reset.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                docRotation = 0;
                // 重新加载本身就是一次复位：缩放变量 vZoom 也跟着回到 1
                loadDocument();
            }
        });
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (docDialog != null) docDialog.dismiss();
            }
        });

        final AlertDialog dlg = AcDialog.builder(activity)
                .setView(view)
                .setCancelable(true)
                .create();
        final WebView ownedWv = docWebView;
        dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            public void onDismiss(DialogInterface dialog) {
                if (ownedWv != null) {
                    try { ownedWv.destroy(); } catch (Exception ignored) {}
                }
                if (docWebView == ownedWv) docWebView = null;
                if (docDialog == dlg) {
                    docDialog = null;
                    docCode = null;
                    docName = null;
                    docLang = null;
                }
            }
        });
        docDialog = dlg;
        docDialog.getWindow().setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        // 同 showCode：不设 softInputMode，HTML 附件里的输入框收不到键盘
        docDialog.getWindow().setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        // Activity 已结束还 show()：WindowManager$BadTokenException，主线程未捕获 = 闪退。
        // JS 桥回调是延迟派发的，点完「放大」立刻按返回就会走到这里（表格那条
        // 调用链在 MainActivity 侧还没有 try/catch 兜着）。
        if (activity.isFinishing()) return;
        docDialog.show();

        loadAfterLayout(docWebView, new Runnable() {
            public void run() { loadDocument(); }
        });
    }

    private void loadDocument() {
        if (docWebView == null || docCode == null) return;

        int boxW = docWebView.getWidth();
        int boxH = docWebView.getHeight();
        if (boxW <= 0) boxW = activity.getResources().getDisplayMetrics().widthPixels;
        if (boxH <= 0) boxH = activity.getResources().getDisplayMetrics().heightPixels;
        final String rotateCss = rotateTransformWrap(docRotation, boxW, boxH);
        final boolean sideways = (docRotation == 90 || docRotation == 270);
        // 构建 HTML 会跑 MarkdownPipeline 五六道正则、CodeHighlighter 逐字符匹配：
        // 40 万字符在 4.4 单核上要数百 ms 到 2s，主线程直接 ANR（每次旋转都会重跑）。
        // 输入全部快照后丢后台，构建完回主线程 load；用代际号丢弃过期结果。
        final String code = docCode;
        final String lang = docLang;
        final String name = docName;
        final boolean sourceMode = docSourceMode;
        final String bgHex = ViewerBg.hex(bgColor);
        final int seq = ++docLoadSeq;
        final int h = boxH;
        new Thread(new Runnable() {
            public void run() {
                String html;
                try {
                    if (sourceMode) {
                        html = SourcePage.build(lang, code, rotateCss, TOOLBAR_INSET_PX, bgHex);
                    } else if (FileKinds.isMarkdown(name)) {
                        html = buildMarkdownHtml(code, rotateCss, bgHex);
                    } else {
                        String trimmed = code.trim().toLowerCase();
                        boolean isFullDoc = "html".equals(lang)
                                && (trimmed.startsWith("<!doctype") || trimmed.startsWith("<html"));
                        html = buildCodePreviewHtml(code, lang, bgHex, rotateCss,
                                isFullDoc, sideways ? h : 0);
                    }
                } catch (Throwable t) {
                    LogUtil.w(TAG, "loadDocument build failed: %s", t.getMessage());
                    return;
                }
                final String out = html;
                activity.runOnUiThread(new Runnable() {
                    public void run() {
                        if (seq != docLoadSeq) return;
                        if (activity.isFinishing() || activity.isDestroyed()) return;
                        if (docWebView == null) return;
                        docWebView.loadDataWithBaseURL(null, out, "text/html", "UTF-8", null);
                    }
                });
            }
        }, "aichat-doc-html").start();
    }

    /** 源码页由 {@link SourcePage} 生成（纯字符串逻辑，可单测）。 */
    private String buildSourceHtml(String lang, String code, String rotateCss) {
        String html = SourcePage.build(lang, code, rotateCss, TOOLBAR_INSET_PX,
                ViewerBg.hex(bgColor));
        LogUtil.d(TAG, "buildSourceHtml: lang=%s chars=%d -> %d", lang, code.length(), html.length());
        return html;
    }

    /**
     * Markdown 预览：先截断再渲染。
     *
     * <p>顺序不能反。{@code MarkdownPipeline} 里有代码保护、公式、脚注、上下标
     * 五六道正则，十万字符跑下来在 API 18 上要好几秒，主线程直接 ANR。
     */
    private String buildMarkdownHtml(String md, String rotateCss, String bgHex) {
        String shown = md.length() > MarkdownPage.MAX_CHARS
                ? md.substring(0, MarkdownPage.MAX_CHARS) : md;
        long t0 = System.currentTimeMillis();
        String body = MarkdownPipeline.render(shown, activity);
        String html = MarkdownPage.build(body, md.length(), rotateCss, TOOLBAR_INSET_PX, bgHex);
        LogUtil.d(TAG, "buildMarkdownHtml: chars=%d -> %d in %dms",
                shown.length(), html.length(), System.currentTimeMillis() - t0);
        return html;
    }

    // ==================== 公共 ====================

    /** 预览 WebView 的统一配置：可缩放、禁用外部跳转、指定底色。 */
    private void configureWebView(WebView wv, int background) {
        wv.getSettings().setJavaScriptEnabled(true);
        wv.getSettings().setBuiltInZoomControls(true);
        wv.getSettings().setDisplayZoomControls(false);
        wv.getSettings().setUseWideViewPort(true);
        wv.getSettings().setLoadWithOverviewMode(true);
        wv.getSettings().setSupportZoom(true);
        wv.setBackgroundColor(background);
        wv.setWebViewClient(new WebViewClient() {
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return true;
            }
        });
    }

    /**
     * 等 dialog 里的 WebView 完成首次布局再加载。布局前加载会按 0×0 视口渲染，
     * 出现空白/错版（直到用户点重置 "#" 触发一次按正确尺寸重载）。
     */
    private void loadAfterLayout(final WebView wv, final Runnable load) {
        if (wv.getWidth() > 0 && wv.getHeight() > 0) {
            load.run();
            return;
        }
        wv.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
            public void onGlobalLayout() {
                wv.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                load.run();
            }
        });
    }

    /**
     * 打开背景色选择。选定后立即：① 更新统一字段并持久化；② 同步所有已打开
     * 预览 WebView 的控件底色与 DOM 背景。这样旋转按钮重新 load HTML、
     * 屏幕旋转、重开查看器、进程重建都从 {@link ViewerBg} 读到同一个色，
     * 不会再"改回默认"。
     */
    private void openBgPicker() {
        ViewerBg.showPicker(activity, bgColor, new ViewerBg.Callback() {
            public void onPicked(int argb) {
                bgColor = argb;
                ViewerBg.set(activity, argb);
                String hex = ViewerBg.hex(argb);
                applyBg(imageWebView, argb, hex);
                applyBg(tableWebView, argb, hex);
                applyBg(codeWebView, argb, hex);
                applyBg(docWebView, argb, hex);
                LogUtil.i(TAG, "viewer bg picked: %s", hex);
            }
        });
    }

    /** 控件底色 + DOM 背景一起改；DOM 用统一脚本，保证表格单元格也跟随、字体色不动。 */
    private void applyBg(WebView wv, int argb, String hex) {
        if (wv == null) return;
        try { wv.setBackgroundColor(argb); } catch (Exception ignored) {}
        evalJs(wv, "if(typeof viewerSetBg==='function')viewerSetBg('" + hex + "')");
    }

    /**
     * 绑定「导出为 PNG/JPEG」按钮，四类查看器共用。
     *
     * @param direct 图片查看器传 {@link #currentBitmapSource}：当前页是本地可读源时
     *               直接落原图（零背景、保原分辨率）；其余查看器传 null，走截图 + 裁背景。
     */
    private void bindExport(View view, final WebView wv, final String tag,
                            final ViewerExport.DirectSource direct) {
        Button b = (Button) view.findViewById(R.id.viewer_export);
        if (b == null) return;
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ViewerExport.choose(activity, wv, bgColor, tag, direct);
            }
        });
    }

    /**
     * 当前页是否来自本地可读源（{@code data:} / {@code file:}）。
     *
     * <p>只在图片查看器、且当前页是位图时返回非 null：SVG 是标记片段没有可直出的
     * 位图字节，{@code http(s)} 不在导出时二次下载（防盗链/超时/流量不可控），
     * 两者都返回 null 走截图 + 裁背景。
     */
    private String currentBitmapSource() {
        if (imageList == null || imageIndex < 0 || imageIndex >= imageList.size()) return null;
        ImageInfo info = imageList.get(imageIndex);
        if (!"raster".equals(info.type)) return null;
        String s = info.src;
        if (s == null) return null;
        if (s.startsWith("data:image/") || s.startsWith("file://")) return s;
        return null;
    }

    /**
     * 让旋转后的内容保持在正坐标区间的 CSS。裸 rotate() 会绕元素中心转，
     * 把部分内容推到负 x/y 被裁掉且无法滚动到（"旋转后被截断"）。
     * translate 百分比相对元素自身盒子（W×H），所以 90/270 时包裹层必须按
     * 交换后的盒子（竖↔横）给尺寸，否则位移落在错误的轴上，内容整体移出屏幕。
     */
    private static String rotateTransform(int deg) {
        switch (deg) {
            case 90:
                return "-webkit-transform:rotate(90deg) translateY(-100%);" +
                        "transform:rotate(90deg) translateY(-100%);" +
                        "-webkit-transform-origin:0 0;transform-origin:0 0;";
            case 180:
                return "-webkit-transform:translate(100%,100%) rotate(180deg);" +
                        "transform:translate(100%,100%) rotate(180deg);" +
                        "-webkit-transform-origin:0 0;transform-origin:0 0;";
            case 270:
                return "-webkit-transform:translateY(100%) rotate(270deg);" +
                        "transform:translateY(100%) rotate(270deg);" +
                        "-webkit-transform-origin:0 0;transform-origin:0 0;";
            default:
                return "";
        }
    }

    /**
     * 代码/SVG/HTML 预览包裹层专用的旋转 CSS。
     *
     * <p>包裹层是整宽（W = 视口宽，H = 内容高），而 rotateTransform 里的百分比
     * 位移是相对这个盒子计算的。90/270 时可视内容要占交换后的盒子，裸
     * translateY(-100%) 会按包裹层的*高度*在旋转轴上位移，内容整体落到可视区
     * 之外（"旋转后空白"）。给交换后的轴补显式像素宽度，才能让旋转后的内容
     * 落在正坐标区间并可滚动到。
     */
    private static String rotateTransformWrap(int deg, int boxW, int boxH) {
        if (deg == 90) {
            return "width:" + boxH + "px;" +
                    "-webkit-transform:rotate(90deg) translate(0,-100%);" +
                    "transform:rotate(90deg) translate(0,-100%);" +
                    "-webkit-transform-origin:0 0;transform-origin:0 0;";
        }
        if (deg == 270) {
            return "width:" + boxH + "px;" +
                    "-webkit-transform:rotate(-90deg) translate(-100%,0);" +
                    "transform:rotate(-90deg) translate(-100%,0);" +
                    "-webkit-transform-origin:0 0;transform-origin:0 0;";
        }
        if (deg == 180) {
            return "-webkit-transform:translate(100%,100%) rotate(180deg);" +
                    "transform:translate(100%,100%) rotate(180deg);" +
                    "-webkit-transform-origin:0 0;transform-origin:0 0;";
        }
        return "";
    }

    /** API 19+ 用 evaluateJavascript，API 18 回退 loadUrl。 */
    private static void evalJs(WebView wv, String js) {
        if (wv == null) return;
        if (android.os.Build.VERSION.SDK_INT >= 19) {
            wv.evaluateJavascript(js, null);
        } else {
            LogUtil.v(TAG, "evalJs: API<19 fallback loadUrl: %s", LogUtil.preview(js, 120));
            // loadUrl 会按 URL 解析：'#' 之后整段丢失、'%' 触发解码，代码里这两者都常见
            wv.loadUrl("javascript:" + TextKit.forJsUrl(js));
        }
    }

    /** 屏幕旋转后 dialog 窗口尺寸需要重新铺满，否则会留下黑边。 */
    void onConfigurationChanged() {
        // 部分 ROM 在配置变更时会重建 WebView 渲染层，DOM 内联背景可能丢失。
        // 用持久化字段重新施加一次，保证「旋转后背景色不改回默认」。
        String hex = ViewerBg.hex(bgColor);
        applyBg(imageWebView, bgColor, hex);
        applyBg(tableWebView, bgColor, hex);
        applyBg(codeWebView, bgColor, hex);
        applyBg(docWebView, bgColor, hex);
        if (imageDialog != null && imageDialog.isShowing()) {
            imageDialog.getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        if (tableDialog != null && tableDialog.isShowing()) {
            tableDialog.getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        if (codeDialog != null && codeDialog.isShowing()) {
            codeDialog.getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        if (docDialog != null && docDialog.isShowing()) {
            docDialog.getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
    }

    /** 页面销毁时释放三个预览 WebView，避免持有已销毁的 Activity。 */
    void destroy() {
        if (imageWebView != null) {
            imageWebView.destroy();
            imageWebView = null;
        }
        if (tableWebView != null) {
            tableWebView.destroy();
            tableWebView = null;
        }
        if (codeWebView != null) {
            codeWebView.destroy();
            codeWebView = null;
        }
        if (docWebView != null) {
            docWebView.destroy();
            docWebView = null;
        }
        if (imageDialog != null) {
            imageDialog.dismiss();
            imageDialog = null;
        }
        if (tableDialog != null) {
            tableDialog.dismiss();
            tableDialog = null;
        }
        if (codeDialog != null) {
            codeDialog.dismiss();
            codeDialog = null;
        }
        if (docDialog != null) {
            docDialog.dismiss();
            docDialog = null;
        }
    }
}
