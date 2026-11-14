AiChat v1.14.22 —— 测速过程边测边排（实时跟随最快源）

测速不再等全部跑完才排序。每有一个加速源出结果，列表立刻按延迟重排一次；判成不可用的当场沉到末尾，测出更快的当场跟着改选。测速过程中就能看着列表收敛，不用盯着进度条干等。

═══ 改动 ═══
- 排序时机从 onProbeFinished 一次性重排，改成 onProbeDone 每收到一项结果就重排：可用按延迟升序、未测居中、FAILED 垫底；Collections.sort 稳定排序，同延迟保持原顺序
- 回调契约改为传 Mirror 对象而不是列表下标：probeAll 提交任务时捕获对象引用，列表实时重排后下标必然失效，继续用 index 会把结果刷到别的行上
- 选中项实时跟随当前最快：每出现更快的源就改选并写偏好；只在真正换源时才写盘，避免 80+ 次回调逐次落盘
- 手动点选优先：测速期间用户点过某个源就停止自动跟随，结束时只文字告知最快项，不覆盖用户选择
- 对话框保护：长按菜单 / 编辑地址 / 移除确认都把行下标捕获进点击回调，列表一跳就会操作错源。用 openDialogs 计数器在对话框打开期间暂停重排，只刷新单行数值；全部关闭后补排一次。用计数器而非布尔值，兼容「菜单里再点编辑」的嵌套弹窗
- 单源测速仍不重排：其余源此刻都是「未测」，重排没有信息量
- Mirror.latency 改 volatile：后台测速线程写、主线程排序读，明确跨线程可见性
- 进度文案收短为「边测边排」，常驻提示补充实时排序与跟随最快说明，避免 258dp 窄屏截断；修正页面类注释里过时的「7~9 项 / LinearLayout」描述

═══ 验证 ═══
- ./gradlew compileDebugJavaWithJavac / assembleDebug / assembleRelease：BUILD SUCCESSFUL；debug 与 release 均用项目正式签名（mc.jks / alias mc）
- 兼容性基线不变：minSdk 18 / targetSdk 18 / compileSdk 34，258dp 窄屏与 API 18/19 TLS 兼容层未改动
- 发布产物：armeabi-v7a / arm64-v8a / x86 / x86_64 / universal 五 ABI + speedtest.txt（魔数 AICHAT_SPEEDTEST_V1）

---
**Full Changelog**: https://github.com/zip8919/AiChat/compare/v1.14.21...v1.14.22
