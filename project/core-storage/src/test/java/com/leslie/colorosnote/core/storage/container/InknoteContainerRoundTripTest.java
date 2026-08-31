package com.leslie.colorosnote.core.storage.container;

import com.leslie.colorosnote.core.model.BrushPreset;
import com.leslie.colorosnote.core.model.ImageObject;
import com.leslie.colorosnote.core.model.InknoteContainer;
import com.leslie.colorosnote.core.model.NoteDocument;
import com.leslie.colorosnote.core.model.Page;
import com.leslie.colorosnote.core.model.ShapeObject;
import com.leslie.colorosnote.core.model.Stroke;
import com.leslie.colorosnote.core.model.StrokePoint;
import com.leslie.colorosnote.core.model.TapeObject;
import com.leslie.colorosnote.core.model.TextBoxObject;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Round-trip tests for {@link InknoteWriter} / {@link InknoteReader} (pure JVM). */
public class InknoteContainerRoundTripTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    static NoteDocument sampleDocument() {
        NoteDocument doc = new NoteDocument();
        doc.title = "Meeting notes";
        doc.tags.add("tag-1");
        doc.createdAt = 1_700_000_000_000L;
        doc.updatedAt = 1_700_000_100_000L;

        Page p1 = new Page();
        p1.index = 0;
        p1.title = "first";
        Stroke stroke = new Stroke();
        stroke.brushPreset = BrushPreset.fountainPen();
        stroke.color = 0xFF112233;
        stroke.points.add(new StrokePoint(10f, 20f, 1000L, 0.5f));
        stroke.points.add(new StrokePoint(30.5f, 40.25f, 1016L, 0.75f));
        p1.strokes.add(stroke);
        TextBoxObject text = new TextBoxObject();
        text.text = "hello 你好";
        text.fontSize = 18f;
        p1.objects.add(text);
        doc.pages.add(p1);

        Page p2 = new Page();
        p2.index = 1;
        p2.bookmarked = true;
        ImageObject image = new ImageObject();
        image.resourceId = "res-42";
        p2.objects.add(image);
        ShapeObject shape = new ShapeObject();
        shape.shapeType = ShapeObject.ShapeType.ARROW;
        shape.points.add(0f);
        shape.points.add(0f);
        shape.points.add(50f);
        shape.points.add(80f);
        p2.objects.add(shape);
        TapeObject tape = new TapeObject();
        tape.rect = new float[]{1f, 2f, 100f, 40f};
        p2.objects.add(tape);
        doc.pages.add(p2);
        return doc;
    }

    @Test
    public void fullRoundTrip() throws Exception {
        NoteDocument original = sampleDocument();
        File file = tmp.newFile("doc" + InknoteContainer.FILE_EXTENSION);
        byte[] resource = "fake-png-bytes".getBytes(StandardCharsets.UTF_8);
        new InknoteWriter().write(file, original,
                Collections.singletonMap(InknoteContainer.resourcePath("res-42"), resource));

        // Container layout: manifest + document + per-page json/bin + resource.
        Set<String> entries = zipEntryNames(file);
        assertTrue(entries.contains(InknoteContainer.MANIFEST));
        assertTrue(entries.contains(InknoteContainer.DOCUMENT));
        for (Page page : original.pages) {
            assertTrue(entries.contains(InknoteContainer.pagePath(page.id)));
        }
        assertTrue(entries.contains(InknoteContainer.strokesPath(original.pages.get(0).id)));
        assertTrue(entries.contains(InknoteContainer.resourcePath("res-42")));

        InknoteReader reader = new InknoteReader();
        NoteDocument restored = reader.read(file);
        assertEquals(original.id, restored.id);
        assertEquals("Meeting notes", restored.title);
        assertEquals(InknoteContainer.SCHEMA_VERSION, restored.schemaVersion);
        assertEquals(2, restored.pages.size());

        Page rp1 = restored.pages.get(0);
        assertEquals(1, rp1.strokes.size());
        Stroke rs = rp1.strokes.get(0);
        assertEquals(original.pages.get(0).strokes.get(0).id, rs.id);
        assertEquals(2, rs.points.size());
        assertEquals(30.5f, rs.points.get(1).x, 0f);
        assertEquals(1016L, rs.points.get(1).timestampMs);
        assertEquals(0.75f, rs.points.get(1).pressure, 0f);
        assertNotNull(rs.brushPreset);
        assertEquals(1, rp1.objects.size());
        assertTrue(rp1.objects.get(0) instanceof TextBoxObject);
        assertEquals("hello 你好", ((TextBoxObject) rp1.objects.get(0)).text);

        Page rp2 = restored.pages.get(1);
        assertEquals(3, rp2.objects.size());
        assertTrue(rp2.objects.get(0) instanceof ImageObject);
        assertEquals("res-42", ((ImageObject) rp2.objects.get(0)).resourceId);
        assertTrue(rp2.objects.get(1) instanceof ShapeObject);
        assertEquals(ShapeObject.ShapeType.ARROW, ((ShapeObject) rp2.objects.get(1)).shapeType);
        assertTrue(rp2.objects.get(2) instanceof TapeObject);
        assertTrue(rp2.bookmarked);

        assertArrayEquals(resource, reader.readEntry(file, InknoteContainer.resourcePath("res-42")));
    }

    @Test
    public void documentJsonHasNoInlinePages() throws Exception {
        NoteDocument doc = sampleDocument();
        File file = tmp.newFile("doc2.inknote");
        new InknoteWriter().write(file, doc);
        byte[] docJson = new InknoteReader().readEntry(file, InknoteContainer.DOCUMENT);
        String json = new String(docJson, StandardCharsets.UTF_8);
        assertTrue(json.contains("pageIds"));
        // Page payloads must not be duplicated inline in document.json.
        assertTrue(!json.contains("\"strokes\""));
    }

    static Set<String> zipEntryNames(File file) throws Exception {
        Set<String> names = new HashSet<>();
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(file))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                names.add(e.getName());
            }
        }
        return names;
    }
}
