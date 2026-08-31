package com.leslie.colorosnote.document.office;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Round-trip tests for {@link ImportedDocumentJson} (pure JVM). */
public class ImportedDocumentJsonTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private static ImportedDocument sampleDocument() {
        List<DrawCommand> page1 = new ArrayList<>();
        page1.add(new DrawCommand.Text(48f, 96.5f, "标题 Heading", 28f,
                0xFF112233, true, false, TextAlign.LEFT));
        page1.add(new DrawCommand.Text(400f, 130f, "居中", 16f,
                0xFF445566, false, true, TextAlign.CENTER));
        page1.add(new DrawCommand.Rect(48f, 150f, 200f, 80f, 0xFF0000FF, false, 1.5f));
        page1.add(new DrawCommand.Rect(48f, 240f, 200f, 80f, 0xFF00FF00, true, 0f));
        page1.add(new DrawCommand.Line(48f, 340f, 600f, 340f, 0xFF888888, 0.75f));
        page1.add(new DrawCommand.Image(48f, 360f, 320f, 240f, "img-001.png"));

        List<DrawCommand> page2 = new ArrayList<>();
        page2.add(new DrawCommand.Text(700f, 96f, "右对齐", 14f,
                0xFF000000, false, false, TextAlign.RIGHT));

        List<ImportedPage> pages = new ArrayList<>();
        pages.add(new ImportedPage(1, "第一页", 816, 1056, page1, "标题 Heading 居中"));
        pages.add(new ImportedPage(2, null, 816, 1056, page2, ""));

        Map<String, File> resources = new LinkedHashMap<>();
        resources.put("img-001.png", new File("sandbox/img-001.png"));
        return new ImportedDocument("报告.docx", OfficeType.WORD, "poi", pages,
                Arrays.asList("第 3 页表格已简化", "忽略 1 个浮动文本框"),
                new File("sandbox"), resources);
    }

    @Test
    public void roundTripPreservesEverything() {
        ImportedDocument source = sampleDocument();
        File dir = new File(tmp.getRoot(), "resources");

        ImportedDocument restored =
                ImportedDocumentJson.fromBytes(ImportedDocumentJson.toBytes(source), dir);

        assertEquals("报告.docx", restored.getSourceFileName());
        assertEquals(OfficeType.WORD, restored.getType());
        assertEquals("poi", restored.getEngineId());
        assertEquals(Arrays.asList("第 3 页表格已简化", "忽略 1 个浮动文本框"),
                restored.getWarnings());
        assertEquals(dir, restored.getResourceDir());

        // Resource ids survive and resolve inside the caller-supplied dir.
        assertEquals(1, restored.getResourceFiles().size());
        assertEquals(new File(dir, "img-001.png"), restored.resourceFile("img-001.png"));
        assertNull(restored.resourceFile("img-999.png"));

        assertEquals(2, restored.getPages().size());

        ImportedPage p1 = restored.getPages().get(0);
        assertEquals(1, p1.getPageNumber());
        assertEquals("第一页", p1.getName());
        assertEquals(816, p1.getWidthPx());
        assertEquals(1056, p1.getHeightPx());
        assertEquals("标题 Heading 居中", p1.getTextLayer());
        assertEquals(6, p1.getCommands().size());

        DrawCommand.Text t = (DrawCommand.Text) p1.getCommands().get(0);
        assertEquals(48f, t.x, 1e-6f);
        assertEquals(96.5f, t.y, 1e-6f);
        assertEquals("标题 Heading", t.text);
        assertEquals(28f, t.textSizePx, 1e-6f);
        assertEquals(0xFF112233, t.colorArgb);
        assertTrue(t.bold);
        assertEquals(TextAlign.LEFT, t.align);

        DrawCommand.Text t2 = (DrawCommand.Text) p1.getCommands().get(1);
        assertEquals(TextAlign.CENTER, t2.align);
        assertTrue(t2.italic);

        DrawCommand.Rect strokeRect = (DrawCommand.Rect) p1.getCommands().get(2);
        assertEquals(200f, strokeRect.width, 1e-6f);
        assertEquals(80f, strokeRect.height, 1e-6f);
        assertEquals(0xFF0000FF, strokeRect.colorArgb);
        assertEquals(false, strokeRect.fill);
        assertEquals(1.5f, strokeRect.strokeWidthPx, 1e-6f);

        DrawCommand.Rect fillRect = (DrawCommand.Rect) p1.getCommands().get(3);
        assertEquals(true, fillRect.fill);

        DrawCommand.Line line = (DrawCommand.Line) p1.getCommands().get(4);
        assertEquals(48f, line.x1, 1e-6f);
        assertEquals(340f, line.y1, 1e-6f);
        assertEquals(600f, line.x2, 1e-6f);
        assertEquals(340f, line.y2, 1e-6f);
        assertEquals(0.75f, line.strokeWidthPx, 1e-6f);

        DrawCommand.Image image = (DrawCommand.Image) p1.getCommands().get(5);
        assertEquals("img-001.png", image.resourceId);
        assertEquals(320f, image.width, 1e-6f);
        assertEquals(240f, image.height, 1e-6f);

        // Null page name comes back as null (no page titles in Word imports).
        assertNull(restored.getPages().get(1).getName());
    }

    @Test
    public void roundTripEmptyDocument() {
        ImportedDocument empty = new ImportedDocument("empty.pptx", OfficeType.SLIDES,
                "poi", Collections.<ImportedPage>emptyList(),
                Collections.<String>emptyList(), new File("unused"),
                Collections.<String, File>emptyMap());

        ImportedDocument restored = ImportedDocumentJson.fromJson(
                ImportedDocumentJson.toJson(empty), tmp.getRoot());

        assertEquals(OfficeType.SLIDES, restored.getType());
        assertTrue(restored.getPages().isEmpty());
        assertTrue(restored.getWarnings().isEmpty());
        assertTrue(restored.getResourceFiles().isEmpty());
    }

    @Test
    public void unknownCommandKindIsSkippedNotFatal() {
        String json = ImportedDocumentJson.toJson(sampleDocument())
                .replace("\"kind\":\"line\"", "\"kind\":\"bezierCurve\"");
        ImportedDocument restored =
                ImportedDocumentJson.fromJson(json, tmp.getRoot());
        // The line command is dropped; the other five survive.
        assertEquals(5, restored.getPages().get(0).getCommands().size());
    }

    @Test
    public void unknownTypeFallsBackToNull() {
        String json = ImportedDocumentJson.toJson(sampleDocument())
                .replace("\"type\":\"WORD\"", "\"type\":\"FUTURE_FORMAT\"");
        ImportedDocument restored =
                ImportedDocumentJson.fromJson(json, tmp.getRoot());
        assertNull(restored.getType());
        assertEquals(2, restored.getPages().size());
    }

    @Test
    public void malformedPayloadThrows() {
        try {
            ImportedDocumentJson.fromJson("{\"version\":1}", tmp.getRoot());
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertNotNull(expected);
        }
    }
}
