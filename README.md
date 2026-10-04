# AiChat

Android 词典笔 AI 对话应用（版本 1.4.1，minSdk 18 / targetSdk 18，真机 Android 4.4.2 / API 19）。基于 WebView + HTML/CSS 渲染 Markdown/LaTeX，通过任意 OpenAI 兼容端点调用大模型，内置 DeepSeek（不含 API Key）与商汤日日新 默认配置，默认模型 `deepseek-flash`。

## 功能

- **多模型 / 多提供商** — 模型配置页可增删提供商与模型，自定义 API URL、Chat Path、API Key；内置 DeepSeek、商汤日日新 及任意 OpenAI 兼容端点
- **对话** — 流式输出（设置可开关）；回复进行中再发送自动排队，队列支持上移/下移/插队发送（打断当前回复）/删除；长按发送键打断当前请求，思考阶段打断自动补 `[/thinking]` 收尾
- **思考模式** — 关闭/低/中/高四档，思考内容可折叠/展开
- **Markdown 渲染** — WebView + HTML/CSS：标题、粗斜体、删除线、代码块（复制按钮 + 语法高亮）、表格（横向滑动 + 点击放大）、引用、列表、分割线、图片、链接；LaTeX 公式（`$...$`/`$$...$$`/`\(...\)`/`\[...\]`/裸 `\command`/化学式 `\ce{...}`）、脚注、高亮 `==text==`、上下标、内嵌 HTML/SVG 直通
- **语法高亮** — 支持 Java、Python、JS/TS、Bash/sh/shell、JSON、C/C++、Go、Rust、Kotlin、Swift、C#、SQL、XML、HTML、SVG；未声明语言时按内容嗅探（HTML/SVG/XML 自动识别）
- **代码查看器** — 所有代码块均可弹出全屏预览（任意语言），支持缩放/旋转/背景切换；代码块可导出保存到应用存储 exports 目录（按语言扩展名，覆盖 SVG/HTML 及各类代码）
- **智能自动滚动** — AI 生成时底部跟随，上翻不打扰，滚回底部恢复；实时渲染（200ms 节流）可在设置中开关
- **回到底部按钮** — 单击回底，长按清空输入框
- **对话管理** — 多对话、历史列表；历史支持重命名、清空、多选/全选/反选/批量删除；消息支持复制/选择文本/修改/删除/重试/回溯到此处/创建分支
- **自动标题** — 首次对话自动生成标题，标题生成模型与提示词可在设置中配置
- **系统提示词** — 自定义系统提示词，支持保存/管理/切换多个预设
- **小工具**（长按"历史"按钮进入）— 已保存代码列表（查看/复制/重命名/编辑/预览运行/多选批量删除）；JS 运行器：在 WebView（Chromium 30）中运行 JS/HTML/SVG，输出可复制，支持粘贴、载入已保存文件、保存到 exports；APP 代码环境提示词（复制/插入输入框/直接发送）
- **设置** — 流式输出、实时渲染、调试日志、快速搜题开关；模型/提供商管理；查询 DeepSeek 余额（显示总余额/充值/赠送）
- **扫描识别** — 横屏扫描界面，调用词典笔系统 OCR 识别文字，支持加载摘抄记录、编辑、复制、插入
- **快速搜题模式** — 开启后按扫描键直接跳转系统扫描应用，返回自动填入输入框
- **屏幕旋转** — 右上角 ↻ 按钮切换横屏/竖屏
- **性能优化** — 后台线程渲染、流式增量更新、DOM 细粒度操作

## 构建

Windows：

```bat
build.bat --sensenova-key sk-xxx                          # Debug
build.ps1 -SensenovaKey "sk-xxx"                          # Release，输出 AiChat-v<版本>-<时间戳>-release.apk
```

Linux / macOS：

```bash
./build.sh --sensenova-key sk-xxx
```

直接使用 Gradle（Windows 为 `gradlew.bat`）：

```bash
gradlew assembleRelease -PsensenovaKey=sk-xxx
```

不传 `-PsensenovaKey` 时商汤日日新的 API Key 为空（DeepSeek 提供商同样默认不带 Key），需在设备上的模型配置页手动填写。

签名密钥位于 `app/keystore/mc.jks`，通过环境变量或 Gradle 属性提供：

| 方式 | 密钥库密码 | 密钥别名 |
|------|-----------|----------|
| 环境变量 | `KEYSTORE_PASSWORD` | `KEY_ALIAS` |
| Gradle 属性 | `-PkeystorePassword=...` | `-PkeyAlias=...` |

注：别名默认值 `mc` 仅存在于 build.sh 脚本层，Gradle 端未设默认值。release 输出：`app/build/outputs/apk/release/app-release.apk`。

## 发布

推送 `v*` 标签触发 GitHub Actions 自动构建发布（`.github/workflows/release.yml`），需配置 Secrets：`SENSENOVA_KEY`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`。

> 注意：`-PsensenovaKey` 的值会被编译进 `BuildConfig` 常量并留在 APK 的 dex 中，任何人拿到 APK 都能提取（这正是发布包里会出现真实 key 的原因）。所以该 key 不要写进仓库源码，只通过构建参数 / CI Secrets 注入。

## 技术栈

- 纯 Android Framework（Activity、WebView、HttpURLConnection），零第三方 HTTP/图片加载库
- 依赖（`app/build.gradle:53-57`）：conscrypt-android 2.5.2、jlatexmath-android 0.2.0、commonmark 0.21.0 + GFM strikethrough/tables 扩展
- Markdown 解析：commonmark-java + GFM 扩展；LaTeX：jlatexmath-android
- 编译：compileSdk 34，Java 8，minSdk/targetSdk 18；`abiFilters` 未设置，APK 包含全部 ABI
- **TLS / 网络安全**：`TlsCompat.java` 安装 Conscrypt 提供 AES-GCM 等现代套件（兼容 Android 4.4 老设备），启用平台支持的全部协议与套件（仅移除 SSLv3/SSLv2Hello）。**注意：当前实现信任所有证书（trust-all），且主机名校验恒返回 true——不校验任何证书与主机名，存在中间人攻击风险**，请勿在不信任的网络中使用

## 项目结构

```
├── app/
│   ├── build.gradle                # 构建配置：versionName 1.4.1 / versionCode 21 / compileSdk 34
│   ├── keystore/mc.jks             # 签名密钥库
│   └── src/main/
│       ├── AndroidManifest.xml     # 包名 xyz.zip8919.app.aichat，6 个 Activity
│       ├── java/xyz/zip8919/app/aichat/
│       │   ├── MainActivity.java        # 主界面：聊天、队列、消息操作、代码查看器、快速搜题、JsBridge
│       │   ├── ApiClient.java           # HTTP/SSE 请求：流式解析、thinking 处理、余额查询
│       │   ├── TlsCompat.java           # 老设备 TLS 兼容层：安装 Conscrypt、协议/套件全开、trust-all
│       │   ├── MessageHtmlRenderer.java # Markdown → HTML/CSS 渲染器（思考折叠、代码高亮、JsBridge）
│       │   ├── CodeHighlighter.java     # 代码语法高亮（约 20 种语言 token 规则）
│       │   ├── ConfigManager.java       # 提供商/模型/思考等级配置读写
│       │   ├── ConversationManager.java # 会话索引持久化与标题规范化（自动标题逻辑在 MainActivity）
│       │   ├── Conversation.java        # 会话数据模型
│       │   ├── Message.java             # 消息数据模型
│       │   ├── ProviderInfo.java        # 提供商数据模型（API URL/Path/Key/thinking 类型）
│       │   ├── ModelInfo.java           # 模型数据模型
│       │   ├── StorageManager.java      # 存储路径探测、会话/配置/exports 读写
│       │   ├── SettingsActivity.java    # 设置页：开关、预设、余额查询
│       │   ├── ModelConfigActivity.java # 模型配置页：提供商/模型增删改
│       │   ├── ConversationManagerActivity.java # 历史列表：重命名/清空/多选批量删除
│       │   ├── ToolsActivity.java       # 小工具页：已保存代码管理 + JS 运行器入口
│       │   ├── JsRunnerActivity.java    # JS 运行器：WebView 执行 JS/HTML/SVG、输出/保存
│       │   ├── ScanActivity.java        # 扫描识别页：调系统 OCR、摘抄记录
│       │   ├── LogUtil.java             # 统一日志工具（AiChat/* 前缀，可开关）
│       │   └── ConversationAdapter.java # 历史列表适配器
│       └── res/
├── build.bat / build.ps1 / build.sh     # 构建脚本
├── gradlew / gradlew.bat / gradle/      # Gradle Wrapper
├── settings.gradle
├── LICENSE                              # GPL-3.0
└── .github/workflows/release.yml        # 标签触发发布
```

## 目标设备

- 系统：Android 4.4.2（API 19）词典笔真机；minSdk 18 / targetSdk 18（低 targetSdk 保留安装时授权、无运行时权限，为老设备有意选择）
- 屏幕：竖屏窄宽
- CPU：build.gradle 未设 `abiFilters`，构建产物包含全部 ABI（如仅需 armv7a 可自行加 `abiFilters 'armeabi-v7a'`）

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
