package com.leslie.colorosnote.feature.audio;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * File-transcription engine backed by Vosk (spec 21.2): decodes the recording
 * to PCM via an {@link AudioDecoder}, normalizes to 16kHz mono
 * ({@link PcmResampler}) and streams it through a {@link RecognizerAdapter},
 * aggregating finalized utterances into {@link TranscriptSegment}s.
 *
 * <p>The pipeline logic is pure Java — both the decoder and the recognizer
 * factory are injected, so this class is fully JVM-testable. Android glue
 * lives in {@link MediaCodecAudioDecoder} and {@link VoskAndroidBridge}.
 */
public final class VoskTranscriptionEngine implements TranscriptionEngine {

    private final VoskModelManager modelManager;
    private final RecognizerFactory recognizerFactory;
    private final AudioDecoder decoder;

    public VoskTranscriptionEngine(VoskModelManager modelManager,
                                   RecognizerFactory recognizerFactory,
                                   AudioDecoder decoder) {
        this.modelManager = modelManager;
        this.recognizerFactory = recognizerFactory;
        this.decoder = decoder;
    }

    @Override
    public boolean isAvailable() {
        return modelManager.isModelReady();
    }

    @Override
    public TranscriptionResult transcribe(File audio, ProgressCallback progress,
                                          CancelToken cancel) {
        if (!modelManager.isModelReady()) {
            return TranscriptionResult.error(ErrorCode.MODEL_NOT_INSTALLED,
                    "语音模型未下载，请先在语音转文字中下载模型");
        }
        if (audio == null || !audio.isFile()) {
            return TranscriptionResult.error(ErrorCode.IO_ERROR, "录音文件不存在");
        }
        try {
            if (!recognizerFactory.isLoaded()) {
                recognizerFactory.load(modelManager.modelDir());
            }
        } catch (IOException | RuntimeException e) {
            return TranscriptionResult.error(ErrorCode.ENGINE_UNAVAILABLE,
                    "识别引擎加载失败：" + e.getMessage());
        }

        RecognizerAdapter recognizer = recognizerFactory.create(PcmResampler.TARGET_RATE);
        try {
            List<TranscriptSegment> segments = new ArrayList<>();
            long durationUs = decoder.durationUs();
            final long[] fedSamples = {0};
            final int[] index = {0};
            if (progress != null) progress.onProgress(0, "识别中");

            decoder.decode(audio, (samples, length, sampleRate, channels, ptsUs) -> {
                short[] mono = PcmResampler.toMono(samples, length, channels);
                short[] pcm16k = PcmResampler.resample(mono, mono.length, sampleRate);
                if (pcm16k.length == 0) {
                    return;
                }
                fedSamples[0] += pcm16k.length;
                if (recognizer.acceptWaveForm(pcm16k, pcm16k.length)) {
                    appendSegment(segments, recognizer.resultJson(), index, fedSamples[0]);
                }
                if (progress != null && durationUs > 0) {
                    progress.onProgress(
                            (int) Math.min(99, ptsUs * 100 / durationUs), "识别中");
                }
            });

            if (cancel != null && cancel.isCancelled()) {
                return TranscriptionResult.error(ErrorCode.CANCELLED, "已取消");
            }
            appendSegment(segments, recognizer.finalResultJson(), index, fedSamples[0]);
            if (progress != null) progress.onProgress(100, "完成");
            return TranscriptionResult.ok(segments);
        } catch (IOException e) {
            return TranscriptionResult.error(ErrorCode.IO_ERROR,
                    "音频解码失败：" + e.getMessage());
        } catch (RuntimeException e) {
            return TranscriptionResult.error(ErrorCode.ENGINE_UNAVAILABLE,
                    "识别失败：" + e.getMessage());
        } finally {
            recognizer.close();
        }
    }

    private static void appendSegment(List<TranscriptSegment> segments, String resultJson,
                                      int[] index, long fedSamples16k) {
        String text = VoskJson.extractText(resultJson);
        if (text.isEmpty()) {
            return;
        }
        long endMs = fedSamples16k * 1000L / PcmResampler.TARGET_RATE;
        segments.add(new TranscriptSegment("seg-" + (index[0]++), text,
                Math.max(0, endMs - 1), endMs));
    }

    /** Convenience: joins all segments into one plain-text transcript. */
    public static String joinText(List<TranscriptSegment> segments) {
        StringBuilder sb = new StringBuilder();
        if (segments != null) {
            for (TranscriptSegment s : segments) {
                if (s.text == null || s.text.isEmpty()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('。');
                }
                sb.append(s.text);
            }
        }
        return sb.toString();
    }

    @Override
    public String displayName() {
        return "离线识别（Vosk 中文小模型）";
    }
}
