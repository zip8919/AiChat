package xyz.zip8919.app.aichat;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ConversationAdapter extends BaseAdapter {
    private Context context;
    private List<Conversation> conversations;
    private LayoutInflater inflater;
    private SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

    public ConversationAdapter(Context context, List<Conversation> conversations) {
        this.context = context;
        this.conversations = conversations;
        this.inflater = LayoutInflater.from(context);
    }

    public void setConversations(List<Conversation> conversations) {
        this.conversations = conversations;
        notifyDataSetChanged();
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
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        Conversation conv = conversations.get(position);
        holder.titleView.setText(conv.title);
        holder.timeView.setText(dateFormat.format(new Date(conv.updatedAt)));
        return convertView;
    }

    static class ViewHolder {
        TextView titleView;
        TextView timeView;
    }
}
