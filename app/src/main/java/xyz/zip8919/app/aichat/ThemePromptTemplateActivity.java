package xyz.zip8919.app.aichat;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

/**
 * 主题生成提示词模板编辑页（设置 → 主题生成提示词）。
 *
 * <p>模板决定了「新建主题 → 生成提示词」最后交给模型的那段话。不同模型口味差别很大
 * （有的要求只输出 JSON、有的要少注释），写死在代码里只能等发版，所以放出来给用户改。
 *
 * <p>安全边界：内容只影响「生成提示词」这段文本，不参与 CSS 渲染，也不进系统提示词；
 * 写坏了最差结果是提示词不好用，不会让应用崩溃或主题失效。留空即回退内置默认。
 */
public class ThemePromptTemplateActivity extends ThemedActivity {

    private EditText templateEdit;
    private Button btnReset, btnSave;
    private String original;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_theme_prompt);
        setTitle(Strings.get(R.string.themeprompttemplateactivity_001));

        templateEdit = (EditText) findViewById(R.id.template_edit);
        btnReset = (Button) findViewById(R.id.btn_reset);
        btnSave = (Button) findViewById(R.id.btn_save);

        // 模板五 六千字，{desc} 加 18 个设备占位符全都可用，逐字敲大括号不现实
        PlaceholderChips.attach(
                (LinearLayout) findViewById(R.id.template_chips),
                templateEdit, PlaceholderChips.THEME);

        original = ThemePromptStore.getTemplate(this);
        templateEdit.setText(original);

        btnSave.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                save();
            }
        });
        btnReset.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                confirmReset();
            }
        });
    }

    private void save() {
        String text = templateEdit.getText().toString();
        if (text.trim().length() == 0) {
            ThemePromptStore.reset(this);
            toast(Strings.get(R.string.themeprompttemplateactivity_002));
        } else {
            if (text.indexOf("{desc}") < 0) {
                // 不是错误，只是容易踩：允许保存，但把后果说清楚
                toast(Strings.get(R.string.themeprompttemplateactivity_003));
            } else {
                toast(Strings.get(R.string.themeprompttemplateactivity_004));
            }
            ThemePromptStore.setTemplate(this, text);
        }
        original = ThemePromptStore.getTemplate(this);
        finish();
    }

    private void confirmReset() {
        AcDialog.builder(this)
                .setTitle(Strings.get(R.string.themeprompttemplateactivity_005))
                .setMessage(Strings.get(R.string.themeprompttemplateactivity_006))
                .setPositiveButton(Strings.get(R.string.themeprompttemplateactivity_007), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        ThemePromptStore.reset(ThemePromptTemplateActivity.this);
                        original = ThemePromptStore.defaultTemplate();
                        templateEdit.setText(original);
                        templateEdit.setSelection(0);
                        toast(Strings.get(R.string.themeprompttemplateactivity_008));
                    }
                })
                .setNegativeButton(Strings.get(R.string.themeprompttemplateactivity_009), null)
                .show();
    }

    private boolean isDirty() {
        return original != null
                && !original.equals(templateEdit.getText().toString());
    }

    @Override
    public void onBackPressed() {
        if (!isDirty()) {
            super.onBackPressed();
            return;
        }
        AcDialog.builder(this)
                .setTitle(Strings.get(R.string.themeprompttemplateactivity_010))
                .setItems(new String[]{Strings.get(R.string.themeprompttemplateactivity_011), Strings.get(R.string.themeprompttemplateactivity_012), Strings.get(R.string.themeprompttemplateactivity_013)},
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                if (w == 0) finish();
                                else if (w == 1) save();
                            }
                        })
                .show();
    }

    private void toast(String s) {
        AcToast.show(this, s);
    }
}
