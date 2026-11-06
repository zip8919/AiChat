package xyz.zip8919.app.aichat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
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
    private int imageBg; // 0=白 1=灰 2=黑

    // ---- 表格查看器状态 ----
    private AlertDialog tableDialog;
    private WebView tableWebView;
    private int tableRotation;
    private int tableBg;

    // ---- 代码预览状态 ----
    private AlertDialog codeDialog;
    private WebView codeWebView;
    private int codeRotation;
    private int codeBg;
    private String codeLang;
    private String codeText;

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
            Toast.makeText(activity, "无法加载图片列表", Toast.LENGTH_SHORT).show();
            return;
        }

        if (images.isEmpty() || index < 0 || index >= images.size()) {
            Toast.makeText(activity, "图片不可用", Toast.LENGTH_SHORT).show();
            return;
        }

        imageList = images;
        imageIndex = index;
        imageRotation = 0;
        // 默认背景：SVG 多为透明底深色线条，用白底；位图用黑底
        ImageInfo first = images.get(index);
        imageBg = ("svg".equals(first.type)) ? 0 : 2;

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

        configureWebView(imageWebView, Color.BLACK);

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
            public void onClick(View v) {
                imageBg = (imageBg + 1) % 3;
                if (imageWebView != null) {
                    evalJs(imageWebView, "viewerSetBg('" + bgHex(imageBg) + "')");
                }
            }
        });
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (imageDialog != null) imageDialog.dismiss();
            }
        });

        imageDialog = new AlertDialog.Builder(activity)
                .setView(view)
                .setCancelable(true)
                .create();
        imageDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            public void onDismiss(DialogInterface dialog) {
                if (imageWebView != null) {
                    imageWebView.destroy();
                    imageWebView = null;
                }
                imageDialog = null;
                imageList = null;
            }
        });
        imageDialog.getWindow().setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        imageDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
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

        String bgColor = bgHex(imageBg);
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
                    "function viewerSetBg(c){document.body.style.backgroundColor=c;}</script>" +
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
                    "function viewerSetBg(c){document.body.style.backgroundColor=c;document.documentElement.style.backgroundColor=c;}" +
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
            Toast.makeText(activity, "表格内容为空", Toast.LENGTH_SHORT).show();
            return;
        }

        tableRotation = 0;
        tableBg = 0;

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

        configureWebView(tableWebView, Color.WHITE);

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
            public void onClick(View v) {
                tableBg = (tableBg + 1) % 3;
                if (tableWebView != null) {
                    evalJs(tableWebView, "viewerSetBg('" + bgHex(tableBg) + "')");
                }
            }
        });
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (tableDialog != null) tableDialog.dismiss();
            }
        });

        tableDialog = new AlertDialog.Builder(activity)
                .setView(view)
                .setCancelable(true)
                .create();
        tableDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            public void onDismiss(DialogInterface dialog) {
                if (tableWebView != null) {
                    tableWebView.destroy();
                    tableWebView = null;
                }
                tableDialog = null;
            }
        });
        tableDialog.getWindow().setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
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

        String bgColor = bgHex(tableBg);
        String html = "<!DOCTYPE html><html><head>" +
                "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0,user-scalable=yes\">" +
                "<style>" +
                "*{margin:0;padding:0;}" +
                "html,body{background:" + bgColor + ";padding:8px;overflow:auto;" +
                "-webkit-transform-origin:0 0;transform-origin:0 0;}" +
                ".table-wrap{display:inline-block;" + rotateCss + "}" +
                "table{border-collapse:collapse;font-size:14px;}" +
                "th,td{border:1px solid #ddd;padding:8px 12px;text-align:left;}" +
                "th{background:#f0f0f0;font-weight:bold;white-space:nowrap;}" +
                "td{white-space:nowrap;}" +
                "</style>" +
                "<script>var vZoom=1;function viewerZoom(f){vZoom=Math.min(5,Math.max(0.1,vZoom*f));" +
                "var t='scale('+vZoom+')';" +
                "document.body.style.webkitTransform=t;document.body.style.transform=t;}" +
                "function viewerSetBg(c){document.body.style.backgroundColor=c;}</script>" +
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
            Toast.makeText(activity, "代码内容为空", Toast.LENGTH_SHORT).show();
            return;
        }

        codeRotation = 0;
        codeBg = 0;
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

        configureWebView(codeWebView, Color.WHITE);

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
            public void onClick(View v) {
                codeBg = (codeBg + 1) % 3;
                if (codeWebView != null) {
                    evalJs(codeWebView, "viewerSetBg('" + bgHex(codeBg) + "')");
                }
            }
        });
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (codeDialog != null) codeDialog.dismiss();
            }
        });

        codeDialog = new AlertDialog.Builder(activity)
                .setView(view)
                .setCancelable(true)
                .create();
        codeDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            public void onDismiss(DialogInterface dialog) {
                if (codeWebView != null) {
                    codeWebView.destroy();
                    codeWebView = null;
                }
                codeDialog = null;
                codeText = null;
                codeLang = null;
            }
        });
        codeDialog.getWindow().setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        codeDialog.show();

        loadAfterLayout(codeWebView, new Runnable() {
            public void run() { loadCodePreview(); }
        });
    }

    private void loadCodePreview() {
        if (codeWebView == null || codeText == null) return;

        LogUtil.d(TAG, "loadCodePreview: lang=%s len=%d rot=%d",
                codeLang, codeText.length(), codeRotation);
        String bgColor = bgHex(codeBg);

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
                "function viewerSetBg(c){document.body.style.backgroundColor=c;}";

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
            if (code.toLowerCase().contains("<head>")) {
                doc = code.replaceFirst("(?i)<head[^>]*>", "$0" + Matcher.quoteReplacement(inject));
            } else if (code.toLowerCase().contains("<html>")) {
                doc = code.replaceFirst("(?i)<html[^>]*>",
                        "$0<head>" + Matcher.quoteReplacement(inject) + "</head>");
            } else {
                doc = "<!DOCTYPE html><html><head>" + inject
                        + "</head><body>" + code + "</body></html>";
            }
            if (rotateCss.isEmpty()) {
                return doc;
            }
            // 只包裹 body 内容，旋转施加在我们的容器上
            if (doc.matches("(?is).*<body[^>]*>.*</body>.*")) {
                doc = doc.replaceFirst("(?is)(<body[^>]*>)", "$1<div class=\"viewer-rot\">");
                doc = doc.replaceFirst("(?is)</body>", "</div></body>");
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

    private static String bgHex(int state) {
        switch (state) {
            case 1: return "#888888";
            case 2: return "#000000";
            default: return "#FFFFFF";
        }
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
            wv.loadUrl("javascript:" + js);
        }
    }

    /** 屏幕旋转后 dialog 窗口尺寸需要重新铺满，否则会留下黑边。 */
    void onConfigurationChanged() {
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
    }
}
