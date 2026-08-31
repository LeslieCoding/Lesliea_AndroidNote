package com.leslie.colorosnote.feature.audio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PcmResamplerTest {

    @Test
    public void resampleIdentityAtTargetRate() {
        short[] in = {1, -2, 300, -400};
        short[] out = PcmResampler.resample(in, PcmResampler.TARGET_RATE);
        assertArrayEquals(in, out);
    }

    @Test
    public void resample48kTo16kShrinksThreefold() {
        short[] in = new short[4800]; // 100ms at 48kHz
        for (int i = 0; i < in.length; i++) {
            in[i] = (short) i;
        }
        short[] out = PcmResampler.resample(in, 48000);
        assertEquals(1600, out.length);
        // Start preserved; tail interpolates at pos (outLength-1)*3 = 4797.
        assertEquals(0, out[0]);
        assertEquals(4797, out[out.length - 1]);
    }

    @Test
    public void resample44100To16k() {
        short[] in = new short[44100]; // 1s at 44.1kHz
        short[] out = PcmResampler.resample(in, 44100);
        assertEquals(16000, out.length);
    }

    @Test
    public void toMonoAveragesChannels() {
        short[] stereo = {100, 300, -100, -300};
        short[] mono = PcmResampler.toMono(stereo, stereo.length, 2);
        assertArrayEquals(new short[]{200, -200}, mono);
    }

    @Test
    public void toMonoPassthroughSingleChannel() {
        short[] in = {5, 6, 7};
        assertArrayEquals(in, PcmResampler.toMono(in, in.length, 1));
    }

    @Test
    public void bytesShortsRoundTrip() {
        short[] in = {0, 1, -1, Short.MAX_VALUE, Short.MIN_VALUE, 12345};
        byte[] bytes = PcmResampler.shortsToBytes(in, in.length);
        short[] back = PcmResampler.bytesToShorts(bytes, bytes.length);
        assertArrayEquals(in, back);
    }

    @Test
    public void resampleHandlesEmptyInput() {
        assertEquals(0, PcmResampler.resample(new short[0], 44100).length);
        assertEquals(0, PcmResampler.resample(new short[]{1}, 0).length);
    }
}
