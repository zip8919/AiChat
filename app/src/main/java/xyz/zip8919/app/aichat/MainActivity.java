package xyz.zip8919.app.aichat;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import java.net.HttpURLConnection;
import android.os.Bundle;
import android.os.Handler;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    private static final String PREFS_NAME = "aichat_prefs";
    private static final int REQUEST_CONVERSATION_MANAGER = 1;

    private ConfigManager configManager;
    private ConversationManager conversationManager;
    private StorageManager storageManager;
    private SharedPreferences prefs;

    private EditText inputEditText;
    private Button sendButton;
    private ListView messageListView;
    private Spinner modelSpinner;
    private Spinner thinkingSpinner;

    private List<Message> messages;
    private MessageAdapter messageAdapter;
    private List<ModelInfo> availableModels;

    private AtomicBoolean isRequestInProgress = new AtomicBoolean(false);
    private HttpURLConnection currentConnection;
    private Thread currentRequestThread;
    private Handler handler = new Handler();

    private String currentModel;
    private String currentApiKey;
    private String currentApiUrl;
    private String systemPrompt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        this.prefs = getSharedPreferences(PREFS_NAME, 0);
        this.storageManager = StorageManager.getInstance(this);
        this.configManager = ConfigManager.getInstance();
        this.conversationManager = ConversationManager.getInstance();

        if (!storageManager.createDirectories()) {
            Toast.makeText(this, "无法访问外部存储", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, storageManager.getStorageInfo(), Toast.LENGTH_LONG).show();
        }

        configManager.load();
        conversationManager.loadConversations();

        initViews();
        loadSystemPrompt();
        initConversation();
    }

    private void initViews() {
        inputEditText = (EditText) findViewById(R.id.input_edit_text);
        sendButton = (Button) findViewById(R.id.send_button);
        modelSpinner = (Spinner) findViewById(R.id.model_spinner);
        thinkingSpinner = (Spinner) findViewById(R.id.thinking_spinner);
        messageListView = (ListView) findViewById(R.id.message_list);

        // Buttons
        findViewById(R.id.new_conversation_button).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { createNewConversation(); }
        });
        findViewById(R.id.history_button).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openConversationManager(); }
        });
        findViewById(R.id.settings_button).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openSettings(); }
        });

        // Send button
        sendButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { sendMessage(); }
        });
        sendButton.setOnTouchListener(new View.OnTouchListener() {
            private boolean longPressed = false;
            private Runnable longPressRunnable;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        longPressed = false;
                        longPressRunnable = new Runnable() {
                            public void run() {
                                longPressed = true;
                                interruptRequest();
                            }
                        };
                        handler.postDelayed(longPressRunnable, 500);
                        return false;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        handler.removeCallbacks(longPressRunnable);
                        if (!longPressed && isRequestInProgress.get()) {
                            interruptRequest();
                        }
                        return false;
                }
                return false;
            }
        });

        // Thinking spinner
        String[] thinkingLevels = {"关闭", "低", "中", "高"};
        ArrayAdapter<String> thinkingAdapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, thinkingLevels);
        thinkingAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        thinkingSpinner.setAdapter(thinkingAdapter);

        // Set current thinking level
        String level = configManager.getThinkingLevel();
        int levelPos = 2; // default medium
        if ("off".equals(level)) levelPos = 0;
        else if ("low".equals(level)) levelPos = 1;
        else if ("high".equals(level)) levelPos = 3;
        thinkingSpinner.setSelection(levelPos);

        thinkingSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                String[] levels = {"off", "low", "medium", "high"};
                configManager.setThinkingLevel(levels[pos]);
                configManager.save();
            }
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // Model spinner
        refreshModelSpinner();
    }

    private void refreshModelSpinner() {
        availableModels = configManager.getModels();
        List<String> names = new ArrayList<String>();
        for (ModelInfo m : availableModels) {
            names.add(m.name + " (" + m.provider + ")");
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modelSpinner.setAdapter(adapter);

        // Select default
        String defaultModel = configManager.getDefaultModel();
        for (int i = 0; i < availableModels.size(); i++) {
            if (availableModels.get(i).name.equals(defaultModel)) {
                modelSpinner.setSelection(i);
                selectModel(i);
                break;
            }
        }

        modelSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                selectModel(pos);
            }
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void selectModel(int pos) {
        if (pos < 0 || pos >= availableModels.size()) return;
        ModelInfo model = availableModels.get(pos);
        ProviderInfo provider = configManager.getProvider(model.provider);
        if (provider != null) {
            currentModel = model.name;
            currentApiKey = provider.apiKey;
            currentApiUrl = provider.apiUrl;
        }
    }

    private void loadSystemPrompt() {
        systemPrompt = SettingsActivity.getSystemPrompt(this);
    }

    private void initConversation() {
        Conversation conv = conversationManager.getCurrentConversation();
        messages = conv.messages;
        messageAdapter = new MessageAdapter(this, messages);
        messageListView.setAdapter(messageAdapter);
    }

    private void createNewConversation() {
        if (isRequestInProgress.get()) {
            interruptRequest();
        }
        loadSystemPrompt();
        Conversation conv = conversationManager.createNewConversation();
        conv.systemPrompt = systemPrompt;
        conv.model = currentModel;
        messages = conv.messages;
        messageAdapter.setMessages(messages);
        messageAdapter.notifyDataSetChanged();
        Toast.makeText(this, "已创建新对话", Toast.LENGTH_SHORT).show();
    }

    private void interruptRequest() {
        if (!isRequestInProgress.get()) return;
        isRequestInProgress.set(false);
        if (currentConnection != null) {
            currentConnection.disconnect();
            currentConnection = null;
        }
        runOnUiThread(new Runnable() {
            public void run() {
                if (!messages.isEmpty()) {
                    Message lastMsg = messages.get(messages.size() - 1);
                    if (lastMsg.isAssistant()) {
                        String content = lastMsg.content;
                        if (content == null || content.isEmpty()) {
                            messages.remove(messages.size() - 1);
                        } else {
                            lastMsg.content = content + " (已打断)";
                        }
                    }
                }
                messageAdapter.notifyDataSetChanged();
                Toast.makeText(MainActivity.this, "已打断请求", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    private void openConversationManager() {
        startActivityForResult(new Intent(this, ConversationManagerActivity.class),
                REQUEST_CONVERSATION_MANAGER);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CONVERSATION_MANAGER && resultCode == RESULT_OK) {
            String conversationId = data.getStringExtra("conversation_id");
            if (conversationId != null) {
                if (isRequestInProgress.get()) interruptRequest();
                conversationManager.saveCurrentConversation();
                conversationManager.switchConversation(conversationId);
                Conversation conv = conversationManager.getCurrentConversation();
                messages = conv.messages;
                messageAdapter.setMessages(messages);
                messageAdapter.notifyDataSetChanged();
                Toast.makeText(this, "已切换到: " + conv.title, Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadSystemPrompt();
        refreshModelSpinner();
    }

    @Override
    protected void onPause() {
        super.onPause();
        conversationManager.saveCurrentConversation();
    }

    @Override
    protected void onStop() {
        super.onStop();
        conversationManager.saveCurrentConversation();
    }

    private void sendMessage() {
        if (isRequestInProgress.get()) {
            interruptRequest();
            return;
        }
        String input = inputEditText.getText().toString().trim();
        if (input.isEmpty()) return;

        loadSystemPrompt();

        Message userMsg = new Message(Message.ROLE_USER, input);
        messages.add(userMsg);
        conversationManager.getCurrentConversation().touch();
        messageAdapter.notifyDataSetChanged();
        inputEditText.setText("");

        // Auto title generation for first message
        Conversation conv = conversationManager.getCurrentConversation();
        boolean isFirstMsg = conv.messages.size() == 1;
        boolean autoTitle = SettingsActivity.isAutoTitleEnabled(this);
        if (isFirstMsg && autoTitle && !conv.titleGenerated) {
            generateTitle(input);
        }

        // Fire request
        ProviderInfo provider = configManager.getProvider(
                availableModels.get(modelSpinner.getSelectedItemPosition()).provider);
        if (provider == null) {
            Toast.makeText(this, "未选择模型", Toast.LENGTH_SHORT).show();
            return;
        }

        String thinkingLevel = configManager.getThinkingLevel();
        if (!configManager.isThinkingEnabled()) {
            thinkingLevel = "off";
        }

        sendStreamingRequest(provider, thinkingLevel);
    }

    private void sendStreamingRequest(final ProviderInfo provider, final String thinkingLevel) {
        isRequestInProgress.set(true);

        currentRequestThread = new Thread(new Runnable() {
            public void run() {
                final SpannableStringBuilder aiResponse = new SpannableStringBuilder();
                final StringBuilder thinkingBuf = new StringBuilder();
                final StringBuilder contentBuf = new StringBuilder();
                final boolean[] thinkingActive = {false};
                boolean[] thinkingFinished = {false};

                final Message aiMsg = new Message(Message.ROLE_ASSISTANT, "");
                messages.add(aiMsg);
                final int aiIndex = messages.size() - 1;

                HttpURLConnection conn = null;
                try {
                    java.net.URL url = new java.net.URL(provider.apiUrl + provider.chatPath);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setRequestProperty("Authorization", "Bearer " + provider.apiKey);
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(30000);
                    conn.setReadTimeout(0);

                    if (conn instanceof javax.net.ssl.HttpsURLConnection) {
                        // Reuse ApiClient's TLS setup
                        javax.net.ssl.SSLContext ssl = javax.net.ssl.SSLContext.getInstance("TLSv1.2");
                        ssl.init(null, new javax.net.ssl.TrustManager[] {
                            new javax.net.ssl.X509TrustManager() {
                                public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
                                public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
                                public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
                            }
                        }, null);
                        ((javax.net.ssl.HttpsURLConnection) conn).setSSLSocketFactory(ssl.getSocketFactory());
                        ((javax.net.ssl.HttpsURLConnection) conn).setHostnameVerifier(new javax.net.ssl.HostnameVerifier() {
                            public boolean verify(String h, javax.net.ssl.SSLSession s) { return true; }
                        });
                    }

                    currentConnection = conn;

                    // Build body
                    org.json.JSONObject body = new org.json.JSONObject();
                    body.put("model", currentModel);
                    body.put("stream", true);

                    org.json.JSONArray msgs = new org.json.JSONArray();
                    if (systemPrompt != null && !systemPrompt.isEmpty()) {
                        org.json.JSONObject sm = new org.json.JSONObject();
                        sm.put("role", "system");
                        sm.put("content", systemPrompt);
                        msgs.put(sm);
                    }
                    for (Message m : messages) {
                        if (m == aiMsg) continue; // skip placeholder
                        org.json.JSONObject mm = new org.json.JSONObject();
                        mm.put("role", m.role);
                        mm.put("content", m.isAssistant() ? ApiClient.removeThinkingContent(m.content) : m.content);
                        msgs.put(mm);
                    }
                    body.put("messages", msgs);

                    // Thinking params
                    if ("boolean".equals(provider.thinkingType)) {
                        if (!"off".equals(thinkingLevel)) {
                            body.put(provider.thinkingParamName, true);
                            int budget = 4096;
                            if ("low".equals(thinkingLevel)) budget = 2048;
                            else if ("high".equals(thinkingLevel)) budget = 32768;
                            body.put("thinking_budget", budget);
                        }
                    } else {
                        org.json.JSONObject tObj = new org.json.JSONObject();
                        tObj.put("type", "off".equals(thinkingLevel) ? "disabled" : "enabled");
                        body.put(provider.thinkingParamName, tObj);
                        if (!"off".equals(thinkingLevel)) {
                            body.put("reasoning_effort", "high".equals(thinkingLevel) ? "max" : "high");
                        }
                    }

                    java.io.OutputStream os = conn.getOutputStream();
                    os.write(body.toString().getBytes("UTF-8"));
                    os.close();

                    int code = conn.getResponseCode();
                    if (code != 200) {
                        final String err = "HTTP " + code;
                        runOnUiThread(new Runnable() {
                            public void run() { Toast.makeText(MainActivity.this, "请求失败: " + err, Toast.LENGTH_SHORT).show(); }
                        });
                        return;
                    }

                    java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"));
                    String line;
                    while (isRequestInProgress.get() && (line = reader.readLine()) != null) {
                        line = line.trim();
                        if (!line.startsWith("data: ")) continue;
                        String data = line.substring(6);
                        if ("[DONE]".equals(data)) break;

                        try {
                            org.json.JSONObject json = new org.json.JSONObject(data);
                            org.json.JSONArray choices = json.optJSONArray("choices");
                            if (choices == null || choices.length() == 0) continue;
                            org.json.JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                            if (delta == null) continue;

                            if (delta.has("reasoning_content") && !delta.isNull("reasoning_content")) {
                                String rc = delta.getString("reasoning_content");
                                thinkingBuf.append(rc);
                                if (!thinkingActive[0]) {
                                    thinkingActive[0] = true;
                                    aiResponse.append("[thinking]");
                                }
                                int s1 = aiResponse.length();
                                aiResponse.append(rc);
                                aiResponse.setSpan(new ForegroundColorSpan(0xFF888888), s1, aiResponse.length(), 0);
                                aiResponse.setSpan(new StyleSpan(android.graphics.Typeface.ITALIC), s1, aiResponse.length(), 0);
                            }

                            if (delta.has("content") && !delta.isNull("content")) {
                                String ct = delta.getString("content");
                                contentBuf.append(ct);
                                if (thinkingActive[0] && !thinkingFinished[0]) {
                                    thinkingFinished[0] = true;
                                    aiResponse.append("[/thinking]");
                                    // Mark the closing tag
                                    int cs = aiResponse.length() - 11;
                                    aiResponse.setSpan(new ForegroundColorSpan(0xFF888888), cs, aiResponse.length(), 0);
                                }
                                aiResponse.append(ct);
                            }

                            final String currentText = aiResponse.toString();
                            runOnUiThread(new Runnable() {
                                public void run() {
                                    if (aiIndex < messages.size()) {
                                        messages.get(aiIndex).content = currentText;
                                        messageAdapter.notifyDataSetChanged();
                                    }
                                }
                            });
                        } catch (Exception e) {
                            // skip malformed SSE chunks
                        }
                    }
                    reader.close();
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            Toast.makeText(MainActivity.this, "请求失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    });
                } finally {
                    if (conn != null) conn.disconnect();
                    currentConnection = null;
                    isRequestInProgress.set(false);
                    // Save conversation on complete
                    conversationManager.saveCurrentConversation();
                }
            }
        });
        currentRequestThread.start();
    }

    private void generateTitle(final String firstMessage) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    ProviderInfo titleProvider = findTitleProvider();
                    if (titleProvider == null) return;
                    String titleModel = findTitleModel(titleProvider);

                    List<Message> titleMsgs = new ArrayList<Message>();
                    Message sysMsg = new Message(Message.ROLE_SYSTEM,
                            "你是一个标题生成助手。根据用户消息生成3-15字标题。只输出标题本身，禁止输出任何其他文字、解释、标点或换行。");
                    Message userMsg = new Message(Message.ROLE_USER,
                            "生成标题（仅输出标题文字，不要任何其他内容）：\n" + firstMessage);
                    titleMsgs.add(sysMsg);
                    titleMsgs.add(userMsg);

                    ApiClient.CallResult result = ApiClient.callWithError(titleProvider,
                            titleModel, titleMsgs, "", "off");

                    if (result.response != null) {
                        org.json.JSONObject json = new org.json.JSONObject(result.response);
                        org.json.JSONArray choices = json.optJSONArray("choices");
                        if (choices != null && choices.length() > 0) {
                            org.json.JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
                            if (msg != null) {
                                String title = msg.optString("content", "").trim()
                                        .replaceAll("[\"''\"'.:;,!，。：；！？]", "").trim();
                                if (title.length() > 20) title = title.substring(0, 20);

                                final String finalTitle = title.length() > 0 ? title : "新对话";
                                runOnUiThread(new Runnable() {
                                    public void run() {
                                        Conversation conv = conversationManager.getCurrentConversation();
                                        if (conv != null && !conv.titleGenerated) {
                                            conv.title = finalTitle;
                                            conv.titleGenerated = true;
                                            conversationManager.saveCurrentConversation();
                                        }
                                    }
                                });
                            }
                        }
                    }
                } catch (final Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    private ProviderInfo findTitleProvider() {
        // Prefer SiliconFlow with Qwen model for better Chinese title quality
        ProviderInfo sf = configManager.getProvider("硅基流动");
        if (sf != null && sf.apiKey != null && !sf.apiKey.isEmpty()) return sf;
        // Fallback to any provider with an API key
        for (ProviderInfo p : configManager.getProviders()) {
            if (p.apiKey != null && !p.apiKey.isEmpty()) return p;
        }
        return null;
    }

    private String findTitleModel(ProviderInfo provider) {
        if ("硅基流动".equals(provider.name)) return "Qwen/Qwen3.6-35B-A3B";
        if ("DeepSeek".equals(provider.name)) return "deepseek-v4-flash";
        // Use first available model for unknown providers
        List<ModelInfo> models = configManager.getModels();
        for (ModelInfo m : models) {
            if (m.provider.equals(provider.name)) return m.name;
        }
        return "";
    }

}
