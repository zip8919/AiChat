package xyz.zip8919.app.aichat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import java.net.HttpURLConnection;
import android.os.Bundle;
import android.os.Handler;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final String PREFS_NAME = "aichat_prefs";
    private static final int REQUEST_CONVERSATION_MANAGER = 1;
    private static final int REQUEST_SCAN = 2;

    private ConfigManager configManager;
    private ConversationManager conversationManager;
    private StorageManager storageManager;
    private SharedPreferences prefs;

    private EditText inputEditText;
    private Button sendButton;
    private WebView conversationWebView;
    private Spinner modelSpinner;
    private Spinner thinkingSpinner;

    private List<Message> messages;
    private List<ModelInfo> availableModels;

    private AtomicBoolean isRequestInProgress = new AtomicBoolean(false);
    private AtomicInteger requestGeneration = new AtomicInteger(0);
    private HttpURLConnection currentConnection;
    private Thread currentRequestThread;
    private Handler handler = new Handler();

    // Image viewer dialog state
    private AlertDialog imageViewerDialog;
    private WebView imageViewerWebView;
    private TextView imageCounterText;
    private Button prevButton, nextButton, zoomOutBtn, zoomInBtn, rotateBtn;
    private List<ImageInfo> currentImageList;
    private int currentImageIndex;
    private int currentRotation;

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
        conversationWebView = (WebView) findViewById(R.id.message_webview);
        conversationWebView.getSettings().setJavaScriptEnabled(true);
        conversationWebView.getSettings().setDefaultTextEncodingName("UTF-8");
        conversationWebView.getSettings().setBuiltInZoomControls(false);
        conversationWebView.getSettings().setLoadWithOverviewMode(true);
        conversationWebView.getSettings().setUseWideViewPort(true);
        conversationWebView.getSettings().setAllowFileAccess(true);
        conversationWebView.getSettings().setAllowFileAccessFromFileURLs(true);
        conversationWebView.addJavascriptInterface(new JsBridge(), "Android");
        conversationWebView.setWebViewClient(new WebViewClient() {
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return true;
            }
        });

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
        findViewById(R.id.rotate_button).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int orient = getResources().getConfiguration().orientation;
                if (orient == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
                    setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                else
                    setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            }
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

        // Scan button
        findViewById(R.id.scan_button).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    startActivityForResult(new Intent(MainActivity.this, ScanActivity.class), REQUEST_SCAN);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "无法启动扫描: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
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
        int levelPos = 2;
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
        refreshWebView();
    }

    private void createNewConversation() {
        if (isRequestInProgress.get()) interruptRequest();
        loadSystemPrompt();
        Conversation conv = conversationManager.createNewConversation();
        conv.systemPrompt = systemPrompt;
        conv.model = currentModel;
        messages = conv.messages;
        refreshWebView();
        Toast.makeText(this, "已创建新对话", Toast.LENGTH_SHORT).show();
    }

    private void interruptRequest() {
        if (!isRequestInProgress.get()) return;
        requestGeneration.incrementAndGet(); // 作废当前请求代数，防止旧线程 finally 污染新请求
        isRequestInProgress.set(false);
        if (currentConnection != null) {
            currentConnection.disconnect();
            currentConnection = null;
        }
        final int msgCountAtInterrupt = messages.size();
        runOnUiThread(new Runnable() {
            public void run() {
                // 如果消息数已变化，说明新请求已启动，跳过清理防止误删
                if (messages.size() != msgCountAtInterrupt) {
                    Toast.makeText(MainActivity.this, "已打断请求", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!messages.isEmpty()) {
                    Message lastMsg = messages.get(messages.size() - 1);
                    if (lastMsg.isAssistant()) {
                        String content = lastMsg.content;
                        if (content == null || content.isEmpty()) {
                            messages.remove(messages.size() - 1);
                            removeDomFrom(messages.size());
                        } else {
                            lastMsg.content = content + " (已打断)";
                            updateAiContent(lastMsg.content);
                        }
                    }
                }
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
                refreshWebView();
                Toast.makeText(this, "已切换到: " + conv.title, Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQUEST_SCAN && resultCode == RESULT_OK) {
            String text = data.getStringExtra("scan_text");
            if (text != null && !text.isEmpty()) {
                inputEditText.setText(text);
                inputEditText.setSelection(text.length());
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

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 旋转时重新适配图片查看器尺寸
        if (imageViewerDialog != null && imageViewerDialog.isShowing()) {
            imageViewerDialog.getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
        }
    }

    private void sendMessage() {
        if (isRequestInProgress.get()) {
            interruptRequest();
            // 延迟重发，等旧请求线程完全退出后再执行，避免竞态崩溃
            handler.postDelayed(new Runnable() {
                public void run() {
                    sendMessage();
                }
            }, 150);
            return;
        }
        String input = inputEditText.getText().toString().trim();
        if (input.isEmpty()) return;

        loadSystemPrompt();

        Message userMsg = new Message(Message.ROLE_USER, input);
        messages.add(userMsg);
        conversationManager.getCurrentConversation().touch();
        inputEditText.setText("");

        // Append user message to WebView
        String userHtml = MessageHtmlRenderer.renderMessageDiv(userMsg, messages.size() - 1, this);
        appendHtml(userHtml);

        Conversation conv = conversationManager.getCurrentConversation();
        boolean isFirstMsg = conv.messages.size() == 1;
        boolean autoTitle = SettingsActivity.isAutoTitleEnabled(this);
        if (isFirstMsg && autoTitle && !conv.titleGenerated) {
            generateTitle(input);
        }

        ProviderInfo provider = configManager.getProvider(
                availableModels.get(modelSpinner.getSelectedItemPosition()).provider);
        if (provider == null) {
            Toast.makeText(this, "未选择模型", Toast.LENGTH_SHORT).show();
            return;
        }

        String thinkingLevel = configManager.getThinkingLevel();
        if (!configManager.isThinkingEnabled()) thinkingLevel = "off";

        int gen = requestGeneration.incrementAndGet();
        sendStreamingRequest(provider, thinkingLevel, gen);
    }

    private void sendStreamingRequest(final ProviderInfo provider, final String thinkingLevel, final int generation) {
        isRequestInProgress.set(true);

        final Message aiMsg = new Message(Message.ROLE_ASSISTANT, "");
        messages.add(aiMsg);
        final int aiIndex = messages.size() - 1;

        // Append AI placeholder div
        runOnUiThread(new Runnable() {
            public void run() {
                conversationWebView.loadUrl("javascript:appendAiDiv()");
            }
        });

        currentRequestThread = new Thread(new Runnable() {
            public void run() {
                final StringBuilder rawContent = new StringBuilder();
                final boolean[] thinkingActive = {false};
                final boolean[] thinkingFinished = {false};

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
                        if (m == aiMsg) continue;
                        org.json.JSONObject mm = new org.json.JSONObject();
                        mm.put("role", m.role);
                        mm.put("content", m.isAssistant() ? ApiClient.removeThinkingContent(m.content) : m.content);
                        msgs.put(mm);
                    }
                    body.put("messages", msgs);

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
                    long lastUpdate = 0;
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
                                if (!thinkingActive[0]) {
                                    thinkingActive[0] = true;
                                    rawContent.append("[thinking]");
                                }
                                rawContent.append(rc);
                            }

                            if (delta.has("content") && !delta.isNull("content")) {
                                String ct = delta.getString("content");
                                if (thinkingActive[0] && !thinkingFinished[0]) {
                                    thinkingFinished[0] = true;
                                    rawContent.append("[/thinking]");
                                }
                                rawContent.append(ct);
                            }

                            // Throttle: light text update at most every 200ms
                            long now = System.currentTimeMillis();
                            if (now - lastUpdate > 200) {
                                lastUpdate = now;
                                final String content = rawContent.toString();
                                runOnUiThread(new Runnable() {
                                    public void run() {
                                        if (requestGeneration.get() == generation && aiIndex < messages.size()) {
                                            messages.get(aiIndex).content = content;
                                            String esc = jsEscape(content);
                                            conversationWebView.loadUrl("javascript:updateLastText('" + esc + "')");
                                        }
                                    }
                                });
                            }
                        } catch (Exception e) { }
                    }
                    // Final update: full render with markdown/LaTeX/highlighting
                    final String finalContent = rawContent.toString();
                    new Thread(new Runnable() {
                        public void run() {
                            final String html = MessageHtmlRenderer.contentToHtml(finalContent, MainActivity.this);
                            runOnUiThread(new Runnable() {
                                public void run() {
                                    // 代数匹配且索引有效时才更新，防止旧请求覆盖新请求
                                    if (requestGeneration.get() == generation && aiIndex < messages.size()) {
                                        messages.get(aiIndex).content = finalContent;
                                        String esc = jsEscape(html);
                                        conversationWebView.loadUrl("javascript:updateLastMsg('" + esc + "')");
                                        conversationWebView.loadUrl("javascript:finalizeLast(" + aiIndex + ")");
                                    }
                                }
                            });
                        }
                    }).start();
                    reader.close();
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            Toast.makeText(MainActivity.this, "请求失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    });
                } finally {
                    if (conn != null) conn.disconnect();
                    // 只有当前请求代数匹配时才清理状态，防止旧线程污染新请求
                    if (requestGeneration.get() == generation) {
                        currentConnection = null;
                        isRequestInProgress.set(false);
                        conversationManager.saveCurrentConversation();
                        final int finalIdx = aiIndex;
                        runOnUiThread(new Runnable() {
                            public void run() {
                                conversationWebView.loadUrl("javascript:finalizeLast(" + finalIdx + ")");
                            }
                        });
                    }
                }
            }
        });
        currentRequestThread.start();
    }

    private void generateTitle(final String firstMessage) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    // Get title model from settings, then find its provider
                    String titleModel = SettingsActivity.getTitleModel(MainActivity.this);
                    ModelInfo tmi = configManager.getModel(titleModel);
                    if (tmi == null) return;
                    ProviderInfo titleProvider = configManager.getProvider(tmi.provider);
                    if (titleProvider == null) return;

                    String titlePrompt = SettingsActivity.getTitlePrompt(MainActivity.this);
                    List<Message> titleMsgs = new ArrayList<Message>();
                    Message sysMsg = new Message(Message.ROLE_SYSTEM, titlePrompt);
                    Message userMsg = new Message(Message.ROLE_USER,
                            "根据以上要求，为以下对话生成标题：\n" + firstMessage);
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

    // ========== WebView helpers ==========

    private void refreshWebView() {
        new Thread(new Runnable() {
            public void run() {
                final String html = MessageHtmlRenderer.buildConversationHtml(messages, MainActivity.this);
                runOnUiThread(new Runnable() {
                    public void run() {
                        conversationWebView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
                    }
                });
            }
        }).start();
    }

    private void appendHtml(String msgHtml) {
        String esc = jsEscape(msgHtml);
        conversationWebView.loadUrl("javascript:appendMsg('" + esc + "')");
    }

    private void updateAiContent(String content) {
        String html = MessageHtmlRenderer.contentToHtml(content, this);
        String esc = jsEscape(html);
        conversationWebView.loadUrl("javascript:updateLastMsg('" + esc + "')");
    }

    private void removeDomFrom(int pos) {
        conversationWebView.loadUrl("javascript:removeFromIdx(" + pos + ")");
    }

    private static String jsEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    // ========== Image info ==========

    private static class ImageInfo {
        String src;
        String alt;
    }

    // ========== JavaScript bridge ==========

    class JsBridge {
        @JavascriptInterface
        public void copyCode(final String code) {
            handler.post(new Runnable() {
                public void run() {
                    try {
                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText("code", code));
                        Toast.makeText(MainActivity.this, "代码已复制", Toast.LENGTH_SHORT).show();
                    } catch (Exception e1) {
                        try {
                            android.text.ClipboardManager oldCm = (android.text.ClipboardManager)
                                    getSystemService(Context.CLIPBOARD_SERVICE);
                            oldCm.setText(code);
                            Toast.makeText(MainActivity.this, "代码已复制", Toast.LENGTH_SHORT).show();
                        } catch (Exception e2) {
                            Toast.makeText(MainActivity.this, "复制失败: " + e2.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            });
        }

        @JavascriptInterface
        public void openUrl(String url) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception e) {
                Toast.makeText(MainActivity.this, "无法打开链接", Toast.LENGTH_SHORT).show();
            }
        }

        @JavascriptInterface
        public void messageMenu(final String idx) {
            handler.post(new Runnable() {
                public void run() {
                    try {
                        showMessageMenu(Integer.parseInt(idx));
                    } catch (Exception e) { }
                }
            });
        }

        @JavascriptInterface
        public void messageLongPress(final String idx) {
            handler.post(new Runnable() {
                public void run() {
                    try {
                        showMessageMenu(Integer.parseInt(idx));
                    } catch (Exception e) { }
                }
            });
        }

        @JavascriptInterface
        public void showImageViewer(final String indexStr, final String imagesJson) {
            handler.post(new Runnable() {
                public void run() {
                    try {
                        showImageViewerDialog(Integer.parseInt(indexStr), imagesJson);
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "查看图片失败", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }
    }

    // ========== 图片查看器 ==========

    private void showImageViewerDialog(int index, String imagesJson) {
        if (imageViewerDialog != null && imageViewerDialog.isShowing()) {
            imageViewerDialog.dismiss();
        }

        List<ImageInfo> images = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(imagesJson);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                ImageInfo info = new ImageInfo();
                info.src = obj.getString("src");
                info.alt = obj.optString("alt", "");
                images.add(info);
            }
        } catch (Exception e) {
            Toast.makeText(this, "无法加载图片列表", Toast.LENGTH_SHORT).show();
            return;
        }

        if (images.isEmpty() || index < 0 || index >= images.size()) {
            Toast.makeText(this, "图片不可用", Toast.LENGTH_SHORT).show();
            return;
        }

        currentImageList = images;
        currentImageIndex = index;
        currentRotation = 0;

        View view = getLayoutInflater().inflate(R.layout.dialog_image_viewer, null);
        imageViewerWebView = (WebView) view.findViewById(R.id.viewer_webview);
        imageCounterText = (TextView) view.findViewById(R.id.viewer_counter);
        prevButton = (Button) view.findViewById(R.id.viewer_prev);
        nextButton = (Button) view.findViewById(R.id.viewer_next);
        zoomOutBtn = (Button) view.findViewById(R.id.viewer_zoom_out);
        zoomInBtn = (Button) view.findViewById(R.id.viewer_zoom_in);
        rotateBtn = (Button) view.findViewById(R.id.viewer_rotate);
        Button closeBtn = (Button) view.findViewById(R.id.viewer_close);

        // Configure WebView for pinch-to-zoom and double-tap zoom
        imageViewerWebView.getSettings().setJavaScriptEnabled(true);
        imageViewerWebView.getSettings().setBuiltInZoomControls(true);
        imageViewerWebView.getSettings().setDisplayZoomControls(false);
        imageViewerWebView.getSettings().setUseWideViewPort(true);
        imageViewerWebView.getSettings().setLoadWithOverviewMode(true);
        imageViewerWebView.getSettings().setSupportZoom(true);
        imageViewerWebView.setBackgroundColor(Color.BLACK);
        imageViewerWebView.setWebViewClient(new WebViewClient() {
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return true;
            }
        });

        prevButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (currentImageIndex > 0) {
                    currentImageIndex--;
                    currentRotation = 0;
                    loadCurrentImage();
                }
            }
        });

        nextButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (currentImageIndex < currentImageList.size() - 1) {
                    currentImageIndex++;
                    currentRotation = 0;
                    loadCurrentImage();
                }
            }
        });

        zoomOutBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                imageViewerWebView.zoomOut();
            }
        });

        zoomInBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                imageViewerWebView.zoomIn();
            }
        });

        rotateBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                currentRotation = (currentRotation + 90) % 360;
                loadCurrentImage();
            }
        });

        closeBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (imageViewerDialog != null) imageViewerDialog.dismiss();
            }
        });

        imageViewerDialog = new AlertDialog.Builder(this)
                .setView(view)
                .setCancelable(true)
                .create();

        imageViewerDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            public void onDismiss(DialogInterface dialog) {
                if (imageViewerWebView != null) {
                    imageViewerWebView.destroy();
                    imageViewerWebView = null;
                }
                imageViewerDialog = null;
            }
        });

        imageViewerDialog.getWindow().setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        imageViewerDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));

        imageViewerDialog.show();
        loadCurrentImage();
    }

    private void loadCurrentImage() {
        if (currentImageList == null || imageCounterText == null) return;

        ImageInfo info = currentImageList.get(currentImageIndex);
        imageCounterText.setText((currentImageIndex + 1) + "/" + currentImageList.size());
        prevButton.setEnabled(currentImageIndex > 0);
        nextButton.setEnabled(currentImageIndex < currentImageList.size() - 1);

        String src = jsEscape(info.src);
        String rotateCss = currentRotation != 0
                ? "-webkit-transform:rotate(" + currentRotation + "deg);transform:rotate(" + currentRotation + "deg);"
                : "";

        String html = "<!DOCTYPE html><html><head>" +
                "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0,user-scalable=yes\">" +
                "<style>" +
                "*{margin:0;padding:0;}" +
                "html,body{width:100%;height:100%;background:#000;display:flex;align-items:center;justify-content:center;}" +
                "img{max-width:100%;max-height:100%;" + rotateCss + "}" +
                "</style></head><body>" +
                "<img src=\"" + src + "\" alt=\"" + jsEscape(info.alt) + "\">" +
                "</body></html>";

        imageViewerWebView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    // ========== 消息长按菜单 ==========

    private void showMessageMenu(final int pos) {
        final Message msg = messages.get(pos);
        String[] items = {"复制", "选择文本", "修改", "删除", "重试", "回溯到此处", "创建分支"};

        new AlertDialog.Builder(this)
                .setTitle("操作消息")
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        switch (which) {
                            case 0: copyMessage(pos); break;
                            case 1: selectText(pos); break;
                            case 2: editMessage(pos); break;
                            case 3: deleteMessage(pos); break;
                            case 4: retryMessage(pos); break;
                            case 5: rollbackTo(pos); break;
                            case 6: branchAt(pos); break;
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String getMessageText(Message msg) {
        if (msg.isAssistant()) return ApiClient.removeThinkingContent(msg.content);
        return msg.content;
    }

    // 1. 复制
    private void copyMessage(int pos) {
        String text = getMessageText(messages.get(pos));
        if (text == null || text.isEmpty()) {
            Toast.makeText(this, "内容为空", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Object svc = getSystemService(Context.CLIPBOARD_SERVICE);
            if (svc instanceof ClipboardManager) {
                ClipboardManager cm = (ClipboardManager) svc;
                cm.setPrimaryClip(ClipData.newPlainText("message", text));
            } else {
                // API 18 fallback: some devices return the old ClipboardManager
                ((android.text.ClipboardManager) svc).setText(text);
            }
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "复制失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // 2. 选择文本
    private void selectText(int pos) {
        String text = getMessageText(messages.get(pos));
        final TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextIsSelectable(true);
        tv.setPadding(32, 32, 32, 32);
        tv.setTextSize(14);
        new AlertDialog.Builder(this)
                .setTitle("选择文本")
                .setView(tv)
                .setPositiveButton("关闭", null)
                .show();
    }

    // 3. 修改
    private void editMessage(final int pos) {
        final Message msg = messages.get(pos);
        final String oldContent = getMessageText(msg);

        final EditText input = new EditText(this);
        input.setText(oldContent);
        input.setMinLines(3);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        input.setLayoutParams(lp);

        new AlertDialog.Builder(this)
                .setTitle("修改消息")
                .setView(input)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        final String newContent = input.getText().toString().trim();
                        if (newContent.isEmpty()) return;
                        showConfirmDialog("确定修改本条消息？", new Runnable() {
                            public void run() {
                                msg.content = newContent;
                                // DOM update: only update this message's div
                                String html = msg.isAssistant()
                                    ? MessageHtmlRenderer.contentToHtml(newContent, MainActivity.this)
                                    : "<div class=\"bubble\">" + MessageHtmlRenderer.esc(newContent) + "</div>";
                                String esc = jsEscape(html);
                                conversationWebView.loadUrl("javascript:updateMsgAt(" + pos + ",'" + esc + "')");
                                conversationManager.saveCurrentConversation();
                                Toast.makeText(MainActivity.this, "已修改", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // 4. 删除
    private void deleteMessage(final int pos) {
        Message msg = messages.get(pos);
        String preview = getMessageText(msg);
        if (preview.length() > 30) preview = preview.substring(0, 30) + "...";
        showConfirmDialog("确定删除本条消息？\n\n" + preview, new Runnable() {
            public void run() {
                messages.remove(pos);
                removeDomFrom(pos);
                // Reindex DOM: update data-idx of remaining messages after pos
                conversationWebView.loadUrl("javascript:reindexFrom(" + pos + ")");
                conversationManager.saveCurrentConversation();
                Toast.makeText(MainActivity.this, "已删除", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // 5. 重试
    private void retryMessage(final int pos) {
        final Message msg = messages.get(pos);
        final int n = messages.size() - pos;

        if (msg.isAssistant()) {
            boolean hasUserBefore = false;
            for (int i = pos - 1; i >= 0; i--) {
                if (messages.get(i).isUser()) { hasUserBefore = true; break; }
            }
            if (!hasUserBefore) {
                Toast.makeText(this, "无法找到对应的用户消息", Toast.LENGTH_SHORT).show();
                return;
            }
            showConfirmDialog("将删除本条及之后共 " + n + " 条消息并重新生成回复，确定？", new Runnable() {
                public void run() {
                    messages.subList(pos, messages.size()).clear();
                    removeDomFrom(pos);
                    execStreamingRequest();
                }
            });
        } else {
            final String uc = msg.content;
            showConfirmDialog("将删除本条及之后共 " + n + " 条消息并重新发送，确定？", new Runnable() {
                public void run() {
                    messages.subList(pos, messages.size()).clear();
                    removeDomFrom(pos);
                    Message um = new Message(Message.ROLE_USER, uc);
                    messages.add(um);
                    conversationManager.getCurrentConversation().touch();
                    // Append user message to DOM
                    String userHtml = MessageHtmlRenderer.renderMessageDiv(um, messages.size() - 1, MainActivity.this);
                    appendHtml(userHtml);
                    execStreamingRequest();
                }
            });
        }
    }

    private void execStreamingRequest() {
        loadSystemPrompt();
        ProviderInfo provider = configManager.getProvider(
                availableModels.get(modelSpinner.getSelectedItemPosition()).provider);
        if (provider == null) {
            Toast.makeText(this, "未选择模型", Toast.LENGTH_SHORT).show();
            return;
        }
        String thinkingLevel = configManager.getThinkingLevel();
        if (!configManager.isThinkingEnabled()) thinkingLevel = "off";
        int gen = requestGeneration.incrementAndGet();
        sendStreamingRequest(provider, thinkingLevel, gen);
    }

    // 6. 回溯到此处
    private void rollbackTo(int pos) {
        if (pos >= messages.size() - 1) {
            Toast.makeText(this, "已在最新位置", Toast.LENGTH_SHORT).show();
            return;
        }
        final int n = messages.size() - pos - 1;
        showConfirmDialog("将删除本条之后共 " + n + " 条消息（保留本条），确定？", new Runnable() {
            public void run() {
                messages.subList(messages.size() - n, messages.size()).clear();
                removeDomFrom(messages.size());
                conversationManager.saveCurrentConversation();
                Toast.makeText(MainActivity.this, "已回溯", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // 7. 创建分支
    private void branchAt(final int pos) {
        final int n = pos + 1;
        showConfirmDialog("将前 " + n + " 条消息复制到新对话分支，确定？", new Runnable() {
            public void run() {
                Conversation current = conversationManager.getCurrentConversation();
                Conversation branch = new Conversation();
                branch.title = current.title + "-分支";
                branch.systemPrompt = current.systemPrompt;
                branch.model = current.model;

                for (int i = 0; i <= pos; i++) {
                    Message src = messages.get(i);
                    Message copy = new Message();
                    copy.role = src.role;
                    copy.content = src.content;
                    copy.timestamp = src.timestamp;
                    branch.messages.add(copy);
                }

                conversationManager.getConversations().add(0, branch);
                conversationManager.saveCurrentConversation();
                StorageManager.getInstance().saveConversation(branch.id, ConversationManager.toJson(branch));

                conversationManager.setCurrentConversation(branch);
                messages = branch.messages;
                refreshWebView();
                Toast.makeText(MainActivity.this, "已创建分支: " + branch.title, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showConfirmDialog(String message, final Runnable onConfirm) {
        new AlertDialog.Builder(this)
                .setTitle("确认")
                .setMessage(message)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { onConfirm.run(); }
                })
                .setNegativeButton("取消", null)
                .show();
    }

}
