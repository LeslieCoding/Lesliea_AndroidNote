# 墨迹引擎（INK_ENGINE）

> 全项目最高优先级模块（spec 5/6/7）。core-ink（几何内核，纯 JVM 可测）+ core-render（Android 绘制壳）。

## 采样管线
`StrokeBuilder`：MotionEvent → StrokePoint 全字段（x/y/t/pressure/tilt/orientation/toolType/pointerId/velocity）；historical 批量事件全取；stylus 优先（TOOL_TYPE_STYLUS），手指按 PalmRejectionPolicy（STYLUS_ONLY / FINGER_AND_STYLUS）；笔落下时掌触拒绝、笔中途可接管。`MotionPredictor` 对 androidx input-motionprediction 做反射隔离包装，库缺失自动 NoOp 降级——预测点只用于显示、不提交进笔迹。

## 压感
`PressureSource`：前 12 个 stylus 样本方差判定 REAL/SIMULATED/UNAVAILABLE；SIMULATED 用速度映射（快=细）且 UI 显示「模拟压感」（不伪装真实压感，spec 5.2）。
`PressureCurve`：SOFT=p^0.75、BALANCED=0.5p+0.5smoothstep、FIRM=p^1.8（均带 0.12 地板，轻压防断墨、高压渐进饱和）；CUSTOM 4~6 控制点强制单调插值。

## 平滑与稳定器（spec 7）
`StrokeSmoother`：1€ 自适应低通（位置 beta=0.07、导数截止 2Hz）+ stabilization 拉线（默认关——禁止橡皮筋感）+ 独立压感 1€ 滤波（2.5Hz、beta=0.04）。慢写去抖、快写低滞后；压力阶跃约一组 240Hz 样本内开始明显响应。

## 笔刷（spec 6）
`BrushEngine` + `StrokeMesh`：宽度 = lerp(base, min+(max-min)·curve(p), pressureToWidth) × velocity 因子 × tilt 因子 × 书法 nib 因子 × taper；速度因子保留 0.22 的物理笔尖下限，100% 速度感应也不会断墨。中心线经 centripetal Catmull-Rom 拟合，曲线和圆头帽按最终 device-pixel scale 自适应细分（普通倍率低成本，深度缩放自动加密），中心差分法向生成左右轮廓后单次 fill Path——**不是逐点画点**。BrushPreset 可序列化（spec 6 全字段）。

## 橡皮（spec 6.6）
`EraserEngine`：PIXEL 局部擦除 = 矢量切割（擦除谓词逐点标记 + 边界二分插值切点，保留段成新 stroke）；STROKE 整笔；AREA 矩形/套索区域；INK_ONLY/HIGHLIGHT_ONLY 过滤。绝不 rasterize 整页。

## Undo/Redo（spec 30）
Command 模式（AddStroke/RemoveStroke/MoveObjects/TransformStroke/ChangeColor/AddObject/RemoveObject/Composite）；拖动 begin/endTransaction 合成一条；容量 100；同类命令 mergeWith 合并。

## 渲染（低延迟，spec 5.3）
`InkView`：onDraw = 底稿 → 完成笔迹 Bitmap（按页离屏，LRU 48MB）→ 在绘 mesh → 半透明预测点；活笔迹稳定段+尾段合成一次抗锯齿填充，避免深度缩放下共享弦接缝。超整页位图上限后启用 device-resolution 视窗位图；若笔在后台重渲期间完成，swap 前把新增 stroke 增量补进新位图，避免旧快照覆盖新墨迹。脏矩形局部 invalidate；整页首渲在 compute 线程；**主线程零 IO**。事件到首像素目标 1 帧内（60Hz p95<20ms，真机验收见 COLOROS14_DEVICE_ACCEPTANCE.md）。
