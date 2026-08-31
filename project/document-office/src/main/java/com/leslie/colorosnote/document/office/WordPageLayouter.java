package com.leslie.colorosnote.document.office;

import java.util.ArrayList;
import java.util.List;

/**
 * Flows a {@link WordBlock} stream into fixed A4 pages (794x1123 px @96dpi,
 * 0.75in margins). Output is pure draw commands + text layer — never a bitmap;
 * rasterization is core-render's job.
 *
 * <p>Layout is approximate by design (heuristic text metrics, no font shaping):
 * the goal is a stable, readable base page for annotation, not a Word-perfect
 * render. Tables are laid out as equal-width grids with wrapping cell text;
 * images are scaled to the content width keeping aspect ratio; explicit page
 * breaks and pageBreakBefore are honored.
 */
public final class WordPageLayouter {

    public static final int PAGE_WIDTH_PX = 794;
    public static final int PAGE_HEIGHT_PX = 1123;
    public static final int MARGIN_PX = 72; // 0.75in @96dpi

    private static final int CONTENT_WIDTH = PAGE_WIDTH_PX - 2 * MARGIN_PX;
    private static final int CONTENT_BOTTOM = PAGE_HEIGHT_PX - MARGIN_PX;
    private static final float MIN_LINE_HEIGHT = 10f;
    private static final float DEFAULT_CELL_FONT_PX = TextMetrics.ptToPx(10.5f);
    private static final int TABLE_BORDER_COLOR = 0xFF888888;

    private final ImportGuard guard;

    private final List<PageLayout> pages = new ArrayList<>();
    private List<DrawCommand> commands = new ArrayList<>();
    private StringBuilder textLayer = new StringBuilder();
    private float cursorY = MARGIN_PX;

    public WordPageLayouter(ImportGuard guard) {
        this.guard = guard;
    }

    public List<PageLayout> layout(List<WordBlock> blocks) throws OfficeImportException {
        for (WordBlock block : blocks) {
            guard.tickBlock();
            if (block instanceof WordBlock.PageBreak) {
                finishPage();
            } else if (block instanceof WordBlock.Paragraph) {
                layoutParagraph((WordBlock.Paragraph) block);
            } else if (block instanceof WordBlock.Table) {
                layoutTable((WordBlock.Table) block);
            } else if (block instanceof WordBlock.Image) {
                layoutImage((WordBlock.Image) block);
            }
        }
        finishPage();
        if (pages.isEmpty()) {
            // Even an empty document yields one blank base page.
            emitPage();
        }
        return pages;
    }

    // ------------------------------------------------------------- paragraph

    private void layoutParagraph(WordBlock.Paragraph p) throws OfficeImportException {
        if (p.pageBreakBefore) {
            finishPage();
        }
        cursorY += p.spacingBeforePx;
        List<Line> lines = wrap(p);
        StringBuilder paragraphText = new StringBuilder();
        for (WordBlock.Run run : p.runs) {
            paragraphText.append(run.text);
        }
        for (Line line : lines) {
            ensureSpace(line.height);
            float x = alignedX(p.align, line.width);
            for (Segment seg : line.segments) {
                float baseline = cursorY + line.height * 0.8f;
                commands.add(new DrawCommand.Text(x, baseline, seg.run.text, seg.run.sizePx,
                        seg.run.colorArgb, seg.run.bold, seg.run.italic, p.align));
                x += seg.width;
            }
            cursorY += line.height;
        }
        if (paragraphText.length() > 0) {
            guard.addText(paragraphText.length());
            textLayer.append(paragraphText).append('\n');
        }
        cursorY += p.spacingAfterPx;
    }

    /** Greedy character-level wrap into styled segments. */
    private List<Line> wrap(WordBlock.Paragraph p) {
        List<Line> lines = new ArrayList<>();
        Line line = new Line();
        for (WordBlock.Run run : p.runs) {
            String text = run.text;
            StringBuilder seg = new StringBuilder();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\n') {
                    line.addSegment(seg, run);
                    lines.add(line);
                    line = new Line();
                    continue;
                }
                float w = TextMetrics.charWidth(c, run.sizePx);
                if (line.width + w > CONTENT_WIDTH && line.width > 0) {
                    line.addSegment(seg, run);
                    lines.add(line);
                    line = new Line();
                }
                seg.append(c);
                line.width += w;
                line.height = Math.max(line.height, TextMetrics.lineHeight(run.sizePx));
            }
            line.addSegment(seg, run);
        }
        if (line.width > 0 || lines.isEmpty()) {
            line.height = Math.max(line.height, MIN_LINE_HEIGHT);
            lines.add(line);
        }
        return lines;
    }

    // ----------------------------------------------------------------- table

    private void layoutTable(WordBlock.Table table) throws OfficeImportException {
        if (table.rows.isEmpty()) {
            return;
        }
        int cols = 0;
        for (List<String> row : table.rows) {
            cols = Math.max(cols, row.size());
        }
        if (cols == 0) {
            return;
        }
        float colWidth = (float) CONTENT_WIDTH / cols;
        float padding = 4f;
        for (List<String> row : table.rows) {
            guard.tickBlock();
            // Wrap every cell to find the row height.
            List<List<String>> wrappedCells = new ArrayList<>();
            float rowHeight = 0f;
            float cellLineHeight = TextMetrics.lineHeight(DEFAULT_CELL_FONT_PX);
            for (int c = 0; c < cols; c++) {
                String cell = c < row.size() ? row.get(c) : "";
                List<String> cellLines = wrapPlain(cell, colWidth - 2 * padding, DEFAULT_CELL_FONT_PX);
                wrappedCells.add(cellLines);
                rowHeight = Math.max(rowHeight, cellLines.size() * cellLineHeight + 2 * padding);
            }
            ensureSpace(rowHeight);
            float x = MARGIN_PX;
            for (int c = 0; c < cols; c++) {
                commands.add(new DrawCommand.Rect(x, cursorY, colWidth, rowHeight,
                        TABLE_BORDER_COLOR, false, 1f));
                float ty = cursorY + padding;
                for (String cellLine : wrappedCells.get(c)) {
                    float baseline = ty + cellLineHeight * 0.8f;
                    commands.add(new DrawCommand.Text(x + padding, baseline, cellLine,
                            DEFAULT_CELL_FONT_PX, 0xFF000000, false, false, TextAlign.LEFT));
                    ty += cellLineHeight;
                }
                x += colWidth;
            }
            textLayer.append(String.join(" | ", row)).append('\n');
            cursorY += rowHeight;
        }
        cursorY += 6f; // small gap after a table
    }

    /** Plain-text wrap for cells (single style). */
    private List<String> wrapPlain(String text, float width, float sizePx) {
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        float curWidth = 0f;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            float w = TextMetrics.charWidth(c, sizePx);
            if (curWidth + w > width && cur.length() > 0) {
                lines.add(cur.toString());
                cur.setLength(0);
                curWidth = 0f;
            }
            cur.append(c);
            curWidth += w;
        }
        if (cur.length() > 0 || lines.isEmpty()) {
            lines.add(cur.toString());
        }
        return lines;
    }

    // ----------------------------------------------------------------- image

    private void layoutImage(WordBlock.Image img) throws OfficeImportException {
        float w = img.widthPx;
        float h = img.heightPx;
        float maxW = CONTENT_WIDTH;
        float maxH = CONTENT_BOTTOM - MARGIN_PX;
        if (w > maxW) {
            h *= maxW / w;
            w = maxW;
        }
        if (h > maxH) {
            w *= maxH / h;
            h = maxH;
        }
        ensureSpace(h);
        commands.add(new DrawCommand.Image(MARGIN_PX, cursorY, w, h, img.resourceId));
        cursorY += h + 4f;
    }

    // ---------------------------------------------------------------- paging

    private void ensureSpace(float needed) throws OfficeImportException {
        if (cursorY + needed > CONTENT_BOTTOM && !commands.isEmpty()) {
            finishPage();
        }
    }

    private void finishPage() throws OfficeImportException {
        if (commands.isEmpty() && textLayer.length() == 0 && !pages.isEmpty()) {
            cursorY = MARGIN_PX; // consecutive breaks: no duplicate blank pages
            return;
        }
        emitPage();
    }

    private void emitPage() throws OfficeImportException {
        guard.startPage();
        pages.add(new PageLayout(PAGE_WIDTH_PX, PAGE_HEIGHT_PX, null, commands, textLayer.toString()));
        commands = new ArrayList<>();
        textLayer = new StringBuilder();
        cursorY = MARGIN_PX;
    }

    private float alignedX(TextAlign align, float lineWidth) {
        switch (align) {
            case CENTER:
                return MARGIN_PX + Math.max(0f, (CONTENT_WIDTH - lineWidth) / 2f);
            case RIGHT:
                return MARGIN_PX + Math.max(0f, CONTENT_WIDTH - lineWidth);
            default:
                return MARGIN_PX;
        }
    }

    // -------------------------------------------------------------- line model

    private static final class Segment {
        final WordBlock.Run run;
        final String text;
        final float width;

        Segment(WordBlock.Run run, String text, float width) {
            this.run = run;
            this.text = text;
            this.width = width;
        }
    }

    private static final class Line {
        final List<Segment> segments = new ArrayList<>();
        float width;
        float height;

        void addSegment(StringBuilder buf, WordBlock.Run run) {
            if (buf.length() == 0) {
                return;
            }
            String text = buf.toString();
            segments.add(new Segment(run, text, TextMetrics.measure(text, run.sizePx)));
            buf.setLength(0);
        }
    }
}
