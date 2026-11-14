AiChat v1.14.17 —— 5×3 = 15 轮全量代码审查优化收官

本次发布完成「15 轮全量代码审查」的最后一个周期。每轮覆盖全部 111 个 Java 文件（5 个分区并行审查），奇数轮找 bug、偶数轮做性能，硬约束：功能不变、不影响原有功能、性能高效。v1.14.15（轮 1–5）、v1.14.16（轮 6–10）已发，本版收尾轮 11–15。

═══ 周期三（轮 11–15，本版）═══

【轮 11 · 找 bug：6 条 P0】
- 深色 token 静默丢失：setValue 只写 darkTokens 不置 hasDark
- 会话列表并发崩溃：conversations 改 CopyOnWriteArrayList
- 纯文本模式整条变一行：streamFinalize 把 tail 搬进无 pre-wrap 的 base
- 公式解析抛异常：取子串起止颠倒（bracketEnd + Math.max 兜底）
- 流式 IOException 时已渲染内容丢失（partialOut 出参把半截 display 补进 trace）
- 刚发的图被判无引用删掉：deleteSelected 删除前重扫引用

【轮 12 · 性能：26 项】
- CodeHighlighter 高亮记忆化 + dead[] 单调标记：5KB 代码块 185ms → 0.9ms（305 输入差分测试零差异）
- Html.esc/escAttr 无特殊字符快路径；MarkdownPipeline 三处短路（hasSvgTag 前置探测、跳过无占位回填、indexOf("~~~")）
- 渲染缓存 24 → 48 条（一屏 40 条不再被压命中率）
- ThemeRecolor 采样缓存 + 同色短路；getEffectiveColor 去掉 int→String→int 往返
- ThemeText 静态 Pattern；AppearanceSpinnerAdapter 三种灰共用一次取值
- ToolsActivity 列表只 stat 一次（300 文件省 ~4900 次 syscall）；JsRunner 行数增量维护
- ViewerExport 导出上限 16M → 4M 像素（老机 OOM 治理）；UsageStats 快照挪 io 线程
- SystemInfo replaceIfPresent；ApiClient 日志守卫；批量删除只回收一次
- AttachmentFiles.apiDataUri LRU；StorageManager.readFile 预置容量；索引落盘 5s 节流

【轮 13 · 找 bug：20 处】
- 深色开再关后顶栏蓝条消失必须杀进程：回默认分支显式设默认色（不再置 null）
- 未闭合 [tool] 丢正文 → 兜底渲染尾段；用户气泡换行被压平 → pre-wrap
- 工具轮次末帧被节流丢弃 → flushAndWait 强制落地最后一帧
- 续跑残留半截正文 → Message.retryTrim 记录截断点；工具半截内容续跑拼接
- 会话 id 路径穿越 → isValidConversationId + 以文件名为准；未发送附件被当孤儿删 → PENDING 草稿保护
- 索引为空时「无引用」页永久死路 → scanned>0 条件；.bak 永久残留 → renameAtomic 成功后清理
- 删除/保存竞态 → deletedIds 提前置位；导出 3 张全尺寸位图 OOM → 压缩前回收
- ViewerController 4 处弹窗加 isFinishing 守卫（BadTokenException 闪退）；StatsActivity 同类守卫
- HttpRequestTool 去掉 PATCH（Android HttpURLConnection 必抛异常）
- UpdateActivity runProbe 误导弹框；JsRunner 行数回归自修

【轮 14 · 性能：20+ 项】
- 流式帧公式缓存 frameCache：5 公式 ×5 tick/s 的位图重画从「每帧全重画」降到首次渲染
- StreamRenderer 三处 new Thread 改共用 2 线程渲染池（一条消息 ~1500 线程 → 0 新建）
- 定稿 HTML 预热挪到 UI 落地之后（scheduleWarmUp，600ms 后台渲染 + 代数校验）
- 会话保存队列按 id 合并：连续保存同一会话只写终态（O(N²) 写盘 → O(1)）
- readFile 去 readLine 大文件 3 份复制 → char[] 直读
- apiDataUri 缓存按字符计费 2MB（含图消息连续发送不再反复 base64）
- StorageFilesActivity.reload() 主线程 → 后台线程（300 文件 20~40ms 移出 UI）
- StorageRefs 扫描复用已列目录数组，不再二次 listFiles
- ThemeRepository 按文件解析缓存（改一个主题不再重解析全部）+ deleteAll 批量删除
- FontRepository 字体 LRU 改按条目计费（大字体不再命中率为 0）、坏字体不重复写盘、setEnabledAll/deleteAll 一次落盘
- AppearanceActivity：控件改动不再整树刷两遍、色块 drawable 复用只改描边、Spinner 签名短路、系统字体引用短路
- ToolsActivity 运行器分段不白扫目录 + 列表签名短路；ViewerController 文档注入 Pattern 静态化

【轮 15 · 找 bug：收官复查（5 区全量覆盖，23 处改动）】
先清掉轮 13 遗留 + 自查 4 处：
- HTTP 200 + JSON 错误体被当正文吞掉（余额不足/密钥无效只见「解析错误」）→ SseRound.errorMessageOf 提取服务端原话（覆盖 {"error":{"message":..}}/{"error":".."}/{"message":".."} 三种形态）
- SSE data 行携带错误体（data: {"error":{...}}）：chunks 已自增、无 choices，绕过空流兜底被当成「成功但空内容」、收尾清空气泡 → 无 choices 分支就地识别错误对象并置 r.error
- 非流式响应体是错误对象 / 网关 HTML 错误页 → new JSONObject 加 try/catch，统一走 errorMessageOf，错误卡片带服务端原话
- UpdateActivity 主线程拷 APK（点「安装」界面僵 0.5~1.5s）→ 同步 IO 移后台线程；安装路径不再重复 worldReadable 拷贝

渲染与流式（B 区 3 处）
- 工具轮次 flushAndWait 超时后任务不取消：迟到帧会作废新一轮在飞帧、把旧 base/tail 写回一帧（末尾正文重复、DOM 与落盘 content 不符）→ commitEpoch 代数校验丢弃迟到帧
- 工具轮次 flush 的 base 已含本轮 display，base 与 tail 重复渲染同一段正文 → 传 append 前快照，与 onProgress 契约一致
- 定稿预热内层线程脱离 handler 管辖、定稿后 onDestroy 不自增代数 → 补 isFinishing/isDestroyed 校验（限时泄漏 + 脏静态缓存）

网络与对话（C 区 5 处）
- 删除与在途写盘竞态：flush 超时 + 已过 deletedIds 检查的写会让文件复活，且复活后该 id 被永久拉黑、后续所有对话静默不落盘（P1 内容丢失）→ 写盘后二次检查并删复活文件 + 切换会话解除拉黑
- MAX_TOOL_ROUNDS 用绝对 round 判断：8 轮触顶后「从出错处重试」从 8 起、上限恒真 → 先发一次 HTTP 再跑一轮工具就立刻失败、无限重试（重复发送 + 记账错误）→ 改按本次 execute 实际轮数计数
- 协议层错误（SseRound 正常返回 r.error、不走 IOException catch）不设截断点 → 续跑不截断半截内容、模型重写本轮导致界面与落盘各两份 → 与断流 catch 一致记 partialStart
- HttpRequestTool「去掉 PATCH」只改了给模型的 enum，校验层仍放行：4.4 上 setRequestMethod("PATCH") 必抛 ProtocolException、工具 100% 失败 → isAllowedMethod 删除 PATCH + 修正 body 描述
- SystemInfo 用 File.getUsableSpace()（API 21+），4.4 上 NoSuchMethodError 被吞 → {free_storage} 恒「未知」→ 改 StatFs 长整型取值（API 18 可用）

存储与文件（D 区 7 处）
- 图片页 deleteSelected 重扫漏判 PENDING 草稿 + 无 onResume 用旧快照：刚加进输入框/刚发出去的图被当孤儿删掉、静默裂图（P1）→ 补 isPendingName 跳过 + onResume 刷新
- cleanOrphans「空索引拒绝」缺 scanned>0，与 Scanner/附件页不一致：会话目录已空时一键清理直接 return、反复点无效 → 放宽为「扫到会话却零引用」才拒绝
- readFile 的 \r 归一化直接删除所有 \r：`a\rb` 粘成 `ab`，展示后写回永久破坏原文件 → 折叠式归一化（\r / \r\n 都输出一个 \n）
- renameAtomic 的 early return 绕过「成功后删 .bak」：轮 13 加的清理在主流文件系统上从未生效，旧正文明文副本永久残留 → 去掉早退，删 bak 覆盖所有成功路径
- 索引 mtime+length 失效判定在「同秒等长写入 + 5s 节流未落盘」时命中旧条目 → 冷启动显示旧标题/条数 → 该场景立即落盘索引
- ViewerExport 导出文件名秒级时间戳，同秒同格式二次导出静默覆盖（原图直出必落同一秒）→ stamp 加毫秒
- StorageFilesActivity 后台扫描用启动时 bySize 快照，扫描期间切排序被结果覆盖（文案与列表错位）→ 回主线程前按当前排序重排

设置与更新（E 区 4 处）
- install() 后台线程无异常兜底：worldReadable 抛 SecurityException/Error 时未捕获异常被 CrashCatcher 交给系统默认 handler killProcess（整个 App 闪退）+ 按钮永久 disabled → Runnable 全程 catch(Throwable) + 恢复按钮 + 明确提示
- 4.4 + 内部存储回退 + 复制失败时 worldReadable 静默返回内部私有 src，Uri.fromFile 指向别 uid 读不到的目录、「解析包错误」且无提示 → 失败返回 null，不弹安装器，提示到「已保存文件」手动打开
- 重复点「检查更新」未作废在跑的测速：旧代 onProbeFinished 覆盖进度文案并自动改选镜像、与失败状态自相矛盾 → check() 调 invalidateProbe()
- 安装准备期间切换安装包：不恢复 btnAction 且弹出切换前旧包的安装器（装错版本）→ installPending 标志 + 切换/重查时作废

五区全量复查（A 区主题外观由轮 14 全量审查覆盖、本轮重点回归），本轮 23 处改动全部经 clean assembleDebug + CSS/JS 一致性校验

═══ 周期二（轮 6–10，v1.14.16 已发）═══

【轮 6 · 找 bug：3 P0 + 14 文件 P1 + 3 回归修正】属性页重建预览污染、索引空误删在用图、批量删除主线程 ANR；回归修正（命中路径改走 revision、clearStatus 不强停 429 倒计时、tool_calls 校验放宽到只拦半截 JSON）
【轮 7 · 性能：9 项】insideBacktickSpan O(n²)→O(n)、流式帧不污染 latexCache、主题仓库 500ms 目录签名窗口、hexOf 手写、字体导入后台化、缩略图 in-flight 去重、commit()→apply()
【轮 8 · 找 bug：13 项】data-idx 偏移（P0）、渲染线程兜底、生成中删除/回溯守卫、折叠条空白、相机 Provider 截断、重命名致原图写坏、多选集合未清空、模型重名、thinking 400
【轮 9 · 性能：10 项】UsageStats 流式写盘（8~15MB→8KB）、查看器大文档后台构建、代码高亮整段转义、批量删除索引一次落盘、StorageFilesActivity fileMeta 预取、JsRunnerActivity 输出合帧
【轮 10 · 找 bug：12 项】深色 CSS 保存丢失、GBK 文本写坏、整图裁剪回收在用 Bitmap、未闭合 <svg 吞正文、降级顺序误判、关闭思考对 boolean provider 失效、单条删除主线程 ANR

═══ 周期一（轮 1–5，v1.14.15 已发）═══

【轮 1 · 找 bug：14 项】写盘 rename 兜底、主题重命名失败回滚、全屏输入状态保存、代理对安全截断、下载长度校验、429 中断回调、reader finally 关闭、余额响应统一清洗、Toast 用 Application Context、公式位图 finally 回收、trace 渲染独立序号、扫描回调判销毁、大图预览走采样、配置列表改 COW
【轮 2 · 性能：8 项】TLS 缓存两套 SSLSocketFactory、默认色 hex 缓存、4.4 跳过 API23 getButtonDrawable、取色器复用 drawable、流式中间态不写渲染缓存、jsEscape 单遍、统计序列化移出主线程、可见区截图过 fit
【轮 3 · 找 bug + 回归：17 项】修回 4 个自引入回归（trace 序号作废不全、429 卡片残留、写盘删旧丢数据、gif 动画丢失）；explicit 表改 CHM、colorSignature 覆盖全 token、采样 bounds 还原、HTTP 工具补 TLS、标题生成不持有 Activity、429 文案不串轮、裁剪位图互斥锁、统计加载幂等、硬截改 TextKit.cut、定稿预热缓存
【轮 4 · 性能：6 项】Markdown 双快路径、采样位图静态复用、主题列表目录签名缓存、CSS 未变跳过 evalJs、统计冷启动异步加载
【轮 5 · 回归：22 项】3 个 P0（trace 落地误杀 tail tick、统计加载 Error 死锁、写盘 .bak 滞留）；renameAtomic 原子替换 + 孤儿 .bak 自愈、WebView 重灌 CSS 缓存失效、429 卡片代数守卫、换肤缓存签名、动图长度上限、CropView 位图引用、索引不完整误删在用图、jsArg 补 U+2028/2029、崩溃日志保留段数等

═══ 验证 ═══
- 每轮结束 clean assembleDebug + JS node --check + CSS 花括号配平
- 关键算法改动做差分测试（CodeHighlighter 305 输入零差异）
- 发布产物：armeabi-v7a / arm64-v8a / universal / x86 / x86_64 五 ABI，CI 构建校验
- 兼容性基线：Android 4.4（minSdk 18）全链路回归

---
**Full Changelog**: https://github.com/zip8919/AiChat/compare/v1.14.16...v1.14.17
