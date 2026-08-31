package com.leslie.colorosnote.feature.audio;

import java.io.File;
import java.io.IOException;

/**
 * Decodes an audio file (AAC/M4A recordings) into raw PCM16 chunks.
 * Production implementation: {@link MediaCodecAudioDecoder}
 * (MediaExtractor + MediaCodec); tests feed synthetic PCM.
 */
public interface AudioDecoder {

    interface PcmSink {
        /**
         * One decoded chunk.
         *
         * @param samples    interleaved PCM16
         * @param length     valid sample count in {@code samples}
         * @param sampleRate source rate (e.g. 44100)
         * @param channels   source channel count
         * @param ptsUs      presentation time of the chunk start
         */
        void onPcm(short[] samples, int length, int sampleRate, int channels, long ptsUs);
    }

    /** Total duration in microseconds, or &lt; 0 when unknown. */
    long durationUs();

    /**
     * Streams the whole file to the sink. Blocking; call off the main thread.
     *
     * @throws IOException when the file cannot be decoded
     */
    void decode(File audio, PcmSink sink) throws IOException;
}
