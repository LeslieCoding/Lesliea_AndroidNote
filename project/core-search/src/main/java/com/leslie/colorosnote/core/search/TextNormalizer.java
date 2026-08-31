package com.leslie.colorosnote.core.search;

import java.util.Locale;

/**
 * Normalizes text for indexing and querying so that Chinese full/half width,
 * case and whitespace differences do not break matching (spec 22.2).
 * Pure JVM, no Android dependencies.
 */
public final class TextNormalizer {

    private TextNormalizer() {
    }

    /**
     * Normalize raw text: NFKC-like fullwidth -> halfwidth mapping for ASCII
     * range, lowercase latin, collapse whitespace. CJK characters are kept
     * as-is (no space segmentation assumption).
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            // Fullwidth ASCII variants (U+FF01..U+FF5E) -> ASCII
            if (c >= 0xFF01 && c <= 0xFF5E) {
                c = (char) (c - 0xFEE0);
            } else if (c == 0x3000) { // ideographic space
                c = ' ';
            }
            if (Character.isWhitespace(c)) {
                // collapse runs of whitespace into one space
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') {
                    sb.append(' ');
                }
                continue;
            }
            sb.append(c);
        }
        return sb.toString().toLowerCase(Locale.ROOT).trim();
    }

    /** True if the character belongs to a CJK script block that has no spaces. */
    public static boolean isCjk(char c) {
        Character.UnicodeBlock b = Character.UnicodeBlock.of(c);
        return b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || b == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || b == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || b == Character.UnicodeBlock.HIRAGANA
                || b == Character.UnicodeBlock.KATAKANA
                || b == Character.UnicodeBlock.HANGUL_SYLLABLES;
    }
}
