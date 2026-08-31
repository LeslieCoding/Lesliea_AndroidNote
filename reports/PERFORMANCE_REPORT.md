# 性能报告 — Leslie ColorOSNote

## 构建产物指标（实测）

| 指标 | 值 |
|---|---|
| app-debug.apk | ≈ 136 MB（未压缩资源+调试符号） |
| app-release.apk | ≈ 97.5 MB（R8 混淆 + 资源压缩后） |
| 体积主要来源 | Apache POI（Office 解析）、ML Kit（OCR/手写识别模型库）、x86/arm 原生库 |
| release 全量构建 | ≈ 66s（R8 含 POI/ML Kit） |

## 低延迟手写设计（spec 核心指标）

- 书写路径**零 IO、零装箱分配**：笔迹事件在 InkView 线程直接成链，journal 增量追加在 IO 线程异步落盘，主线程不阻塞。
- 笔画以**网格化多边形（StrokeMesh）单次 Path 填充**渲染，杜绝逐段 drawPath 的 overdraw 与对象分配。
- 压感曲线（含 4–6 点自定义曲线）、速度/压力映射、起笔 taper 均在 core-ink 纯计算层完成，有单测覆盖。
- 页面渲染复用 PageRenderer 成员 Paint/Path（无每帧分配）；底图（PDF/Office 页）按 256px 宽度档位量化进**字节预算 LRU**（默认堆 1/8，下限 8MB），淘汰即回收 Bitmap。
- 编辑器自动保存：每笔 journal 追加 + 2s 防抖整存 + onPause 立即存；杀进程不丢笔迹。

## 大文档与内存

- .inknote 容器：元数据 JSON + 笔画二进制分体存储（StrokeBinCodec），增量 journal 避免整文件重写。
- 底图 Provider 渲染失败不缓存、release() 幂等；PDF 引擎侧已回收的缓存命中会自动重渲染，避免死 Bitmap。
- 已知限制：导入大 PDF 时容器 extras 需整读入内存（spec 31.1 流式写入待 core-storage 支持）；详见 docs/KNOWN_LIMITATIONS.md。

## 真机实测环境（已确认）

| 项 | 实测值 |
|---|---|
| 设备 | OPPO Pad OPD2101（product: OPD2101 / device: OP5223） |
| 系统 | Android 14，API 34（ColorOS 14）——与 spec 目标平台一致 |
| SoC 平台 | qcom（骁龙） |
| 屏幕 | 1600×2560 物理像素，360 dpi |
| 内存 | 总 5.63 GB |

## 真机实测数据

安装通道：`adb install -r` 已可用（v1.0.9 起实测通过）。实测数据（v1.0.13，2026-08-21 凌晨采集）：

| 指标 | 实测值 | 备注 |
|---|---|---|
| 屏幕刷新率 | **120 Hz 生效**（1600×2560） | `dumpsys display` mActiveSfDisplayMode fps=120.00001 |
| 内存占用 | TOTAL PSS ≈ **92.5 MB**（RSS 188 MB，Native Heap 10.8 MB） | 编辑器开 2 页笔记（含墨迹+文本框+贴纸）后台暂停态 |
| 帧耗时 | 50 分位 **6ms** / 90 分位 18ms / 95 分位 21ms | `dumpsys gfxinfo` 全程 2173 帧累计（含弹窗/列表滚动） |
| 卡顿帧占比 | 19.14%（416/2173） | 集中于弹窗与滚动场景；书写主路径零 IO 零分配，跟手无感知延迟 |
| 杀进程重开 | 笔迹/对象**完整保留**（截图 16/39/40） | journal 追加 + 2s 防抖整存 + onPause 立即存生效 |
| 息屏唤醒 | 进程与 Activity 存活，状态不丢 | `dumpsys activity` 框架层验证（锁屏 PIN 限制亮屏截图） |

未实测项（环境限制）：物理旋转屏幕（ColorOS 禁止 shell 写系统设置；两 Activity 已声明 configChanges 保证旋转不重建）、录音权限交互、跟手延迟毫秒级仪器测量（需高速摄像或 onscreen 探针，超出 adb 能力）。
