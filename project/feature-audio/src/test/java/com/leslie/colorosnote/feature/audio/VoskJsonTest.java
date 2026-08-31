package com.leslie.colorosnote.feature.audio;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class VoskJsonTest {

    @Test
    public void extractsFinalText() {
        assertEquals("你好 世界", VoskJson.extractText("{\"text\": \"你好 世界\"}"));
    }

    @Test
    public void extractsPartial() {
        assertEquals("正在识别", VoskJson.extractPartial("{\"partial\": \"正在识别\"}"));
    }

    @Test
    public void emptyResultMapsToEmpty() {
        assertEquals("", VoskJson.extractText("{\"text\": \"\"}"));
        assertEquals("", VoskJson.extractText("{}"));
    }

    @Test
    public void garbageMapsToEmpty() {
        assertEquals("", VoskJson.extractText(null));
        assertEquals("", VoskJson.extractText(""));
        assertEquals("", VoskJson.extractText("not json"));
        assertEquals("", VoskJson.extractPartial("{\"text\": \"不是 partial\"}"));
    }

    @Test
    public void trimsWhitespace() {
        assertEquals("句子", VoskJson.extractText("{\"text\": \"  句子  \"}"));
    }
}
