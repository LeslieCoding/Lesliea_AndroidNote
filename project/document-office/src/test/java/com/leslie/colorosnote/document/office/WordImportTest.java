package com.leslie.colorosnote.document.office;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.apache.poi.xwpf.usermodel.BreakType;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

/** Pure-JVM tests for Word (.docx) extraction and page layout. */
public class WordImportTest {

    /** Smallest valid PNG (1x1 transparent). */
    static final byte[] PNG_1PX = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, (byte) 0xC4,
            (byte) 0x89, 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41,
            0x54, 0x78, (byte) 0x9C, 0x63, 0x00, 0x01, 0x00, 0x00,
            0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, (byte) 0xB4, 0x00,
            0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, (byte) 0xAE,
            0x42, 0x60, (byte) 0x82
    };

    private static File writeTemp(byte[] bytes, String suffix) throws Exception {
        File f = File.createTempFile("word-import-test", suffix);
        f.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(bytes);
        }
        return f;
    }

    private static byte[] buildDocx() throws Exception {
        XWPFDocument doc = new XWPFDocument();

        XWPFParagraph title = doc.createParagraph();
        title.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun titleRun = title.createRun();
        titleRun.setBold(true);
        titleRun.setFontSize(20);
        titleRun.setText("季度报告 Quarterly Report");

        XWPFParagraph body = doc.createParagraph();
        XWPFRun bodyRun = body.createRun();
        bodyRun.setFontSize(11);
        bodyRun.setColor("1F4E79");
        bodyRun.setText("This is the first body paragraph with some plain text content.");
        XWPFRun breakRun = body.createRun();
        breakRun.addBreak(BreakType.PAGE);
        XWPFRun afterBreak = body.createRun();
        afterBreak.setText("Text after an explicit page break.");

        XWPFTable table = doc.createTable(2, 2);
        XWPFTableRow row0 = table.getRow(0);
        setCellText(row0.getCell(0), "Name");
        setCellText(row0.getCell(1), "Score");
        XWPFTableRow row1 = table.getRow(1);
        setCellText(row1.getCell(0), "Alice");
        setCellText(row1.getCell(1), "95");

        XWPFParagraph picPara = doc.createParagraph();
        XWPFRun picRun = picPara.createRun();
        picRun.addPicture(new ByteArrayInputStream(PNG_1PX),
                XWPFDocument.PICTURE_TYPE_PNG, "pixel.png", 96 * 9525, 96 * 9525); // 1in EMU

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        doc.write(bos);
        doc.close();
        return bos.toByteArray();
    }

    private static void setCellText(XWPFTableCell cell, String text) {
        XWPFParagraph p = cell.getParagraphs().get(0);
        p.createRun().setText(text);
    }

    @Test
    public void docxProducesPagesTextTableAndImage() throws Exception {
        File docx = writeTemp(buildDocx(), ".docx");
        PoiOfficeEngine engine = new PoiOfficeEngine();
        OfficeImportResult result = engine.importDocument(docx, OfficeType.WORD,
                ImportOptions.defaults(), ProgressCallback.NONE);

        // Explicit page break -> at least 2 pages.
        assertTrue("pages >= 2, got " + result.getPages().size(),
                result.getPages().size() >= 2);
        for (PageLayout page : result.getPages()) {
            assertEquals(WordPageLayouter.PAGE_WIDTH_PX, page.getWidthPx());
            assertEquals(WordPageLayouter.PAGE_HEIGHT_PX, page.getHeightPx());
        }

        String allText = allText(result.getPages());
        assertTrue(allText.contains("Quarterly Report"));
        assertTrue(allText.contains("first body paragraph"));
        assertTrue(allText.contains("after an explicit page break"));
        assertTrue(allText.contains("Alice"));

        // Table borders become rect commands.
        boolean sawRect = false;
        boolean sawBoldTitle = false;
        for (PageLayout page : result.getPages()) {
            for (DrawCommand cmd : page.getCommands()) {
                if (cmd instanceof DrawCommand.Rect) {
                    sawRect = true;
                }
                if (cmd instanceof DrawCommand.Text) {
                    DrawCommand.Text t = (DrawCommand.Text) cmd;
                    if (t.bold && t.text.contains("Quarterly")) {
                        sawBoldTitle = true;
                    }
                }
            }
        }
        assertTrue("table borders drawn", sawRect);
        assertTrue("bold title style preserved", sawBoldTitle);

        // Embedded image extracted into the resource table.
        assertEquals(1, result.getImages().size());
        ImageResource img = result.getImages().get(0);
        assertEquals("image/png", img.getMimeType());
        assertEquals(PNG_1PX.length, img.getData().length);
        boolean sawImageCmd = false;
        for (PageLayout page : result.getPages()) {
            for (DrawCommand cmd : page.getCommands()) {
                if (cmd instanceof DrawCommand.Image
                        && ((DrawCommand.Image) cmd).resourceId.equals(img.getId())) {
                    sawImageCmd = true;
                }
            }
        }
        assertTrue("image placed via draw command", sawImageCmd);
    }

    @Test
    public void cancellationAbortsImport() throws Exception {
        File docx = writeTemp(buildDocx(), ".docx");
        ProgressCallback cancelImmediately = new ProgressCallback() {
            @Override
            public void onProgress(int percent, String stage) {
            }

            @Override
            public boolean isCancelled() {
                return true;
            }
        };
        try {
            new PoiOfficeEngine().importDocument(docx, OfficeType.WORD,
                    ImportOptions.defaults(), cancelImmediately);
            fail("expected cancellation");
        } catch (OfficeImportException e) {
            assertTrue(e.isCancelled());
        }
    }

    @Test
    public void corruptFileFailsWithClearError() throws Exception {
        File garbage = writeTemp("not an office file at all".getBytes("UTF-8"), ".docx");
        try {
            new PoiOfficeEngine().importDocument(garbage, OfficeType.WORD,
                    ImportOptions.defaults(), ProgressCallback.NONE);
            fail("expected failure for corrupt file");
        } catch (OfficeImportException e) {
            assertFalse(e.isCancelled());
            assertNotNull(e.getMessage());
        }
    }

    static String allText(List<PageLayout> pages) {
        StringBuilder sb = new StringBuilder();
        for (PageLayout p : pages) {
            sb.append(p.getTextLayer()).append('\n');
        }
        return sb.toString();
    }
}
