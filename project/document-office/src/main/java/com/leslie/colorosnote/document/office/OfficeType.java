package com.leslie.colorosnote.document.office;

import java.util.Locale;

/** Supported Office document families. */
public enum OfficeType {
    WORD,
    SLIDES,
    SHEET;

    /** Infers the type from a file name extension, or null when unsupported. */
    public static OfficeType fromFileName(String name) {
        if (name == null) {
            return null;
        }
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".docx") || lower.endsWith(".doc")) {
            return WORD;
        }
        if (lower.endsWith(".pptx") || lower.endsWith(".ppt")) {
            return SLIDES;
        }
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
            return SHEET;
        }
        return null;
    }
}
