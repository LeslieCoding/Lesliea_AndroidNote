# OCR 与手写识别（OCR）

## OCR（spec 14）
- 引擎：ML Kit Text Recognition v2 中文模型（离线，模型由系统按需下发）。
- 决策流（14.2，「准确而不是随便识别」）：原生文本 ≥16 字符直接用文本层 → 无/少文本才渲染页图 → 预处理（长边 2048 缩放、灰度对比度增强、投影方差 deskew ±10°、过暗/过曝质量标记）→ OCR → 置信度检查（<0.5 标 lowConfidence，垃圾行过滤）→ 坐标映射回原图 → 增量写入搜索索引。
- 结果结构：blocks/lines/elements 各带 text + bbox + confidence；可复制、可在原图定位、可搜索。
- 无 GMS 设备：返回 ENGINE_UNAVAILABLE 明确提示，不伪造。
- 执行：全部后台线程，绝不抢墨迹线程（AppExecutors background 单线程低优先级）。

## 手写识别（spec 15）
- 引擎：ML Kit Digital Ink Recognition（zh-Hani 默认，模型可下载/删除/查状态）。
- 套索选择 → 转文字 → 预览 → 用户选择：复制 / 替换原手写 / 插入文本框 / 取消。**默认不破坏原始手写**。
- 手写搜索：`InkGroupingUtil` 按时间+空间邻近把 stroke 分行/块 → 后台识别 → `HandwritingSearchIndexer` 生成索引条目（页 + 区域 + strokeIds）→ 全局搜索命中后跳转高亮。
- 分组阈值：行内垂直中心距 < 0.6×笔画中位高度、时间间隔、水平间隙三条件。
