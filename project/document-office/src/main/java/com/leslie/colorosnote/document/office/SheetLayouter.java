package com.leslie.colorosnote.document.office;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;

import java.util.ArrayList;
import java.util.List;

/**
 * Lays out every sheet of a workbook as paged grids on A4-ish pages:
 *
 * <ul>
 *   <li>Column widths come from the file (1/256-char units -> px); columns are
 *       accumulated left-to-right into chunks that fit the content width, so an
 *       over-wide sheet splits into column blocks instead of one giant bitmap
 *       (requirement 12.2: never raster a whole sheet).</li>
 *   <li>Rows accumulate until the content height; page breaks create new pages.
 *       Page order: for each column chunk, pages top-to-bottom, then the next
 *       chunk (readable "report" order).</li>
 *   <li>Cell text uses {@link DataFormatter} display strings; borders become
 *       rect commands; cell font size/bold map to the text style. Merged cells
 *       keep their value in the top-left cell only (approximation).</li>
 * </ul>
 *
 * <p>Multiple sheets produce consecutive page runs named "SheetName (part N)".
 */
final class SheetLayouter {

    static final int PAGE_WIDTH_PX = 1123;  // landscape A4 suits grids better
    static final int PAGE_HEIGHT_PX = 794;
    static final int MARGIN_PX = 36;

    private static final int MAX_COLUMNS = 256;
    private static final float DEFAULT_COL_WIDTH_PX = 64f;
    private static final float MIN_COL_WIDTH_PX = 32f;
    private static final float DEFAULT_ROW_HEIGHT_PX = TextMetrics.ptToPx(15f);
    private static final float CELL_PADDING_PX = 3f;
    private static final int GRID_COLOR = 0xFF999999;

    private final ImportGuard guard;
    private final List<String> warnings;
    private final DataFormatter formatter = new DataFormatter();

    SheetLayouter(ImportGuard guard, List<String> warnings) {
        this.guard = guard;
        this.warnings = warnings;
    }

    List<PageLayout> fromWorkbook(Workbook wb) throws OfficeImportException {
        List<PageLayout> pages = new ArrayList<>();
        for (int s = 0; s < wb.getNumberOfSheets(); s++) {
            Sheet sheet = wb.getSheetAt(s);
            layoutSheet(wb, sheet, pages);
        }
        if (pages.isEmpty()) {
            guard.startPage();
            pages.add(new PageLayout(PAGE_WIDTH_PX, PAGE_HEIGHT_PX, "Empty workbook",
                    new ArrayList<>(), ""));
        }
        return pages;
    }

    private void layoutSheet(Workbook wb, Sheet sheet, List<PageLayout> pages)
            throws OfficeImportException {
        String sheetName = sheet.getSheetName();
        int colCount = Math.min(computeColumnCount(sheet), MAX_COLUMNS);
        int lastRow = sheet.getLastRowNum();
        if (colCount == 0 || lastRow < 0) {
            return; // genuinely empty sheet contributes no page
        }
        if (hasDrawing(sheet)) {
            warnings.add("Sheet '" + sheetName + "': embedded images/charts are not imported");
        }

        float contentW = PAGE_WIDTH_PX - 2f * MARGIN_PX;
        float contentH = PAGE_HEIGHT_PX - 2f * MARGIN_PX;

        float[] colWidths = new float[colCount];
        for (int c = 0; c < colCount; c++) {
            colWidths[c] = columnWidthPx(sheet, c);
        }

        // Chunk columns to fit the content width.
        List<int[]> chunks = new ArrayList<>(); // [startCol, endColExclusive]
        int start = 0;
        float acc = 0f;
        for (int c = 0; c < colCount; c++) {
            if (acc + colWidths[c] > contentW && c > start) {
                chunks.add(new int[]{start, c});
                start = c;
                acc = 0f;
            }
            acc += colWidths[c];
        }
        chunks.add(new int[]{start, colCount});

        int part = 0;
        for (int[] chunk : chunks) {
            List<DrawCommand> commands = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            float y = MARGIN_PX;
            boolean pageStarted = false;
            for (int r = 0; r <= lastRow; r++) {
                guard.tickBlock();
                Row row = sheet.getRow(r);
                float rowH = row != null ? rowHeightPx(row) : DEFAULT_ROW_HEIGHT_PX;
                if (y + rowH > PAGE_HEIGHT_PX - MARGIN_PX && pageStarted) {
                    part++;
                    guard.startPage();
                    pages.add(new PageLayout(PAGE_WIDTH_PX, PAGE_HEIGHT_PX,
                            pageName(sheetName, part, chunks.size()), commands, text.toString()));
                    commands = new ArrayList<>();
                    text = new StringBuilder();
                    y = MARGIN_PX;
                    pageStarted = false;
                }
                pageStarted = true;
                emitRow(wb, row, chunk, colWidths, y, rowH, commands, text);
                y += rowH;
            }
            if (pageStarted) {
                part++;
                guard.startPage();
                pages.add(new PageLayout(PAGE_WIDTH_PX, PAGE_HEIGHT_PX,
                        pageName(sheetName, part, chunks.size()), commands, text.toString()));
            }
        }
    }

    private void emitRow(Workbook wb, Row row, int[] chunk, float[] colWidths, float y,
                         float rowH, List<DrawCommand> commands, StringBuilder text)
            throws OfficeImportException {
        float x = MARGIN_PX;
        StringBuilder rowText = new StringBuilder();
        for (int c = chunk[0]; c < chunk[1]; c++) {
            float colW = colWidths[c];
            commands.add(new DrawCommand.Rect(x, y, colW, rowH, GRID_COLOR, false, 0.75f));
            Cell cell = row != null ? row.getCell(c) : null;
            String value = cell == null ? "" : formatter.formatCellValue(cell).trim();
            if (!value.isEmpty()) {
                guard.addText(value.length());
                float sizePx = DEFAULT_ROW_HEIGHT_PX * 0.55f;
                boolean bold = false;
                try {
                    CellStyle style = cell.getCellStyle();
                    if (style != null) {
                        Font font = wb.getFontAt(style.getFontIndexAsInt());
                        if (font != null) {
                            sizePx = Math.max(8f, TextMetrics.ptToPx(font.getFontHeightInPoints()));
                            bold = font.getBold();
                        }
                    }
                } catch (RuntimeException ignored) {
                    // keep defaults
                }
                float maxW = colW - 2 * CELL_PADDING_PX;
                String clipped = clipToWidth(value, maxW, sizePx);
                float baseline = y + rowH * 0.68f;
                commands.add(new DrawCommand.Text(x + CELL_PADDING_PX, baseline, clipped,
                        sizePx, 0xFF000000, bold, false, TextAlign.LEFT));
                if (rowText.length() > 0) {
                    rowText.append(" | ");
                }
                rowText.append(value);
            }
            x += colW;
        }
        if (rowText.length() > 0) {
            text.append(rowText).append('\n');
        }
    }

    private static String clipToWidth(String value, float maxW, float sizePx) {
        if (TextMetrics.measure(value, sizePx) <= maxW) {
            return value;
        }
        StringBuilder sb = new StringBuilder();
        float w = 0f;
        float ellipsisW = TextMetrics.measure("…", sizePx);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            float cw = TextMetrics.charWidth(c, sizePx);
            if (w + cw + ellipsisW > maxW) {
                break;
            }
            sb.append(c);
            w += cw;
        }
        return sb.append('…').toString();
    }

    private static String pageName(String sheetName, int part, int chunkCount) {
        return chunkCount > 1 || part > 1
                ? sheetName + " (part " + part + ")"
                : sheetName;
    }

    private static int computeColumnCount(Sheet sheet) {
        int max = 0;
        for (Row row : sheet) {
            short last = row.getLastCellNum();
            if (last > max) {
                max = last;
            }
        }
        return max;
    }

    private static float columnWidthPx(Sheet sheet, int col) {
        try {
            int units = sheet.getColumnWidth(col); // 1/256 of a character width
            if (units <= 0) {
                return DEFAULT_COL_WIDTH_PX;
            }
            return Math.max(MIN_COL_WIDTH_PX, units * 7f / 256f + 6f);
        } catch (RuntimeException e) {
            return DEFAULT_COL_WIDTH_PX;
        }
    }

    private static float rowHeightPx(Row row) {
        float pt = row.getHeightInPoints();
        return pt > 0 ? TextMetrics.ptToPx(pt) : DEFAULT_ROW_HEIGHT_PX;
    }

    private static boolean hasDrawing(Sheet sheet) {
        try {
            return sheet.getDrawingPatriarch() != null;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
