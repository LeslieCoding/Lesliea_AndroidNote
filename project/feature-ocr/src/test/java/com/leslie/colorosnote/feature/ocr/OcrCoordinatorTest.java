package com.leslie.colorosnote.feature.ocr;

import android.graphics.Bitmap;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Decision-flow tests for {@link OcrCoordinator} using a fake engine
 * (spec 14.2 branches: native text first, confidence/sanity check, no crash
 * on engine failure). The fake ignores the Bitmap argument so the whole flow
 * stays on the JVM.
 */
public class OcrCoordinatorTest {

    /** Fake engine: returns a canned result or throws a canned exception. */
    private static final class FakeOcrEngine implements OcrEngine {
        private final OcrResult result;
        private final OcrException failure;

        FakeOcrEngine(OcrResult result, OcrException failure) {
            this.result = result;
            this.failure = failure;
        }

        @Override
        public OcrResult recognize(Bitmap bitmap, OcrOptions options) throws OcrException {
            if (failure != null) {
                throw failure;
            }
            return result;
        }

        @Override
        public boolean isAvailable() {
            return failure == null;
        }

        @Override
        public void close() {
        }
    }

    private static OcrResult resultWithLines(int imageWidth, int imageHeight,
                                             Float confidence, String... lineTexts) {
        List<OcrResult.Line> lines = new java.util.ArrayList<>();
        float top = 10;
        for (String t : lineTexts) {
            List<OcrResult.Element> elements = Collections.singletonList(
                    new OcrResult.Element(t, new OcrBox(10, top, 50, top + 20), confidence));
            lines.add(new OcrResult.Line(t, new OcrBox(10, top, 50, top + 20), confidence,
                    elements));
            top += 30;
        }
        return new OcrResult(Collections.singletonList(
                new OcrResult.Block("block", new OcrBox(10, 10, 50, top), lines)),
                imageWidth, imageHeight);
    }

    // ---- plan(): native-text-first decision branches ----

    @Test
    public void plan_nativeTextSufficientSkipsOcr() {
        OcrCoordinator c = new OcrCoordinator(new FakeOcrEngine(null, null));
        assertEquals(OcrCoordinator.Plan.USE_NATIVE_TEXT,
                c.plan(OcrCoordinator.MIN_NATIVE_TEXT_CHARS));
        assertEquals(OcrCoordinator.Plan.USE_NATIVE_TEXT, c.plan(5000));
    }

    @Test
    public void plan_nativeTextSparseRunsOcr() {
        OcrCoordinator c = new OcrCoordinator(new FakeOcrEngine(null, null));
        assertEquals(OcrCoordinator.Plan.RUN_OCR, c.plan(0));
        assertEquals(OcrCoordinator.Plan.RUN_OCR, c.plan(OcrCoordinator.MIN_NATIVE_TEXT_CHARS - 1));
    }

    // ---- collect(): coordinate scaling + sanity + low confidence ----

    @Test
    public void collect_scalesBoxesBackToOriginalImage() {
        OcrCoordinator c = new OcrCoordinator(new FakeOcrEngine(null, null));
        FakeOcrEngine fake = new FakeOcrEngine(resultWithLines(1024, 512, 0.9f, "你好"), null);
        try {
            OcrCoordinator.OcrOutcome outcome =
                    c.collect(fake.recognize(null, OcrOptions.defaults()),
                            null, "doc1", "page1", 2048, 1024);
            assertEquals(OcrCoordinator.Status.OK, outcome.status);
            assertEquals(1, outcome.entries.size());
            OcrBox box = outcome.entries.get(0).box;
            assertEquals(20f, box.left, 1e-4f);   // 10 * 2
            assertEquals(60f, box.bottom, 1e-4f); // 30 * 2
            assertFalse(outcome.lowConfidence);
            assertEquals("doc1", outcome.entries.get(0).documentId);
            assertEquals("page1", outcome.entries.get(0).pageId);
        } catch (OcrException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void collect_lowMeanConfidenceFlagsOutcomeAndEntries() {
        OcrCoordinator c = new OcrCoordinator(new FakeOcrEngine(null, null));
        OcrCoordinator.OcrOutcome outcome = c.collect(
                resultWithLines(100, 100, 0.2f, "可能认错"), null, "d", "p", 100, 100);
        assertEquals(OcrCoordinator.Status.OK, outcome.status);
        assertTrue(outcome.lowConfidence);
        assertTrue(outcome.entries.get(0).lowConfidence);
    }

    @Test
    public void collect_noConfidenceDataIsNotFlaggedLow() {
        OcrCoordinator c = new OcrCoordinator(new FakeOcrEngine(null, null));
        OcrCoordinator.OcrOutcome outcome = c.collect(
                resultWithLines(100, 100, null, "文字"), null, "d", "p", 100, 100);
        assertEquals(OcrCoordinator.Status.OK, outcome.status);
        assertFalse(outcome.lowConfidence);
    }

    @Test
    public void collect_dropsGarbageLinesWithoutLettersOrDigits() {
        OcrCoordinator c = new OcrCoordinator(new FakeOcrEngine(null, null));
        OcrCoordinator.OcrOutcome outcome = c.collect(
                resultWithLines(100, 100, 0.9f, "@@@###", "正常文本", "   "),
                null, "d", "p", 100, 100);
        assertEquals(OcrCoordinator.Status.OK, outcome.status);
        assertEquals(1, outcome.entries.size());
        assertEquals("正常文本", outcome.entries.get(0).text);
    }

    @Test
    public void collect_allGarbageIsEmptyResult() {
        OcrCoordinator c = new OcrCoordinator(new FakeOcrEngine(null, null));
        OcrCoordinator.OcrOutcome outcome = c.collect(
                resultWithLines(100, 100, 0.9f, "|||"), null, "d", "p", 100, 100);
        assertEquals(OcrCoordinator.Status.EMPTY_RESULT, outcome.status);
        assertTrue(outcome.entries.isEmpty());
    }

    // ---- failure branch: no crash, no fake data ----

    @Test
    public void engineFailure_surfacesErrorWithoutEntries() {
        OcrCoordinator c = new OcrCoordinator(new FakeOcrEngine(null, null));
        OcrException err = new OcrException(
                OcrException.ErrorCode.ENGINE_UNAVAILABLE, "no GMS on ColorOS");
        OcrCoordinator.OcrOutcome outcome = c.engineFailure(err);
        assertEquals(OcrCoordinator.Status.ENGINE_ERROR, outcome.status);
        assertTrue(outcome.entries.isEmpty());
        assertSame(err, outcome.error);
        assertNull(outcome.result);
    }

    @Test
    public void skippedNative_hasNoEntries() {
        OcrCoordinator c = new OcrCoordinator(new FakeOcrEngine(null, null));
        OcrCoordinator.OcrOutcome outcome = c.skippedNative();
        assertEquals(OcrCoordinator.Status.SKIPPED_NATIVE_TEXT, outcome.status);
        assertTrue(outcome.entries.isEmpty());
    }

    @Test
    public void fakeEngine_failurePathCarriesErrorCode() {
        FakeOcrEngine fake = new FakeOcrEngine(null, new OcrException(
                OcrException.ErrorCode.MODEL_NOT_READY, "model missing"));
        try {
            fake.recognize(null, OcrOptions.defaults());
        } catch (OcrException e) {
            assertEquals(OcrException.ErrorCode.MODEL_NOT_READY, e.code());
            OcrCoordinator c = new OcrCoordinator(fake);
            assertEquals(OcrCoordinator.Status.ENGINE_ERROR, c.engineFailure(e).status);
            return;
        }
        throw new AssertionError("fake engine should have thrown");
    }

    @Test
    public void options_defaultsLimitLongEdge() {
        assertEquals(2048, OcrOptions.defaults().maxLongEdge);
        assertEquals(1024, new OcrOptions("zh", 1024, true, false).maxLongEdge);
        // Non-positive limit falls back to the default.
        assertEquals(2048, new OcrOptions(null, 0, true, true).maxLongEdge);
    }

    @Test
    public void collect_keepsMultiLineOrder() {
        OcrCoordinator c = new OcrCoordinator(new FakeOcrEngine(null, null));
        OcrCoordinator.OcrOutcome outcome = c.collect(
                resultWithLines(100, 200, 0.9f, "第一行", "第二行"),
                null, "d", "p", 100, 200);
        assertEquals(Arrays.asList("第一行", "第二行"),
                Arrays.asList(outcome.entries.get(0).text, outcome.entries.get(1).text));
    }
}
