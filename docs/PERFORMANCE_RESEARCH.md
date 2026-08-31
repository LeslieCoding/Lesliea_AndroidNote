# 性能调研报告 — iPad 主流手写笔记软件技术与本应用集成方案

> 2026-08-21 调研，覆盖 GoodNotes、Notability、Noteshelf、ZoomNotes 四款 iPad 笔记软件 + iOS/Android 平台级低延迟与缓存技术。所有结论附来源；传闻/推测单独标注。对应本应用（Java，自研墨迹引擎）现状给出"已具备 / 本轮集成 / 后续规划"三级映射。

## 一、四款软件的关键性能技术（有来源支撑）

### GoodNotes
- 自研跨平台渲染引擎（iOS=Metal，Web=WebGL），渲染逻辑共享；实时墨迹要求 60+ FPS。[Swift.org 官方博客](https://swift.org/blog/bringing-goodnotes-to-web-with-swift/)
- Windows 端用 Web Ink API（DelegatedInkTrailPresenter）把笔尖轨迹直接交给 OS 合成器先显示（"湿墨/干墨"双层）。[PWA Builder Blog](https://blog.pwabuilder.com/posts/how-goodnotes-uses-web-apis-to-create-a-great-pwa-for-windows/)
- 消费合并触控事件（coalesced events）；2025-06 接入 iOS 预测触控降低延迟。[Goodnotes 更新日志](https://www.goodnotes.com/blog/features-fixes-updates-june-2025)
- 笔迹矢量存储，GPU 曲线细分 + shader 抗锯齿（渲染工程师招聘明示）。[招聘页镜像](https://talents.studysmarter.co.uk/companies/goodnotes/london/senior-graphics-engineer-ink-rendering-expert-25858889/)
- 手写 OCR 完全端侧、后台边写边建索引、索引进度可见、可按文档重建；**导入 PDF 只索引内嵌文本层，不做图像 OCR**。[官方博客](https://www.goodnotes.com/blog/machine-learning-engineer-handwriting-recognition)、[搜索支持页](https://support.goodnotes.com/hc/en-us/articles/7353743594127-How-to-Search-Your-Notes)
- 官方把 500+ 页文档/单页过多笔迹/大 PDF 列为已知卡顿来源。[性能排障页](https://support.goodnotes.com/hc/en-us/articles/13318380461967-Performance-and-Stability-Troubleshooting-for-Android-Windows-and-Web)

### Notability
- Android 版官方确认"触摸预测 + 系统低延迟绘图通路"。[官方新闻稿](https://www.morningstar.com/news/business-wire/20260811736505/notability-brings-fully-native-ai-assisted-note-taking-to-android)
- 低延迟三招（WWDC 平台级）：coalesced touches 取回全部采样、predicted touches 预测下一帧、setNeedsDisplay(InRect) 帧对齐 + 脏矩形渲染。[WWDC16-220 实录](https://nonstrict.eu/wwdcindex/wwdc2016/220/)
- 墨迹 = float 点序列 + 逐点宽度乘数 + 样式的矢量结构；Bézier 拟合；选择/橡皮归约为点到曲线距离（Bézier Clipping）。[官方工程博客](https://blog.notability.com/post/notability-object-selection-adventures-in-vector-graphics)、[格式逆向](https://jvns.ca/blog/2018/03/31/reverse-engineering-notability-format/)
- .note 容器内预生成 4 档缩略图；录音联动 = 内容对象记录相对录音起点的时间戳。
- 手写识别采购 MyScript（不自研）。

### Noteshelf / ZoomNotes（均无公开引擎资料，以下为用户手册级事实）
- ZoomNotes：墨迹为矢量对象模型；**View Filtering（LOD 剔除）——按屏幕像素阈值跳过过小图形/过细线宽的绘制**，官方手册明示的防卡顿手段。[官方手册 PDF](http://www.zoom-notes.com/Documents/ZoomNotes7.5.pdf)
- 笔宽语义分"绝对/跟随缩放"（Follow Zoom）；压力映射按笔型差异化（粗细/浓淡/无响应）。
- 备份走原生格式"零渲染成本"，PDF 导出才走渲染管线（后台队列）。
- 视图状态栈（View rewind）；1:1 物理尺寸模式；主动笔 4 点校准。
- Noteshelf 前车之鉴：启动路径被云同步检查阻塞（官方 Known Issues）；荧光笔分层合成在墨迹下层。[Noteshelf 支持站](https://noteshelf-support.fluidtouch.biz/hc/en-us)

### 平台级通用技术
- iPadOS 9ms 延迟 = Metal + 触控预测 + 帧中事件处理。[MacRumors](https://www.macrumors.com/2019/06/21/apple-pencil-latency-ipados-developers/)
- Android 对应：MotionEvent 历史采样消费（getHistoricalX/Y/Pressure）、`androidx input-motionprediction:1.0.0`（MotionEventPredictor）、`androidx graphics-core` 前缓冲渲染（GLFrontBufferedRenderer/CanvasFrontBufferedRenderer/LowLatencyCanvasView）、Choreographer 帧对齐。[androidx input 发布说明](https://developer.android.google.cn/jetpack/androidx/releases/input)、[androidx graphics 发布说明](https://developer.android.google.cn/jetpack/androidx/releases/graphics)
- 大 PDF 内存工程（Nutrient/PSPDFKit）：页面位图缓存**按字节定预算**（RAM 10%，钳 256MiB–1.5GiB）+ 磁盘二级缓存 + 自建内存分级监控（Android 14/15 后 TRIM_MEMORY_RUNNING_CRITICAL 不可靠）。[Nutrient 工程博客](https://www.nutrient.io/blog/android-pdf-out-of-memory-handling/)
- 位图缓存官方指南：LruCache 取堆 1/8、sizeOf 按字节、onTrimMemory 收缩 + DiskLruCache 二级缓存。[Android 官方指南](https://developer.android.google.cn/topic/performance/graphics/cache-bitmap)
- Jetpack Ink API（2025-12 起 1.0 稳定，Google 内部 Sketchology 引擎公开化）作为长期架构参照。

## 二、与本应用现状的对照与集成结论

| 技术点 | 本应用现状 | 结论 |
|---|---|---|
| MotionEvent 历史采样消费 | ✅ StrokeBuilder 已遍历 getHistorySize（含压感/倾角/方向历史） | 已具备 |
| 触控轨迹预测 | ✅ core-ink MotionPredictor + StrokeBuilder 预测点渲染，InkView 已接线 | 已具备 |
| 脏矩形局部刷新 | ✅ InkView.invalidatePageRect（页空间→屏空间映射） | 已具备 |
| 字节预算 LRU（PDF/Office 底图） | ✅ BackgroundRenderCache + LruMemoryCache（1/8 堆、sizeOf 字节、逐出回收 Bitmap） | 已具备 |
| 缩略图字节 LRU | ✅ ThumbnailCache（1/8 堆、后台线程加载） | 已具备 |
| journal 增量落盘（抗崩溃） | ✅ append-only journal + 2s 防抖整存 + onPause 即存 | 已具备 |
| 索引/OCR 后台化 | ✅ SearchIndexScheduler 专用调度 + AppExecutors.io | 已具备 |
| **内存压力分级缓存收缩** | ❌ 无 onTrimMemory 响应 | **本轮集成（v1.1.1）**：core-common 新增 CacheTrimRegistry；PdfBackgroundProvider/OfficeBackgroundProvider/ThumbnailCache 注册 trim 目标；InkNoteApp.onTrimMemory 按级别收缩（UI_HIDDEN→50%，LOW/BACKGROUND→25%，CRITICAL/MODERATE→10%，COMPLETE→清空） |
| 导入 PDF 不做图像 OCR | ✅ 与 GoodNotes 策略一致（只索引文本层） | 已具备，写入 KNOWN_LIMITATIONS |
| 前缓冲渲染（graphics-core wet/dry 双层） | ❌ 当前为 View 内 onDraw 双层（烘焙位图+进行中笔迹） | **后续规划**：CanvasFrontBufferedRenderer 需 InkView 迁 SurfaceView 体系，改动面大，待大版本窗口评估（参照 Jetpack Ink 的 wet/dry 分层） |
| LOD 屏幕像素剔除（ZoomNotes View Filtering） | 部分（底图按宽度档位量化） | 后续规划：连续模式低缩放时跳过投影 <2px 的笔迹段 |
| 笔宽 Follow-Zoom 语义 / 视图状态栈 / 录音时间戳联动 | ❌ | 功能项而非性能项，记入需求池，不在本轮 |
| 磁盘二级缓存（底图页） | ❌ 内存 LRU 单级 | 后续规划：大 PDF 场景再加 DiskLruCache |

## 三、本轮集成明细（v1.1.1）

1. `core-common/CacheTrimRegistry`：线程安全注册表，`trimAll(keepFraction)` 钳制 [0,1]、单目标异常不影响其余；5 个 JVM 单测覆盖。
2. `BackgroundRenderCache.trimToFraction(fraction)`：按字节预算等比收缩（LruMemoryCache.trimToSize）。
3. `PdfBackgroundProvider`/`OfficeBackgroundProvider`：构造注册、release 反注册（防泄漏）。
4. `ThumbnailCache`：注册收缩目标（保留 maxKb 字段），LibraryFragment.onDestroyView 反注册。
5. `InkNoteApp.onTrimMemory`：全级别映射收缩（含 Android 14/15 不再可靠投递的 RUNNING_CRITICAL，仍处理）。

## 四、调研局限

- Notability/Noteshelf/ZoomNotes 的底层渲染框架（Metal/Skia）、tile 缓存结构、线程模型无公开一手资料，报告仅采用官方文档/手册/工程博客级证据，其余均已标注传闻。
- "前缓冲降延迟 50%"、"Ink API 端到端 4ms" 为官方宣传二手转述，未独立验证。
