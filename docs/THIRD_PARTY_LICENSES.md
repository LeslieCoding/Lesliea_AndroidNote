# 第三方依赖与许可证（THIRD_PARTY_LICENSES）

| 依赖 | 版本 | 许可证 | 用途 |
|---|---|---|---|
| AndroidX appcompat / material / recyclerview / constraintlayout / lifecycle / preference / swiperefreshlayout | 见 libs.versions.toml | Apache-2.0 | UI 基础 |
| AndroidX Room | 2.6.1 | Apache-2.0 | 元数据库 |
| AndroidX WorkManager | 2.9.1 | Apache-2.0 | 后台任务（垃圾箱清理等） |
| AndroidX input-motionprediction | 1.0.0 | Apache-2.0 | 触控笔轨迹预测（反射隔离，可降级） |
| Jetpack Media3 ExoPlayer | 1.4.1 | Apache-2.0 | 音频播放 |
| Google ML Kit Text Recognition (中文) | 16.0.1 | 受 Google ML Kit 服务条款约束 | 离线 OCR |
| Google ML Kit Digital Ink Recognition | 18.1.0 | 同上 | 离线手写识别 |
| Apache POI (poi/poi-ooxml/poi-scratchpad) | 5.2.5 | Apache-2.0 | Word/Excel 读取（不执行宏） |
| Gson | 2.11.0 | Apache-2.0 | 序列化 |
| JUnit | 4.13.2 | EPL-1.0 | 仅测试 |
| kxml2 / xmlpull | 2.3.0 / 1.1.3.1 | MIT / 公有领域 | 仅 JVM 测试的 XML 解析实现 |

## 说明
- 全部图标、贴纸、UI 素材为原创矢量绘制，未复制 Apple/Goodnotes/Notability 等品牌资产。
- 未打包任何商业字体；使用系统字体。
- 无商业 SDK 硬依赖；商业 Office/PDF SDK 通过 adapter + LicenseProvider 可选接入。
- 大模型（OCR/ASR）不打包进 APK，按需由系统/用户下载。
