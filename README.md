# AiChat

Android 词典笔 AI 对话应用（版本 1.14.4，minSdk 18 / targetSdk 18，真机 Android 4.4.2 / API 19）。基于 WebView + HTML/CSS 渲染 Markdown/LaTeX，通过任意 OpenAI 兼容端点调用大模型，内置 DeepSeek（不含 API Key）与商汤日日新 默认配置，默认模型 `deepseek-v4-flash`（商汤日日新）。

> 改代码前先看 [ARCHITECTURE.md](ARCHITECTURE.md)：请求链路图、类职责边界、并发约定、「想改 X 去哪」对照表。

## 功能

- **多模型 / 多提供商** — 模型配置页可增删提供商与模型，自定义 API URL、Chat Path、API Key；内置 DeepSeek、商汤日日新 及任意 OpenAI 兼容端点
- **对话** — 流式输出（设置可开关）；回复进行中再发送自动排队，队列支持上移/下移/插队发送（打断当前回复）/删除；长按发送键打断当前请求，思考阶段打断自动补 `[/thinking]` 收尾；请求失败时在用户消息下方显示错误卡片，可查看详情、点击气泡立即重试；回复进行中重试会先确认打断
- **输入区** — 发送键为纸飞机图标，生成中且输入框为空时变为方块「停止」图标；有文字（或挂了附件）时才可发送；有文字时右上出现四角图标，点开进入全屏编辑（完成回填、取消丢弃）；发送键左侧加号为附件菜单，支持「添加文本文件」「添加图片」「从链接添加图片」「拍照」；选图与拍照后进入**裁剪页**可框选区域（拖动四角/四边手柄，可旋转）再导入
- **附件** — 选中文件后**不往输入框里塞正文**，只在输入框上方显示一个可点掉的标签（两行：文件名 + 真实大小与 token 估算）。单文件上限 **10 MB**、最多 5 个、合计 20M 字符（约 40 MB 堆，老设备内存红线）、UTF-8 解码失败自动回退 GBK、超限报错不静默截断。正文随消息存进 `Message.attachments` 并**随请求体发给模型**；气泡下方独立渲染附件卡片（文件名 + 大小 · ~tokens），不混进正文，改消息、复制、重试都不会把附件弄丢；历史回放与重试都走完整正文，1.8.2 及以前的老会话读入时自动迁移成同样的结构。
- **查看附件** — 点消息下方的附件卡片即可打开：代码/JSON/XML/脚本按扩展名做**语法高亮**（超出 120K 字符自动关高亮、超出 400K 字符只显示前段并提示）；HTML 与 SVG 在查看器**右上角有「预览」**，可切到渲染结果再切回源码；**JS 的右上角是「运行」**，直接进脚本运行器；**Markdown（`.md`）右上角有「预览」**，渲染为排版文档（标题/列表/表格/代码块，64K 截断）；其余纯文本（`.log` 等）按纯文本折行显示。查看器沿用图片查看器的骨架，有缩放、旋转与复位。**图片附件**用图片查看器打开（拷贝到私有目录后按 1600px 采样解码），气泡里的卡片直接显示缩略图。图片进 `chat/completions` 默认只写一行说明、不塞 Base64——文本协议塞不下像素，也避免端点把它当正文计费；模型开启「多模态输入」后改走图像通道（见下）
- **消息元信息** — 每条消息底部显示时间（当天 `HH:mm`，跨天 `MM-dd HH:mm`）；AI 消息另显示 token 用量与生成速度（tok/s）；用户消息 token 为本地估算值（中文约 0.6 token/字、英文与符号约 0.3 token/字符，按厂商文档系数），以 `~` 前缀标注；端点返回 `usage` 时一律以返回值为准。端点不支持 `stream_options.include_usage` 时自动降级并记住，后续请求不再携带该字段
- **429 自动重试** — 仅对 429 生效（5xx / 408 / 超时不重试，避免重复计费）；等待期间对话区显示倒计时卡片，右侧「立即重试」可跳过剩余等待；设置页可配重试次数（1-20）、基础间隔、最大等待与指数退避；优先采用服务端 `Retry-After`（支持秒数与 HTTP-date）；等待过程可被「停止」立即打断
- **思考模式** — 关闭/低/中/高四档，思考内容可折叠/展开，标题显示本次思考的 token 估算（与消息底部的 token 单位一致）
- **本地时间工具** — `get_current_time` 读取设备当前日期、时间、星期与时区（含 ISO 8601 与时间戳），不联网、不需要任何 Key；即使关闭联网搜索也可用。涉及「今天/现在/星期几」等时间敏感问题时应先调用，避免模型凭训练数据猜日期
- **联网搜索** — 设置页总开关（默认开启）；搜索服务可选 **Tavily（默认，免费，经自建网关）** 或 DeepSeek。AI 可通过 `web_search` 检索实时网页，通过 `image_search` **搜索图片**（结果以缩略图网格展示，点击进图片查看器看原图），通过 `web_fetch` 抓取网页正文；来源独立成可折叠来源区（按 URL 去重，最多 8 条），不写入正文、不进入后续请求；工具轮次不设上限，单轮工具调用数可配置（默认 15，范围 1-50），超出部分按未执行处理；搜索摘要单条限 1200 字符、单次合计限 6000 字符。总开关同时控制工具定义、搜索系统提示词和实际搜索/抓取请求。工具用途说明写在 `tools[].function.description` 里（协议自带字段），不再进 system prompt，每轮省约 200 token
- **Request 工具** — `http_request` 让 AI 在本机发起 HTTP 请求直接调 API：支持 GET/POST/PUT/DELETE/PATCH/HEAD、自定义请求头、POST body、1-30 秒超时。**默认关闭**，设置页开启。三道安全边界：拒绝环回 / 私有网段 / 链路本地 / 唯一本地地址，禁止跟随 3xx（防公网跳内网），响应体截断 8KB
- **多模态输入（看图）** — 模型配置里可勾选「支持图片输入（多模态）」（`supports_vision`）；**DeepSeek Flash 系列（`deepseek-flash` / `deepseek-v4-flash`）内置默认开启**，其余模型按需自行勾选。开启后用户消息带图片附件时，请求体按 OpenAI/DeepSeek 兼容格式装配 `content` 数组（`text` + `image_url` 的 data URI，Base64 内嵌）；`image_search` 找到的图在视觉开启时也会注入下一轮，模型能直接「看到」图片内容。图片一律按**原图口径**（不传 `detail` 字段）发送，发图尺寸按模型像素预算（≤1302²）等比取档——预算外的像素端点本来就会缩掉，多发纯属浪费请求体。搜索图**只内嵌前 2 张**（每张最多约 994 token），其余图只留链接不进图像通道。未开启时保持纯文本协议：图片只写一行说明不塞 Base64，绝不默默多花钱
- **图片 token 估算** — 按 **DeepSeek 官方图片 Token 计算器**口径（进模型前缩放到约 544²~1300² 像素、每张上限 1024、patch 计数 `H×(W+1)+2`）：原图口径每张 184~1024 token（满档约 994）。发图尺寸与估算同用一个像素预算（1302²），**卡片显示值就是端点的实际计费值**。附件卡片显示「大小 · 尺寸 · ~tokens」，端点返回 `usage` 时以返回值为准、不带 `~`
- **Markdown 渲染** — WebView + HTML/CSS：标题、粗斜体、删除线、代码块（复制按钮 + 语法高亮）、表格（横向滑动 + 点击放大）、引用、列表、分割线、图片、链接；LaTeX 公式（`$...$`/`$$...$$`/`\(...\)`/`\[...\]`/裸 `\command`/化学式 `\ce{...}`）、脚注、高亮 `==text==`、上下标、内嵌 HTML/SVG 直通
- **语法高亮** — 支持 Java、Python、JS/TS、Bash/sh/shell、JSON、C/C++、Go、Rust、Kotlin、Swift、C#、SQL、XML、HTML、SVG；未声明语言时按内容嗅探（HTML/SVG/XML 自动识别）
- **代码查看器** — 所有代码块均可弹出全屏预览（任意语言），支持缩放/旋转/背景切换；代码块可导出保存到应用存储 exports 目录（按语言扩展名，覆盖 SVG/HTML 及各类代码）
- **智能自动滚动** — AI 生成时底部跟随，上翻不打扰，滚回底部恢复；实时渲染（200ms 节流）可在设置中开关
- **回到底部按钮** — 单击回底，长按清空输入框
- **对话管理** — 多对话、历史列表；历史支持重命名、清空、多选/全选/反选/批量删除；消息支持复制/选择文本/修改/删除/重试/回溯到此处/创建分支
- **历史列表索引** — 元数据单独存 `conversations.idx`（NDJSON：标题、更新时间、消息条数、文件字节数、mtime）。打开列表时逐个文件只比 `mtime + 字节数`，命中就直接用索引，不再把每个会话 JSON 连同图片缩略图 Base64 全量读一遍；仅改动过的那一个文件回退解析。索引随会话写盘增量更新，丢失、坏行或版本不符会自动全量重建，**只影响快慢、不影响数据正确性**。升级后第一次打开列表会建一次索引
- **存储空间管理**（设置页进入）— 概览把占用拆成五类：对话记录、图片附件、已保存文件、临时文件、其他，显示已用 / 可用与占比条；点分类进详情，条目可定位回原会话或原文件页。图片与文件支持多选批量删除，删除前按**会话引用计数**判断归属，引用索引读不全时自动暂停「一键清理图片」；一键清理只动可安全释放的部分：无引用图片，以及超过 1 小时、确定为残留的 `.tmp`（写文件先落 `.tmp` 再改名，进程被杀会留下），清理前先算「可安全释放」的总量
- **用量统计**（设置页进入）— 按自定义起止日期汇总请求数、prompt / completion token、缓存命中 token 与平均生成速度，并按模型、服务商、工具三个维度拆分。端点没返回 `usage`（例如中途打断）时记为本地估算并单独标注，不与「账单口径」混算；事件上限 2 万条，超出后从最旧的一条开始淘汰
- **自动标题** — 首次对话自动生成标题，标题生成模型与提示词可在设置中配置
- **系统提示词** — 自定义系统提示词，支持保存/管理/切换多个预设
- **小工具**（长按"历史"按钮进入）— 已保存代码列表（查看/复制/重命名/编辑/预览运行/多选批量删除）；JS 运行器：在 WebView（Chromium 30）中运行 JS/HTML/SVG，输出可复制，支持粘贴、载入已保存文件、保存到 exports；APP 代码环境提示词（复制/插入输入框/直接发送）
- **代码执行器提示词可配** — 设置页可自定义环境提示词，支持占位符 `{android}` `{api}` `{webview}` `{model}` `{app_version}` `{ua}`，发送时按当前设备实际值填充；未知占位符原样保留。系统提示词与标题生成提示词使用同一套占位符规则。WebView 版本从 `WebSettings.getDefaultUserAgent()` 的 `Chrome/xx` 段提取（`WebViewFactory` 是隐藏 API，不可反射）
- **设置** — 联网搜索、流式输出、实时渲染、调试日志、快速搜题开关；`http_request` 工具开关；429 重试策略；代码执行器提示词；模型/提供商管理；查询 DeepSeek 余额（显示总余额/充值/赠送）；存储空间管理与用量统计入口；关于页入口
- **关于与使用说明** — 关于页显示应用名、版本号（`versionName (versionCode)`）、**使用说明（与首启弹窗同一份文案）**、检查更新、反馈建议、开源许可（commonmark-java / jlatexmath-android / Conscrypt）；反馈建议仍为占位。首次启动弹出使用说明与免责声明，必须点「同意并继续」才可用；确认前不会发送任何由外部 Intent 带入的消息
- **版本更新**（关于页「检查更新」进入）— 直连 `api.github.com` 查最新 Release，按 `tag_name` 逐段比较数字判断新旧，展示更新说明；按 `Build.CPU_ABI` 自动挑对应架构的 APK（挑不到退 universal），也可手动切换。下载前内置 7 个加速镜像逐项测速并自动选最快，**测速是 HTTP 首字节探测（`Range: bytes=0-1023` 的 TTFB + 实读 1KB）而非 ICMP ping**——Android 上 ICMP 普遍被丢，测了也不代表下载快。镜像模板支持 `{url}` 占位符（省略则作前缀），可自定义添加、长按删除、选中项本地记忆。API 查询不走镜像（镜像只代理 `github.com/.../releases/download/`）；下载走已有的 `TlsCompat`（Conscrypt）兼容层，8KB 流式写盘、先 `.tmp` 再改名、按 API 给的 size 校验防截断，下载前清理旧 APK 避免占满可用存储；完成后按系统版本调起安装器——**API 19（4.4）的 `PackageInstallerActivity` 只声明了 `file` scheme，必须用 `file://`**（`content://` 会因 Intent 匹配不到 Activity 而报「没有可用的安装器」，targetSdk 18 也不会触发 `FileUriExposedException`），API 24+ 才走 `FilePathProvider` 的 `content://` + 临时授权；若存储回退到内部私有目录会先把 APK 复制到外部公共 Downloads 再装。签名一致，覆盖安装不丢数据
- **扫描识别** — 横屏扫描界面，调用词典笔系统 OCR 识别文字，支持加载摘抄记录、编辑、复制、插入
- **快速搜题模式** — 开启后按扫描键直接跳转系统扫描应用，返回自动填入输入框
- **屏幕旋转** — 右上角 ↻ 按钮切换横屏/竖屏
- **性能优化** — 后台线程渲染、流式增量更新、DOM 细粒度操作；长对话分批渲染，首屏只渲染最近 40 条，滑到顶部再按 30 条追加更早消息并保持滚动位置

## 构建

Windows：

```bat
build.bat --sensenova-key sk-xxx                          # Debug
build.ps1 -SensenovaKey "sk-xxx"                          # Release，按 ABI 分包输出 AiChat-v<版本>-<时间戳>-<abi>.apk
```

Linux / macOS：

```bash
./build.sh --sensenova-key sk-xxx
```

直接使用 Gradle（Windows 为 `gradlew.bat`）：

```bash
gradlew clean assembleRelease -PsensenovaKey=sk-xxx
```

> **`clean` 不能省。** 本工程当前 AGP 版本的增量打包会在 APK 里留下约 600KB 的空洞字节（打出来 5.36MB，clean 后 4.74MB，内容完全一致）。交付包一律走 clean 构建；三个 `build.*` 脚本已内置 `clean`。

不传 `-PsensenovaKey` 时商汤日日新的 API Key 为空（DeepSeek 提供商同样默认不带 Key），需在设备上的模型配置页手动填写。

签名密钥位于 `app/keystore/mc.jks`，通过环境变量或 Gradle 属性提供：

| 方式 | 密钥库密码 | 密钥别名 |
|------|-----------|----------|
| 环境变量 | `KEYSTORE_PASSWORD` | `KEY_ALIAS` |
| Gradle 属性 | `-PkeystorePassword=...` | `-PkeyAlias=...` |

注：别名默认值 `mc` 仅存在于 build.sh 脚本层，Gradle 端未设默认值。

release 按 ABI 分包（`app/build/outputs/apk/release/`），每个包只含对应 ABI 的一份 `libconscrypt_jni.so`：

| 文件 | 适用设备 | 体积 |
|------|---------|------|
| `app-arm64-v8a-release.apk` | 绝大多数现代设备（首选） | ~1.65 MB |
| `app-armeabi-v7a-release.apk` | 老 32 位 ARM 设备 | ~1.48 MB |
| `app-x86-release.apk` | x86 模拟器 | ~1.73 MB |
| `app-x86_64-release.apk` | x86_64 模拟器 | ~1.79 MB |
| `app-universal-release.apk` | 不确定 ABI 时的通用包（含全部 4 个 ABI） | ~4.04 MB |

## 联网搜索

设置页的「联网搜索」总开关默认开启，同时控制三件事：

1. 是否向模型发送 `web_search` / `web_fetch` 工具定义
2. 是否在系统提示词中追加联网工具说明
3. 是否真正调用搜索/抓取 API

搜索服务在设置页选择，默认 **Tavily**：

| 后端 | 端点 | 鉴权 |
|------|------|------|
| Tavily（默认） | `POST https://tavily.20090102.xyz/search`、`/extract` | 网关 `?key=` + `Authorization: Bearer tvly-…` |
| DeepSeek | `POST https://api.deepseek.com/v1/tools/search`、`/v1/tools/fetch` | DeepSeek 提供商的 API Key |

Tavily 的密钥在应用内「设置 → Tavily API Key」填写，仅保存在本机，不写入源码、也不打进安装包。切换到 Tavily 后不需要配置 DeepSeek 提供商；DeepSeek 后端才要求 DeepSeek 提供商填写了 Key。当前对话模型可以来自任意提供商，与搜索后端无关。搜索不可用时工具返回错误提示，普通对话不受影响。

- 计费：Tavily 走自建网关的免费额度；DeepSeek 每 1,000 次成功请求 5 元，具体以 [DeepSeek 官方价格页](https://api-docs.deepseek.com/zh-cn/quick_start/pricing/) 为准
- 搜索摘要单条上限 1,200 字符、单次合计上限 6,000 字符，超出总量的条目只保留标题与链接；`web_fetch` 正文不截断（仅受 1MB 网络读取保护）
- 工具调用产生的中间消息只在本次请求内使用，最终回答和来源区正常保存到会话
- 每条搜索/抓取的工具调用都会在回答中展示一张结果卡片：搜索卡片含查询词、结果条数及每条结果的标题、链接、站点与摘要；抓取卡片含网页标题、URL 与正文。卡片默认折叠，点击标题栏展开/收起。这些卡片随消息持久化，重新打开会话仍可查看，但不会作为上下文发给模型
- 工具调用前的思考内容会保留在回答中，不会因随后的搜索/整理而消失

部分 OpenAI 兼容端点不支持 `tools` 参数。首轮请求因该参数返回 HTTP 400/422 时会自动去掉联网工具重试一次，并提示当前模型不支持联网工具。

## 发布

推送 `v*` 标签触发 GitHub Actions 自动构建发布（`.github/workflows/release.yml`），需配置 Secrets：`SENSENOVA_KEY`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`。Release 附件为 4 个 ABI 分包 + 1 个 universal 通用包（`app/build/outputs/apk/release/*.apk`），请按设备 ABI 选择下载。

> 注意：`-PsensenovaKey` 的值会被编译进 `BuildConfig` 常量并留在 APK 的 dex 中，任何人拿到 APK 都能提取（这正是发布包里会出现真实 key 的原因）。所以该 key 不要写进仓库源码，只通过构建参数 / CI Secrets 注入。

## 技术栈

- 纯 Android Framework（Activity、WebView、HttpURLConnection），零第三方 HTTP/图片加载库
- 依赖（`app/build.gradle:53-57`）：conscrypt-android 2.5.2、jlatexmath-android 0.2.0、commonmark 0.21.0 + GFM strikethrough/tables 扩展
- Markdown 解析：commonmark-java + GFM 扩展；LaTeX：jlatexmath-android
- 联网搜索：OpenAI `tool_calls` + DeepSeek Search/Fetch 或 Tavily；流式与非流式响应均支持；工具轮次不设上限，单轮调用数可配置（默认 15，范围 1-50）
- 编译：compileSdk 34，Java 8，minSdk/targetSdk 18；用 `splits.abi` 按 ABI 分包（armeabi-v7a / arm64-v8a / x86 / x86_64 + universal 通用包），每个包只带对应 ABI 的 `libconscrypt_jni.so`
- **TLS / 网络安全**：`TlsCompat.java` 安装 Conscrypt 提供 AES-GCM 等现代套件（兼容 Android 4.4 老设备），启用平台支持的全部协议与套件（仅移除 SSLv3/SSLv2Hello）。**注意：当前实现信任所有证书（trust-all），且主机名校验恒返回 true——不校验任何证书与主机名，存在中间人攻击风险**，请勿在不信任的网络中使用

## 项目结构

```
├── app/
│   ├── build.gradle                # versionName 1.14.4 / versionCode 65 / compileSdk 34
│   ├── keystore/mc.jks             # 签名密钥库
│   └── src/main/
│       ├── AndroidManifest.xml     # 包名 xyz.zip8919.app.aichat，15 个 Activity
│       ├── java/xyz/zip8919/app/aichat/
│       │   ├── MainActivity.java            # 主界面：视图装配、事件分发、会话状态
│       │   │
│       │   ├── 【请求链路】
│       │   ├── ChatStreamRunner.java        # 一次回复的编排：轮次循环、失败分类、收尾
│       │   ├── StreamJob.java               # 请求参数快照（构造后不可变）
│       │   ├── SseRound.java                # 单轮 SSE / 非流式响应解析
│       │   ├── ChatTransport.java           # 请求体、连接、错误体
│       │   ├── ToolExecutor.java            # 一批 tool_calls 的执行与配对
│       │   ├── ChatHistory.java             # 历史压缩、工具回放、配对校验
│       │   ├── ToolHistoryRecovery.java     # 旧会话：从正文卡片反推工具历史
│       │   ├── ToolCalls.java               # tool_calls 规范化与累积
│       │   ├── TraceKit.java                # trace 长度上限：只在块边界裁剪
│       │   ├── StreamRenderer.java          # 流式节流渲染（base / tail 分离）
│       │   ├── WebSearchTool.java           # 联网工具：搜索、抓取、工具定义、结果整理
│       │   ├── TavilyTool.java              # Tavily 搜索 / 抓取后端
│       │   ├── HttpRequestTool.java         # http_request 工具：本机请求 URL（默认关闭）
│       │   ├── RetryPolicy.java             # 429 重试策略（退避、Retry-After、上限）
│       │   ├── ErrorReport.java             # 错误详情归类
│       │   ├── ApiClient.java               # 非流式调用：标题生成、余额查询
│       │   │
│       │   ├── 【渲染】
│       │   ├── MessageHtmlRenderer.java     # 一条消息 → HTML（思考块、工具卡、来源、错误卡）
│       │   ├── MarkdownPipeline.java        # markdown 流水线：代码/SVG 屏蔽 → 公式 → 解析 → 回填
│       │   ├── MarkdownPage.java            # .md 文件预览页：MarkdownPipeline 渲染 + 文档样式（64K 截断）
│       │   ├── MathRenderer.java            # LaTeX / \ce / 裸命令 → 位图
│       │   ├── RendererAssets.java          # 页面 CSS 与 JS
│       │   ├── CodeHighlighter.java         # 约 20 种语言的 token 规则
│       │   ├── Html.java                    # HTML 转义
│       │   ├── TextKit.java                 # 流式纯文本、标题截断
│       │   │
│       │   ├── 【界面与交互】
│       │   ├── ViewerController.java        # 图片 / 表格 / HTML·SVG 全屏预览；附件查看（源码 / md 预览 / 运行）
│       │   ├── FullscreenInputActivity.java # 全屏输入页（取消丢弃 / 完成回填）
│       │   ├── AboutActivity.java           # 关于页：版本、检查更新、反馈、开源许可
│       │   ├── UpdateActivity.java          # 版本更新页：Release 查询、镜像测速、下载安装
│       │   ├── UpdateManager.java           # GitHub API / HTTP 首字节测速 / 流式下载 / 镜像配置
│       │   ├── MessageQueue.java            # 排队发送：上移、下移、插队
│       │   ├── AttachmentText.java          # 附件：给模型的正文、卡片摘要、老会话迁移
│       │   ├── AttachmentFiles.java         # 图片附件：拷贝进私有目录、URL/拍照导入、裁剪产物落盘、缩略图、data URI
│       │   ├── ImageTokens.java             # 图片 token 估算（DeepSeek 官方计算器口径）
│       │   ├── CropActivity.java           # 图片裁剪页（选图/拍照/URL 导入的统一入口）
│       │   ├── CropView.java               # 裁剪框视图：四角/四边手柄、旋转、输出
│       │   ├── FileKinds.java               # 扩展名 → 高亮语言 / 能否预览 / 能否运行
│       │   ├── NetSecurity.java             # 内网访问与证书校验的进程内开关（默认严格）
│       │   ├── HelpText.java                # 使用说明与免责声明（首启与关于页共用）
│       │   ├── TitleGenerator.java          # 兜底标题与自动标题
│       │   ├── ExportHelper.java            # 代码导出：语言 → 扩展名
│       │   ├── SettingsActivity.java        # 设置页：开关、预设、余额查询
│       │   ├── ModelConfigActivity.java     # 提供商 / 模型增删改
│       │   ├── ConversationManagerActivity.java # 历史列表：重命名、清空、批量删除
│       │   ├── StorageActivity.java         # 存储空间：五类占用概览、占比条、一键清理
│       │   ├── StorageAttachmentsActivity.java # 图片附件列表：56dp 缩略图、引用归属、批量删除
│       │   ├── StorageFilesActivity.java    # 已保存文件列表：查看/运行/编辑/分享/重命名/删除
│       │   ├── StatsActivity.java           # 用量统计：区间汇总、按模型/服务商/工具拆分
│       │   ├── ConversationAdapter.java     # 历史列表适配器
│       │   ├── ToolsActivity.java           # 小工具页：已保存代码 + JS 运行器入口
│       │   ├── JsRunnerActivity.java        # JS 运行器
│       │   ├── ScanActivity.java            # 扫描识别页
│       │   │
│       │   ├── 【数据与平台】
│       │   ├── ConfigManager.java           # 提供商 / 模型 / 思考等级
│       │   ├── ConversationManager.java     # 会话持久化（异步写盘队列）
│       │   ├── ConversationIndex.java      # 历史元数据索引：NDJSON、指纹校准、原子写回
│       │   ├── Conversation.java            # 会话模型
│       │   ├── Message.java                 # 消息模型（含 attachments / apiText / toolHistory / sources）
│       │   ├── ProviderInfo.java            # 提供商模型
│       │   ├── ModelInfo.java               # 模型模型
│       │   ├── StorageManager.java          # 存储路径探测与读写
│       │   ├── SystemInfo.java              # 系统 / WebView 信息与提示词占位符填充
│       │   ├── LogUtil.java                 # 统一日志（可开关）
│       │   └── TlsCompat.java               # 老设备 TLS 兼容（trust-all，见下方警告）
│       └── res/
├── ARCHITECTURE.md                      # 架构说明：请求链路、类职责、并发约定、改动指引
├── build.bat / build.ps1 / build.sh     # 构建脚本
├── gradlew / gradlew.bat / gradle/      # Gradle Wrapper
├── settings.gradle
├── LICENSE                              # GPL-3.0
└── .github/workflows/release.yml        # 标签触发发布
```

## 目标设备

- 系统：Android 4.4.2（API 19）词典笔真机；minSdk 18 / targetSdk 18（低 targetSdk 保留安装时授权、无运行时权限，为老设备有意选择）
- 屏幕：竖屏窄宽
- CPU：按 ABI 分包输出（`app/build.gradle` 的 `splits.abi`），提供 armeabi-v7a / arm64-v8a / x86 / x86_64 四个分包与一个 universal 通用包；如只想产出单一 ABI，可把 `splits.abi` 换成 `defaultConfig.ndk.abiFilters`

## 免责声明

本软件**仅供学习和技术研究使用**，禁止用于任何商业或非法目的。

本软件的扫描识别功能通过调用系统中已安装应用的**公开接口**（ContentProvider、Activity、Broadcast）实现，相关接口由第三方应用的 `AndroidManifest.xml` 声明为 `exported="true"` 或未受权限保护的广播。

- 本软件**未**捆绑、复制或修改任何第三方应用的代码
- 本软件**未**绕过任何技术保护措施
- 本软件**未**破解付费功能或鉴权机制
- 扫描功能仅在用户主动触发时工作，取决于设备上预装的系统应用是否可用

使用本软件即表示您同意：开发者对因使用本软件产生的任何后果不承担任何责任。如您对此功能有疑虑，可自行在源代码中修改或移除相关类名和 URI。

## License

GPL-3.0
