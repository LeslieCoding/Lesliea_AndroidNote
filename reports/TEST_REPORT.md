# 测试报告 — Leslie ColorOSNote

构建环境：JDK 17.0.20 / Gradle 8.9 / AGP（compileSdk 34, minSdk 28, targetSdk 34）。
测试类型：JVM 单元测试（JUnit4），命令 `gradle testDebugUnitTest`。

## 汇总

| 指标 | 值 |
|---|---|
| 模块数（含测试） | 17 |
| 测试用例总数 | 353 |
| 失败 | 0 |
| 错误 | 0 |

## 分模块明细

| 模块 | 用例数 | 失败 | 错误 |
|---|---|---|---|
| core-common | 18 | 0 | 0 |
| core-ink | 45 | 0 | 0 |
| core-model | 16 | 0 | 0 |
| core-render | 20 | 0 | 0 |
| core-search | 14 | 0 | 0 |
| core-security | 11 | 0 | 0 |
| core-storage | 31 | 0 | 0 |
| document-caj | 19 | 0 | 0 |
| document-office | 15 | 0 | 0 |
| document-pdf | 11 | 0 | 0 |
| feature-audio | 12 | 0 | 0 |
| feature-editor | 36 | 0 | 0 |
| feature-export | 13 | 0 | 0 |
| feature-handwriting | 18 | 0 | 0 |
| feature-import | 36 | 0 | 0 |
| feature-ocr | 17 | 0 | 0 |
| feature-search | 21 | 0 | 0 |

## 覆盖要点

- **墨迹引擎**：压力曲线（含自定义曲线）、自适应压感校准（压缩量程扩展/硬压不截断/跨笔迹存活）、速度模拟压感降级、平滑/插值、笔刷网格化、橡皮（局部/整笔）、套索、undo/redo 命令栈。
- **存储**：Room 16 实体 + FTS4、.inknote 容器读写、journal 增量、垃圾箱 15 天、文档仓库 CRUD、extras（resources/audio 等）整存保留与 putResource 合并落盘、duplicate 携带 extras。
- **文档导入**：12 阶段流水线（沙盒复制逐字节校验、超限中止、格式探测、CAJ→PDF 链路、取消语义、半成品清理）、格式分派表、中文类型化错误。
- **搜索**：中文归一化、bigram、多源结果合并排序、索引调度。
- **OCR/手写**：预处理、计划决策（native 文本足够则跳过）、置信度分级、引擎失败降级。
- **导出**：内容过滤（墨迹/荧光笔/贴纸开关）、页码范围、PDF/PNG/TXT/MD/.inknote 路由、备份 ZIP。
- **底图渲染**：缓存键量化、LRU 字节预算淘汰、页码边界、失败重试；ImportedDocument JSON codec round-trip（office.json 容器 extras），Office/PDF 导入底图 provider 接线。
- **对象工具**：贴纸几何生成（100×100 归一化图元）、胶带 RDP 抽稀/包围盒/命中、对象 9-float 逆变换命中（zIndex 取顶）、置顶置底、最近贴纸 LRU、对象编辑命令快照 undo。

## 备注

- Bitmap/Canvas 等 Android 框架路径不在 JVM 单测覆盖范围，按惯例在真机层抽查（见 docs/TEST_PLAN.md 与 COLOROS14_DEVICE_ACCEPTANCE.md）。
- 数字为末次全量 `gradle testDebugUnitTest`（BUILD SUCCESSFUL，含导入底图接线修复、自适应压感校准与编辑器工具栏合并改造）实测。

---

# 真机实测报告（OPPO Pad，ColorOS 14）

测试设备：OPPO Pad（adb 序列号 `2d64f765`），屏幕 1600×2560 @120Hz，ColorOS 14 / Android 14。
被测版本：v1.0.9 → v1.0.13（versionCode 14）。同网段另一台一加 Ace5（`92f73a16`）未参与测试。
测试方式：adb 模拟输入全功能遍历 + 截图留证（截图存 `reports/device-test/*.png`）。

## 实测中修复的缺陷（按版本）

| 版本 | 缺陷 | 根因 | 修复 |
|---|---|---|---|
| v1.0.9 | 套索选区空点页面后选区不消失 | 点击未命中对象时未清空选区状态 | 空点清除选区并刷新 |
| v1.0.10 | "更多"菜单弹窗被撑满全屏 | `PopupMoreMenuBinding.inflate(inflater)` 无 parent，根布局 224dp 宽度丢失，weight 行撑满 | `PopupWindow` 显式给定 `dp(236)` 宽度 |
| v1.0.11 | 连续翻页模式必崩溃（logcat 显示此前多次复现） | `PageContinuousAdapter.onBindViewHolder` 中 `holder.itemView.getParent()` 在 bind 阶段为 null 导致 NPE | parent 判空，空则走 post 且 post 内再判空 |
| v1.0.12 | 文本框/贴纸/胶带等对象插入后全部不显示（数据已保存） | `InkView.onDraw` 从未调用 `renderer.drawObjects` | completed bitmap 之后补画对象层（对象可变，不烘焙进 bitmap） |
| v1.0.13 | 搜索索引未接线：`indexTextBox`/`indexPageTitle`/`indexFileName` 定义了但无人调用，导致搜不到文本框内容与重命名标题 | 接线缺失 | EditorServices 懒加载 SearchIndexScheduler；文本框确定/重命名/页面标题/新建笔记处全部接上 |

## 实测通过项（截图编号）

- 主页新 UI、新建笔记（01–03）；四种笔刷书写与压感（04–08）；undo/redo（09/10）；橡皮（11）
- 套索圈选 + 选区 + 操作条（12–14）；杀进程重开笔迹保留（16）
- 笔刷弹窗改色/线宽/硬朗曲线生效（20–22）；橡皮弹窗（23）
- 更多菜单修复后宽度正常（27）；连续翻页模式：切换/加页/滚动/切回单页（28–31）
- 页面模板：横线（32/33）；插入弹窗（34）；文本框插入并显示（35–40）；贴纸放置（41/42）；缩略图跳页（43）
- 重命名文档生效并同步标签页（45–47）
- 搜索：文本框内容 "IndexProbe" 命中（49/50）、标题 "SearchDemo" 命中（52b）、点结果跳转到编辑器对应页面（51）
- 息屏/唤醒：亮屏实测通过——息屏 4s 后唤醒直接回到编辑器，墨迹/文本框/贴纸完整（截图 55/56）；后台（回桌面）→ 再启动回到资料库，文档完整（57）

## 性能实测数据

- 屏幕：1600×2560，**120Hz 模式生效**（mActiveSfDisplayMode fps=120.00001）
- 内存：`dumpsys meminfo` TOTAL PSS ≈ **92.5 MB**（RSS 188 MB），Native Heap ≈ 10.8 MB
- 帧率（`dumpsys gfxinfo`，全程 2173 帧累计，含滚动/弹窗/书写）：
  - 50 分位 6ms，90 分位 18ms，95 分位 21ms，99 分位 117ms
  - Janky frames 19.14%（含大量弹窗/列表滚动帧；书写帧占比较少）

## 环境限制说明（非应用缺陷）

- ColorOS 禁止 shell 写 `Settings.System`（无 WRITE_SETTINGS），无法 adb 强制旋转屏幕；应用两个 Activity 均已声明 `configChanges="orientation|screenSize|..."`，旋转不重建、不丢状态，需物理旋转抽查。
- ColorOS 安装确认页（com.oplus.appdetail InstallGuideActivity）会拦截模拟点击，属系统安全限制，需用户手动点一次"继续安装"。
- 设备上 `com.leslie.music` 会周期性抢前台，与本应用无关。

## v1.1.0 主页 UI 大版本更新（2026-08-21 上午）

- 暖奶白全局背景（#F8F6F2，四模块同步）；「+」蓝渐变圆角按钮；搜索栏加大圆角。
- 「+」新建/导入菜单 12 项全部换成马卡龙瓷贴图标；修复行变高后不可滚动问题（NestedScrollView）。
- 三横线导航、排序弹窗从系统灰 PopupMenu 换成奶白自定义 PopupWindow（排序带当前项对勾）。
- 真机逐项点验截图 58–67：主页/「+」菜单/导航弹窗/排序弹窗/列表网格切换/编辑器均通过。
