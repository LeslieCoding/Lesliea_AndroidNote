# 文档导入（DOCUMENT_IMPORT）

## 统一流水线（spec 36）
`ImportJob` 状态机：Selected → Validate → Copy to sandbox → Detect format → Extract metadata → Convert → Generate thumbnails → Extract text → OCR → Create document → Index → Commit。进度 0-100、可取消、失败 retry；失败删除临时产物、不留半成品；类型化错误带中文用户提示 + AppLogger 诊断日志。

## PDF（spec 11，第一优先级）
- `PdfEngine` 接口与批注层解耦；默认实现 `AndroidPdfEngine`（系统 PdfRenderer）：内部锁串行化、白底、旋转支持、32MB LRU 页位图缓存（Bitmap 所有权归引擎，驱逐时 recycle）。
- 缩略图 `PdfThumbnailer`：~200px 宽、内存 LRU + 磁盘 LRU（16MB，tmp+rename 原子写）。
- 文本提取：PdfRenderer 无文本 API → 返回空并标注未知（接口为未来引擎预留）；扫描 PDF 走 OCR。
- 导出 `PdfExportWriter`：PdfDocument + PagePainter 回调（底稿 + 矢量批注合成），DPI 36-600、页面范围、进度、协作取消、tmp+rename 原子落盘。Flatten 导出，非截图糊弄。

## Office（spec 12）
- 抽象 `OfficeImportEngine` + `OfficeEngineRegistry`；商业 SDK 以 adapter 接入，`LicenseProvider` 读安全配置（无硬编码许可证，缺失→DEV_MODE）。
- 内置 `PoiOfficeEngine`：magic 嗅探（PK→OOXML / D0CF→OLE2）。
  - **Word**（docx XWPF / doc HWPF）：段落样式/表格/图片/分页符 → A4 启发式流式排版（CJK 全角字宽换行）→ 矢量绘制指令 + 文本层。
  - **PPT**（pptx）：自研 OOXML XML 解析（POI XSLF 绑定 java.awt，Android 不可用）——sldSz 页尺寸、xfrm 位置（EMU→px）、文本样式、solidFill 颜色、图片 rels 映射；一 slide 一页保宽高比；母版继承/主题色记 warning。**老 .ppt 明确不支持**（POI HSLF 同样绑定 AWT），提示转 .pptx/PDF。
  - **Excel**（xlsx/xls，WorkbookFactory）：列宽行高换算、列分块（超宽独占）+ 行分页、多 sheet，绝不整表 raster。
- 安全：不执行宏（POI 无执行引擎）、只读、大小/条目/超时/取消护栏、损坏文件明确报错。

## CAJ（spec 13）
插件式 `CajImportEngine`；默认实现：magic 探测（绝不只看后缀）→ 真 PDF 直接用 → 扫描前 32MB 找内嵌 %PDF…%%EOF 完整区间则原子提取 → 其他变体 UNSUPPORTED_CAJ_VARIANT + 引导外部转换。`ImportOutcome.success()` 强制校验输出文件真实存在，杜绝伪造成功。

## 图片
JPG/PNG/WEBP/GIF 经 SAF/相册/相机导入为单页 IMAGE 底稿文档；大图 downsample 预览，导出用原图。
