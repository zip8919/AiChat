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
| `MessageHtmlRenderer` | 一条消息 → 一段 HTML（卡片结构） | 不解析 markdown 语法 |
| `MarkdownPipeline` | markdown → html 的流水线 | 不处理公式位图生成 |
| `MathRenderer` | `\ce` / LaTeX / 裸命令 → 位图 | 不碰消息结构 |
| `RendererAssets` | CSS 与 JS 字符串 | — |
| `ViewerController` | 图片 / 表格 / HTML·SVG 全屏预览 | 不持有会话状态 |
| `MessageQueue` | 排队发送 | 不知道消息内容怎么发 |
| `ConversationManager` | 会话读写、异步落盘队列 | 不碰 UI |

## 并发约定

三个标记各管一件事，**不要用其中任何一个去替代另一个**：

| 字段 | 含义 | 谁写 | 谁读 |
|---|---|---|---|
| `requestGeneration` | 请求代数。新请求 / 打断都自增 | UI 线程 | 请求线程、渲染回调 |
| `isRequestInProgress` | 是否有一条请求在跑 | UI 线程 | 队列、发送键、Abort |
| `renderSeq` | 渲染序号。每次发起渲染自增，回来时对不上就丢弃 | 任意线程 | 渲染回调 |

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
| 消息卡片长什么样 | `MessageHtmlRenderer` |
| markdown 扩展语法 | `MarkdownPipeline` |
| 公式渲染 | `MathRenderer` |
| 流式刷新的频率与内容 | `StreamRenderer` |
| 发送键 / 排队 / 长按行为 | `MainActivity.bindSendButton`、`MessageQueue` |

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
