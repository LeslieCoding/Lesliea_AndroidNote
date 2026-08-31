package com.leslie.colorosnote.document.office;

import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.CharacterRun;
import org.apache.poi.hwpf.usermodel.Paragraph;
import org.apache.poi.hwpf.usermodel.Picture;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STBrType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Extracts a format-neutral {@link WordBlock} stream from .docx (XWPF) and
 * legacy .doc (HWPF). POI parses only; macros/embedded scripts are never
 * executed (POI has no execution engine — requirement 12.3).
 *
 * <p>Known fidelity limits: HWPF (.doc) exposes no run colors and no reliable
 * inline-picture anchors, so legacy pictures are appended in document order and
 * a warning is recorded; run color/borders beyond cell grids are approximated.
 */
final class WordExtractor {

    private static final float DEFAULT_FONT_PT = 11f;
    private static final int DEFAULT_COLOR = 0xFF000000;

    private final ImportGuard guard;
    private final ResourceTable resources;
    private final List<String> warnings;
    private final Set<String> fontFamilies = new LinkedHashSet<>();

    WordExtractor(ImportGuard guard, ResourceTable resources, List<String> warnings) {
        this.guard = guard;
        this.resources = resources;
        this.warnings = warnings;
    }

    // ------------------------------------------------------------------ docx

    List<WordBlock> fromXWPF(XWPFDocument doc) throws OfficeImportException {
        List<WordBlock> blocks = new ArrayList<>();
        appendHeaderFooter(blocks, doc);
        for (IBodyElement element : doc.getBodyElements()) {
            guard.tickBlock();
            if (element instanceof XWPFParagraph) {
                appendParagraph(blocks, (XWPFParagraph) element);
            } else if (element instanceof XWPFTable) {
                blocks.add(tableBlock((XWPFTable) element));
            }
        }
        appendFooter(blocks, doc);
        noteFonts();
        return blocks;
    }

    private void appendHeaderFooter(List<WordBlock> blocks, XWPFDocument doc) {
        // Approximation: first header text becomes a small gray lead paragraph.
        try {
            List<XWPFHeader> headers = doc.getHeaderList();
            if (headers != null) {
                for (XWPFHeader h : headers) {
                    String text = h.getText();
                    if (text != null && !text.trim().isEmpty()) {
                        blocks.add(smallGrayParagraph(text.trim()));
                        return;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            warnings.add("Could not read docx headers");
        }
    }

    private void appendFooter(List<WordBlock> blocks, XWPFDocument doc) {
        try {
            List<XWPFFooter> footers = doc.getFooterList();
            if (footers != null) {
                for (XWPFFooter f : footers) {
                    String text = f.getText();
                    if (text != null && !text.trim().isEmpty()) {
                        blocks.add(smallGrayParagraph(text.trim()));
                        return;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            warnings.add("Could not read docx footers");
        }
    }

    private WordBlock.Paragraph smallGrayParagraph(String text) {
        WordBlock.Paragraph p = new WordBlock.Paragraph();
        p.runs.add(new WordBlock.Run(text, TextMetrics.ptToPx(8f), false, false, 0xFF666666));
        return p;
    }

    private void appendParagraph(List<WordBlock> blocks, XWPFParagraph src)
            throws OfficeImportException {
        WordBlock.Paragraph dst = new WordBlock.Paragraph();
        dst.align = mapAlign(src.getAlignment());
        dst.pageBreakBefore = src.isPageBreak();
        dst.spacingBeforePx = twipsToPx(src.getSpacingBefore());
        dst.spacingAfterPx = twipsToPx(src.getSpacingAfter());

        for (XWPFRun run : src.getRuns()) {
            // Explicit page breaks inside runs.
            for (CTBr br : run.getCTR().getBrArray()) {
                if (br.getType() == STBrType.PAGE) {
                    blocks.add(dst.runs.isEmpty() ? new WordBlock.PageBreak() : dst);
                    blocks.add(new WordBlock.PageBreak());
                    dst = new WordBlock.Paragraph();
                    dst.align = mapAlign(src.getAlignment());
                }
            }
            String text = run.text();
            if (text != null && !text.isEmpty()) {
                guard.addText(text.length());
                float sizePx = TextMetrics.ptToPx(run.getFontSize() > 0 ? run.getFontSize() : DEFAULT_FONT_PT);
                dst.runs.add(new WordBlock.Run(text, sizePx, run.isBold(), run.isItalic(),
                        parseColor(run.getColor())));
                noteFamily(run.getFontFamily());
            }
            for (XWPFPicture pic : run.getEmbeddedPictures()) {
                WordBlock.Image img = pictureBlock(pic);
                if (img != null) {
                    if (!dst.runs.isEmpty()) {
                        blocks.add(dst);
                        dst = new WordBlock.Paragraph();
                        dst.align = mapAlign(src.getAlignment());
                    }
                    blocks.add(img);
                }
            }
        }
        if (!dst.runs.isEmpty() || dst.pageBreakBefore) {
            blocks.add(dst);
        }
    }

    private WordBlock.Image pictureBlock(XWPFPicture pic) throws OfficeImportException {
        try {
            byte[] data = pic.getPictureData().getData();
            String id = resources.add(data, pic.getPictureData().suggestFileExtension());
            if (id == null) {
                return null;
            }
            float w = TextMetrics.ptToPx(72f * 2f); // fallback 2in
            float h = w * 0.75f;
            try {
                org.openxmlformats.schemas.drawingml.x2006.main.CTPositiveSize2D ext =
                        pic.getCTPicture().getSpPr().getXfrm().getExt();
                if (ext != null && ext.getCx() > 0 && ext.getCy() > 0) {
                    w = TextMetrics.emuToPx(ext.getCx());
                    h = TextMetrics.emuToPx(ext.getCy());
                }
            } catch (RuntimeException ignored) {
                // keep fallback size
            }
            return new WordBlock.Image(id, w, h);
        } catch (RuntimeException e) {
            warnings.add("Skipped unreadable embedded picture");
            return null;
        }
    }

    private WordBlock.Table tableBlock(XWPFTable table) throws OfficeImportException {
        WordBlock.Table block = new WordBlock.Table();
        for (XWPFTableRow row : table.getRows()) {
            guard.tickBlock();
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                String text = cell.getText();
                if (text == null) {
                    text = "";
                }
                guard.addText(text.length());
                cells.add(text.trim());
            }
            if (!cells.isEmpty()) {
                block.rows.add(cells);
            }
        }
        return block;
    }

    // ------------------------------------------------------------------- doc

    List<WordBlock> fromHWPF(HWPFDocument doc) throws OfficeImportException {
        List<WordBlock> blocks = new ArrayList<>();
        Range range = doc.getRange();
        List<String> tableCells = null;
        WordBlock.Table table = null;
        int extractedPictures = 0;

        for (int i = 0; i < range.numParagraphs(); i++) {
            guard.tickBlock();
            Paragraph p = range.getParagraph(i);
            String raw = p.text();
            if (raw == null) {
                continue;
            }
            boolean rowMark = raw.trim().equals("\u0007"); // HWPF end-of-table-row mark (0x07)
            if (p.isInTable()) {
                if (table == null) {
                    table = new WordBlock.Table();
                    tableCells = new ArrayList<>();
                }
                if (rowMark) {
                    if (!tableCells.isEmpty()) {
                        table.rows.add(tableCells);
                        tableCells = new ArrayList<>();
                    }
                } else {
                    String cell = raw.replace("\u0007", "").replace("\r", "").trim();
                    guard.addText(cell.length());
                    tableCells.add(cell);
                }
                continue;
            }
            if (table != null) { // leaving a table
                if (tableCells != null && !tableCells.isEmpty()) {
                    table.rows.add(tableCells);
                }
                if (!table.rows.isEmpty()) {
                    blocks.add(table);
                }
                table = null;
                tableCells = null;
            }

            WordBlock.Paragraph dst = new WordBlock.Paragraph();
            dst.pageBreakBefore = p.pageBreakBefore();
            String text = raw.replace("\r", "");
            if (text.contains("\f")) {
                blocks.add(new WordBlock.PageBreak());
                text = text.replace("\f", "");
            }
            if (text.trim().isEmpty()) {
                if (dst.pageBreakBefore) {
                    blocks.add(dst);
                }
                continue;
            }
            guard.addText(text.length());

            // Split into styled runs via character runs, best effort.
            int offset = 0;
            for (int r = 0; r < p.numCharacterRuns() && offset < text.length(); r++) {
                CharacterRun cr = p.getCharacterRun(r);
                int len = Math.min(cr.text().length(), text.length() - offset);
                if (len <= 0) {
                    continue;
                }
                String piece = text.substring(offset, offset + len);
                float sizePx = TextMetrics.ptToPx(cr.getFontSize() > 0 ? cr.getFontSize() / 2f : DEFAULT_FONT_PT);
                dst.runs.add(new WordBlock.Run(piece, sizePx, cr.isBold(), cr.isItalic(), DEFAULT_COLOR));
                noteFamily(cr.getFontName());
                offset += len;
            }
            if (offset < text.length()) {
                // Trailing text with no matching character run: default style.
                dst.runs.add(new WordBlock.Run(text.substring(offset),
                        TextMetrics.ptToPx(DEFAULT_FONT_PT), false, false, DEFAULT_COLOR));
            }
            blocks.add(dst);
        }
        if (table != null && !table.rows.isEmpty()) {
            if (tableCells != null && !tableCells.isEmpty()) {
                table.rows.add(tableCells);
            }
            blocks.add(table);
        }

        // HWPF gives no reliable inline anchors; append pictures in document order.
        try {
            for (Picture pic : doc.getPicturesTable().getAllPictures()) {
                String id = resources.add(pic.getContent(), pic.suggestFileExtension());
                if (id != null) {
                    float w = TextMetrics.ptToPx(pic.getWidth() > 0 ? pic.getWidth() : 144f);
                    float h = TextMetrics.ptToPx(pic.getHeight() > 0 ? pic.getHeight() : 108f);
                    blocks.add(new WordBlock.Image(id, w, h));
                    extractedPictures++;
                }
            }
        } catch (RuntimeException e) {
            warnings.add("Could not extract pictures from .doc");
        }
        if (extractedPictures > 0) {
            warnings.add("Legacy .doc pictures are appended in document order (no inline anchors)");
        }
        noteFonts();
        return blocks;
    }

    // ---------------------------------------------------------------- helpers

    private void noteFamily(String family) {
        if (family != null && !family.isEmpty()) {
            fontFamilies.add(family);
        }
    }

    private void noteFonts() {
        if (!fontFamilies.isEmpty()) {
            warnings.add("Fonts referenced (substituted if missing on device): " + fontFamilies);
        }
    }

    private static float twipsToPx(int twentiethsOfPoint) {
        if (twentiethsOfPoint <= 0) {
            return 0f;
        }
        return TextMetrics.ptToPx(twentiethsOfPoint / 20f);
    }

    private static int parseColor(String hex) {
        if (hex == null || hex.isEmpty() || "auto".equalsIgnoreCase(hex)) {
            return DEFAULT_COLOR;
        }
        try {
            return 0xFF000000 | (Integer.parseInt(hex, 16) & 0xFFFFFF);
        } catch (NumberFormatException e) {
            return DEFAULT_COLOR;
        }
    }

    private static TextAlign mapAlign(org.apache.poi.xwpf.usermodel.ParagraphAlignment align) {
        if (align == null) {
            return TextAlign.LEFT;
        }
        switch (align) {
            case CENTER:
                return TextAlign.CENTER;
            case RIGHT:
                return TextAlign.RIGHT;
            default:
                return TextAlign.LEFT;
        }
    }
}
