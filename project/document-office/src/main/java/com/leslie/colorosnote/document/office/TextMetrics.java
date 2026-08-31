package com.leslie.colorosnote.document.office;

/**
 * Approximate text measurement for layout. Without android.graphics.Paint we use
 * heuristics: ASCII ~0.52em, CJK full-width 1em, others 0.6em. Rendering is exact
 * in core-render; this only decides line wrapping of the fixed base page.
 */
final class TextMetrics {

    /** Points (1/72in) to layout pixels at 96 dpi. */
    static float ptToPx(float pt) {
        return pt * 96f / 72f;
    }

    /** EMU (Office measure) to layout pixels at 96 dpi. */
    static float emuToPx(long emu) {
        return emu * 96f / 914400f;
    }

    static float charWidth(char c, float fontSizePx) {
        if (c == '\t') {
            return fontSizePx * 2f;
        }
        if (c < 0x80) {
            return fontSizePx * 0.52f;
        }
        Character.UnicodeScript script = Character.UnicodeScript.of(c);
        if (script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL) {
            return fontSizePx;
        }
        return fontSizePx * 0.6f;
    }

    static float measure(String text, float fontSizePx) {
        float w = 0f;
        for (int i = 0; i < text.length(); i++) {
            w += charWidth(text.charAt(i), fontSizePx);
        }
        return w;
    }

    /** Default single-line height for a font size. */
    static float lineHeight(float fontSizePx) {
        return fontSizePx * 1.35f;
    }

    private TextMetrics() {
    }
}
