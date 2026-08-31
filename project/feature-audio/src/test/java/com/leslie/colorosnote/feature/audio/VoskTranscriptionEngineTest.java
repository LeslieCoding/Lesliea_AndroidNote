package com.leslie.colorosnote.feature.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class VoskTranscriptionEngineTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File filesDir;
    private VoskModelManager modelManager;

    @Before
    public void setUp() {
        filesDir = new File(tmp.getRoot(), "files");
        modelManager = new VoskModelManager(filesDir,
                (url, expected, dest, cb) -> {
                    throw new IOException("no network in tests");
                });
    }

    private void installModel() throws IOException {
        File dir = modelManager.modelDir();
        for (String rel : new String[]{"am/final.mdl", "graph/HCLG.fst", "graph/words.txt"}) {
            File f = new File(dir, rel);
            assertTrue(f.getParentFile().mkdirs() || f.getParentFile().isDirectory());
            Files.write(f.toPath(), new byte[]{1});
        }
    }

    /** Decoder that replays scripted chunks. */
    private static final class FakeDecoder implements AudioDecoder {
        List<short[]> chunks = new ArrayList<>();
        int sampleRate = PcmResampler.TARGET_RATE;
        int channels = 1;
        long duration = 1_000_000;

        @Override
        public long durationUs() {
            return duration;
        }

        @Override
        public void decode(File audio, PcmSink sink) {
            long pts = 0;
            for (short[] chunk : chunks) {
                sink.onPcm(chunk, chunk.length, sampleRate, channels, pts);
                pts += chunk.length * 1_000_000L / sampleRate;
            }
        }
    }

    /** Recognizer that finalizes on every feed, echoing a fixed text. */
    private static final class EchoRecognizer implements RecognizerAdapter {
        final String text;
        boolean closed;

        EchoRecognizer(String text) {
            this.text = text;
        }

        @Override
        public boolean acceptWaveForm(short[] data, int length) {
            return true;
        }

        @Override
        public String resultJson() {
            return "{\"text\": \"" + text + "\"}";
        }

        @Override
        public String partialResultJson() {
            return "{}";
        }

        @Override
        public String finalResultJson() {
            return "{\"text\": \"" + text + "尾\"}";
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class FakeFactory implements RecognizerFactory {
        final RecognizerAdapter recognizer;
        boolean loaded;

        FakeFactory(RecognizerAdapter recognizer) {
            this.recognizer = recognizer;
        }

        @Override
        public void load(File modelDir) {
            loaded = true;
        }

        @Override
        public boolean isLoaded() {
            return loaded;
        }

        @Override
        public RecognizerAdapter create(float sampleRate) {
            return recognizer;
        }
    }

    @Test
    public void modelMissingReturnsChineseError() {
        VoskTranscriptionEngine engine = new VoskTranscriptionEngine(
                modelManager, new FakeFactory(new EchoRecognizer("x")), new FakeDecoder());
        assertFalse(engine.isAvailable());
        TranscriptionEngine.TranscriptionResult r =
                engine.transcribe(new File(filesDir, "a.m4a"), null, null);
        assertEquals(TranscriptionEngine.ErrorCode.MODEL_NOT_INSTALLED, r.error);
        assertTrue(r.errorMessage.contains("语音模型未下载"));
    }

    @Test
    public void audioMissingReturnsIoError() throws IOException {
        installModel();
        VoskTranscriptionEngine engine = new VoskTranscriptionEngine(
                modelManager, new FakeFactory(new EchoRecognizer("x")), new FakeDecoder());
        TranscriptionEngine.TranscriptionResult r =
                engine.transcribe(new File(filesDir, "nope.m4a"), null, null);
        assertEquals(TranscriptionEngine.ErrorCode.IO_ERROR, r.error);
        assertEquals("录音文件不存在", r.errorMessage);
    }

    @Test
    public void aggregatesFinalResultsIntoSegments() throws IOException {
        installModel();
        File audio = tmp.newFile("rec.m4a");
        FakeDecoder decoder = new FakeDecoder();
        decoder.chunks.add(new short[1600]);
        decoder.chunks.add(new short[1600]);
        EchoRecognizer recognizer = new EchoRecognizer("你好");
        VoskTranscriptionEngine engine = new VoskTranscriptionEngine(
                modelManager, new FakeFactory(recognizer), decoder);

        TranscriptionEngine.TranscriptionResult r = engine.transcribe(audio, null, null);
        assertEquals(TranscriptionEngine.ErrorCode.NONE, r.error);
        // Two finalized feeds + final tail.
        assertEquals(3, r.segments.size());
        assertEquals("你好", r.segments.get(0).text);
        assertEquals("你好尾", r.segments.get(2).text);
        assertTrue(recognizer.closed);
        assertEquals("你好。你好。你好尾", VoskTranscriptionEngine.joinText(r.segments));
    }

    @Test
    public void reportsProgressFromDecoderPts() throws IOException {
        installModel();
        File audio = tmp.newFile("rec.m4a");
        FakeDecoder decoder = new FakeDecoder();
        decoder.chunks.add(new short[16000]);
        decoder.chunks.add(new short[16000]);
        decoder.duration = 2_000_000; // 2s total
        List<Integer> percents = new ArrayList<>();
        VoskTranscriptionEngine engine = new VoskTranscriptionEngine(
                modelManager, new FakeFactory(new EchoRecognizer("x")), decoder);
        engine.transcribe(audio, (percent, stage) -> percents.add(percent), null);
        assertEquals(Integer.valueOf(0), percents.get(0));
        assertEquals(Integer.valueOf(50), percents.get(2));
        assertEquals(Integer.valueOf(100), percents.get(percents.size() - 1));
    }

    @Test
    public void cancelAfterDecodeSkipsResults() throws IOException {
        installModel();
        File audio = tmp.newFile("rec.m4a");
        FakeDecoder decoder = new FakeDecoder();
        decoder.chunks.add(new short[1600]);
        VoskTranscriptionEngine engine = new VoskTranscriptionEngine(
                modelManager, new FakeFactory(new EchoRecognizer("x")), decoder);
        TranscriptionEngine.TranscriptionResult r =
                engine.transcribe(audio, null, () -> true);
        assertEquals(TranscriptionEngine.ErrorCode.CANCELLED, r.error);
    }

    @Test
    public void decodeFailureReturnsIoError() throws IOException {
        installModel();
        File audio = tmp.newFile("rec.m4a");
        AudioDecoder failing = new AudioDecoder() {
            @Override
            public long durationUs() {
                return -1;
            }

            @Override
            public void decode(File a, PcmSink sink) throws IOException {
                throw new IOException("bad file");
            }
        };
        VoskTranscriptionEngine engine = new VoskTranscriptionEngine(
                modelManager, new FakeFactory(new EchoRecognizer("x")), failing);
        TranscriptionEngine.TranscriptionResult r = engine.transcribe(audio, null, null);
        assertEquals(TranscriptionEngine.ErrorCode.IO_ERROR, r.error);
        assertTrue(r.errorMessage.startsWith("音频解码失败"));
    }
}
