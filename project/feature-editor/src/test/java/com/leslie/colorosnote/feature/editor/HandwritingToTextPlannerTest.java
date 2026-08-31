package com.leslie.colorosnote.feature.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.leslie.colorosnote.core.model.Page;
import com.leslie.colorosnote.core.model.Stroke;
import com.leslie.colorosnote.core.model.StrokePoint;
import com.leslie.colorosnote.core.model.TextBoxObject;
import com.leslie.colorosnote.feature.handwriting.Candidate;
import com.leslie.colorosnote.feature.handwriting.HandwritingException;
import com.leslie.colorosnote.feature.handwriting.HandwritingRecognizer;
import com.leslie.colorosnote.feature.handwriting.RecognizeOptions;
import com.leslie.colorosnote.feature.handwriting.StrokeData;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure-JVM tests for 手写转文字 orchestration (spec 15). */
public class HandwritingToTextPlannerTest {

    /** Fake engine: answers by the vertical position of the ink (two canned lines). */
    private static final class FakeRecognizer implements HandwritingRecognizer {
        final List<String> preContexts = new ArrayList<>();
        final List<Float> writingAreaHeights = new ArrayList<>();
        boolean throwOnRecognize;
        boolean blankSecondLine;

        @Override
        public List<Candidate> recognize(List<StrokeData> strokes, RecognizeOptions options)
                throws HandwritingException {
            if (throwOnRecognize) {
                throw new HandwritingException(
                        HandwritingException.ErrorCode.RECOGNITION_FAILED, "boom");
            }
            preContexts.add(options.preContext);
            writingAreaHeights.add(options.writingAreaHeight);
            float centerY = 0f;
            for (StrokeData s : strokes) {
                centerY += s.bounds().centerY();
            }
            centerY /= strokes.size();
            if (centerY < 100f) {
                return Collections.singletonList(new Candidate("你好笔记", 0.9f));
            }
            if (blankSecondLine) {
                return Collections.singletonList(new Candidate("   ", 0.4f));
            }
            return Collections.singletonList(new Candidate("世界很大", 0.8f));
        }

        @Override
        public boolean isModelDownloaded() {
            return true;
        }

        @Override
        public void downloadModel() {
        }

        @Override
        public void deleteModel() {
        }

        @Override
        public void close() {
        }
    }

    private static Stroke stroke(float x0, float y0, float x1, float y1, long t0) {
        Stroke s = new Stroke();
        s.points.add(new StrokePoint(x0, y0, t0, 0.5f));
        s.points.add(new StrokePoint(x1, y1, t0 + 100, 0.5f));
        return s;
    }

    // ------------------------------------------------------------------
    // adaptStrokes
    // ------------------------------------------------------------------

    @Test
    public void adaptStrokesSkipsEmptyAndMapsPoints() {
        List<Stroke> strokes = new ArrayList<>();
        strokes.add(stroke(10f, 20f, 30f, 40f, 1000));
        strokes.add(new Stroke()); // no points
        strokes.add(null);

        List<StrokeData> data = HandwritingToTextPlanner.adaptStrokes(strokes);
        assertEquals(1, data.size());
        assertEquals(2, data.get(0).pointCount());
        assertEquals(10f, data.get(0).xs[0], 0.001f);
        assertEquals(40f, data.get(0).ys[1], 0.001f);
        assertEquals(1000, data.get(0).ts[0]);
    }

    @Test
    public void adaptStrokesNullOrEmptyYieldsEmpty() {
        assertTrue(HandwritingToTextPlanner.adaptStrokes(null).isEmpty());
        assertTrue(HandwritingToTextPlanner.adaptStrokes(new ArrayList<>()).isEmpty());
    }

    // ------------------------------------------------------------------
    // recognizePage
    // ------------------------------------------------------------------

    @Test
    public void recognizePageJoinsLinesWithNewline() throws HandwritingException {
        // Two lines far apart vertically, written within the time-gap window.
        List<Stroke> strokes = new ArrayList<>();
        strokes.add(stroke(0f, 0f, 100f, 10f, 0));
        strokes.add(stroke(0f, 500f, 100f, 510f, 1000));
        FakeRecognizer fake = new FakeRecognizer();

        String text = HandwritingToTextPlanner.recognizePage(
                fake, HandwritingToTextPlanner.adaptStrokes(strokes));

        assertEquals("你好笔记\n世界很大", text);
        assertEquals(2, fake.preContexts.size());
    }

    @Test
    public void recognizePageChainsPreContextFromPreviousLines() throws HandwritingException {
        List<Stroke> strokes = new ArrayList<>();
        strokes.add(stroke(0f, 0f, 100f, 10f, 0));
        strokes.add(stroke(0f, 500f, 100f, 510f, 1000));
        FakeRecognizer fake = new FakeRecognizer();

        HandwritingToTextPlanner.recognizePage(
                fake, HandwritingToTextPlanner.adaptStrokes(strokes));

        assertNull(fake.preContexts.get(0));
        // Second line sees the tail of the first line's recognized text.
        assertEquals("你好笔记", fake.preContexts.get(1));
        // Writing area height follows the line region height.
        assertEquals(10f, fake.writingAreaHeights.get(0), 0.001f);
    }

    @Test
    public void recognizePageSkipsBlankLines() throws HandwritingException {
        List<Stroke> strokes = new ArrayList<>();
        strokes.add(stroke(0f, 0f, 100f, 10f, 0));
        strokes.add(stroke(0f, 500f, 100f, 510f, 1000));
        FakeRecognizer fake = new FakeRecognizer();
        fake.blankSecondLine = true;

        String text = HandwritingToTextPlanner.recognizePage(
                fake, HandwritingToTextPlanner.adaptStrokes(strokes));
        assertEquals("你好笔记", text);
    }

    @Test
    public void recognizePageAllBlankYieldsEmpty() throws HandwritingException {
        List<Stroke> strokes = new ArrayList<>();
        strokes.add(stroke(0f, 500f, 100f, 510f, 0));
        FakeRecognizer fake = new FakeRecognizer();
        fake.blankSecondLine = true;

        String text = HandwritingToTextPlanner.recognizePage(
                fake, HandwritingToTextPlanner.adaptStrokes(strokes));
        assertEquals("", text);
    }

    @Test
    public void recognizePageNeverFabricatesOnEngineFailure() {
        List<Stroke> strokes = new ArrayList<>();
        strokes.add(stroke(0f, 0f, 100f, 10f, 0));
        FakeRecognizer fake = new FakeRecognizer();
        fake.throwOnRecognize = true;
        try {
            HandwritingToTextPlanner.recognizePage(
                    fake, HandwritingToTextPlanner.adaptStrokes(strokes));
            fail("engine failure must propagate as HandwritingException");
        } catch (HandwritingException e) {
            assertEquals(HandwritingException.ErrorCode.RECOGNITION_FAILED, e.code());
        }
    }

    // ------------------------------------------------------------------
    // contentBottom / insertBounds
    // ------------------------------------------------------------------

    @Test
    public void contentBottomConsidersStrokesAndObjects() {
        Page page = new Page();
        assertEquals(0f, HandwritingToTextPlanner.contentBottom(page), 0.001f);

        page.strokes.add(stroke(0f, 0f, 100f, 60f, 0));
        assertEquals(60f, HandwritingToTextPlanner.contentBottom(page), 0.001f);

        TextBoxObject box = new TextBoxObject();
        box.bounds = new float[]{10f, 300f, 200f, 360f};
        page.objects.add(box);
        assertEquals(360f, HandwritingToTextPlanner.contentBottom(page), 0.001f);
    }

    @Test
    public void insertBoundsBelowContent() {
        float[] b = HandwritingToTextPlanner.insertBounds(200f, 595f, 842f, 2);
        assertEquals(24f, b[0], 0.001f);
        assertEquals(216f, b[1], 0.001f); // 200 + gap
        assertEquals(595f - 24f, b[2], 0.001f);
        // Height = (2 + 0.6) * 18 * 1.2
        assertEquals(216f + 2.6f * 18f * 1.2f, b[3], 0.01f);
    }

    @Test
    public void insertBoundsOnEmptyPageStartsAtTopMargin() {
        float[] b = HandwritingToTextPlanner.insertBounds(0f, 595f, 842f, 1);
        assertEquals(48f, b[1], 0.001f);
    }

    @Test
    public void insertBoundsClampedInsidePage() {
        float[] b = HandwritingToTextPlanner.insertBounds(830f, 595f, 842f, 5);
        assertTrue(b[3] <= 842f - 24f + 0.001f);
        assertTrue(b[1] >= 24f);
    }

    @Test
    public void insertBoundsSingleLineHasMinimumHeight() {
        float[] b = HandwritingToTextPlanner.insertBounds(0f, 595f, 842f, 1);
        float min = 18f * 1.2f * 1.6f;
        assertTrue(b[3] - b[1] >= min - 0.001f);
    }
}
