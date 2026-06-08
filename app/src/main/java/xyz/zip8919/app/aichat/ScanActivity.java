package xyz.zip8919.app.aichat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class ScanActivity extends Activity {

    private static final Uri CONTENT_URI = Uri.parse("content://com.jxw.wbzc/query");

    private EditText scanEditText;
    private TextView scanHintText;
    private Button scanTriggerButton;

    private static final int[] SCAN_KEY_CODES = {5, 27, 131, 137, 286};
    private long lastScanLaunchTime = 0;
    private boolean expectingScanResult = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        setContentView(R.layout.activity_scan);

        scanEditText = (EditText) findViewById(R.id.scan_edit_text);
        scanHintText = (TextView) findViewById(R.id.scan_hint_text);
        scanTriggerButton = (Button) findViewById(R.id.scan_trigger_button);

        scanTriggerButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchSystemScan();
            }
        });

        findViewById(R.id.backspace_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Editable editable = scanEditText.getText();
                int selStart = scanEditText.getSelectionStart();
                int selEnd = scanEditText.getSelectionEnd();
                if (selStart != selEnd) {
                    editable.delete(selStart, selEnd);
                } else if (selStart > 0) {
                    editable.delete(selStart - 1, selStart);
                }
            }
        });

        findViewById(R.id.newline_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int start = scanEditText.getSelectionStart();
                scanEditText.getText().insert(start, "\n");
            }
        });

        findViewById(R.id.clear_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scanEditText.setText("");
                scanEditText.setVisibility(View.GONE);
                scanHintText.setVisibility(View.VISIBLE);
                scanTriggerButton.setVisibility(View.VISIBLE);
            }
        });

        // Load excerpts button
        findViewById(R.id.load_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showExcerptPicker();
            }
        });

        findViewById(R.id.cancel_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        findViewById(R.id.done_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String text = scanEditText.getText().toString().trim();
                if (text.isEmpty()) {
                    finish();
                    return;
                }
                showDoneDialog(text);
            }
        });

        scanEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (s.length() > 0) {
                    scanHintText.setVisibility(View.GONE);
                    scanEditText.setVisibility(View.VISIBLE);
                    scanTriggerButton.setVisibility(View.GONE);
                } else {
                    scanHintText.setVisibility(View.VISIBLE);
                    scanTriggerButton.setVisibility(View.VISIBLE);
                }
            }
            @Override
            public void afterTextChanged(Editable s) {}
        });
    }

    private void launchSystemScan() {
        expectingScanResult = true;
        // Try system scan activity first (same as scan key behavior)
        try {
            Intent intent = new Intent();
            intent.setClassName("com.jxw.launcher", "com.jxw.launcher.SPWBZCActivity");
            startActivity(intent);
            return;
        } catch (Exception e) {
            Log.d("ScanActivity", "SPWBZCActivity failed: " + e.getMessage());
        }
        try {
            Intent intent = new Intent();
            intent.setClassName("com.jxw.wbzc", "com.jxw.wbzc.MainActivity");
            startActivity(intent);
            return;
        } catch (Exception e) {
            Log.d("ScanActivity", "wbzc.MainActivity failed: " + e.getMessage());
        }
        Toast.makeText(this, "请手动打开文本摘抄应用扫描", Toast.LENGTH_LONG).show();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        for (int code : SCAN_KEY_CODES) {
            if (keyCode == code) {
                Toast.makeText(this, "检测到扫描键: " + keyCode, Toast.LENGTH_SHORT).show();
                expectingScanResult = true;
                try {
                    Intent intent = new Intent();
                    intent.setClassName("com.jxw.launcher", "com.jxw.launcher.SPWBZCActivity");
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(intent);
                } catch (Exception e) {
                    scanEditText.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            showExcerptPicker();
                        }
                    }, 1500);
                }
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (expectingScanResult) {
            expectingScanResult = false;
            loadLatestExcerpt();
        }
    }

    /**
     * Auto-load the most recent excerpt (only if EditText is empty).
     */
    private void loadLatestExcerpt() {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(CONTENT_URI, null, null, null, "_id DESC");
            if (cursor != null && cursor.moveToFirst()) {
                int contentIdx = cursor.getColumnIndex("content");
                if (contentIdx >= 0) {
                    String content = cursor.getString(contentIdx);
                    if (content != null && content.length() > 0) {
                        scanEditText.setText(content);
                        scanEditText.setSelection(content.length());
                        scanEditText.setVisibility(View.VISIBLE);
                        scanHintText.setVisibility(View.GONE);
                        scanTriggerButton.setVisibility(View.GONE);
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        } finally {
            if (cursor != null) {
                try { cursor.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Show a dialog listing all saved excerpts for user to pick from.
     */
    private void showExcerptPicker() {
        final List<ExcerptItem> items = new ArrayList<ExcerptItem>();
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(CONTENT_URI, null, null, null, "_id DESC");
            if (cursor != null) {
                int idIdx = cursor.getColumnIndex("_id");
                int titleIdx = cursor.getColumnIndex("title");
                int contentIdx = cursor.getColumnIndex("content");
                int timeIdx = cursor.getColumnIndex("create_time");

                while (cursor.moveToNext()) {
                    ExcerptItem item = new ExcerptItem();
                    if (idIdx >= 0) item.id = cursor.getInt(idIdx);
                    if (titleIdx >= 0) item.title = cursor.getString(titleIdx);
                    if (contentIdx >= 0) item.content = cursor.getString(contentIdx);
                    if (timeIdx >= 0) item.time = cursor.getLong(timeIdx);
                    if (item.content != null && item.content.length() > 0) {
                        items.add(item);
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        } finally {
            if (cursor != null) {
                try { cursor.close(); } catch (Exception ignored) {}
            }
        }

        if (items.isEmpty()) {
            Toast.makeText(this, "没有找到摘抄记录，请先在文本摘抄中扫描并保存", Toast.LENGTH_LONG).show();
            return;
        }

        String[] labels = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            ExcerptItem item = items.get(i);
            String label = item.title != null ? item.title : "摘抄 #" + item.id;
            // Show first 30 chars of content as preview
            String preview = item.content;
            if (preview.length() > 30) preview = preview.substring(0, 30) + "...";
            labels[i] = label + "  " + preview;
        }

        new AlertDialog.Builder(this)
            .setTitle("选择摘抄记录")
            .setItems(labels, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    String content = items.get(which).content;
                    scanEditText.setText(content);
                    scanEditText.setSelection(content.length());
                    scanEditText.setVisibility(View.VISIBLE);
                    scanHintText.setVisibility(View.GONE);
                    scanTriggerButton.setVisibility(View.GONE);
                }
            })
            .setNegativeButton(getString(R.string.cancel), null)
            .show();
    }

    private static class ExcerptItem {
        int id;
        String title;
        String content;
        long time;
    }

    private void showDoneDialog(final String text) {
        final String[] options = {
            getString(R.string.copy_to_clipboard),
            getString(R.string.insert_to_input),
            getString(R.string.copy_and_insert)
        };

        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.done))
            .setItems(options, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if (which == 0 || which == 2) {
                        copyToClipboard(text);
                    }
                    if (which == 1 || which == 2) {
                        returnTextToMain(text);
                        finish();
                    }
                    if (which == 0) {
                        finish();
                    }
                }
            })
            .setNegativeButton(getString(R.string.cancel), new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.dismiss();
                }
            })
            .show();
    }

    private void copyToClipboard(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            try {
                clipboard.setPrimaryClip(ClipData.newPlainText("scan", text));
            } catch (Exception e) {
                android.text.ClipboardManager oldClipboard =
                    (android.text.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (oldClipboard != null) {
                    oldClipboard.setText(text);
                }
            }
        }
    }

    private void returnTextToMain(String text) {
        Intent result = new Intent();
        result.putExtra("scan_text", text);
        setResult(RESULT_OK, result);
    }
}
