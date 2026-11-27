package xyz.zip8919.app.aichat;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 反馈页的「应用内文件」选择器。
 *
 * <p>分类做成顶部横向页签而不是列表跳转：词典笔屏幕只有 258dp，一次能看到的
 * 文件本来就不多，再套一层分类页会让人每选一个文件都要退两级。页签之间
 * 切换不丢已勾选项，所以「会话 + 日志 + 配置」可以一次凑齐再返回。
 *
 * <p>选中只记路径，真正的读取与打码在点「添加」时统一做：{@code config.json}
 * 要先写一份打码副本，放在主线程做会让界面顿一下，放大文件时更明显。
 *
 * <p>两个轻量预览：会话沿用存储页的详情弹窗（条数 / 体积 / 时间，可跳去查看），
 * 主题直接进 {@link ThemePreviewActivity} 的只读模式，不另写一套预览。
 */
public class FeedbackFilePickerActivity extends ThemedActivity {

    private static final String TAG = "FeedbackFilePicker";

    /** 调用方已选数量，用于本次还能选几个。 */
    static final String EXTRA_SELECTED_COUNT = "selected_count";
    /** 结果：文件绝对路径，与 masked / names 一一对应。 */
    static final String EXTRA_PATHS = "paths";
    /** 结果：上传时使用的文件名。会话用标题而不是一串 id，方便在反馈里辨认。 */
    static final String EXTRA_NAMES = "names";
    /** 结果：该项是否为打码副本。 */
    static final String EXTRA_MASKED = "masked";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final List<FeedbackFileSource.Item> items = new ArrayList<FeedbackFileSource.Item>();
    /** 跨页签的已选集合：key 是文件绝对路径。 */
    private final LinkedHashMap<String, FeedbackFileSource.Item> chosen =
            new LinkedHashMap<String, FeedbackFileSource.Item>();

    private int category = FeedbackFileSource.CAT_CONVERSATION;
    private int alreadyAttached;
    private int loadSeq;

    private LinearLayout tabs;
    private TextView hint;
    private ListView list;
    private TextView empty;
    private TextView loading;
    private TextView countView;
    private Button addButton;
    private PickAdapter adapter;
    private boolean preparing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_feedback_file_picker);

        Intent intent = getIntent();
        alreadyAttached = intent == null ? 0 : intent.getIntExtra(EXTRA_SELECTED_COUNT, 0);
        if (alreadyAttached < 0) alreadyAttached = 0;
        if (alreadyAttached > FeedbackApi.MAX_FILES) alreadyAttached = FeedbackApi.MAX_FILES;

        tabs = (LinearLayout) findViewById(R.id.pick_tabs);
        hint = (TextView) findViewById(R.id.pick_hint);
        list = (ListView) findViewById(R.id.pick_list);
        empty = (TextView) findViewById(R.id.pick_empty);
        loading = (TextView) findViewById(R.id.pick_loading);
        countView = (TextView) findViewById(R.id.pick_count);
        addButton = (Button) findViewById(R.id.pick_add);

        findViewById(R.id.pick_back).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (preparing) {
                    AcToast.show(FeedbackFilePickerActivity.this,
                            Strings.get(R.string.feedbackfilepickeractivity_031));
                    return;
                }
                finish();
            }
        });

        adapter = new PickAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                if (pos < 0 || pos >= items.size() || preparing) return;
                toggle(items.get(pos));
            }
        });

        addButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (preparing) return;
                add();
            }
        });

        buildTabs();
        selectCategory(FeedbackFileSource.CAT_CONVERSATION, true);
    }

    // ------------------------------------------------------------ 页签

    private void buildTabs() {
        tabs.removeAllViews();
        for (int i = 0; i < FeedbackFileSource.CATEGORY_COUNT; i++) {
            final int cat = i;
            LinearLayout tab = new LinearLayout(this);
            tab.setOrientation(LinearLayout.VERTICAL);
            tab.setGravity(Gravity.CENTER_HORIZONTAL);
            tab.setPadding(dp(10), dp(8), dp(10), 0);
            tab.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView label = new TextView(this);
            label.setTextSize(13);
            label.setText(FeedbackFileSource.categoryName(i));
            label.setTag("label");
            tab.addView(label);

            View line = new View(this);
            LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(2));
            lineLp.topMargin = dp(5);
            line.setLayoutParams(lineLp);
            line.setTag("line");
            tab.addView(line);

            tab.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (preparing || cat == category) return;
                    selectCategory(cat, true);
                }
            });
            tabs.addView(tab);
        }
    }

    private void paintTabs() {
        int on = getResources().getColor(R.color.primary);
        int off = getResources().getColor(R.color.text_secondary);
        for (int i = 0; i < tabs.getChildCount(); i++) {
            LinearLayout tab = (LinearLayout) tabs.getChildAt(i);
            boolean active = i == category;
            TextView label = (TextView) tab.findViewWithTag("label");
            View line = tab.findViewWithTag("line");
            if (label != null) {
                label.setTextColor(active ? on : off);
                label.getPaint().setFakeBoldText(active);
            }
            if (line != null) {
                line.setBackgroundColor(active ? on : 0x00000000);
            }
        }
    }

    private void selectCategory(int cat, boolean reload) {
        category = cat;
        paintTabs();
        hint.setText(FeedbackFileSource.categoryHint(cat));
        if (reload) load();
    }

    // ------------------------------------------------------------ 列表

    private void load() {
        final int seq = ++loadSeq;
        items.clear();
        adapter.notifyDataSetChanged();
        empty.setVisibility(View.GONE);
        loading.setVisibility(View.VISIBLE);
        loading.setText(R.string.feedbackfilepickeractivity_034);

        final int cat = category;
        new Thread(new Runnable() {
            public void run() {
                final List<FeedbackFileSource.Item> got =
                        FeedbackFileSource.list(getApplicationContext(), cat);
                MAIN.post(new Runnable() {
                    public void run() {
                        if (seq != loadSeq || isFinishing()) return;
                        loading.setVisibility(View.GONE);
                        items.clear();
                        items.addAll(got);
                        adapter.notifyDataSetChanged();
                        boolean none = items.isEmpty();
                        empty.setVisibility(none ? View.VISIBLE : View.GONE);
                        empty.setText(FeedbackFileSource.categoryEmpty(cat));
                        list.setSelection(0);
                        refreshBar();
                    }
                });
            }
        }, "feedback-pick-load").start();
    }

    private void toggle(FeedbackFileSource.Item item) {
        if (chosen.containsKey(item.key())) {
            chosen.remove(item.key());
            adapter.notifyDataSetChanged();
            refreshBar();
            return;
        }
        if (item.file.length() > FeedbackApi.MAX_FILE_BYTES) {
            AcToast.show(this, Strings.get(R.string.feedbackcomposeactivity_014,
                    item.file.getName()));
            return;
        }
        if (available() <= 0) {
            AcToast.show(this, Strings.get(R.string.feedbackfilepickeractivity_035));
            return;
        }
        chosen.put(item.key(), item);
        adapter.notifyDataSetChanged();
        refreshBar();
    }

    private int available() {
        return FeedbackApi.MAX_FILES - alreadyAttached - chosen.size();
    }

    private void refreshBar() {
        countView.setText(Strings.get(R.string.feedbackfilepickeractivity_029, chosen.size()));
        addButton.setEnabled(!chosen.isEmpty() && !preparing);
        addButton.setText(preparing
                ? R.string.feedbackfilepickeractivity_031
                : R.string.feedbackfilepickeractivity_030);
    }

    // ------------------------------------------------------------ 添加

    /**
     * 把勾选项交给反馈页。打码副本在这里生成：失败的那一项跳过并提示，
     * 不影响其余文件；一个都没成功就留在本页，用户还能重试或换一个文件。
     */
    private void add() {
        if (chosen.isEmpty()) return;
        preparing = true;
        refreshBar();
        loading.setVisibility(View.VISIBLE);
        loading.setText(R.string.feedbackfilepickeractivity_031);

        final List<FeedbackFileSource.Item> picked =
                new ArrayList<FeedbackFileSource.Item>(chosen.values());
        new Thread(new Runnable() {
            public void run() {
                final ArrayList<String> paths = new ArrayList<String>();
                final ArrayList<String> names = new ArrayList<String>();
                final ArrayList<Boolean> masked = new ArrayList<Boolean>();
                int skipped = 0;
                for (int i = 0; i < picked.size(); i++) {
                    FeedbackFileSource.Item it = picked.get(i);
                    File send = it.file;
                    if (it.masked) {
                        send = FeedbackFileSource.maskedCopy(getApplicationContext(), it.file);
                        if (send == null) {
                            skipped++;
                            continue;
                        }
                    }
                    paths.add(send.getAbsolutePath());
                    names.add(uploadName(it));
                    masked.add(Boolean.valueOf(it.masked));
                }
                final boolean[] flags = new boolean[paths.size()];
                for (int i = 0; i < flags.length; i++) flags[i] = masked.get(i).booleanValue();
                final int miss = skipped;
                MAIN.post(new Runnable() {
                    public void run() {
                        if (isFinishing()) return;
                        preparing = false;
                        loading.setVisibility(View.GONE);
                        refreshBar();
                        if (miss > 0) {
                            AcToast.show(FeedbackFilePickerActivity.this,
                                    Strings.get(R.string.feedbackfilepickeractivity_032));
                        }
                        if (paths.isEmpty()) return;
                        Intent data = new Intent();
                        data.putStringArrayListExtra(EXTRA_PATHS, paths);
                        data.putStringArrayListExtra(EXTRA_NAMES, names);
                        data.putExtra(EXTRA_MASKED, flags);
                        setResult(Activity.RESULT_OK, data);
                        finish();
                    }
                });
            }
        }, "feedback-pick-add").start();
    }

    // ------------------------------------------------------------ 预览

    /** 会话详情：与存储页同一套信息，但去掉删除，避免在反馈流程里误删会话。 */
    private void showConversation(final FeedbackFileSource.Item it) {
        String meta;
        String size = Formatter.formatShortFileSize(this, it.file.length());
        String time = DateFormat.format("yyyy-MM-dd HH:mm", it.updatedAt).toString();
        if (it.messageCount >= 0) {
            meta = Strings.get(R.string.feedbackfilepickeractivity_036,
                    it.messageCount, size, time);
        } else {
            meta = Strings.get(R.string.feedbackfilepickeractivity_037, size, time);
        }
        AcDialog.builder(this)
                .setTitle(it.title)
                .setMessage(meta)
                .setPositiveButton(Strings.get(R.string.feedbackfilepickeractivity_025),
                        new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int w) {
                                openConversation(it);
                            }
                        })
                .setNegativeButton(Strings.get(R.string.feedbackfilepickeractivity_038), null)
                .show();
    }

    /**
     * 跳到主界面查看这条会话。用 REORDER_TO_FRONT 而不是新开实例：
     * 主界面持有 WebView 与请求状态，第二个实例会让两份界面同时监听同一份数据。
     * 返回键落回本页，勾选状态还在。
     */
    private void openConversation(FeedbackFileSource.Item it) {
        if (it.conversationId == null || it.conversationId.length() == 0) return;
        Intent i = new Intent(this, MainActivity.class);
        i.putExtra("conversation_id", it.conversationId);
        i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(i);
    }

    private void previewTheme(FeedbackFileSource.Item it) {
        String name = it.file.getName();
        String id = name.endsWith(".actheme")
                ? name.substring(0, name.length() - 8) : name;
        Theme theme = ThemeRepository.getInstance().find(id);
        if (theme == null || theme.fileMissing || theme.versionIncompatible) {
            AcToast.show(this, Strings.get(R.string.feedbackfilepickeractivity_033));
            return;
        }
        Intent i = new Intent(this, ThemePreviewActivity.class);
        i.putExtra("theme_id", id);
        i.putExtra(ThemePreviewActivity.EXTRA_READ_ONLY, true);
        startActivity(i);
    }

    // ------------------------------------------------------------ 适配器

    /**
     * 上传时显示的文件名。会话文件本身叫「<id>.json」，直接上传后没人认得出；
     * 换成会话标题，并去掉文件名里不能用的字符、限长 60。
     */
    private static String uploadName(FeedbackFileSource.Item it) {
        if (it.category != FeedbackFileSource.CAT_CONVERSATION) return it.file.getName();
        String base = it.title.length() > 0 ? it.title : it.file.getName();
        base = base.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
        if (base.length() == 0) base = "conversation";
        if (base.length() > 60) base = base.substring(0, 60);
        return base + ".json";
    }

    private final class PickAdapter extends BaseAdapter {

        public int getCount() {
            return items.size();
        }

        public Object getItem(int position) {
            return items.get(position);
        }

        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = LayoutInflater.from(FeedbackFilePickerActivity.this)
                        .inflate(R.layout.item_feedback_pick_file, parent, false);
                ThemeRecolor.applyTo(row);
                // 行里的「查看 / 预览」只建一次监听，靠 tag 拿当前行数据，滚动时不重复 new
                TextView action = (TextView) row.findViewById(R.id.pick_action);
                action.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        if (preparing) return;
                        Object tag = v.getTag();
                        if (!(tag instanceof FeedbackFileSource.Item)) return;
                        FeedbackFileSource.Item item = (FeedbackFileSource.Item) tag;
                        if (item.category == FeedbackFileSource.CAT_CONVERSATION) {
                            showConversation(item);
                        } else if (item.category == FeedbackFileSource.CAT_THEME) {
                            previewTheme(item);
                        }
                    }
                });
            }
            FeedbackFileSource.Item it = items.get(position);
            if (it == null) return row;

            ImageView icon = (ImageView) row.findViewById(R.id.pick_icon);
            TextView name = (TextView) row.findViewById(R.id.pick_name);
            TextView meta = (TextView) row.findViewById(R.id.pick_meta);
            TextView note = (TextView) row.findViewById(R.id.pick_note);
            TextView action = (TextView) row.findViewById(R.id.pick_action);
            TextView state = (TextView) row.findViewById(R.id.pick_state);

            FileIconBinder.bind(icon, it.file);
            name.setText(it.category == FeedbackFileSource.CAT_CONVERSATION && it.title.length() > 0
                    ? it.title : it.file.getName());

            String size = Formatter.formatShortFileSize(FeedbackFilePickerActivity.this,
                    it.file.length());
            String time = DateFormat.format("MM-dd HH:mm", it.updatedAt).toString();
            if (it.category == FeedbackFileSource.CAT_CONVERSATION && it.messageCount >= 0) {
                meta.setText(Strings.get(R.string.feedbackfilepickeractivity_036,
                        it.messageCount, size, time));
            } else {
                meta.setText(Strings.get(R.string.feedbackfilepickeractivity_037, size, time));
            }
            if (it.noteRes != 0) {
                note.setVisibility(View.VISIBLE);
                note.setText(it.noteRes);
            } else {
                note.setVisibility(View.GONE);
            }

            action.setTag(it);
            if (it.category == FeedbackFileSource.CAT_CONVERSATION) {
                action.setVisibility(View.VISIBLE);
                action.setText(R.string.feedbackfilepickeractivity_025);
            } else if (it.category == FeedbackFileSource.CAT_THEME) {
                action.setVisibility(View.VISIBLE);
                action.setText(R.string.feedbackfilepickeractivity_026);
            } else {
                action.setVisibility(View.GONE);
            }

            boolean picked = chosen.containsKey(it.key());
            state.setText(picked
                    ? R.string.feedbackfilepickeractivity_028
                    : R.string.feedbackfilepickeractivity_027);
            state.setTextColor(getResources().getColor(
                    picked ? R.color.primary : R.color.text_secondary));
            return row;
        }
    }

    @Override
    protected void onDestroy() {
        loadSeq++;
        super.onDestroy();
    }

    /** 生成打码副本期间不接受返回：半途退出会留下写了一半的缓存目录。 */
    @Override
    public void onBackPressed() {
        if (preparing) {
            AcToast.show(this, Strings.get(R.string.feedbackfilepickeractivity_031));
            return;
        }
        super.onBackPressed();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
