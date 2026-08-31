package com.leslie.colorosnote.document.office;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

/** Pure-JVM tests for Excel (.xlsx/.xls) sheet paging. */
public class SheetImportTest {

    private static File writeTemp(byte[] bytes, String suffix) throws Exception {
        File f = File.createTempFile("sheet-import-test", suffix);
        f.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(bytes);
        }
        return f;
    }

    private static byte[] buildXlsx() throws Exception {
        XSSFWorkbook wb = new XSSFWorkbook();
        // Sheet 1: tall -> row paging.
        Sheet tall = wb.createSheet("Tall");
        for (int r = 0; r < 80; r++) {
            Row row = tall.createRow(r);
            row.createCell(0).setCellValue("row-" + r);
            row.createCell(1).setCellValue(r * 1.5);
        }
        // Sheet 2: wide -> column chunking.
        Sheet wide = wb.createSheet("Wide");
        Row header = wide.createRow(0);
        for (int c = 0; c < 20; c++) {
            wide.setColumnWidth(c, 200 * 256); // very wide columns force chunking
            Cell cell = header.createCell(c);
            cell.setCellValue("column-" + c);
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        wb.write(bos);
        wb.close();
        return bos.toByteArray();
    }

    @Test
    public void xlsxMultipleSheetsPageAndExtractText() throws Exception {
        File xlsx = writeTemp(buildXlsx(), ".xlsx");
        OfficeImportResult result = new PoiOfficeEngine().importDocument(
                xlsx, OfficeType.SHEET, ImportOptions.defaults(), ProgressCallback.NONE);

        List<PageLayout> pages = result.getPages();
        assertTrue("tall sheet must page, got " + pages.size(), pages.size() >= 2);

        String all = WordImportTest.allText(pages);
        assertTrue(all.contains("row-0"));
        assertTrue(all.contains("row-79"));
        assertTrue(all.contains("column-0"));
        assertTrue(all.contains("column-19"));

        // Wide sheet must have been chunked into multiple column blocks,
        // producing several pages named "Wide (part N)".
        long wideParts = pages.stream()
                .filter(p -> p.getName() != null && p.getName().startsWith("Wide"))
                .count();
        assertTrue("wide sheet chunked into parts, got " + wideParts, wideParts >= 2);

        // Cells render as border rects + text commands.
        boolean sawRect = false;
        boolean sawText = false;
        for (PageLayout page : pages) {
            for (DrawCommand cmd : page.getCommands()) {
                if (cmd instanceof DrawCommand.Rect) {
                    sawRect = true;
                }
                if (cmd instanceof DrawCommand.Text) {
                    sawText = true;
                }
            }
        }
        assertTrue(sawRect);
        assertTrue(sawText);
    }

    @Test
    public void legacyXlsReadsViaHSSF() throws Exception {
        HSSFWorkbook wb = new HSSFWorkbook();
        Sheet sheet = wb.createSheet("Legacy");
        Row row = sheet.createRow(0);
        row.createCell(0).setCellValue("legacy-cell");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        wb.write(bos);
        wb.close();
        File xls = writeTemp(bos.toByteArray(), ".xls");

        OfficeImportResult result = new PoiOfficeEngine().importDocument(
                xls, OfficeType.SHEET, ImportOptions.defaults(), ProgressCallback.NONE);
        assertTrue(result.getPages().size() >= 1);
        assertTrue(WordImportTest.allText(result.getPages()).contains("legacy-cell"));
        assertEquals("Legacy", result.getPages().get(0).getName());
    }
}
