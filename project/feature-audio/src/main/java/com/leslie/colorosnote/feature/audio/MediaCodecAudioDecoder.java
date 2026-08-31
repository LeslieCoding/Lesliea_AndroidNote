package com.leslie.colorosnote.feature.audio;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Android {@link AudioDecoder}: MediaExtractor + MediaCodec, decodes AAC/M4A
 * (the recorder's output format) to PCM16 chunks at the source rate/channels.
 * Resampling to 16kHz mono happens downstream in {@link PcmResampler} so this
 * class stays a thin platform wrapper.
 */
public final class MediaCodecAudioDecoder implements AudioDecoder {

    private static final long TIMEOUT_US = 10_000L;

    @Override
    public long durationUs() {
        return -1; // per-file duration is reported via decode(); unknown up front
    }

    @Override
    public void decode(File audio, PcmSink sink) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(audio.getAbsolutePath());
            int track = selectAudioTrack(extractor);
            if (track < 0) {
                throw new IOException("文件中没有音频轨道");
            }
            extractor.selectTrack(track);
            MediaFormat format = extractor.getTrackFormat(track);
            int sampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
            String mime = format.getString(MediaFormat.KEY_MIME);
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            while (!outputDone) {
                if (!inputDone) {
                    int inIndex = codec.dequeueInputBuffer(TIMEOUT_US);
                    if (inIndex >= 0) {
                        ByteBuffer buf = codec.getInputBuffer(inIndex);
                        int read = buf == null ? -1 : extractor.readSampleData(buf, 0);
                        if (read < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIndex, 0, read,
                                    extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US);
                if (outIndex >= 0) {
                    if (info.size > 0) {
                        ByteBuffer out = codec.getOutputBuffer(outIndex);
                        if (out != null) {
                            byte[] chunk = new byte[info.size];
                            out.position(info.offset);
                            out.get(chunk, 0, info.size);
                            short[] pcm = PcmResampler.bytesToShorts(chunk, info.size);
                            sink.onPcm(pcm, pcm.length, sampleRate, channels,
                                    Math.max(0, info.presentationTimeUs));
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFormat = codec.getOutputFormat();
                    if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    }
                    if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                }
            }
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                    codec.release();
                } catch (RuntimeException ignored) {
                    // release best effort
                }
            }
            extractor.release();
        }
    }

    private static int selectAudioTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                return i;
            }
        }
        return -1;
    }
}
