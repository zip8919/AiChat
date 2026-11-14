package xyz.zip8919.app.aichat;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class ConversationAdapter extends BaseAdapter {
    private static final String TAG = "ConvAdapter";
    private static final int MAX_TIME_CACHE = 256;
    private Context context;
    private List<Conversation> conversations;
    private LayoutInflater inflater;
    private SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
    private Map<String, String> timeCache = new HashMap<String, String>();
    private boolean selectionMode = false;
    private Set<String> selectedIds = new HashSet<String>();
    /** 从「最占空间」跳进来时定位的会话；只影响底色，不影响选中。 */
    private String highlightId;

    public void setHighlightId(String id) {
        LogUtil.d(TAG, "setHighlightId: %s", id);
        this.highlightId = id;
        notifyDataSetChanged();
    }

    public ConversationAdapter(Context context, List<Conversation> conversations) {
        this.context = context;
        this.conversations = conversations;
        this.inflater = LayoutInflater.from(context);
    }

    public void setConversations(List<Conversation> conversations) {
        LogUtil.d(TAG, "setConversations: %d", conversations == null ? -1 : conversations.size());
        this.conversations = conversations;
        pruneSelection();
        notifyDataSetChanged();
    }

    public boolean isSelectionMode() {
        return selectionMode;
    }

    public void setSelectionMode(boolean on) {
        LogUtil.d(TAG, "setSelectionMode: %s", on);
        this.selectionMode = on;
        this.selectedIds.clear();
        notifyDataSetChanged();
    }

    public void toggleSelection(int position) {
        if (conversations == null || position < 0 || position >= conversations.size()) {
            return;
        }
        String id = conversations.get(position).id;
        if (selectedIds.contains(id)) {
            selectedIds.remove(id);
        } else {
            selectedIds.add(id);
        }
        LogUtil.d(TAG, "toggleSelection: pos=%d id=%s selected=%d", position, id, selectedIds.size());
        notifyDataSetChanged();
    }

    public void selectAll() {
        if (conversations == null) {
            return;
        }
        selectedIds.clear();
        for (int i = 0; i < conversations.size(); i++) {
            selectedIds.add(conversations.get(i).id);
        }
        LogUtil.d(TAG, "selectAll: %d", selectedIds.size());
        notifyDataSetChanged();
    }

    public void invertSelection() {
        if (conversations == null) {
            return;
        }
        Set<String> inverted = new HashSet<String>();
        for (int i = 0; i < conversations.size(); i++) {
            String id = conversations.get(i).id;
            if (!selectedIds.contains(id)) {
                inverted.add(id);
            }
        }
        selectedIds = inverted;
        LogUtil.d(TAG, "invertSelection: %d", selectedIds.size());
        notifyDataSetChanged();
    }

    public int getSelectedCount() {
        return selectedIds.size();
    }

    public List<String> getSelectedIds() {
        List<String> out = new ArrayList<String>();
        if (conversations == null) {
            return out;
        }
        for (int i = 0; i < conversations.size(); i++) {
            String id = conversations.get(i).id;
            if (selectedIds.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    private void pruneSelection() {
        if (selectedIds.isEmpty() || conversations == null) {
            return;
        }
        Set<String> alive = new HashSet<String>();
        for (int i = 0; i < conversations.size(); i++) {
            alive.add(conversations.get(i).id);
        }
        selectedIds.retainAll(alive);
    }

    @Override
    public int getCount() {
        return conversations != null ? conversations.size() : 0;
    }

    @Override
    public Object getItem(int position) {
        return conversations.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_conversation, parent, false);
            holder = new ViewHolder();
            holder.titleView = (TextView) convertView.findViewById(R.id.conversation_title);
            holder.timeView = (TextView) convertView.findViewById(R.id.conversation_time);
            holder.checkView = (CheckBox) convertView.findViewById(R.id.conversation_check);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        Conversation conv = conversations.get(position);
        // 这里原来每次 bind 写一行 LogUtil.v：String.format 装箱 + logcat 写入
        // （IPC + 内核缓冲）单次 30~80µs，快速滑动时每秒几十次，全花在与刷新
        // 无关的事情上。bind 顺序出问题时用布局边界/adapter 断点查，不再靠日志。
        holder.titleView.setText(ConversationManager.normalizeTitle(conv.title));
        String timeKey = conv.id + ":" + conv.updatedAt;
        String timeText = timeCache.get(timeKey);
        if (timeText == null) {
            timeText = dateFormat.format(new Date(conv.updatedAt));
            if (timeCache.size() >= MAX_TIME_CACHE) {
                timeCache.clear();
            }
            timeCache.put(timeKey, timeText);
        }
        // 副行 = 时间 · 条数 · 体积。体积是用户决定「删哪个」的唯一依据，
        // 只给时间的话，列表只能按「看起来新不新」猜占用。
        StringBuilder sub = new StringBuilder(timeText);
        // 列表项来自索引时 messages 是空的，条数必须取索引里的 messageCount；
        // 只有完整解析出来的会话（messageCount 为 0 但 messages 可能因新建为空）
        // 才退回实际列表长度。
        int msgCount = conv.messageCount > 0 ? conv.messageCount
                : (conv.messages == null ? 0 : conv.messages.size());
        sub.append(" · ").append(msgCount).append(" 条");
        if (conv.fileBytes > 0) {
            sub.append(" · ").append(StorageReport.size(conv.fileBytes));
        }
        holder.timeView.setText(sub.toString());
        // 先把标题/副行文字与底色按主题刷一遍，再按选中/高亮覆盖底色。
        // 顺序不能反：applyTo 会把背景重置成常规底色。
        ThemeRecolor.applyTo(convertView);
        if (selectionMode) {
            boolean checked = selectedIds.contains(conv.id);
            holder.checkView.setVisibility(View.VISIBLE);
            holder.checkView.setChecked(checked);
            // 选中/普通底色不能写死 #FFF3CD/#FFFFFF：getView 每次 bind 都会执行，
            // 会把 ThemeRecolor 刷好的深色底重新盖成白色。
            convertView.setBackgroundColor(checked
                    ? ThemeRecolor.listMarkBg(convertView.getContext(), 0xFFFFF3CD)
                    : ThemeRecolor.listBg(convertView.getContext()));
        } else {
            holder.checkView.setVisibility(View.GONE);
            holder.checkView.setChecked(false);
            // 高亮只表达「你刚点的是这一条」，不能和选中态混淆，所以优先级放在选中之后
            convertView.setBackgroundColor(conv.id.equals(highlightId)
                    ? ThemeRecolor.listMarkBg(convertView.getContext(), 0xFFE3F2FD)
                    : ThemeRecolor.listBg(convertView.getContext()));
        }
        return convertView;
    }

    static class ViewHolder {
        TextView titleView;
        TextView timeView;
        CheckBox checkView;
    }
}
