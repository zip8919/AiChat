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
| `ViewerController` | 图片 / 表格 / HTML·SVG 全屏预览 | 不持有会话状态 |
| `FullscreenInputActivity` | 全屏输入编辑，完成后回填主输入框 | 不做草稿持久化（主输入框是唯一数据源） |
| `AboutActivity` | 版本展示、检查更新、反馈、开源许可 | 不发起版本网络请求（功能待开发） |
| `MessageQueue` | 排队发送（每条带 display 与 apiText 两份文本） | 不知道消息内容怎么发 |
| `AttachmentText` | 附件 → 两套文本：给模型的正文、给人的摘要；大小与 token 估算 | 不读文件，不碰 UI |
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
| 首启引导文案与顺序 | `HelpText` + `MainActivity.showFirstLaunchDialog` + `KEY_FIRST_LAUNCH_DONE` |
| 附件怎么进提示词 | `AttachmentText.toApi` / `toDisplay` → `ChatHistory.apiContentOf` |
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
    发送后气泡只显示「[附件] name（N 字）」，模型确实读到了正文；
    排队发送时附件正文不丢（`MessageQueue` 带 apiText）
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
