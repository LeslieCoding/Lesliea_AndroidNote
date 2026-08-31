# 音频与转写（AUDIO_TRANSCRIPTION）

## 录音（spec 20）
- `AudioRecorderController`（feature-audio）：MediaRecorder AAC/M4A 44.1kHz 单声道 96kbps，课堂人声优先。
- 状态机 IDLE/RECORDING/PAUSED/STOPPED/ERROR；暂停/恢复；录音中 500ms 间隔时长回调。
- **分段防损坏**：`SegmentRollingPolicy` 每 5 分钟滚动切新文件（`name_part000.m4a`…），崩溃最多丢当前段。
- 音频焦点：被电话/其他 App 抢占时自动暂停并回调；存储 <50MB 拒绝/中止。
- 权限：RECORD_AUDIO 按需申请，未授权走明确错误。

## 播放
- `AudioPlayerController`：Media3 ExoPlayer；分段文件 ConcatenatingMediaSource 无缝连播；seek；0.5–2.0x 倍速；250ms 位置回调。

## 墨迹-音频时间轴（spec 20.1）
- `InkAudioTimeline`：每个 stroke 记录 start/endAudioTimeMs（编辑器录音时写入锚点）。
- 点击手写 → `audioTimeForStroke` 跳转播放；播放中 `strokesNear(t, window)` 供 UI 强调当前时间附近的笔迹；支持用户偏移校正；快照可序列化随文档保存。

## 转写（spec 21）
- 两层抽象 `TranscriptionEngine`：本地引擎 + 云端 provider 接口（默认关闭、API Key 走 Keystore、UI 明示上传）。
- **真引擎已落地：Vosk 离线识别**（`com.alphacephei:vosk-android:0.3.75`，传递依赖 JNA 5.18.1）。模型 `vosk-model-small-cn-0.22`（42MB，官方 URL 见下）不打包进 APK，首次使用经奶白进度卡片下载解压到 `filesDir/models/vosk-model-small-cn-0.22/`；**就绪判断是结构性的**（`am/final.mdl` + `graph/HCLG.fst` + `graph/words.txt` 存在且非空），所以可以直接 `adb push` 预置模型目录兜底，无需任何标记文件。
  - 模型 URL：`https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip`（Content-Length 43,898,754，2026-08 实测可下）。
  - 文件转写链路：`MediaCodecAudioDecoder`（MediaExtractor+MediaCodec 解 AAC/M4A）→ `PcmResampler`（去混声道+线性插值重采样到 16kHz mono PCM16）→ `RecognizerAdapter.acceptWaveForm` → `VoskJson` 抽取 finalResult 文本汇总成 `TranscriptSegment`。入口：`VoskSpeechTranscriptionGateway`（feature-editor，注入点 `EditorServices.speechTranscription`），录音停止对话框「转文字」按钮。
  - 实时识别链路：`MicCapture`（AudioRecord 16kHz mono PCM16，200ms 块，支持暂停/恢复）→ `LiveTranscriptionSession`（partialResult/finalResult 累计，纯 Java 可单测）→ `VoskLiveTranscriber`（后台线程编排，回调切主线程）。入口：「+ → 语音转文字」奶白卡片（`SpeechToTextController`），停止后可「插入为文本框」（undo 路径 `InkCommands.AddObject`）/「复制」/「丢弃」。
  - 引擎隔离：`RecognizerFactory`/`RecognizerAdapter`/`AudioDecoder` 是接口，Vosk JNI 只在 `VoskAndroidBridge`；管道逻辑全部 JVM 可单测。
  - R8：app/proguard-rules.pro 已加 `-keep org.vosk.**` / `com.sun.jna.**`。
- `WhisperTranscriptionEngine` 骨架保留未接线（whisper.cpp JNI 桥未内置时诚实返回错误码）。
- `TranscriptStore`：段落（text/start/end/confidence/speaker 预留）管理、按时间排序、点击跳转音频；导出 TXT/Markdown；可搜索（接入全局索引）。
