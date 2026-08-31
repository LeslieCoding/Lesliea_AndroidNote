package com.leslie.colorosnote.feature.ocr;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Unit tests for the pure-JVM parts of {@link OcrResult}. */
public class OcrResultTest {

    private static final float EPS = 1e-4f;

    private static OcrResult sample(int imageWidth, int imageHeight) {
        OcrResult.Element e1 = new OcrResult.Element("你", new OcrBox(10, 10, 30, 30), 0.9f);
        OcrResult.Element e2 = new OcrResult.Element("好", new OcrBox(30, 10, 50, 30), 0.5f);
        OcrResult.Line line = new OcrResult.Line("你好", new OcrBox(10, 10, 50, 30), 0.7f,
                Arrays.asList(e1, e2));
        OcrResult.Block block = new OcrResult.Block("你好", new OcrBox(10, 10, 50, 30),
                Collections.singletonList(line));
        return new OcrResult(Collections.singletonList(block), imageWidth, imageHeight);
    }

    @Test
    public void scaledTo_doublesCoordinatesWhenTargetIsTwiceTheSource() {
        OcrResult scaled = sample(1000, 500).scaledTo(2000, 1000);
        OcrBox box = scaled.blocks.get(0).lines.get(0).box;
        assertEquals(20f, box.left, EPS);
        assertEquals(20f, box.top, EPS);
        assertEquals(100f, box.right, EPS);
        assertEquals(60f, box.bottom, EPS);
        assertEquals(2000, scaled.imageWidth);
        assertEquals(1000, scaled.imageHeight);
        // Element boxes are scaled too.
        OcrBox elemBox = scaled.blocks.get(0).lines.get(0).elements.get(1).box;
        assertEquals(60f, elemBox.left, EPS);
    }

    @Test
    public void scaledTo_sameSizeIsIdentity() {
        OcrResult r = sample(100, 100);
        assertTrue(r.scaledTo(100, 100) == r);
    }

    @Test
    public void meanConfidence_averagesElementsAndIgnoresNull() {
        OcrResult.Element noConf = new OcrResult.Element("x", new OcrBox(0, 0, 1, 1), null);
        OcrResult withExtra = new OcrResult(Collections.singletonList(
                new OcrResult.Block("x", new OcrBox(0, 0, 1, 1), Collections.singletonList(
                        new OcrResult.Line("x", new OcrBox(0, 0, 1, 1), null,
                                Collections.singletonList(noConf))))), 100, 100);
        assertNull(withExtra.meanConfidence());
        assertEquals(0.7f, sample(100, 100).meanConfidence(), EPS);
    }

    @Test
    public void allText_joinsBlocksWithNewlines() {
        OcrResult.Block b1 = new OcrResult.Block("ab", new OcrBox(0, 0, 1, 1), null);
        OcrResult.Block b2 = new OcrResult.Block("cd", new OcrBox(0, 2, 1, 3), null);
        OcrResult r = new OcrResult(Arrays.asList(b1, b2), 10, 10);
        assertEquals("ab\ncd", r.allText());
        assertEquals("", new OcrResult(null, 10, 10).allText());
    }

    @Test
    public void emptyResultIsSafe() {
        OcrResult r = new OcrResult(null, 0, 0);
        List<OcrResult.Block> blocks = r.blocks;
        assertTrue(blocks.isEmpty());
        assertNull(r.meanConfidence());
        assertTrue(r.scaledTo(10, 10) == r);
    }
}
