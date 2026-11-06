package xyz.zip8919.app.aichat;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

/**
 * 全屏输入页。从主界面的展开按钮进入，点「完成」把全文回填给
 * {@link MainActivity}，点「取消」或按返回键则整段丢弃。
 *
 * <p>不做草稿持久化：主输入框是唯一数据源，这里改了又取消就应当等于没改过，
 * 否则用户会看到两份互相覆盖的文本。
 */
public class FullscreenInputActivity extends Activity {

    public static final String EXTRA_TEXT = "fullscreen_text";
    public static final String EXTRA_RESULT = "fullscreen_result";

    private EditText edit;
    private TextView counter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_fullscreen_input);

        edit = (EditText) findViewById(R.id.fullscreen_edit);
        counter = (TextView) findViewById(R.id.fullscreen_count);
        Button cancel = (Button) findViewById(R.id.fullscreen_cancel);
        Button done = (Button) findViewById(R.id.fullscreen_done);

        String initial = getIntent().getStringExtra(EXTRA_TEXT);
        if (initial != null) {
            edit.setText(initial);
            edit.setSelection(initial.length());
        }

        edit.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
            public void onTextChanged(CharSequence s, int st, int b, int c) { }
            public void afterTextChanged(Editable s) {
                updateCounter(s.length());
            }
        });
        updateCounter(edit.getText().length());

        cancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                setResult(RESULT_CANCELED);
                finish();
            }
        });
        done.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent out = new Intent();
                out.putExtra(EXTRA_RESULT, edit.getText().toString());
                setResult(RESULT_OK, out);
                finish();
            }
        });

        // 进页面直接可打字，不要求用户再点一次输入框
        edit.requestFocus();
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        LogUtil.d("FullscreenInput", "opened, initial len=%d",
                initial == null ? 0 : initial.length());
    }

    private void updateCounter(int len) {
        if (counter != null) counter.setText(len + " 字");
    }
}
