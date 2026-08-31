package com.leslie.colorosnote.document.office;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Pure-JVM tests for the Android-safe .pptx parser: builds a minimal OOXML
 * package by hand (zip + presentation.xml + slides + rels + one PNG) and
 * verifies page size, text extraction, EMU->px coordinate math and the image
 * resource table. Also covers the explicit legacy-.ppt rejection path.
 */
public class PptxImportTest {

    private static final String NS_P = "http://schemas.openxmlformats.org/presentationml/2006/main";
    private static final String NS_A = "http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final String NS_R =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    private static final String PRESENTATION_XML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<p:presentation xmlns:p=\"" + NS_P + "\" xmlns:a=\"" + NS_A + "\">"
                    + "<p:sldSz cx=\"9144000\" cy=\"6858000\"/>" // 10in x 7.5in
                    + "</p:presentation>";

    private static final String SLIDE1_XML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<p:sld xmlns:p=\"" + NS_P + "\" xmlns:a=\"" + NS_A + "\" xmlns:r=\"" + NS_R + "\">"
                    + "<p:cSld><p:spTree>"
                    // Text shape: off(457200,457200)=0.5in -> 48px, ext 3657600x914400 -> 384x96px,
                    // centered run "Hello OOXML", 28pt bold red.
                    + "<p:sp>"
                    + "<p:nvSpPr><p:cNvPr id=\"2\" name=\"Title\"/></p:nvSpPr>"
                    + "<p:spPr><a:xfrm><a:off x=\"457200\" y=\"457200\"/>"
                    + "<a:ext cx=\"3657600\" cy=\"914400\"/></a:xfrm></p:spPr>"
                    + "<p:txBody>"
                    + "<a:p><a:pPr algn=\"ctr\"/>"
                    + "<a:r><a:rPr lang=\"en-US\" sz=\"2800\" b=\"1\">"
                    + "<a:solidFill><a:srgbClr val=\"FF0000\"/></a:solidFill></a:rPr>"
                    + "<a:t>Hello OOXML</a:t></a:r>"
                    + "</a:p>"
                    + "</p:txBody>"
                    + "</p:sp>"
                    // Picture at 1in,2in (96px,192px), size 1in x 1in (96x96px), rId2 -> media.
                    + "<p:pic>"
                    + "<p:nvPicPr><p:cNvPr id=\"3\" name=\"Picture\"/></p:nvPicPr>"
                    + "<p:blipFill><a:blip r:embed=\"rId2\"/></p:blipFill>"
                    + "<p:spPr><a:xfrm><a:off x=\"914400\" y=\"1828800\"/>"
                    + "<a:ext cx=\"914400\" cy=\"914400\"/></a:xfrm></p:spPr>"
                    + "</p:pic>"
                    // Blue filled rectangle shape, no text.
                    + "<p:sp>"
                    + "<p:nvSpPr><p:cNvPr id=\"4\" name=\"Rect\"/></p:nvSpPr>"
                    + "<p:spPr><a:xfrm><a:off x=\"0\" y=\"4572000\"/>"
                    + "<a:ext cx=\"914400\" cy=\"457200\"/></a:xfrm>"
                    + "<a:solidFill><a:srgbClr val=\"0000FF\"/></a:solidFill></p:spPr>"
                    + "<p:txBody><a:p/></p:txBody>"
                    + "</p:sp>"
                    + "</p:spTree></p:cSld></p:sld>";

    private static final String SLIDE2_XML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<p:sld xmlns:p=\"" + NS_P + "\" xmlns:a=\"" + NS_A + "\" xmlns:r=\"" + NS_R + "\">"
                    + "<p:cSld><p:spTree>"
                    + "<p:sp>"
                    + "<p:nvSpPr><p:cNvPr id=\"2\" name=\"Body\"/></p:nvSpPr>"
                    + "<p:spPr><a:xfrm><a:off x=\"914400\" y=\"914400\"/>"
                    + "<a:ext cx=\"4572000\" cy=\"914400\"/></a:xfrm></p:spPr>"
                    + "<p:txBody><a:p><a:r><a:rPr sz=\"1800\"/><a:t>第二张幻灯片</a:t></a:r></a:p></p:txBody>"
                    + "</p:sp>"
                    + "</p:spTree></p:cSld></p:sld>";

    private static final String SLIDE1_RELS =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId2\" "
                    + "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" "
                    + "Target=\"../media/image1.png\"/>"
                    + "</Relationships>";

    private static File writeTemp(byte[] bytes, String suffix) throws Exception {
        File f = File.createTempFile("pptx-import-test", suffix);
        f.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(bytes);
        }
        return f;
    }

    private static byte[] buildPptx() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos)) {
            put(zip, "ppt/presentation.xml", PRESENTATION_XML.getBytes(StandardCharsets.UTF_8));
            put(zip, "ppt/slides/slide1.xml", SLIDE1_XML.getBytes(StandardCharsets.UTF_8));
            put(zip, "ppt/slides/slide2.xml", SLIDE2_XML.getBytes(StandardCharsets.UTF_8));
            put(zip, "ppt/slides/_rels/slide1.xml.rels", SLIDE1_RELS.getBytes(StandardCharsets.UTF_8));
            put(zip, "ppt/media/image1.png", WordImportTest.PNG_1PX);
        }
        return bos.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    @Test
    public void pptxParsesSlidesTextPositionsAndImages() throws Exception {
        File pptx = writeTemp(buildPptx(), ".pptx");
        OfficeImportResult result = new PoiOfficeEngine().importDocument(
                pptx, OfficeType.SLIDES, ImportOptions.defaults(), ProgressCallback.NONE);

        List<PageLayout> pages = result.getPages();
        assertEquals(2, pages.size());

        // Slide size 10in x 7.5in -> 960x720 px @96dpi; aspect ratio preserved.
        PageLayout page1 = pages.get(0);
        assertEquals(960, page1.getWidthPx());
        assertEquals(720, page1.getHeightPx());
        assertEquals("Slide 1", page1.getName());
        assertEquals("Slide 2", pages.get(1).getName());

        // Text layer: both slides' text extracted.
        assertTrue(page1.getTextLayer().contains("Hello OOXML"));
        assertTrue(pages.get(1).getTextLayer().contains("第二张幻灯片"));

        // Run style: 28pt -> 37.33px, bold, srgbClr FF0000.
        DrawCommand.Text title = null;
        DrawCommand.Rect blueRect = null;
        DrawCommand.Image image = null;
        for (DrawCommand cmd : page1.getCommands()) {
            if (cmd instanceof DrawCommand.Text && ((DrawCommand.Text) cmd).text.contains("Hello")) {
                title = (DrawCommand.Text) cmd;
            } else if (cmd instanceof DrawCommand.Rect) {
                blueRect = (DrawCommand.Rect) cmd;
            } else if (cmd instanceof DrawCommand.Image) {
                image = (DrawCommand.Image) cmd;
            }
        }
        assertNotNull("title text command", title);
        assertTrue(title.bold);
        assertEquals(0xFFFF0000, title.colorArgb);
        assertEquals(TextMetrics.ptToPx(28f), title.textSizePx, 0.01f);
        assertEquals(TextAlign.CENTER, title.align);

        // EMU -> px math: picture at (914400, 1828800) EMU -> (96, 192) px, 96x96 px.
        assertNotNull("image command", image);
        assertEquals(96f, image.x, 0.5f);
        assertEquals(192f, image.y, 0.5f);
        assertEquals(96f, image.width, 0.5f);
        assertEquals(96f, image.height, 0.5f);

        // Blue solidFill shape -> filled rect at y=480px (4572000 EMU = 5in).
        assertNotNull("blue rect", blueRect);
        assertEquals(0xFF0000FF, blueRect.colorArgb);
        assertTrue(blueRect.fill);
        assertEquals(480f, blueRect.y, 0.5f);
        assertEquals(96f, blueRect.width, 0.5f);

        // Media bytes landed in the resource table, referenced by id.
        assertEquals(1, result.getImages().size());
        ImageResource res = result.getImages().get(0);
        assertEquals(image.resourceId, res.getId());
        assertEquals("image/png", res.getMimeType());
        assertEquals(WordImportTest.PNG_1PX.length, res.getData().length);
    }

    @Test
    public void legacyPptIsRejectedWithUnsupported() throws Exception {
        // OLE2 compound-file magic followed by padding — no real content needed,
        // the container sniff alone must trigger the explicit rejection.
        byte[] ole2 = new byte[512];
        ole2[0] = (byte) 0xD0;
        ole2[1] = (byte) 0xCF;
        ole2[2] = 0x11;
        ole2[3] = (byte) 0xE0;
        File ppt = writeTemp(ole2, ".ppt");
        try {
            new PoiOfficeEngine().importDocument(ppt, OfficeType.SLIDES,
                    ImportOptions.defaults(), ProgressCallback.NONE);
            fail("expected UNSUPPORTED rejection for legacy .ppt");
        } catch (OfficeImportException e) {
            assertTrue(e.isUnsupported());
            assertTrue(e.getMessage().contains(".pptx"));
        }
    }

    @Test
    public void corruptPptxFailsWithClearError() throws Exception {
        File garbage = writeTemp("PK not really a zip".getBytes(StandardCharsets.UTF_8), ".pptx");
        try {
            new PoiOfficeEngine().importDocument(garbage, OfficeType.SLIDES,
                    ImportOptions.defaults(), ProgressCallback.NONE);
            fail("expected failure for corrupt pptx");
        } catch (OfficeImportException | java.io.IOException expected) {
            assertNotNull(expected.getMessage());
        }
    }
}
