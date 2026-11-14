AiChat v1.14.20 —— 会话渲染进度修复 + 镜像并行测速可配置

承接 v1.14.19 的六项体验改造，本版只修两个实测反馈：历史会话「数据加载完了，页面却还白屏很久、进度条早没了」，以及镜像测速的并行度不可调。

═══ 一、会话切换进度覆盖到 WebView 渲染完成 ═══
- 问题定位：v1.14.19 里 ConversationManager 在会话数据读完就报 100%，MainActivity 调 refreshWebView() 后固定等 260ms 收遮罩。真正耗时的是后台拼整页 HTML + WebView loadDataWithBaseURL + 排版渲染，可交互要等 onPageFinished。长会话因此「加载完成一闪而过，之后几秒白屏且没有任何进度」
- 进度按真实工作重新分段：数据加载 8~60% → 准备消息渲染（N 条）62% → 构建消息页面（N 条）68% → 渲染消息页面 85~98%（缓慢脉冲推进）→ onPageFinished 100% → 收起遮罩
- 遮罩收起权交给 WebViewClient.onPageFinished / onReceivedError；先 flushPendingJs 再收遮罩，保证用户看到的是消息已注入的页面
- 渲染代数守卫：期间若因主题变化、上滑加载又触发重灌，等待目标自动迁移到最后一代，不会被旧页回调提前关掉，也不会因旧渲染被丢弃而永不收起
- 双超时兜底：数据/构建阶段 12s、灌页后 10s；任一阶段卡死（渲染线程异常、WebView 不回调）都强制收起并提示，绝不把用户锁在遮罩里；onDestroy 清掉全部延迟回调
- 连点接管：上一轮渲染已结束、只差 180ms 停留动画时，下一次切换直接接管，不被防重入吞掉

═══ 二、镜像测速：并行开关 + 自定义并发数 ═══
- 更新页就地加「并行测速」CheckBox，默认开启；关闭后走单线程池严格串行（更省内存，80+ 源会明显变慢）
- 「并发 N」按钮弹输入框，可设 1~64，默认 6；越界值拒绝，偏好持久化在 aichat_update
- 测速线程池从固定 newFixedThreadPool(3) 改为按并发数按需重建；关闭并行等价于 1 线程池，任务按提交顺序执行，线程降一档优先级避免高并发饿着主线程
- 进度文案显示本轮模式（并行 6 路 / 串行 / 单源），测速完成结果也带模式后缀
- 每次全量测速先 cancelProbe 断开上一轮连接，避免改了并发数立刻重测时旧任务仍占着连接；left 计数移进 finally，单项异常不会让「测速」按钮一直灰着
- 风险写进界面：并发 TLS 连接数与内存占用成正比，老设备（66MB 级堆）建议不超过 8，上限硬编码 64

═══ 验证 ═══
- ./gradlew compileDebugJavaWithJavac / assembleDebug / assembleRelease：BUILD SUCCESSFUL；debug 与 release 均用项目正式签名（mc.jks / alias mc）构建，CI 用同一密钥签发正式包
- 构建配置：debug buildType 也挂 signingConfigs.release，调试包可直接覆盖安装在已装正式包的词典笔上；签名口令仍走构建参数注入，未写进仓库
- 兼容性基线不变：minSdk 18 / targetSdk 18 / compileSdk 34，258dp 窄屏与 API 18/19 TLS 兼容层未改动
- 发布产物：armeabi-v7a / arm64-v8a / x86 / x86_64 / universal 五 ABI + speedtest.txt（魔数 AICHAT_SPEEDTEST_V1）

---
**Full Changelog**: https://github.com/zip8919/AiChat/compare/v1.14.19...v1.14.20
