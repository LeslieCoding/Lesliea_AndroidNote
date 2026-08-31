package com.leslie.colorosnote.feature.audio;

/**
 * PCM conversion helpers for the Vosk pipeline (spec 21.2): recognizers only
 * accept 16kHz / mono / 16-bit PCM, while recordings are AAC 44.1kHz mono and
 * the mic can be opened at other rates. Pure Java, JVM-testable.
 */
public final class PcmResampler {

    /** Sample rate required by Vosk recognizers. */
    public static final int TARGET_RATE = 16000;

    private PcmResampler() {
    }

    /** Downmixes interleaved multi-channel PCM to mono (sample average). */
    public static short[] toMono(short[] samples, int length, int channels) {
        if (channels <= 1) {
            short[] out = new short[length];
            System.arraycopy(samples, 0, out, 0, length);
            return out;
        }
        int frames = length / channels;
        short[] out = new short[frames];
        for (int i = 0; i < frames; i++) {
            int sum = 0;
            for (int c = 0; c < channels; c++) {
                sum += samples[i * channels + c];
            }
            out[i] = (short) (sum / channels);
        }
        return out;
    }

    /**
     * Linear-interpolation resample of mono PCM to {@link #TARGET_RATE}.
     * Returns the input slice unchanged (copied) when already at target rate.
     */
    public static short[] resample(short[] mono, int sourceRate) {
        return resample(mono, mono.length, sourceRate);
    }

    public static short[] resample(short[] mono, int length, int sourceRate) {
        if (sourceRate == TARGET_RATE) {
            short[] out = new short[length];
            System.arraycopy(mono, 0, out, 0, length);
            return out;
        }
        if (sourceRate <= 0 || length == 0) {
            return new short[0];
        }
        int outLength = (int) Math.max(1, Math.round((long) length * (double) TARGET_RATE / sourceRate));
        short[] out = new short[outLength];
        double step = (double) sourceRate / TARGET_RATE;
        for (int i = 0; i < outLength; i++) {
            double pos = i * step;
            int idx = (int) pos;
            double frac = pos - idx;
            int next = Math.min(idx + 1, length - 1);
            idx = Math.min(idx, length - 1);
            out[i] = (short) (mono[idx] + frac * (mono[next] - mono[idx]));
        }
        return out;
    }

    /** Little-endian PCM16 bytes (Android MediaCodec output) to shorts. */
    public static short[] bytesToShorts(byte[] bytes, int length) {
        int count = length / 2;
        short[] out = new short[count];
        for (int i = 0; i < count; i++) {
            out[i] = (short) ((bytes[2 * i] & 0xFF) | (bytes[2 * i + 1] << 8));
        }
        return out;
    }

    /** Shorts to little-endian PCM16 bytes (feeds Vosk byte[] overloads). */
    public static byte[] shortsToBytes(short[] samples, int length) {
        byte[] out = new byte[length * 2];
        for (int i = 0; i < length; i++) {
            out[2 * i] = (byte) (samples[i] & 0xFF);
            out[2 * i + 1] = (byte) ((samples[i] >> 8) & 0xFF);
        }
        return out;
    }
}
