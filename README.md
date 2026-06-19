# AiChat

armv7a Android 4.3 (API 18) 词典笔 AI 对话应用。基于 WebView + HTML/CSS 的 Markdown/LaTeX 渲染，支持 DeepSeek 和 SiliconFlow API。

## 功能

- **流式对话** — SSE 实时响应，支持 DeepSeek 和 SiliconFlow 双 API
- **思考模式** — 四档可调（关闭/低/中/高），思考内容可折叠/展开
- **Markdown 渲染** — WebView + HTML/CSS 完整渲染：
  - 标题、粗体、斜体、删除线、代码块（带复制按钮 + **语法高亮**，支持 Java/Python/JS/TS/Bash/C++/Go/Rust/Kotlin/Swift/C#/SQL/XML/HTML/**SVG**）
  - 表格（横向滑动 + 点击放大查看）、引用块、列表、分割线、图片、链接
  - LaTeX 数学公式：`$...$` / `$$...$$` / `\(...\)` / `\[...\]` / 裸 `\command` / 化学式 `\ce{...}`
  - 脚注 `[^id]`、高亮 `==text==`、上标 `^text^`、下标 `~text~`
  - 内嵌 HTML/SVG 直通
- **HTML/SVG 代码预览** — 代码块"预览"按钮，弹出全屏查看器（缩放/旋转/背景切换）
- **智能自动滚动** — AI 生成时在底部自动跟随，上翻后不打扰，滚回底部恢复跟随
- **回到底部按钮** — 输入框上方，单击回底，长按清空输入框
- **性能优化** — 后台线程渲染、流式增量更新、DOM 细粒度操作
- **对话管理** — 多对话、历史列表、分支、回溯
- **消息操作** — `⋯` 按钮菜单：复制、选择文本、修改、删除、重试、回溯、分支
- **自动标题** — 首次对话自动调用 AI 生成标题，可自定义标题生成提示词
- **系统提示词预设** — 保存/管理/切换多个系统提示词预设
- **打断请求** — 长按发送键可中断正在进行的请求
- **屏幕旋转** — 右上角 ↻ 按钮切换横屏/竖屏
- **扫描识别** — 横屏扫描界面，调用词典笔系统 OCR 识别文字，支持加载摘抄记录、编辑、复制、插入
- **快速搜题模式** — 设置开关，开启后按扫描键直接跳转系统扫描应用，返回自动填入输入框

## 构建

```bash
# Debug
gradle assembleDebug

# Windows PowerShell
.\build.ps1 -DeepseekKey "sk-xxx" -SiliconflowKey "sk-xxx"

# Linux / macOS
./build.sh --deepseek-key sk-xxx --siliconflow-key sk-xxx
```

签名密钥通过环境变量提供：

| 变量 | 说明 |
|------|------|
| `KEYSTORE_PASSWORD` | 签名密钥库密码 |
| `KEY_ALIAS` | 签名密钥别名（默认 `mc`） |

## 发布

推送 `v*` 标签触发 GitHub Actions 自动构建和发布：

```bash
git tag v1.3.5
git push origin v1.3.5
```

需配置 Secrets：`DEEPSEEK_KEY`、`SILICONFLOW_KEY`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`

## 技术栈

- 纯 Android Framework（Activity、WebView）
- Markdown 解析：[commonmark-java](https://github.com/commonmark/commonmark-java) + GFM 扩展（strikethrough、tables）
- LaTeX 渲染：[jlatexmath-android](https://github.com/noties/jlatexmath-android)
- HTTP：`java.net.HttpURLConnection` + TLS 1.2 手动配置
- 最小 API 18（Android 4.3），armv7a
- 零第三方 HTTP/图片加载库

## 项目结构

```
├── app/
│   ├── build.gradle
│   ├── keystore/mc.jks
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/xyz/zip8919/app/aichat/
│       │   ├── ApiClient.java
│       │   ├── CodeHighlighter.java
│       │   ├── ConfigManager.java
│       │   ├── Conversation.java
│       │   ├── ConversationAdapter.java
│       │   ├── ConversationManager.java
│       │   ├── ConversationManagerActivity.java
│       │   ├── MainActivity.java
│       │   ├── MarkdownParser.java
│       │   ├── Message.java
│       │   ├── MessageAdapter.java
│       │   ├── MessageHtmlRenderer.java   # Markdown → HTML/CSS 渲染器
│       │   ├── ModelConfigActivity.java
│       │   ├── ModelInfo.java
│       │   ├── ProviderInfo.java
│       │   ├── ScanActivity.java
│       │   ├── SettingsActivity.java
│       │   └── StorageManager.java
│       └── res/
├── build.ps1
├── build.sh
├── settings.gradle
└── .github/workflows/release.yml
```

## 目标设备

- CPU: armv7a
- 系统: Android 4.3 (API 18)
- 屏幕: 竖屏窄宽（词典笔）
- TLS: 1.2（禁用 1.3，Android 4 不支持）

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
