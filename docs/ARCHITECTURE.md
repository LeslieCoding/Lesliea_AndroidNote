# Leslie ColorOSNote — 架构说明

> 基线需求：`../Enterprise_Android_Tablet_InkNote_ColorOS14_Development_Prompt.md`
> 目标：ColorOS 14 (API 34) 平板，本地优先的企业级手写笔记与文档批注。

## 模块划分

```
app                    壳：Application、MainActivity、导航、主题
core-common            日志、线程调度、结果类型、工具
core-model             纯数据模型：Document/Page/Stroke/BrushPreset/对象层（Gson 序列化）
core-storage           Room 元数据库 + .inknote 文件容器读写 + journal 增量保存 + 迁移
core-ink               墨迹引擎：采样、平滑/稳定器、压力曲线、笔刷渲染、橡皮几何
core-render            页面渲染：底稿层+墨迹层+对象层合成、tile/LRU 缓存、缩略图
core-search            FTS 索引、中文 n-gram 归一化、搜索执行
core-security          Keystore 封装、导入安全校验（zip bomb/MIME/magic）
feature-library        资料库 UI：文件夹/最近/收藏/标签/垃圾箱/网格列表
feature-editor         笔记编辑器：工具条、套索、undo/redo、手势、页面管理
feature-import         统一 ImportJob 流水线
feature-export         PDF/图片/.inknote/TXT 导出
feature-audio          录音、播放(Media3)、墨迹-音频时间轴
feature-ocr            ML Kit Text Recognition v2（中文）+ 预处理
feature-handwriting    ML Kit Digital Ink 手写识别 + 手写搜索索引
feature-search         全局搜索 UI
feature-settings       设置（手势、防误触、压力曲线、备份）
document-pdf           PdfEngine 抽象 + PdfRenderer 实现
document-office        OfficeImportEngine 抽象 + Apache POI 分页转换实现
document-caj           CajImportEngine 插件式：格式探测→内嵌 PDF 提取→明确错误
```

依赖方向：feature-* / document-* → core-*；core-* 不依赖 feature-*。

## 关键设计

- **文档模型**：底稿层（模板/PDF/Office 转换页/图片）+ 墨迹层（矢量 stroke）+ 对象层（图片/文本框/形状/贴纸/胶带）+ 元数据；不保存为长图。
- **文件格式**：`.inknote` = 版本化 ZIP（manifest.json + document.json + pages/*.json + strokes/*.bin + resources/ + audio/ + thumbnails/）。manifest 含 schemaVersion，加载走 migration。写入 = 临时文件 + fsync + atomic rename。
- **墨迹**：MotionEvent 全字段采样（x/y/t/pressure/tilt/orientation/toolType/pointerId），stylus 优先、掌触拒绝；BrushPreset 可序列化；压力曲线 Soft/Balanced/Firm/Custom；在绘 stroke 直接画到前端 Canvas，完成后进矢量层。压感不可用时提供速度模拟压感并在 UI 明示。
- **Undo/Redo**：Command 模式，拖动合并为单条事务，历史上限 + checkpoint。
- **自动保存**：stroke 完成即写增量 journal；后台 compact；崩溃恢复 < 2s 损失；恢复去重。
- **PDF**：`PdfEngine` 接口与批注层解耦，默认实现用系统 `PdfRenderer`，后台渲染 + LRU tile 缓存。
- **Office**：`OfficeImportEngine` 抽象；内置 POI 转换器（读→分页→固定底稿）；商业 SDK 走 adapter + license 接口，未购买时明确提示。
- **OCR/手写**：ML Kit 离线模型；识别结果与原始手写分离，可撤回；低置信度标记。
- **音频**：MediaRecorder 分段 AAC/M4A + Media3 播放；stroke 记录 start/endAudioTimeMs。
- **搜索**：Room FTS4 + Unicode/2-gram 归一化；增量索引；结果定位到页与区域。
- **线程**：主线程只做 UI/输入；OCR、转换、索引、导出、缩略图全部后台；WorkManager 做垃圾箱清理/重建索引/备份。
- **安全**：SAF 导入导出；zip bomb/MIME/magic 校验；导入超时与内存上限；Keystore 存 API Key；不执行宏。

## 构建

```bash
source toolchain/env.sh
cd project
gradle :app:assembleDebug :app:assembleRelease
```

- minSdk 28 / targetSdk 34 / compileSdk 34 / Java 17 / AGP 8.5.2 / Gradle 8.9
- 版本集中管理：`project/gradle/libs.versions.toml`，禁止 `+` 动态版本。
