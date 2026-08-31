package com.leslie.colorosnote.feature.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class LiveTranscriptionSessionTest {

    /** Scripted recognizer: each feed pops the next scripted outcome. */
    private static class FakeRecognizer implements RecognizerAdapter {
        /** Each entry: null = partial, otherwise finalized utterance text. */
        final Deque<String> script = new ArrayDeque<>();
        String finalTail = "";

        @Override
        public boolean acceptWaveForm(short[] data, int length) {
            String next = script.isEmpty() ? null : script.poll();
            return next != null;
        }

        @Override
        public String resultJson() {
            // Tests feed short[] in feed order; resultJson mirrors the popped text.
            return "{\"text\": \"" + lastFinalized + "\"}";
        }

        String lastFinalized = "";

        @Override
        public String partialResultJson() {
            return "{\"partial\": \"进行中\"}";
        }

        @Override
        public String finalResultJson() {
            return "{\"text\": \"" + finalTail + "\"}";
        }

        @Override
        public void close() {
        }
    }

    @Test
    public void partialsForwardedWithoutCommitting() {
        FakeRecognizer rec = new FakeRecognizer();
        List<String> partials = new ArrayList<>();
        LiveTranscriptionSession session = new LiveTranscriptionSession(rec,
                new LiveTranscriptionSession.Listener() {
                    @Override
                    public void onPartial(String text) {
                        partials.add(text);
                    }

                    @Override
                    public void onUtteranceFinalized(String text) {
                    }
                });
        session.feed(new short[10], 10);
        session.feed(new short[10], 10);
        assertEquals(2, partials.size());
        assertEquals("进行中", partials.get(0));
        assertEquals("", session.committedText());
    }

    @Test
    public void finalizedUtterancesAccumulate() {
        FakeRecognizer rec = new FakeRecognizer() {
            @Override
            public boolean acceptWaveForm(short[] data, int length) {
                if (!script.isEmpty()) {
                    lastFinalized = script.poll();
                    return true;
                }
                return false;
            }
        };
        rec.script.add("第一句话");
        rec.script.add("第二句话");
        List<String> finals = new ArrayList<>();
        LiveTranscriptionSession session = new LiveTranscriptionSession(rec,
                new LiveTranscriptionSession.Listener() {
                    @Override
                    public void onPartial(String text) {
                    }

                    @Override
                    public void onUtteranceFinalized(String text) {
                        finals.add(text);
                    }
                });
        session.feed(new short[10], 10);
        session.feed(new short[10], 10);
        session.feed(new short[10], 10); // partial, ignored
        assertEquals(2, finals.size());
        assertEquals("第一句话，第二句话", session.committedText());
    }

    @Test
    public void finishFlushesTailOnce() {
        FakeRecognizer rec = new FakeRecognizer();
        rec.finalTail = "结尾";
        LiveTranscriptionSession session = new LiveTranscriptionSession(rec, null);
        assertEquals("结尾", session.finish());
        assertEquals("结尾", session.finish()); // idempotent
        assertTrue(session.isFinished());
        session.feed(new short[10], 10); // ignored after finish
        assertEquals("结尾", session.committedText());
    }

    @Test
    public void emptyFinalsAreSkipped() {
        FakeRecognizer rec = new FakeRecognizer() {
            @Override
            public boolean acceptWaveForm(short[] data, int length) {
                lastFinalized = ""; // engine finalized silence
                return true;
            }
        };
        LiveTranscriptionSession session = new LiveTranscriptionSession(rec, null);
        session.feed(new short[10], 10);
        assertEquals("", session.committedText());
        assertEquals("", session.finish());
    }
}
