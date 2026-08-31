package com.leslie.colorosnote.feature.export;

/**
 * Options for exporting a document (spec 37).
 */
public final class ExportOptions {

    public enum Format { PDF, PNG, JPEG, INKNOTE, TXT, MARKDOWN, BACKUP_ZIP }

    public Format format = Format.PDF;

    // Page range (inclusive, 0-based); null = all pages.
    public Integer fromPage;
    public Integer toPage;

    // PDF/PNG content switches (spec 37)
    public boolean includeBackground = true;
    public boolean includeInk = true;
    public boolean includeHighlights = true;
    public boolean includeStickers = true;
    /** Export tapes in their current revealed/covered state (spec 19). */
    public boolean tapeCurrentState = true;
    /** 36..600, default 150. */
    public int dpi = 150;

    public boolean isPageIncluded(int pageIndex, int pageCount) {
        int from = fromPage == null ? 0 : Math.max(0, fromPage);
        int to = toPage == null ? pageCount - 1 : Math.min(pageCount - 1, toPage);
        return pageIndex >= from && pageIndex <= to;
    }

    public int clampDpi() {
        return Math.max(36, Math.min(600, dpi));
    }
}
