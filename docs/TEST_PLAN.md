# 测试计划（TEST_PLAN）

> 对应需求 43 节。状态列：✅ 已实现并通过 / 🟡 已实现待验 / ⬜ 待实现。

## 1. 单元测试（JVM，JUnit4）

| 模块 | 覆盖点 | 状态 |
|---|---|---|
| core-common | Geometry 相交/包含/距离/Catmull-Rom；SpatialIndex 四叉树增删查/分裂 | ✅ |
| core-model | StrokePoint 二进制编解码往返、坏版本拒绝；BrushPreset 默认预设范围；Stroke bounds/transform | ✅ |
| core-search | 中文归一化（全半角/大小写/空白）、CJK bigram、混合分词、相关性排序（低置信降权） | ✅ |
| core-security | magic number 探测、扩展名一致性、导入大小上限 | ✅ |
| core-storage | 垃圾箱 15 天保留策略、journal 去重、ZIP 容器读写、schema migration | 🟡 开发中 |
| core-ink | 压力曲线单调性、平滑收敛、笔刷宽度、橡皮几何切割、套索命中、undo/redo 事务 | ⬜ |
| document-office | POI 生成 docx/xlsx/pptx → 分页排版 → 文本提取/图片落盘 | 🟡 开发中 |
| document-caj | magic 探测、内嵌 PDF 提取 | 🟡 开发中 |
| feature-audio | 录音状态机、分段、墨迹-音频时间轴映射、转写导出格式 | ⬜ |
| feature-ocr/handwriting | 决策流分支、低置信过滤、手写分组、索引序列化 | 🟡 开发中 |

运行方式：`source toolchain/env.sh && cd project && gradle testDebugUnitTest`

## 2. Instrumentation（真机/模拟器）

- KeystoreSecretStore 加解密往返（Android Keystore 只在设备上可用）
- Room 数据库创建/迁移/外键
- 新建笔记 → 书写 → 强杀进程 → 重启恢复（journal replay，无重复 stroke）
- 导入中断恢复（不留半成品）
- MediaRecorder 分段录音 10 分钟稳定性

## 3. 性能基准（需求 43.4）

- stylus event → 首像素渲染延迟（目标 60Hz p95 <20ms）
- 单页 1000 stroke 滚动帧率
- 500 页 PDF 滚动内存曲线
- OCR 并发不拖慢书写线程
- 10 分钟连续书写内存稳定性

## 4. 崩溃恢复矩阵（需求 43.5）

书写中强杀 / 导入中强杀 / 导出中强杀 / 数据库事务中断 / 磁盘满 / 权限撤销。
