package xyz.zip8919.app.aichat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.util.Base64;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Locale;

/**
 * 文件类操作的统一入口：判断类型、用第三方应用打开、分享、预览图片。
 *
 * <p>原先这套逻辑散在小工具页里，存储管理的两个列表各自抄了一份（而且抄漏了
 * 一半类型），结果是同一个文件在不同页面能用不同应用打开。收在一处之后，
 * 「打开方式不对」只需要改这张表。
 *
 * <p>所有方法都必须在主线程调用（会直接弹 UI）。
 */
final class FileActions {

    private static final String TAG = "FileActions";

    /** 单张图片做 base64 预览的上限，超出退回「用第三方应用打开」。 */
    private static final long PREVIEW_MAX_BYTES = 8L * 1024 * 1024;

    private FileActions() {
    }

    /**
     * 扩展名 → MIME。
     *
     * <p>不写成一张大表也不用系统 {@code MimeTypeMap}：API 18 的映射表缺很多类型
     * （svg / js / webp 常缺），缺了就会退成 {@code *}{@code /*}，系统把文件列表
     * 里所有应用都列出来——用户看到的「默认应用错误」多半是这一步造成的。
     * 这里先查自己的表，查不到再问系统，最后才退到 {@code application/octet-stream}。
     */
    static String mimeOf(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.US);
        int dot = n.lastIndexOf('.');
        String ext = dot >= 0 && dot < n.length() - 1 ? n.substring(dot + 1) : "";

        if (ext.equals("png")) return "image/png";
        if (ext.equals("jpg") || ext.equals("jpeg")) return "image/jpeg";
        if (ext.equals("gif")) return "image/gif";
        if (ext.equals("webp")) return "image/webp";
        if (ext.equals("bmp")) return "image/bmp";
        if (ext.equals("svg")) return "image/svg+xml";
        if (ext.equals("pdf")) return "application/pdf";
        if (ext.equals("html") || ext.equals("htm")) return "text/html";
        if (ext.equals("css")) return "text/css";
        if (ext.equals("js")) return "application/javascript";
        if (ext.equals("json")) return "application/json";
        if (ext.equals("xml")) return "text/xml";
        if (ext.equals("txt") || ext.equals("log") || ext.equals("ini")
                || ext.equals("cfg") || ext.equals("conf")) return "text/plain";
        if (ext.equals("md") || ext.equals("markdown")) return "text/markdown";
        if (ext.equals("csv")) return "text/csv";
        if (ext.equals("zip")) return "application/zip";
        if (ext.equals("apk")) return "application/vnd.android.package-archive";
        if (ext.equals("mp4")) return "video/mp4";
        if (ext.equals("mp3")) return "audio/mpeg";

        String sys = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(ext);
        if (sys != null && sys.length() > 0) return sys;

        // 不认识就按二进制流给：宁可让用户从「能处理二进制」的应用里挑，
        // 也不要给 */* 把文本编辑器、浏览器全列进来
        return "application/octet-stream";
    }

    /**
     * 位图才算「图片」。
     *
     * <p>{@code .svg} 故意排除在外：它是文本（能编辑、能高亮），而且
     * {@code BitmapFactory} 不认它。以前按 MIME 的 {@code image/} 前缀一刀切，
     * 结果 SVG 被当成图片送去预览，WebView 的 {@code <img src="data:image/svg+xml">}
     * 在部分内核算不出尺寸，直接显示「图片已损坏」的占位图，编辑入口也被一起挡掉。
     */
    static boolean isImage(String name) {
        String ext = extOf(name);
        return ext.equals("png") || ext.equals("jpg") || ext.equals("jpeg")
                || ext.equals("gif") || ext.equals("webp") || ext.equals("bmp");
    }

    static boolean isSvg(String name) {
        return "svg".equals(extOf(name));
    }

    private static String extOf(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.US);
        int dot = n.lastIndexOf('.');
        return dot >= 0 && dot < n.length() - 1 ? n.substring(dot + 1) : "";
    }

    /**
     * 用第三方应用打开。
     *
     * <p>走 chooser 而不是裸 {@code ACTION_VIEW}：系统里一旦有应用把该 MIME 注册成
     * 默认，用户就再也选不到别的——「默认应用错误」正是这么来的。每次给一次选择，
     * 代价是一次点击，收益是用户永远能自己纠正。
     */
    static void open(Activity a, File f) {
        if (a == null || f == null || !f.isFile()) {
            AcToast.show(a, "文件不存在");
            return;
        }
        String mime = mimeOf(f.getName());
        try {
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(FileActions.uriOf(f), mime);
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(Intent.createChooser(view, "打开 " + f.getName()));
            LogUtil.i(TAG, "open: %s mime=%s", f.getName(), mime);
        } catch (ActivityNotFoundException e) {
            AcToast.showLong(a, "没有能打开 " + f.getName() + " 的应用");
        } catch (Throwable t) {
            LogUtil.w(TAG, "open failed: %s : %s", f.getName(), t.getMessage());
            AcToast.show(a, "打开失败");
        }
    }

    /** 分享给其它应用（微信 / 邮件 / 网盘…）。 */
    static void share(Activity a, File f) {
        if (a == null || f == null || !f.isFile()) {
            AcToast.show(a, "文件不存在");
            return;
        }
        String mime = mimeOf(f.getName());
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(mime);
            send.putExtra(Intent.EXTRA_STREAM, FileActions.uriOf(f));
            send.putExtra(Intent.EXTRA_SUBJECT, f.getName());
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(Intent.createChooser(send, "分享 " + f.getName()));
            LogUtil.i(TAG, "share: %s mime=%s", f.getName(), mime);
        } catch (Throwable t) {
            LogUtil.w(TAG, "share failed: %s : %s", f.getName(), t.getMessage());
            AcToast.show(a, "分享失败");
        }
    }

    static Uri uriOf(File f) {
        return FilePathProvider.uriFor(f);
    }

    // ==================== 文本类：查看 / 编辑 / 预览运行 ====================

    /** 只读查看文本内容，带语法高亮，可一键复制全文。 */
    static void viewText(final Activity a, final File f) {
        if (a == null || f == null || !f.isFile()) return;
        if (isImage(f.getName())) {
            previewImage(a, f);
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                final String content = StorageManager.getInstance(a.getApplicationContext())
                        .readFile(f.getAbsolutePath());
                if (content == null) {
                    a.runOnUiThread(new Runnable() {
                        public void run() {
                            AcToast.show(a, "读取失败");
                        }
                    });
                    return;
                }
                final String lang = sniffLang(f.getName(), content);
                final String html = buildHighlightHtml(content, lang);
                a.runOnUiThread(new Runnable() {
                    public void run() {
                        if (a.isFinishing()) return;
                        showTextDialog(a, f, content, lang, html);
                    }
                });
            }
        }, "aichat-view-text").start();
    }

    /**
     * 语言判定：**先看扩展名，再看内容**。
     *
     * <p>只看内容会栽在带 XML 声明的 SVG 上：{@code <?xml version="1.0"?>} 开头，
     * 内容嗅探命中 {@code startsWith("<")} 就判成 html，于是矢量图被当成网页渲染，
     * 用户看到一片空白。
     */
    static String sniffLang(String name, String content) {
        String byExt = FileKinds.languageOf(name);
        if (byExt.length() > 0) return byExt;
        return MainActivity.detectLang(content);
    }

    /** 二进制文件绝不能当文本读写：按 UTF-8 解再存回去，等于把文件写坏。 */
    private static boolean isBinary(String name) {
        // svg 的 MIME 也是 image/*，但它是纯文本，必须放过
        if (isSvg(name)) return false;
        String m = mimeOf(name);
        return m.startsWith("image/") || m.equals("application/zip")
                || m.equals("application/pdf") || m.startsWith("audio/")
                || m.startsWith("video/")
                || m.equals("application/vnd.android.package-archive");
    }

    /** 编辑文本并覆盖保存。{@code onSaved} 用于让调用页刷新列表，可为 null。 */
    static void editText(final Activity a, final File f, final Runnable onSaved) {
        if (a == null || f == null || !f.isFile()) return;
        if (isImage(f.getName())) {
            AcToast.show(a, "图片不能当文本编辑");
            return;
        }
        if (isBinary(f.getName())) {
            AcToast.show(a, "该类型不是文本文件，不能编辑");
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                final String content = StorageManager.getInstance(a.getApplicationContext())
                        .readFile(f.getAbsolutePath());
                a.runOnUiThread(new Runnable() {
                    public void run() {
                        if (content == null) {
                            AcToast.show(a, "读取失败");
                            return;
                        }
                        final android.widget.EditText input = new android.widget.EditText(a);
                        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                        input.setText(content);
                        input.setTextSize(12);
                        AcDialog.builder(a)
                                .setTitle("编辑 " + f.getName())
                                .setView(input)
                                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int w) {
                                        String ok = StorageManager.getInstance(a.getApplicationContext())
                                                .saveExport(f.getName(), input.getText().toString());
                                        LogUtil.i(TAG, "editText: save %s ok=%s",
                                                f.getName(), (ok != null));
                                        if (ok == null) {
                                            AcToast.show(a, "保存失败");
                                            return;
                                        }
                                        StorageActivity.invalidateCache();
                                        if (onSaved != null) onSaved.run();
                                    }
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    }
                });
            }
        }, "aichat-edit-text").start();
    }

    /**
     * 预览 / 运行：html、svg 交给 {@link ViewerController} 渲染；
     * 其它语言交给 {@link JsRunnerActivity}（能跑 JS，也能看纯文本输出）。
     */
    static void previewCode(final Activity a, final ViewerController viewer, final File f) {
        if (a == null || f == null || !f.isFile()) return;
        if (isImage(f.getName())) {
            previewImage(a, f);
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                final String content = StorageManager.getInstance(a.getApplicationContext())
                        .readFile(f.getAbsolutePath());
                a.runOnUiThread(new Runnable() {
                    public void run() {
                        if (a.isFinishing()) return;
                        if (content == null) {
                            AcToast.show(a, "读取失败");
                            return;
                        }
                        String lang = sniffLang(f.getName(), content);
                        LogUtil.i(TAG, "previewCode: %s lang=%s", f.getName(), lang);
                        if ("html".equals(lang) || "svg".equals(lang)) {
                            viewer.showCode(lang, content);
                        } else {
                            a.startActivity(MainActivity.newJsRunnerIntent(a, content, lang));
                        }
                    }
                });
            }
        }, "aichat-preview-code").start();
    }

    private static void showTextDialog(Activity a, File f, final String content,
                                       String lang, String html) {
        final WebView wv = new WebView(a);
        wv.getSettings().setJavaScriptEnabled(false);
        wv.setBackgroundColor(0xFFFFFFFF);
        int viewH = (int) (a.getResources().getDisplayMetrics().density * 380);
        wv.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, viewH));
        wv.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
        AlertDialog dialog = AcDialog.builder(a)
                .setTitle(f.getName() + "  [" + lang + "]")
                .setView(wv)
                .setPositiveButton("复制全部", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        android.content.ClipboardManager cm =
                                (android.content.ClipboardManager) a.getSystemService(
                                        android.content.Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("aichat", content));
                            AcToast.show(a, "已复制到剪贴板");
                        }
                    }
                })
                .setNegativeButton("关闭", null)
                .create();
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            public void onDismiss(DialogInterface d) {
                try { wv.destroy(); } catch (Throwable ignored) { }
            }
        });
        dialog.show();
    }

    private static String buildHighlightHtml(String content, String lang) {
        return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<style>"
                + "html,body{margin:0;padding:0;background:#fff;}"
                + "pre{margin:8px;padding:8px;background:#F5F5F5;font-family:monospace;font-size:12px;"
                + "white-space:pre-wrap;word-wrap:break-word;}"
                + ".tk-kw{color:#d73a49;font-weight:bold;}"
                + ".tk-str{color:#032f62;}"
                + ".tk-cmt{color:#6a737d;font-style:italic;}"
                + ".tk-num{color:#005cc5;}"
                + ".tk-type{color:#6f42c1;}"
                + ".tk-fn{color:#6f42c1;}"
                + ".tk-op{color:#d73a49;}"
                + "</style></head><body><pre>"
                + CodeHighlighter.highlight(content, lang)
                + "</pre></body></html>";
    }

    /**
     * 应用内预览图片：读成 base64 data URI 塞进 WebView。不用 {@code file://}，
     * 避免 WebView 的文件访问限制；读盘与编码都在后台线程。
     */
    static void previewImage(final Activity a, final File f) {
        // 参数必须 final：匿名内部类要捕获它们（项目按 Java 7 语法编译）
        if (a == null || f == null || !f.isFile()) {
            AcToast.show(a, "图片不存在");
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                final String uri = readImageDataUri(f);
                a.runOnUiThread(new Runnable() {
                    public void run() {
                        if (a.isFinishing()) return;
                        if (uri == null) {
                            AcToast.showLong(a, "这张图太大，改用「打开方式」查看");
                            return;
                        }
                        showImageDialog(a, f, uri);
                    }
                });
            }
        }, "aichat-preview-image").start();
    }

    private static String readImageDataUri(File f) {
        if (f.length() > PREVIEW_MAX_BYTES || f.length() == 0) return null;
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) f.length());
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return "data:" + mimeOf(f.getName()) + ";base64,"
                    + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
        } catch (IOException e) {
            LogUtil.w(TAG, "readImageDataUri failed: %s", e.getMessage());
            return null;
        } finally {
            if (in != null) {
                try { in.close(); } catch (IOException ignored) { }
            }
        }
    }

    private static void showImageDialog(final Activity a, final File f, String dataUri) {
        final WebView wv = new WebView(a);
        wv.getSettings().setJavaScriptEnabled(false);
        wv.getSettings().setBuiltInZoomControls(true);
        wv.getSettings().setDisplayZoomControls(false);
        wv.getSettings().setUseWideViewPort(true);
        wv.getSettings().setLoadWithOverviewMode(true);
        wv.setBackgroundColor(0xFF202020);
        int viewH = (int) (a.getResources().getDisplayMetrics().density * 420);
        wv.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, viewH));
        String html = "<!DOCTYPE html><html><head>"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0,user-scalable=yes\">"
                + "<style>*{margin:0;padding:0;}"
                + "html,body{width:100%;height:100%;background:#202020;display:table;}"
                + ".cell{display:table-cell;vertical-align:middle;text-align:center;}"
                + "img{max-width:100%;max-height:100%;}</style></head><body>"
                + "<div class=\"cell\"><img src=\"" + dataUri + "\"></div>"
                + "</body></html>";
        wv.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);

        AlertDialog dialog = AcDialog.builder(a)
                .setTitle(f.getName())
                .setView(wv)
                .setPositiveButton("打开方式", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        open(a, f);
                    }
                })
                .setNeutralButton("分享", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        share(a, f);
                    }
                })
                .setNegativeButton("关闭", null)
                .create();
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            public void onDismiss(DialogInterface d) {
                // 不销毁会每次预览泄漏一个 WebView 及其渲染资源
                try { wv.destroy(); } catch (Throwable ignored) { }
            }
        });
        dialog.show();
    }
}
