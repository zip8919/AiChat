AiChat v1.14.21 —— 测速结果按延迟排序（不可用垫底）

测速完成后，加速源列表自动按实测延迟从低到高重排，下载失败 / 校验不过的镜像统一排到末尾，不用再自己翻列表找最快的那个。

═══ 改动 ═══
- 排序键：可用（latency >= 0）按毫秒升序；未测（NOT_TESTED）次之；不可用（FAILED，含 HTTP 200 但返回 HTML 错误页的失效镜像）垫底
- 用 rank 而不是直接比 latency：NOT_TESTED(-1) 与 FAILED(-2) 都是负数，直接升序会把「不可用」顶到列表最前面
- Collections.sort 是稳定排序，延迟相同的源保持原顺序，直连与内置源的相对位置不乱
- 排序放在全部测速回调结束之后（onProbeFinished），不干扰 onProbeDone 按索引刷新行；随后 notifyDataSetChanged 整表刷新
- 选中项按对象引用重新定位，不再依赖下标：保留「测速期间手动点选就不改选」的既有语义；未手动点选时仍自动选中最快的那个
- 单源测速（长按「测速此源」）不重排：其余源此刻都是「未测」，重排只会把列表打乱，没有信息量
- 更新页常驻提示补充排序说明，进度条文案保持简短，避免 258dp 窄屏被截断

═══ 验证 ═══
- ./gradlew compileDebugJavaWithJavac / assembleDebug / assembleRelease：BUILD SUCCESSFUL；debug 与 release 均用项目正式签名（mc.jks / alias mc）
- 兼容性基线不变：minSdk 18 / targetSdk 18 / compileSdk 34，258dp 窄屏与 API 18/19 TLS 兼容层未改动
- 发布产物：armeabi-v7a / arm64-v8a / x86 / x86_64 / universal 五 ABI + speedtest.txt（魔数 AICHAT_SPEEDTEST_V1）

---
**Full Changelog**: https://github.com/zip8919/AiChat/compare/v1.14.20...v1.14.21
