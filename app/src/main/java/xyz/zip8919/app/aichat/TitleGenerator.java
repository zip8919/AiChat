package xyz.zip8919.app.aichat;

import android.app.Activity;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 会话标题：发送首条消息时先写「前 15 字」兜底标题，再用标题模型异步生成更贴切的。
 *
 * <p>从 MainActivity 抽出（约 115 行）。两处易错点集中在这里，别再散回 Activity：
 * <ul>
 *   <li>{@code titleRequested} 必须在发起请求<b>之前</b>置位，否则首轮回复失败回滚后
 *       再次发送会重复请求两次标题；</li>
 *   <li>异步结果回来时会话可能已经切走，必须用发起时的会话 id 校验，
 *       否则会把 A 会话的标题写到 B 会话上。</li>
 * </ul>
 */
final class TitleGenerator {

    private static final String TAG = "TitleGenerator";

    private TitleGenerator() {
    }

    /**
     * 仅当标题仍是「新对话」或空、且尚未由模型生成过标题时才设置兜底标题。
     * 用户手动改过名（titleGenerated=true）时绝不覆盖。
     */
    static void applyFallback(ConversationManager cm, Conversation conv, String firstMessage) {
        if (conv == null || conv.titleGenerated) return;
        String cur = conv.title == null ? "" : conv.title.trim();
        if (cur.length() > 0 && !"新对话".equals(cur)) {
            LogUtil.d(TAG, "applyFallback skipped: title already '%s'", cur);
            return;
        }
        String ft = TextKit.fallbackTitle(firstMessage);
        if (ft.length() == 0) return;
        conv.title = ft;
        cm.saveCurrentConversation();
        LogUtil.i(TAG, "fallback title set: '%s'", ft);
    }

    /** 用设置里的标题模型生成标题；失败或返回空时保留兜底标题。 */
    static void generate(final Activity activity, final ConfigManager cfg,
            final ConversationManager cm, final String firstMessage) {
        LogUtil.d(TAG, "generate called for message: %s", LogUtil.preview(firstMessage, 100));
        // 记录发起时的会话 id：回调回来若已切会话就丢弃
        final String convId = cm.getCurrentConversation().id;
        // 提示词在主线程读：里面的 {ua} 占位符首次取值会初始化 WebView 内核，
        // 而 WebView 的核心方法只认主线程，放到下面的请求线程上做有概率直接崩。
        final String titlePrompt = SettingsActivity.getTitlePrompt(activity);
        // 后台线程最长要跑 50s（连接+读超时），不能强引用 Activity：
        // 期间退出/旋转页面会让整个 View 树与 WebView 都无法回收。
        final android.content.Context app = activity.getApplicationContext();
        final android.os.Handler main =
                new android.os.Handler(android.os.Looper.getMainLooper());
        new Thread(new Runnable() {
            public void run() {
                try {
                    String titleModel = SettingsActivity.getTitleModel(app);
                    LogUtil.d(TAG, "generate: titleModel=%s", titleModel);
                    ModelInfo tmi = cfg.getModel(titleModel);
                    if (tmi == null) {
                        LogUtil.w(TAG, "generate aborted: model '%s' not found", titleModel);
                        return;
                    }
                    ProviderInfo provider = cfg.getProvider(tmi.provider);
                    if (provider == null) {
                        LogUtil.w(TAG, "generate aborted: provider '%s' not found", tmi.provider);
                        return;
                    }
                    LogUtil.d(TAG, "generate: using %s @ %s", tmi.name, provider.name);

                    List<Message> msgs = new ArrayList<Message>();
                    msgs.add(new Message(Message.ROLE_SYSTEM, titlePrompt));
                    msgs.add(new Message(Message.ROLE_USER,
                            "根据以上要求，为以下对话生成标题：\n" + firstMessage));

                    long t0 = System.currentTimeMillis();
                    ApiClient.CallResult result = ApiClient.callWithError(provider,
                            titleModel, msgs, "", "off");
                    LogUtil.d(TAG, "generate: api returned in %d ms, response=%s, error=%s",
                            System.currentTimeMillis() - t0,
                            LogUtil.preview(result == null ? null : result.response, 400),
                            result == null ? "null-result" : result.error);

                    if (result.response == null) return;
                    JSONObject json = new JSONObject(result.response);
                    JSONArray choices = json.optJSONArray("choices");
                    if (choices == null || choices.length() == 0) {
                        LogUtil.w(TAG, "generate: response has no choices");
                        return;
                    }
                    JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
                    if (msg == null) {
                        LogUtil.w(TAG, "generate: response has no message object");
                        return;
                    }

                    String raw = TextKit.stripThinkTags(msg.optString("content", ""));
                    String title = raw.trim()
                            .replaceAll("[\"''\"'.:;,!，。：；！？]", "").trim();
                    if (title.length() > 20) title = title.substring(0, 20);
                    LogUtil.i(TAG, "generate: raw=%s -> title=%s",
                            LogUtil.preview(raw, 120), title);
                    if (title.length() == 0) {
                        // 模型返回空标题：保留发送时写入的兜底标题
                        LogUtil.i(TAG, "generate: empty result, keep fallback title");
                        return;
                    }

                    final String finalTitle = title;
                    main.post(new Runnable() {
                        public void run() {
                            // getCurrentConversation() 带懒创建副作用：标题线程
                            // 最长跑 50s，期间会话被删会在列表里凭空多一条空会话。
                            Conversation conv = null;
                            List<Conversation> all = cm.getConversations();
                            if (all != null) {
                                for (int k = 0; k < all.size(); k++) {
                                    Conversation c = all.get(k);
                                    if (c != null && convId.equals(c.id)) { conv = c; break; }
                                }
                            }
                            if (conv == null) {
                                LogUtil.d(TAG, "title dropped: conversation changed (started=%s, now=%s)",
                                        convId, conv == null ? "-" : conv.id);
                                return;
                            }
                            if (conv.titleGenerated) {
                                LogUtil.d(TAG, "title not applied (generated already)");
                                return;
                            }
                            conv.title = finalTitle;
                            conv.titleGenerated = true;
                            // conv 是按发起时的 convId 找回的，可能已不是 current；
                            // saveCurrentConversation() 会写到错误会话，按 conv 本身保存
                            cm.saveConversationAsync(conv);
                            LogUtil.i(TAG, "conversation title set to: %s", finalTitle);
                        }
                    });
                } catch (Exception e) {
                    LogUtil.e(TAG, "generate EXCEPTION: " + e.getMessage(), e);
                }
            }
        }) {{ setName("aichat-title-" + System.currentTimeMillis()); }}.start();
    }
}
