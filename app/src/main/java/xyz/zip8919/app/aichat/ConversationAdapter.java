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
        LogUtil.v(TAG, "getView: pos=%d id=%s title=%s", position, conv.id, conv.title);
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
        holder.timeView.setText(timeText);
        if (selectionMode) {
            boolean checked = selectedIds.contains(conv.id);
            holder.checkView.setVisibility(View.VISIBLE);
            holder.checkView.setChecked(checked);
            convertView.setBackgroundColor(checked ? 0xFFFFF3CD : 0xFFFFFFFF);
        } else {
            holder.checkView.setVisibility(View.GONE);
            holder.checkView.setChecked(false);
            convertView.setBackgroundColor(0xFFFFFFFF);
        }
        return convertView;
    }

    static class ViewHolder {
        TextView titleView;
        TextView timeView;
        CheckBox checkView;
    }
}
