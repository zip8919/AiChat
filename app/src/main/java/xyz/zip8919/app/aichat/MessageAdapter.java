package xyz.zip8919.app.aichat;

import android.content.Context;
import android.graphics.Color;
import android.text.SpannableStringBuilder;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.SparseBooleanArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;
import java.util.List;

public class MessageAdapter extends BaseAdapter {
    private static final int VIEW_TYPE_USER = 0;
    private static final int VIEW_TYPE_AI = 1;

    private Context context;
    private LayoutInflater inflater;
    private List<Message> messages;
    private SparseBooleanArray thoughtExpanded = new SparseBooleanArray();

    public MessageAdapter(Context context, List<Message> messages) {
        this.context = context;
        this.messages = messages;
        this.inflater = LayoutInflater.from(context);
    }

    public void setMessages(List<Message> messages) {
        this.messages = messages;
        this.thoughtExpanded = new SparseBooleanArray();
    }

    @Override
    public int getCount() {
        return messages != null ? messages.size() : 0;
    }

    @Override
    public Object getItem(int position) {
        return messages.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public int getViewTypeCount() {
        return 2;
    }

    @Override
    public int getItemViewType(int position) {
        Message msg = messages.get(position);
        return msg.isUser() ? VIEW_TYPE_USER : VIEW_TYPE_AI;
    }

    @Override
    public View getView(final int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        int viewType = getItemViewType(position);

        if (convertView == null) {
            if (viewType == VIEW_TYPE_USER) {
                convertView = inflater.inflate(R.layout.item_message_user, parent, false);
            } else {
                convertView = inflater.inflate(R.layout.item_message_ai, parent, false);
            }
            holder = new ViewHolder();
            holder.textView = (TextView) convertView.findViewById(R.id.message_text);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        Message msg = messages.get(position);
        String content = msg.content;

        if (content != null && content.contains("[thinking]")) {
            SpannableStringBuilder formatted = formatThinkingContent(content, position);
            holder.textView.setText(formatted);
            holder.textView.setMovementMethod(LinkMovementMethod.getInstance());
        } else {
            holder.textView.setText(content);
            holder.textView.setMovementMethod(null);
        }

        return convertView;
    }

    private SpannableStringBuilder formatThinkingContent(String content, final int position) {
        SpannableStringBuilder builder = new SpannableStringBuilder();
        boolean isExpanded = thoughtExpanded.get(position, false);

        int start = 0;
        while (start < content.length()) {
            int thinkStart = content.indexOf("[thinking]", start);
            if (thinkStart == -1) {
                builder.append(content.substring(start));
                break;
            }
            if (thinkStart > start) {
                builder.append(content.substring(start, thinkStart));
            }
            int thinkEnd = content.indexOf("[/thinking]", thinkStart);
            if (thinkEnd == -1 || !content.contains("[/thinking]")) {
                // Thinking not closed yet (streaming in progress)
                // Just show thinking content in gray italic
                String thinkingText = content.substring(thinkStart + 10);
                if (thinkStart > 0) {
                    builder.append(content.substring(0, thinkStart));
                }
                int spanStart = builder.length();
                builder.append(thinkingText);
                int spanEnd = builder.length();
                builder.setSpan(new ForegroundColorSpan(0xFF888888), spanStart, spanEnd, 0);
                builder.setSpan(new StyleSpan(android.graphics.Typeface.ITALIC), spanStart, spanEnd, 0);
                break;
            } else {
                // Closed thinking block
                String thinkingContent = content.substring(thinkStart + 10, thinkEnd);
                if (isExpanded) {
                    int s1 = builder.length();
                    builder.append(thinkingContent);
                    int s2 = builder.length();
                    builder.setSpan(new ForegroundColorSpan(0xFF888888), s1, s2, 0);
                    builder.setSpan(new StyleSpan(android.graphics.Typeface.ITALIC), s1, s2, 0);
                    builder.append("\n\n");
                    int f1 = builder.length();
                    builder.append("── 折叠思考 ──");
                    int f2 = builder.length();
                    builder.setSpan(new ClickableSpan() {
                        @Override
                        public void onClick(View widget) {
                            thoughtExpanded.put(position, false);
                            notifyDataSetChanged();
                        }
                    }, f1, f2, 0);
                    builder.setSpan(new ForegroundColorSpan(Color.parseColor("#2196F3")), f1, f2, 0);
                    builder.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), f1, f2, 0);
                } else {
                    builder.append("...\n\n");
                    int e1 = builder.length();
                    builder.append("── 展开思考（" + thinkingContent.length() + "字）──");
                    int e2 = builder.length();
                    builder.setSpan(new ClickableSpan() {
                        @Override
                        public void onClick(View widget) {
                            thoughtExpanded.put(position, true);
                            notifyDataSetChanged();
                        }
                    }, e1, e2, 0);
                    builder.setSpan(new ForegroundColorSpan(Color.parseColor("#2196F3")), e1, e2, 0);
                    builder.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), e1, e2, 0);
                }
                start = thinkEnd + 11;
            }
        }

        return builder;
    }

    static class ViewHolder {
        TextView textView;
    }
}
