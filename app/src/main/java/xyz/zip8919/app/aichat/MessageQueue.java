package xyz.zip8919.app.aichat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 排队发送：当前回复未结束时，新输入不丢弃也不并发，按顺序排进队列，
 * 等本轮结束由 {@link #drain()} 依次发出。
 *
 * <p>从 MainActivity 抽出（约 250 行）。队列条目带稳定 id，弹窗只持有 id 不持有
 * 位置——drain 可能在用户操作弹窗期间先发走一条，按位置操作会打到错误的条目。
 * 本类独占队列与两个弹窗的状态，Activity 只负责把改动的时机（新建会话、
 * 切换会话、回复结束）转发进来。
 */
final class MessageQueue {

    private static final String TAG = "MessageQueue";

    /** 队列需要宿主提供的三件事；不直接引用 MainActivity，便于单测替换。 */
    interface Host {
        /** 当前是否有请求在飞行中。 */
        boolean isRequestInFlight();

        /** 真正发出一条消息（已从队列移除）。{@code files} 为该条的附件，可为 null。 */
        void sendQueued(String text, List<AttachmentText.File> files);

        /** 打断当前回复，让队首立刻发出。 */
        void interruptForQueued();
    }

    private static final class QueuedMessage {
        final int id;
        /** 用户原话；队列条与弹窗都用它。 */
        final String text;
        /**
         * 附件快照。必须随队列入队时一起拷走：输入框的 pendingAttachments
         * 在入队那一刻就被清空了，晚一步取就只剩文字，文件悄悄丢失。
         */
        final List<AttachmentText.File> files;

        QueuedMessage(int id, String text, List<AttachmentText.File> files) {
            this.id = id;
            this.text = text == null ? "" : text;
            this.files = files == null
                    ? Collections.<AttachmentText.File>emptyList()
                    : new ArrayList<AttachmentText.File>(files);
        }
    }

    private final Activity activity;
    private final Host host;
    private final View bar;
    private final TextView barText;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final List<QueuedMessage> pending = new ArrayList<QueuedMessage>();
    private int nextId = 1;
    /**
     * 重试进行中时暂停放行。
     *
     * <p>不加这个闸门会有一个很隐蔽的失败：用户点「打断并重试」后，旧请求的
     * 收尾仍可能把队首消息发出去，新请求自增代数，重试流程里的代数校验随即失败，
     * 于是<b>用户点了重试却什么都没发生</b>——队列消息替他把重试顶掉了。
     */
    private boolean paused;
    private AlertDialog managerDialog;
    private AlertDialog actionsDialog;
    /** 动作弹窗打开期间请求过重列，等它关闭后再执行。 */
    private boolean managerDirty;
    /** 动作弹窗是"选了动作"关闭的，还是"取消"关闭的。 */
    private boolean actionTaken;

    MessageQueue(Activity activity, Host host, View bar, TextView barText) {
        this.activity = activity;
        this.host = host;
        this.bar = bar;
        this.barText = barText;
        updateBar();
    }

    boolean isEmpty() {
        return pending.isEmpty();
    }

    int size() {
        return pending.size();
    }

    /** 无附件的普通入队。 */
    void enqueue(String text) {
        enqueue(text, null);
    }

    /** 入队并清空输入框由调用方负责；这里只做队列与提示。 */
    void enqueue(String text, List<AttachmentText.File> files) {
        pending.add(new QueuedMessage(nextId++, text, files));
        updateBar();
        LogUtil.i(TAG, "enqueued: len=%d files=%d pending=%d",
                text == null ? 0 : text.length(),
                files == null ? 0 : files.size(), pending.size());
        AcToast.show(activity, "已排队 " + pending.size() + " 条，将在当前回复结束后发送");
    }

    /** 本轮回复结束：若队列非空且当前空闲，取出队首发送。 */
    void drain() {
        if (pending.isEmpty()) return;
        handler.post(new Runnable() {
            public void run() {
                if (paused) {
                    LogUtil.i(TAG, "drain skipped: paused for retry (pending=%d)", pending.size());
                    return;
                }
                if (host.isRequestInFlight() || pending.isEmpty()) {
                    LogUtil.i(TAG, "drain deferred: inFlight=%s pending=%d",
                            host.isRequestInFlight(), pending.size());
                    return;
                }
                QueuedMessage next = pending.remove(0);
                updateBar();
                refreshManager();
                LogUtil.i(TAG, "drain: sending id=%d len=%d remaining=%d",
                        next.id, next.text.length(), pending.size());
                host.sendQueued(next.text, next.files);
            }
        });
    }

    /** 暂停放行：重试流程开始前调用，避免 drain 抢先自增请求代数。 */
    void pause() {
        paused = true;
        LogUtil.d(TAG, "queue paused (pending=%d)", pending.size());
    }

    /**
     * 恢复放行。这里主动 drain 一次：重试可能因为 pos 失效而根本没发出请求，
     * 那种情况下没有任何收尾会再来叫醒队列，排队消息就永远卡住了。
     * 若重试请求正在飞，drain 内部会因 {@code isRequestInFlight()} 自行跳过。
     */
    void resume() {
        paused = false;
        LogUtil.d(TAG, "queue resumed (pending=%d)", pending.size());
        drain();
    }

    /** 丢弃所有排队消息（新建/切换会话时调用，队列属于旧会话）。 */
    void clearAll() {
        if (!pending.isEmpty()) {
            LogUtil.i(TAG, "clearAll: dropping %d queued message(s)", pending.size());
            pending.clear();
        }
        // 队列都换了，暂停状态不能跟着带过去——否则新会话里排队永远不发
        paused = false;
        dismissManager();
        updateBar();
    }

    /** 显示/隐藏输入框上方的排队提示条。仅 UI 线程。 */
    void updateBar() {
        if (bar == null || barText == null) return;
        int n = pending.size();
        if (n == 0) {
            bar.setVisibility(View.GONE);
        } else {
            bar.setVisibility(View.VISIBLE);
            barText.setText("排队 " + n + " 条，将在当前回复结束后依次发送");
        }
    }

    void showManager() {
        if (pending.isEmpty()) {
            AcToast.show(activity, "没有排队中的消息");
            updateBar();
            return;
        }
        final String[] items = new String[pending.size()];
        final int[] ids = new int[pending.size()];
        for (int i = 0; i < pending.size(); i++) {
            QueuedMessage qm = pending.get(i);
            ids[i] = qm.id;
            String s = qm.text.replace('\n', ' ');
            // 只发附件不写字时 text 是空的：列表里出现「3. 」谁也认不出是哪条
            if (s.length() == 0 && !qm.files.isEmpty()) {
                s = "[附件] " + qm.files.get(0).name;
                if (qm.files.size() > 1) s += " 等 " + qm.files.size() + " 个文件";
            }
            if (s.length() > 30) s = s.substring(0, 30) + "…";
            items[i] = (i + 1) + ". " + s;
        }
        managerDialog = AcDialog.builder(activity)
                .setTitle("排队中的消息")
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        showItemActions(ids[which]);
                    }
                })
                .setPositiveButton("关闭", null)
                .setNeutralButton("全部清空", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        LogUtil.i(TAG, "queue: clear all (%d)", pending.size());
                        pending.clear();
                        updateBar();
                        // 列表已空，旧 items 快照不能再留着；与 clearAll() 一样收掉弹窗，
                        // 顺手把 managerDialog 字段置空，别挂着一个已关闭的引用。
                        dismissManager();
                    }
                })
                .show();
    }

    /**
     * 队列在弹窗打开期间被改动（drain 先发走一条）后重列。否则列表行还指向
     * 已经不存在的位置。
     *
     * <p>单条动作弹窗打开时先挂起：这时再弹一层列表会叠在用户正在用的弹窗上。
     * 挂起是安全的——每个动作都按 id 定位，drain 抢先时回报「该消息已发送」。
     */
    void refreshManager() {
        if (actionsDialog != null && actionsDialog.isShowing()) {
            managerDirty = true;
            return;
        }
        if (managerDialog == null || !managerDialog.isShowing()) return;
        managerDialog.dismiss();
        managerDialog = null;
        if (pending.isEmpty()) return;
        showManager();
    }

    void dismissManager() {
        if (managerDialog != null) {
            managerDialog.dismiss();
            managerDialog = null;
        }
    }

    /** 按稳定 id 操作，不按位置。 */
    private void showItemActions(final int id) {
        final int index = indexOf(id);
        if (index < 0) {
            LogUtil.i(TAG, "action on id=%d dropped, already sent (pending=%d)", id, pending.size());
            AcToast.show(activity, "该消息已发送");
            refreshManager();
            return;
        }
        final String[] actions = {"上移", "下移", "插队发送（打断当前回复）", "删除"};
        actionsDialog = AcDialog.builder(activity)
                .setTitle("第 " + (index + 1) + " 条")
                .setItems(actions, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        actionTaken = true;
                        if (which == 0) move(id, -1);
                        else if (which == 1) move(id, 1);
                        else if (which == 2) jump(id);
                        else remove(id);
                    }
                })
                .setNegativeButton("取消", null)
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    public void onDismiss(DialogInterface d) {
                        actionsDialog = null;
                        // 选完动作回到列表，方便连续调整；"取消"则整栈关闭
                        boolean reopen = actionTaken;
                        actionTaken = false;
                        if (managerDirty || (reopen && !pending.isEmpty())) {
                            managerDirty = false;
                            refreshManager();
                            if (reopen && (managerDialog == null || !managerDialog.isShowing())) {
                                showManager();
                            }
                        }
                    }
                })
                .show();
    }

    private int indexOf(int id) {
        for (int i = 0; i < pending.size(); i++) {
            if (pending.get(i).id == id) return i;
        }
        return -1;
    }

    private void move(int id, int delta) {
        int from = indexOf(id);
        if (from < 0) {
            AcToast.show(activity, "该消息已发送");
            refreshManager();
            return;
        }
        int to = from + delta;
        if (to < 0 || to >= pending.size()) {
            AcToast.show(activity, delta < 0 ? "已经在最前" : "已经在最后");
            return;
        }
        QueuedMessage moved = pending.remove(from);
        pending.add(to, moved);
        LogUtil.i(TAG, "move id=%d %d -> %d (pending=%d)", id, from, to, pending.size());
        updateBar();
        refreshManager();
    }

    private void remove(int id) {
        int index = indexOf(id);
        if (index < 0) {
            AcToast.show(activity, "该消息已发送");
            refreshManager();
            return;
        }
        QueuedMessage removed = pending.remove(index);
        LogUtil.i(TAG, "delete id=%d idx=%d len=%d (pending=%d)",
                id, index, removed.text.length(), pending.size());
        updateBar();
        refreshManager();
        if (pending.isEmpty()) {
            AcToast.show(activity, "队列已清空");
        }
    }

    /**
     * 把选中的消息插到队首，然后停掉在飞的回复，让 drain 立刻取到它。
     */
    private void jump(int id) {
        int index = indexOf(id);
        if (index < 0) {
            AcToast.show(activity, "该消息已发送");
            refreshManager();
            return;
        }
        QueuedMessage moved = pending.remove(index);
        pending.add(0, moved);
        LogUtil.i(TAG, "jump id=%d idx=%d to head (pending=%d, inFlight=%s)",
                id, index, pending.size(), host.isRequestInFlight());
        updateBar();
        refreshManager();
        if (host.isRequestInFlight()) {
            host.interruptForQueued();
        } else {
            drain();
        }
    }

    /** Activity 销毁：清掉挂起的 drain 回调，关闭残留弹窗。 */
    void destroy() {
        handler.removeCallbacksAndMessages(null);
        dismissManager();
        if (actionsDialog != null) {
            actionsDialog.dismiss();
            actionsDialog = null;
        }
        pending.clear();
        paused = false;
    }
}
