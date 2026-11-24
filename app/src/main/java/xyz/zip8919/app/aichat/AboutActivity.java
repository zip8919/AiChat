package xyz.zip8919.app.aichat;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 关于页。版本号、开源许可、使用说明，「检查更新」（跳 {@link UpdateActivity}）与「反馈建议」入口。
 *
 * <p>反馈建议给三条路：发邮件、提 Issue、应用内反馈中心。前两条交给系统已装应用处理；
 * 第三条进 {@link FeedbackActivity}，输入、选文件、提交全在应用内完成，不再跳浏览器。
 * 设备上没有邮件应用或打不开网页时，弹窗把地址或链接显示出来，可复制后手动处理，
 * 不静默失败。
 */
public class AboutActivity extends ThemedActivity {

    private static final String TAG = "AboutActivity";

    /** 开发者收件地址；与在线反馈中心后台配置的收件人相互独立。 */
    private static final String DEV_EMAIL = "zip8919@qq.com";
    private static final String ISSUES_URL = "https://github.com/zip8919/AiChat/issues/new";
    private static final String FEEDBACK_URL = "https://feedback.20090102.xyz";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        TextView version = (TextView) findViewById(R.id.about_version);
        version.setText(Strings.get(R.string.aboutactivity_001, versionText()));

        Button help = (Button) findViewById(R.id.about_help);
        Button checkUpdate = (Button) findViewById(R.id.about_check_update);
        Button feedback = (Button) findViewById(R.id.about_feedback);
        Button licenses = (Button) findViewById(R.id.about_licenses);
        Button notices = (Button) findViewById(R.id.about_notice);

        notices.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    startActivity(new Intent(AboutActivity.this, NoticeActivity.class));
                } catch (Exception e) {
                    LogUtil.w(TAG, "openNotices failed: %s", e.getMessage());
                }
            }
        });

        help.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showHelp();
            }
        });
        checkUpdate.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(AboutActivity.this, UpdateActivity.class));
            }
        });
        // 仅调试包：关闭思考的诊断日志导出。正式包没有这个入口，
        // 也不会产生日志文件（ThinkLog / OffDiag 都按 BuildConfig.DEBUG 关闭）。
        if (OffDiag.ENABLED) installDiagEntry(checkUpdate);
        feedback.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showFeedback();
            }
        });
        licenses.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showLicenses();
            }
        });

        LogUtil.d(TAG, "onCreate: version=%s", versionText());
    }

    private String versionText() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName + " (" + info.versionCode + ")";
        } catch (Exception e) {
            LogUtil.w(TAG, "versionText failed: %s", e.getMessage());
            return Strings.get(R.string.aboutactivity_003);
        }
    }

    /**
     * 使用说明与首启弹窗同源（{@link HelpText}），随时可回查。
     */
    private void showHelp() {
        AcDialog.builder(this)
                .setTitle(Strings.get(R.string.aboutactivity_004))
                .setMessage(HelpText.full())
                .setPositiveButton(Strings.get(R.string.aboutactivity_005), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        d.dismiss();
                    }
                })
                .show();
    }

    /**
     * 只列组件名与项目地址，不在这里复述许可证名称——写错了比不写更糟，
     * 具体条款以各项目仓库为准。
     */
    private void showLicenses() {
        String text = Strings.get(R.string.aboutactivity_006);
        AcDialog.builder(this)
                .setTitle(Strings.get(R.string.aboutactivity_008))
                .setMessage(text)
                .setPositiveButton(Strings.get(R.string.aboutactivity_009), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        d.dismiss();
                    }
                })
                .show();
    }

    /**
     * 三条反馈渠道。用列表而不是三个按钮：关于页按钮已经四个，再拆三个会把页面撑长，
     * 老设备一屏放不下。
     */
    private void showFeedback() {
        final String[] items = new String[]{
                Strings.get(R.string.aboutactivity_010),
                Strings.get(R.string.aboutactivity_011),
                Strings.get(R.string.aboutactivity_012)
        };
        AcDialog.builder(this)
                .setTitle(Strings.get(R.string.activity_about_004))
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            sendMail();
                        } else if (which == 1) {
                            openUrl(ISSUES_URL);
                        } else {
                            openFeedbackCenter();
                        }
                    }
                })
                .setNegativeButton(Strings.get(R.string.aboutactivity_013), null)
                .show();
    }

    /**
     * 反馈中心是应用内的原生页面：标题、描述、联系方式都用原生输入框填，附件走系统
     * 文件选择器，提交由 {@link FeedbackApi} 直接打反馈中心接口。旧版本这里跳浏览器，
     * 在词典笔上要么没有浏览器、要么输入体验极差。
     *
     * <p>启动失败只可能是包内 Activity 被误删，仍兜底打开网页，不让入口变成死路。
     */
    private void openFeedbackCenter() {
        try {
            startActivity(new Intent(AboutActivity.this, FeedbackActivity.class));
        } catch (Exception e) {
            LogUtil.w(TAG, "openFeedbackCenter failed: %s", e.getMessage());
            openUrl(FEEDBACK_URL);
        }
    }

    /**
     * 邮件走 {@code ACTION_SENDTO} + {@code mailto:}，只匹配邮件类应用；
     * 用 {@code ACTION_SEND} 会被聊天软件接走，用户拿到的不是写信界面。
     */
    private void sendMail() {
        Intent intent = new Intent(Intent.ACTION_SENDTO);
        intent.setData(Uri.parse("mailto:" + DEV_EMAIL
                + "?subject=" + Uri.encode(Strings.get(R.string.aboutactivity_014))
                + "&body=" + Uri.encode(mailBody())));
        if (intent.resolveActivity(getPackageManager()) != null) {
            try {
                startActivity(intent);
                return;
            } catch (ActivityNotFoundException e) {
                LogUtil.w(TAG, "sendMail failed: %s", e.getMessage());
            }
        }
        showMailFallback();
    }

    /** 预填正文带上版本与机型，用户不用自己找；三段都为空时也只是少几行。 */
    private String mailBody() {
        return Strings.get(R.string.aboutactivity_015,
                SystemInfo.appVersion(this),
                SystemInfo.deviceModel(),
                SystemInfo.androidVersion());
    }

    /**
     * 没有邮件应用：给出地址本身，同时留一条「用其他应用发送」。
     * 只提示「无法发送」等于把用户堵死，复制与分享至少有一条能走通。
     */
    private void showMailFallback() {
        AcDialog.builder(this)
                .setTitle(Strings.get(R.string.aboutactivity_016))
                .setMessage(Strings.get(R.string.aboutactivity_017, DEV_EMAIL))
                .setPositiveButton(Strings.get(R.string.aboutactivity_018), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        copyText(DEV_EMAIL);
                    }
                })
                .setNeutralButton(Strings.get(R.string.aboutactivity_019), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        shareMail();
                    }
                })
                .setNegativeButton(Strings.get(R.string.aboutactivity_013), null)
                .show();
    }

    /**
     * 在「检查更新」下面插入诊断导出按钮（仅调试包）。
     *
     * <p>为什么不放进设置页：排查这类问题的人需要少点几下就能拿到文件，
     * 关于页在首页一次点击就到，且这里不会和常规设置项混在一起。
     */
    private void installDiagEntry(View anchor) {
        try {
            if (!(anchor.getParent() instanceof ViewGroup)) return;
            final ViewGroup parent = (ViewGroup) anchor.getParent();
            Button btn = new Button(this);
            btn.setText(Strings.get(R.string.aboutactivity_026));
            btn.setLayoutParams(new LinearLayout.LayoutParams(anchor.getLayoutParams()));
            btn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    exportDiagLog();
                }
            });
            int idx = parent.indexOfChild(anchor) + 1;
            parent.addView(btn, idx);
        } catch (Throwable t) {
            LogUtil.w(TAG, "installDiagEntry failed: %s", t.getMessage());
        }
    }

    /**
     * 把诊断日志复制进导出目录再分享。
     *
     * <p>日志本体在 {@code files/logs} 下，属于应用私有目录，别的应用读不到；
     * 先落到 {@code exports} 才能通过 {@code content://} 交给接收方。
     */
    private void exportDiagLog() {
        File src = ThinkLog.file();
        if (src == null || !src.isFile() || src.length() == 0) {
            AcToast.showLong(this, Strings.get(R.string.aboutactivity_027));
            return;
        }
        StorageManager sm = StorageManager.getInstance(getApplicationContext());
        String text = sm.readFile(src.getAbsolutePath());
        if (text == null || text.length() == 0) {
            AcToast.showLong(this, Strings.get(R.string.aboutactivity_027));
            return;
        }
        String name = "thinking_trace_"
                + new SimpleDateFormat("MMdd_HHmm", Locale.US).format(new Date()) + ".log";
        String path = sm.saveExport(name, text);
        if (path == null) {
            AcToast.showLong(this, Strings.get(R.string.aboutactivity_029));
            return;
        }
        AcToast.showLong(this, Strings.get(R.string.aboutactivity_028, path));
        FileActions.share(this, new File(path));
    }

    private void shareMail() {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_EMAIL, new String[]{DEV_EMAIL});
        send.putExtra(Intent.EXTRA_SUBJECT, Strings.get(R.string.aboutactivity_014));
        send.putExtra(Intent.EXTRA_TEXT, mailBody());
        try {
            startActivity(Intent.createChooser(send, Strings.get(R.string.aboutactivity_024)));
        } catch (ActivityNotFoundException e) {
            LogUtil.w(TAG, "shareMail failed: %s", e.getMessage());
            copyText(DEV_EMAIL);
        }
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            LogUtil.w(TAG, "openUrl failed: %s", e.getMessage());
            showLinkFallback(url);
        }
    }

    /** 打不开网页时把链接摆出来；用户能看见地址，才谈得上换台设备继续。 */
    private void showLinkFallback(final String url) {
        AcDialog.builder(this)
                .setTitle(Strings.get(R.string.aboutactivity_020))
                .setMessage(Strings.get(R.string.aboutactivity_021, url))
                .setPositiveButton(Strings.get(R.string.aboutactivity_022), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        copyText(url);
                    }
                })
                .setNegativeButton(Strings.get(R.string.aboutactivity_013), null)
                .show();
    }

    private void copyText(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("aichat", text));
        AcToast.show(this, Strings.get(R.string.aboutactivity_023));
    }
}
