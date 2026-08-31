# 深缩放书写：延迟、撕裂与"写小字"的竞品调研

日期：2026-08-31 ｜ 对应版本：v2.4.0 → v2.5.0 改造依据
范围：压感链路、圆滑渲染、低延迟三条链路在**深缩放**下的行为，以及 iPad/Android
竞品实际怎么解决"放大写小字"。所有结论附来源；营销话术与工程事实分开标注。

> 本文只补 v1.1.1 的 `PERFORMANCE_RESEARCH.md` 和 v1.4.15 的
> `reports/ZOOM_RENDER_RESEARCH.md` 没覆盖的部分：**缩放**维度。笔刷宽度模型、
> 1€ 滤波参数、perfect-freehand 常量等已在 `BRUSH_RESEARCH.md` 调研过，不重复。

---

## 〇、最重要的一条结论（推翻了本项目一直以来的隐含假设）

本项目从 v1.4.15 起一直在做一件事：**把画布缩放到极大倍数，然后想办法让在那个
倍数下书写既不糊、不撕裂、又不延迟**。历次迭代（v2.0.2 自适应细分 → v2.1.1 矢高
覆盖圆盘 → v2.2.0 圆盘解耦 → v2.4.0 weld 像素上限）都是在这条路上加固。

调研结果是：**iPad 上那些"写小字很爽"的笔记软件，并不是靠解决深缩放渲染做到的。
它们是靠一个叫 Zoom Window（放大书写窗口）的功能，让用户根本不需要深缩放。**

支撑证据有两个方向：

1. **连 Apple 自己的 PencilKit 都没解决深缩放书写。** 开发者论坛里长期存在的问题：
   PKCanvasView 缩放后笔迹变模糊、需要手动想办法重渲；叠在 PDFView 上缩放后画质
   变糊；以及在**非 1x 缩放下书写时 PKCanvasView 内部 tile 渲染会闪烁**，应用层
   试过延迟 contentInset 更新、书写期间锁住布局重算、合并 CATransaction，
   **都解决不了**。
   来源：[PencilKit: zoom PKCanvasView, drawing blurred](https://developer.apple.com/forums/thread/698317)、
   [CanvasView overlay on PDFKit loses quality when zoomed](https://developer.apple.com/forums/thread/792941)、
   [PKCanvasView flicker](https://developer.apple.com/forums/thread/818218)

2. **GoodNotes 把"写小字"当成一个独立功能来做，而不是当成缩放的副产品。**
   Zoom Window：屏幕上半部是放大的书写区，下半部实时显示缩小后落在页面上的效果；
   页面上有一个可调大小的 Zoom Box 框出当前区域，**框越小放大倍数越高**；配合
   Auto-Advance，笔画越过窗口中线后窗口自动移到下一行，可以连续书写不用手动挪。
   来源：[Goodnotes 官方支持文档：Write with the Zoom Window](https://support.goodnotes.com/hc/en-us/articles/7353756826383-Write-with-the-Zoom-Window)、
   [How and Why to Use the Zoom Window in GoodNotes](https://emily-c-hokett.medium.com/how-and-why-to-use-the-zoom-window-in-goodnotes-433db0543e3d)

**对本项目的含义**：我们在 1000% 缩放下反复遇到的撕裂/延迟/线宽问题，本质上是在
一条竞品刻意绕开的路上硬走。深缩放的加固不该丢（它保证了缩放看细节时的清晰度），
但真正该补的功能是 Zoom Window——用户想要的是"把字写小"，不是"把画布放大"。

> 注意：检索到一项相关专利 [US20150067489 "ZOOMABLE PAGES FOR CONTINUOUS DIGITAL
> WRITING"](https://patents.justia.com/patent/20150067489)。放大书写窗口本身在
> Notability / Noteshelf / OneNote / Samsung Notes 等多家产品中长期存在，属通用
> 交互，但若本项目将来要商用分发，这条值得让法务确认一次。本文只作提示，不作
> 法律结论。

---

## 一、压感：链路已对齐，无新增缺口

竞品与平台的做法与本项目现状一致，本轮无新发现需要落地：

- 笔尖压力传感器 → 电信号 → 协议上报，应用侧只是消费 `MotionEvent` 的
  pressure 通道。本项目 `PressureSource` 已做真实/模拟判定并在 UI 明示。
- Android 运动预测 `MotionEventPredictor.predict()` 返回的预测事件**同时带 x/y、
  压力、方向、倾斜**——不只是坐标。本项目 `StrokeBuilder.updatePrediction` 已经
  在读 `getHistoricalPressure`，符合官方用法。
  来源：[Advanced stylus features（Views）](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/advanced-stylus-features)
- 官方明示的两条纪律，本项目均已满足：**预测点只能临时显示、不得进入最终笔迹**；
  **新预测点出现时必须先移除旧预测点**。

---

## 二、圆滑：本项目路线与竞品一致

- GoodNotes 把笔迹存为矢量而非栅格，"每条线、形状、高亮都保留为几何图形，任意
  缩放 100% 清晰"；Notability 的 Pencil Tool 同样是矢量墨水，缩放不像素化。
  来源：[Paperlike: Goodnotes vs Notability](https://paperlike.com/blogs/paperlikers-insights/app-review-goodnotes-vs-notability)
- 本项目已是"矢量为唯一事实源 + 按当前 scale 重栅格化 + 圆形笔刷沿曲线扫过 +
  抗锯齿填充"，与竞品同构。v2.4.0 的像素级覆盖测试已证明 1000% 下 0 内部空洞、
  外缘偏差 ≤1 device px。

**本轮结论：圆滑这条线没有需要补的东西。**

---

## 三、低延迟：还剩两级没吃到，其中一级的阻塞理由已经过期

### 3.1 前缓冲渲染（front-buffer rendering）——阻塞理由已过期

v1.1.1 调研当时的结论是"需要把 InkView 迁到 SurfaceView 体系，改动面大，后续
规划"。**这个理由现在不成立了**：`androidx.graphics:graphics-core` 已发布正式
稳定版，其中的 **`LowLatencyCanvasView`** 就是为了消除这个障碍而生的——它支持用
普通 Canvas + Paint 在 View 层级内做低延迟渲染，并**在内部自行管理 SurfaceView
实例**（按需平移进/出屏幕以在"同步渲染"和"低延迟渲染"之间切换），应用侧不需要
自己迁移到 SurfaceView 体系。
来源：[graphics 发布说明](https://developer.android.com/jetpack/androidx/releases/graphics)、
[androidx.graphics.lowlatency API 参考](https://developer.android.com/reference/androidx/graphics/lowlatency/package-summary)

原理：绕开多缓冲，直接写屏幕缓冲区，省掉一次缓冲交换。
来源：[Advanced stylus features](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/advanced-stylus-features)

### 3.2 ⚠️ 但官方明确警告：前缓冲渲染在缩放/平移时会产生撕裂

这一条对本项目特别重要，必须原样记下来：

> Front-buffer rendering should only be used for small portions of screen
> (handwriting, drawing, sketching). **Avoid for fullscreen updates, panning,
> or zooming to prevent tearing artifacts.**
> ——[Advanced stylus features](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/advanced-stylus-features)

也就是说：**如果为了降延迟而无脑接前缓冲渲染，会在缩放/平移时引入撕裂**——正是
用户这几轮一直在抱怨的那个症状。所以接入方案必须是"只在笔尖落下书写期间开前缓冲，
一旦进入捏合缩放/平移立刻 commit 回双缓冲"，不能全程开着。

### 3.3 Jetpack Ink API（androidx.ink）——Google 的官方答案

2024 年起 Google 提供了 Ink API，把本项目自研的这一整套东西官方化了：
Brush 模块（声明式笔刷）、Rendering 模块、**Live Authoring 模块（wet/dry 分层，
以设备能提供的最低延迟处理实时输入）**，其底层正是用上面那个低延迟图形库，
"通过绕过传统 UI 渲染管线的一部分来显著缩小笔尖与渲染线条之间的差距"。
来源：[Introducing Ink API](https://android-developers.googleblog.com/2024/10/introducing-ink-api-jetpack-library.html)、
[Add inking to your app with the Ink API（Views）](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/about-ink-api)、
[ink 发布说明](https://developer.android.com/jetpack/androidx/releases/ink)

**评估结论：不迁移。** 本项目的墨迹内核（压力曲线、书法 nib、橡皮矢量切割、
撤销命令模型、.inknote 格式）已深度自研且有 576 项测试覆盖，换 Ink API 等于重写
core-ink + core-render 并放弃已验证的几何修复。**但 Ink API 的 wet/dry 分层思路
值得单独借鉴**——这正是 3.1 那条要做的事。

### 3.4 已经吃到的部分（无需重做）

- MotionEvent 历史采样全取 ✅
- `requestUnbufferedDispatch()` ✅
- 运动预测 ✅（v2.3.0 才真正修好：反射类名一直写错，从未生效）
- 高刷屏 120Hz ✅

---

## 四、缩放专项：竞品的三条具体做法

### 4.1 LOD / View Filtering（ZoomNotes）

ZoomNotes 官方手册明示的防卡顿手段：**按屏幕像素阈值跳过过小的图形和过细的线宽**
不绘制。缩放到很小时，投影不足 1–2 px 的笔迹段直接不画。
来源：[ZoomNotes 官方手册 PDF](http://www.zoom-notes.com/Documents/ZoomNotes7.5.pdf)（PDF 为图形流，本轮未能提取原文，此条沿用 v1.1.1 调研时的记录）

本项目状态：底图按宽度档位量化算部分实现；笔迹层未做屏幕像素剔除。**属于缩小
方向的优化，与用户当前抱怨（放大方向）无关，优先级低。**

### 4.2 笔宽语义：绝对宽度 vs 跟随缩放（Follow Zoom）

ZoomNotes 把笔宽分成"绝对"和"跟随缩放"两种语义。这直接对应用户问的"线宽有没有
解决"：放大 10 倍后，2pt 的笔应该在屏幕上画出 20 倍粗的线（跟随缩放，本项目当前
行为），还是仍然是屏幕上 2pt 的细线（绝对宽度，用于放大后写小字）？

**本项目当前只有"跟随缩放"一种语义。** 这解释了为什么用户放大到 1000% 后觉得
线太粗、要一直调线宽——因为没有"绝对宽度"这个选项。不过 Zoom Window（见第五节）
从交互上更彻底地解决了同一需求，优先做 Zoom Window。

### 4.3 缩放上限

PencilKit 的官方示例里 `maximumZoomScale` 用 10；本项目 `MAX_ZOOM_FACTOR = 10`
与之一致，无需调整。
来源：[Simple Introduction to PencilKit](https://medium.com/@chaemin2001/simple-introduction-to-pencilkit-draw-on-an-image-with-zoom-in-and-out-features-1f8c8f78e803)

---

## 五、落地优先级

| 项 | 价值 | 代价 | 结论 |
|---|---|---|---|
| **Zoom Window（放大书写窗口）** | 极高——直接解决用户真实诉求，且绕开整条深缩放难题 | 中（新 UI + 复用现有 InkView/Page） | **本轮做** |
| `LiveInkQualityPolicy` 常量语义修正 | 中——当前常量是绝对 scale，注释却按缩放倍数论证，换设备会复发 | 极低 | **本轮做** |
| 前缓冲渲染（LowLatencyCanvasView） | 高——最后一级延迟 | 高，且必须只在书写期开启，否则缩放/平移撕裂 | 下一轮，单独立项 |
| 笔宽绝对/跟随缩放语义 | 中 | 低 | Zoom Window 之后按需 |
| LOD 屏幕像素剔除 | 低（缩小方向） | 中 | 暂不做 |
| 迁移到 Jetpack Ink API | 负 | 极高（重写内核） | 不做 |

---

## 六、调研局限（诚实清单）

- **云记（com.jideos.jnotes）没有任何工程级公开资料。** 各下载站的描述只有
  "流畅纸笔书写体验和低延迟数字墨水""低延迟高精度的压感技术"这类营销话术，
  没有渲染架构、tile 结构、缩放策略的一手信息。
  来源：[应用宝 云记](https://sj.qq.com/appdetail/com.jideos.jnotes)
  想拿到真实做法只能反编译其 APK 或抓 GPU trace，本轮未做。
- GoodNotes / Notability / Noteshelf 的底层渲染框架、tile 缓存结构、线程模型
  同样无公开一手资料；本文只采用官方支持文档、Apple 开发者论坛、官方手册级证据。
- ZoomNotes 手册 PDF 本轮未能提取文本（PDF 为图形流），View Filtering 一条沿用
  v1.1.1 调研时的记录，未重新核对原文。

---

## 附录（v3.0.0 补充调研）：书写体验功能，竞品有而本项目没有的

第五节只覆盖了"缩放/延迟"这条线。v3.0.0 这轮补查了 iPad 竞品**提升书写手感的功能**，
对照代码后确认了四个真空缺（均已在 v3.0.0 落地）：

### 1. Ink-to-Shape / 画完按住变形状
GoodNotes、Notability、Noteshelf **三家都有**：一笔画完后把 Apple Pencil 笔尖按住不
放，应用就把它修成完美的圆、直线、三角或矩形；GoodNotes 还把它和
Line Straightening / Stroke Stabilization 并列为书写整洁功能。
来源：[Paperlike: 13 Tips to Take Neater Notes on iPad](https://paperlike.com/blogs/paperlikers-insights/neater-notes)、
[Goodnotes 2025 年功能更新](https://www.goodnotes.com/blog/features-fixes-updates-march-2025)、
[Astropad: How to Make Handwriting Better on iPad](https://astropad.com/blog/how-to-make-handwriting-better-on-ipad-8-tips/)

本项目当时：**没有**。而且 `EditorSettings.longPressShapeFix`（"长按图形修正"）这个
开关从 v1.x 起就存在、被持久化、能在设置里改，**但全项目没有任何代码读它**——用户
打开它什么也不会发生。v3.0.0 把 `ShapeRecognizer` 接到了这个开关上。

### 2. 涂抹擦除（scribble to erase）
在写错的地方来回涂几下就删掉，不用切换到橡皮。
来源：[Paperlike: 13 Tips to Take Neater Notes on iPad](https://paperlike.com/blogs/paperlikers-insights/neater-notes)

本项目当时：**没有**。v3.0.0 落地 `ScribbleEraseRecognizer`。

### 3. 手写笔悬停预览
iPadOS 16 起 Apple Pencil 悬停可预览落点；Android 同样有 stylus hover 事件
（`ACTION_HOVER_*`）。本项目当时全项目 `onHoverEvent` **零实现**。v3.0.0 落地。

### 4. 笔宽绝对 vs 跟随缩放
见正文 4.2。本项目当时只有"跟随"。v3.0.0 落地"固定"模式。

### 顺带澄清一条 iPadOS 的功能（本项目不适用）
Apple 的 **Scribble** 指的是"用笔在文本框里手写、自动转成打字文本"，不是涂抹擦除；
GoodNotes / Notability 支持它是因为它是 iPadOS 系统能力。
来源：[Creative Bloq: handwriting on the iPad](https://www.creativebloq.com/how-to/handwriting-on-the-iPad)
Android 侧的对应能力是 Scribe（输入法手写），与本项目的墨迹引擎无关，不列入需求。
