# AGENTS.md

面向在本仓库改代码的人与 AI 代理。只想知道怎么安装使用，请看 [README.md](README.md)。

**动手前先读 [§12 兼容性红线](#12-兼容性红线)。** 这里大多数「现代写法」在 API 19 上会直接崩溃或白屏。

## 1. 项目概览

AiChat 是运行在 Android 词典笔上的 AI 对话客户端：传统原生 Android 应用（不用 Compose、不用 AndroidX），消息用 WebView + HTML/CSS 渲染，模型侧走任意 OpenAI 兼容端点。

| 项 | 值 |
|---|---|
| 包名 | `xyz.zip8919.app.aichat` |
| 目标设备 | Android 4.4.2（API 19）词典笔，竖屏窄宽（常见 258dp） |
| minSdk / targetSdk / compileSdk | 18 / 18 / 34 |
| 源码语言 | Java 8；无 Kotlin、无 AndroidX、无第三方 UI 库 |
| 构建 | AGP 8.2.2 + Gradle 8.2 + JDK 17 |
| 规模 | 121 个 Java 文件 / 约 4.6 万行；25 个 Activity；37 个布局 |

`targetSdk 18` 是刻意选择：保留安装时授权，不引入运行时权限流程。改这个值会同时牵动权限、APK 安装器、文件 URI 三条链路。

## 2. 目录结构

```
AiChat/
├── app/
│   ├── build.gradle                  # 版本号、签名、ABI 分包、依赖
│   ├── keystore/mc.jks               # 发布签名库（debug 包同样使用）
│   └── src/main/
│       ├── AndroidManifest.xml       # 25 个 Activity + 2 个 Provider
│       ├── java/xyz/zip8919/app/aichat/   # 121 个类，单包平铺
│       └── res/                      # 37 个布局 + 图标 + values
├── .github/
│   ├── workflows/release.yml         # 推送 v* 标签触发构建与发布
│   └── RELEASE_NOTES.md              # CI 直接读它当 Release 正文
├── docs/
│   └── rendering-test-suite.md       # 渲染能力的手测语料
├── build.sh / build.bat / build.ps1  # 三平台构建封装
├── gradlew / gradlew.bat / gradle/
├── README.md / AGENTS.md / LICENSE
```

Java 源码**单包平铺**，不按功能拆 package。这是既有结构：角色靠类名后缀区分（见 §11.2），文件排序即按角色聚集。

## 3. 模块划分

| 模块 | 职责 | 关键类 |
|---|---|---|
| **请求链路** | 一次回复的编排：组请求体、读 SSE、跑工具轮次、失败分类、收尾 | `ChatStreamRunner` `StreamJob` `ChatTransport` `SseRound` `ToolExecutor` `ChatHistory` `ToolHistoryRecovery` `ToolCalls` `MessageSanitizer` `TraceKit` `StreamRenderer` `RetryPolicy` `ErrorReport` `ApiClient` |
| **联网工具** | 提供给模型的搜索、抓取、发请求能力 | `WebSearchTool` `TavilyTool` `HttpRequestTool` `NetSecurity` |
| **渲染** | 消息 → HTML → WebView；Markdown、公式、代码高亮 | `MessageHtmlRenderer` `MarkdownPipeline` `MathRenderer` `RendererAssets` `CodeHighlighter` `Html` `TextKit` `MarkdownPage` `SourcePage` |
| **界面与交互** | 主界面、查看器、输入、排队、附件、裁剪、扫描、小工具 | `MainActivity` `ViewerController` `ViewerBg` `ViewerExport` `FullscreenInputActivity` `MessageQueue` `AttachmentText` `AttachmentFiles` `ImageTokens` `CropActivity` `CropView` `FileKinds` `FileIcons` `FileIconBinder` `FileActions` `ScanActivity` `ToolsActivity` `JsRunnerActivity` |
| **外观与主题** | 显示大小、字号、字体、明暗、4 套内置主题与自定义主题 | `AppearanceManager` `AppearanceStore` `AppearanceState` `ThemeApplier` `ThemeRecolor` `ThemeTokens` `ThemeResolver` `ThemeCssBuilder` `ThemeRepository` `ThemeValidator` `BuiltinThemes` `ThemeText` `ScalePolicy` `FontRepository` `WebViewRefresher` `ThemedActivity` `AcDialog` `AcToast` + 7 个页面 |
| **配置与模型** | 提供商、模型、提示词预设、连通性测试 | `ConfigManager` `ProviderInfo` `ModelInfo` `Endpoint` `PresetStore` `ProviderTester` `CapabilityProbe` `VisionJudge` `ThinkingLevels` `SettingsActivity` `ModelConfigActivity` `ProviderEditActivity` `ModelEditActivity` `PresetManagerActivity` `ModelSpinnerAdapter` `ConfigRowAdapter` |
| **数据与平台** | 会话落盘、存储治理、上限、日志、崩溃、TLS | `ConversationManager` `ConversationIndex` `Conversation` `Message` `StorageManager` `StorageRefs` `StorageScanner` `StorageReport` `StorageCleaner` `Limits` `SystemInfo` `LogUtil` `CrashCatcher` `TlsCompat` `ConvTime` `TitleGenerator` `HelpText` `ExportHelper` |

三个子系统需要额外说明：

- **请求链路**是不可分割的一圈：`ChatStreamRunner` 只做编排，协议细节在 `ChatTransport` / `SseRound`，工具语义在 `ToolExecutor`，历史语义在 `ChatHistory`。改其中一环要连带检查另外几环的约定。
- **外观与主题**分两层：原生 View 走 `ThemeApplier` + `ThemeRecolor` 按 token 重着色，WebView 走 `ThemeCssBuilder` 生成 CSS 字符串再 `WebViewRefresher` 替换。两层共用一个 token 表 `ThemeTokens`。
- **存储治理**一律"先扫描、再报告、后清理"三段式：`StorageScanner` 产 `StorageReport`，`StorageCleaner` 只接受报告里的可清理项，不自己猜。

## 4. 关键依赖

依赖只有五个，都是必要项：

| 依赖 | 版本 | 用途 | 为什么不能随手换 |
|---|---|---|---|
| `org.conscrypt:conscrypt-android` | 2.5.2 | 老设备 TLS 兼容（提供 AES-GCM 等现代套件） | API 19 自带的 Bouncy Castle 缺套件，现代 CDN 会直接握手失败 |
| `ru.noties:jlatexmath-android` | 0.2.0 | LaTeX / `\ce` 公式渲染 | 纯 Java 实现，能在 4.4 上跑；KaTeX 需要新 WebView |
| `org.commonmark:commonmark` | 0.21.0 | Markdown 解析 | 只用它的 AST 与 GFM 扩展，渲染管线是自写的 |
| `commonmark-ext-gfm-strikethrough` | 0.21.0 | `~~删除线~~` | — |
| `commonmark-ext-gfm-tables` | 0.21.0 | GFM 表格 | — |

其余能力一律用 framework 自带：`HttpURLConnection` 管 HTTP、`org.json` 管 JSON、`BitmapFactory` 管图片。**不要引入 Retrofit / OkHttp / Glide / Gson / Kotlin 标准库**：APK 已经贴着 4.7 MB，且新库普遍要求 API 21+。

## 5. 数据流

### 5.1 一次回复

```
用户点击发送
  └─ MainActivity.sendMessageText            拼用户消息、写标题、起请求
       └─ StreamJob                          不可变参数快照（开关、key、上限）
            └─ ChatStreamRunner.execute()    请求线程，唯一的状态机
                 ├─ ChatHistory.build          组 messages（含工具历史回放）
                 ├─ ChatTransport.open/write   HTTP 连接与请求体
                 ├─ SseRound.read              单轮 SSE / 非流式 JSON 解析
                 ├─ ToolExecutor.run           一批 tool_calls 的执行与配对
                 ├─ StreamRenderer.onProgress  流式节流渲染（base / tail 分离）
                 └─ Session.onSettled        → MainActivity.settleStream
                                                成功：提交正文 + 来源 + 落盘
                                                失败：挂错误卡片 / 回滚空占位
```

### 5.2 渲染

```
Message（一条消息）
  └─ MessageHtmlRenderer   思考块、工具卡、来源区、错误卡、附件卡的结构
       └─ MarkdownPipeline 代码/SVG 屏蔽 → 公式替换 → commonmark 解析 → 回填
            └─ MathRenderer  LaTeX / \ce / 裸命令 → 位图 → SVG data-URI
```

渲染分**流式**与**定稿**两条路径：流式时 `StreamRenderer` 按 200ms 节流刷新，`updateMsgAt` 之外的整段替换必须清空 `lastStreamTrace`（见 §8）；定稿后走完整渲染。

### 5.3 持久化

```
Android/data/xyz.zip8919.app.aichat/files/
├── config.json          提供商、模型、思考等级、密钥
├── appearance.json      外观覆盖（浅色 / 深色分开存）
├── fonts.json           字体索引
├── conversations/       每会话一个 JSON，图片附件同目录
│   └── conversations.idx    NDJSON 元数据索引（标题、条数、字节数、mtime）
├── exports/             代码块与查看器导出
├── themes/              *.actheme
├── fonts/               字体文件
├── camera/              拍照临时输出
└── crash.log            崩溃栈
```

外部存储不可用时整体回退到内部私有目录，路径由 `StorageManager` 探测，**不要自己拼 `getExternalFilesDir`**。所有写盘走"先写 `.tmp`、再改名"的原子写。

### 5.4 工具历史与旧会话

`Message.toolHistory` 是 1.7 之后才有的字段。更早的会话只存了渲染用的卡片文本，续聊时模型看不到当时搜过什么，因此 `ToolHistoryRecovery` 从正文卡片反推工具记录，`ChatHistory` 回放时优先用 `toolHistory`、缺失则用反推结果。

`MessageSanitizer` 在发送前把 messages 修成协议合法顺序：带 `tool_calls` 的 assistant 消息后面必须紧跟对应的 tool 消息，否则端点直接 400。

## 6. 类职责边界

每个类都有明确的"不该做的事"。越界的改动通常会在别处以难复现的时序问题出现。

| 类 | 职责 | 不该做的事 |
|---|---|---|
| `MainActivity` | 生命周期、视图绑定、事件分发、会话列表状态 | 不做协议解析、不拼 HTML |
| `ChatStreamRunner` | 轮次循环、失败分类、收尾决策 | 不碰 View，不直接读写 SharedPreferences |
| `StreamJob` | 一次请求的全部输入，构造后不可变 | 不放运行期可变状态 |
| `SseRound` | 单轮响应的字节流 → display/content/toolCalls | 不认识 Message，不判断重试 |
| `ToolExecutor` | tool_calls → conversation/toolHistory/trace | 不决定"用哪个 key"，由 Host 提供 |
| `ChatTransport` | 请求体、连接、错误体 | 不判断 400 该不该回退 |
| `ChatHistory` | 历史压缩、tool 配对校验、回放 | 不发请求 |
| `ToolHistoryRecovery` | 旧会话：从正文卡片反推工具历史 | 不碰网络，不改消息 |
| `MessageSanitizer` | 发送前修正 messages 的协议顺序 | 不改消息内容与语义 |
| `StreamRenderer` | 流式节流、base 与 tail 分开更新 | 不解析 markdown |
| `TraceKit` | trace 长度上限，只在块边界裁剪 | 不做渲染，不判断内容语义 |
| `RetryPolicy` | 429 重试：退避、`Retry-After`、上限、可打断等待 | 不判断 HTTP 状态，不写 UI |
| `ErrorReport` | 错误详情归类 | 不进任何 API 请求体 |
| `ApiClient` | 非流式调用：标题生成、余额查询 | 不参与对话主链路 |
| `HttpRequestTool` | `http_request`：内网拦截、禁跟跳转、响应截断 | 不决定是否启用（由设置 + `StreamJob` 传入） |
| `NetSecurity` | 内网访问 / 证书校验的进程内开关（默认严格） | 纯容器，不读配置 |
| `WebSearchTool` | 联网工具：搜索、抓取、工具定义、结果整理 | 不直接发 HTTP（交给 `TavilyTool` / DeepSeek 后端） |
| `TavilyTool` | Tavily 搜索 / 抓取后端 | 不组装工具卡片 |
| `MessageHtmlRenderer` | 一条消息 → 一段 HTML（卡片结构） | 不解析 markdown 语法 |
| `MarkdownPipeline` | markdown → html 的流水线 | 不处理公式位图生成 |
| `MathRenderer` | `\ce` / LaTeX / 裸命令 → 位图 | 不碰消息结构 |
| `RendererAssets` | CSS 与 JS 字符串 | 无逻辑 |
| `CodeHighlighter` | 语言 token 规则与高亮 | 不做语法分析，只做词法匹配 |
| `TextKit` / `Html` | 流式纯文本、标题截断、HTML 转义 | 无状态纯函数 |
| `MarkdownPage` / `SourcePage` | `.md` 预览页 / 源码页的 HTML 壳 | 不做源码高亮（那是 `CodeHighlighter`） |
| `ViewerController` | 图片 / 表格 / HTML·SVG 全屏预览；附件查看 | 不持有会话状态 |
| `ViewerBg` / `ViewerExport` | 查看器背景设置 / 导出 PNG·JPEG | 不做解码决策 |
| `FullscreenInputActivity` | 全屏输入编辑，完成后回填主输入框 | 不做草稿持久化（主输入框是唯一数据源） |
| `MessageQueue` | 排队发送（每条带原话与附件快照） | 不知道消息内容怎么发 |
| `AttachmentText` | 附件正文、卡片摘要、老会话迁移、token 估算 | 不读文件，不碰 UI |
| `AttachmentFiles` | 图片：拷进私有目录、导入、缩略图、data URI | 不做文本解码 |
| `ImageTokens` | 图片 token 估算 + 发图像素预算 | 纯函数，不读文件 |
| `CropActivity` / `CropView` | 图片裁剪：框选、旋转、输出 | 不发网络请求，不进会话 |
| `FileKinds` | 扩展名 → 高亮语言 / 能否预览 / 能否运行 | 纯函数，可单测 |
| `FileIcons` / `FileIconBinder` / `FileActions` | 图标自绘、列表绑定、打开/分享/预览 | 不做文件读写策略 |
| `ConfigManager` | 提供商 / 模型 / 思考等级的读写与校验 | 不碰 UI；改名必须同步模型引用 |
| `Endpoint` | 地址拼装纯函数：`join` / `chatUrl` / `tidyChatPath` / `deriveName` | 不发请求，不读配置 |
| `PresetStore` | 提示词预设存取（上限 20，重名自动编号） | 不碰 UI，不认识 SettingsActivity |
| `ProviderTester` | 连通性探测 + 远端模型列表解析 + 错误归类 | 不写 UI，不改配置 |
| `CapabilityProbe` | 能力探测：逐档测思考档位是否被接受（看状态码）+ 换两张不同数字的图各问一次测图片输入 | 不改配置，不落库（结论由调用方写回 `ModelInfo`） |
| `VisionJudge` | 图片输入判定：解析回复正文，两轮各读对应图内数字，两张都读对才算支持 | 纯字符串处理，不碰网络与 Android |
| `ThinkingLevels` | 六档定义与排序、`resolve` 落档、`reasoning_effort` / `thinking_budget` 映射 | 纯函数，不读配置 |
| `AppearanceManager` | 外观系统唯一门面，working / committed 双态 | 不直接落盘，不做 View 变换 |
| `ThemeApplier` | 窗口、字号、字体注入 | 不做颜色反查（那是 `ThemeRecolor`） |
| `ThemeRecolor` | 按 View 原始色值反查 token 并重着色 | 不碰 WebView、透明背景、语义色 |
| `ThemeRepository` | 内置 4 套 + 用户主题扫描、增删导出 | 不建索引文件，不做校验（那是 `ThemeValidator`） |
| `ThemeValidator` / `ThemeText` | `.actheme` 三级校验 + CSS 沙箱过滤 / AI 输出清洗 | 不落盘，不碰 UI |
| `ThemeCssBuilder` | WebView 主题 CSS 字符串 | 不注入，不替换（那是 `WebViewRefresher`） |
| `ScalePolicy` | 显示大小与字号档位口径 | 纯计算，不改配置 |
| `FontRepository` | 字体目录、元数据、Typeface LRU | 不渲染 |
| `ConversationManager` | 会话读写、异步落盘队列 | 不碰 UI |
| `ConversationIndex` | 历史元数据索引：NDJSON、指纹校准、原子写回 | 只影响快慢，不影响正确性 |
| `StorageManager` | 存储路径探测与读写 | 不做清理决策 |
| `StorageScanner` / `StorageReport` / `StorageCleaner` | 扫描、结果、清理执行 | 清理必须接受报告，不自己猜 |
| `Limits` | 运行期上限与配额（volatile 静态字段） | 不持久化，不碰 UI |
| `SystemInfo` | 系统 / WebView 信息与占位符填充 | 不做 IO，不持有 Context |
| `CrashCatcher` | 未捕获异常落盘 | 不吞异常，不弹窗 |
| `TlsCompat` | 老设备 TLS 兼容 | 不改变证书校验策略（风险见 §13） |

## 7. 配置约定

| 约定 | 说明 |
|---|---|
| 模型唯一键 | `ModelInfo.key()` = **提供商 + U+001F + 模型 ID**。默认模型（`default_model`）、标题模型（`title_model`）、主界面选中态（`selected_model` + `selected_model_provider`）一律存这个键，**不要用裸模型 ID**：同一个 ID 挂在两个提供商下是常态，裸 ID 无法区分，会出现"两条都标默认""重启后换了端点" |
| 老配置兼容 | v1.15.0 及之前的配置存的是裸模型 ID。`ConfigManager.findModel()` 先按唯一键匹配、再退回按 ID 匹配；`isDefaultModel()` 对老格式沿用同一口径（同名多条只认第一条），升级不需要迁移动作 |
| 模型重名校验 | `isModelNameTaken(provider, name, self)`——只要求**同一提供商下**不重复。不要改回全局唯一 |
| 提供商唯一标识 | `ProviderInfo.name` 是模型引用它的外键，改名必须同步 `ModelInfo.provider`（`ProviderEditActivity.save` 已处理） |
| 显示名 | `ProviderInfo.displayName` / `ModelInfo.displayName` 只影响界面；`name` 永远是唯一标识 |

## 8. 并发约定

六个标记各管一件事，**不要用其中任何一个去替代另一个**：

| 字段 | 含义 | 谁写 | 谁读 |
|---|---|---|---|
| `requestGeneration` | 请求代数。新请求 / 打断都自增 | UI 线程 | 请求线程、渲染回调 |
| `isRequestInProgress` | 是否有一条请求在跑 | UI 线程 | 队列、发送键、Abort |
| `renderSeq` | 渲染序号。每次发起渲染自增，回来时对不上就丢弃 | 任意线程 | 渲染回调 |
| `lastStreamTrace` | base 层当前已推送的 trace 文本 | UI 线程 | 请求线程（判断要不要写 base） |
| `queue.paused` | 重试期间暂停队列放行 | UI 线程 | drain |
| `__holdRender`（JS） | 手指按在表格/代码块上时暂停重绘，保护横向滚动 | touchstart | touchend 后 180ms |

`lastStreamTrace` 是**两条渲染路径共享**的状态：流式 tick 的 `streamBase`（整段替换）和工具卡片的 `streamAppendBase`（只追加增量）。任何一方写完都要更新它，否则另一方会按旧前缀重渲染，卡片重复或消失。任何"整段替换 `.content`"的操作（刷新整页、`updateMsgAt` 之外的 DOM 重建）都必须把它清空。

- 判断「这条回复还是不是当前这条」→ 用代数（`session.isCurrent(gen)`）。
- 判断「用户有没有打断」→ 用 `isAlive`（代数匹配且仍在进行中）。
- 请求线程写 DOM 前必须先过代数守卫，否则旧请求会覆盖新请求的界面。

## 9. 动态视图着色

`ThemeApplier` 只在 `setContentView` 与 `onResume` 遍历一次当前视图树。以下三类永远刷不到，**必须自己按 token 设色**：

| 场景 | 做法 |
|---|---|
| `new TextView(ctx)` 等纯代码创建的 View，且在 `onResume` 之后 `addView` | 渲染时 `setTextColor(ThemeTokens.get(...))` + `setBackgroundDrawable(ThemeRecolor.cardBg(ctx))` |
| `LayoutInflater.from(ctx)` 出来的 ListView / GridView 行（不在 Activity 的 inflater 上） | 在 `getView` 末尾按上面同样方式设一次 |
| Spinner 下拉项、AlertDialog 内容（独立 Window） | 由 `AcDialog` / `AcToast` 统一处理，不要另起 |

`ThemeRecolor.cardBg()` 在默认外观下返回原 `@drawable/bg_card` 资源，所以默认态不会出现任何色差。

## 10. 改哪里

| 想改的东西 | 打开 |
|---|---|
| 请求体字段 / 参数 | `ChatTransport.buildRequestBody` |
| 支持新的流式事件格式 | `SseRound` |
| 工具执行策略、上限、失败占位 | `ToolExecutor` |
| 失败后能不能「从出错处重试」 | `ChatStreamRunner` 的 finally + `MainActivity.settleStream` |
| 历史怎么压缩、什么时候回放工具记录 | `ChatHistory` |
| 旧会话「模型看不到之前搜过什么」 | `ToolHistoryRecovery` |
| 协议顺序 400 | `MessageSanitizer` |
| trace 太长、卡片渲染越来越慢 | `TraceKit` + `MainActivity.renderTraceAsync` |
| 消息卡片长什么样 | `MessageHtmlRenderer` |
| markdown 扩展语法 | `MarkdownPipeline` |
| 公式渲染 | `MathRenderer` |
| 流式刷新的频率与内容 | `StreamRenderer` |
| 发送键 / 排队 / 长按行为 | `MainActivity.bindSendButton`、`MessageQueue` |
| 发送键图标与启用状态（发送 / 停止） | `MainActivity.refreshSendButton`、`isStopState` |
| 429 重试次数、间隔、退避 | `RetryPolicy` + `SettingsActivity.getRetryPolicy` |
| `http_request` 工具的能力与安全边界 | `HttpRequestTool`（默认关闭，设置页开关） |
| 代码执行器提示词、占位符 | `SettingsActivity.getJsEnvPrompt` + `SystemInfo.fillPlaceholders` |
| 系统 / 标题提示词、占位符 | `SettingsActivity.getSystemPrompt` / `getTitlePrompt`（都在主线程调用，`{ua}` 首次取值要初始化 WebView） |
| 提示词输入框的快捷插入胶囊 | `PlaceholderChips`（四组 token 常量 + `attach`） |
| 提示词预设的增删改与应用 | `PresetManagerActivity` + `PresetStore`；设置页只负责「保存当前为预设」与跳转 |
| 请求地址怎么拼、Base URL / Chat Path 斜杠怎么归一 | `Endpoint`（`chatUrl` / `join` / `tidyChatPath`），页面里不要自己拼 |
| 提供商 / 模型的显示名（别名） | `ProviderInfo.displayName` / `ModelInfo.displayName` + `ConfigManager.providerLabel` / `modelLabel` |
| 连通性测试与失败原因 | `ProviderTester.testProvider` / `testChat`，失败文案由 `describe` / `networkError` 给出 |
| 首启引导文案与顺序 | `HelpText` + `MainActivity.showFirstLaunchDialog` + `KEY_FIRST_LAUNCH_DONE` |
| 附件怎么进提示词 | `AttachmentText.toApi`（由 `apiTextOf` 调用）→ `ChatHistory.apiContentOf` |
| 多模态请求体（图片进 content 数组） | `ChatHistory.buildConversation`（`visionEnabled` 时装配 `image_url` 块）+ `ModelInfo.supportsVision` |
| 图片 token 公式 | `ImageTokens.estimate`（AttachmentText 缓存调用） |
| image_search 工具与图片卡片 | `WebSearchTool` + `TavilyTool` + `RendererAssets`（`.tool-imgs` 网格） |
| 搜索图注入模型视觉 | `ToolExecutor.injectImages`（vision 开启时把结果图转 data URI 进下一轮） |
| 图片 URL / 拍照 / 裁剪导入 | `MainActivity` 三个回调 → `CropActivity` → `importImageFile` → `AttachmentFiles` |
| .md 预览 | `MarkdownPage` + `ViewerController.showDocument` |
| 附件 UI（标签、上限） | `MainActivity.refreshAttachmentBar` / `addAttachment` |
| 各类上限与配额 | `Limits`（设置页写入，`reload` 刷新静态字段） |
| 显示大小 / 字号换算 | `ScalePolicy` |
| 主题色 token 与默认值 | `ThemeTokens` + `res/values/colors.xml` |
| 原生界面重着色的命中规则 | `ThemeRecolor`（按原始色值反查） |
| WebView 侧主题 CSS | `ThemeCssBuilder` |
| `.actheme` 校验与 CSS 沙箱 | `ThemeValidator` |
| 内置主题内容 | `BuiltinThemes`（JSON 常量，不落盘） |
| 主题生成提示词 | `ThemePromptStore` + `ThemePromptTemplateActivity` |
| 字体导入与启用 | `FontRepository`（8 MB 上限） |
| 存储占用分类与清理 | `StorageScanner` / `StorageReport` / `StorageCleaner` |
| 会话列表加载慢 | `ConversationIndex` |
| 版本更新与镜像测速 | `UpdateManager` + `UpdateActivity` |

## 11. 开发规范

### 11.1 代码风格

- **4 空格缩进**，不用 Tab。行宽不强制，但避免长到需要横向滚动。
- **匿名内部类**，不用 lambda 与 stream。现有 600 余处 `new XxxListener() {}` 是统一风格：老设备上可预测、无 desugar 变量。
- **类级 Javadoc 必写**，内容不是"这个类是什么"，而是**为什么这么写、边界在哪**。涉及老设备取舍的地方要写清踩过的坑，避免后人"优化"回去。
- `TAG` 常量 + `LogUtil`：`private static final String TAG = "类名";`，日志统一走 `LogUtil`，前缀由它加 `AiChat/`。**不要用 `System.out` 或裸 `Log.d`**；`LogUtil` 的开关在设置页，绕过它等于绕过了日志开关。
- 空 `catch` 必须写注释说明为什么吞掉。吞异常而不写理由，是本项目最容易被后人改坏的地方。
- 常量集中在类顶部；可配置上限一律走 `Limits`，不要新加散落的 `static final` 阈值。
- 集合显式写泛型（`new ArrayList<String>()`），不用 `var`、不用 `@SuppressWarnings("unchecked")` 糊过去。
- 字符串拼接避免在循环里 `+=`；`StringBuilder` 显式指定初值长度更佳。
- 不要新增 `public` 类，除非它真的跨包可见（整个项目只有一个包，`public` 只给 framework 用：Activity、Provider、Application）。工具类一律包级 + `final` + 私有构造。

### 11.2 命名

| 角色 | 后缀 | 例 |
|---|---|---|
| 页面 | `Activity` | `StorageActivity` |
| 带状态的单例门面 | `Manager` | `AppearanceManager`、`ConversationManager` |
| 纯数据存取 | `Store` / `Repository` | `AppearanceStore`、`ThemeRepository` |
| 无状态策略 / 纯计算 | `Policy` / `Kit` | `RetryPolicy`、`ScalePolicy`、`TraceKit`、`TextKit` |
| 工具能力（给模型用） | `Tool` | `WebSearchTool`、`HttpRequestTool` |
| 编排者 | `Runner` | `ChatStreamRunner` |
| 列表适配器 | `Adapter` | `ManagerRowAdapter` |
| 对话框 / 提示 | `Dialog` / `Toast` | `AcDialog`、`AcToast` |
| 数据模型 | 名词本身 | `Message`、`Conversation`、`Theme` |

资源命名：布局 `activity_*` / `item_*` / `dialog_*`；图标 `ic_*`；背景 `bg_*`；颜色 token 统一 `ac_` 前缀（`ac_primary`、`ac_bg`…），与 `ThemeTokens` 表一一对应。新增颜色 token 必须同时改三处：`colors.xml`、`ThemeTokens`、`ThemeCssBuilder`。

### 11.3 提交

格式：`<type>: <一句话说明>`，必要时带版本号。

```
release: v1.15.2 合并 1.14.24 的占位符胶囊条
fix(1.15.1): 深色列表白底、连通性测试误报证书错误
ci: Release body 直接读 RELEASE_NOTES.md
```

- `type` 取值：`feat` / `fix` / `release` / `ci` / `chore` / `docs` / `refactor`。
- 首行说清**改了什么**，不要写"update""修改若干"。
- 多个独立改动分多次提交；版本号变更单独一次或在 `release:` 提交里。
- 涉及上限、协议、兼容性取舍的改动，在提交正文写**为什么**和**验证方式**。

### 11.4 分支

主干是 **`main`**（默认分支），发布从主干打标签，CI 在标签上构建。

`master` 是并行开发线，改动推到这里后需合并回 `main`，让两条分支指向同一提交，**不要长期分叉**。两条线各自改写过同一批文案时，以功能较新的一侧为准，另一侧的文案修正（转义、缺漏）要逐条补回，避免直接覆盖。

### 11.5 测试

仓库内**没有自动化测试**（`app/src/test` 不存在）。验证靠三条：

1. `./gradlew clean assembleDebug` 必须成功，无新增 warning 级别的资源/清单错误。
2. 改动涉及的功能在真机上手动过一遍，写在提交信息里。
3. 纯逻辑类优先在本地 JVM 桩工程里断言（`.test/`，不入库）：`SseRound`、`ToolExecutor`、`StreamRenderer`、`ChatHistory`、`MarkdownPipeline`、`FileKinds`、`ThemeText`、`TextKit`、`Endpoint`、`MessageSanitizer` 都不依赖 Android 运行时，三个桩（`android.util.Log`、`Context`、`Bitmap`）就能跑。`ChatStreamRunner` 依赖 HTTP，不做单测，靠真机回归。

新增纯逻辑类时，尽量让它不碰 `Bitmap` / `Context`，这样才留得住可测性。

### 11.6 构建

```bash
export KEYSTORE_PASSWORD=<口令>
export KEY_ALIAS=mc
./gradlew clean assembleDebug
```

- **`clean` 不能省**：当前 AGP 的增量打包会在 APK 里留下约 600 KB 空洞字节（5.36 MB vs 4.74 MB，内容一致）。三个 `build.*` 脚本已内置 `clean`。
- 签名口令只从环境变量或 `-PkeystorePassword` / `-PkeyAlias` 读，**不要写进 `build.gradle`**。
- debug 包与 release 包用同一签名（`app/keystore/mc.jks`），否则无法覆盖安装。
- `-PsensenovaKey=...` 把商汤 Key 编进 `BuildConfig`；不传则为空，由设备上手动填写。
- 产物按 ABI 分包：`app-arm64-v8a` / `app-armeabi-v7a` / `app-x86` / `app-x86_64` + `app-universal`。每个包只含对应 ABI 的一份 `libconscrypt_jni.so`。
- 本地只构建 debug，**不在本地构建正式包**。

### 11.7 发布

1. 改 `app/build.gradle`：`versionCode` 单调 +1，`versionName` 按语义化递增。
2. 更新 `.github/RELEASE_NOTES.md`：CI 用 `body_path` 把它当 Release 正文。写用户能看懂的变更，不写内部实现过程。
3. 提交到 `main`。
4. 打标签 `vX.Y.Z`，推送。
5. 等 GitHub Actions 的 `Release APK` 跑完，**以最终状态为准**，不中途假定成功。
6. 确认 Release 里有 5 个 APK + `speedtest.txt`。不从 GitHub 回下载正式包，不校验包内容。

版本号规则：新功能递增次版本（1.15.x → 1.16.0），修复递增修订号（1.15.1 → 1.15.2）。`versionCode` 与版本号无关，只保证单调递增。

### 11.8 文档维护

| 文档 | 面向谁 | 写什么 | 不写什么 |
|---|---|---|---|
| `README.md` | 使用者 | 能做什么、怎么装、怎么用、常见问题 | 实现细节、参数阈值、版本过程、开发备注 |
| `AGENTS.md` | 改代码的人 / 代理 | 架构、边界、约定、红线 | 面向用户的营销文案 |
| `.github/RELEASE_NOTES.md` | 升级用户 | 本版变更 | 内部重构细节 |

规则：

- 行为变了就同步改文档，**同一个提交里改完**。文档滞后比没有文档更糟。
- 主文档只写**当前有效状态**，不写"以前是 X 现在是 Y"的演进过程。
- 新增或修改阈值时，同步 `Limits` 的注释与本文 §12 的对应条目。
- 类职责变化时更新 §6；新增"想改 X"的入口时更新 §10。
- 新增界面文案时的存放位置、命名与写法见 §11.9。

### 11.9 界面文案

**用户能看到、能听到的文字，一律写在资源文件里，代码与布局里不留中文字面量。** 包括 Toast、对话框标题与按钮、列表项、错误提示、空状态、占位符提示、无障碍描述。

存放位置：

| 文案来源 | 写到哪 | 引用方式 |
|---|---|---|
| 布局属性（`android:text` / `hint` / `contentDescription` / `title` / `summary` / `textOn` / `textOff`） | `values/strings_ui.xml` | `@string/xxx` |
| 布局 `android:tag` 里的长说明（点 ⓘ 弹出的解释） | `values/strings_ui.xml` | `@string/xxx` |
| Activity / 有 `Context` 的类 | `values/strings_ui.xml` | `getString(R.string.xxx)` |
| 工具类、管理器、适配器等没有 `Context` 的位置 | `values/strings_ui.xml` | `Strings.get(R.string.xxx)` |

命名：`<类名全小写>_<三位序号>`，例如 `settingsactivity_012`。布局迁移出来的条目沿用布局名前缀（`activity_settings_012`、`activity_settings_tag_001`）。编号不回收、不复用；新增条目顺延当前序列的末尾，**不要占用已存在的编号**（`modelconfigactivity_014` 是「 副本」，覆盖会让复制提供商显示成「默认」）。

写法要求：

- **格式化一律用位置参数** `%1$s` / `%1$d`，后续翻译才能调整语序。
- **一句话就是一条资源**。不要在代码里用 `"前缀" + 变量 + "后缀"` 拼句，也不要拆成多条资源相加——那会让翻译断句。用 `%1$s` 把变量嵌进去。
- 资源里出现字面百分号（如显示大小 `100%`）且带参数时，加 `formatted="false"`，否则 `aapt2` 直接编译失败。
- 引号与反斜杠：资源里的 `"` 写 `\"`，换行写 `\n`。**多写一层反斜杠（`\\"`）会让引号在运行时丢失**，JSON 类字符串会坏掉；改完这类资源要在 `aapt2 dump resources` 里核对实际值。
- 同一句话在不同页面出现时，共用一个资源键（如「取消」），不要每个类各建一条。

不能资源化的三类，**保持字面量并在注释里写明原因**：

| 类型 | 例 |
|---|---|
| 解析历史数据的格式标记 | `AttachmentText.TAG_OPEN`、`ConversationManager.LEGACY_SOURCES_HEADER` |
| 判断分支用的固定值 | `"system".equals(id)`、`case "actheme"` |
| 提示词正文、工具描述与工具返回给模型的文本 | `ThemePromptStore`、`WebSearchTool`、`TavilyTool`、`HttpRequestTool` |

**改文案时不要动判断逻辑。** 菜单分支、`switch` 判断、`equals` 比较如果依赖显示文字，改文案就会让功能静默失效（附件长按「用其他应用打开」曾因此点不动）。列表回调改用位置下标判断，判断用的固定值放进常量并与显示文案分离。

消息区渲染脚本（`RendererAssets.JS`）里的按钮文字用 `@@L_XXX@@` 占位，由 `RendererAssets.localize()` 在生成页面时替换成 `renderer_0xx` 资源；新增脚本文案时照此加占位符 + 资源 + 映射，不要直接写中文。

## 12. 兼容性红线

**目标运行时是 API 19，不是 API 34。** `compileSdk 34` 只决定能调哪些编译期 API，运行期一切以 4.4.2 为准。

| 红线 | 说明 |
|---|---|
| framework API | 只能用 API 18 及以下存在的 API。更高版本 API 必须 `Build.VERSION.SDK_INT` 判断（现有 11 处），不要用 `@RequiresApi` |
| 第三方库 | 不引入 AndroidX、Kotlin、Compose、Retrofit/OkHttp、Glide、Gson；新库普遍要求 API 21+ |
| 布局 | 不用 `ConstraintLayout`、`RecyclerView`、`SwipeRefreshLayout`；用 `LinearLayout` / `RelativeLayout` / `FrameLayout` + `ListView` 复用 |
| 矢量图 | 不用 `VectorDrawable`（minSdk 18 无支持库）。图标用多密度 PNG，或代码自绘（`FileIcons`） |
| WebView | API 19 上是 Chromium 30：**没有 CSS 变量、没有 ES6、没有 fetch/Promise、没有 flex gap**。页面脚本按 ES5 写，颜色靠字符串拼进 CSS（`ThemeCssBuilder` 就是为此存在） |
| 权限 | 无运行时权限，全部安装时授予；不要调用 `requestPermissions` |
| APK 安装 | API 19 的 `PackageInstallerActivity` 只声明 `file` scheme，必须用 `file://`；API 24+ 才走 `FilePathProvider` 的 `content://` + 临时授权 |
| 图片解码 | 必须采样：先读 bounds，再按 `inSampleSize` + `RGB_565` 解码。整图 decode 一张 4000×3000 就会 OOM |
| 内存 | 附件单文件 10 MB、合计 20M 字符（约 40 MB 堆）；代码高亮超 120K 字符关闭、超 400K 只显示前段；`.md` 预览 64K 截断。这些不是产品限制，是防 OOM 与 ANR 的线 |
| 列表 | 一律 `ListView` + ViewHolder 复用；长列表分批渲染（首屏 40 条，上滑每次追加 30 条） |
| 主线程 | 扫描、解码、JSON 解析、渲染一律后台线程；`onResume` 不无条件遍历 View 树（用 `AppearanceManager.revision` 门控） |
| HTTP | `HttpURLConnection`；SSE 长连接读超时设 0，连接超时 30s。不要给流式请求设读超时，模型思考超过阈值会被动断开 |
| 时间 / 格式化 | 不用 `java.time`（API 26+）；用 `SimpleDateFormat` / `Calendar`，并注意线程安全 |

## 13. 安全与凭据

| 项 | 现状 | 约束 |
|---|---|---|
| 商汤 Key | `-PsensenovaKey` 编进 `BuildConfig.SENSENOVA_KEY` | 一旦打包就能被反编译提取；**只通过构建参数 / CI Secrets 注入，不写进仓库源码** |
| Tavily Key | 只存设备本机设置，不进包 | 代码里不得出现默认 Key |
| 签名库 | `app/keystore/mc.jks` 在仓库内，口令走环境变量 | 已知风险：拿到仓库即可签发同签名包。**不要新增任何形式的明文口令** |
| TLS | `TlsCompat` 信任所有证书且主机名校验恒 `true` | 已知风险（中间人）。改动网络层时不要把这个行为扩散到别的 `SSLSocketFactory` |
| 内网访问 | `NetSecurity` 默认严格：拒绝环回 / 私有网段 / 链路本地 / 唯一本地 | 只有用户显式开启并二次确认才放开 |
| `http_request` | 默认关闭；三道边界：禁内网、禁跟 3xx、响应截断 8KB | 不要把默认值改成开启 |
| 日志 | `LogUtil` 无脱敏 | 新增日志**不得**包含 Key、`Authorization` 头、完整请求体 |

`targetSdk 18` 下 `file://` 外发不触发 `FileUriExposedException`，但这不构成把私有目录暴露出去的理由：对外分享一律走 `FilePathProvider`。

## 14. 调试

### 14.1 日志

```bash
adb logcat -s "AiChat/*"
```

`LogUtil` 会给每条日志加 `AiChat/<调用类>` 前缀。设置页的「调试日志」开关控制 `v` / `d` 级别是否输出，`w` / `e` 始终输出。

### 14.2 崩溃

未捕获异常由 `CrashCatcher` 追加到 `Android/data/xyz.zip8919.app.aichat/files/crash.log`（含 `Caused by` 链、机型、系统版本、ABI）。用户不连 adb 也能用文件管理器取出。

### 14.3 一次请求的形状

```
>>> request START: gen=3 ... webSearch=true backend=tavily
HTTP 200 in 812 ms (round=0)
HTTP 200 in 1204 ms (round=1)      ← 工具轮次
rounds done: sources=5 apiText=842 toolHistory=4
<<< request end: elapsed=2431 ms, chunks=67, failed=false
```

失败时最后一行是 `failed=true`，往前找 `request failed: HTTP xxx` 或 `streaming request exception`。错误卡片里的细节与 `ErrorReport` 一致。
