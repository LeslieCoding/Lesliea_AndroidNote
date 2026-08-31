package com.leslie.colorosnote.core.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class TextNormalizerTest {

    @Test
    public void fullwidthBecomesHalfwidth() {
        assertEquals("abc 123", TextNormalizer.normalize("ＡＢＣ　１２３"));
    }

    @Test
    public void caseAndWhitespaceCollapsed() {
        assertEquals("hello world", TextNormalizer.normalize("  HeLLo \t\n World  "));
    }

    @Test
    public void cjkKeptVerbatim() {
        assertEquals("手写笔记", TextNormalizer.normalize("手写笔记"));
    }

    @Test
    public void mixedContent() {
        assertEquals("笔记 v2 版本", TextNormalizer.normalize("笔记 Ｖ2  版本"));
    }

    @Test
    public void nullAndEmpty() {
        assertEquals("", TextNormalizer.normalize(null));
        assertEquals("", TextNormalizer.normalize("   "));
    }

    @Test
    public void cjkDetection() {
        assertTrue(TextNormalizer.isCjk('汉'));
        assertTrue(!TextNormalizer.isCjk('a'));
    }
}
