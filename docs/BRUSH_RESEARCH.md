# 笔刷算法调研报告（钢笔 / 圆珠笔 / 铅笔）

> 调研日期：2026-08-21。目标：为 Android 手写笔记应用（Java、自研渲染管线，已有 MotionEvent 压力/速度、压感曲线 soft/balanced/firm、轨迹预测）改进笔刷手感。用户反馈：「现在的钢笔没有钢笔的感觉」。
>
> 本报告对照本项目现状：`core-ink/BrushEngine.java`（宽度模型 + 三角轮廓网格）、`StrokeSmoother.java`（EMA + 拉线稳定器）、`core-model/BrushPreset.java`（预设参数）。每条建议给出「改什么 / 预期效果 / 来源」。

---

## 〇、结论速览（为什么现在的钢笔「不像钢笔」）

对照行业做法（GoodNotes、Noteful、perfect-freehand、Google Ink），当前实现的差距主要在四点：

1. **压感宽度范围太窄**：现在钢笔 min/max = 0.65×~1.6× baseWidth。GoodNotes 压感可从细到粗达 2 倍以上（轻压极细、重压明显变粗）；真实弹性尖钢笔从 0.5mm 弹到 1.5mm（3 倍动态范围）。**建议扩大到 0.35×~2.0×**。
2. **收笔/起笔处理太弱**：现在 taper 是线性的、长度 0.1/0.2（乘 10×baseWidth），且 taper 曲线线性。优秀钢笔 App（Notes Plus X、Freenotes、Noteful、perfect-freehand）都把**两端 taper + 缓动曲线**当成钢笔感的核心：起笔圆/尖、收笔长而渐尖。
3. **速度→宽度融合过于简单**：当前 `w *= 1 - 0.5·vNorm·velocityToWidth`（线性、上限只减 50% 的一半），且速度未经平滑。真实钢笔/perfect-freehand/Write 的做法是：速度平滑后用**非线性（smoothstep 类）映射**，快写时宽度可压到 60% 左右，且速度信号要用短时间窗平滑，否则宽度抖动。
4. **压力信号未滤波**：压力 EMA 系数偏大（`pressureAlpha = 1 - 0.5·s`，s=0.2 时 α=0.9，几乎没滤波）。压力噪声直接变成宽度抖动，线条粗细「毛刺感」强，缺少钢笔的「墨线均匀」感。

---

## 一、钢笔（Fountain Pen）宽度模型

### 1.1 压力→宽度映射曲线的形态

- **动态范围**：行业标杆的钢笔压感范围显著大于我们当前的 0.65×~1.6×：
  - GoodNotes 6：轻压极细、重压可达约 2 倍笔宽，用户可切换「圆头/尖头」（tip 选项主要影响笔画末端形态）。来源：[Best fountain pen, 10 handwriting apps, 1 winner — Paperless X](https://beingpaperless.com/best-fountain-pen-10-handwriting-apps-1-winner/)
  - 真实弹性尖（flex nib）钢笔：0.5mm → 1.5mm，即 3 倍范围。来源：[Tom's Studio Specialty Fountain Pen Nibs](https://www.gentlemanstationer.com/parts/toms-studio-fountain-pen-nibs)
  - perfect-freehand 的 `thinning` 参数（压力对宽度的影响权重，默认 0.5，手写字常用 0.6~0.75；负值则压力越大越细）。来源：[perfect-freehand README](https://raw.githubusercontent.com/steveruizok/perfect-freehand/main/README.md)
- **曲线形态**：本项目已有 SOFT=`p^0.55` / BALANCED=`0.5p+0.5·smoothstep` / FIRM=`p^1.8`，方向正确。钢笔「绵软有弹性」的手感来自 SOFT 型曲线（小压力区间宽度变化灵敏）+ 轻压地板（light pressure floor，项目已有 0.05 地板，保持）。**建议钢笔默认从 BALANCED 改为 SOFT，并把 min/max 改为 0.4×~1.9× baseWidth。**

**落地**：`BrushPreset.fountainPen()` 改 `minWidth = baseWidth*0.4f`、`maxWidth = baseWidth*1.9f`、`pressureCurve = SOFT`；UI 增加「笔尖形态（圆头/尖头）」开关（映射到 end taper 长短与 easing）。
**预期效果**：轻触即细、按压出锋，接近 GoodNotes 钢笔观感。
**来源**：https://beingpaperless.com/best-fountain-pen-10-handwriting-apps-1-winner/ ，https://raw.githubusercontent.com/steveruizok/perfect-freehand/main/README.md

### 1.2 速度→宽度（快写变细）如何与压力融合

- **融合方式**：业界普遍采用**乘性融合**（宽度 = 压力宽度 × 速度因子），与本项目一致，无需改结构。两个改进点：
  1. **速度先平滑再使用**：当前直接用每点瞬时速度，宽度会抖。perfect-freehand 模拟压感（simulatePressure）本质上就是「用点间距（速度）反推压力」，内部对速度做了平滑；Google Ink Stroke Modeler 用 40ms 时间窗的移动平均平滑速度（wobble smoother：`timeout=0.04s, speed_floor=1.31, speed_ceiling=1.44`）。来源：[Ink Stroke Modeler](https://google.github.io/ink-stroke-modeler/)
  2. **映射用非线性 + 更深的变细幅度**：建议 `speedNorm = smoothstep(0, VELOCITY_REF, speed_smoothed)`，速度因子下限从现在的 ~0.925（velocityToWidth=0.15 时）扩到 **0.6**（`w *= 1 - 0.4·speedNorm` 钢笔、圆珠笔 0.15）。Write（styluslabs）的招牌钢笔手感主要就是速度驱动宽度变化，即使无压感设备也成立。来源：[styluslabs/Write](https://github.com/styluslabs/Write)
- **方向一致性**：快=细 的方向是正确的（真实钢笔快写出墨少、线细）。不要反过来。

**落地**：`BrushEngine.computeWidth` 中：对速度做 40ms 移动平均（或在 StrokeSmoother 输出侧附带平滑速度），`vNorm` 改 smoothstep，速度因子幅度提到 0.4（`velocityToWidth` 默认值钢笔 0.3~0.4）；`VELOCITY_REF=1500` 按真机实测调整（写字典型速度 200~800 逻辑单位/s，连笔峰值 1500+）。
**预期效果**：连笔、撇捺自动变细出锋，宽度不再抖动。
**来源**：https://google.github.io/ink-stroke-modeler/ ，https://github.com/styluslabs/Write

### 1.3 起笔/收笔粗细（Taper）

- **现状**：线性 taper（`taperFactor = 0.05 + 0.95·t`），长度 = taper 值 × 10 × baseWidth，钢笔默认 0.1/0.2。
- **行业做法**：
  - perfect-freehand：`start.taper` / `end.taper` 是**绝对弧长**，各自带独立 `easing` 函数，且 cap（圆头帽）与 taper 互斥。来源：perfect-freehand README（同上）
  - Notes Plus X：起笔圆、收笔尖；Freenotes 两端都 taper；Noteful 让用户调节 taper 强度——评测一致认为「两端 taper 是钢笔感的关键」。来源：Paperless X 评测（同上）
- **建议**：
  1. taper 因子改**缓动曲线**：起笔 `t²`（二次，快速出头，模拟落笔即有墨），收笔 `smoothstep` 或 `easeOutCubic`（`1-(1-t)³`，渐尖更自然），尖端地板保持 0.05。
  2. 钢笔默认值：**taperStart 0.15、taperEnd 0.5**（收笔明显长于起笔，出锋）；「尖头」笔尖形态 = end taper 加长到 0.8。
  3. 收笔 taper 依赖整笔弧长（computeMesh 已实现 retroactive 应用），保持现架构即可。

**落地**：`BrushEngine.taperFactor` 增加 easing 参数（start/end 不同曲线）；`fountainPen()` 默认 taperStart=0.15、taperEnd=0.5。
**预期效果**：笔画末端有「笔锋」，横竖撇捺有书法感。
**来源**：https://raw.githubusercontent.com/steveruizok/perfect-freehand/main/README.md ，https://beingpaperless.com/best-fountain-pen-10-handwriting-apps-1-winner/

---

## 二、墨迹渲染技术

### 2.1 贴图 stamp 式 vs 几何轮廓式

| 方案 | 原理 | 优点 | 缺点 | 适用 |
|---|---|---|---|---|
| Stamp/贴图（dab）式 | 沿路径按间距（spacing ≈ 直径的 25%~35%）重复贴笔尖纹理 | 天然支持铅笔纹理/毛边/混色；MyPaint、Krita、Procreate 走这条路 | 高频采样下 overdraw 大；宽度极快变化时有「糖葫芦」感 | 铅笔、炭笔、毛笔 |
| 几何轮廓式 | 沿骨架按宽度向法线两侧扩展成轮廓/三角带，一次填充 | 边缘光滑锐利、矢量可编辑、性能高 | 难以表现纹理和毛边 | 钢笔、圆珠笔、荧光笔 |

- 本项目已是几何轮廓式（`StrokeMesh` 中心差分法向 + 左右轮廓，单次 fill Path），方向正确。**建议保持钢笔/圆珠笔走几何式，铅笔改走 stamp 式（或几何式 + 纹理着色器）**。三角带（triangle strip）沿骨架扩展是学术与工业标准做法，见 Hertzmann《Algorithms for Rendering in Artistic Styles》「Rendering with Triangle Strips」一节：[NYU PDF](https://cs.nyu.edu/media/publications/hertzmann_aaron.pdf)；Wacom Ink SDK 的 shape-filling 矢量墨迹同样沿骨架变宽度填充：[Wacom Ink Geometry Pipeline](https://developer-docs.wacom.com/docs/sdk-for-ink/tech/pipeline/)。

### 2.2 宽度/位置平滑：EMA vs 一欧元滤波（1€ Filter） vs 弹簧-质量模型

- **一欧元滤波（Casiez, CHI 2012）**：速度自适应低通——慢速强滤波去抖、快速弱滤波降延迟，正好解决固定 EMA「要不太抖要不太跟手」的矛盾。**已用在 Chrome/Android 系统输入栈**。
  - 调参流程：先 β=0，慢速书写下调 fcmin 直到慢速抖动消失（起点 fcmin≈1Hz）；再快速书写上调 β 直到快速滞后可以接受（β 从 0.0001~0.001 量级试起，位置单位为 px 时常见 β≈0.005~0.02）。
  - 有官方 Java 实现（BSD）。来源：[1€ Filter 官网](https://gery.casiez.net/1euro/) ，[论文 PDF](https://inria.hal.science/hal-00670496v1/document)
- **Google Ink Stroke Modeler**（C++，Apache-2.0，Google Keep/Jamboard 同源技术）：把笔尖建模为「弹簧-质量-阻尼」系统，欧拉积分，配 wobble smoother（40ms 移动平均）+ 重采样（`min_output_rate=180Hz`）+ 笔画收尾 catch-up + Kalman/StrokeEnd 预测。默认参数：`spring_mass_constant = 11/32400`、`drag_constant = 72`、`timeout=0.04s`、`speed_floor=1.31`、`speed_ceiling=1.44`（单位 cm/s 量级）、`max_input_samples=20`。来源：[Ink Stroke Modeler](https://google.github.io/ink-stroke-modeler/)
- **建议**：
  1. 位置平滑：把 `StrokeSmoother` 的固定 EMA 换成 **1€ 滤波**（改动最小、收益最大）；`smoothing` 参数映射到 fcmin（0→fcmin 3Hz，1→fcmin 0.7Hz），β 固定 0.01 左右。
  2. **压力单独用更强的 1€ 滤波**（fcmin≈2Hz，β≈0.001）——宽度抖动比位置抖动更刺眼。
  3. 中期可考虑 JNI 接入 Google Ink Stroke Modeler 或移植其弹簧-质量模型（项目已有 MotionPredictor，Ink Stroke Modeler 的预测器思路一致）。
- Xournal++ 的输入稳定器也提供「averaging + preprocessor」两段式参考：[Xournal++ Preferences](https://xournalpp.github.io/guide/config/preferences/)

**预期效果**：慢速写小字不再抖、快速连笔不拖尾；宽度曲线光滑无「台阶」。

### 2.3 曲线拟合与重采样

- **拟合**：perfect-freehand 输出轮廓多边形后用「相邻点取中点 + 二次贝塞尔」连接（`smoothPathFromPolygon`），Saber 直接复用此方案并分高低两档质量（低档每 4 点取 1）。来源：[Saber `_stroke.dart`](https://raw.githubusercontent.com/saber-notes/saber/main/lib/components/canvas/_stroke.dart)
- **建议**：本项目中心线在做网格前，先按**固定弧长（0.5~1 px）用 Catmull-Rom 重采样**，再算宽度与法向——好处：法线/宽度变化均匀，转弯处宽度过渡平滑，三角带密度稳定。已完成的笔画做一遍高质量重采样 + 轮廓二次贝塞尔连接；在绘笔画保持现有低精度路径（Saber 同款策略）。
- Ink Stroke Modeler 的重采样（180Hz 均匀时间采样）解决的是输入采样率不一致问题，与我们的固定弧长重采样互补，可选。

---

## 三、圆珠笔与铅笔的区别参数

### 3.1 圆珠笔（Ballpoint）

- 核心特征：**宽度几乎恒定**，压力只产生轻微粗细/深浅变化，无 taper 出锋（或极短）。
- 评测佐证：Notability 钢笔「不随书写变粗，唯一资格是压感」即被批评像圆珠笔——说明圆珠笔 ≈ 恒定宽度 + 微弱压感。来源：Paperless X 评测（同上）
- 建议参数（对 `BrushPreset.ballpoint()`）：
  - min/max = 0.9×~1.1× baseWidth（当前 0.85~1.2 稍宽，可保留）
  - `pressureCurve=FIRM`（重压才明显）✅ 已是
  - `pressureToOpacity` 0.1~0.2（轻压略灰，模拟油墨深浅）——当前为 0，建议加一点
  - `velocityToWidth` 0.1~0.15（快写略细）——当前为 0
  - taperStart 0、taperEnd 0.05~0.1、圆头帽 ✅ 基本已是

### 3.2 铅笔（Pencil）

铅笔的三要素：**灰度（压力→不透明度为主）、纹理颗粒、边缘毛糙**。

1. **灰度**：压力主要映射到不透明度而非宽度（项目 `pencilHb()` 已是 pressureToOpacity=0.8、pressureToWidth=0.4，方向正确）；SOFT 曲线 ✅。可再加**速度→不透明度**（快写略浅，模拟石墨未附着）。
2. **纹理颗粒（graphite grain）**：
   - 简单做法（保持几何管线）：渲染时用颗粒纹理对填充做 **MULTIPLY/dst-out 噪声调制**（项目已有 `textureId="texture_pencil_grain"` 字段）。纹理必须是**平铺纸纹/噪点**，并按笔画局部坐标采样，避免「屏幕固定纹理滑动伪影」（implicit brush 论文指出静态屏对齐噪声会产生 sliding artifact）。来源：[Implicit Brushes for Stylized Line-based Rendering](http://www.cad.zju.edu.cn/home/chenjiazhou/pdf/implicit2011.pdf)
   - 更真实的做法（stamp 式）：沿路径以 spacing ≈ 0.25~0.35×直径贴「软石墨」dab，每个 dab 的 alpha 加 ±10~15% 抖动、位置加小幅抖动。Apple Motion / ArtRage 等都用「dab + spacing + jitter」模型。来源：[Apple Motion User Guide（stroke dabs/jitter）](https://help.apple.com/pdf/motion/en_US/motion-user-guide.pdf)
3. **边缘毛糙**：给轮廓半宽加**低频小幅噪声**：`half *= 1 + noise(arcLength)·0.08~0.12`（沿弧长的一维 value noise，空间频率约每毫米 2~4 个周期）；stamp 方案则天然有毛边，不必另加。抗锯齿边缘不要用硬圆头帽，用 1~2px 的 alpha 羽化。
4. **侧锋（tilt）**：已有 tiltToWidth=0.8 / tiltToOpacity=0.3 ✅；真实铅笔侧锋还会让颗粒更明显——可在高 tilt 时提高纹理对比度（或切换更大更软的 dab）。

**落地**：铅笔短期 = 几何轮廓 + 宽度噪声（0.1 幅度）+ 纸纹 MULTIPLY + 羽化边缘；中期 = 独立 stamp 渲染路径（dab spacing 0.3、alpha jitter 0.15）。
**预期效果**：从「灰色钢笔」变成有石墨颗粒感的铅笔。

---

## 四、开源实现参考与关键参数建议

### 4.1 可直接参考/复用的项目

| 项目 | 许可 | 参考价值 |
|---|---|---|
| [Google ink-stroke-modeler](https://google.github.io/ink-stroke-modeler/) | Apache-2.0 (C++) | 输入平滑+预测的工业级实现；参数可直接抄：wobble timeout 0.04s、spring_mass 11/32400、drag 72、min_output_rate 180Hz |
| [androidx.ink（Jetpack Ink API）](https://developer.android.google.cn/reference/androidx/ink/brush/package-summary) | Apache-2.0 | Google 官方笔刷/笔画抽象（Brush、StockBrushes 压力笔/荧光笔等）；即使不接库，其 BrushFamily/StockBrushes 的参数划分值得对照 |
| [perfect-freehand](https://github.com/steveruizok/perfect-freehand) | MIT (TS，有 [Dart](https://pub.dev/packages/perfect_freehand)/Rust/Python 移植) | **最值得通读**（约几百行）：thinning、streamline、simulatePressure、start/end taper+easing、轮廓中点二次贝塞尔 |
| [styluslabs/Write](https://github.com/styluslabs/Write) | 开源 | 速度驱动宽度的手写 App 标杆，证明「速度因子」对钢笔感的贡献不亚于压感 |
| [MyPaint / libmypaint 笔刷引擎](https://docs.drawpile.net/help/draw/mypaint.html) | GPL（看思路勿抄码） | 输入（pressure/speed/tilt…）→ 任意笔刷参数的多点映射曲线模型；`radius_by_pressure`、`offset_by_speed` 等映射思想可借鉴到我们的 preset 系统 |
| [Saber](https://github.com/saber-notes/saber) | GPL（看思路） | Flutter 笔记 App，perfect_freehand 集成 + 高低双精度渲染 + 点去重阈值（0.1×size） |
| [1€ Filter](https://gery.casiez.net/1euro/) | BSD（有 Java 版） | 替换 EMA 的最小改动方案 |

### 4.2 关键参数速查表（数值级建议）

| 参数 | 钢笔 | 圆珠笔 | 铅笔 HB | 当前项目值（钢笔） |
|---|---|---|---|---|
| minWidth / baseWidth | **0.35~0.45** | 0.85~0.9 | 0.5 | 0.65（偏窄动态） |
| maxWidth / baseWidth | **1.8~2.2** | 1.1~1.2 | 1.75 | 1.6 |
| pressureCurve | **SOFT** | FIRM | SOFT | BALANCED |
| pressureToWidth | 1.0 | 0.35 | 0.4 | 1.0 |
| pressureToOpacity | 0~0.1 | 0.1~0.2 | **0.8** | 0 |
| 速度因子最大减幅 | **40%**（velocityToWidth≈0.35） | 15% | 25% | ~7.5%（太弱） |
| 速度平滑窗口 | 40ms 移动平均（Ink Modeler） | 同左 | 同左 | 无 |
| 速度映射 | smoothstep(0, vRef, v) | 同左 | 同左 | 线性 clamp |
| taperStart | 0.15（t² easing） | 0 | 0.1 | 0.1（线性） |
| taperEnd | **0.5~0.8（easeOutCubic）** | 0.05~0.1 | 0.2 | 0.2（线性） |
| taper 尖端地板 | 0.05 | 0.05 | 0.05 | 0.05 ✅ |
| 位置滤波 | 1€：fcmin≈1.0~2.0Hz, β≈0.005~0.02 | 同左 | 同左 | 固定 EMA α=0.84 |
| 压力滤波 | 1€：fcmin≈2Hz, β≈0.001 | 同左 | 同左 | EMA α=0.9（≈没滤） |
| 边缘毛糙噪声 | 0 | 0 | 半宽 ±8~12% value noise | 无 |
| 纹理 | 无 | 无 | 纸纹 MULTIPLY / stamp dab spacing 0.3、alpha jitter 0.15 | 有 textureId 字段待实现 |
| 中心线重采样 | 固定弧长 0.5~1px Catmull-Rom | 同左 | 同左 | 无（直接用原始点） |

---

## 五、落地优先级（对本项目的改动清单）

1. **P0 钢笔参数调整**（半天）：`fountainPen()` 改 min/max 0.4×/1.9×、曲线 SOFT、velocityToWidth 0.35、taperEnd 0.5。预期立即解决「不像钢笔」的 70%。
2. **P0 速度平滑 + smoothstep**（半天）：`BrushEngine` 内对速度做 40ms 移动平均，`vNorm` 改 smoothstep，速度减幅上限 0.4。
3. **P1 taper easing**（1 天）：`taperFactor` 支持 start=t²、end=easeOutCubic；UI 加「笔尖：圆头/尖头」（尖头=end taper 0.8）。
4. **P1 1€ 滤波替换 EMA**（1~2 天）：位置 + 压力各一个 1€ 滤波器，`smoothing` 映射 fcmin。用官方 Java 版做参考实现。
5. **P2 中心线固定弧长重采样**（1~2 天）：Catmull-Rom 0.5~1px 间隔，再建网格；完成笔画轮廓二次贝塞尔连接。
6. **P2 铅笔质感**（2~3 天）：半宽 value noise（±10%）+ 纸纹 MULTIPLY 落实 `textureId` + 边缘 1~2px 羽化；速度→不透明度。
7. **P3（可选）**：JNI 接 Google ink-stroke-modeler 或 androidx.ink；铅笔 stamp 渲染路径。

## 附：主要参考链接

- Google Ink Stroke Modeler（含完整算法与默认参数）：https://google.github.io/ink-stroke-modeler/
- 1€ Filter 官网（调参流程 + Java 实现）：https://gery.casiez.net/1euro/ ；论文：https://inria.hal.science/hal-00670496v1/document
- perfect-freehand（thinning/streamline/taper/easing 语义）：https://github.com/steveruizok/perfect-freehand
- styluslabs Write（速度驱动宽度）：https://github.com/styluslabs/Write
- androidx.ink Brush API：https://developer.android.google.cn/reference/androidx/ink/brush/package-summary
- 10 款手写 App 钢笔横评（taper/压感/厚度范围的行业基准）：https://beingpaperless.com/best-fountain-pen-10-handwriting-apps-1-winner/
- GoodNotes 笔工具设置（压感/稳定器）：https://support.goodnotes.com/hc/en-us/articles/7353756785679-Using-the-Pen-tool
- 真实弹性尖钢笔动态范围 0.5→1.5mm：https://www.gentlemanstationer.com/parts/toms-studio-fountain-pen-nibs
- Wacom Ink 几何管线（shape-filling 矢量墨迹）：https://developer-docs.wacom.com/docs/sdk-for-ink/tech/pipeline/
- Hertzmann《Algorithms for Rendering in Artistic Styles》（三角带渲染）：https://cs.nyu.edu/media/publications/hertzmann_aaron.pdf
- Implicit Brushes（纹理滑动伪影）：http://www.cad.zju.edu.cn/home/chenjiazhou/pdf/implicit2011.pdf
- MyPaint 笔刷设置（输入→参数映射）：https://docs.drawpile.net/help/draw/mypaint.html
- Saber（perfect_freehand 集成 + 双精度渲染）：https://raw.githubusercontent.com/saber-notes/saber/main/lib/components/canvas/_stroke.dart
- Xournal++ 输入稳定器：https://xournalpp.github.io/guide/config/preferences/


---

# 第二轮调研（2026-08-21，v1.1.6 之后）

> 背景：v1.1.6 落地后用户反馈「稍微有点好用了，但还不算最好用」。本轮按六个专题深挖，所有结论均核对到源码级（perfect-freehand 全文、Chromium 输入栈、Google Ink Stroke Modeler 完整算法文档、androidx.graphics 官方文档）。
>
> 对应源码：`core-ink/BrushEngine.java`、`core-ink/StrokeSmoother.java`、`core-ink/PressureCurve.java`、`core-model/BrushPreset.java`（字段：smoothing/stabilization/velocityToWidth/taperStart/taperEnd/pressureToOpacity/textureId）。

## 〇、本轮结论速览

1. **平滑算法可以直接「毕业」**：1€ 滤波有 Chromium 在 Android 输入栈的实测默认参数（`mincutoff=4.7, beta=0.01, dcutoff=1.0, freq=60Hz`），官方 BSD Java 实现可直接抄；Google Ink 的弹簧-质量模型也只有 ~20 行核心代码。两者都是纯 Java 可落地。
2. **perfect-freehand 的「笔锋好」有三个之前没注意的细节**：（a）起笔压力取前 10 点的滑动平均防「粗头」（DEFAULT_FIRST_PRESSURE=0.25）；（b）轮廓点按 `(size×smoothing)²` 最小间距去重，笔画边缘干净；（c）笔尾最后 3px（END_NOISE_THRESHOLD）的点直接丢弃，消除抬笔抖动。**这三点比 taper 本身更影响观感。**
3. **taper 缓动方向对错了**：perfect-freehand 起笔默认 easing 是 `t*(2-t)`（easeOutQuad，快速出头后放缓），不是 v1.1.6 用的 `t²`（easeIn，出头慢）；收笔 `--t*t*t+1`（easeOutCubic）✅一致。起笔应改为 easeOutQuad。
4. **延迟有一个零成本大招没做**：落笔时 `View.requestUnbufferedDispatch()`（API 21+，一行代码），跳过 MotionEvent 批处理排队，OPPO 240Hz 采样下收益直接。另有 androidx.graphics 的 `CanvasFrontBufferedRenderer`（API 29+，稳定版 1.0.4，纯 Java/Kotlin 可用，不需 NDK）。
5. **主流 App 参数实测**：GoodNotes 6 钢笔默认「笔尖锐利度 50% + 压感灵敏度 50%」，且 Min 档压感不归零（保留压感）；Concepts 钢笔是**速度驱动**（慢=细、快=粗，墨量模型，与我们「快=细」方向相反但同样成立）。

---

## 一、1€ (One Euro) 滤波器：手写笔场景的具体参数

### 1.1 算法与 Android 实测参数

- 公式（官方）：对信号 x 先估速度导数 dx（用 dcutoff=1Hz 固定低通滤波），再按速度自适应调节截止频率 `cutoff = mincutoff + beta·|dx|`，最后用该 cutoff 做一阶低通（α = 1/(1+τ/te)，τ = 1/(2π·cutoff)，te 为采样间隔）。来源：[1€ Filter 官网](https://gery.casiez.net/1euro/)
- **Chromium 在 Android 全设备输入栈（手写笔/触摸预测）的实测默认值**（2023 年 3~5 月实验调参得出）：`freq=60Hz, dcutoff=1.0, mincutoff=4.7, beta=0.01`（位置单位为 px）。来源：[Chromium ui/base/prediction/one_euro_filter.h](https://git.iridiumbrowser.de/iridium-browser/iridium-browser/-/raw/m128/ui/base/prediction/one_euro_filter.h)
- **官方调参流程**：先 β=0，慢速移动调 fcmin（从 1Hz 起，降 fcmin 去抖但增滞后）；再快速移动调 β（px 单位时从 0.0001~0.001 量级起，按 ×10/÷10 找数量级）。「慢抖 → 降 fcmin；快滞 → 升 β」。来源：[1€ Filter 官网调参节](https://gery.casiez.net/1euro/)
- **官方 Java 实现（BSD）**：Stéphane Conversy（Université de Toulouse）版，官网有链接，可直接搬进 `core-ink`。

### 1.2 相比当前 EMA 的实际收益

- 当前 `StrokeSmoother` 固定 α（s=0.2 时 positionAlpha=0.84）的问题：慢速写小字时滤波不足（抖动直接进网格），快速连笔时滞后恒定（拖尾感）。1€ 的本质就是「α 随速度在 [α(fcmin), ~1] 之间自动滑动」，一次解决两端矛盾。
- **关键实现细节：必须用真实时间戳**。1€ 的 α 依赖 te（采样间隔），OPPO Pencil 标称 240Hz 采样但 MotionEvent 是批量投递的（一个 MOVE 含多个历史点），必须遍历 `getHistoricalEventTime(i)` 逐点喂滤波器，不能假设固定 60Hz。dcutoff 保持 1.0 即可。
- 建议参数（页面坐标 px 单位）：
  - **位置**：fcmin 映射 smoothing 滑杆（0 → fcmin 4.7Hz 即 Chromium 默认近乎直通；1 → fcmin 0.8Hz 强滤波），β 固定 **0.01**（Chromium 实测值）。
  - **压力**：单独一个 1€，**fcmin≈2Hz, β≈0.001**——压力噪声幅值小但直接变宽度抖动，且压力变化慢，适合强滤波弱速度补偿。
- 参考实现（除官方 Java 版外）：[jaantollander/OneEuroFilter](https://github.com/jaantollander/OneEuroFilter)（伪码级清晰）；[MKSharaf/OneEuroFilterExplained](https://github.com/MKSharaf/OneEuroFilterExplained)。

### 1.3 备选：Google Ink 弹簧-质量模型（同样纯 Java 可实现）

Ink Stroke Modeler 核心仅三段，全部可移植（算法文档已公开到公式级）：

1. **Wobble Smoother**（去数字仪高频噪声）：对位置做 40ms 时间窗移动平均得 `p̄`，对速度同窗平均得 `v̄`；λ = clamp(N(v_floor, v_ceil, v̄))，输出 `p' = lerp(p̄, p_raw, λ)`。默认 `timeout=0.04s, speed_floor=1.31, speed_ceiling=1.44`（单位与笔画坐标/秒一致；px 单位下可把 floor/ceiling 设为写字典型速度区间的两端，如 300/1500 px/s）。**慢速全滤波、快速直通，过渡区间窄——效果与 1€ 类似但更可控。**
2. **弹簧-质量位置模型**：`a = (anchor - s)/M - kd·v; v += Δt·a; s += Δt·v`，`M = spring_mass_constant = 11/32400 ≈ 3.4e-4, kd = drag_constant = 72`。anchor 是重采样后的原始输入点。欧拉积分，~15 行 Java。
3. **时间重采样**：按 `min_output_rate=180Hz` 把事件间隔过长的相邻输入点线性插值补齐（`a_j = ceil(Δt / (1/180))` 份），保证模型积分步长稳定；笔末用 end-of-stroke catch-up（`end_of_stroke_stopping_distance=0.001, max_iterations=20`）追上最后输入点。

**建议**：先上 1€（改动最小，替换 `StrokeSmoother` 内部 EMA 即可，参数有 Chromium 背书）；若效果仍不满意再上弹簧-质量模型。来源：[Ink Stroke Modeler 完整算法](https://google.github.io/ink-stroke-modeler/)

---

## 二、轨迹重采样与插值

### 2.1 perfect-freehand 的 streamline（实际是「拉线式 lerp」，不是滤波器）

源码级公式（`getStrokePoints.ts`）：每个新输入点不直接采用，而是
`t = MIN_STREAMLINE_T + (1 - streamline) × STREAMLINE_T_RANGE`（常量 0.15 / 0.85），
`newPoint = lerp(prevEmittedPoint, rawPoint, t)`——即从**上一个已发射点**向原始点走固定比例 t。streamline=0.5 → t=0.575。这本质是一个「永远追不上目标的追踪点」，天然去抖；与我们的拉线稳定器同族，但无半径死区、更平滑。来源：[getStrokePoints.ts](https://raw.githubusercontent.com/steveruizok/perfect-freehand/main/packages/perfect-freehand/src/getStrokePoints.ts)

另有两个细节可直接抄：
- **起点噪声抑制**：累计弧长未超过 `size`（笔画直径）前不发射新点——落笔瞬间的抖动被整体吞掉。
- **仅 2 个输入点时自动插 4 个中间点**，保证短笔画/点按的 taper 形态正确（我们的 WELD_DISTANCE 只防零距离，没有补点逻辑）。

### 2.2 等弧长重采样（解决采样间隔不均导致的宽度/taper 不均）

- 问题：OPPO 笔 120~240Hz 采样 + 事件抖动 → 点间距忽密忽疏，而宽度法向、taper 都按点计算，转弯处法向跳变、网格密度不均。
- 做法（保持现有架构最小改动）：`BrushEngine.computeMesh`（完成笔迹）入口先对中心线做 **Catmull-Rom 固定弧长重采样**（间隔 0.5~1px）：CR 样条过所有原始点、C1 连续，用弦长参数化即可；逐弧长步进采样时用二分/牛顿法在段内定位（参考：[imaginary-institute: Efficiently Finding Points on Catmull-Rom](https://www.imaginary-institute.com/blog/2014/09/03/efficiently-finding-points-on-catmull-rom-and-bezier-curves/)）。宽度/压力也随 t 同步插值。
- 在绘笔画不重采样（保持低延迟），只在 `endStroke()`/烘焙时做——与 Saber 高低双精度策略一致。
- Google Ink 的时间重采样（180Hz）解决的是同一问题的「时间域」版本，二选一即可；弧长域对宽度均匀性的收益更直接。

### 2.3 轮廓点去重（完美笔迹边缘干净的秘诀）

perfect-freehand 在往左/右轮廓数组加点前判断：新轮廓点与上一同侧轮廓点距离平方 > `(size × smoothing)²` 才采纳（`minDistance = pow(size*smoothing, 2)`）。这保证轮廓多边形顶点密度与笔宽成正比，细笔顶点密、粗笔顶点疏，边缘无锯齿堆积。**建议在我们的 StrokeMesh 构建时加同款去重**：`minDist² = (preset.baseWidth × smoothingFactor)²`，smoothingFactor≈0.5 起步。来源：[getStrokeOutlinePoints.ts](https://raw.githubusercontent.com/steveruizok/perfect-freehand/main/packages/perfect-freehand/src/getStrokeOutlinePoints.ts)

---

## 三、perfect-freehand 参数深扒（源码级）

全部来自官方仓库 main 分支源码（MIT）：

### 3.1 宽度公式

```
radius = size × easing(0.5 - thinning × (0.5 - pressure))
```
（[getStrokeRadius.ts](https://raw.githubusercontent.com/steveruizok/perfect-freehand/main/packages/perfect-freehand/src/getStrokeRadius.ts)）

- 无压感时 pressure=0.5 → radius = size/2（size 即直径）。
- thinning=0.5（默认）、pressure∈[0,1]、easing=恒等 → radius ∈ [0, size]，即**宽度动态范围 0%~100% size**。我们的 minWidth/maxWidth 0.4~1.9×baseWidth 等价于 thinning≈0.65、easing 恒等——范围已经够，**差距不在范围，在下面三个细节**。
- `easing` 是作用于「压力映射后的归一化宽度」的曲线，等价于我们的 PressureCurve 后置——pf 用户常用 easing = t²（轻压更细），我们已有 SOFT 曲线，无需改结构。

### 3.2 simulatePressure（速度→压力，供无压感设备）

```
sp = min(1, distance / size)          // 归一化速度
rp = min(1, 1 - sp)
pressure = min(1, prevPressure + (rp - prevPressure) × sp × 0.275)
```
（[simulatePressure.ts](https://raw.githubusercontent.com/steveruizok/perfect-freehand/main/packages/perfect-freehand/src/simulatePressure.ts)，RATE_OF_PRESSURE_CHANGE=0.275）

要点：**快写时压力向 0 收敛（变细）、慢写时向 1 收敛（变粗），且是按帧递推的积分器（天然平滑，无需额外滤波）**。方向是「快=细」，与我们一致。这个递推公式比我们的「40ms 窗口平均 + smoothstep」更省（无窗口存储），可借鉴其收敛速率 0.275。

### 3.3 三个防「粗头/脏尾」细节（本轮最重要发现）

1. **起笔压力平均**：`computeInitialPressure` 取前 10 个点的滑动平均（`(acc + p_i)/2` 递推）作为初始半径基准，配合 `DEFAULT_FIRST_PRESSURE=0.25`——落笔点必然慢、必然低压，不设防会出现「粗头」。**建议：`BrushEngine` 对笔画前 ~8 点的压力做滑动平均后再进宽度公式。**
2. **笔尾噪声剔除**：距离笔尾 < 3px（`END_NOISE_THRESHOLD=3`）的非末位点直接 skip——抬笔瞬间手抖产生的毛刺不进网格。**建议：`computeMesh` 里跳过 distFromEnd < 3px 的中间点。**
3. **last 语义**：`last=true`（笔画完成）时最后一个点用**原始输入点**而非 streamline 追踪点——保证收笔位置精确停在抬笔处（笔锋到位）；在绘期间最后一个点总是「略微滞后」的追踪点。我们 endStroke 已 retroactive 处理 taper，但需确认收笔锚点用的是原始点而非平滑后点。

### 3.4 taper 的真实默认缓动（修正 v1.1.6）

源码默认：
- 起笔：`taperStartEase = t => t*(2-t)`（**easeOutQuad**，快速出头后渐缓）——v1.1.6 用的 t² 方向反了，落笔出头会偏慢、起笔显「钝」。**改为 t*(2-t)。**
- 收笔：`taperEndEase = t => --t*t*t+1`（easeOutCubic）✅ 与 v1.1.6 一致。
- taper 是**距离驱动**（runningLength 与 taper 绝对弧长比较），不是压力驱动；两端 taper 取 `min(startStrength, endStrength)` 作用于半径。
- 尖角处理：相邻向量点积 < 0（>90° 折角）时在该点画 13 段圆帽（CORNER_CAP_SEGMENTS=13）而不是延伸轮廓——避免折角外尖刺。我们的中心差分法向在锐角折返时也会炸，建议照搬「点积<0 → 圆帽」策略。

### 3.5 常量速查（[constants.ts](https://raw.githubusercontent.com/steveruizok/perfect-freehand/main/packages/perfect-freehand/src/constants.ts)）

| 常量 | 值 | 用途 |
|---|---|---|
| RATE_OF_PRESSURE_CHANGE | 0.275 | 模拟压力收敛速率 |
| DEFAULT_FIRST_PRESSURE | 0.25 | 首点默认压力（防粗头） |
| END_NOISE_THRESHOLD | 3 (px) | 笔尾噪声剔除距离 |
| MIN_STREAMLINE_T / STREAMLINE_T_RANGE | 0.15 / 0.85 | streamline lerp 系数 |
| START_CAP_SEGMENTS / END_CAP_SEGMENTS / CORNER_CAP_SEGMENTS | 13 / 29 / 13 | 圆帽分段数 |
| MIN_RADIUS | 0.01 | 半径地板 |

---

## 四、主流笔记 App 钢笔参数实测

### 4.1 GoodNotes 6（官方文档级参数）

- 三种笔：**Fountain Pen**（压感）、**Ball Pen**（**无压感**，笔画永远是圆头端）、**Brush Pen**（高压感，用于 lettering）。
- 钢笔两个可调维度，**默认都是 50%**：
  - **Tip Sharpness（笔尖锐利度）**：Min=圆头 ↔ Max=尖头——控制笔画两端的形态（对应我们的 taper 长度/easing），与压感无关。
  - **Pressure Sensitivity（压感灵敏度）**：控制线宽随压力变化的程度；**官方明确「Min 值不会移除压感」**——即压感映射曲线有一个不可关闭的地板（对应我们的 minWidth 地板 0.4×）。
- 含义：GoodNotes 钢笔手感 = 中等 taper + 中等压感动态范围的组合，并非极端参数。来源：[GoodNotes 官方 Pen tool 文档](https://support.goodnotes.com/hc/en-us/articles/7353756785679-Using-the-Pen-tool)

### 4.2 Concepts（速度驱动的另一极）

官方手册明确：**Pen 工具快=细、慢=粗；Fountain Pen 相反（慢=细、快=粗）**，即钢笔模拟「墨水量」模型——快写墨水铺开更多。两种方向都被主流产品采用且用户接受，说明「速度影响宽度」本身比方向更关键；我们保持「快=细」（真实钢笔出墨少）即可。来源：[Concepts Brushes and Tools 手册](https://concepts.app/en/manual/brushes-and-tools)

### 4.3 其他

- Paperless X 的 10 款 App 钢笔横评（第一轮已引）结论不变：两端 taper 是钢笔感第一要素，Noteful/Notes Plus X/Freenotes 靠 taper 取胜，Notability 因「线宽不随书写变化」被批像圆珠笔。来源：[Paperless X](https://beingpaperless.com/best-fountain-pen-10-handwriting-apps-1-winner/)
- OPPO Pencil 硬件能力（官方宣传）：240Hz 采样、4096 级压感、支持侧锋倾斜——我们的输入管线能拿到的轴数据完整，瓶颈在 App 侧的事件分发（见第六节 requestUnbufferedDispatch）。来源：[OPPO Pencil 商品页规格](https://www.smzdm.com/p/85047151/)

---

## 五、铅笔/圆珠笔质感（纯 Canvas 低成本方案）

### 5.1 铅笔纹理：BitmapShader + saveLayer，无需 NDK

低成本三段式（按性价比排序）：

1. **宽度噪声（半小时）**：`BrushEngine` 铅笔预设给半宽加沿弧长的一维 value noise：`half *= 1 + noise(arcLen × 3周期/mm) × 0.10`。第一轮已建议，仍未落地，仍是铅笔感性价比最高的一步。
2. **颗粒纹理（1 天，纯 Canvas）**：
   - 预生成 256×256 可平铺噪点 Bitmap（Graphite grain，一次性程序生成即可，不必带资源文件）。
   - 完成笔迹烘到位图时：`saveLayer(strokeBounds, null)` → 正常 fill 笔迹 Path → `Paint` 设 `BitmapShader(grain, REPEAT, REPEAT)` + `BlendMode.MULTIPLY`（或 Porter-Duff SRC_IN 做纯裁剪）画 strokeBounds 矩形 → restore。纹理只落在笔迹内，成本=一次额外矩形绘制。
   - **防滑动伪影**：给 shader 设 `setLocalMatrix`，把纹理坐标锚定在**页面坐标系**（平移 = -strokeBounds.left/top + 页面偏移），缩放页面时纹理跟随笔迹而非屏幕（第一轮 Implicit Brushes 论文的结论）。
3. **边缘羽化**：笔迹 Path 描边 1px、alpha≈0.3 的同色细描边，或 `BlurMaskFilter(0.8f, NORMAL)` 只用于铅笔（硬件加速下 BlurMaskFilter 仅文字支持，注意降级——所以推荐描边方案）。
   参考：[50 Shaders of Android: Drawing on Canvas（Nutrient）](https://www.nutrient.io/blog/50-shaders-of-android-drawing-on-canvas/)；[BlendMode 中文参考](https://juejin.cn/post/7548720572004663323)

### 5.2 圆珠笔「积墨点」：值得做，但要克制

- 真实圆珠笔停顿/转折处滚珠供墨过多形成墨点，是圆珠笔辨识度的来源之一。主流笔记 App 几乎没人做（Concepts/GoodNotes 的 Ball Pen 都是恒定宽度圆头），属于「差异化加分项」而非必备。
- 低成本实现（不动渲染管线）：在宽度模型里加**停顿检测**——平滑后速度 < 阈值（如 60px/s）持续 > 80ms 时，宽度乘一个脉冲因子（1 + 0.15×停顿秒数，上限 1.3×）且不透明度略升；笔末落点处可选加一个直径≈1.2×当前宽度的小圆点。
- 风险：误判（正常慢速写字拐弯）会让线条出现莫名其妙的胖点。**建议先做成预设参数 `blobEnabled`（默认关），真机 A/B 后再决定默认值。**

---

## 六、延迟优化新招（普通 App 可行部分）

### 6.1 requestUnbufferedDispatch()：零成本，立即做

- `View.requestUnbufferedDispatch()`（API 21+）：请求系统不再批量缓存 MotionEvent，事件到达即分发。手写场景下批量投递会让一帧内的多个采样点「同时」到达，既增加感知延迟又让速度估算抖动。Compose 官方《高级触控笔功能》文档明确推荐在落笔（ACTION_DOWN）时调用。
- 我们已有历史点遍历（batching 处理），不冲突：unbuffered 后历史点变少、实时性提高，处理逻辑不用改。
- 来源：[Android 手写渲染技术演进（掘金，引用 Compose 官方文档）](https://juejin.cn/post/7642626890507796531)；[MotionEventPredictor 官方推荐配套用法](https://juejin.cn/post/7642626890507796531)

### 6.2 androidx.graphics 前缓冲：纯 Java/Kotlin 可用，无需 NDK

- `androidx.graphics:graphics-core` **稳定版 1.0.0（2024-05）→ 1.0.4（2025-12）**，提供：
  - `CanvasFrontBufferedRenderer`（**API 29+**，用 `android.graphics.Canvas` API 的前缓冲渲染器，不需要 OpenGL 栈）：在途笔画先画进 front buffered layer（与显示同步呈现，最低延迟），定期合并进 multi buffered layer（无撕裂）。`cancel()` 支持手掌误触回滚。
  - `LowLatencyCanvasView`：开箱即用的低延迟 View 封装。
  - `GLFrontBufferedRenderer`：需要已有 GL 栈，我们用不上。
- 兼容性教训（release notes）：1.0.2 修复 Android 14+ 不支持 front buffer usage flag 时的闪烁；1.0.3 修复 API<33 全屏闪烁——**接入时必须真机回归 ColorOS 14（API 34）**。
- 来源：[CanvasFrontBufferedRenderer 官方文档](https://developer.android.google.cn/reference/androidx/graphics/lowlatency/CanvasFrontBufferedRenderer)；[androidx.graphics release notes](https://developer.android.google.cn/jetpack/androidx/releases/graphics)
- 架构提示：官方推荐「在途笔迹（低延迟路径）/ 完成笔迹（质量路径）」双路径分层，与我们「实时 Path + 完成烘位图」的现有架构天然吻合，接入点清晰。

### 6.3 预测与回滚

- MotionPredictor 已有 ✅；注意官方强调**预测点只用于临时可视化，不得进最终笔迹数据**（我们已遵守则无需改）。
- `ACTION_CANCEL` / `FLAG_CANCELED`（API 33+，标识疑似非预期接触的抬起）：应支持把该 pointer 的在途笔迹整体回滚删除，而不是当中断笔迹提交。检查我们的 ACTION_CANCEL 路径是否有此语义。

---

## 七、v1.1.8 推荐落地清单（按性价比排序；全部纯 Java + Canvas，无 NDK）

| # | 改动 | 文件 | 具体数值 | 工作量 | 预期效果 |
|---|---|---|---|---|---|
| 1 | 落笔时 `requestUnbufferedDispatch()` | 笔输入 View（一处调用） | ACTION_DOWN 时调用一次 | **0.5h** | 感知延迟立刻下降，240Hz 采样真正生效 |
| 2 | taper 起笔缓动 t² → `t*(2-t)`（easeOutQuad） | `BrushEngine.taperFactorStart` | 与 perfect-freehand 默认对齐 | 0.5h | 起笔「落墨即有」，不再显钝 |
| 3 | 起笔压力滑动平均防粗头 | `BrushEngine.computeWidth` | 前 8 点 `(acc+p)/2` 递推，首点压力地板 0.25 | 1~2h | 消除粗头，起笔形态接近 pf/GoodNotes |
| 4 | 笔尾噪声剔除 | `BrushEngine.computeMesh` | 跳过 distFromEnd < 3px 的中间点（末点保留原始坐标） | 1h | 收笔无毛刺，笔锋干净 |
| 5 | 1€ 滤波替换位置/压力 EMA | `StrokeSmoother`（内部换实现，接口不变） | 位置 fcmin 0.8~4.7Hz（映射 smoothing 滑杆，默认 1.5）、β=0.01、dcutoff=1.0；压力 fcmin=2Hz、β=0.001；**用 getHistoricalEventTime 真实时间戳** | 3~4h | 慢写不抖、快写不拖尾，宽度曲线无台阶 |
| 6 | 轮廓点去重 | `StrokeMesh` 构建 | 同侧相邻轮廓点 minDist² = (baseWidth×0.5)² | 1~2h | 边缘干净，顶点数降 30%+ |
| 7 | 铅笔宽度噪声 + 纸纹 MULTIPLY | `BrushEngine` + 烘焙渲染处 | 半宽 ±10% value noise（3周期/mm）；256² 程序生成噪点图 + BitmapShader + saveLayer/MULTIPLY，localMatrix 锚定页面坐标 | 4~6h | 铅笔从「灰钢笔」变石墨质感 |
| 8 | 完成笔迹等弧长重采样 | `BrushEngine.computeMesh` 入口 | Catmull-Rom，0.5~1px 弧长间隔，宽度随 t 插值 | 4~6h | 转弯处宽度/法向均匀，taper 平滑 |
| 9 | ACTION_CANCEL/FLAG_CANCELED 笔迹回滚 | 笔输入处理 | 取消语义 → 删除在途笔迹而非提交 | 2~3h | 手掌误触不留脏笔画 |
| 10 | （可选）圆珠笔积墨点 | `BrushEngine` + `BrushPreset.blobEnabled` | 速度<60px/s 持续 80ms → 宽度脉冲 ≤1.3× | 2h | 圆珠笔辨识度（默认关，A/B 后定） |
| 11 | （可选，较大）接 CanvasFrontBufferedRenderer | 在途笔迹渲染层 | androidx.graphics:graphics-core:1.0.4，API 29+，需 ColorOS 14 真机回归 | 1~2 天 | 端到端延迟逼近系统手写 |

**建议 v1.1.8 范围：#1~#6（合计约 1 天半），真机两轮验证后再视反馈决定 #7/#8。**

## 附：本轮新增参考链接

- Chromium 1€ 滤波 Android 输入栈默认参数：https://git.iridiumbrowser.de/iridium-browser/iridium-browser/-/raw/m128/ui/base/prediction/one_euro_filter.h
- 1€ Filter 官网（调参流程 + 官方 Java 实现）：https://gery.casiez.net/1euro/
- perfect-freehand 源码（本轮全部公式出处）：
  - https://raw.githubusercontent.com/steveruizok/perfect-freehand/main/packages/perfect-freehand/src/getStrokePoints.ts
  - https://raw.githubusercontent.com/steveruizok/perfect-freehand/main/packages/perfect-freehand/src/getStrokeOutlinePoints.ts
  - https://raw.githubusercontent.com/steveruizok/perfect-freehand/main/packages/perfect-freehand/src/getStrokeRadius.ts
  - https://raw.githubusercontent.com/steveruizok/perfect-freehand/main/packages/perfect-freehand/src/simulatePressure.ts
  - https://raw.githubusercontent.com/steveruizok/perfect-freehand/main/packages/perfect-freehand/src/constants.ts
- Google Ink Stroke Modeler 完整算法文档（wobble smoother/弹簧-质量/重采样/预测器）：https://google.github.io/ink-stroke-modeler/
- CanvasFrontBufferedRenderer 官方文档：https://developer.android.google.cn/reference/androidx/graphics/lowlatency/CanvasFrontBufferedRenderer
- Android 手写渲染技术演进（前缓冲/Ink API/requestUnbufferedDispatch/ACTION_CANCEL 回滚）：https://juejin.cn/post/7642626890507796531
- GoodNotes 6 Pen tool 官方文档（笔尖锐利度/压感灵敏度默认 50%）：https://support.goodnotes.com/hc/en-us/articles/7353756785679-Using-the-Pen-tool
- Concepts 笔刷手册（速度驱动方向）：https://concepts.app/en/manual/brushes-and-tools
- Canvas Shader 实战（BitmapShader/BlendMode）：https://www.nutrient.io/blog/50-shaders-of-android-drawing-on-canvas/
- Catmull-Rom 弧长参数化：https://www.imaginary-institute.com/blog/2014/09/03/efficiently-finding-points-on-catmull-rom-and-bezier-curves/
- OPPO Pencil 240Hz/4096 级压感规格：https://www.smzdm.com/p/85047151/


---

# 第三轮调研：钢笔变体库（2026-08-22）

> 目标：在钢笔工具弹框中提供一组**预调好的笔刷变体**，全部用现有 `BrushPreset` 参数空间表达（`baseWidth/minWidth/maxWidth/opacity/pressureEnabled/pressureCurve/pressureToWidth/pressureToOpacity/velocityToWidth/tiltEnabled/tiltToWidth/smoothing/stabilization/taperStart/taperEnd/blendMode`），不需要新增引擎字段。
>
> 基准：当前默认钢笔 `fountainPen()`（baseWidth 3、min 0.4×、max 1.9×、SOFT、velocityToWidth 0.4、taperStart 0.15、taperEnd 0.5、smoothing 0.2、stabilization 0.1）。下列变体以它为「标准尖」向外拉开差异。

## 一、调研依据（变体设计的事实基础）

### 1.1 真实钢笔尖线宽规格（mm）

- **万宝龙官方尖谱**（[followmypenblog 万宝龙尖全览](https://www.followmypenblog.com/a-comprehensive-overview-of-montblanc-fountain-pen-nibs-which-nib-should-i-choose/)）：EF **0.43mm** / F **0.50mm** / M **0.62mm**（最畅销，黄金中点）/ B **0.92mm** / BB **1.05mm**；斜切尖 OM/OB/OBB 靠**握笔角度**改变线宽。
- 行业通用范围（[Scriveiner 笔宽指南](https://scriveiner.com/blogs/scriveiner-blog/pen-size-chart)）：EF 0.3~0.5mm、M 0.7~0.9mm；日系尖普遍比欧美同标号细（[Pen Heaven 对照](https://www.penheaven.com/blog/fountain-pen-nib-width-comparison)）。
- **弹性尖（flex nib）**：无压 0.5mm → 满压 2.0mm，约 **4 倍动态范围**（[Fountain Pen Revolution](https://fprevolutionusa.com/blogs/news/flex-nib-pressure-control-key-line-variation)）；[Tom's Studio EF 半弹尖](https://tomsstudio.com/products/toms-studio-fountain-pen-nib-extra-fine-semi-flex)：0.5 → 1.5mm（3 倍）。弹性尖是靠压力撑开两瓣尖出锋，**压感是它的全部**。
- **美工尖（fude / bent nib）**：笔尖上弯 40°~55°，**靠笔与纸面的夹角（tilt）控制线宽**——竖着写极细、放平刷出宽面，可覆盖细线到大笔触（[Tom's Studio Fude](https://tomsstudio.com/products/fountain-pen-nib-fude)、[Sailor Fude de Mannen 40° vs 55° 对比](https://www.idyllsketching.com/2025/04/25/comparing-the-sailor-fude-de-mannen-fountain-pens-40-vs-55/)）。

**宽度换算**：当前默认钢笔 baseWidth 3 ≈ 真实 M 尖 0.62mm → 换算系数约 4.8 逻辑单位/mm。故 EF(0.43mm)≈2.1、B(0.92mm)≈4.4、BB(1.05mm)≈5.0。下表 baseWidth 按此取整。

### 1.2 主流 App 的钢笔变体/档位做法

- **GoodNotes 6**（[官方 Pen tool 文档](https://support.goodnotes.com/hc/en-us/articles/7353756785679-Using-the-Pen-tool)）：Fountain Pen 可调**笔尖锐利度**（Min=圆头 ↔ Max=尖头，控制两端 taper 形态）+**压感灵敏度**（Min 不归零，保留压感地板），默认均为 50%；Brush Pen 高压感、面向 lettering。用户社区流行「笔锋 100% + 压感 75%」的强笔锋配置（[微博用户实测](https://weibo.com/5838285528/Js4Y1F9FK)、[Lemon8 设置分享](https://www.lemon8-app.com/@allievikstromm/7398055285480948230)）。
- **Noteful**：钢笔压感灵敏度**很高**，轻压即出明显粗细变化，靠两端 taper 出锋取胜（[Paperless X 横评](https://beingpaperless.com/best-fountain-pen-10-handwriting-apps-1-winner/)）。
- **Concepts**：Fountain Pen 是**速度驱动**的墨量模型——慢=细、快=粗（[Concepts 官方手册](https://concepts.app/en/manual/brushes-and-tools)）；同门的普通 Pen 则快=细。证明「速度主导宽度」是可被用户接受的独立笔型。
- **styluslabs Write**：无压感设备上纯靠速度驱动宽度做出招牌钢笔手感（第一、二轮已引），说明速度型变体对手指/无压感输入也有价值。
- **Samsung Notes**：钢笔为压感型圆尖；书法笔（calligraphy）为**扁尖**——横粗竖细，宽度由笔画方向决定（对应我们的 tilt/orientation 通道）。
- **华为笔记/天生会画**：钢笔支持压感出锋；天生会画允许用户**自定义压力曲线**（X=压力、Y=输出），默认直线对角线（[华为官方支持文档](https://consumer.huawei.com/cn/support/content/zh-cn16000585/)）。
- **Notability 反例**：钢笔「线宽不随书写变化」被评测批评像圆珠笔——变体库中每个变体都必须有可感知的宽度动态。

### 1.3 变体设计原则

1. 每个变体至少在一个维度上走到参数空间的**边缘**（极细 / 极宽动态 / tilt 驱动 / 速度驱动 / 强 taper），不凑数。
2. `minWidth/maxWidth` 的**比值**（动态范围）是手感差异的第一来源：EF 尖刚性大（比值小 ~2.5×）、弹性尖比值大（~6×）。
3. `taperEnd` 对应 GoodNotes 的「笔尖锐利度」；笔锋向变体（brush pen）两端 taper 都要长。
4. 所有变体保持 `opacity=1`、`blendMode=SRC_OVER`、`pressureToOpacity=0`（除非特别注明），与钢笔「墨线均匀」的定位一致。

## 二、钢笔变体参数表（可直接抄进 `BrushPreset`）

> 数值即最终代码值；`minWidth`/`maxWidth` 列给出「×baseWidth 系数 / 绝对值」两种写法，代码里建议沿用现有的 `baseWidth * 系数` 写法。brushType 均用 `FOUNTAIN_PEN`（美工尖、毛笔锋可选 `CALLIGRAPHY_PEN`）。

| # | 中文名 / 英文名 | baseWidth | minWidth | maxWidth | pressureCurve | pressureToWidth | velocityToWidth | tiltEnabled / tiltToWidth | smoothing / stabilization | taperStart / taperEnd |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 极细尖 EF / Extra Fine | **2.0** | 0.6× = 1.2 | 1.5× = 3.0 | BALANCED | 0.7 | 0.25 | false / 0 | **0.15 / 0.05** | 0.10 / 0.35 |
| 2 | 标准尖 M / Standard（现默认） | 3.0 | 0.4× = 1.2 | 1.9× = 5.7 | SOFT | 1.0 | 0.4 | false / 0 | 0.2 / 0.1 | 0.15 / 0.5 |
| 3 | 粗尖 B / Broad Signature | **4.5** | 0.5× = 2.25 | 1.6× = 7.2 | BALANCED | 0.85 | 0.25 | false / 0 | **0.3 / 0.15** | 0.10 / 0.4 |
| 4 | 弹性尖 / Flex Nib | 3.0 | **0.3× = 0.9** | **2.4× = 7.2** | SOFT | 1.0 | 0.2 | false / 0 | 0.25 / 0.15 | **0.2 / 0.6** |
| 5 | 美工尖 / Fude (Bent Nib) | 4.0 | **0.15× = 0.6** | 2.5× = 10.0 | BALANCED | 0.5 | 0.2 | **true / 0.9** | 0.25 / 0.15 | 0.10 / 0.45 |
| 6 | 疾书尖 / Speed Writer | 3.0 | 0.5× = 1.5 | 1.7× = 5.1 | FIRM | **0.3** | **0.6** | false / 0 | **0.15 / 0.05** | 0.10 / 0.55 |
| 7 | 毛笔锋 / Brush Lettering | 3.5 | **0.2× = 0.7** | 2.2× = 7.7 | SOFT | 1.0 | 0.35 | false / 0 | 0.25 / **0.2** | **0.3 / 0.8** |

公共字段（全部变体）：`pressureEnabled=true`、`pressureToOpacity=0`、`tiltToOpacity=0`、`opacity=1`、`blendMode=SRC_OVER`、`textureId=null`。例外：#5 的 tiltEnabled/tiltToWidth 见表；#4 可选 `pressureToOpacity=0.1` 模拟重压墨量加深（建议先 A/B）。

## 三、逐个变体说明（手感 + 来源依据）

**1. 极细尖 EF（Extra Fine）**——手感：线条细而挺、轻微压感，起收笔短促干净，适合小字密集笔记、批注、公式下标；低平滑（0.15）保证精确跟笔。依据：万宝龙 EF 0.43mm（硬尖、精确、刮纸感对应窄动态范围）；GoodNotes「笔尖锐利度 Max + 压感 Min（不归零）」组合的社区用法。

**2. 标准尖 M（Standard）**——现有默认，不动。依据：万宝龙 M 0.62mm 黄金中点；GoodNotes 默认「锐利度 50% + 压感 50%」。

**3. 粗尖 B（Broad Signature）**——手感：浓墨重彩、顺滑饱满，宽度动态相对收敛（粗尖本身不需要太大变化），加重平滑/稳定（0.3/0.15）让大字和签名不抖。适合签名、标题、重点标注。依据：万宝龙 B 0.92mm/BB 1.05mm（官方定位即「签名与正式文书」）。

**4. 弹性尖（Flex Nib）**——手感：轻压如 EF、重压铺出宽墨带，0.9→7.2 约 8 倍绝对跨度（0.3×~2.4×），SOFT 曲线让轻压区格外灵敏；taper 两端加长（0.2/0.6）配合出锋。适合花体英文、练字、手账装饰。依据：FPR 弹性尖 0.5→2.0mm、Tom's Studio 半弹尖 0.5→1.5mm 的实测动态范围。

**5. 美工尖（Fude / Bent Nib）**——手感：**tilt 主导宽度**——立笔细线、侧锋宽面，横竖撇捺靠笔的角度自然出粗细；压感降为辅助（0.5）。min 仅 0.15×，动态全库最大。依据：Sailor Fude de Mannen 40°/55°、Tom's Studio Fude 的「角度控宽」机制；Samsung Notes 书法笔/万宝龙 OM/OB 斜切尖同理。注意：依赖手写笔 tilt 轴；无 tilt 输入时由压感（0.5）+ 速度兜底，真机需验证 OPPO Pencil tilt 数据质量。

**6. 疾书尖（Speed Writer）**——手感：**速度主导**——慢速垫底稳重、连笔撇捺自动变细出锋，压感压到 0.3、曲线 FIRM 防止轻压宽度乱飘；低平滑保证快速连笔跟手。适合课堂速记、草书连笔；也是无压感输入（手指）下最有「笔锋」的选择。依据：styluslabs Write 的速度驱动模型、Concepts 的速度型笔、Noteful 高灵敏度快写出锋的口碑。

**7. 毛笔锋（Brush Lettering）**——手感：两端都出尖（taperStart 0.3 / taperEnd 0.8，对应 GoodNotes「笔尖锐利度 Max」），轻抬即细、按下即粗，最强拉线稳定（0.2）让大字笔画弧度顺滑。适合标题 lettering、中文标题字。依据：GoodNotes Brush Pen（高压感 lettering 定位）与用户社区「笔锋 100% 压感 75%」配置；perfect-freehand 强调两端 taper 是「锋」的来源（第一、二轮结论）。

## 四、差异覆盖检查与落地建议

- **覆盖维度**：线宽档位（#1/#2/#3）✅、压感动态（#4/#7）✅、tilt 驱动（#5）✅、速度驱动（#6）✅、taper 形态（#1 短钝 ↔ #7 双尖）✅。相邻变体至少在两个参数维度上有 ≥30% 差异，无凑数项。
- **建议弹框排序**：标准 M（默认选中）→ EF → B → 疾书尖 → 弹性尖 → 毛笔锋 → 美工尖（按「普通笔记 → 创作向」渐进排列）。
- **落地位置**：`BrushPreset.java` 的 defaults 区新增 7 个静态工厂方法（或在钢笔弹框内作为钢笔的子预设列表）；UI 弹框显示中文名 + 一句手感描述（上表「手感」列的浓缩版）。
- **待真机验证**：#5 的 tilt 轴质量（OPPO Pencil）；#4/#7 的大 maxWidth 在细格纸页面下是否过粗（若过粗，把 baseWidth 降 0.5 而保持系数不变）；#6 的 velocityToWidth=0.6 依赖第二轮落地的速度平滑（40ms 窗口 + smoothstep），未落地前先降到 0.45。

## 附：本轮新增参考链接

- 万宝龙全尖线宽对照（EF 0.43/F 0.50/M 0.62/B 0.92/BB 1.05mm）：https://www.followmypenblog.com/a-comprehensive-overview-of-montblanc-fountain-pen-nibs-which-nib-should-i-choose/
- 通用尖宽指南（EF 0.3~0.5 / M 0.7~0.9mm）：https://scriveiner.com/blogs/scriveiner-blog/pen-size-chart
- 弹性尖 0.5→2.0mm 动态范围：https://fprevolutionusa.com/blogs/news/flex-nib-pressure-control-key-line-variation
- Tom's Studio 半弹尖（0.5→1.5mm）与 Fude 美工尖（角度控宽）：https://tomsstudio.com/products/toms-studio-fountain-pen-nib-extra-fine-semi-flex ，https://tomsstudio.com/products/fountain-pen-nib-fude
- Sailor Fude de Mannen 40° vs 55° 对比：https://www.idyllsketching.com/2025/04/25/comparing-the-sailor-fude-de-mannen-fountain-pens-40-vs-55/
- GoodNotes 6 Pen tool 官方文档（锐利度/压感默认 50%）：https://support.goodnotes.com/hc/en-us/articles/7353756785679-Using-the-Pen-tool
- Concepts 笔刷手册（Fountain Pen 速度驱动墨量模型）：https://concepts.app/en/manual/brushes-and-tools
- Paperless X 十款 App 钢笔横评（Noteful 高压感/taper 结论）：https://beingpaperless.com/best-fountain-pen-10-handwriting-apps-1-winner/
- 华为天生会画自定义压力曲线：https://consumer.huawei.com/cn/support/content/zh-cn16000585/
- GoodNotes 用户社区「笔锋 100 + 压感 75」配置：https://weibo.com/5838285528/Js4Y1F9FK
