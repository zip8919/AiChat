package xyz.zip8919.app.aichat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 关于页。版本号、开源许可、使用说明，以及「检查更新」入口（跳 {@link UpdateActivity}）。
 * 「反馈建议」仍是占位入口，不接后端。
 */
public class AboutActivity extends ThemedActivity {

    private static final String TAG = "AboutActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        TextView version = (TextView) findViewById(R.id.about_version);
        version.setText("版本 " + versionText());

        Button help = (Button) findViewById(R.id.about_help);
        Button checkUpdate = (Button) findViewById(R.id.about_check_update);
        Button feedback = (Button) findViewById(R.id.about_feedback);
        Button licenses = (Button) findViewById(R.id.about_licenses);

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
        feedback.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Toast.makeText(AboutActivity.this, "反馈建议功能待开发", Toast.LENGTH_SHORT).show();
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
            return "未知";
        }
    }

    /**
     * 使用说明与首启弹窗同源（{@link HelpText}），随时可回查。
     */
    private void showHelp() {
        AcDialog.builder(this)
                .setTitle("使用说明")
                .setMessage(HelpText.full())
                .setPositiveButton("关闭", new DialogInterface.OnClickListener() {
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
        String text = "本应用使用了以下开源组件，版权归各自作者所有，"
                + "分别按其项目仓库中的许可证发布：\n\n"
                + "• commonmark-java\n  github.com/commonmark/commonmark-java\n\n"
                + "• jlatexmath-android\n  github.com/noties/jlatexmath-android\n\n"
                + "• Conscrypt\n  github.com/google/conscrypt\n";
        AcDialog.builder(this)
                .setTitle("开源许可")
                .setMessage(text)
                .setPositiveButton("关闭", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        d.dismiss();
                    }
                })
                .show();
    }
}
