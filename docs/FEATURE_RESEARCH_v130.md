# 手写笔记应用功能调研报告（FEATURE_RESEARCH v1.3.0）

> 调研时间：2026-08-21 ｜ 目标平台：Android + Java ｜ 目标设备：OPPO Pad（ColorOS 14，可能无 GMS）
> 所有版本号均经上网核实；URL 均为调研中实际查阅的来源。

---

## 一、手写转文字（手写识别 / OCR）

### 推荐方案

**首选：Google ML Kit Digital Ink Recognition（bundled standalone SDK）**。这是唯一面向「触屏实时笔迹」（Ink / 笔划序列）的成熟离线方案，与 Gboard 手写输入同源。standalone 版（`com.google.mlkit:*`）的推理与模型管理都在 SDK 内部完成，**不走 Google Play Services**，理论上可在无 GMS 的 ColorOS 上离线使用。

### 关键依赖与版本

- `com.google.mlkit:digital-ink-recognition:19.0.0`（2025-08-29 发布，支持 16KB 页大小；targetSdk 35+ 必须用 19.0.0）
- 拍照 OCR 对比项（bundled 版）：`com.google.mlkit:text-recognition-chinese:16.0.1`
- **不要用** `com.google.android.gms:play-services-mlkit-*`（thin SDK），它依赖 Play Services 动态下发模型，无 GMS 设备直接不可用。

### 中文手写模型

- BCP-47 标签是 **`zh-Hani`**（区域变体：`zh-Hani-CN` / `zh-Hani-HK` / `zh-Hani-TW`），不是简单的 `zh`。
- 识别完全离线，但每语言模型需先下载（约 20MB/语言）。
- 模型管理 API 已变更：用 `RemoteModelManager.getInstance()`（isModelDownloaded / download），文档中旧称 `DigitalInkRecognitionModelManager` 已不在公开 API。
- 另有形状/emoji 分类器（`zxx-Zsym-x-shapes`、`zxx-Zsye-x-emoji`）和手势分类（`zh-Hani-x-gesture`），可用于笔记编辑手势。

### Java 接入骨架

```java
// build.gradle
implementation 'com.google.mlkit:digital-ink-recognition:19.0.0'

// 1. 模型管理
DigitalInkRecognitionModelIdentifier id =
    DigitalInkRecognitionModelIdentifier.fromLanguageTag("zh-Hani"); // 可能抛 MlKitException，需判空
DigitalInkRecognitionModel model = DigitalInkRecognitionModel.builder(id).build();

RemoteModelManager mgr = RemoteModelManager.getInstance();
mgr.isModelDownloaded(model)
   .addOnSuccessListener(downloaded -> {
       if (!downloaded) {
           mgr.download(model, new DownloadConditions.Builder().build())
              .addOnSuccessListener(v -> Log.i(TAG, "Model downloaded"))
              .addOnFailureListener(e -> Log.e(TAG, "download failed: " + e));
       }
   });

// 2. 识别器
DigitalInkRecognizer recognizer = DigitalInkRecognition.getClient(
    DigitalInkRecognizerOptions.builder(model).build());

// 3. 笔迹采集（自定义 View 的 onTouchEvent 中）
Ink.Builder inkBuilder = Ink.builder();
Ink.Stroke.Builder strokeBuilder; // ACTION_DOWN 创建, ACTION_MOVE addPoint, ACTION_UP 收尾
strokeBuilder.addPoint(Ink.Point.create(event.getX(), event.getY(), System.currentTimeMillis()));

// 4. 识别（带上下文可显著提精度）
RecognitionContext ctx = RecognitionContext.builder()
    .setPreContext(preContext)                     // 最多取末尾 20 字符
    .setWritingArea(new WritingArea(width, height)) // 按单行高度估算
    .build();
recognizer.recognize(ink, ctx)
    .addOnSuccessListener(result -> {
        String best = result.getCandidates().get(0).getText();
    })
    .addOnFailureListener(e -> { /* 处理 */ });
```

### Digital Ink vs Text Recognition v2 怎么选

官方明确分工：Digital Ink 处理屏幕笔划序列（边写边识别）；Text Recognition 处理照片/相机图片。**建议两者并存**：书写转文字用 Digital Ink；「拍照导入纸面笔记」整页 OCR 用 `text-recognition-chinese:16.0.1`（bundled 版）。TR v2 中文模型对工整手写有一定容忍度，但无笔顺信息、对连笔中文效果一般，不适合实时手写输入。

### 无 GMS 设备可用性（关键）

- 结论：**bundled standalone 版（`com.google.mlkit:*`）不依赖 GMS**，模型推理和下载都在 SDK 内完成；多家第三方文档明确 bundled 模型 "works offline and does not require Google Play services"。
- 注意：有相反证据（mlkit issue #236 反映部分场景依赖 Play services），且未找到国产机实测的权威记录，因此**必须在 OPPO Pad 真机验证**两点：
  1. 国内网络下 `RemoteModelManager.download` 首次下载 `zh-Hani` 是否成功（首次下载需要能访问 Google 服务器，建议首次启动 Wi-Fi 下预下载）；
  2. 手写笔采样频率下 Ink 构建与识别延迟。

### 坑点

1. 首次模型下载需联网且可能受国内网络影响——做下载失败重试与降级提示。
2. 模型检查/下载是异步操作，放后台线程，有插件反馈卡 UI 线程导致 ANR。
3. `DigitalInkRecognitionModelIdentifier.fromLanguageTag` 对不支持的 tag 返回 null，需判空。
4. minSdk 要求 21+；混淆规则：`-keep class com.google.mlkit.** { *; }`。

### 不依赖 GMS 的备选：华为 HMS ML Kit

- 端侧文本识别（拍照 OCR）：`com.huawei.hms:ml-computer-vision-ocr` + 中英文模型包 `com.huawei.hms:ml-computer-vision-ocr-cn-model`，最新版以英文文档为准（3.17.11.304）。
- **华为没有等价的端侧笔划级手写识别**：其"手写体识别"是图片型手写 OCR（云侧，需联网 + AGC 配额），无法支撑"边写边识别"，不能作为 Digital Ink 的替代，只可作拍照整页 OCR 备选。
- 注意：华为手写体识别的确切 Maven 坐标未能核实（华为文档站 JS 渲染受限），如需使用要登录开发者后台确认。

### 参考来源

- https://developers.google.cn/ml-kit/release-notes?hl=en
- https://developers.google.cn/ml-kit/vision/digital-ink-recognition/android?hl=en
- https://developers.google.cn/ml-kit/vision/digital-ink-recognition/base-models?hl=zh-cn
- https://developers.google.cn/ml-kit/vision/digital-ink-recognition?hl=en
- https://developers.googleblog.com/digital-ink-recognition-in-ml-kit/
- https://mvnrepository.com/artifact/com.google.mlkit/digital-ink-recognition
- https://capawesome.io/docs/sdks/capacitor/barcode-scanner/ （bundled 不依赖 Play Services 佐证）
- https://github.com/Acuant/AndroidSDKV11/blob/master/README.md
- https://developer.huawei.com/consumer/en/doc/hiai-guides/overview-sdk-0000001051070278
- https://developer.huawei.com/consumer/cn/forum/topic/0202107711589894889 （华为手写体识别介绍）
- https://github.com/googlesamples/mlkit/issues/236 （GMS 依赖反证）
- https://github.com/flutter-ml/google_ml_kit_flutter/issues/598 （下载 ANR 坑点）

---

## 二、语音转文字

### 推荐方案

**不要以系统 SpeechRecognizer 作为唯一方案。推荐「Vosk 离线识别为主、SpeechRecognizer 为可选增强」的双层架构。**

理由：

- ColorOS 14 国行固件不带 GMS，系统语音识别服务要么缺失（`isRecognitionAvailable()` 返回 false），要么绑定厂商自家实现，可用性不可控。
- 「录音文件转文字」SpeechRecognizer **根本不支持文件输入**（只从麦克风实时采集），文件转写必须走能喂 byte[] 的库（Vosk / sherpa-onnx）。
- Vosk 完全离线、无 GMS 依赖、Apache 2.0、Java 原生 API，中文小模型仅 42MB。

### Android 原生 SpeechRecognizer（API 31+ 设备端识别）

关键 API：

- `SpeechRecognizer.isOnDeviceRecognitionAvailable(context)`（API 31+）
- `SpeechRecognizer.createOnDeviceSpeechRecognizer(context)`（API 31+）
- `RecognizerIntent.EXTRA_PREFER_OFFLINE = true`
- `checkRecognitionSupport(Intent, Executor, RecognitionSupportCallback)` + `triggerModelDownload(...)`（API 33+）
- 需要 `RECORD_AUDIO` 权限；targetSdk ≥ 30 时 manifest 需加：
  ```xml
  <queries><intent><action android:name="android.speech.RecognitionService"/></intent></queries>
  ```
  否则 `isRecognitionAvailable()` 返回 false。

国产 ROM 上的坑：

- 设备端识别服务（Android 12 引入）在 Pixel 上由 Google Speech Services 提供，**ColorOS/MIUI 国行固件无 GMS 时该服务不存在**，`isOnDeviceRecognitionAvailable()` 直接返回 false。
- 常见故障：`bind to recognition service failed`、`ERROR_CLIENT(5)`、`ERROR_INSUFFICIENT_PERMISSIONS(9)`（小米语音助手拦截）等，行为因厂商而异。
- 即便可用，识别走厂商云端（隐私 + 断网即挂），与「离线转写」需求不符。
- **结论**：只作运行时探测到的增强通道，探测通过才启用；不能作为核心能力。ColorOS 14 在 OPPO Pad 上的具体返回值没有公开实测数据，需真机跑一行代码验证。

### 首选备选：Vosk 离线识别

Maven 坐标（2026-08 核实）：

```groovy
implementation 'com.alphacephei:vosk-android:0.3.75@aar'
implementation 'net.java.dev.jna:jna:5.18.1@aar'
```

中文模型（官网模型列表，Apache 2.0）：

- `vosk-model-small-cn-0.22`：**42MB**，运行时约 300MB 内存，官方定位就是 Android/RPi。WER：THCHS 17.15 / SpeechIO-02 23.54（安静朗读场景不错，嘈杂口语场景一般）。
- `vosk-model-cn-0.22`：1.3GB 大模型（WER 约减半），服务器定位，不建议内置，可作可选下载。

Java 落地要点：

```java
Model model = new Model(modelPath);                 // 模型先解压到本地目录
Recognizer rec = new Recognizer(model, 16000.0f);   // 必须 16kHz
while ((n = in.read(buf)) >= 0)
    if (rec.acceptWaveForm(buf, n)) { rec.getResult(); ... }
rec.getFinalResult();
rec.setWords(true); // 词级时间戳，可做「点击文字跳转录音位置」
```

坑点：

- **音频格式**：只吃 16kHz / 单声道 / 16bit PCM。建议录音直接用 `AudioRecord` 录 PCM 存 WAV；已有 m4a/aac 需 `MediaExtractor` + `MediaCodec` 解码重采样。
- 42MB 模型不要打包进 APK，首次启动下载解压到 `filesDir` 并校验完整性（官方 demo 有 `StorageService` 可参考）。
- 官方 demo 配置：`abiFilters 'armeabi-v7a','arm64-v8a','x86_64','x86'`，minSdk 21。

### 其他备选

- **sherpa-onnx（重点关注的升级路径）**：最新 v1.13.6，GitHub releases 有预编译 Android AAR（Java 包 `com.k2fsa.sherpa.onnx`）。中文走 Paraformer 模型（int8 量化约 200+MB，精度明显优于 Vosk 小模型，自带标点）。文件转写用 `OfflineRecognizer` + `OfflineStream.acceptWaveform(float[], sampleRate)`。代价：AAR 约 27MB；so 与 AAR 版本必须严格对应；个别新 SoC 上 onnxruntime 版本坑。
- **讯飞 AIkit 离线听写 SDK**：中文离线效果最好的一档，但商用授权 + 首次联网激活 license。
- whisper.cpp Android：中文效果一般、CPU 推理慢，不推荐作实时转写主力。
- 云端兜底：讯飞/百度/火山在线 ASR API 作可选「联网高精度转写」开关。

### 落地建议

1. 录音模块用 `AudioRecord` 直接产 16kHz 单声道 PCM/WAV。
2. 抽象 `interface SpeechTranscriber`：先实现 `VoskTranscriber`（首启下载 42MB）；SpeechRecognizer 实现 `SystemTranscriber` 探测通过才启用。
3. Vosk small-cn 实测不达标（讲课/会议口语场景大概率不够）则升级 sherpa-onnx + Paraformer int8。
4. 用 Vosk 词级时间戳做「文字-录音联动回放」差异化功能。

### 参考来源

- https://developer.android.google.cn/reference/android/speech/SpeechRecognizer?hl=zh-cn
- https://central.sonatype.com/artifact/com.alphacephei/vosk-android
- https://alphacephei.com/vosk/models 、https://alphacephei.com/vosk/android
- https://github.com/alphacep/vosk-android-demo
- https://github.com/k2-fsa/sherpa-onnx 、https://github.com/k2-fsa/sherpa-onnx/releases
- https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-paraformer/paraformer-models.html
- https://www.xfyun.cn/doc/asr/AIkit_offline_iat/Android-SDK.html
- 国产 ROM 坑：https://qidizi.github.io/SpeechRecognizer-bind-to-recognition-service-failed/ 、https://juejin.cn/post/6844903861405089799

---

## 三、和纸胶带（Washi Tape）

### 竞品形态（对标 GoodNotes 6 Tape 工具）

- **笔画式放置**：拿笔在页面上"画"一段胶带，粗细用滑块调节，落笔即生成沿笔迹的胶带条带（不是拖固定贴纸）。
- **遮罩背诵（active recall）**：盖住答案，点一下"揭开"查看，再点恢复遮盖——GoodNotes 区别于普通贴纸的关键交互。
- 可在胶带上书写；预设纯色 + 花纹图案，支持自定义槽位和素材商城扩展。
- Notability 无独立胶带工具，社区做法是插入带撕边、半透明的 washi PNG 贴纸。

常见花纹（建议首批内置清单）：纯色/半透明纯色（马卡龙色系最高频）、条纹（横/竖/斜/彩虹）、波点、格子（gingham/棋盘格）、碎花植物、渐变、几何（chevron/波浪/星星/爱心）、特殊质感（金箔/闪粉/水彩）。宽度做 3–5 档粗细滑块即可。

视觉特征：长条矩形、**两端撕纸/锯齿小缺口**、半透明款隐约透出下层文字、常斜贴 ±15°~45°、四角轻微阴影制造贴纸上纸的立体感。

### Android 渲染方案（零第三方依赖）

**此功能不需要任何第三方依赖**，`android.graphics` 全家桶即可，自 API 1 起可用，零 GMS 依赖。

核心架构：**两层合成（Pattern 层 + Mask 层）**——`saveLayer` 开辟离屏层，先画满花纹（DST），再用不规则边缘 Path 以 `DST_IN` 模式做遮罩（SRC），最后整体半透明合成。

```java
// 1) 花纹 Shader：小 tile 位图平铺，随胶带旋转
Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
BitmapShader patternShader = new BitmapShader(tileBitmap,
        Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
Matrix m = new Matrix();
m.setRotate(tapeAngle, cx, cy);
patternShader.setLocalMatrix(m);
fillPaint.setShader(patternShader);

// 2) 离屏层：先画花纹，再 DST_IN 遮罩出撕边轮廓
int sc = canvas.saveLayer(tapeBounds, null);   // 必须用两参版！
canvas.drawRect(tapeBounds, fillPaint);
maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
canvas.drawPath(buildTapeOutlinePath(), maskPaint); // 带撕边的轮廓
maskPaint.setXfermode(null);
canvas.restoreToCount(sc);

// 3) 整体半透明（saveLayer 的 Paint 设 alpha 如 0xB0）+ setShadowLayer 投影
```

花纹 tile 的程序化生成（无需美术资源）：

- 条纹：`LinearGradient` 双色硬边渐变或循环 `drawRect`；斜纹 = 画完旋转 45°。
- 波点：tile 上 1 个整圆 + 四角各 1/4 圆保证无缝。
- 格子 gingham：两条半透明色带正交叠加，重叠处自然加深。
- 渐变：`LinearGradient` / `RadialGradient`。
- 碎花/复杂图案：建议直接用美术无缝贴图资源。

两端撕边：Path 轮廓的两端短边用**随机折线锯齿**，且用固定 seed 的 `Random(seed)` 保证同段胶带撤销/重绘时边缘不变形。

遮罩背诵交互：胶带数据模型存 `Path/笔迹点列 + 宽度 + 花纹 id + 角度 + revealed 布尔`；`revealed=true` 时跳过绘制或画 20% 虚影；点击命中用 `Region` 或点到折线距离判断。

### 坑点

1. **`Canvas.saveLayer(RectF, Paint, int)` 已弃用并移除**（API 26 弃用、28 移除），必须用两参版 `saveLayer(RectF, Paint)`（API 21+）。
2. Xfermode 合成必须发生在离屏 layer 上；异常时 `setLayerType(LAYER_TYPE_SOFTWARE, null)` 兜底。
3. **不要用 `canvas.clipPath()` 做撕边**：硬件加速下无抗锯齿，边缘生硬。正确做法是 `saveLayer + DST_IN` + 抗锯齿 Paint。
4. tile 图案边缘像素必须对缝，否则平铺出接缝；`MIRROR` 可掩盖非对称接缝。
5. `saveLayer` bounds 只取胶带外接矩形，不要整页。
6. GoodNotes Android 版实为 Web 套壳（依赖 Chrome），性能口碑差——原生 Canvas 方案是差异化优势。

### 参考来源

- https://www.goodnotes.com/blog/tape-tool-tips-tricks （GoodNotes 胶带工具官方形态）
- https://kdigitalstudio.com/products/washi-tape-digital-stickers （撕边/透明版本描述）
- https://allaboutplanners.com.au/my-all-time-favorite-washi-tapes-and-how-i-organize-them/
- https://code.tutsplus.com/android-sdk-drawing-with-pattern-fills--mobile-19527t （BitmapShader 平铺）
- https://gist.github.com/nickbutcher/4179642450db266f0a33837f2622ace3
- https://juejin.cn/post/7491927782772932623 （PorterDuff 18 种模式实战）
- https://github.com/BigBadaboom/androidsvg/issues/164 （saveLayer saveFlags 弃用）

---

## 四、形状工具 UX

### 竞品交互（GoodNotes 6 / Notability）

**GoodNotes 6**：

- 形状：线条、曲线、矩形、圆角矩形、圆形、三角形、图表形状、连接线（Connectors，吸附形状边缘、移动时保持连接）。
- 三种入口：①工具栏选形状工具直接画；②AutoShape 图标；③**Draw-and-Hold**——一笔画完在末端**停顿按住**→自动规整→**不松手仍可拖动笔尖调整缩放和角度**→松手定型（核心细节：规整后按住期间形状是"活的"）。
- 规整后可改描边色/粗细/填充/角样式，甚至形状类型互换；白色圆点手柄=缩放，蓝色矩形手柄=非等比调整，外凸圆点=旋转。
- 荧光笔扩展：可设"荧光笔始终画直线"。

**Notability**：

- 形状：圆、椭圆、三角形、正方形、矩形、五边形、六边形、箭头、直线、曲线。
- 任何笔工具画完停顿按住即吸附；开关在设置 > Note Editor > Shapes Detection。自 9.0 起**没有独立形状工具按钮**。
- 独特细节：按住期间可缩放；红色吸附参考线；**箭头画法：先画线，再回头画箭头头部**；三点曲线可拖控制点回直线。

### 我们的设计建议

- 选择器形状集合取并集：**直线、箭头、曲线、矩形（含圆角矩形）、椭圆/圆、三角形、菱形、五角星**（菱形/五角星两家官方没列，但用户预期高）。
- 排布：主工具栏放"形状"按钮 → 弹出**水平小条**列形状预设 + 一个"AutoShape/自由识别"项；不要学 Notability 完全藏掉入口。
- Draw-and-Hold 还原点：①停顿判定（300–500ms 无位移）→ ②100–150ms 形变插值动画（不要瞬移）→ ③按住期间可继续调形 → ④松手定型、支持撤销。

### Android 实现方案（按推荐排序）

**方案 A（快速交付）：Desert Ant Labs Shapes SDK**

- 依赖：`implementation("ai.desertant:shapes:0.7.3")`（Maven Central 核实）。
- 单笔点列 → 识别矩形/椭圆/三角形/五角星/直线，自动吸附轴对齐/正圆/15° 角；单笔 <10ms；模型 ~1.3MB 随 AAR 打包，**纯端侧无网络无 GMS**。
- 坑：**仅单笔画**（两笔画形状需自己做笔画合并）；Source-Available 许可证，免费额度 10 万月活设备；Kotlin 写的，Java 项目需带 kotlin-stdlib。

**方案 B（零依赖、纯 Java 可抄，推荐自研路线）：$1 Unistroke Recognizer**

- 经典几何模板匹配算法（华盛顿大学），核心约 100 行代码。
- 最佳参考实现：Flutter 包 `one_dollar_unistroke_recognizer` v1.3.5（开源笔记应用 Saber 生产使用中），Dart 源码极简，逐行翻成 Java 无难度。
- 关键增强：直线用专用算法判定；`convertToCanonicalPolygon()/convertToRect()/convertToCircle()` 等把用户笔画换算成规整几何体（正是 snapping 落笔的代码）；默认模板含线/圆/矩形/三角形/星形，可加菱形/箭头。
- 坑：多笔画形状（箭头）需自己分段处理；菱形 vs 正方形要控制模板角度。

**方案 C（兜底）：AOSP 内置 `android.gesture` 包**——`GestureOverlayView` + `GestureLibraries`，API 4 起，无 GMS，但精度差，仅作原型。

交互层（自研）：`MotionEvent` 流上做静止检测（时间窗 + 位移阈值），触发识别后用 `ValueAnimator` 做原笔迹到规整形状的插值动画，手指未抬起时把笔点位移映射为形状缩放/旋转。

### 参考来源

- https://support.goodnotes.com/hc/en-us/articles/13682939148943-Shapes-and-Diagramming-Draw-perfect-lines-curves-and-shapes
- https://support.gingerlabs.com/hc/en-us/articles/226905028-Creating-and-Styling-Perfect-Shapes
- https://beingpaperless.com/goodnotes-vs-notability/
- https://central.sonatype.com/artifact/ai.desertant/shapes 、https://github.com/Desert-Ant-Labs/shapes
- https://depts.washington.edu/acelab/proj/dollar/index.html
- https://pub.dev/packages/one_dollar_unistroke_recognizer 、https://github.com/adil192/one_dollar_unistroke_recognizer
- https://github.com/xournalpp/xournalpp/issues/2275

---

## 五、贴纸（Sticker）

### 推荐方案

- **交互层**：不引第三方依赖。贴纸应作为**页面内容模型**的一部分（GoodNotes 做法）：`StickerData { assetPath, x, y, scale, rotation, flipX }` 存页面逻辑坐标，渲染时 `页面Matrix.concat(贴纸Matrix)` 用 `canvas.drawBitmap(bitmap, matrix, paint)`，随页面缩放/翻页/导出 PDF 天然正确。
- 若只做"图片编辑"式临时贴纸，可直接抄 **wuapnjie/StickerView**（Apache 2.0，纯 Java，作者明确建议拷源码改；已停更，不要直接依赖 JitPack）。
- **素材层**：内置 `assets/stickers/<分类>/` 分包 + 每分类 `manifest.json`，`AssetManager.list()` 枚举、`BitmapFactory.decodeStream()` 解码（设 `inScaled=false`）。列表缩略图用 Glide `com.github.bumptech.glide:glide:5.0.9`（minSdk 23）。
- **无 GMS 注意**：不要依赖 `androidx.emoji2` 的可下载字体方案（需 GMS 字体提供方）；要么 `emoji2-bundled`（内置字体约 10MB），要么全用 PNG 素材。

### 交互做法

- 每个 Sticker 持 `android.graphics.Matrix`；单指拖动 `postTranslate()`，双指 `ScaleGestureDetector` + 两点角度差 `postRotate()`（**必须指定贴纸中心 pivot**，否则绕原点转飞）。
- 选中态画虚线框 + 四角控制柄（删除/缩放旋转/翻转，`BitmapStickerIcon` 命中检测）；点空白取消选中。
- 手势冲突：未点中贴纸时 `onTouchEvent` 返回 false 让笔迹层接管；建议手写笔（`TOOL_TYPE_STYLUS`）默认只写字、手指操作贴纸，或显式进入"贴纸编辑模式"再拦截事件。
- 内存：assets 放 ≤512px PNG，列表页务必缩略图。

### 免费/可商用素材途径（方向，不下载）

- **Fluent Emoji（微软）** — 首推。MIT 许可，可商用可二改无需署名，Flat 风格 PNG/SVG 直接当贴纸。https://github.com/microsoft/fluentui-emoji
- **Noto Emoji（Google）** — 图形 Apache 2.0，可商用需放许可证声明；`png/128`、`png/512` 即透明底 PNG。https://github.com/googlefonts/noto-emoji
- **Twemoji** — 图形 CC-BY 4.0 需署名；原生 PNG 仅 72px 会糊，需用 SVG 放大。https://github.com/twitter/twemoji
- **OpenMoji** — CC BY-SA 4.0，可商用但需署名且**衍生图须同许可证共享**（传染性），法务敏感则避开。https://github.com/hfg-gmuend/openmoji
- **Flaticon** — 免费套餐**必须署名**（每处可见标注作者 + 链接），Premium（9.99 EUR/月起）免署名；免费署名在 App 内落地麻烦，作备选。https://www.flaticon.com/pricing

**建议组合**：首批内置 Fluent Emoji Flat + Noto Emoji 512px PNG，风格统一、授权干净（MIT / Apache 2.0），关于页统一放许可证文本。

### 参考来源

- https://github.com/wuapnjie/StickerView （架构参考，Apache 2.0）
- https://www.jianshu.com/p/fc37baa97fc3 （状态机讲解）
- https://github.com/bumptech/glide/releases
- https://mvnrepository.com/artifact/androidx.emoji2/emoji2
- https://github.com/hfg-gmuend/openmoji/issues/462 （SA 条款商用确认）

---

## 六、文本框工具 UX

### 主流笔记 App 标准交互

**GoodNotes**：选文本工具**轻点页面任意位置**即创建可移动文本框并直接进入输入态；再次点按进编辑；整体拖动移动；点框外结束编辑；三种句柄——白色角方块=缩放（文字跟着变大）、蓝色圆点=调整大小（只改框宽高、文字重排）、突出圆点=旋转。Pin Text Tool 可连续创建多个。Object Menu 支持剪切/复制/删除/置顶/锁定。

**Notability**：点页面直接打字；**Quick Text Boxes** 开关支持「对角拖拽画框」创建；键盘上方样式工具条可收藏 3 个常用字体样式。

**归纳行业标准**：编辑态 = 虚线/浅色边框 + 光标 + 软键盘；选中态 = 边框 + 句柄；点空白退出编辑；拖动框体移动；句柄区分「重排式 Resize」和「整体缩放 Scale」；双击/再次点按回编辑态。

### Android 实现方案对比

| | EditText 覆盖层 | Canvas 自绘（StaticLayout） |
|---|---|---|
| 光标/选区/IME/复制粘贴 | 免费获得 | 无，不可行 |
| 中文输入法候选框定位 | 正确 | 需自研，极难 |
| 缩放旋转 | 需同步变换 View，scale 会糊字 | 与笔迹同一 Matrix，不糊 |
| 大量文本框 | View 开销大 | 轻量 |

**推荐：混合架构（业界通用）**：

1. 数据模型：每文本框一实体（RectF 位置、文字、字号、颜色、旋转角），存页面坐标系。
2. **展示态**：所有非编辑中文本框用 `android.text.StaticLayout` 画在画布 View 里（API 23+ 用 `StaticLayout.Builder.obtain(text, 0, text.length(), textPaint, widthPx)`）。
3. **编辑态**：在画布上方 `FrameLayout` 容器 `addView` 一个无边框背景 `EditText`（虚线边框和句柄自绘在装饰层），`requestFocus()` + `InputMethodManager.showSoftInput()` 弹键盘；此时画布上对应文本临时不画，避免重影。
4. 结束编辑（点框外）：`getText()` 写回模型，`removeView`，画布重绘。
5. 拖动/调整：编辑容器里 `onTouchEvent` + `GestureDetector`，四角句柄热区 40–48dp 修改 RectF 并同步 EditText LayoutParams。

### 坑点

- **双渲染重影**：编辑态务必隐藏画布层那份文字。
- **坐标系换算**：画布平移/缩放矩阵下，EditText 布局参数须经 `Matrix.mapRect()` 换算。
- **缩放糊字**：不要对 EditText 用 `setScaleX/Y`，按缩放系数换算 `textSize`（sp）重设。
- 拖动结束后调 `InputMethodManager.restartInput(view)` 修正 IME 候选框错位。
- 结束编辑时检查框体出界并 clamp 到页内（GoodNotes 官方都提示大段文本会被裁剪）。
- 本方案全部走 AOSP/AndroidX 自带 API，**零 GMS 依赖**，ColorOS 无 GMS 可完整运行；注意测试分屏/小窗下软键盘弹出导致的画布 resize。

### 参考来源

- https://support.goodnotes.com/hc/en-us/articles/7353727615375-Type-text-with-the-Text-Tool
- https://support.gingerlabs.com/hc/en-us/articles/206059387-Text-and-Text-Boxes
- https://www.samsung.com/us/support/answer/ANS10001469/
- https://www.jianshu.com/p/9ac7d497e179 （StaticLayout 多行绘制）
- https://github.com/wuapnjie/StickerView （句柄/拖动架构参考）

---

## 结论速查表

| 功能 | 一句话选型结论 |
|---|---|
| 手写转文字 | 用 ML Kit Digital Ink `digital-ink-recognition:19.0.0` + `zh-Hani` 模型（bundled 版不依赖 GMS，但首启模型下载须在 OPPO Pad 真机验证）；拍照 OCR 叠加 bundled 版 `text-recognition-chinese:16.0.1`。 |
| 语音转文字 | Vosk 离线为主（`vosk-android:0.3.75` + 42MB 中文小模型，录音直接录 16kHz PCM），系统 SpeechRecognizer 探测通过才作增强；效果不够再升 sherpa-onnx + Paraformer。 |
| 和纸胶带 | 纯 framework 自绘：`BitmapShader(REPEAT)` 平铺花纹 tile + `saveLayer(RectF,Paint)` + `DST_IN` 撕边遮罩 + 半透明投影，对标 GoodNotes 笔画式胶带 + 点击揭示背诵交互。 |
| 形状工具 | 选择器放直线/箭头/曲线/矩形/椭圆/三角形/菱形/五角星 + Draw-and-Hold（停顿 300–500ms 规整、按住可调形）；识别算法自研抄 $1 Unistroke（one_dollar_unistroke_recognizer 翻 Java），赶工期可用 `ai.desertant:shapes:0.7.3`。 |
| 贴纸 | 贴纸作页面内容模型（Matrix + 逻辑坐标）自研，素材用 Fluent Emoji（MIT）+ Noto Emoji（Apache 2.0）内置 assets 分包，避开 OpenMoji 的 SA 传染性。 |
| 文本框 | 混合架构：展示态 `StaticLayout` 画进画布，编辑态 `addView` 一个 EditText 覆盖层（改 textSize 而非 scale 防糊字），全部 AOSP API 零 GMS。 |
