# 架构说明

面向改代码的人：**想改某个行为，应该打开哪个文件**。

## 一次回复的完整链路

```
用户点击发送
  └─ MainActivity.sendMessageText            拼用户消息、写标题、起请求
       └─ StreamJob                          不可变参数快照（开关、key、上限、快照）
            └─ ChatStreamRunner.execute()    请求线程，唯一的状态机
                 ├─ ChatHistory.build          组 messages（含工具历史回放）
                 ├─ ChatTransport.open/write   HTTP 连接与请求体
                 ├─ SseRound.read              单轮 SSE / 非流式 JSON 解析
                 ├─ ToolExecutor.run           一批 tool_calls 的执行与配对
                 ├─ StreamRenderer.onProgress  流式节流渲染（base / tail 分离）
                 └─ Session.onSettled        → MainActivity.settleStream
                                                成功：提交正文 + 来源 + 落盘
                                                失败：挂错误卡片 / 回滚空占位
  WebView 渲染                                 MessageHtmlRenderer
                                                 └─ MarkdownPipeline
                                                      └─ MathRenderer
```

## 类职责

| 类 | 职责 | 不该做的事 |
|---|---|---|
| `MainActivity` | 生命周期、视图绑定、事件分发、会话列表状态 | 不做协议解析、不拼 HTML |
| `ChatStreamRunner` | 轮次循环、失败分类、收尾决策 | 不碰 View，不直接读写 SharedPreferences |
| `StreamJob` | 一次请求的全部输入，构造后不可变 | 不放运行期可变状态 |
| `SseRound` | 单轮响应的字节流 → display/content/toolCalls | 不认识 Message，不判断重试 |
| `ToolExecutor` | tool_calls → conversation/toolHistory/trace | 不决定「用哪个 key」，由 Host 提供 |
| `ChatTransport` | 请求体、连接、错误体 | 不判断 400 该不该回退 |
| `StreamRenderer` | 流式节流、base 与 tail 分开更新 | 不解析 markdown |
| `ChatHistory` | 历史压缩、tool 配对校验、回放 | 不发请求 |
| `ToolHistoryRecovery` | 旧会话：从正文卡片反推工具历史 | 不碰网络，不改消息 |
| `HttpRequestTool` | `http_request` 工具：内网拦截、跳转禁跟、响应截断 | 不决定是否启用（由设置 + `StreamJob` 传入） |
| `RetryPolicy` | 429 重试：退避、`Retry-After`、上限、可打断等待 | 不判断 HTTP 状态，不写 UI |
| `SystemInfo` | 系统 / WebView 信息与提示词占位符填充 | 不做 IO，不持有 Context |
| `TraceKit` | trace 长度上限，只在块边界裁剪 | 不做渲染，不判断内容语义 |
| `MessageHtmlRenderer` | 一条消息 → 一段 HTML（卡片结构） | 不解析 markdown 语法 |
| `MarkdownPipeline` | markdown → html 的流水线 | 不处理公式位图生成 |
| `MathRenderer` | `\ce` / LaTeX / 裸命令 → 位图 | 不碰消息结构 |
| `RendererAssets` | CSS 与 JS 字符串 | — |
| `ViewerController` | 图片 / 表格 / HTML·SVG 全屏预览；附件文件查看（源码高亮 + 预览/运行） | 不持有会话状态 |
| `FullscreenInputActivity` | 全屏输入编辑，完成后回填主输入框 | 不做草稿持久化（主输入框是唯一数据源） |
| `AboutActivity` | 版本展示、检查更新、反馈、开源许可 | 不发起版本网络请求（功能待开发） |
| `MessageQueue` | 排队发送（每条带原话与附件快照） | 不知道消息内容怎么发 |
| `AttachmentText` | 附件 → 给模型的正文、卡片摘要、老会话迁移；大小与 token 估算（图片走 `ImageTokens`） | 不读文件，不碰 UI |
| `AttachmentFiles` | 图片：拷进私有目录（内容 SHA-1 命名）、URL/拍照导入、缩略图、API/view 两级 data URI | 不做文本解码 |
| `ImageTokens` | 图片 token 估算（DeepSeek 官方计算器：544²~1300² 缩放、上限 1024、`H×(W+1)+2`） | 纯函数，不读文件 |
| `CropActivity` / `CropView` | 图片裁剪：框选手柄、旋转、输出到私有目录 | 不发网络请求，不进会话 |
| `MarkdownPage` | .md 文件预览页（MarkdownPipeline 渲染 + 文档样式） | 不做源码高亮（那是 `SourcePage`） |
| `FileKinds` | 扩展名 → 高亮语言 / 能否预览 / 能否运行 | 纯函数，可单测 |
| `NetSecurity` | 内网访问 / 证书校验的进程内开关（默认严格） | 纯容器，不读配置 |
| `HelpText` | 使用说明与免责声明文案 | 不拼 UI，不发请求 |
| `ConversationManager` | 会话读写、异步落盘队列 | 不碰 UI |

## 并发约定

三个标记各管一件事，**不要用其中任何一个去替代另一个**：

| 字段 | 含义 | 谁写 | 谁读 |
|---|---|---|---|
| `requestGeneration` | 请求代数。新请求 / 打断都自增 | UI 线程 | 请求线程、渲染回调 |
| `isRequestInProgress` | 是否有一条请求在跑 | UI 线程 | 队列、发送键、Abort |
| `renderSeq` | 渲染序号。每次发起渲染自增，回来时对不上就丢弃 | 任意线程 | 渲染回调 |
| `lastStreamTrace` | base 层当前已推送的 trace 文本 | UI 线程 | 请求线程（判断要不要写 base） |
| `queue.paused` | 重试期间暂停队列放行 | UI 线程 | drain |
| `__holdRender`（JS） | 手指按在表格/代码块上时暂停重绘，保护横向滚动 | touchstart | touchend 后 180ms |

`lastStreamTrace` 是**两条渲染路径共享**的状态：流式 tick 的 `streamBase`（整段替换）
和工具卡片的 `streamAppendBase`（只追加增量）。任何一方写完都要更新它，
否则另一方会按旧前缀重渲染，卡片重复或消失。任何「整段替换 `.content`」的操作
（刷新整页、`updateMsgAt` 之外的 DOM 重建）都必须把它清空。

- **判断「这条回复还是不是当前这条」→ 用代数**（`session.isCurrent(gen)`）。
- **判断「用户有没有打断」→ 用 `isAlive`**（代数匹配且仍在进行中）。
- 请求线程写 DOM 前必须先过代数守卫，否则旧请求会覆盖新请求的界面。

## 改哪里

| 想改的东西 | 打开 |
|---|---|
| 请求体字段 / 参数 | `ChatTransport.buildRequestBody` |
| 支持新的流式事件格式 | `SseRound` |
| 工具执行策略、上限、失败占位 | `ToolExecutor` |
| 失败后能不能「从出错处重试」 | `ChatStreamRunner` 的 finally + `MainActivity.settleStream` |
| 历史怎么压缩、什么时候回放工具记录 | `ChatHistory` |
| 旧会话「模型看不到之前搜过什么」 | `ToolHistoryRecovery` |
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
| 首启引导文案与顺序 | `HelpText` + `MainActivity.showFirstLaunchDialog` + `KEY_FIRST_LAUNCH_DONE` |
| 附件怎么进提示词 | `AttachmentText.toApi`（由 `apiTextOf` 调用）→ `ChatHistory.apiContentOf` |
| 多模态请求体（图片进 content 数组） | `ChatHistory.buildConversation`（`visionEnabled` 时装配 `image_url` 块）+ `ModelInfo.supportsVision` |
| 图片 token 公式 | `ImageTokens.estimate`（AttachmentText 缓存调用） |
| image_search 工具与图片卡片 | `WebSearchTool` + `TavilyTool` + `RendererAssets`（`.tool-imgs` 网格） |
| 搜索图注入模型视觉 | `ToolExecutor.injectImages`（vision 开启时把结果图转 data URI 进下一轮） |
| 图片 URL / 拍照 / 裁剪导入 | `MainActivity` 三个回调 → `CropActivity` → `importImageFile` → `AttachmentFiles` |
| .md 预览 | `MarkdownPage` + `ViewerController.showDocument` |
| 附件 UI（标签、上限） | `MainActivity.refreshAttachmentBar` / `addAttachment` |

## 调试

**抓日志**：`adb logcat -s StreamRunner MainActivity SseRound ToolExecutor`

一次请求在 logcat 里的形状：

```
>>> request START: gen=3 ... webSearch=true backend=tavily
HTTP 200 in 812 ms (round=0)
HTTP 200 in 1204 ms (round=1)      ← 工具轮次
rounds done: sources=5 apiText=842 toolHistory=4
<<< request end: elapsed=2431 ms, chunks=67, failed=false
```

失败时最后一行是 `failed=true`，往前找 `request failed: HTTP xxx` 或
`streaming request exception`，错误卡片里的细节与 `ErrorReport` 一致。

**单测**：`SseRound`、`ToolExecutor`、`StreamRenderer`、`ChatHistory`、`MarkdownPipeline`
都不依赖 Android 运行时，用 JVM 桩工程直接喂输入断言输出即可
（`android.util.Log`、`Context`、`Bitmap` 三个桩就够）。
`ChatStreamRunner` 依赖 HTTP，不做单测，靠真机回归。

## 回归清单（改动请求链路后必跑）

1. 普通对话：流式开 / 关各一次，中途长按打断
2. 联网搜索：搜索 → 卡片 → 续写，卡片上的链接可点
3. 工具轮次失败：错误卡片出现，「从出错处重试」能继续
4. 首轮失败：用户消息上挂重试，点击后重发
5. 排队：回复中发第二条，队列管理里上移 / 下移 / 插队
6. 切会话 / 新建：进行中的请求被正确丢弃，不串消息
7. 杀进程重进：历史消息、工具记录、来源都在
8. 旧会话续聊：升级前的会话里带搜索卡片的回复，追问一句「刚才查到什么」，
   模型能说出当时的结论（验证 `ToolHistoryRecovery`）
9. 排队 + 重试：回复中排队一条，再点「打断并重试」——重试要真的执行，
   排队那条在重试结束后发出（验证 `MessageQueue.pause/resume`）
10. 多轮搜索（连续触发 5 次以上工具）：每轮卡片都应立刻出现，
    滑动不卡顿（验证增量渲染 + `TraceKit`）
11. 生成中长按正在输出的气泡：弹出「复制已生成内容 / 选择文本 / 停止生成」
12. 生成中含宽表格的回答：手指在表格上横向拖动，位置不能被重绘拉回
13. **发送键状态机**：输入框为空且生成中 → 显示方块（停止）；有文字 → 显示纸飞机（发送）；
    文字清空后立刻回到停止态，不能出现「既能发又能停」的中间态
14. **全屏输入**：有文字时四角图标出现，点开编辑后「完成」回填、「取消」丢弃，
    返回键等同于取消且不改动原输入
15. **429 重试**：断网/限流时自动重试，重试期间「停止」能立即打断等待；
    重试次数耗尽后错误卡片带「已自动重试 N 次仍失败」
16. **Token 与时间**：每条消息底部显示时间；AI 消息带 token 与 tok/s；
    端点不支持 `stream_options` 时应自动降级且只降级一次，后续请求不再带该字段
17. **首启引导**：清除数据后首启弹免责声明，确认前不会发出任何外部 Intent 带来的消息；
    「关于」页版本号与实际 APK 一致，「关于 → 使用说明」与首启弹窗文字完全相同
18. **附件**：选一个 .txt → 输入框内容不变、上方出现文件标签；点标签可移除；
    发送后正文里不含任何附件元信息，气泡下方是独立的附件卡片；
    排队发送时附件不丢（`MessageQueue` 带附件快照）
19. **思考块标题**：展开再收起后，括号里的 token 数不变（走 `data-tok`，不按可见文本重算）
20. **429 重试上限**：设置里能填到 20，填 21 会被夹回 20；填 0 会被夹回 1
21. **附件真的被读到了**：附一个 .txt 只写「总结这个文件」，模型回答里必须出现文件内容
    的关键词；同一会话再追问细节，仍能答上来（说明历史回放也带了正文）
22. **附件元信息**：标签第二行显示真实大小与 `~tokens`；10 MB 文件能附上，超 10 MB 报错
23. **立即重试**：把重试次数设 3、间隔 10 s，用限流 Key 触发 429，卡片出现后点「立即重试」，
    应当立刻重发而不是等满 10 s；点「停止」仍然能中断
24. **内网开关**：默认关闭时 `http://192.168.1.1` 被拒；开启（弹窗确认后）能连上；
    取消确认时开关自动拨回
25. **证书开关**：关闭时访问自签证书站点失败并在错误详情里看到提示；开启后能连上
26. **改消息不丢附件**：给一条带附件的老消息点「修改」，只改文字并确定——
    附件卡片必须还在原位，正文里不能冒出「[附件]」这一行
27. **老会话迁移**：升级前带附件的会话打开后应显示为独立卡片，正文只剩用户原话；
    再追问一句文件里的细节，模型仍能答上（说明迁移后正文没丢）
28. **重试保留附件**：对带附件的用户消息点「重试」，重发的那条必须仍带附件卡片
29. **token 估算**：纯中文 100 字左右应显示 `~60 tokens`，纯英文 400 字符左右应显示
    `~120 tokens`；端点在流式里返回 `usage` 时应显示不带 `~` 的真实值
30. **429 退避按轮次独立**：基数设 1 s，用限流 Key 触发一次带工具的回复——
    第 1 轮连吃两次 429 后等 1 s、2 s；第 2 轮再遇到 429 必须重新等 1 s，
    不能变成 4 s（`V` 用例用本地端点覆盖了这条，真机再确认一次体感）
31. **标题提示词占位符**：把标题提示词改成含 `{model}` 的文案，新会话生成标题后
    检查请求体里机器型号已被替换（日志里 `generate:` 一行可见）
32. **查看附件**：给消息附一个 `.java` → 点卡片，查看器顶部显示文件名、正文带颜色；
    Android 4.4 上横向滑动与缩放都跟手（源码不折行是刻意设计）
33. **HTML/SVG 预览**：附一个 `.html`（含 `<style>` / `<script>`）→ 点卡片看到源码，
    右上角「预览」→ 变成渲染结果，按钮变「源码」可切回；`.svg` 同理，图形应居中且不被工具栏遮住
34. **JS 运行**：附一个 `.js` → 右上角是「运行」而不是「预览」，点开进脚本运行器，
    控制台能打印 `console.log` 的输出
35. **大文件**：附一个 2 MB 的日志 → 查看器只渲染前 40 万字符，顶部黄条说明已截断；
    滑动不能卡顿（截断就是为了这个）
36. **图片附件**：加号 → 添加图片 → 卡片上出现 36dp 缩略图与「大小 · 尺寸 · 仅本机」；
    点卡片进图片查看器，可缩放/旋转；杀掉进程重进会话，缩略图与点开都还正常
37. **图片不进请求体**：附图片后发一句问题，请求体里只有「[图片附件] x.jpg（…）」与
    「未包含图像内容」，不含 base64；模型不会声称看到了图
38. **多模态输入**：DeepSeek Flash 系列（`deepseek-flash` / `deepseek-v4-flash`）默认已勾
    （升级迁移自动开，用户关掉后不再翻回）；附图发问——请求体是 `content` 数组
    （text + image_url data URI），模型能描述图片内容；取消勾选后回到纯文本路径（同 #37）
39. **图片 token**：附一张 1024×1024 图，卡片显示 `~652 tokens`（DeepSeek 官方计算器值）；
    `detail:"low"` 口径约 184；端点返回 `usage` 时以返回值为准、不带 `~`
40. **md 预览**：附一个 `.md` → 右上角「预览」→ 渲染成排版文档，按钮变「源码」可切回；
    超 64K 的 md 只渲染前段
41. **图片 URL 导入**：加号 → 从链接添加图片 → 输入 https 图片地址，成功后进裁剪页；
    内网地址被拒（走 `assertPublicHost` 同一道校验）
42. **拍照 + 裁剪**：拍照 → 裁剪页框选/旋转 → 确认后卡片出现缩略图；
    选图文件同样先过裁剪页；取消裁剪不落任何附件
43. **image_search**：问「搜几张橘猫的图」→ 出现图片网格卡片（缩略图 + 张数），
    点缩略图进图片查看器；视觉开启时追问「图里是什么」模型能答（注入生效，
    只内嵌前 2 张且带 detail=low，约 184 token/张，其余图只留链接）
