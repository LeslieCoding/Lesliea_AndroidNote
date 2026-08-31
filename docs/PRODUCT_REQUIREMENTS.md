# 产品需求（PRODUCT_REQUIREMENTS）

完整权威需求文档见仓库根目录：
`../Enterprise_Android_Tablet_InkNote_ColorOS14_Development_Prompt.md`（2325 行，54 节）。

## 摘要
- **定位**：面向 Android 平板（ColorOS 14 / API 34 强制兼容基线）、本地优先的企业级手写笔记与文档批注平台，体验对标 iPad 头部笔记 App（不复制任何品牌资产）。
- **核心优先级**：低延迟手写 > 离线可用 > 数据安全 > 稳定性 > 文档批注 > 绘画增强。
- **核心场景**：课堂手写、PDF/Office 批注、高亮贴纸、录音+手写时间轴、多格式导入、多模板纸张、无限草稿、全局搜索、OCR、手写转文字、记忆胶带复习。
- **语言/架构**：Java 优先（Kotlin 仅限库强制处）、20 个 Gradle 模块、Room 元数据 + `.inknote` 版本化 ZIP 容器。
- **验收**：见 `COLOROS14_DEVICE_ACCEPTANCE.md` 与需求 45 节 Definition of Done（A-K 十一类）。
