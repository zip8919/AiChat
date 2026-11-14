AiChat v1.14.19 —— 六项体验改造（文件图标 / 前缀续写 / 清理保护期 / 会话加载 / 提示词清空 / 加速源）

本版处理文件列表辨识度、中断回复可续写、临时文件清理口径、会话切换反馈、系统提示词编辑，以及 GitHub 加速源的可操作性与测速可信度。

═══ 一、文件列表加图标 ═══
- 代码库、已保存文件、占用前 10 三个列表统一加 36dp 文件图标：图片项显示采样缩略图，其余按扩展名显示分类色徽章（代码 / 文档 / 数据 / 压缩包 / 音视频 / 字体 / 其它）
- FileIcons 自绘 Drawable：minSdk 18 用不了 vector drawable，也不想为每类切 5 套密度 PNG；零资源文件，识别色不随主题漂
- FileIconBinder 异步采样解码（inJustDecodeBounds → inSampleSize → RGB_565）+ 按字节计费的 LruCache；用 file_icon_path_tag 校验行复用，避免 ListView 滚动时缩略图错绑
- 图标绑定放在 ThemeRecolor 之后，防止整树着色把识别色改掉

═══ 二、中断后继续生成（DeepSeek 官方前缀续写）═══
- 按官方文档实现：base_url 用 https://api.deepseek.com/beta，messages 最后一条必须是 assistant 且带 "prefix": true
- 错误卡片在「重新回复」之外新增「继续生成」：复用同一条 AI 消息，新分片追加在已生成正文之后，不再从用户消息整条重发
- 其他服务商降级为「assistant 回放已生成内容 + 一条续写指令」重新请求，保证功能可用；严格的 OpenAI 兼容端点要求以 user 结尾，只放 assistant 会 400
- 请求侧用 Continue 状态把 displayBase（含 [thinking] 标记的显示正文）与 prefixText（剥标记后的干净正文）分开：前者作流式渲染 base，后者发给模型；结算时 apiText 保留前缀 + 新内容，历史不会只剩后半截
- 继续生成不带 tools、不发工具说明系统提示，官方 /beta 路径也不下发 thinking 参数，避免 400 降级与工具卡片搅乱续写
- 只作用于最后一条 AI 消息：所有流式 DOM 函数都写「最后一个 AI 气泡」，中断后已继续提问的消息走「重新回复」

═══ 三、临时文件清理去掉长期保护期 ═══
- 删除 7 天 / 1 天 / 24 小时 / 1 小时等多档阈值，统一为 WRITE_GRACE_MS = 2 分钟的写入窗口，唯一目的是别删到正在写的文件
- 扫描、清理、附件 tmp-*. 清理、缓存目录四处口径统一；界面文案移除「已过保护期」等长期保护说明

═══ 四、会话切换加载动画与分阶段进度 ═══
- 新增半透明遮罩：旋转进度 + 阶段文案 + 数字进度条 + 百分比，按深色 / 浅色分别取色
- ConversationManager.LoadProgress 回报真实阶段：校验会话信息 → 读取会话文件 / 正文 → 解析消息记录（N 条）→ 恢复附件与工具记录 → 准备界面 → 加载完成
- 加载在后台线程，进度统一 post 回主线程；切换期间防重入；100% 停留 260ms 再收起，避免「一闪而过」

═══ 五、系统提示词清空按钮 ═══
- 输入框标题行加「清空」，二次确认写明字数与「需点保存才会写盘」，清空后仍由设置页统一保存落盘

═══ 六、GitHub 加速源 ═══
- 修复内置 / 自定义源缺少编辑、删除、单测入口的问题：列表改 ListView，长按菜单提供测速此源 / 编辑地址 / 复制地址 / 删除或移除 / 恢复全部内置源；内置源编辑走「新增自定义 + 隐藏内置」而不是改常量
- 每个 release 内置 speedtest.txt（魔数 AICHAT_SPEEDTEST_V1）。测速从「Range 取首字节」改为「下载并校验测速文件内容」，HTTP 200 但返回 HTML 错误页的失效镜像会被判 FAILED
- parseRelease 把 speedtest.txt 从安装包列表排除、单独记 speedTestUrl；没有测速文件的历史 release 自动退回旧探测方式
- 自带加速源从 github.akams.cn 的 JS chunks 提取节点后固化内置（该站无公开 JSON API，运行时解析 bundle 不稳定）；测速池由单线程改为 3 线程，取消时逐个断开连接

═══ 验证 ═══
- ./gradlew compileDebugJavaWithJavac / assembleDebug / assembleRelease：BUILD SUCCESSFUL（release 本地用临时自签名证书验证打包链路，正式包由 CI 用仓库密钥签发；原 keystore 已还原）
- 兼容性基线：minSdk 18 / targetSdk 18 / compileSdk 34，258dp 窄屏与 API 18/19 TLS 兼容层未改动
- 发布产物：armeabi-v7a / arm64-v8a / x86 / x86_64 / universal 五 ABI + speedtest.txt

---
**Full Changelog**: https://github.com/zip8919/AiChat/compare/v1.14.18...v1.14.19
