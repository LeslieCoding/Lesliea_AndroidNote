# 安全与隐私（SECURITY）

## 原则
本地优先：无网络时全部核心功能可用；任何网络功能默认关闭、用户显式开启。

## 实现
- **密钥存储**：API Key 等机密经 `KeystoreSecretStore`（AES-256-GCM，密钥由 Android Keystore 包装，不出 TEE）；禁止明文入库/日志。
- **日志纪律**：`AppLogger` 契约禁止记录 API Key、笔记正文、麦克风原始内容；本地诊断日志 2MB×5 轮转。
- **导入安全**（`core-security` + `feature-import`）：
  - magic number 与扩展名一致性校验（不只看后缀）；
  - 文件大小上限 512MB；解压总量 2GB / 条目 10 万 / 压缩比 200:1 防 zip bomb；zip-slip 路径防护；
  - 导入先落沙盒校验后提交，失败清理不留半成品；
  - 转换/解析有超时与取消护栏。
- **Office 宏**：POI 无执行引擎，宏天然不执行；嵌入脚本/外部链接不信任；导入全程只读。
- **权限**：录音/相机按需运行时申请；文件走 Storage Access Framework；不申请「管理所有文件」。
- **网络安全**：云转写 provider 可插拔、默认关、开启时 UI 明示数据上传。
- **依赖卫生**：版本全部锁定于 `gradle/libs.versions.toml`，禁止 `+` 动态版本；R8 + shrinkResources 于 release。
- **应用锁/生物识别/笔记加密**：接口预留（core-security），第一版未启用。
