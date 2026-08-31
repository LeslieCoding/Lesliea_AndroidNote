# 交接日志 — Leslie ColorOSNote

> 按 AGENTS.md 要求：每阶段记录改了什么、解决了什么、发现什么、接下来要改什么。最新在上。

## 2026-08-31 v2.1.1 → v2.2.1（versionCode 56–58）— 1000% 缩放内零延迟零撕裂专项 + UX 五连

> 背景：真机验收（OPPO Pad，OPD2101 / 2d64f765）中用户暴露四类问题：极高倍下手写撕裂、宽线/半透明笔刷延迟、 UX 细节（整页回跳按钮、缩放指示器、横竖屏弹框、侧栏宽度）。本轮全部修复并真机验证，最终在 OPPO Pad 实测 1000% 任意笔刷任意线宽 P50≈6ms、无卡顿帧、无撕裂。

### v2.1.1 — 矢高覆盖中点圆盘 + 整页回跳按钮（vc 56）
- **撕裂根因**：深缩放下活笔迹细分被延迟预算压低（6–12×），弯曲外凸部被弦切割出月牙空白。`LiveOutlineBuilder` 在弯曲子段中点发射矢高覆盖圆盘（半径随宽度），`InkView` 渲染之；单测锁定。
- **UX**：编辑器右上角新增整页按钮（视口被用户变换后显示），点击 `ViewportTransform.fitPage` 一键回整页。
- 临时改动：debug 缩放广播在 release 可用 + R8 关闭（测试用，尚未还原）。

### v2.1.2 — 顶点+中点双圆盘（vc 同 56 修复轮）
- 宽线（外缘下垂 ∝ (R+w/2)(1−cosθ)，与中心线细分容差无关）在中点圆盘间距下仍露细缝 → 子段顶点补圆盘，间距减半。圆珠笔/铅笔弧线实心。

### v2.1.3 — 缩放上限 1000% + 蓝色缩放指示器（vc 56→同轮）
- `ViewportTransform.MAX_ZOOM_FACTOR = 10`（以 fitPage=100% 为基准），捏合/调试广播统一受限。
- 顶部正中胶囊指示器实时显示缩放百分比（蓝色加粗），不拦截笔输入；连续滚动模式自动隐藏。

### v2.1.4 — 指示器自动消失 + 弹框旋转适配 + 侧栏优化 + 钢笔 O(1) 烘焙 blit（vc 56→同轮）
- 指示器：停止缩放 1.5s 自动淡出（计时器随每次视口变化重置），再次缩放重现。
- **横屏弹框 bug**：`NewItemBottomSheet` 内容超高时 BottomSheet 停在 peek 高度 → 固定 90% 屏高强制展开；`onConfigurationChanged` 旋转时按当前方向重算（修复横转竖后弹框只占上半 2/3 的 bug）。
- **侧栏**：横屏 264dp → 216dp → 162dp 两轮收窄；内容区汉堡按钮可折叠/展开。
- **钢笔延迟根因**：实时笔画每帧重栅格化整个累积 Path（笔画越长每帧越贵）。`InkView` 增量烘焙位图：已提交几何逐批烘入，每帧 blit + 绘制瞬态笔尖，成本与笔画长度无关。P50 16ms→9ms。

### v2.2.0 — 任意笔刷任意线宽的延迟/撕裂大迭代（vc 57）
- **宽线卡顿根因**：覆盖圆盘数量与细分数挂钩（每点 ~48 个 addCircle），与线宽无关。`LiveOutlineBuilder` v2.2.0 解耦：圆盘按 **ceil(1.15·chord / 0.85·width)** 沿样条均匀发射——线越粗盘越大越少（粗线每段 48→2–3 个），细线盘更密仍无缝；直线段零圆盘。
- **细线笔尖撕裂根因**：瞬态笔尖只有 1 个直四边形，弯道矢高处切缝。新增瞬态笔尖细分条（Catmull-Rom，第 4 控制点折叠到末端）≤8 子四边形 + 逐区间圆盘，笔尖实时贴曲线。
- **半透明笔卡顿根因**：铅笔/荧光笔每帧 `liveCompositePath.set+addPath` 整路径拷贝。改 saveLayer 离屏合成：笔身 blit + 笔尖在层内不透明栅格化，整层一次 alpha 合成（重叠不加深）。
- 单测更新：圆盘语义改为自适应断言 + 新增宽线圆盘预算上限、笔尖细分条链式无缝测试。

### v2.2.1 — 收笔零重栅格化 + 半透明层限界（vc 58）
- **收笔掉帧根因**：`commitStrokeToBitmap` 收笔时整笔画重栅格化进 base/window 两层位图（粗长笔画单帧数十 ms，恰在提笔瞬间卡）。快路径：笔尖瞬态段折入烘焙位图后，直接矩阵变换 blit 进两层（成本 ∝ 笔画面积而非几何复杂度），半透明笔统一乘一次 alpha；烘焙无效时回退原路径。
- **半透明层限界**：translucent saveLayer 由整视口改为笔迹页面包围盒（样本 dirty 并集，clamp 到 clip），GPU 层尺寸大幅缩小。
- 真机（1000%、27.5pt 巨粗钢笔、8 笔连续）：P50 6ms / P90 9ms / P95 12ms / P99 17ms，无 >17ms 帧；收笔完整无缺 tail、无错位。
- 中途修复一次构建失败（saveLayer 需 RectF 重载）。

### 验证与产物
- 全量单测通过（含新增圆盘/笔尖/宽线预算测试）；Release 签名构建，apksigner verify 通过。
- 真机验收项：1000% 上限钳制、指示器显隐、整页按钮、横竖屏弹框、侧栏折叠、粗/细线钢笔圆珠笔铅笔书写延迟与撕裂——全部通过。
- 产物：`artifacts/LeslieColorOSNote-release.apk`（v2.2.1，versionCode 58）。
- 未竟：`EditorFragment` debug 广播限制与 `app/build.gradle` R8 临时关闭仍未还原（交付正式版前必须处理）。

## 2026-08-31 v2.1.0（versionCode 55）— 三轮手感参数迭代（顺滑/压感/深度缩放质量）

> 背景：在 v2.0.4（低延迟/高倍视窗竞态/自交三角空洞）之后，用户仍要求针对「顺滑度」「按压响应」「深度放大下笔迹空缺/棱角」继续迭代参数，至少三轮。本轮聚焦纯参数级调优，全部改动落在 core-ink 几何内核，均有数值仿真测试锁定。

### 架构状态（承前）
`BrushEngine/LiveOutlineBuilder/SplineRefiner`(core-ink) + `InkView/LiveInkQualityPolicy/RenderScalePolicy`(core-render)。活笔迹增量四边形+圆碟并集；完成笔迹 Catmull-Rom 细分+圆端帽入整页/高倍视窗位图。v2.0.3 已把 X/Y 独立 1€ 滤波合并成旋转不变共享二维速率截止；v2.0.4 已为活笔迹加实时细分预算（慢速 ≤12×、快速 ≤6×）并合成一次 AA 填充。

### 第 1 轮 — 顺滑（StrokeSmoother）
- 现象「顺滑度 100% 反而在转弯显棱角」根因（上一轮已部分修复的残留）：100% 时位置低通截止降到 1.35Hz，近零速度处滤波近乎停滞，笔转后再加速时 β·speed 项重新打开截止 → 输出突然追上，呈方向突跳/拐角感；且低速段跟手滞后偏大。
- 改动：`POS_FCMIN_AT_ONE` 1.35 → **2.2Hz**；`POS_BETA` 0.085 → **0.09**。保留重去抖特性，但 100% 档不再停留过深——低速转弯跟手且消除方向突跳。DCUTOFF 2.5Hz、smoothing 0 档 5.5Hz 不动。
- 回归锁：`maxSmoothing_keepsUpOnSlowPen`（60u/s 慢笔 1s 覆盖 ≥80%）、既有 `highSpeed_lagImproved_v2`（600u/s 滞后 <2.4）、`highSmoothing_isRotationInvariantThroughTurn` 全过。

### 第 2 轮 — 按压（StrokeSmoother 压感滤波 + 测试）
- 现象：按压时笔迹变粗偏慢，深压反馈不够跟手。压感是慢变标量，其 1€ 基础截止偏低导致按压延迟大。
- 改动：`PRESSURE_MINCUTOFF_HZ` 2.5 → **3.2Hz**、`PRESSURE_BETA` 0.04 → **0.05**。刻意按压约 32ms 内开始明显响应，释放仍得到强去抖（速度补偿项对慢变标量保持弱）。
- 回归锁：`pressureStepRespondsWithinOneFrameGroup` 响应带上限放宽至 <0.9（反映更快的按压）、新增 `gradualPress_fattensInTime`（80ms 渐变按压 ≥0.55）。

### 第 3 轮 — 深度放大质量/空缺（SplineRefiner）
- 现象：极端缩放（48×+）下完成笔迹曲线仍可能露出多边形折面。根因：中心线细分上限 128、弦高容差 0.15px，深缩放大段需要的子段超过上限被截断。
- 改动：`MAX_SUBDIVISIONS` 128 → **192**、`MAX_SAGITTA_PX` 0.15 → **0.12px**、`MIN_MAX_TURN_STEP` 0.65° → 0.4°。仅影响完成笔迹（page/视窗按最终 device scale 重细分）；活笔迹仍走 `LiveInkQualityPolicy` ≤12× 预算，不牺牲 v2.0.4 低延迟成果。
- 回归锁：`deepZoomWideCornerRefinesBeyondOldCap`（64× 长边直角细分饱和到新上限且 >128）、既有深度缩放细分测试全过。

### 验证
- `:core-ink:testDebugUnitTest :core-render:testDebugUnitTest` 通过；全模块 `gradle testDebugUnitTest` BUILD SUCCESSFUL（无新增失败）。
- `:app:assembleRelease` BUILD SUCCESSFUL（R8 + 资源裁剪 + 正式签名）。
- 版本 54/2.0.4 → 55/2.1.0；包名 com.leslie.colorosnote 不变。
- 产物：`artifacts/LeslieColorOSNote-v2.1.0-release.apk`（SHA-256 6551B569F0B543873BF8246F11A4E859D3DA40C8DF2A7B8DFCFB21EBB63F68EA）；apksigner verify 通过，证书 CN=Leslie ColorOSNote。

### 未竟/备注
- **安装受阻**：`adb devices` 里 OPPO Pad（OPD2101 / 2d64f765）显示 offline，定向 `adb -s 2d64f765 reconnect` 后从列表消失；另一台一加 Ace 5（PKG110 / 92f73a16）在线，但按设备锁定约定**未对其实施任何操作**。需要用户物理重新插拔/在 Pad 上确认 adb 授权后，回连安装并做三项真机验收（100% 顺滑度慢速转弯、深压渐变变粗、48× 以上快速画圆看是否仍有折面/空缺）。


## 2026-08-28 v2.0.2（versionCode 52）— 深度缩放圆弧与快写断墨三轮迭代

### 架构审计与根因
- 输入链路：`InkView.onTouchEvent` → `StrokeBuilder`（完整 historical MotionEvent）→ `PressureSource`/`VelocityTracker` → `StrokeSmoother`（1€）→ `BrushEngine`。
- 活笔迹：`BrushEngine.addPoint` → `LiveOutlineBuilder` 增量四边形/端帽 → `InkView` Path；完成笔迹：`PageRenderer` → `BrushEngine.computeMesh` → `StrokeMesh.closedOutline` → 离屏整页/高倍视窗位图。
- 圆仍有棱角：v1.4.18 的 centripetal CR 方向正确，但固定 6°/子段、最多 12 段以及固定 24 段端帽只针对普通倍率；几十倍放大后有限多边形再次可见。
- 快写空缺有三层来源：速度感应 100% 可把线宽压到 `0.01` 页单位；活笔迹稳定 body 与 transient tail 分两次 AA fill，共享弦在深度缩放时可能露 1px 接缝；后台 base/window 旧快照可在新笔提交后 swap 上屏，短暂覆盖刚提交的墨迹。工作区还残留 v2.0.2 竞态插桩 `Thread.sleep(600)`，会把最后一项放大为肉眼明显停顿。

### 第 1 轮：几何随最终像素倍率自适应
- `SplineRefiner` 新增 scale-aware overload；普通倍率仍以 6° 为目标，容差随 `1/sqrt(scale)` 收紧，最低 0.65°，单采样段上限 96。`BrushEngine.computeMesh` 增加 renderScale 重载。
- `PageRenderer` 从 Canvas matrix 取得最终 device-pixel scale：整页烘焙用 bake scale，高倍视窗用 window scale，导出/预览保持各自实际 scale。
- `StrokeMesh` 端帽按 0.20px 最大弦高动态选择 24..256 段；`LiveOutlineBuilder` 同步使用 viewport scale，活笔迹与落墨一致。
- 回归锁：64× 曲线细分显著密于 1×；宽笔深度缩放端帽多于固定 24 段；直线仍不细分。

### 第 2 轮：断墨下限、压感响应、活笔迹无缝
- `BrushEngine` 速度宽度因子增加 0.22 下限：即使 UI 速度感应=100%、速度达到/超过 1300u/s，仍保留可见物理笔尖，不再塌成近零宽度；仍有约 4.5× 快慢粗细对比。
- 压感 1€ 参数从 2Hz/beta 0.001 调到 2.5Hz/beta 0.04，32ms 压力阶跃响应测试锁定“按下去能及时变粗”；位置 1€ 参数不动，跟手/去抖平衡保持 v2.0.0 结果。
- `PressureSource` 对 NaN/Infinity 驱动数据做有限值防御，异常样本降为轻压而不是把 NaN 传播进 Path。
- `InkView` 把 stable livePath + transient tailPath 合成一次 AA fill，消除两次绘制共享弦的像素缝。

### 第 3 轮：高倍视窗提交竞态
- 新增纯 JVM `StrokeSnapshotDelta`：识别“只新增”与“有删除/替换”。base/window 后台位图 swap 前若只是新增笔迹，就把缺失 stroke 增量画入新位图再交换；删除/替换仍走完整重渲。
- 清除全部 `DBG` 日志和临时 600ms sleep。`BakedPage`/`WindowLayer` 保持渲染覆盖计数，新增笔不再被旧快照短暂盖掉。
- 新增 3 个 delta 测试：渲染期间 append 可补画、erase 必须重渲、等数量 replace 不能被误判为同步。

### 验证
- 分轮运行 `:core-ink:testDebugUnitTest :core-render:testDebugUnitTest` 均通过；新增/更新测试覆盖深度缩放、速度断墨下限、压力响应、非法压力和快照竞态。
- 全量单测与 debug APK 构建见本轮最终命令结果。当前沙箱把 adb home 固定到不可写的 `C:\.android`，无法完成真机注入；仍建议在 OPPO Pad 用真实笔做“64× 画圆 + 速度感应 100% 快写 + 重渲期间连续写”三项验收。


## 2026-08-27 v2.0.1（versionCode 51）— 撤销复活 bug 根因修复（journal tombstone）+ 残留笔迹擦除

### 任务 1：擦除 v2.0.0 第 3 轮残留的荧光笔测试笔迹
设备回连后执行未竟事项：进 SearchDemo 第 3 页 → 橡皮 (800,225) → `input stylus swipe 600 1380 600 1520 300` → 翻页落盘 → 拉 bin 与 `tmp/sd_v200_before.inknote` 逐 entry md5 核对：page3 bin 回到 66 条用户笔迹、无残留 id `a2e79967…`、其余 entry 逐字节一致，仅 document.json/updatedAt 与 manifest.json/writtenAtMs 时间戳变。擦除完成（产物 `tmp/sd_v201_erased.inknote`）。

### 任务 2：撤销后笔迹复活 —— 根因与修复
**根因**（v2.0.0 交接里"机制未定位"的那条）：崩溃恢复 journal（requirement 10）只有 upsert 语义。`EditorFragment.onStrokeFinishedShared` 每笔完成后追加 `journal.appendStrokeAsync`（fsync 落盘）；撤销/橡皮/剪切只动内存，**journal 里没有删除记录**。若撤销后、防抖全量保存（2s）落盘并 compact 之前进程被杀，`DocumentRepository.openDocument` 的 `journalFor(id).recover(doc)` 会把这条笔迹原样重放回来（保留原 createdAtMs，与观测完全吻合）。重放不置 dirty，所以"重开后 bin 干净、同进程下一轮有编辑触发落盘才复活"的现象也由此解释。

**修复**（最小侵入，四处接线 + 两个新 op）：
- `core-storage/.../journal/JournalStore.java`：新增 `stroke_delete` tombstone op——`appendStrokeDelete(pageId, strokeId)` + `*Async`；`apply()` 重放时按 id 从 page.strokes 移除（新增静态 `removeStroke`）；tombstone 对不存在的笔迹是 no-op（容器里没存过它时安全）。实例锁改为**跨实例静态锁** `LOCK`——`journalFor()` 每次新建实例，实例锁无法互斥并发 append，有行交错风险。
- `core-ink/.../UndoRedoStack.java`：新增 `Listener`（`onApplied`/`onUndone`）——execute/redo 后回调 onApplied、undo 后回调 onUndone；事务成员逐个回调 onApplied，合并项（Composite）在 undo/redo 时回调一次，listener 自行递归。
- `core-ink/.../InkCommands.java`、`feature-editor/.../EditorCommands.java`：补只读访问器（AddStroke/RemoveStroke/Composite/ReplaceStrokes），引擎语义零改动。
- `feature-editor/.../EditorFragment.java`：注册 `journalMirrorListener`，把命令翻译成 journal 记录——AddStroke apply→append / undo→tombstone；RemoveStroke 反向；ReplaceStrokes（橡皮）按 id diff：消失的 id 补 tombstone、新增的碎片补 append（EraserEngine 从不就地改笔迹，切割生成新 id，diff 精确）；Composite 递归。原 `onStrokeFinishedShared` 里的直接 append 删除（listener 已覆盖）。**就地编辑（变换/改色）与页面/对象结构变化仍只走防抖全量保存**（不产生复活，维持既有行为）。

**单测**（+9 例）：`JournalStoreTest` +4（append+tombstone 重放后不存在；容器已存笔迹被 tombstone 删除——正是"保存后撤销再杀进程"场景；append→delete→append 重做后存在且仅一条；未知 id tombstone no-op）；新增 `DocumentRepositoryJournalRecoveryTest` 5 例（进程死亡级集成：画→撤销→杀→重开不复活 / 保存后撤销→杀→不复活 / 未撤销的笔正常恢复 / 撤销后重做恰好恢复一条 / 撤销后保存成功则 compact 兜底）；`UndoRedoStackTest` +3（listener 的 execute/undo/redo 序列、事务成员+Composite 回调、无 listener 安全）。另给 `core-storage/build.gradle` 开 `unitTests.returnDefaultValues = true`（AppLogger 走 android.util.Log 的既有处理方式，同 document-office/feature-import）。全模块 `gradle testDebugUnitTest` BUILD SUCCESSFUL，541 例 0 失败（基线 337 + 本轮 9 + 其间其他轮次新增）。

### 真机验证（OPPO Pad 2d64f765，v51/2.0.1，2026-08-27 19:01-19:20）
- **变体 A（原 bug 场景）**：画钢笔 → 撤销 → 0.5s 内 `am force-stop`（防抖保存未落盘窗口）→ monkey 重开进文档 → 翻页 → 拉 bin：journal 里可见 `stroke` + `stroke_delete` 同 id 两条；bin 与基线 `tmp/sd_v210_baseline.inknote` **8 个 entry 全部逐字节一致**，无复活。
- **变体 B（杀前翻页）**：画 → 撤销 → 翻页 → force-stop → 重开 → 拉 bin：同样全 entry 一致。实测发现 **`goToPage` 并不触发落盘**（v2.0.0 交接里"翻页即触发落盘"的记忆不确；翻页那次观察到的应是防抖保存），本轮 tombstone 兜住的正是这个"保存没落盘"窗口。
- **变体 C（保存路径回归）**：画 → 等防抖保存（实测冷启动后主线程忙，落盘延迟到 5s+，最终落盘+compact 成功，journal 消失，bin 67 条）→ 撤销 → 再等保存 → journal compact、page3 bin 回到 66 条且与基线逐字节一致（仅时间戳 entry 变）。**设备最终无残留、journal 已 compact**。

### 遗留 / 备注
- 防抖保存延迟观察：monkey 冷启动进文档后主线程忙于缩略图/墨迹渲染，2s 防抖回调实测 >5s 才执行（最终落盘正确）。非本轮引入（时序路径未动），如用户感知"退出后立刻杀进程丢最近几笔"再查。
- 理论残留窗口（已分析无害）：append 与 tombstone 走 cached 线程池，极端并发下 tombstone 可能排在 compact 之后重建 journal——重放幂等（tombstone 对容器里没有的 id no-op、append 按 id 去重），不会复活/重复。要彻底有序需串行 journal 执行器，本轮未做。
- 对象（图片/文本框等）编辑本就不写 journal（`appendObjectAsync` 生产环境无调用点），行为未变。
- `artifacts/` 未更新：只出 debug 装机，未做 release/deliver。



需求：在 v1.4.21 六参数（压力灵敏度/平滑度/速度感应/倾斜感应/线宽/不透明度）基础上迭代三轮追求手感质变。UI 结构与持久化字段语义不变。

### 第 1 轮 — 响应曲线形状（core-ink）
假设与改动：
- **压力曲线 SOFT**：指数 0.55 → 0.75（业界 gamma 0.7-0.8 带），输出地板 0.05 → 0.12（防断墨）。轻压区更细腻：p=0.3 输出 0.540→0.477，p=0.05 输出 0.233→0.213；p=0.95 几乎不变（0.974→0.967）。
- **速度→宽度**：映射 smoothstep → smoothstep^1.5，VELOCITY_REF 1500→1300。宽度惩罚（v2w=1 时）：300u/s 0.104→0.050，650u/s 0.401→0.354，1000u/s 0.741→0.804，1300u/s 0.951→1.000（正常书写速度保宽、真甩笔能到满细）。
- **OneEuro 调参**（数值仿真，240Hz、σ=0.5u 抖动 + 1.2Hz 书写正弦 + 2500u/s 斜坡，二次方差分解法分离抖动透过率与跟踪滞后）：POS_BETA 0.05→0.07、DCUTOFF 1→2Hz。仿真：抖动 RMS 1.691→1.459（-13.7%），稳态滞后 1.66→0.96ms（-42%）；600u/s 滞后 2.82→2.08 页单位（-26%），双轴帕累托占优，未劣化 v1.4.10/11 延迟成果。
数据对比（同一份真机注入点流回放 v1.4.21 vs v2.0.0 公式，用户预设 v2w=0）：慢笔 0.929→0.924（-0.5%），轻压快笔 0.604→0.556（-8%，轻压细化正中设计目标），taper 尖端不变。
真机坐实（临时把钢笔线宽调到 5pt 使差异出像素，事后拖回 0.5pt 并核对 prefs）：慢笔实测 21.6px（新公式预测 21.3），快笔实测 13.1px（新公式预测 13.2，旧公式预测 15.2 被排除）。输入管线回归：基线 vs 复测速度 131/436/1715 ≈ 131/1700、压力分布一致、方向角 std ~0.003°（注入直线无抖动，平滑度对比以仿真为准）。
单测：PressureCurveTest +1（SOFT 新曲线+地板锁定）、BrushEngineTest +1（650u/s 惩罚 0.354、1400u/s 满细）、StrokeSmootherTest +1（600u/s 滞后 <2.4 单位，旧值 2.82 会挂）。

### 第 2 轮 — 默认值与笔型个性（core-model + feature-editor）
- 工厂默认：钢笔 velocityToWidth 0.4→0.5（新速度映射下中段几乎无惩罚，深系数只在真甩笔显现）；圆珠笔 smoothing 0.1→0.2；铅笔 tiltToOpacity 0.3→0.45 + smoothing 0.15→0.2；荧光笔 smoothing 0.1→0.15。恒宽/零速度等笔型个性不动。
- **迁移策略**：BrushPresetStore PRESET_VERSION 4→5，接受 v5/v4/v3（全部工具）与 v2（除圆珠笔/铅笔）——用户已有调教原样加载，新默认只到全新安装/已被早期迁移丢弃的条目。验收谓词抽成纯 JVM `acceptsPresetVersion` 并补 `BrushPresetStoreMigrationTest` 3 例。
- 真机验证：byte 级备份 prefs → force-stop 删档 → 四弹窗实测新默认（钢笔 3pt 100/20/50/0、圆珠笔 2.5pt 0/20/0/0、铅笔 2pt 80/20/0/45、荧光笔 14pt 0/15/0/0 全中）→ prefs 字节级写回（md5 f4ef2a54… 双侧一致）→ 重开弹窗确认用户值（0.5pt 100/0/0/0）回来了。
- 注意：用户 v3 铅笔存档无 tilt 字段 → 加载落新工厂默认 0.45（属"默认值本身优化"，符合迁移约定；仅在有真实倾斜事件时生效）。
- 单测：BrushPresetTest +1（新默认锁定+个性防漂移）；BrushParamMappingTest 中铅笔倾斜默认 0.3→0.45 同步。

### 第 3 轮 — 细节打磨与回归
- **AA 复核**：渲染管线 strokePaint/livePaint/bitmapPaint 均带 ANTI_ALIAS/FILTER_BITMAP（core-render，无需改动）；实画 0.5pt 斜线边缘亮度剖面 255→217→79→24→121→255，两侧均有渐变带 = 正确子像素 AA，无硬台阶。
- **活笔迹↔落墨一致性**：LiveOutlineBuilderTest.liveAndCommittedOutlinesMatchAtKnots（v1.4.18 遗留）等 13 例在位，SplineRefiner knot 法线不变式保证零跳变。
- **UI 全行程复核**：钢笔弹窗拉到最宽标签（60 pt + 四行全 100 %）uiautomator 逐行核对——全部 h=37、无半截行、无裁切；拖回用户值（0.5pt 100/0/0/0，dump 确认）。
- **全场景回归**（SearchDemo 第 3 页）：橡皮笔画擦除 ✓（目标线像素清零）、撤销/重做/撤销循环 ✓、连续翻页 3→2→1→3 笔迹稳定 ✓。套索无法用注入事件画闭合路径（input swipe 只能直线），以 LassoEngineTest 单测覆盖为准。双指缩放无法经 adb 注入，未测（沿用既有实现，本轮未动缩放链路）。
- **异常发现（待查）**：第 2 轮 17:49:32 画的荧光笔测试笔迹（id a2e79967-1ea2-4edc-bd9e-46baf39e9d3c，14pt 黄色，页面坐标 x81..333/y471..512）当时已撤销（17:50 拉的 bin 干净），却在第 3 轮 17:55 的最终 bin 里以原 createdAtMs 复活。UndoRedoStack 代码上 push 即清 redo 栈、进程死亡栈即失，机制未定位；嫌疑路径：force-stop 时序 + EditorFragment 级 undo 栈存活 + 某次 redo tap 复活旧命令。教训：撤销后应立即拉 bin 核对，不能只靠最终对比。

### ⚠️ 未竟事项（设备断开阻塞）
第 3 轮收尾时 OPPO Pad（2d64f765）被拔线，adb 只剩一台无本应用的 PKG110（92f73a16），轮询 25 分钟未回连。**上述残留笔迹仍在 Pad 的 SearchDemo 第 3 页 bin 里（67 条 = 66 用户 + 1 残留），尚未擦除**。设备回连后执行：进 SearchDemo 第 3 页 → 橡皮（800,225）→ `input stylus swipe 600 1380 600 1520 300` 擦掉 → 翻页触发落盘 → 拉 bin 与 `D:\Leslie\tmp\sd_v200_before.inknote` 逐 entry md5 核对（应回到 66 条、前缀一致、仅 document/manifest 时间戳变）；另核对 prefs 语义（fd5ef… 基线 vs 当前，预期仅钢笔 min/max float 尘埃）。

### 汇总
- 版本 49/1.4.21 → 50/2.0.0（第一轮即升，三轮共一个版本）。`gradle :app:assembleDebug testDebugUnitTest` BUILD SUCCESSFUL；全模块 337 例（core-ink 85 / core-model 27 / core-render 50 / core-storage 31 / feature-editor 95 / feature-export 13 / feature-import 36）0 失败。
- bin 核对：第 1/2 轮各自清理后与测试前快照逐 entry md5 一致（仅 updatedAt/writtenAtMs 时间戳变）。第 3 轮终核发现上述残留（设备随后断开，见未竟事项）。
- 用户内容零触碰；用户预设（含其 17:08 后自己把钢笔平滑度/速度感应调到 0 的新调教）全部保留。
- `artifacts/` 未更新：只出 debug，未做 release/deliver。


## 2026-08-27 v1.4.21（versionCode 49）— 四笔型弹窗新增手写参数滑杆（压力灵敏度/平滑度/速度感应/倾斜感应）

### 需求
把 DeepSeek 手写参数记录做成弹窗可调项。映射结论：**落地**——压感级别→压力灵敏度%（PENCIL→pressureToOpacity 默认 0.8，其他笔型→pressureToWidth，v=0 关 pressureEnabled）、滤波去抖→平滑度%（→smoothing，OneEuro fcmin 4.7→0.8Hz）、速度变细→速度感应%（→velocityToWidth）、倾斜侧锋→倾斜感应%（PENCIL→tiltToOpacity 默认 0.3，其他→tiltToWidth，tiltEnabled=v>0）；**已具备**——笔尖方向（书法笔 nibAngle 引擎已有，工具栏无书法笔不暴露）、曲线拟合（centripetal CR，v1.4.18）、预测/低延迟（OneEuro 自适应低通，v1.4.10/11）、240Hz 采样+历史事件批量处理、Path 抗锯齿渲染；**不适用**——采样率/高刷/GPU/系统两级渲染（硬件系统层）；**放弃**——边缘柔化需动渲染管线（用户授权不做）。原"压力曲线"RadioGroup 行被压力灵敏度数值滑杆取代（`pressureCurve` 字段保留，笔刷样式卡仍会写）。

### 改动
- 新增 `PercentSliderMapper`（feature-editor，纯 JVM）：0..100 ↔ 0..1f 双向钳制 + 文案 "37 %"。
- 新增 `BrushParamMapping`（feature-editor，纯 JVM）：上述四参数 ↔ preset 字段映射集中在此；`applyFixedWidthSpread` 让 BALLPOINT/HIGHLIGHTER 在 pressureToWidth>0 时 min/max=base×(1∓0.4v)、v=0 恢复恒宽（FOUNTAIN/CALLIGRAPHY/PENCIL no-op，保住各自既有宽度语义）。
- `popup_brush_props.xml` 全量重写：根改 ScrollView（300dp、bg_popup、无滚动条）防内容超高裁切；删"压力曲线"label+`group_curve`；新增四行滑杆（id：seek_pressure/seek_smoothing/seek_velocity/seek_tilt + 对应 text_*_value），所有水平行 `baselineAligned="false"`（沿用 v1.4.20 教训）。
- `BrushPropertiesPopup`：`bindPercentRow` 通用绑定；`refreshNumericRows` 供笔刷样式卡点击后全量回同步；线宽 handler 末尾调 `applyFixedWidthSpread`；弹窗高度 cap = min(内容高, anchor 下方可用空间)，超出走 ScrollView 滚动。
- `BrushPresetStore`：PRESET_VERSION 3→4，迁移接受 3（全部工具）与 2（除 BALLPOINT/PENCIL）；新增 tiltEnabled/tiltToWidth/tiltToOpacity 存取，旧档缺失落工厂默认（行为不变）。
- strings.xml：删 brush_pressure_curve/brush_curve_*，新增 brush_pressure_sensitivity/brush_smoothing/brush_velocity/brush_tilt。
- **引擎零改动**（pressureToWidth/velocityToWidth/tiltToWidth/tiltToOpacity/pressureToOpacity/smoothing 早已被 BrushEngine.java:170-218、StrokeSmoother.java:75-83 消费）。
- 单测：新增 `PercentSliderMapperTest` 5 例 + `BrushParamMappingTest` 9 例全绿；全模块 `testDebugUnitTest` BUILD SUCCESSFUL。版本 48/1.4.20 → 49/1.4.21。

### 真机验证（OPPO Pad 2d64f765，2026-08-27 07:33-07:48）
- 装机 49/1.4.21（dumpsys 确认）。四个弹窗逐个 screencap + uiautomator 核对：钢笔（`tmp/v21_pen.png/xml`，3 样式卡+颜色+hex+线宽 0.5pt+不透明度+压力 100%+平滑 20%+速度 40%+倾斜 0%）、圆珠笔（`v21_bp.*`，0.5pt、0/10/0/0）、铅笔（`v21_pc.*`，0.5pt、80/15/0/30——与 prefs 一致，v3 旧档缺失倾斜字段正确落工厂默认 0.3）、荧光笔（`v21_hl.*`，33pt、不透明度 40%、0/10/0/0 + "拉直"开关行 h=61）。**全部数值行 label/value h=37、滑杆 h=36、行距 110px，无半截行无底部裁切**（内容最高 ~1580px < anchor 下可用空间，ScrollView 未触发滚动）。
- 功能抽测（SearchDemo 第 3 页 y>1300 空白区，注入 stylus 事件带模拟压感 toast）：
  - **压力灵敏度**（钢笔）：0% 档画线实测最大厚度 1px 恒宽；100%（实测 98%）档同轨迹实测 2px 且带压感楔形变化——压力映射生效（截图 `v21_press.png`）。
  - **平滑度**：1% vs 100% 各画一条同轨迹直线，右端墨点 x=780 vs 784、目视无差异——**符合预期**：StrokeSmoother 只过滤输入抖动/滞后，注入事件是匀速完美直线，无抖动可滤。滑杆读写持久化本身经 dump 确认（1%/100% 正确落 preset）。
  - **倾斜感应**（铅笔默认 30% 画一笔，`v21_tilt.png`）：注入事件无 tilt 数据 → tiltNorm=0 → 侧锋加深不可见，**符合预期**（引擎 tiltEnabled 门控路径有单测覆盖，UI 可调即满足需求）。
- **恢复与清理**：钢笔弹窗拖回 100/20/40/0（dump 确认），读 prefs 逐项对基线——BALLPOINT/PENCIL/HIGHLIGHTER presetVersion 仍 3 未被重写、字段与基线完全一致；FOUNTAIN_PEN 因拖动被存为 v4：styleId="m"/baseWidth=0.5/min=0.20000001788139343/max=0.949999988079071/pressureToWidth=1/velocityToWidth=0.4/smoothing=0.2/taper 0.15/0.5/pressureCurve=SOFT/tilt 三字段=0，全部精确还原。撤销 ×5 清掉全部测试笔迹（中途 3 条画错的已即时撤销）。
- **bin 核对**：`sd_v21_before.inknote`（07:30）vs `sd_v21_after.inknote`（07:48），8 个 zip entry 逐一 md5——**笔迹数据 entry 逐字节一致、无新增 stroke**（期间用户未写新内容）；仅 document.json updatedAt / manifest.json writtenAtMs 时间戳变。撤销持久化正确。

### 遗留 / 注意
- **EditorSeekBar 触控热区备忘**（下次真机脚本用）：屏幕像素系（1600×2560 screencap = input 坐标系）下滑杆有效映射 x≈367+595×v；**点按超出 [367,962] 被忽略**（点 350/965 无效、点 700→56%），点按设值用 x=367+595×v；**拖动可钳位到端点**（从拇指上拖过端点→0%/100%），但从端点外侧空白起手向右拖会被 ScrollView/父布局吃掉不生效——设 0% 用左拖、设 100% 用从拇指短拖过右端。
- FOUNTAIN_PEN 存档出现 `stabilization=0.1` 字段（基线记录里没抄这项，无法比对；疑似钢笔工厂默认原有，本次弹窗整体回写带出）。铅笔 v3 存档无 tilt 字段、运行时落工厂默认 tiltToOpacity=0.3——与旧版本默认行为一致（设计阶段已核对工厂预设）。
- `artifacts/` 未更新：只出 debug，未做 release/deliver。


## 2026-08-27 v1.4.20（versionCode 48）— 修复：笔型弹窗"线宽"标签行被拦腰裁断

### 根因
v1.4.19 把「线宽」标签改成"标签+pt 数值"的水平行（`popup_brush_props.xml` 原 :78-99），但水平 `LinearLayout` 的 `baselineAligned` 默认 true：该行两个 `wrap_content` 的 TextView 被基线对齐布局压缩成 21px 高、底部锚定（正常标签 37px），文字上半截被裁。uiautomator 实锤（修复前 dump）：行 [362,922][965,991] h=69，内两 TextView 均 [*,970][*,991] h=21；而同列其他标签行均 h=37。十六进制输入行同为水平行但子 view 是定高 EditText/Button，不受影响；`popup_eraser`/`popup_lasso_mode` 无此结构，不受影响。总内容高度并未超出弹窗，故不需要 ScrollView/加高——选方案②的精确版：修掉基线对齐错位。

### 改动
- `popup_brush_props.xml`：线宽标签行加 `android:baselineAligned="false"`；14dp 上边距从两个子 TextView 收拢到行本身（子 marginTop=0），行高/弹窗总高不变（1088px）。附防回归注释。
- 宽度 300dp、其余间距、全部功能逻辑（WidthSliderMapper/持久化/笔刷行为）未动。
- 版本 47/1.4.19 → 48/1.4.20。

### 真机验证（OPPO Pad 2d64f765）
- 修复前复现：钢笔弹窗"线宽"/"0.5 pt" 上半截被裁（与用户证据 `tmp/pop20.png` 一致）。
- 装机 48 后 uiautomator 复核：线宽行两 TextView 均 h=37（[367,954][425,991] / [425,954][960,991]），与其他标签行一致。
- 四个带线宽弹窗逐个人眼裁片确认完整：钢笔（`tmp/v20_pen.png`，含笔刷样式 3 卡）、圆珠笔（`v20_bp.png`）、铅笔（`v20_pencil.png`）、荧光笔（`v20_hl.png`，含"拉直"开关）——线宽标签+pt 数值+滑杆、不透明度、压力曲线全部完整无裁切。用户预设保持（圆珠笔 1pt、铅笔 2pt、荧光笔 14pt；钢笔 0.5pt 为用户自己后来调的，未动）。
- 纯 UI 验证，未落任何测试笔迹；用户内容零触碰。

### 遗留 / 注意
- 教训：v1.4.19 真机验证时截图裁片从 y=950 起看，裁断线上半在视野外，漏检了此 bug——验证 UI 行要看完整行高（或用 uiautomator bounds 对照预期高度）。
- `artifacts/` 未更新：只出 debug，未做 release/deliver。


## 2026-08-27 v1.4.19（versionCode 47）— 三笔型统一笔尖粗细调节 + 线宽下限压到 0.5pt

### 需求与现状核查
用户两条：(1) 钢笔/圆珠笔/铅笔弹窗都要有一致的"笔尖粗细"调节；(2) 线宽拉到最小还是很粗，要求下限显著压低。核查结论：三个笔型**本来共用** `BrushPropertiesPopup`（feature-editor，含线宽滑杆），需求 (1) 的结构已满足，真问题在范围与精度——旧滑杆（`BrushPropertiesPopup`，原 :76-94）映射 `progress+1` → **1..40 整数**档。单位换算：`Page.DEFAULT_WIDTH=595`（core-model/Page.java:16，A4 @ 72dpi）→ **1 页逻辑单位 = 1pt**；OPPO Pad fit 缩放下 595pt ≈ 1374px ≈ 2.3px/pt，旧下限 1pt ≈ 2.3px 物理宽——这正是"拉到最小还很粗"。

### 改动
- 新增 `WidthSliderMapper`（feature-editor，纯 JVM）：`MIN_WIDTH=0.5f` / `MAX_WIDTH=60f` / `STEP=0.5f` pt，`MAX_PROGRESS=119`；`progressToWidth`/`widthToProgress` 双向钳制映射 + `label(width)` 数值文案（"0.5 pt"/"3 pt"）。新下限 0.5pt ≈ 1.15px（1600×2560 fit），上限 40→60pt。
- `popup_brush_props.xml`：`seek_width` max 39→**119**；「线宽」标签行改为水平行，右侧新增 `text_width_value` 实时显示当前 pt 值（沿用 EditorPopupLabel 灰色小字风格）。
- `BrushPropertiesPopup`（feature-editor）：滑杆初值/拖动/样式变体回同步三处全部改走 `WidthSliderMapper`；拖动静 listener 里把 BALLPOINT/PENCIL 也并入 HIGHLIGHTER 的 `min=max=base` 分支（这两款本就恒宽，此举防旧存档 min≠max 漂移重新引入宽度变化——锁住 v1.4.16 恒宽语义）。弹窗三个笔型共用，无需按笔型分别加 UI。
- 持久化：`BrushPresetStore` **未动**，`PRESET_VERSION` 保持 3——存储语义（baseWidth 单位=pt）没变，用户已有调教原样加载（旧整数档位恰落在新 0.5pt 网格上，有单测锁定）。
- 单测：新增 `WidthSliderMapperTest` 8 例（边界/钳制/全档 round-trip/旧整数档精确还原/文案）；`BrushEngineTest` 新增 `tinyPresetWidthIsNotClampedThicker`（0.5pt 恒宽笔渲染宽度逐点=0.5 且包围盒 ≤0.5pt；钢笔 0.5pt 档轻压不低于 minWidth——引擎无隐性钳粗）。core-ink 13 例、feature-editor 新 8 例全绿，全模块 `testDebugUnitTest` BUILD SUCCESSFUL。
- 版本 46/1.4.18 → 47/1.4.19。
- 未动：钢笔笔锋/taper、圆珠笔恒宽、铅笔压感→深浅、SplineRefiner 平滑——本次只动弹窗滑杆映射与 UI。

### 真机验证（OPPO Pad 2d64f765，2026-08-27 02:39-02:52）
- 装机 versionCode 47 / 1.4.19（dumpsys 确认），进 SearchDemo 第 3 页下半空白区（用户 02:18-02:19 的"金12"等内容在上半页 y<1250，未触碰）。
- **钢笔**：弹窗线宽滑杆拖到最左显示 "0.5 pt"（截图 `D:\Leslie\tmp\v19_pen_min_set.png`），画一笔实测 **2px**；拖到最右显示 "60 pt"（`v19_pen_max_set.png`），画一笔实测**最宽 153px**（压感楔形，注入事件模拟压感；60pt×2.31px/pt≈138px 量级，符合）。截图 `v19_pen_min.png` / `v19_pen_max.png`。
- **圆珠笔**：弹窗确认有线宽滑杆 + pt 数值（需求 1 核验点，`v19_pop_bp.png`，当前值 "1 pt"=用户预设）；"0.5 pt" 档画一笔实测 **1px**，"60 pt" 档实测 **136px** 恒宽黑带（`v19_bp.png`）。
- **铅笔**：弹窗同样有线宽滑杆 + pt 数值（`v19_pop_pencil.png`，工厂默认 "2 pt"）；"0.5 pt" 档实测 **1px** 灰线，"60 pt" 档实测 **138px** 灰色带（opacity 0.85，`v19_pencil.png`）。
- 像素宽测量：`py -3 D:\Leslie\tmp\v19measure.py D:\Leslie\tmp\v19_pencil.png 600`（x=600 列扫暗色行程）。
- **清理与恢复**：撤销键（155,225）×6 撤掉全部测试笔迹；三笔型滑杆拖回用户预设并截图确认文案——圆珠笔 "1 pt"、钢笔 "4.5 pt"、铅笔 "2 pt"；读 prefs 确认 `preset_BALLPOINT` baseWidth=1、`preset_FOUNTAIN_PEN` styleId="b"/baseWidth=4.5、`preset_PENCIL` baseWidth=2。
- **bin 核对**（`sd_v19_before.inknote`(00:40 快照) vs `sd_v19_after.inknote`，逐 entry md5 + StrokeBinCodec 格式解析）：第 1/2 页 bin 逐字节一致；第 3 页 bin 原 30 条记录前缀逐字节一致，新增 36 条全部为用户笔迹（createdAtMs 02:18-02:19、圆珠笔 1.0pt，验证开始前已存在），**我的 6 条测试笔迹（0.5/60pt）不在落盘文件里——撤销已正确持久化**；document.json/manifest.json 仅 updatedAt/writtenAtMs 时间戳变。重开文档第 3 页确认无残留。

### 遗留 / 注意
- `artifacts/` 未更新：只出 debug，未做 release/deliver。
- 小误差备忘：恢复钢笔 4.5pt 后 prefs 里 min/max 为 2.2500019/7.2000022（float round-trip 尘埃，≈4e-6px，视觉无差）；铅笔预设本次被存成 presetVersion=3（旧残留无版本字段、每次加载本就被丢弃回工厂默认 2/2/2，现值与默认一致，行为无变化）。


## 2026-08-27 v1.4.18（versionCode 46）— 拐弯折角/画圆变多边形：Catmull-Rom 中心线样条细分（centripetal）

### 根因
用户反馈所有笔拐弯处有明显折角、画圆是多边形。根因：中心线就是采样点折线，两条渲染路径都按**直 chord** 逐段拼四边形/三角带，拐点处无 join 圆角——`BrushEngine.buildMesh`（core-ink/BrushEngine.java）weld 后直接按结点中央差分法线偏移成带；`LiveOutlineBuilder`（core-ink）活笔迹提交段同样每段一个直 quad。真实数据佐证：SearchDemo 第 1 页大圈（bin 297b1add stroke 21，n=574、bbox x221..519/y82..429、FOUNTAIN baseW=4）weld 后仅 69 点、平均点距 17.6 页单位——点稀时折线棱角全部直接呈现在轮廓上。OneEuro 平滑器（POS_BETA=0.05，v1.4.10/11 延迟调优成果）未动，不是成因。

### 选型：centripetal CR，不用 uniform
最初实现为 uniform Catmull-Rom，用真实数据回归时发现抖动簇上 uniform 参数化会产生 162° 微环（打结）；centripetal 参数化同一数据最坏转角仅 24.6°，且文献（Barry–Goldman）保证段内无环。故定为 centripetal，不要再改回 uniform。

### 改动
- 新增 `SplineRefiner`（core-ink/SplineRefiner.java）：`MAX_SUBDIVISIONS=12`、6°/子段。`subdivisions(p0..p3)` 按两端切线（p2-p0、p3-p1）夹角定细分数（直线/退化段=1，直线零开销）；`eval(...)` 为 centripetal CR（Barry–Goldman 嵌套 lerp，dt=max(√dist,1e-4)，切线用 TANGENT_H=1e-3 数值微分），带无分配重载 `eval(..., out, scratch)`；`refine(center,widths,opacities,n)` 返回 Refined{center,widths,opacities,normals,count}——结点精确穿过、结点法线=结点中央差分（与旧行为逐 float 一致）、内部点法线=样条切线法线（退化时复用前一条）。
- `BrushEngine.buildMesh`：weld 后调 `SplineRefiner.refine`，偏移/包围盒改用 refined 数组。
- `LiveOutlineBuilder`：新增字段 zx/zy/hasZ（段首前一结点，首段用自身补点）+ crOut/crScratch；提交段按 subdivisions 细分为多 quad，两端结点法线用 nx0/nx1（与邻段精确共弦），内部用切线法线；reset 清 hasZ。v1.4.17 的 headCap/tailCap 未动。
- 两条路径（活笔迹提交 + PageRenderer/computeMesh 烘焙）共用同一 SplineRefiner，结点处轮廓弦 1e-3 内一致（有单测锁）。
- 单测：新增 `SplineRefinerTest` 8 例；`BrushEngineTest` 加 `curvedStrokeIsSmoothNotPolygonal`（12 点 1/4 圆弧，细分后最大中心线转角 <9°）+ `straightStrokeIsNotSubdivided`（40 点直线 refine 后仍 40 点）；`LiveOutlineBuilderTest` 加 `curvedSegmentCommitsSubdividedQuads` + `liveAndCommittedOutlinesMatchAtKnots`；既有 `tailStartChordMatchesLastCommittedQuad` 改读最后一个 quad（细分后读首个是错的）。core-ink 74 例全绿，全模块 `testDebugUnitTest` BUILD SUCCESSFUL。
- 版本 45/1.4.17 → 46/1.4.18。

### 真机验证（OPPO Pad，序列号本轮在 2d64f765/92f73a16 间抖动过，最终 2d64f765）
- 光栅对比（纯 Python 离线渲染真实 stroke 21）：`D:\Leslie\tmp\circle_old.png`（无 refine，明显多边形棱角）vs `D:\Leslie\tmp\circle_new.png`（centripetal refine，圆周平滑）。
- 装机 v1.4.18 后 SearchDemo 第 1 页大圈全分辨率裁片（`v18c_doc.png` crop 614,486 起 700x800）圆周平滑无折角；与 uniform 版截图 `v18_editor.png` 同区域（仍有棱角）对比成立。
- 三笔回归（第 3 页空白区各一笔直线，`v18c_reg.png`）：圆珠笔/铅笔恒宽、两端标准半圆（端点裁片确认）；钢笔笔锋楔形保留（toast「当前为模拟压感」，注入事件无真压感）。直线未被异常细分。
- 清理见下"事故"段；最终 SearchDemo 三个 strokes bin 与会话前 00:03 快照**逐字节一致**（仅 document.json/manifest.json 的 updatedAt 时间戳变），用户内容零改动。

### 遗留 / 注意 / 教训
- **测试笔迹事故**：文档加载 spinner 期间注入的 8 条 `input stylus swipe` 碎成 17 笔进了第 3 页 bin；撤销键连点后**放大视口下截图看似空白**（视口盲区，实际没撤干净/未覆盖全部），重开文档 17 笔仍在。最终用橡皮 stroke 模式（工具栏 803,224；注意 695,224 是荧光笔，会画出黄色高亮带，别点错）逐片擦除，并拉 bin 逐字节核对确认恢复。**教训：测试笔迹清理必须以拉 bin 核对笔数/字节为准，截图在缩放视口下不可信。**
- MAX_SUBDIVISIONS=12 上限下，数据本身带 >72° 的真锐角处细分后每步仍 ~24°（如 117° 拐角），属可接受的忠实还原，非平滑不足。
- uniform vs centripetal 的取舍已在上文记录：若未来改参数化，先用真实抖动数据回归微环。
- `input stylus swipe` 画不了单笔弧线；首 ~160px 不落墨旧现象仍在。
- `artifacts/` 未更新：本次只出 debug，未做 release 构建/deliver。


## 2026-08-26 v1.4.17（versionCode 45）— 圆珠笔/铅笔端点真圆头：端盖加密 + 活笔迹补圆 cap

### 根因（两层）
v1.4.16 把圆珠笔/铅笔改成恒宽无 taper 后，用户实测端点仍"尖、不圆滑"。逐层核查宽度模型（`BrushEngine.computeWidth`，core-ink/BrushEngine.java:170-203，压感/速度/倾斜/taper 对这两款笔全为 0，Python 逐点移植 `computeMesh`+`closedOutline` 复核：恒宽 2.5、两端各外凸 r=1.25 半圆，几何无误）后定位到两个真实缺陷：
1. **提交网格的端盖是六边形**（主因）：`StrokeMesh.CAP_SEGMENTS = 6`（core-ink/StrokeMesh.java:12）——半圆只切 6 段，且弧的中点（笔尖正前方）恰是多边形**顶点**（内角 150°）。fit 缩放下半径仅 ~3px 看不出，一旦放大或用户在笔属性弹窗调宽，端点就是肉眼可见的六边形尖顶——正是"不圆滑"。
2. **活笔迹覆盖层根本没有端盖**（次要）：InkView 活笔迹只画 `LiveOutlineBuilder` 的四边形条带（InkView.java addLivePoint/rebuildTailPath），起笔平头、笔尖平头，与提交后的胶囊轮廓不一致。

### 改动
- `StrokeMesh`（core-ink/StrokeMesh.java:12）：`CAP_SEGMENTS` 6→**24**——弦高降至半径的 ~0.2%，与真半圆无肉眼差别；每笔仅多 36 个轮廓顶点，开销可忽略。
- `LiveOutlineBuilder`（core-ink）：新增 `headCap(float[])`/`tailCap(float[])`——分别在首点（第二采样落定时前向法线即冻结）与最新点输出与 `StrokeMesh.closedOutline()` 端盖同几何的半圆封闭子路径（左端点→外凸弧→右端点，CAP_SEGMENTS+1 个点）；`reset()` 清头状态。
- `InkView`（core-render）：`addLivePoint` 在头法线定型后向 `livePath` 追加一次起笔半圆（`liveHeadCapDrawn` 标记，每次 `onStrokeStarted` 复位）；`rebuildTailPath` 每批重建尾部四边形时一并重建随笔尖的收笔半圆。新增 `appendPolygon` 助手。至此活笔迹（InkView→BrushEngine）与提交/烘焙/视窗重栅格化（PageRenderer→computeMesh）两条路径端点都是真半圆。
- 单测：`BrushEngineTest` 新增 `ballpoint_capsAreFullSemicircles`（两端盖每个弧点到端点中心距离=半径、轮廓沿笔向两端各外凸整整 r）与 `capsAreSmoothlyTessellated`（CAP_SEGMENTS≥16 锁死六边形回归）；`LiveOutlineBuilderTest` 新增 `headCapIsSemicircleBulgingBackward`（头弧后凸至 -r、弦端点=头轮廓点左右点）、`tailCapIsSemicircleBulgingForward`（尾弧前凸至 +r、弦端点与 tailQuad 末端弦完全一致）、`capsResetWithStroke`。core-ink 全部 10+11 例绿，全模块 `testDebugUnitTest` BUILD SUCCESSFUL。
- 钢笔不动：taper 笔锋逻辑（BrushEngine.java:319-337）与钢笔参数未碰。
- 版本 44/1.4.16 → 45/1.4.17。

### 真机验证（OPPO Pad 2d64f765）
- 已于 2026-08-26 23:30–23:40 完成：圆珠笔/铅笔端点截图确认为标准半圆，钢笔笔锋保留无回归；测试笔迹已清理。
- 另：v1.4.18 会话（次日 00:15 起）三笔回归再次确认圆珠笔/铅笔端点半圆、钢笔楔形笔锋保留（`D:\Leslie\tmp\v18c_reg.png` 端点裁片）。

### 遗留 / 注意
- 真机验证已补（见上）。
- `artifacts/` 未更新：本次只出 debug，未做 release 构建/deliver。


## 2026-08-26 v1.4.16（versionCode 44）— 圆珠笔/铅笔去笔锋：恒宽 + 铅笔压感→深浅

### 根因
所有笔型共用同一套压感宽度模型（`BrushEngine.computeWidth`，core-ink/BrushEngine.java:170-203：压力→min..max 宽度插值 + 速度变细 + 倾斜加宽 + 起收笔 taper），笔型差异只在 `BrushPreset` 工厂参数。旧参数里圆珠笔（BrushPreset.java 旧 ballpoint()：pressureToWidth=0.35、velocityToWidth=0.12、taperEnd=0.08）和铅笔（旧 pencilHb()：min 1/max 3.5 + pressureToWidth=0.4 + velocityToWidth=0.2 + tiltToWidth=0.8）都带宽度调制和收笔尖细，违背物理直觉。竞品标准（已核实）：GoodNotes 圆珠笔=不压感、固定线宽；Notability 铅笔=压感只影响不透明度/深浅，不影响线宽。

### 改动（纯参数级，引擎零改动——钢笔/毛笔等待压感笔刷手感不受影响）
- `BrushPreset.ballpoint()`（core-model/BrushPreset.java:161）：min=max=base=2.5 恒宽；pressureEnabled=false；pressureToWidth/pressureToOpacity/velocityToWidth 全 0；taperStart/taperEnd 全 0（圆头圆尾，圆角 cap 由 StrokeMesh 自带）。
- `BrushPreset.pencilHb()`（BrushPreset.java:187）：min=max=base=2 恒宽；pressureToWidth/velocityToWidth/tiltToWidth 全 0、无 taper；压感保留映射到不透明度（pressureToOpacity=0.8，轻=浅灰、重=深，Notability 模型）；tiltToOpacity=0.3 侧锋加深保留；纸纹 texture 保留。
- `BrushPresetStore`（feature-editor）：presetVersion 2→3；v2 存量预设仅对圆珠笔/铅笔丢弃（回退到新工厂默认），钢笔/荧光笔等默认未变的工具仍接受 v2 预设，存量用户调教不丢。
- 渲染路径无需改：活笔迹（InkView→BrushEngine.addPoint）与提交/烘焙/视窗重栅格化（PageRenderer.drawStrokes→BrushEngine.computeMesh，PageRenderer.java:451）共用同一 preset 参数，两处自动一致。
- 单测：`BrushPresetTest` 改写 `ballpoint_fixedWidthNoTaper`、`pencilHb_fixedWidthPressureToOpacity`（替换过期的 0.85x..1.2x 断言）；`BrushEngineTest` 新增 `ballpoint_constantWidthNoTaperRegardlessOfPressure`（压感 0.05/0.5/1.0 三档全程宽=baseWidth，含首末点）、`pencil_constantWidthPressureOnlyDarkens`（恒宽 + 重压 averageOpacity > 轻压）。全模块 `testDebugUnitTest` 绿。
- 版本 43/1.4.15 → 44/1.4.16。

### 真机验证（OPPO Pad 2d64f765，截图 `D:\Leslie\tmp\v16_*.png`，逐像素宽度剖面工具 `tmp/strokeprof.py`）
- **圆珠笔**：慢笔（800ms）+ 快笔（150ms）各一。慢笔全程基线 5~6px（毛刺峰值均为与正文文字交叠所致），起笔首列即 4~5px、收笔末列仍 5~7px，无渐变尖细；快笔基线 4~6px，与慢笔无差异（速度变细已除）。
- **铅笔**：慢/快两笔均恒定 3~4px（2 页单位@fit），首末列即全宽（圆角 cap 半径内），无 taper、无速度宽度响应；线条呈铅笔浅灰质感（opacity 0.85 + 纸纹）。注：注入事件为模拟压感（设备 toast「当前为模拟压感」），压感→深浅差异未能在真机区分，由单测锁定。
- **钢笔回归**：同款慢笔笔身 8px、收笔尾部 15px 内 8→3px 渐变尖细——笔锋保持完好。
- 清理：5 笔测试笔迹全部当会话撤销（撤销键 ×5），BACK 回主页后重开笔记与测试前截图逐区域比对无残留（正文重叠行是文档固有内容，测试前截图已存在）；未用橡皮、未动用户内容。

### 遗留 / 注意
- 圆珠笔/铅笔的笔属性弹窗滑杆（若用户手动调 pressureToWidth/taper）仍可重新引入宽度变化——本次只改工厂默认与存量 v2 预设迁移，引擎层未按笔型强制锁定。
- `input stylus swipe` 首 ~160px 不落的旧现象仍在（三笔均从 x≈464 起绘），注入管线问题，非渲染 bug。
- `artifacts/` 未更新：本次只出 debug 真机验证，未做 release 构建/deliver。


## 2026-08-26 v1.4.15（versionCode 43）— 高倍放大仍模糊：视窗阈值 ×1.0 + 底图拉伸光晕消除

### 根因（两层）
用户反馈 v1.4.14 黑屏已修但"再放大一点就写不清"。核查发现两个独立成因：
1. **模糊过渡带**：视窗启用阈值沿用 `TRIGGER_FACTOR=1.25`（`shouldUseWindow` 入口），整页烘培封顶 ~4.58x 后到 5.73x 之间**仍显示拉伸的低分辨率底图**（最多拉 25%），视窗层不启用。这正是"中度放大发虚"。
2. **底图光晕**（更隐蔽）：视窗模式下底图仍整页画在视窗位图**下面**。底图按 4.58x 光栅化，笔画边缘量化到 0.22 页单位的像素网格，与视窗位图（设备分辨率）的真实边缘最多错半格；双线性拉伸后这层错位从锐利视窗拷贝边缘"漏"出来，形成宽度 ≈zoom/4.58 px 的灰色光晕——6x 时 ~3px 不可见，**20x（≈46x）~10px、32x（≈74x）~16px**，高倍下肉眼可见的"毛边/虚影"。用纯 Python 逐像素剖面（`tmp/edgeprobe.py`，无 PIL 环境）实测：修复前 20x 垂直笔画边缘过渡带 5~8px、32x 8~11px，修复后全部 ≤2px。

注：任务核查项 (a) 结论——视窗位图**本来就是从矢量 stroke 快照重栅格化**的（`InkView.startWindowRender`，InkView.java:681-685：`Canvas.scale(spec.scale)` + `renderer.drawStrokes(c, snapshot)`，spec.scale=viewport 设备分辨率），并非从封顶位图放大合成；背景模板每帧经 page→screen 矩阵矢量重绘（onDraw 步骤 1），活笔迹全程矢量直绘（步骤 3）。模糊根源是上面的阈值与光晕，不是位图放大合成。

### 改动
- `RenderScalePolicy`（core-render，纯 JVM）：
  - 新增 `WINDOW_ENTER_FACTOR = 1.0f`：缩放一过底图封顶立即启用视窗，消除 4.58x~5.73x 模糊带。
  - `WINDOW_EXIT_FACTOR` 1.1f→**0.98f**：回差方向修正——入口 ×1.0 配旧退出 ×1.1 会进入即退出抖动（进入-丢弃-再进入循环）；新配对 (0.98, 1.0] 为滞回保持带。
  - `needsRebake` 的 `TRIGGER_FACTOR=1.25` **不动**（封顶以下防抖重烘自愈，降它只会增加捏合过程中的整页重烘次数）。
- `InkView`（core-render）：
  - onDraw 合成改为：视窗激活且 `win.strokeCount == page.strokes.size()` 时，用 `canvas.clipRect(winRect, Region.Op.DIFFERENCE)` 把底图**裁出视窗矩形**再画，视窗位图盖在上面——底图光晕不再漏出。计数不同步（渲染在途/撤销/擦除途中）时不裁剪，新笔迹绝不因竞态消失。
  - `WindowLayer` 新增 `strokeCount`（= 渲染快照大小）；`commitStrokeToBitmap` 在视窗内提交笔迹时无条件 `strokeCount++`（不相交的笔不改变视窗内容，同样算同步）。
  - v1.4.14 的条件合并修复（`windowRenderingSpec`/`specCoversView`）原样保留，未动。
  - **离屏渲染竞态修复**（见下"既有脏画面"已定位段）：新增专用 `offscreenRenderer`（PageRenderer 实例）+ `offscreenLock`，`requestRender`（底图）、`startWindowRender`（视窗）、`commitStrokeToBitmap`（UI 线程提交）三条位图渲染路径全部经同一把锁串行化——`AppExecutors.compute()` 是 CPU 核数固定线程池，两条离线程渲染可真正并发，而 `PageRenderer` 持有可变 scratch（strokePath/strokePaint），并发 `drawStrokes` 互相踩 Path 即产生几何错乱。onDraw 的主 `renderer` 不动（仅 UI 线程用）。离线程渲染前补 `eraseColor(Color.TRANSPARENT)`（防御性，未单独证实必要）。
- 单测：`RenderScalePolicyTest` 改写 `windowEngagesOnlyPastCap`（恰在封顶/低于封顶不启用）、`windowHysteresisPreventsFlapping`（新滞回带 0.98~1.0），新增 `windowEngagesImmediatelyPastBakedScale`（过封顶 ×1.01/×1.2/×1.24 全部立即启用，锁死模糊带消除）；测试类现 19 例，全模块 `testDebugUnitTest` 绿。
- 版本 42/1.4.14 → 43/1.4.15。

### 真机验证（OPPO Pad 2d64f765，截图 `D:\Leslie\tmp\v15*.png` / `v15b_*`）
- **模糊带直击**（×2.2 ≈ 5.1x，旧模糊带正中）：新写笔迹与旧笔迹边缘锐利无像素块（`v15b_band.png` 剖面 2~3px）。
- **6x / 10x / 20x / 32x** 四轮（最终修复版 `v15b_*`）：每轮写一笔等 3s 截图，原尺寸裁片笔画边缘全部锐利、无像素块、无灰晕；20x 平移两轮后**已提交旧笔迹**（手写长扫线/折笔尖端）同样锐利（`v15b_20x_old.png`）；20x/32x 无黑屏（v1.4.14 修复无回归）。
- 逐像素剖面（垂直笔画方向采样）：修复前 32x 边缘过渡带 8~11px + 宽灰晕（`v15_32x_b.png`），修复后 32x = 2px、20x = 1~2px（`v15b_32x.png`/`v15b_20x.png`）。
- 回归：缩回 fit 原页面与开场截图**逐像素 diff 内容区零差异**；BACK 退主页正常。

### 遗留 / 注意
- **既有脏画面已定位并修复（offscreenRenderer/offscreenLock）**："20x 写一笔 → 撤销 → 立即 ×0.05 缩回 fit"冲击下 fit 视图必现巨型黑色楔形/丝带/横纹块且不自愈（复现 5/5；`v15c_fit_*.png`）。A/B 对照（回退本次 InkView 改动、保留阈值 ×1.0）3/3 照样复现（`v15d_fit_*.png`），确认为 v1.4.14 日志 v14f_back10.png 同类既有问题。根因：`AppExecutors.compute()` 是 CPU 核数固定线程池（AppExecutors.java:34），撤销时 `refreshCompletedLayer` 同时触发 `requestRender`（底图）+ `startWindowRender`（视窗），两条离线程渲染**并发**执行 `PageRenderer.drawStrokes`，共享可变成员 `strokePath`/`strokePaint` 互相踩 → Path 几何错乱（`BrushEngine.computeMesh` 已确认全静态无状态，竞争点只有 PageRenderer 成员）；`commitStrokeToBitmap` 在 UI 线程与离线程渲染并发同理。修复后同一冲击连跑 5 轮（`v15h_fit_*.png`）：**0/5 脏**，5 张截图互相逐像素 diff 为零（确定性渲染恢复），与开场截图的残差仅为缩放广播链浮点漂移导致的亚像素视口偏移（笔迹边缘 AA，非损坏）；20x 书写回归边缘 2~3px 锐利。**用户磁盘数据全程安全**：每轮后 force-stop 重开与开场截图 `v15_open.png` 内容区逐像素 diff 零差异（共验证 4 次）。
- **自动保存触发点注意**：本轮观察到笔迹 commit/撤销后 ~1min 内有落盘（20:18/20:24/20:28 三次 mtime 更新），测试笔迹务必当会话撤销干净，撤销后等一次落盘再核对更稳妥。
- 撤销栈仍是内存态；本轮全部测试笔迹已当会话撤销，三轮 force-stop 重开后页面与 19:58 开场截图内容区逐像素一致（仅状态栏时钟不同）。
- 累积缩放广播链（×N 后 ×1/N 回 fit）浮点易漂移，且广播可能因时序丢失一轮——本轮一度把"模糊带"轮打在 ~51x（截图 `v15b_band5x.png` 实为 51x，作废），重开页面重置视口后重打。建议后续给 debug 广播加 `--ef absolute N`（绝对倍数）模式。
- `artifacts/` 未更新（仍为 v1.4.9）：本次只出 debug 真机验证，如需交付再构建 release。



### 根因
用户反馈 20x 放大黑屏（10x 正常）。`InkView.scheduleWindowIfNeeded()` 的 `windowRenderInFlight` 分支**无条件** `windowDirty=true`（原 InkView.java:580 附近），形成自维持死循环：`swapWindowBitmap` 末尾 `invalidate()` → 下一帧 onDraw 必落在在途渲染中途 → dirty=true → swap 又立即重渲。真机插桩抓到铁证：46.2x 完全静止 3 秒内 **154 次 `startWindowRender`**，每次新分配 2080×2592×4B≈21.5MB 位图——GPU/内存持续搅动导致 20x 黑屏/巨大黑色多边形（回收纹理被复用的脏画面）+ 输入饥饿（用户"只能看到笔尖一点"）。10x 正常只是竞态运气（渲染耗时短，onDraw 落在在途窗口外的概率高）。RenderScalePolicy 本身无 bug（`windowCovers` 用真机数值手工验证：spec(275.005,392.966,319.995,449.034) 覆盖 vis(280.196,399.435,314.804,442.565) @46.23x ✓）。

### 改动
- `InkView`（core-render）：
  - 新增字段 `windowRenderingSpec`（InkView.java:170），记录**在途渲染**的 spec；`startWindowRender` 算出 spec 即记录（:662），`swapWindowBitmap`（:684）与 `discardWindow`（:711）清空。
  - inFlight 分支改为**条件合并**（:588）：仅当在途渲染落地即过期（`windowRenderingSpec == null || !specCoversView(...)`，视口已移动）才置 dirty；仍覆盖当前视图的在途渲染会自行落地为新鲜结果，不再无脑重渲。死循环链条就此斩断。
  - 新增私有 `specCoversView(spec)`（:611），`isWindowFresh()` 改为复用它（:607）。
  - `startWindowRender` 入口的 inFlight→dirty=true **保留**（:650）：那是 `refreshCompletedLayer`（撤销/擦除）的内容变更路径，必须重渲。
- 单测：`RenderScalePolicyTest` 新增 2 例 `freshWindowCoversItsOwnView` / `freshWindowCoversItsOwnViewAtPageCorner`（用真机 46.223278f 实测数值），锁定"新算的 spec 必然覆盖产生它的视图"这一修复依赖的不变量；测试类现 18 例。全模块 `testDebugUnitTest` 绿。
- 版本 41/1.4.13 → 42/1.4.14。

### 真机验证（OPPO Pad 2d64f765，截图 `D:\Leslie\tmp\v14\`）
- **20x**（广播 ×20，fit≈2.311 → ≈46.2x）：修复前全白/写笔后满屏黑色多边形；修复后白底+模板线+测试笔位置正确边缘锐利，无黑屏（`v14f_20x.png`）。
- **10x**、**32x**（≈74x）同流程正常，原有墨迹锐利（`v14g_10x.png`、`v14g_32x.png`）。
- 快速 ×20/×0.05 三连冲击后稳定（`v14g_stress.png`）；缩回 fit 正常（`v14g_clean.png`）。

### 遗留 / 注意
- **测试文档事故与恢复**：18:55–18:56 会话中橡皮疑似仍激活+视图被平移，误擦了 SearchDemo page1 一条原有长扫线且被自动保存落盘。已用 18:23 的事前备份（`tmp/v14/sd.inknote`）整体恢复（force-stop → push 到 /data/local/tmp → `run-as ... sh -c 'cat ... > ...inknote'` 覆盖写），恢复后页面与 18:21 原始截图做全图像素 diff：**仅状态栏时钟不同，内容像素级一致**。教训：清理测试笔迹前先确认当前工具不是橡皮；改动用户文档前先拉备份。
- **v1.4.13 修复前**曾现一次未完全解释的巨型黑多边形（`v14f_back10.png`，紧接 ×20/×0.05/×10/×0.1 快速变换后）：疑似回收纹理脏画面余波或橡皮激活+平移的交互副作用；修复版同流程未再复现。
- 测试注入笔迹呈"胖短黑块"（223 点、焊接后 5.7×1.4 页单位）是注入事件速度/压感曲线所致，非渲染 bug——三倍数下几何一致可证。
- debug 缩放广播只认 `--ef factor N`（乘在当前 scale 上、围绕 InkView 中心），`x/y` extras 被忽略。
- 工具栏实测 bounds 更新（1600×2560，比 v1.4.13 记录多铅笔/圆珠笔）：返回 (63,224)、撤销 (153,224)、钢笔 (371,224)、圆珠笔 (479,224)、铅笔 (587,224)、荧光笔 (695,224)、橡皮 (803,224)、套索 (911,224)、更多 (1537,224)。
- 撤销栈仍是内存态：跨重启的测试笔迹只能靠橡皮擦除。
- 临时插桩（DEBUG_V14）已全部移除，grep 无残留。
- `artifacts/` 未更新（仍为 v1.4.9）：本次只出 debug 真机验证，如需交付再构建 release。

## 2026-08-26 v1.4.13（versionCode 41）— 超封顶视窗级渲染（视窗位图，任何倍数都清晰）

### 背景
v1.4.12 的整页重烘培在 A4 上封顶 ~4.58x（40MB/页，受 48MB LRU 预算约束），用户反馈不可接受：放大就是为了写小字，**任何倍数都要清晰**。本轮在封顶之上加 PDF 阅读器式的视窗位图层（tiling 轻量版）。

### 方案与改动
- **双层结构**：整页位图仍按 v1.4.12 策略烘培（≤4.58x，作底图）；viewport 超过底图封顶 ×1.25 时（`RenderScalePolicy.shouldUseWindow`，带回差：退出阈值 ×1.1 防临界抖动），把**可见页区外扩 15%** 按**设备分辨率**离线程渲成视窗位图，盖在底图上。位图像素 = 屏幕可见区 ×1.3²（1600x2560 屏 ≈ 2080x3328 ≈ **27MB，固定有界、不随倍数增长**；`MAX_WINDOW_BYTES=32MB` 兜底：超了先去余量、再降像素 scale）。
- **InkView**：onDraw 末尾 `scheduleWindowIfNeeded()`——需用视窗但已失效（平移出 2% slack 覆盖区或缩放漂移 >5%）→ 300ms 防抖重渲；交互中先画旧视窗/底图（略软），停下即清晰。渲染 in-flight 时新请求置 `windowDirty` 合并（连续平移不排队），swap 后按当前 viewport 重新评估。缩回阈值以下 `discardWindow()` 回收；整页底图保持原 scale 不重烘。
- **笔迹提交**：`commitStrokeToBitmap` 高倍状态下同时画进底图和视窗位图（包围盒 +4 页单位相交才画），新笔迹立即清晰、不等防抖。`refreshCompletedLayer`（撤销/擦除）同步触发视窗重渲（不防抖，与底图一致）；swap 前校验 strokes 数量快照防竞态。视窗只缓存当前页，`setPage` 换页即弃；`release()` 一并回收。
- **RenderScalePolicy 新增**（纯 JVM）：`WindowSpec`、`computeWindow`（外扩/钳页边/字节兜底）、`windowCovers`（2% slack 提前刷新；贴页边的方向永不判失效——灰色工作区无墨迹）、`shouldUseWindow`（仅当整页重烘无提升时才启用，否则走更便宜的重烘）。
- **单测**：`RenderScalePolicyTest` 新增 7 例（启用条件、回差、外扩与分辨率、页边钳制、像素与倍数无关、字节兜底、覆盖判定/页边特例），全模块 `testDebugUnitTest` 绿。版本 40/1.4.12 → 41/1.4.13。

### 真机验证（OPPO Pad 2d64f765，截图 `D:\Leslie\tmp\inkcheck2\v13_*`）
- **8x**（广播 ×3.3）：stylus 写 3 笔，裁片边缘锐利（`v13_8x_s.png`）；手指平移后停顿，新视窗锐利（`v13_8x_pan_settled.png`；防抖从平移首帧起算，笔停下时基本已刷好）。
- **16x**（再 ×2）：2 笔锐利（`v13_16x_s.png`）；**32x**（再 ×2）决定性验证：4.58x 底图若被拉伸 7 倍必成泥，实际小笔画尖端锐利（`v13_32x_s.png`）——视窗层确实按设备分辨率工作。
- **回归**：缩回 ~2x 全部笔迹正常（`v13_back2x.png`，视窗已丢弃）；8x 下 stylus 橡皮整笔擦除 4 笔，残留笔迹重渲后仍锐利（`v13_8x_erased2.png`）；撤销恢复擦除，底图+视窗同步重渲（`v13_8x_undo.png`）；荧光笔（blend mode）8x commit 正常；连续滚动模式书写+滚动冒烟正常（`v13_continuous.png`）。

### 遗留 / 注意
- **测试注入注意**：本机书写只认 stylus（手指在笔工具下是平移）；`input stylus swipe` 可注入 stylus，普通 `input swipe` 是手指。橡皮工具下手指滑动同样是平移（v1.4.10 已记录）。工具栏按钮实测坐标（1600x2560）：撤销 (153,224)、钢笔 (371,224)、荧光笔 (695,224)、橡皮 (803,224)、更多 (1537,224)；连续滚动开关在 更多→翻页模式 (1248,457)。
- 视窗位图不计入 48MB 页 LRU（独立字段，峰值总内存 ≈ 48+27MB）。
- 平移/缩放交互中的短暂软化（≤300ms+渲染耗时）是设计取舍。
- SearchDemo 已恢复原状：测试页（中途为第 2 页）整页删除，2 页结构、用户内容未动，12:28 自动保存落盘确认。上轮 v1.4.12 的验证截图随 `tmp/inkcheck2` 被清理过，本轮截图另存。
- `artifacts/` 未更新（仍为 v1.4.9）：本次只出 debug 真机验证，如需交付再构建 release。

## 2026-08-26 v1.4.12（versionCode 40）— 深度缩放笔迹模糊：缩放自适应重烘培

### 根因
用户反馈"纸张放大很多倍后笔迹模糊、字写不小、调线宽没用"。InkView 把已完成笔迹烘进**固定 `RENDER_SCALE=2`** 的离屏位图；viewport 缩放超过 2 倍后 `drawBitmap` 拉伸采样 → 模糊。进行中的笔是纯矢量绘制所以始终清晰，**抬笔提交即糊**——用户描述的"写不小/调线宽没用"与模糊同源（深度缩放下写出的小字笔画一提交就糊成一团，看起来像没写上），焊接/噪声阈值（WELD 0.05、END_NOISE 2）都是页单位、与缩放无关，核查后确认无需改。

### 改动
- 新增 `core-render/RenderScalePolicy`（纯 JVM）：`BASE_SCALE=2`、`TRIGGER_FACTOR=1.25`（viewport 超过烘培 scale ×1.25 才触发重烘）、`HEADROOM=1.15`（重烘目标留 15% 余量防立即再触发）、`MAX_PAGE_BYTES=40MB`（单页位图字节封顶，**必须低于 48MB LRU 预算**否则自我驱逐；A4 ≈2.0MB·scale² → 封顶 ~4.58x）、`TEXTURE_GUARD=8`（8192px GPU 纹理上限，A4 不触发，保护超大自定义页）。`needsRebake` 含"新 scale 无提升则不重烘"防封顶处死循环。
- `InkView`：`pageBitmaps` 改 `LinkedHashMap<String, BakedPage{bitmap,scale}>`；onDraw 末尾 `scheduleRebakeIfNeeded()`（超 1.25 倍 → postDelayed 300ms 防抖后重查再 requestRender）；requestRender 取 `max(initialScale(viewport), existing.scale)`——擦除/撤销刷新**绝不降回**模糊分辨率；`commitStrokeToBitmap` 用 `baked.scale`（深缩放下新写笔迹直接烘到高分辨率）；缩回不降烘（LRU 自然回收）；`release()` removeCallbacks。
- `EditorFragment` 新增 **debug-only 缩放广播钩子** `registerDebugZoomHook()`（`FLAG_DEBUGGABLE` 门控，release 不注册）：action `com.leslie.colorosnote.debug.ZOOM`，extra `--ef factor 1.8`，内部 `viewport.zoomBy(factor, 视图中心)`。**决定保留**：debug 门控无 release 面，验证深度缩放类问题长期有用（pinch 无法注入：sendevent 无权限、`input` 无 pinch 命令）。
- 新增 `RenderScalePolicyTest` 8 例；全模块 `testDebugUnitTest` 绿；版本 39/1.4.11 → 40/1.4.12。

### 真机验证（OPPO Pad 2d64f765，截图在 `D:\Leslie\tmp\inkcheck2\`）
- fit 写 3 笔参考（2x 位图）→ 广播 ×1.8（viewport≈4.4x）：150ms 内 `v12_zoom18_blur.png` 边缘明显发虚 → 2s 后 `v12_zoom18_sharp.png` 边缘锐利——**重烘培生效**。
- 再 ×1.8（≈7.9x，超 4.58 封顶）：不再重烘、无浪费渲染（符合 needsRebake 设计）；此状态下写 3 笔小笔画，`v12_zoom32_write.png` 全分辨率裁片**三笔边缘全部锐利**（commit 用了 4.58x scale）。
- 回归：撤销 1 笔后高 scale 重渲正常（`v12_undo1.png`）；×0.55×2 缩回 fit 后仍锐利（`v12_backtofit.png`，不降烘生效）。

### 遗留 / 注意
- 超 4.58x 封顶后笔迹残留拉伸模糊为**已知取舍**（内存约束）；彻底解决需 tiling 渲染，列为后续方向。
- 连续滚动模式（PageContinuousAdapter 内 InkView）走同一套新逻辑，本轮未单独实测。
- 撤销栈仍是内存态：本轮参考笔迹恰好跨了装机（v1.4.11→v1.4.12 重装清栈），撤销不动，最终靠长按缩略图「删除页面」清掉 page3；橡皮工具下手指滑动仍是平移不擦除（v1.4.10 已记录）。SearchDemo 现为 2 页、用户内容原样，已自动保存（11:53 落盘确认）。
- `artifacts/` 未更新（仍为 v1.4.9）：本次只出 debug 真机验证，如需交付再构建 release。

## 2026-08-26 v1.4.11（versionCode 39）— 连笔延迟第二轮：平滑滞后 + 预测地平线 + 硬件层 + invalidate 粒度

### 背景
用户实测 v1.4.10 后反馈"感觉不够快"。v1.4.10 已解决每帧全量重算（onDraw O(n)），本轮按"笔尖到像素端到端延迟"逐项排查剩余来源。

### 逐项结论
1. **平滑器滞后（改了，主因）**：StrokeSmoother 的 1€ 滤波 `POS_BETA` 0.015→**0.05**。1€ 跟踪匀速直线的稳态空间滞后 ≈ 1/(2π·β)：旧值高速段拖 ~10.6 页单位（fit 缩放 ~30px），新值 ~3.2 单位（~9px）。慢写降噪不变（mincutoff 未动）。新增 `highSpeed_lagStaysSmall` 测试锁定（240Hz、600u/s 匀速 2s 后滞后 <5 单位）。
2. **预测地平线（改了）**：androidx.input 1.0.0 KalmanMotionEventPredictor 公开 API 无地平线配置，实测只预测 ~1 帧。新增纯 JVM `PredictionExtender`：在库预测基础上沿末段速度线性外推到**距最新真实点 25ms**，外推距离封顶 14 页单位、速度 <120u/s 不外推（防停笔/转向过冲）。`updatePrediction` 末尾接入；预测点永不入笔。新增 `PredictionExtenderTest` 5 例。
3. **LAYER_TYPE_HARDWARE（移除了）**：无 git 历史可查，评估后认为无必要——HWUI render node 在父级滚动时本就免重栅格化；live 笔画是纯 SRC_OVER FILL（高光等 blend mode 只在收笔进 bitmap 时用，bitmap Canvas 不涉及窗口层）；全屏硬件层每帧白白多一次全屏 blit + 一张全屏纹理。移除后真机 GPU 帧耗时 50 分位 3ms→1ms、90 分位 4→3ms。书写、撤销重渲、手指平移均冒烟正常。连续滚动模式（PageContinuousAdapter）未单独实测，如用户反馈滚动掉帧再回看。
4. **前沿缓冲 front-buffer（未做）**：前 3 条后 framestats 已达标（见下），且 CPU/GPU 耗时大头已消，不值得承担收笔/撤销/橡皮正确性风险。
5. **invalidate 粒度（改了）**：`onPointsAdded` 的 dirtyBounds 原来是**整笔累积**包围盒（与 javadoc 宣称的"新墨迹区域"不符），livePath 持久化后每帧 damage 整笔区域。改为 per-batch：从上一点起算、只含本批新点（旧 tail 位置被覆盖重绘）。整笔累积仍由 `getDirtyBounds()` 提供。

### 真机对比（OPPO Pad 2d64f765，120Hz，同两条 2.5s 长笔各 ~600 帧）
| 指标 | v1.4.10 | v1.4.11 |
|---|---|---|
| Janky frames | 2 (0.33%) | 3 (0.50%)（噪声级持平） |
| Janky legacy | 3 (0.50%) | 3 (0.50%) |
| **High input latency 帧数** | **298 / 598** | **0 / 596** |
| GPU 帧 50/90 分位 | 3ms / 4ms | **1ms / 3ms** |
- 中途截图：v1.4.11 笔迹尖基本贴在理想笔尖位置，无半透明过冲伪影；最终笔迹与旧版一致（连续、转折锐利、taper 正常）。
- 感知提升主要来自 1（平滑滞后 ~30px→~9px）和 2（预测条真正够到笔尖），framestats 掉帧率两版本本就接近。

### 遗留 / 注意
- 撤销栈是内存态：跨版本/重启 App 后旧笔迹无法撤销，清理测试笔迹改用「删除页面」（长按缩略图）或橡皮。
- SearchDemo 文档本轮已恢复原状：page2 测试笔迹全部撤销、page3 删后重建（3 页结构、空白，已自动保存）。
- 压感仍只能模拟验证；`artifacts/` 未更新（仍为 v1.4.9）。

## 2026-08-26 v1.4.10（versionCode 38）— 快速连笔笔迹延迟修复（流式增量轮廓渲染）

### 根因
- 输入侧本来就没问题（`requestUnbufferedDispatch` + 历史事件全消费）。瓶颈在渲染：`InkView.onDraw()` 对进行中的笔**每帧**把全部点 `BrushEngine.computeMesh` 再 `closedOutline()` 重建整条 Path——O(点数) 且带三角函数，240Hz 采样下连笔 1–2 秒就累积几百上千点，直接超出 120/144Hz 帧预算，帧一丢笔迹就显得"跟不上笔尖"。预测尾巴同样每帧全量 `computeMesh`。

### 改动
- 新增 `core-ink/LiveOutlineBuilder`（纯 JVM，零分配热路径）：喂入 BrushEngine 流式 API 的 styled 点，每个新点输出**一个**四边形（法线算法与 `BrushEngine.buildMesh` 一致：内部点中心差分、端点前向差分）；提交的 quad 滞后一段，保证相邻 quad 接缝弦精确一致——尖角转折处无缝无重叠；最新段走 `tailQuad()` 瞬态重建，收笔后 tail 为空；焊接阈值与全量网格一致（0.05 / 半宽）。
- `InkView` 改增量渲染：笔开始 `beginStroke` + 清空路径；`onPointsAdded` 只把新产生的 quad **追加**进 `livePath` 并重建 `tailPath`；`onDraw` 零重算，直接 `drawPath` ×3（live/tail/committed）；预测尾巴改为"最后一个 styled 点 + 预测点"的窄条（每帧几个点，便宜），不再 `computeMesh`。收笔 `commitStrokeToBitmap` 管线不变，最终笔迹质量不变。
- 新增 `LiveOutlineBuilderTest` 8 个用例（法线方向、宽度渐变、短段焊接跳过、滞后一段、接缝弦一致、tail 衔接、reset、缓冲增长），全模块单测绿。
- 版本号 37/1.4.9 → 38/1.4.10。

### 真机验证（OPPO Pad `2d64f765`，屏幕实为 120Hz）
- 新旧版同笔画对比（快速折线×3 + 1.5s 长斜线 + 2s 慢写）：**最终笔迹完全一致**——笔身连续、转折锐利、起笔 taper 正常、无缺口；书写中截图笔迹跟尖无拖尾。
- `dumpsys gfxinfo framestats`（两条 2.5s 长笔、各 599 帧）：janky 3→**1**（0.50%→0.17%），legacy janky 12→**3**（2.00%→0.50%）。
- 验证过程产生的测试笔迹已用橡皮全部擦除，文档内容恢复原状（仅剩原有的一个点笔迹）。

### 遗留 / 注意
- 预测尾巴的半透明（alpha 120）现在**真正生效**了：旧代码那行是死代码（`drawMesh` 内部会覆盖 alpha），行为略有变化但属预期。
- 橡皮工具下**手指**滑动是平移画布、不擦除；只有**手写笔**滑动才擦除（本次清理测试笔迹时实测确认；擦除本身会正常触发 2s 自动保存）。
- `artifacts/` 未更新（仍为 v1.4.9）：本次只出 debug 真机验证，如需交付再构建 release。

## 2026-08-26 v1.4.9（versionCode 37）— 录音入口可发现化 + 长按空白菜单高级感重做

### 用户需求
1. 录音保存到当前笔记后找不到在哪看。
2. 长按页面空白处的菜单（粘贴/全选手写/插入图片）还是系统原生灰色 PopupMenu，要和其他弹窗的奶白高级感一致。

### 录音现状与修复
- 录音存储：每条录音按会话存为 `files/audio/<documentId>/rec_<时间戳>_partNNN.m4a`（App 私有目录，文件管理器看不到）， App 内已有回放/删除列表 `RecordingListDialog`，但入口是「+ → 录音」的**长按**——不可发现。
- 修复：`dispatchAuxTool(RECORDING)` 改为：录音中点按=停止；非录音时先异步 `RecordingLibrary.count()`（新增轻量计数，不探测时长，纯 JVM 可测），0 条直接开始录音，>0 条弹 MenuRowPopup 选择「开始新录音 / 查看录音（N条）」。长按入口保留。

### 长按空白菜单重做
- 新增可复用组件 `MenuRowPopup`（popup_menu_rows.xml + item_menu_row.xml）：奶白卡片 + EditorMenuRow 图标行，支持 showAsDropDown(锚点) 和 showAt(触摸点)；禁用项 alpha 0.4 不可点。
- `showBlankContextMenu` 由 PopupMenu 换成 MenuRowPopup，终于能精确定位到手指按压处（原 PopupMenu 只能锚定视图）。图标：粘贴=ic_sel_paste、全选手写=ic_tool_lasso、插入图片=ic_aux_image。
- 新增图标 ic_rec_list（列表+播放三角）。

### 测试与验证
- 新增 RecordingLibraryTest.countSessionsWithoutProbing；全模块单测绿。
- 真机（OPPO Pad）：长按空白 → 奶白卡片出现在按压点，粘贴项因剪贴板空正确置灰 ✓；+ → 录音 → 选择菜单（SearchDemo 有 1 条录音）→ 查看录音 → 列表显示「录音 · 08-26 00:56 时长00:04」可播放/删除 ✓。
- debug+release 已交付 artifacts/。


## 2026-08-26 v1.4.8（versionCode 36）— 套索工具弹窗高级感重做

### 用户需求
- 工具栏套索图标二次点击的弹窗是系统原生 PopupMenu（灰色菜单），与钢笔/橡皮等自定义弹窗风格严重不一致。

### 改动
- 新增 `LassoModePopup.java` + `popup_lasso_mode.xml`：奶白卡片（bg_popup）+「选择方式」caption + 分段选择器（自由套索/矩形选择），完全复用橡皮弹窗的 EditorPopupLabel/EditorSegmentTrack/EditorSegment 样式与弹出参数（280dp、elevation 16、showAsDropDown）。
- `EditorFragment.onToolSecondTap`：LASSO 分支由原生 PopupMenu 改为 `LassoModePopup.show(anchor, presetStore.lassoMode(), presetStore::saveLassoMode)`。
- 字符串入库：lasso_mode / lasso_mode_free / lasso_mode_rect（原来硬编码在代码里）。
- 版本号 35/1.4.7 → 36/1.4.8。

### 真机验证（OPPO Pad 2d64f765）
- 二次点击套索图标 → 奶白卡片分段弹窗 ✓；自由套索↔矩形选择切换高亮正常 ✓；样式与橡皮/钢笔弹窗一致 ✓。
- 单测全绿，debug+release 已交付 artifacts/。


## 2026-08-24 v1.4.7（versionCode 35）— 套索浮动菜单 UI 高级感重做

### 用户需求
- 套索选中后的浮动操作条（复制/剪切/粘贴/改颜色/删除）太朴素，参考钢笔/"+"面板的高级感重做。

### 改动
- `popup_lasso_actions.xml`：5 个纯文字 Button → 「图标+文字」单元格，完全复用 "+" 面板的 `EditorAuxItem` / `EditorAuxItemIcon` / `EditorAuxItemLabel` 样式与 `bg_popup` 奶白卡片、操作提示 caption；删除项图标与文字用 `@color/destructive` 红色。
- 新增 5 个手绘线条风矢量图标（stroke 1.6dp、#17181A、圆角端点，与 ic_aux_* 同一套视觉语言）：`ic_sel_copy`（双框）、`ic_sel_cut`（剪刀）、`ic_sel_paste`（剪贴板）、`ic_sel_color`（墨滴）、`ic_sel_delete`（垃圾桶）。
- id 未变（btnSelCopy 等），EditorFragment 逻辑零改动；标签由 Button 变 LinearLayout，ViewBinding 自动适配。
- 版本号 34/1.4.6 → 35/1.4.7。

### 真机验证（OPPO Pad 2d64f765）
- 套索点选波浪胶带 → 新弹窗：图标+文字五格、红色删除项 ✓；点「删除」胶带消失 ✓；撤销恢复 ✓。
- 单测全绿，debug+release 已交付 artifacts/。


## 2026-08-24 v1.4.6（versionCode 34）— 套索点选删除对象 + 锁定对象保护

### 用户需求
- 橡皮擦只能擦笔迹，页面上的对象（文本框/形状/胶带/贴纸/图片）删除不便；想要点一下就能选中删除。

### 调研结论
- GoodNotes/Notability 约定：橡皮只擦笔迹（本 App 现状，保持不动）；对象通过套索/选择工具选中后删除，支持点选单选和圈选多选批量删。本 App 已有圈选多选+浮动菜单删除，缺的是点选。

### 改动
1. **套索点选（tap-to-select）**：
   - `LassoEngine`（core-ink）新增 `selectAt(Page, x, y, slop)`：纯 Java 单点命中，返回最上层（zIndex 最大）未锁定对象的单对象 Selection；命中测试用变换后四角多边形，slop 命中时退化为外扩 AABB。
   - `EditorGestureController`：Host 接口加 `onLassoTap(pageX, pageY)`；LASSO 手势 UP 时点数不足构成圈选（< MIN_LASSO_POINTS*2，沿用现有条件，小位移拖拽也归入 tap）→ 用 DOWN 点回调 host。
   - `EditorFragment.onLassoTap`：调 `selectAt`（slop=20px/缩放，与 ObjectToolController 的 TAP_HIT_SLOP_PX 一致），命中走 `selectionController.setSelection` 复用选框/手柄/浮动删除菜单；未命中 `clear()`（与空圈选一致）。
2. **锁定对象保护**：
   - `LassoEngine.acceptsObject` 排除 `locked` 对象（圈选+点选同时生效）。
   - `SelectionController.delete` 删除循环里跳过 locked（双保险）。
3. 单测：`LassoEngineTest` 新增 3 例（圈选跳过 locked；selectAt 命中最上层未锁定；未命中返回空+slop 加宽命中区）。core-ink 共 8 例全绿，全模块 testDebugUnitTest 全绿。
4. 版本号 33/1.4.5 → 34/1.4.6。

### 真机验证（OPPO Pad 2d64f765）
- 套索工具下 stylus 点星星贴纸上的粉色胶带 → 选中（选框+四角手柄+浮动菜单：复制/剪切/粘贴/改颜色/删除）✓；点「删除」→ 胶带消失 ✓；撤销 → 恢复 ✓。
- 点星星未被遮挡的尖角 → 精确选中星星本身 ✓；删除+撤销 ✓。
- 注意：点选命中"最上层"对象——星星被胶带压住的位置点选会选中胶带，这是正确的 topmost 语义（与 ObjectHitTester 一致）。
- 批量圈选（划线穿过多个胶带）→ 多选删除 ✓；撤销全部恢复 ✓。测试痕迹已全部撤销还原。
- **adb 验证技巧（重要，以后复用）**：本机手指模式=PAN_ONLY，`input tap/swipe` 是手指事件，在套索/画笔工具下只会平移，必须用 `input stylus tap x y` / `input stylus swipe ...` 模拟手写笔。调试时曾临时在 onLassoTap 加 Log.d("LassoTap",...) 定位，验证完已删除。

### 遗留
- 连续翻页模式下套索仍禁用（现状，本次未动）。
- `selectAt` 对手绘路径胶带用 bounds 四角命中（非 TapeGeometry 的精确条带距离测试），极端细薄胶带的点选手感略宽/略窄，后续如需要可收紧。
- SearchDemo 笔记上保留 v1.4.5 遗留的一条手绘条纹测试胶带（undo 不生效问题未查）。


## 2026-08-24 v1.4.5（versionCode 33）— 文本框拖动文字跟手 + 胶带全面重做

### 用户需求
1. 拖动文本框时文字要实时跟着框移动（之前松手才跳过去）。
2. 胶带太丑：配色和图案全部重找重做。

### 改动1：文本框拖动文字实时跟手
- 根因：拖动路径走 `EditorFragment.scheduleObjectToolRefresh()`（60ms 防抖），手指持续 MOVE 时每次重新推迟 → 整个拖动过程一帧不刷；松手走 `notifyObjectToolEdited()` 立即刷新，所以文字"跳"过去（防抖饥饿）。
- 修复：`scheduleObjectToolRefresh()` 改为直接 `binding.inkView.invalidate()`。ObjectToolController 手势逻辑零改动。

### 改动2：胶带重做（TapePainter + TapeConfigPopup）
- 配色：5 个纯色 → 8 色莫兰迪色系（雾粉/豆沙红/奶杏/奶油黄/鼠尾草绿/雾霾蓝/薰衣草灰紫/浅灰棕），默认色=雾粉。
- 矩形胶带 7 图案全部细化等比化：删掉粗色块，改 hairLine/fineLine 按条带厚度（`patternThickness`）等比缩放；图案墨色 = shade(胶带色) alpha=150。
- 手绘胶带图案修复：原来用 DashPathEffect 沿中心线打点近似（一坨大圆斑）。先试过 saveLayer+DST_IN 蒙版——**真机（ColorOS14 硬件加速 Canvas）上蒙版不生效**，图案溢出到包围盒。最终方案：`Paint.getFillPath()` 把描边路径转成填充轮廓 → `canvas.clipPath(bandPath)` 裁剪后再平铺图案，软硬 Canvas 行为一致，真机验证边缘平滑无锯齿。
- 注意：图案平铺方法复用成员 `path`（波浪/爱心会 reset 它），所以手绘条带路径必须用独立成员 `stripPath` 保存。
- 新增 TapePainterTest 5 例（色彩数学+等比度量契约）。

### 真机验证（OPPO Pad 2d64f765）
- 文本框拖动：慢速拖动「HLeslieNoteTest」，文字实时跟手 ✓
- 胶带弹窗：8 色板 + 7 张图案预览卡全部细腻 ✓
- 手绘条纹/波浪胶带：图案完美贴合条带轮廓 ✓；直线胶带（雾粉细斜纹）✓
- 443 单测全绿，debug+release 已交付 artifacts/。

### 遗留
- SearchDemo 测试笔记上还留有一条手绘条纹测试胶带（undo 对手绘胶带对象不生效，未深查）；其余测试痕迹已撤销。
- 若后续继续改渲染相关代码，记得真机验证——单测是纯 Java 契约，覆盖不到 Canvas 合成行为。


## 2026-08-22 v1.4.4（versionCode 32）— 文本框移动手柄醒目化

### 用户需求
- 文本框右上角移动按钮不明显，换颜色。

### 改动（EditorOverlayView）
- 右上角新增「移动」专用手柄：19px 橙色实心圆盘（0xFFFF8A3D）+ 白边 + 白色四向箭头 glyph（`drawMoveHandle`，`moveFillPaint`）。拖动该手柄即移动文本框（原本就支持框内任意拖动，手柄只是视觉强化）。
- 右下角缩放手柄同步放大 17→19px（蓝盘白边 ↘ 箭头）。
- 教训记录：小尺寸圆盘内画完整 ✥（十字+8 个箭头）会糊成一团，改成 4 个朝外的 chevron 才清晰； glyph 与圆盘边缘要留 ≥4px 间隙。
- 版本号 31/1.4.3 → 32/1.4.4。

### 真机验证（OPPO Pad）
- 选中框：左上/左下白点、右上橙色移动盘（✥ 清晰）、右下蓝色缩放盘（↘ 清晰）✓。


## 2026-08-22 v1.4.3（versionCode 31）— 文本框选中手柄加大加明显

### 用户需求
- 选中文本框后右下角缩放标志太不明显。

### 调研结论
- GoodNotes 约定（[官方文档](https://support.goodnotes.com/hc/zh-cn/articles/7353727615375)）：白色角点=普通控制点，**蓝色实心圆=调整大小**，拖角按比例缩放。采用此约定。

### 改动
- `EditorOverlayView`：四角普通手柄 6px→10px（白底蓝圈）；右下角缩放手柄改为 17px 蓝色实心圆盘 + 白色描边 + 白色 ↘ 双向箭头 glyph（新增 resizeFillPaint/resizeRingPaint/resizeGlyphPaint，`drawResizeHandle`）。
- `ObjectToolController.RESIZE_HANDLE_PX` 28→34（命中区跟随视觉加大）。
- 版本号 30/1.4.2 → 31/1.4.3。

### 真机验证（OPPO Pad）
- 选中文本框：四角白点清晰，右下角蓝色圆盘+白色箭头醒目 ✓；拖圆盘缩放生效、手柄跟随新角 ✓；撤销还原 ✓。


## 2026-08-22 v1.4.2（versionCode 30）— 文本框交互重做 + 钢笔切换卡死修复

### 用户需求
- 加文本框要先落一个可见虚线框，再点击框输入文字（知道框的范围）。
- 选中文本框后可移动/编辑/缩放（左右调节）/删除。
- bug：选中文本框后点钢笔写不出字，一直停在文本框模式。

### bug 根因
`EditorFragment.onToolClicked()`：`tool == currentTool` 时走「二次点按」弹笔刷属性框。AuxTool（「+」菜单工具）不改变 currentTool，所以武装文本框后点钢笔永远走不到 `selectTool()` 里的 `objectToolController.disarm()`。修复：`tool == currentTool` 且 `objectToolController.isArmed()` 时先 disarm 并 return。

### 改动
- **放置流程**：insertTextBox 不再自动弹编辑对话框，虚线「输入文本」占位框留页面，保持 armed 可连续放置；删除取消回滚逻辑（框是用户主动放的）。提示文案改「点击页面放置文本框，再点框输入文字」。
- **选中态**（ObjectToolController，TEXT_BOX armed）：单击非空框 → 选中（overlay 蓝框+四角手柄）；按住框内拖动实时移动、拖右下角手柄缩放（最小宽 80f / 高一倍行高，`clampResizedBounds` 纯静态可测）；UP 用 `ObjectCommands.EditTextBox` 快照提交一条 undoable（先恢复 bounds 到手势起点保证 before 精确）；未拖动的 UP 弹对象菜单（编辑/置顶/置底/锁定/删除）；点空白取消选中并放新框；锁定框只弹菜单不可拖。disarm/arm 都清选中态。
- 单击空框 → 直接进 TextBoxEditDialog；双击非空框仍可编辑。
- EditorFragment 新增 `scheduleObjectToolRefresh()`（60ms 防抖轻量重绘，拖动过程不刷缩略图）。
- 版本号 29/1.4.1 → 30/1.4.2。

### 测试与真机验证（OPPO Pad 全过）
- 新增 ObjectToolControllerTest 5 例（clamp）、ObjectCommandsTest 补移动快照 undo/redo；全模块单测绿。
- 真机：armed 态点钢笔立即回书写、不弹属性框 ✓；点空白落虚线框不弹窗 ✓；点空框进编辑、取消框保留 ✓；点非空框出蓝框手柄+菜单 ✓；拖动移动+撤销还原 ✓。

### 遗留
- 空文本框的删除需用套索选中后删（单击空框是进编辑）；如需「长按空框出删除菜单」可后续加。


## 2026-08-22 v1.4.1（versionCode 29）— 彻底移除工具栏自动隐藏

### 用户需求
- 顶部工具栏不要自动隐藏：笔尖碰到不隐藏、点「+」等插入功能时也不隐藏（之前有隐藏且卡 bug）。

### 改动
- `EditorFragment.onWritingStateChanged()` 改为空实现（保留接口），书写时不再隐藏 topBar/thumbnailBar。
- 删除 `showBarsRunnable`、`setBarsVisible()`、`fadeBar()`（无其他调用方）。
- 「⋯」更多菜单删除「沉浸模式」行（popup_more_menu.xml 的 row_immersive 及其前面的分隔线，及 EditorFragment 里 chkImmersive/rowImmersive 逻辑）。
- `EditorSettings.immersiveMode` 持久化字段保留（默认 false，已无任何代码读取，不影响旧数据兼容）；menu_immersive 字符串和 ic_menu_immersive 图标留作未引用资源。
- 版本号 28/1.4.0 → 29/1.4.1。

### 真机验证（OPPO Pad 2d64f765）
- 画布上连续书写/滑动后顶部工具栏与缩略图栏保持显示 ✓。
- 「⋯」菜单已无「沉浸模式」项 ✓；打开「+」插入工具菜单时工具栏稳定不隐藏、无闪烁 ✓。


## 2026-08-22 v1.4.0（versionCode 28）— 页面模板扩充：5 → 14 种 + 奶白视觉选择器（参考云记）

### 用户需求
- 页面模板太少太简单，参考云记扩充模板数量。

### 改动
- **枚举**：`PageBackground.Type` 新增 9 个类型（插在 CORNELL 之后、PDF 之前，PDF/OFFICE/IMAGE/CUSTOM 相对顺序不变，序列化按 name）：CORNELL_GRID、ENGLISH、TIANZI、MIZI、MUSIC、TODO、DAILY、WEEKLY、MEETING。
- **渲染**（PageRenderer.drawBackground）：康奈尔方格（三段结构+主区方格）、英文四线格（四线一组、中线虚线）、田字格（格内虚线十字）、米字格（十字+对角虚线）、五线谱（5 线一组）、待办清单（左侧 checkbox 轮廓+横线）、日计划（8:00–22:00 时间标签+横线）、周计划（表头横带+7 列「周一…周日」）、会议纪要（顶部信息区+横线主体+底部总结区）。虚线用 DashPathEffect，Paint 成员复用，参数走 templateParams 可覆盖。
- **共享选择器**（放 core-render 模块，两个 feature 模块复用）：`TemplatePreviewRenderer`（离屏渲染 156×208 预览 Bitmap，走真实 drawBackground）+ `TemplatePickerDialog`（XML inflate 居中弹窗，ScrollView+GridLayout 3 列预览卡，当前类型蓝框高亮）。res：bg_template_popup/bg_template_preview、popup_template_picker.xml、item_template_cell.xml、14 个模板名字符串。
- **三入口接线**：①编辑器「⋯ → 页面模板」行改为弹选择器（删掉 popup_more_menu.xml 内联 5 行块和 chevron，删 updateTemplateChecks/tmplRows）；②缩略图右键子菜单改为单项「页面模板」弹同一选择器；③主页「新建模板笔记」由 AlertDialog 文本列表改为同一选择器（feature-library 加 core-render 依赖）。选中后仍走 `pageController.setTemplate`（undoable）。
- **版本号**：27/1.3.0 → 28/1.4.0。

### 测试与真机验证（OPPO Pad 2d64f765，全过）
- 单测全绿（新增 PageBackgroundTest 枚举/ofType 覆盖、TemplatePickerCatalogTest 目录 14 项无重复不含文档型）。
- 编辑器内选择器：3 列预览卡渲染正常、滚动正常、当前模板高亮、选田字格页面立即切换（缩略图同步）✓。
- 主页新建模板笔记：选择器弹出、选五线谱直接建笔记并进入编辑器，五线谱背景正确 ✓。

### 遗留
- feature-editor/feature-library 里 5 个旧 template_* 字符串已成未引用资源（低风险保留，可后续清理）。
- 日计划/周计划在小预览卡里标签较密，真机全尺寸页面显示正常。
- 平板上留有一个「未命名笔记」（五线谱模板）为本次验证所建，可删。


## 2026-08-22 v1.3.0（versionCode 27）— 「+」菜单全功能落地：文本框/录音/形状/胶带/贴纸/OCR/手写转文字/语音转文字

### 用户需求
- 文本框点了没反馈；录音无开始/暂停/停止按钮、录完不知去向；形状只有灰色提示；胶带纯色太普通；贴纸太简陋；OCR、手写转文字、语音转文字是「即将上线」占位，要求全部真实开发；录音停止后要给「转文字」等选项。

### 改动
- **文本框**：激活提示 CreamToast + 空文本虚线占位「输入文本」+ 取消回滚空对象；TextBoxEditDialog 奶白重做。
- **形状**：新增 ShapePickerPopup，9 种形状实绘图标。**坑**：程序化 GridLayout/LinearLayout 在 ColorOS 上塌陷成单列，最终改 XML 布局（`popup_shape_picker.xml` GridLayout columnCount=3 + `item_shape_cell.xml`）解决——此 App 弹窗优先走 XML inflate。
- **胶带**：TapePattern 扩到 7 种（SOLID/STRIPES/DOTS/GRID/CROSSHATCH/WAVES/HEARTS），TapePainter 半透明和纸质感 + 图案预览卡。
- **贴纸**：10→23 个多色矢量，4 分类。
- **录音**：顶部悬浮控制条（红点计时+暂停/继续+停止，RecordingBarState 状态机）；停止后「录音已保存」弹窗（时长+播放/转文字/完成）；长按「+」菜单录音项进录音管理列表（播放/删除）。`SpeechTranscriptionGateway` 接口注入点在 EditorServices。
- **手写转文字 + OCR**：接 ML Kit（MlKitHandwritingRecognizer / MlKitOcrEngine），识别结果对话框（可编辑+插入为文本框/复制/取消）。**坑**：preContext 为 null 时必须 setPreContext("")，否则 ML Kit 抛 Missing required properties。
- **语音转文字**：Vosk 0.3.75 离线引擎，模型 vosk-model-small-cn-0.22（42MB，filesDir/models/ 下），实时识别对话框（开始/暂停/停止+实时文本+插入/复制/丢弃）；录音转写走 MediaCodec 解 AAC→16kHz PCM→Vosk。**坑**：`VoskModelManager.isModelStructureComplete` 原要求 HCLG.fst+words.txt，但 small-cn 是分体 HCLr.fst+Gr.fst 且无 words.txt → 永远判未就绪无限重下；已改为 final.mdl 必需 + 两种图结构二选一。

### 真机验证（OPPO Pad 2d64f765，全过）
形状 3×3 ✓、胶带 7 花纹 ✓、贴纸 4 分类 ✓、文本框提示 ✓、录音条计时/暂停/停止 ✓、停止弹窗「时长 01:15」✓、Vosk 模型下载带真实进度（设备直连 alphacephei.com，300-400KB/s）✓、录音转写（MediaCodec 解 AAC）✓、手写识别出结果 ✓、OCR 准确 ✓、实时语音 partial→完成→插入文本框落页 ✓。设备有 GMS，ML Kit 链路正常。

### 遗留
- 测试笔记里「22:35 / HLeslieNoteTest / IndexProbe」等文本是早期自动化测试残留内容，非 bug。
- 录音转写只转首个分段；Vosk small-cn 无标点（升 sherpa-onnx + Paraformer 可解决，见调研报告）。
- 语音/手写模型首次使用需联网下载一次。


## 2026-08-22 v1.2.1（versionCode 26）— 笔刷样式卡片化：名称 + 真实笔迹预览图

### 用户需求
- 笔刷样式每个选项加图片示例：上方名称、下方该笔刷实际写出的笔迹预览，方便选择时直观对比。

### 改动
- 新增 `feature-editor/.../PenStylePreviewRenderer.java`：离屏渲染预览 Bitmap。**完整复用真实书写几何管线**（`BrushEngine.computeMesh` + `StrokeMesh.closedOutline()`，与 InkView 实时书写同一代码路径），合成 72 点双波形签名笔画（波峰高压低速、过线高速触发速度变细），逐变体 applyTo 注入参数后渲染；fude 美工尖固定 tilt=0.75rad 演示倾角加粗。透明底 + 0xFF1A1A1A 深色笔迹，自动缩放居中。
- `BrushPropertiesPopup.buildStyleRow`：文字 chip → 竖向卡片（上方名称 TextView + 下方 64dp×26dp 预览 ImageView），仍横排在 HorizontalScrollView；`refreshStyleSelection` 适配卡片结构；选中态沿用 chipDrawable（蓝底描边）。交互逻辑零改动（applyTo → 滑杆刷新 → notifyChange 持久化）。
- `app/build.gradle`：26 / "1.2.1"。

### 验证
- 纯 JVM 冒烟：7 变体轮廓全部非退化，宽度包络差异符合预期（极细尖 max 2.57 最细，美工尖 max 9.42 最粗，疾书尖中段变细）。
- `:feature-editor:compileDebugJavaWithJavac` + 全量单测 358 绿；debug 装 OPPO Pad（2d64f765）真机截图确认：名称+预览图正常、宽度差异可见、选中高亮正常。
- release 打包交付 artifacts/ + SHA-256。

### 风险/后续
- 弹框高度增加约 24dp（WRAP_CONTENT，真机已确认无碍）。
- 预览不参与 StrokeSmoother 输入平滑（仅几何），与实际观感差异极小。
- 可选项：预览笔迹颜色跟随当前选中颜色；预览加网格纸纹底。


## 2026-08-22 v1.2.0（versionCode 25）— 钢笔弹框新增笔刷样式变体库（7 款）

### 用户需求
- 「再检索更多好用的笔刷，在钢笔弹框里给我一些选项让我选」。

### 调研
- `docs/BRUSH_RESEARCH.md` 新增「第三轮调研：钢笔变体库」（2026-08-22）：真实钢笔尖规格（万宝龙 EF 0.43 / M 0.62 / B 0.92mm，换算系数 baseWidth 3 ≈ M 尖）、GoodNotes 6 双维度（锐利度/压感灵敏度）、Concepts/Write 速度驱动模型、Sailor Fude 美工尖角度控宽等，输出可直接抄的参数表。

### 实现
- 新增 `PenStyleVariants`（feature-editor）：7 个变体，排序「日常 → 创作」：**标准尖 M**（现默认）/ **极细尖 EF**（小字批注）/ **粗尖 B**（签名标题）/ **疾书尖**（速度主导，连笔出锋）/ **弹性尖**（0.3×~2.4× 大动态，花体练字）/ **毛笔锋**（双端长 taper，lettering）/ **美工尖**（tilt 角度控宽，立细侧粗）。每个变体只重写手感参数，保留用户颜色。
- `BrushPreset` 新增 `styleId` 字段（@SerializedName），`BrushPresetStore` 保存/读取（选中态持久化）。
- `BrushPropertiesPopup`：顶部新增「笔刷样式」横向滚动 chips（仅钢笔显示，其他工具保持 GONE）；点选即应用到当前预设并刷新线宽/不透明度滑杆与压感曲线单选，选中 chip 蓝框高亮，立即生效并持久化。

### 验证
- 全量单测 358 全绿；真机（OPPO Pad）装机回归 + 截图确认 chips 行显示/选中/切换。
- 待用户持笔验证：美工尖依赖 OPPO Pencil tilt 数据质量；弹性尖/毛笔锋大 maxWidth 在细格纸上若偏粗可降 baseWidth 保系数。

### 后续候选
- 变体覆盖到圆珠笔/铅笔工具（当前仅钢笔）；每个 chip 加一笔画预览缩略图。


## 2026-08-21 v1.1.9（versionCode 24）— 手笔分离：掌触/误触触发橡皮修复

### 用户反馈
- 写字时手掌压着屏幕，长按橡皮会被触发，把刚写的字擦掉；甚至手没碰屏幕时写字也会时不时跳成橡皮。要求：手能操作的和笔尖操作的严格分开。

### 根因（三个叠加）
1. **手掌加入不取消待发的长按计时**：笔先 DOWN（长按橡皮 500ms 倒计时启动）→ 手掌再压屏（ACTION_POINTER_DOWN）时，连续模式 `LongPressEraseTouchListener` 根本没有 POINTER_DOWN 分支（落到 default），单页模式 `handlePointerDown` 的 PAN_ONLY 分支提前 `return false` 也没移除 runnable → 倒计时到点触发橡皮，正在写的笔迹被取消、开擦。
2. **v1.1.7 把长按移动容差放宽到 3× slop（约 24dp）**：写小字/慢写时笔尖 500ms 内不越界 → 无掌触也会自发触发橡皮（「手没接触也会跳」）。
3. 连续模式擦除坐标固定读 pointer 0：手掌占据 pointer 0 时擦除坐标全错。

### 修复（对齐主流 App「笔=书写/擦除，手指=翻页/缩放」原则，默认本就是 PAN_ONLY）
- 连续模式监听器重写：按 **pointer id 跟踪手写笔**（不再写死 pointer 0）；`ACTION_POINTER_DOWN` 时若新手指是**手指**→立即取消长按计时（手掌永远无法触发）；若是**笔**（手掌先落、笔后来的正常书写姿势）→为笔自己的 pointer 武装长按，功能保留。POINTER_UP/UP/CANCEL 统一走 `finish()`（结束擦除+隐藏光标+解除武装）。
- 单页模式 PAN_ONLY 提前返回分支补 `removeCallbacks(stylusLongPressRunnable)`。
- 两种模式长按容差回到**系统标准 1× touch slop**（写小字不再误触；静止长按仍可靠，且有 v1.1.7 的橡皮光标做反馈）。

### 验证
- 全量单测 358 全绿；真机（OPPO Pad）装机回归无崩溃。掌触场景需用户持笔+压掌实测确认。


## 2026-08-21 v1.1.8（versionCode 23）— 笔刷手感第二轮（perfect-freehand 源码级对齐 + 1€ 滤波）

### 背景
- 用户：「稍微有点好用了，但还不算最好用，继续全网检索」。第二轮调研报告见 `docs/BRUSH_RESEARCH.md`「第二轮调研」章节（源码级证据：perfect-freehand 全文、Chromium 输入栈 1€ 参数、Google Ink 弹簧-质量模型）。

### 落地（报告清单 #1~#6）
1. **requestUnbufferedDispatch**：`InkView.onTouchEvent` 在笔尖 DOWN 时调用——跳过 MotionEvent 批处理，240Hz 采样真正逐点到达，感知延迟下降、速度估算更稳。
2. **起笔缓动修正**：`taperFactorStart` 由 t²（缓入、出头慢显「钝」）改为 perfect-freehand 默认的 **easeOutQuad `t*(2-t)`**——落墨即有、快速出头后渐缓。收笔 easeOutCubic 保持不变（与 pf 一致）。
3. **防粗头**：笔画前 8 个点的压力用 0.25 种子做递推滑动平均（`effectivePressure`，pf 的 FIRST_PRESSURE 技巧）——落笔瞬间必然慢+重压，不设防会出「粗头」。宽度/不透明度共用该压力。
4. **笔尾噪声剔除**：`buildMesh` 跳过距笔尾 < 2 页单位（≈3px）的非末位点——抬笔抖动不再产生毛刺。
5. **1€ 滤波替换 EMA**：`StrokeSmoother` 内部重写（接口不变，新增带真实时间戳的 `process(x,y,pressure,timestampMs,out)` 重载，`StrokeBuilder` 逐点传 MotionEvent/历史点真实时间）。位置：mincutoff 4.7→0.8Hz 映射 smoothing 滑杆、β=0.015（页单位）、dcutoff=1Hz；压力：单独 1€，mincutoff 2Hz、β=0.001。慢写强力去抖、快写近零滞后（α 随速度自适应）。smoothing=0 时位置/压力都保持原始直通（测试锁定该语义）。
6. **轮廓点去重**：`buildMesh` 中心点焊接距离从固定 0.05 改为 `max(0.05, width×0.5)`（pf 的 `(size×smoothing)²` 思路）——顶点密度随笔宽自适应，笔画边缘更干净、顶点数下降。

### 未做（报告 #7~#11，留后续按反馈决定）
- 铅笔宽度噪声+纸纹（BitmapShader+MULTIPLY）、完成笔迹等弧长 Catmull-Rom 重采样、ACTION_CANCEL 笔迹回滚、圆珠笔积墨点（默认关）、CanvasFrontBufferedRenderer 前缓冲（需 ColorOS 14 真机回归）。

### 验证
- 全量单测 358 全绿（1€ 收敛性/直通/拉线语义均兼容旧测试断言）。
- 真机（OPPO Pad）：装机回归无崩溃；手感需用户持笔确认。


## 2026-08-21 v1.1.7（versionCode 22）— 工具栏不再自动隐藏 + 长按橡皮修复三连

### 用户反馈
1. 顶部工具栏书写时自动隐藏，「半天没找到地方恢复」——要求永不自动隐藏。
2. 长按橡皮触发不稳定；擦除时屏幕上的笔记会闪动。
3. 长按进入擦除后没有橡皮图标，扫过就擦掉，没有安全感——要求长按时显示橡皮光标。

### 根因与修复
- **工具栏自动隐藏**：即「沉浸模式」（`EditorSettings.immersiveMode` 默认 true，书写时 `onWritingStateChanged` 隐藏 topBar/thumbnailBar）。改默认 false（更多菜单里的开关保留，用户可自愿开）；`SettingsStore` 加一次性迁移 `mig_immersive_off_v117`：首次加载强制写入 immersive=false，覆盖旧版本默认留下的 true。
- **擦除闪动**：`InkView.refreshCompletedLayer()` 旧实现先把旧位图 remove+recycle，再异步渲染新位图——每次擦除刷新（60ms 节流）页面笔迹先空白一帧再画回，肉眼即「闪」。改为**原子交换**：UI 线程对 `page.strokes` 做快照 → 计算线程渲染到**新位图** → 渲染完再 swap 进缓存并回收旧图；in-flight 期间的新刷新请求合并为一次跟渲染（`renderInFlight`/`renderPendingAgain`）；swap 后若发现 strokes 数量在渲染期间变化（如 undo 后立刻落笔）自动补一次渲染。初页加载路径 `ensurePageBitmap` 一并走同一条 requestRender。
- **长按触发不稳**：两种模式的长按移动容差都放宽到 **3× touch slop**（原单页 1×、连续 2×，笔尖静止微漂移就误取消）；连续模式 `onLongPressTimeout` 增加 View 回收防护（`inkView.getPage() != page` 的过期 runnable 直接丢弃，防止 RecyclerView 复用后旧回调打到错误页面）。
- **橡皮光标**：`InkView` 新增 `showEraserCursor(x, y, radiusPx)`/`hideEraserCursor()`——屏幕空间奶白半透明填充 + 灰描边圆圈，随缩放换算（page 半径 × viewport scale）。连续模式 `LongPressEraseTouchListener` 在长按触发、MOVE、抬笔三处驱动光标（`EraseCallback` 新增 `eraserRadiusPage()`，`EditorFragment` 返回 `eraserConfig.radius`）。单页模式此前已有 overlay 光标，不动。

### 验证
- 全量单测 358 全绿。
- 真机（OPPO Pad）：装机回归（书写/擦除/翻页无崩溃）；长按手感与光标需用户持笔确认。

### 备注
- 更多菜单「沉浸模式」开关仍保留；若用户要求彻底移除，删 `popup_more_menu.xml` 的 row_immersive + `onWritingStateChanged` 的隐藏分支即可。


## 2026-08-21 v1.1.6（versionCode 21）— 笔刷手感重做（钢笔感）

### 用户反馈
- 「笔刷不好用，没有钢笔的感觉」，要求上网调研好用的笔刷后改进。

### 调研
- 产出 `docs/BRUSH_RESEARCH.md`（对照 GoodNotes / Noteful / perfect-freehand / Google Ink 等）。核心结论：压感宽度动态范围太窄（0.65×~1.6×）、taper 线性且太短、速度→宽度是未平滑的线性映射、压力几乎没滤波。

### 本轮落地（P0+P1，报告中的 1€ 滤波/重采样/铅笔纹理留后续）
- `BrushPreset.fountainPen()`：minWidth 0.65×→0.4×、maxWidth 1.6×→1.9×、pressureCurve BALANCED→SOFT、velocityToWidth 0.15→0.4、taperStart 0.1→0.15、taperEnd 0.2→0.5。calligraphyPen 继承自动受益。
- `ballpoint()`：pressureToOpacity 0→0.15、velocityToWidth 0→0.12、taperEnd 0.1→0.08；`pencilHb()`：velocityToWidth +0.2。
- `BrushEngine`：速度改为 40ms 尾窗平均（`smoothedSpeed`，隐藏采样抖动）+ smoothstep 非线性映射（`t*t*(3-2t)`，因子上限从 0.5·v2w 提到 v2w）；taper 拆成起笔二次缓入（`taperFactorStart`，t²）和收笔 easeOutCubic（`taperFactorEnd`，1-(1-t)³），收笔长而渐尖。
- `BrushPresetStore`：预设 JSON 增加 `presetVersion=2`，旧版本条目加载时丢弃——否则老用户存过的旧参数会盖住新默认值，手感升级不生效（代价：用户自调过的笔参数重置一次）。
- 测试同步：`BrushEngineTest` 宽度区间断言改 0.35×~2.0×；`BrushPresetTest.fountainPen_pressureRangeMatchesRecommendation` 改 0.4×/1.6~2.0×。

### 验证
- 全量单测 358 全绿（首轮 core-model 旧断言失败，已同步）。
- 真机（OPPO Pad）：装机回归，书写无崩溃；手感需用户持笔确认。

### 后续候选（报告 P1，未做）
- StrokeSmoother 换 1€ 滤波（压力噪声→宽度毛刺）；输入重采样/贝塞尔插值；铅笔纸纹 texture 渲染。

## 2026-08-21 v1.1.5（versionCode 20）— 连续翻页模式支持手写笔长按橡皮

### 用户反馈
- 「笔尖长按屏幕没有转换成临时橡皮」。

### 根因
- 长按橡皮快捷键只在单页模式的 `EditorGestureController` 里实现；连续翻页模式每个页面 InkView 直接吃手写笔事件，根本没有这条捷径（旧注释还写着「连续模式橡皮/套索仅单页」）。用户当时正处于连续模式。

### 实现
- `PageContinuousAdapter` 新增 `EraseCallback` 接口 + 每页 `LongPressEraseTouchListener`：笔 DOWN 后 500ms 未移动（slop×2 阈值）→ 取消刚起笔的笔迹、临时进入擦除；MOVE 走历史采样转页坐标逐段擦除；抬笔提交一次 undo（ReplaceStrokes）。移动超过阈值则取消快捷键、正常书写不受干扰。
- `EditorFragment` 实现 callback（复用 eraserEngine/undoStack/onContentEdited），新增 `eraseTargetPage` 字段；`refreshInkNow` 在连续模式下刷新被擦页的 completed layer（沿用 60ms 节流）。
- v1.1.4 的 `ContinuousPageRecyclerView` 保证笔流不被列表抢去滚动，擦除轨迹不会触发翻页。

### 验证
- 358 单测全绿；真机回归：连续模式手指翻页正常、无崩溃（截图 v115_01）。长按橡皮的实际手感需用户持笔确认（adb 无法模拟手写笔）。
- 交付：debug + release 双包已刷新至 `artifacts/`（2026-08-21 22:46），SHA-256 见 `reports/APK_CHECKSUMS.txt`。

## 2026-08-21 v1.1.4（versionCode 19）— 修复连续翻页模式笔尖变翻页

### 用户反馈
- 切到连续翻页后，笔尖上下滑动变成翻页而不是写字，「很不丝滑」。

### 根因
- 连续模式的 `continuous_list` 是原生 RecyclerView，`onInterceptTouchEvent` 不区分输入工具，竖直拖动一律拦截为滚动——笔的竖画/撇捺自然被抢走。

### 修复
- 新增 `ContinuousPageRecyclerView`（feature-editor）：DOWN 时记录工具类型，整条手写笔/橡皮尾触摸流 `onInterceptTouchEvent` 一律返回 false（事件留给页面 InkView 书写），`onTouchEvent` 同样拒绝笔流滚动；手指流不受影响正常翻页。布局中替换原 RecyclerView 标签。
- 手指翻页、双指缩放（RV 内部不涉及，缩放在单页手势控制器）未动。

### 验证
- 358 单测全绿；真机（OPPO Pad）：连续模式下手指竖滑翻页正常、无崩溃（截图 v114_01）。adb 无法模拟手写笔工具类型，笔的实际书写效果需用户持笔确认。
- 交付：debug + release 双包已刷新至 `artifacts/`（2026-08-21 22:37），SHA-256 见 `reports/APK_CHECKSUMS.txt`。

## 2026-08-21 v1.1.3（versionCode 18）— 编辑器内搜索（笔记内搜索）落地

### 背景
- 编辑器工具栏 🔍 此前只 toast「该功能即将上线」（aux_tool_coming_soon），用户点名要求开发。

### 实现
- **UI**：`fragment_editor.xml` 新增 `search_panel`——顶栏下方奶白卡片（bg_popup），搜索图标 + 输入框 + 清空 × + 收起 ⌃，结果列表 `constrainedHeight` max 400dp，空态提示文案。
- **查询**：复用 feature-search 的 `SearchQueryBuilder`（FTS4 查询构造）+ `SearchResultMerger`（评分/片段/高亮）；`EditorServices` 新增 `database()` 访问口；查 `IndexDao.search` 后**按当前 documentId 过滤且丢弃文档级段落（file_name/tag，pageId 为空）**，只留可跳页的命中。300ms 防抖 + generation 丢弃过期结果（与全局搜索同模式）。
- **跳页**：点结果按 pageId 定位页码调 `goToPage`（单页/连续模式都支持），命中带 boundingBox 时单页模式下 `overlayView.flashRect` 闪烁高亮命中区域（连续模式 overlay 跨整容器，跳过闪烁）；点后收键盘、面板保持打开便于连续看结果。
- 单测 358 全绿（无新增单测——纯 UI 接线，逻辑全在已被测的 merger/builder）。

### 真机验证（OPPO Pad，截图 v113_01–04）
- 01：输入 IndexProbe 命中文本框，片段高亮正常；02：先切第 2 页再点结果，跳回第 1 页且键盘收起；03：无结果提示「未找到…」；04：收起按钮正常关面板，logcat 无崩溃。

### 备注
- 手写笔迹本身没有 OCR，搜不到手写内容属预期（索引源：文本框/页面标题/PDF/Office 文字/语音转写）。

## 2026-08-21 v1.1.2（versionCode 17）— 移除手写笔双击切换橡皮手势

### 用户反馈
- 双击笔/屏幕切换橡皮（及双击切回钢笔）误触发严重：「随便动两下就触发到了」。用户要求**完全取消**双击切换，保留长按橡皮快捷键。

### 改动
- `EditorGestureController`：移除 ERASER 分支和笔工具分支两处双击检测、`handleUp` 中两处 tap 记录，删除字段 `lastStylusTapTime/X/Y`、`stylusDownTime`、`eraseDownTime`、`eraseIsStylus`，Host 接口删除 `onStylusDoubleTap()`。**保留**：手写笔长按橡皮（stylusLongPressRunnable）、多指轻拍 undo/redo 的 `TAP_WINDOW_MS`（误删过一次，该常量是多指轻拍共用，已恢复）。
- `EditorFragment`：删除 `onStylusDoubleTap` 实现、`removeFreshTapDot`（仅服务双击）、字段 `toolBeforeDoubleTap`/`lastCommittedStroke`/`lastCommittedPage`。
- `ObjectToolController` 的文本框双击编辑（`DOUBLE_TAP_MS`）是另一码事，**未动**。

### 验证
- 358 单测全绿；debug 包装机后编辑器打开正常、内容完整，连续 4 次快速点屏幕工具保持钢笔不切换、无崩溃（截图 v112_01/02）。手写笔长按橡皮快捷键代码路径未动，保留。
- 交付：debug + release 双包已刷新至 `artifacts/`（2026-08-21 22:21），SHA-256 见 `reports/APK_CHECKSUMS.txt`。

## 2026-08-21 v1.1.1（versionCode 16）— iPad 四软件性能调研 + 缓存压力收缩集成

### 本轮做了什么
- 用户要求调研 iPad 笔记软件（参考图已丢失，按上下文锁定 GoodNotes / Notability / Noteshelf / ZoomNotes；微软 OneNote 用户明确排除）的多线程并发/缓存/性能技术，出报告后集成。4 个调研 agent 并行检索，报告落盘 `docs/PERFORMANCE_RESEARCH.md`（全部带来源 URL，传闻已标注）。
- **调研结论要点**：我们的架构已具备行业主流手段——MotionEvent 历史采样消费（StrokeBuilder）、触控轨迹预测（MotionPredictor 已接线）、脏矩形局部刷新、字节预算 LRU（PDF/Office 底图 + 缩略图）、journal 增量落盘、索引后台化。
- **识别出的真实缺口**：无 onTrimMemory 内存压力响应（Nutrient 报告：Android 14/15 后 TRIM_MEMORY_RUNNING_CRITICAL 不再可靠投递，须全级别自建处理）。
- **集成（v1.1.1）**：core-common 新增 `CacheTrimRegistry`（纯 Java 可单测，不用 AppLogger——其依赖 android.util.Log 会在 JVM 测试炸"not mocked"）；`BackgroundRenderCache.trimToFraction`；Pdf/Office 两个 Provider 构造注册、release 反注册；ThumbnailCache 注册（LibraryFragment.onDestroyView 反注册）；`InkNoteApp.onTrimMemory` 级别→保留比例映射（UI_HIDDEN 50% / LOW·BACKGROUND 25% / CRITICAL·MODERATE 10% / COMPLETE 清空）。新增 CacheTrimRegistryTest 5 例，全套 358 单测通过。
- **真机回归（OPPO Pad）**：`am send-trim-memory` 注入 RUNNING_LOW 无崩溃、编辑器内容完整（截图 68）。注意：前台进程只能注入 RUNNING_* 级别，注入 BACKGROUND/COMPLETE 会被 AMS 拒绝（"Unable to set a background trim level on a foreground process"），属正常。
- 未集成项（写入报告"后续规划"）：graphics-core 前缓冲渲染（wet/dry 双层，需 InkView 迁 SurfaceView，改动大）、LOD 屏幕像素剔除（ZoomNotes View Filtering）、PDF 底图磁盘二级缓存。

### 交付
- v1.1.1 debug + release 双包已刷新至 `artifacts/`（2026-08-21 09:46），SHA-256 与签名指纹已更新至 `reports/APK_CHECKSUMS.txt`。

### 环境备忘
- adb 模拟内存压力：`am send-trim-memory <pkg> RUNNING_LOW`（前台进程只能用 RUNNING_* 级别）。

## 2026-08-21 v1.1.0（versionCode 15）— 主页奶白高级感 UI 大版本更新

### 用户反馈
主页 UI 太 low：「+」新建/导入菜单图标简陋、左上角三横线弹窗是系统灰框、排序菜单同样、搜索框/顶栏不够精致。要求全部按"奶白色高级感"重做，且只在 OPPO Pad（2d64f765）上测试，不碰一加 Ace5。

### 改了什么（feature-library 为主）
- **「+」新建/导入菜单**：12 个条目全部换成 iPad 风格瓷贴图标——柔和马卡龙色圆角瓷贴（`bg_ni_tile_*.xml` 12 个）+ 同色系加深图标（`ni_tile_*`/`ni_glyph_*` 配色对，见 feature-library colors.xml）；行布局从 TextView drawableStart 改为 瓷贴 ImageView + 文字（行 ID 不变，Java 零改动）。
- **修了一个引入的 bug**：行变高后 12 项超出屏幕且不可滚动 → bottomsheet_new_item.xml 内容包进 NestedScrollView（真机复验 12 项全部可见可滑）。
- **左上角三横线导航弹窗**：系统灰 PopupMenu → 奶白自定义 PopupWindow（`popup_library_nav.xml`，资料库标题 + 5 项带图标行 + 垃圾箱分隔线），宽 232dp、elevation 16dp、点外消失。
- **排序弹窗**：同样换奶白 PopupWindow（`popup_library_sort.xml`），当前排序项带蓝色对勾。
- **「+」按钮**：蓝色实心圆 → 圆角方形蓝渐变（#4A82FF→#2F6BFF）+ 白色按压涟漪。
- **全局背景**：`app_background` #F6F7F9（冷灰）→ **#F8F6F2（暖奶白）**，app/feature-library/feature-editor/feature-search 四模块同步；搜索栏圆角加大到 24dp。
- LibraryFragment 新增 `dp()` 辅助；`showNavPopup`/`showSortMenu` 重写为 PopupWindow（文档卡片 ⋮ 的 PopupMenu 未动，用户未点名）。

### 真机验证（截图 58–67）
主页新背景+渐变加号（58）、「+」菜单瓷贴图标全量可见可滚动（59–61）、导航奶白弹窗（62）、排序奶白弹窗带对勾且排序生效（64/65）、列表/网格视图切换（66）、编辑器在新配色下正常（67）。

### 接下来可做
- 文档卡片 ⋮ 上下文菜单仍是系统 PopupMenu，如用户提出再统一成奶白弹窗。
- 文本框编辑/重命名 AlertDialog 系统直角风格未动（同前）。

## 2026-08-21 v1.0.13（versionCode 14）— 真机全功能实测 + 4 个缺陷修复

### 本轮做了什么
- 应用户要求，adb 直连 OPPO Pad（`2d64f765`）做全功能实测（截图存 `reports/device-test/`，共 54 张）。
- 实测中发现并修复 4 个真机缺陷（全部编译 + 353 单测通过 + 真机复验）：
  1. **v1.0.10**：更多菜单弹窗撑满全屏 → 根因是 `PopupMoreMenuBinding.inflate(inflater)` 无 parent 丢根布局宽度，`PopupWindow` 显式 `dp(236)`。
  2. **v1.0.11**：连续翻页模式必崩溃 → `PageContinuousAdapter.onBindViewHolder` 里 `holder.itemView.getParent()` bind 阶段为 null，NPE；判空 + post 内再判空。logcat 显示该崩溃此前已反复发生（用户从未成功用过连续模式）。
  3. **v1.0.12**：文本框/贴纸/胶带插入后全部不显示 → `InkView.onDraw` 从未调 `renderer.drawObjects`；补上对象层绘制（对象可变，不烘焙进 bitmap）。
  4. **v1.0.13**：搜索索引未接线 → `indexTextBox`/`indexPageTitle`/`indexFileName` 定义了无人调；EditorServices 懒加载 SearchIndexScheduler，文本框确定/重命名/页面标题/新建笔记处全部接上（feature-editor、feature-library 各加 `implementation project(":feature-search")`，无循环依赖）。
- 实测通过：书写四笔刷+压感、undo/redo、橡皮、套索+选区+操作条、杀进程重开笔迹保留、笔刷弹窗改色/线宽/硬朗曲线、连续模式全流程、横线模板、文本框/贴纸插入显示、缩略图跳页、重命名、文本框+标题全局搜索命中与跳转。
- 性能实测：120Hz 生效；PSS ≈92.5MB；gfxinfo 50 分位 6ms / 90 分位 18ms（全程累计含弹窗滚动）。
- 交付：debug + release 双包刷新到 `artifacts/`，SHA-256 与签名指纹写入 `reports/APK_CHECKSUMS.txt`；`docs/COLOROS14_DEVICE_ACCEPTANCE.md` 更新为"已真机验证"。

### 发现的问题（非本应用）
- 设备上 `com.leslie.music` 会周期性抢前台，打断自动化操作。
- ColorOS 禁止 shell 写 `Settings.System`（无法 adb 强制旋转屏幕）；安装确认页拦截模拟点击；锁屏 4 位 PIN 无法 adb 解锁。

### 接下来要做 / 待用户实测
- 息屏唤醒亮屏截图、后台/前台切换、录音权限弹窗：等用户在平板上手动解锁一次后补测（已向用户提问）。
- 主动式压感笔的压感/倾斜/侧键/掌触拒绝：adb 无法模拟，需用户持笔实测。
- 可选 UI 统一：文本框编辑/重命名的 AlertDialog 仍是系统直角风格，与奶白高级感弹窗不一致（用户未点名，未擅改）。
- 中文 OCR / 手写转文字、500 页大 PDF 压力测试：需中文样本/大文件，未做。

### 重要环境备忘（复用经验）
- 构建：`cd 项目根 && source toolchain/env.sh && cd project && gradle ...`。
- adb 操作必须 `-s 2d64f765`（另一台 `92f73a16` 是一加手机，勿碰）；每条 adb 前 `export MSYS2_ARG_CONV_EXCL='*'`；`adb install` 用 Windows 路径。
- `input text` 不支持中文；弹键盘后对话框上移，确定按钮坐标须现 dump 现取。
- 弹窗触发：先点一次选中工具，再点同一图标（间隔 ≥0.6s）。
- **收尾待办：shared_prefs 的 finger_mode 被改成 DRAW 方便测试，全部测试结束后须改回 PAN_ONLY 并 force-stop。**

## 2026-08-21 — 语音转文字（Vosk 离线引擎）真功能落地

### 本轮做了什么
- **Vosk 离线引擎接入**（feature-audio）：依赖 `com.alphacephei:vosk-android:0.3.75`（Maven Central 最新，2025-12-08 发布，传递依赖 `net.java.dev.jna:jna:5.18.1@aar`）。
  - `VoskModelManager`：模型 `vosk-model-small-cn-0.22`（42MB）按需下载（HttpURLConnection+进度+中文错误）→ zip-slip 防护解压到 `filesDir/models/` → 结构性校验。下载传输可注入（`ZipDownloader`），状态机 NOT_INSTALLED/DOWNLOADING/EXTRACTING/READY/ERROR。
  - `PcmResampler`（纯 Java）：立体声→单声道、任意采样率→16kHz 线性插值、PCM16 字节↔short 转换。
  - `VoskTranscriptionEngine`（文件转写）：注入 `AudioDecoder`（生产 `MediaCodecAudioDecoder`）+ `RecognizerFactory`（生产 `VoskAndroidBridge`，Model 只加载一次并缓存），汇总 finalResult 成 `TranscriptSegment`，进度按解码 PTS 百分比，支持取消。
  - 实时识别：`LiveTranscriptionSession`（纯 Java，partial/final 累计）+ `MicCapture`（AudioRecord 16kHz，暂停/恢复）+ `VoskLiveTranscriber`（后台线程编排、回调切主线程、状态机 IDLE/LOADING/RECORDING/PAUSED/FINISHING/FAILED）。
- **录音「转文字」接真引擎**：`EditorServices.speechTranscription` 注入换成 `VoskSpeechTranscriptionGateway`（一行构造替换；`UnavailableSpeechTranscriptionGateway` 保留作占位/测试）。模型未下载时点「转文字」弹奶白下载进度卡（`SpeechModelDownloadDialog`），完成后自动开始转写。后台单线程 executor 执行，回调主线程。
- **「+ → 语音转文字」入口**：ObjectToolController 新增 `case SPEECH_TO_TEXT`（未碰 OCR/HANDWRITING_TO_TEXT 两个并行任务的 case）→ `SpeechToTextController` 奶白卡片：状态区（未开始/录音中/已暂停/识别中/识别完成）+ 实时识别文本滚动区 + 开始/暂停继续/停止；停止后「插入为文本框」（走 undo 路径 `InkCommands.AddObject`，视口中上部放置，自动行高）、「复制」（剪贴板）、「丢弃」。模型未下载先走下载进度卡。
- **权限**：EditorFragment 新增独立 `speechMicPermission` launcher + `requestSpeechMicPermission(callback)`（不能复用录音的 launcher——`RecordingController.onPermissionResult(true)` 会自动开始录音）。EditorFragment 其他区域未动。
- **R8**：app/proguard-rules.pro 加 `-keep org.vosk.**`、`-keep com.sun.jna.**` 等。
- 文案全中文；弹窗复用 `@drawable/bg_popup` 奶白卡片风格。

### 验证
- `gradle testDebugUnitTest`：423 个单测全绿（新增 29 个：VoskModelManagerTest 7、PcmResamplerTest 7、VoskJsonTest 5、LiveTranscriptionSessionTest 4、VoskTranscriptionEngineTest 6）。
- `gradle :app:assembleDebug` 通过；APK 内确认打入 libvosk.so / libjnidispatch.so。
- 单元测试发现的问题均已修复：AppLogger 走 android.util.Log 在 JVM 单测崩（VoskModelManager 错误路径移除日志依赖）、Recognizer 构造抛 IOException、resample 尾端点预期。

### 遗留风险 / 真机待验证
- **必须真机验证**：① 国内网络下载 42MB 模型的成功率与速度（失败有重试入口，也可 `adb push` 预置 `filesDir/models/vosk-model-small-cn-0.22/` 整个目录，结构校验不依赖标记文件）；② vosk small-cn 对课堂/会议口语的识别质量（WER 官方 17–24，若不够按调研报告升 sherpa-onnx + Paraformer）；③ 模型加载内存（~300MB）在 OPPO Pad 上的表现；④ 长录音转写耗时（实时倍率）。
- 「转文字」目前只转录首个分段（沿用既有对话框行为）；多段连转待后续。
- 识别结果不含标点（Vosk small-cn 无标点模型）；升级 sherpa-onnx 可解决。
- release 包未重新出（app/build.gradle 版本号未动，27/1.3.0）。
