package xyz.zip8919.app.aichat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
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
public class FullscreenInputActivity extends ThemedActivity {

    public static final String EXTRA_TEXT = "fullscreen_text";
    public static final String EXTRA_RESULT = "fullscreen_result";

    private EditText edit;
    private TextView counter;
    private Button clear;

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (edit != null) out.putString("fs_text", edit.getText().toString());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_fullscreen_input);

        edit = (EditText) findViewById(R.id.fullscreen_edit);
        counter = (TextView) findViewById(R.id.fullscreen_count);
        clear = (Button) findViewById(R.id.fullscreen_clear);
        Button cancel = (Button) findViewById(R.id.fullscreen_cancel);
        Button done = (Button) findViewById(R.id.fullscreen_done);

        String initial = savedInstanceState != null
                ? savedInstanceState.getString("fs_text") : null;
        if (initial == null) initial = getIntent().getStringExtra(EXTRA_TEXT);
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
        clear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                final int len = edit.getText().length();
                if (len == 0) return;
                // 这里没有后悔药：主界面清空也先问一句，长文更是如此
                AcDialog.builder(FullscreenInputActivity.this)
                        .setTitle("清空输入")
                        .setMessage("将清空全部 " + len + " 字。清空后无法撤销。")
                        .setPositiveButton("清空", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                edit.setText("");
                                edit.requestFocus();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
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

    /** 只用来做 token 估算的防抖；静态避免每实例建 Handler。 */
    private static final android.os.Handler COUNTER =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable pendingCount;

    private void updateCounter(final int len) {
        if (clear != null) {
            clear.setVisibility(len > 0 ? View.VISIBLE : View.GONE);
        }
        if (counter == null) return;
        // estimateTokens 对全串 O(n)。短文本同步算（计数不滞后），长文本防抖 150ms：
        // 2 万字时每键 1~3ms、粘贴 5 万字 5~10ms，连续输入会明显跟不上手。
        if (len <= 2000) {
            if (pendingCount != null) COUNTER.removeCallbacks(pendingCount);
            pendingCount = null;
            int tokens = MessageHtmlRenderer.estimateTokens(edit.getText());
            counter.setText(len + " 字 · ~"
                    + MessageHtmlRenderer.formatTokens(tokens) + " tokens");
            return;
        }
        if (pendingCount != null) COUNTER.removeCallbacks(pendingCount);
        pendingCount = new Runnable() {
            public void run() {
                if (isFinishing() || isDestroyed()) return;
                if (counter == null) return;
                // 与消息底部同一套估算规则（中英分别计权、代理对算一个宽字符），
                // 长文输入时先看到大概要花多少，不用发出去再看回执
                int tokens = MessageHtmlRenderer.estimateTokens(edit.getText());
                counter.setText(len + " 字 · ~"
                        + MessageHtmlRenderer.formatTokens(tokens) + " tokens");
            }
        };
        COUNTER.postDelayed(pendingCount, 150);
    }
}
