# 已知限制（KNOWN_LIMITATIONS）

> 诚实清单。以下限制均不影响核心手写笔记体验，且均非伪实现。

## 硬件/系统相关
- **压感依赖硬件链**：平板 + 触控笔 + 驱动都提供 pressure 时才是真实压感；否则 UI 显示「模拟压感」（速度映射），不伪装。
- **自适应压感校准**：真实压感按会话自适应归一化（跟踪缓慢衰减的压力峰值，约 2s 半衰期），解决部分手写笔日常书写仅落在低压区间、粗细变化不明显的问题；用力按压即时顶到峰值，不被截断。
- **ML Kit 依赖 Google Play 服务下发模型**：无 GMS 的 ColorOS 设备上 OCR/手写识别返回 `ENGINE_UNAVAILABLE` 明确提示，核心笔记功能不受影响。
- **语音转写**：whisper.cpp JNI 桥未内置（需 NDK 构建的模型与库，体积大）；模型管理（下载/校验/删除）已实现，无模型时返回 `MODEL_NOT_INSTALLED`。可插拔接口已留好。

## 文档格式
- **.ppt（OLE2 老格式）不支持设备端导入**：Java 生态唯一解析路径 POI HSLF 强依赖 java.awt（Android 无 AWT）。提示用户转为 .pptx/PDF。.doc/.xls（POI HWPF/HSSF，无 AWT）支持。
- **Office 转换是「高保真近似」非 100%**：Word 用启发式流式排版（CJK 全角字宽、表格网格、图片等比）；PPT 不解析母版继承与主题色映射（记 warning）；Excel 按打印网格分页。商业 SDK adapter 接入点已预留（LicenseProvider）。
- **CAJ**：仅支持「实为 PDF」或「内嵌完整 PDF」的 CAJ；其他变体明确报 UNSUPPORTED 并引导外部转换。插件接口已留。
- **PDF 文本提取**：系统 PdfRenderer 无文本层 API，`extractText` 返回空并标注状态未知；搜索 PDF 文本依赖 OCR 路径。接口为未来商业引擎预留。
- **PDF 导出为 flattened**（批注合成进页面）；native 可编辑批注导出待商业 SDK。

## 性能
- 铅笔逐点透明度在当前单次 Path 填充中近似为笔画平均透明度（可升级顶点色绘制）。
- 超长笔画起笔 taper 距离计算为 O(n²)（课堂笔画规模无碍，已注释优化点）。

## 其他
- 云同步/云备份：第一版不做（本地备份已实现）。
- Dark mode：第一版白色主题优先。
- 多窗口复杂多 Activity 编辑器：不做（应用内多 tab + 系统分屏支持）。
- **搜索跳转粒度**：全局搜索命中后打开目标文档并跳转到命中页，命中区域在页面上高亮闪烁约 1.4s（spec 22.3）；连续滚动模式下跳转页码但不画高亮框。
- **Office 导入缩略图**：已接入 Office/PDF 底图 provider（含缩略图与连续滚动）；本次修复**之前**导入的 Office 文档容器中没有 office.json，打开仍是空白页，重新导入即可。

## 对象工具（Phase 5）
- **连续滚动/缩略图场景**：`PageContinuousAdapter` 与 `ThumbnailRenderer` 的 PageRenderer 未注册图片/贴纸 painter，这两处图片贴纸不渲染（胶带/文本/形状正常）。
- **文本框双击编辑**：仅在文本框工具激活状态下生效；笔工具下双击不触发（避免侵入笔书写管线）。
- **图片操作菜单**：替换图片/裁剪/系统剪贴板复制粘贴未做；当前覆盖透明度（25–100%）/置顶置底/锁定/删除。
- **图片崩溃窗口**：插入图片经 putResource 暂存、2s 防抖整存落盘；暂存后落盘前杀进程会丢图（与文档编辑同一崩溃窗口）。
- **重开全量解码**：`loadImageResources` 重开时全量解码 `resources/` 条目，导入的大图背景无 2048px 约束；`EditorBitmapRegistry` 32 条 LRU 兜底。
