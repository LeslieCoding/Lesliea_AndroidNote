package com.leslie.colorosnote.document.office;

import java.util.ArrayList;
import java.util.List;

/**
 * Intermediate, format-neutral block model between Word extraction (docx/doc)
 * and {@link WordPageLayouter}.
 */
abstract class WordBlock {

    private WordBlock() {
    }

    /** One styled run inside a paragraph; sizes already converted to px. */
    static final class Run {
        final String text;
        final float sizePx;
        final boolean bold;
        final boolean italic;
        final int colorArgb;

        Run(String text, float sizePx, boolean bold, boolean italic, int colorArgb) {
            this.text = text;
            this.sizePx = sizePx;
            this.bold = bold;
            this.italic = italic;
            this.colorArgb = colorArgb;
        }
    }

    static final class Paragraph extends WordBlock {
        final List<Run> runs = new ArrayList<>();
        TextAlign align = TextAlign.LEFT;
        float spacingBeforePx;
        float spacingAfterPx;
        boolean pageBreakBefore;
    }

    /** Rectangular grid of plain-text cells (cell formatting is flattened). */
    static final class Table extends WordBlock {
        final List<List<String>> rows = new ArrayList<>();
    }

    static final class Image extends WordBlock {
        final String resourceId;
        final float widthPx;
        final float heightPx;

        Image(String resourceId, float widthPx, float heightPx) {
            this.resourceId = resourceId;
            this.widthPx = widthPx;
            this.heightPx = heightPx;
        }
    }

    /** Explicit page break marker. */
    static final class PageBreak extends WordBlock {
    }
}
