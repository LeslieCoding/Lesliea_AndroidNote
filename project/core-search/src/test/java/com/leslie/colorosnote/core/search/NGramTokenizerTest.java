package com.leslie.colorosnote.core.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class NGramTokenizerTest {

    @Test
    public void cjkRunBecomesBigrams() {
        List<String> t = NGramTokenizer.tokenize("手写笔记");
        assertTrue(t.contains("手写"));
        assertTrue(t.contains("写笔"));
        assertTrue(t.contains("笔记"));
        assertTrue(t.contains("手写笔记")); // short run kept whole
    }

    @Test
    public void latinWordsWhole() {
        List<String> t = NGramTokenizer.tokenize("hello world");
        assertEquals(List.of("hello", "world"), t);
    }

    @Test
    public void mixedRun() {
        List<String> t = NGramTokenizer.tokenize("第1章介绍");
        assertTrue(t.contains("1"));
        assertTrue(t.contains("介绍"));
        assertTrue(t.contains("章介"));
    }

    @Test
    public void singleCjkChar() {
        List<String> t = NGramTokenizer.tokenize("页");
        assertEquals(List.of("页"), t);
    }

    @Test
    public void punctuationSplits() {
        List<String> t = NGramTokenizer.tokenize("你好,world");
        assertTrue(t.contains("你好"));
        assertTrue(t.contains("world"));
    }
}
