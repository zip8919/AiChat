package xyz.zip8919.app.aichat;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 提示词预设管理页。
 *
 * <p>交互按用户要求：<b>单击和长按都打开预览</b>（此前一个选择、一个改删，两种手势
 * 结果不一样，手机上没有哪条线索能让人猜到）；预览弹窗底部是「应用 / 修改 / 取消」，
 * 删除放在修改弹窗里，避免主路径上一个不可撤销的红色按钮。
 *
 * <p>「应用」的结果通过 {@link #EXTRA_APPLY_PROMPT} 回传，由设置页负责写进输入框
 * 并同步下拉框 —— 本页不直接改设置，避免出现「设置页还没保存、预设页已经改了」
 * 的两份真相。
 */
public class PresetManagerActivity extends ThemedActivity {

    /** 应用某个预设时回传的提示词正文。 */
    public static final String EXTRA_APPLY_PROMPT = "apply_prompt";
    /** 应用某个预设时回传的名称（设置页用来同步下拉框）。 */
    public static final String EXTRA_APPLY_NAME = "apply_name";

    private static final String TAG = "PresetManager";

    private ListView list;
    private TextView empty;
    private Button newButton;
    private Button saveCurrentButton;
    private final List<PresetStore.Preset> presets = new ArrayList<PresetStore.Preset>();
    private PresetAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_preset_manager);

        list = (ListView) findViewById(R.id.preset_list);
        empty = (TextView) findViewById(R.id.preset_empty);
        newButton = (Button) findViewById(R.id.preset_new);
        saveCurrentButton = (Button) findViewById(R.id.preset_save_current);

        findViewById(R.id.preset_back).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });

        adapter = new PresetAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View v, int pos, long id) {
                openPreview(pos);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> parent, View v, int pos, long id) {
                openPreview(pos);
                return true;
            }
        });

        newButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { createPreset(null); }
        });
        saveCurrentButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { createPreset(currentPromptFromSettings()); }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        presets.clear();
        presets.addAll(PresetStore.load(this));
        adapter.notifyDataSetChanged();
        boolean has = !presets.isEmpty();
        empty.setVisibility(has ? View.GONE : View.VISIBLE);
        list.setVisibility(has ? View.VISIBLE : View.GONE);
        saveCurrentButton.setEnabled(currentPromptFromSettings().length() > 0);
        LogUtil.d(TAG, "reload: %d presets", presets.size());
    }

    /**
     * 设置页当前输入框里的系统提示词草稿（可能尚未保存）。
     *
     * <p>设置页进入编辑时会写一份 {@code system_prompt_draft}，用来让本页的
     * 「保存当前提示词」不需要先把设置页保存一遍 —— 用户的心理预期是
     * 「我刚写的这段，存成预设」，而不是「先存设置再存预设」。
     */
    private String currentPromptFromSettings() {
        String v = getSharedPreferences(PresetStore.PREFS_NAME, 0)
                .getString("system_prompt_draft", "");
        return v == null ? "" : v.trim();
    }

    // ---- 预览 ----

    /**
     * 预览弹窗：标题是名称，正文是可滚动、可复制的提示词全文，底部三个动作。
     *
     * <p>正文用 ScrollView 包 TextView 而不是 EditText：预览阶段不该出现光标与
     * 输入法，想看可复制的文字选中即可，想改再点「修改」。
     */
    private void openPreview(final int pos) {
        if (pos < 0 || pos >= presets.size()) return;
        final PresetStore.Preset p = presets.get(pos);
        LogUtil.d(TAG, "openPreview: pos=%d name=%s len=%d", pos, p.name, p.prompt == null ? 0 : p.prompt.length());

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        box.setPadding(pad, dp(8), pad, 0);

        TextView meta = new TextView(this);
        meta.setTextSize(11);
        meta.setTextColor(ThemeTokens.get(this, ThemeTokens.AC_TEXT_SECONDARY));
        meta.setText((p.prompt == null ? 0 : p.prompt.length()) + Strings.get(R.string.presetmanageractivity_001));
        box.addView(meta);

        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        TextView body = new TextView(this);
        body.setTextSize(13);
        body.setTextIsSelectable(true);
        body.setTextColor(ThemeTokens.get(this, ThemeTokens.AC_TEXT));
        body.setText(p.prompt == null ? "" : p.prompt);
        body.setPadding(0, dp(8), 0, dp(8));
        sc.addView(body);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = dp(4);
        // 弹窗高度上限交给 weights：内容短时按内容，长时吃满剩余空间并可滚动
        sc.setLayoutParams(lp);
        box.addView(sc);

        AcDialog.builder(this)
                .setTitle(p.name)
                .setView(box)
                .setPositiveButton(Strings.get(R.string.presetmanageractivity_002), new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        applyPreset(p);
                    }
                })
                .setNeutralButton(Strings.get(R.string.presetmanageractivity_003), new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        editPreset(pos);
                    }
                })
                .setNegativeButton(Strings.get(R.string.presetmanageractivity_004), null)
                .show();
    }

    private void applyPreset(PresetStore.Preset p) {
        LogUtil.i(TAG, "applyPreset: name=%s len=%d", p.name, p.prompt == null ? 0 : p.prompt.length());
        android.content.Intent data = new android.content.Intent();
        data.putExtra(EXTRA_APPLY_PROMPT, p.prompt == null ? "" : p.prompt);
        data.putExtra(EXTRA_APPLY_NAME, p.name == null ? "" : p.name);
        setResult(RESULT_OK, data);
        AcToast.show(this, Strings.get(R.string.presetmanageractivity_005));
        finish();
    }

    // ---- 新建 / 修改 ----

    private void createPreset(String presetPrompt) {
        if (presets.size() >= PresetStore.MAX_PRESETS) {
            AcToast.show(this, Strings.get(R.string.presetmanageractivity_006) + PresetStore.MAX_PRESETS + Strings.get(R.string.presetmanageractivity_007));
            return;
        }
        editNew(presetPrompt == null ? "" : presetPrompt.trim());
    }

    private void editNew(String initialPrompt) {
        final String name = PresetStore.nextName(presets, Strings.get(R.string.presetmanageractivity_008));
        editing = null;
        showEditDialog(name, initialPrompt, new EditSink() {
            public boolean onSave(String newName, String newPrompt) {
                if (presets.size() >= PresetStore.MAX_PRESETS) {
                    AcToast.show(PresetManagerActivity.this, Strings.get(R.string.presetmanageractivity_009) + PresetStore.MAX_PRESETS + Strings.get(R.string.presetmanageractivity_010));
                    return false;
                }
                presets.add(new PresetStore.Preset(newName, newPrompt));
                return persist();
            }
        }, null);
    }

    private void editPreset(final int pos) {
        if (pos < 0 || pos >= presets.size()) return;
        final PresetStore.Preset p = presets.get(pos);
        editing = p;
        showEditDialog(p.name, p.prompt == null ? "" : p.prompt, new EditSink() {
            public boolean onSave(String newName, String newPrompt) {
                p.name = newName;
                p.prompt = newPrompt;
                return persist();
            }
        }, new Runnable() {
            public void run() {
                presets.remove(p);
                if (persist()) AcToast.show(PresetManagerActivity.this, Strings.get(R.string.presetmanageractivity_011));
            }
        });
    }

    private interface EditSink {
        /** @return true 表示保存成功，弹窗关闭；false 保持弹窗（比如重名） */
        boolean onSave(String name, String prompt);
    }

    /** 正在编辑的预设（新建为 null）：重名校验时要把它自己排除掉。 */
    private PresetStore.Preset editing;

    /**
     * 名称 + 正文编辑弹窗。新建与修改共用，{@code onDelete} 非空时多一个「删除」。
     *
     * <p>保存时不关弹窗而是先校验重名：AlertDialog 的 PositiveButton 默认点完就关，
     * 校验失败再重新弹一次，用户填的内容全丢。
     */
    private void showEditDialog(final String initialName, final String initialPrompt,
                                final EditSink sink, final Runnable onDelete) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        box.setPadding(pad, dp(8), pad, 0);

        final EditText nameEt = new EditText(this);
        nameEt.setHint(Strings.get(R.string.presetmanageractivity_012));
        nameEt.setSingleLine(true);
        nameEt.setText(initialName == null ? "" : initialName);
        nameEt.setSelection(nameEt.getText().length());
        box.addView(nameEt);

        final EditText promptEt = new EditText(this);
        promptEt.setHint(Strings.get(R.string.presetmanageractivity_013));
        promptEt.setMinLines(5);
        promptEt.setMaxLines(10);
        promptEt.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        promptEt.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        promptEt.setText(initialPrompt == null ? "" : initialPrompt);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(160));
        lp.topMargin = dp(6);
        promptEt.setLayoutParams(lp);
        box.addView(promptEt);

        final android.app.AlertDialog dlg = AcDialog.builder(this)
                .setTitle(onDelete == null ? Strings.get(R.string.presetmanageractivity_014) : Strings.get(R.string.presetmanageractivity_015))
                .setView(box)
                .setPositiveButton(Strings.get(R.string.presetmanageractivity_016), null)
                .setNegativeButton(Strings.get(R.string.presetmanageractivity_017), null)
                .setNeutralButton(onDelete == null ? null : Strings.get(R.string.presetmanageractivity_018), null)
                .create();
        final PresetStore.Preset self = editing;
        AcDialog.onShow(dlg, new android.content.DialogInterface.OnShowListener() {
            public void onShow(android.content.DialogInterface dialog) {
                android.app.AlertDialog d = (android.app.AlertDialog) dialog;
                d.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener(new View.OnClickListener() {
                            public void onClick(View v) {
                                String nm = nameEt.getText().toString().trim();
                                String pr = promptEt.getText().toString().trim();
                                if (nm.length() == 0) {
                                    AcToast.show(PresetManagerActivity.this, Strings.get(R.string.presetmanageractivity_019));
                                    return;
                                }
                                if (pr.length() == 0) {
                                    AcToast.show(PresetManagerActivity.this, Strings.get(R.string.presetmanageractivity_020));
                                    return;
                                }
                                if (PresetStore.contains(presets, nm, self)) {
                                    AcToast.show(PresetManagerActivity.this, Strings.get(R.string.presetmanageractivity_021) + nm);
                                    return;
                                }
                                if (sink.onSave(nm, pr)) {
                                    dlg.dismiss();
                                }
                            }
                        });
                if (onDelete != null) {
                    d.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)
                            .setOnClickListener(new View.OnClickListener() {
                                public void onClick(View v) {
                                    dlg.dismiss();
                                    confirmDelete(onDelete);
                                }
                            });
                }
            }
        });
        dlg.show();
    }

    private void confirmDelete(final Runnable doDelete) {
        AcDialog.builder(this)
                .setTitle(Strings.get(R.string.presetmanageractivity_022))
                .setMessage(Strings.get(R.string.presetmanageractivity_023))
                .setPositiveButton(Strings.get(R.string.presetmanageractivity_024), new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        doDelete.run();
                    }
                })
                .setNegativeButton(Strings.get(R.string.presetmanageractivity_025), null)
                .show();
    }

    private boolean persist() {
        boolean ok = PresetStore.save(this, presets);
        if (!ok) {
            AcToast.show(this, Strings.get(R.string.presetmanageractivity_026));
        }
        reload();
        return ok;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---- 列表 ----

    private final class PresetAdapter extends BaseAdapter {
        public int getCount() { return presets.size(); }
        public Object getItem(int p) { return presets.get(p); }
        public long getItemId(int p) { return p; }

        public View getView(int p, View cv, ViewGroup parent) {
            View v = cv;
            if (v == null) {
                v = LayoutInflater.from(PresetManagerActivity.this)
                        .inflate(R.layout.item_preset_row, parent, false);
                // 同 ConfigRowAdapter：这行不在 Activity 的 Factory2 钩子上，
                // 背景只在创建时按当前主题设一次（每次 bind 重设会拖慢滚动）。
                v.setBackgroundDrawable(ThemeRecolor.cardBg(PresetManagerActivity.this));
            }
            PresetStore.Preset item = presets.get(p);
            TextView name = (TextView) v.findViewById(R.id.preset_row_name);
            TextView prev = (TextView) v.findViewById(R.id.preset_row_preview);
            name.setText(item.name);
            String prompt = item.prompt == null ? "" : item.prompt;
            prev.setText(TextUtils.isEmpty(prompt) ? Strings.get(R.string.presetmanageractivity_027)
                    : TextKit.shorten(prompt.replace('\n', ' '), 60));
            return v;
        }
    }
}
