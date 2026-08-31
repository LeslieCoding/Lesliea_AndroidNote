# Lesliea_AndroidNote

适用于安卓 ColorOS 平板端的手写笔记软件。面向大屏设备深度优化：真实压感笔迹、1000% 连续缩放下零延迟零撕裂的墨水渲染、多标签编辑、资料库管理与文档导入导出。

> 当前版本：v2.2.1（versionCode 58）· minSdk 28 / targetSdk 34 · 适配 OPPO Pad（OPD2101）

## 核心特性

### 书写体验
- **5 种笔刷**：钢笔、书法笔、圆珠笔、铅笔（HB）、荧光笔，均支持宽度 / 不透明度 / 压感 / 速度感应 / 倾斜感应独立调节
- **压感与防误触**：手写笔优先，落笔后自动忽略手掌误触；无压感设备模拟压力
- **1000% 连续缩放**：双指捏合缩放/平移，顶部实时蓝色缩放指示器（停止操作自动隐藏），右上角一键回到整页视图
- **运动预测**：MotionPredictor 预测笔尖轨迹，降低输入到显示的跟随延迟

### 墨水渲染管线（本项目的技术核心）
- **实时墨迹**：Catmull-Rom 样条细分 + 变宽三角带网格，逐点增量构建（O(1)/点）
- **矢高覆盖圆盘**：弯曲段按“弧长 ÷ 线宽”自适应发射覆盖圆盘，任何线宽、任何缩放下笔迹实心连续无撕裂
- **笔尖细分条**：最新一段样条细分 + 逐区间圆盘，笔尖实时贴合曲线
- **O(1) 帧渲染**：已确认笔画增量烘焙进位图，每帧只做位图 blit + 笔尖小段绘制——书写成本与笔画长度无关
- **收笔零重栅格化**：收笔时烘焙位图直接变换拷贝进已提交图层
- **1000% 实测**：任意笔刷、任意线宽下 P50 ≈ 6ms，无卡顿帧

### 笔记与文档
- **多标签编辑**：同时打开多个页面，横竖屏自适应
- **资料库**：分区（全部笔记 / 最近 / 收藏 / 标签 / 文件夹 / 回收站）、可折叠侧栏、全文搜索（含手写 OCR）
- **导入**：PDF、Office 文档、CAJ、扫描文档、图片
- **导出**：PDF / 图片分享
- **音频笔记**：录音与笔记联动
- **安全**：笔记加密存储（core-security）

## 模块结构

```
project/
├── app/                  # 应用壳：导航、主题、组装
├── core/
│   ├── common/           # 通用工具
│   ├── model/            # 笔记 / 笔刷 / 笔画数据模型
│   ├── storage/          # 本地持久化
│   ├── ink/              # 墨水引擎：平滑、样条细分、笔刷网格
│   ├── render/           # 渲染：视口变换、墨水视图、页面渲染
│   ├── search/           # 搜索
│   └── security/         # 加密
├── feature/
│   ├── library/          # 资料库
│   ├── editor/           # 编辑器（笔刷栏、缩放指示器、整页按钮）
│   ├── import/ export/   # 文档导入导出
│   ├── audio/            # 音频笔记
│   ├── ocr/ handwriting/ # OCR 与手写识别
│   └── search/ settings/ # 搜索与设置
└── document/
    ├── pdf/ office/ caj/ # 文档解析
```

## 版本历史与开发记录

- [CHANGELOG.md](CHANGELOG.md) — 每个版本改了什么（v1.x → v2.2.1）
- [docs/HANDOVER_LOG.md](docs/HANDOVER_LOG.md) — 完整开发交接日志：每轮迭代的现象、根因、改动、回归测试与真机验证
- [reports/](reports/) — 性能报告、测试报告、真机验收与缩放渲染研究
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) · [docs/INK_ENGINE.md](docs/INK_ENGINE.md) · [docs/PRODUCT_REQUIREMENTS.md](docs/PRODUCT_REQUIREMENTS.md) 等架构与需求文档

## 构建

```bash
# 环境要求：JDK 17、Android SDK（compileSdk 34）、Gradle 8.9
cd project
./gradlew assembleRelease      # Release APK（需 keystore.properties 配签名）
./gradlew assembleDebug        # Debug APK
./gradlew test                 # 单元测试（core-ink / core-render 全量）
```

`local.properties`、`keystore.properties`（签名配置）不入库，需自行创建。

## 许可

个人项目，保留所有权利。
