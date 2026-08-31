package com.leslie.colorosnote.feature.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.leslie.colorosnote.core.model.NoteDocument;
import com.leslie.colorosnote.core.model.Page;
import com.leslie.colorosnote.core.model.PageObject;
import com.leslie.colorosnote.core.model.ShapeObject;
import com.leslie.colorosnote.core.model.StickerObject;
import com.leslie.colorosnote.core.model.Stroke;

import org.junit.Test;

import java.util.List;

public class PageRenderFilterTest {

    private static Stroke stroke(Stroke.Layer layer) {
        Stroke s = new Stroke();
        s.layer = layer;
        return s;
    }

    private static Page pageWithContent() {
        Page page = new Page();
        page.strokes.add(stroke(Stroke.Layer.INK));
        page.strokes.add(stroke(Stroke.Layer.HIGHLIGHT));
        page.strokes.add(stroke(Stroke.Layer.INK));
        page.objects.add(new ShapeObject());
        page.objects.add(new StickerObject());
        return page;
    }

    @Test
    public void allContentIncludedByDefault() {
        ExportOptions options = new ExportOptions();
        Page page = pageWithContent();
        assertEquals(3, PageRenderFilter.visibleStrokes(page, options).size());
        assertEquals(2, PageRenderFilter.visibleObjects(page, options).size());
    }

    @Test
    public void inkToggleDropsInkButKeepsHighlights() {
        ExportOptions options = new ExportOptions();
        options.includeInk = false;
        List<Stroke> visible = PageRenderFilter.visibleStrokes(pageWithContent(), options);
        assertEquals(1, visible.size());
        assertEquals(Stroke.Layer.HIGHLIGHT, visible.get(0).layer);
    }

    @Test
    public void highlightToggleDropsHighlights() {
        ExportOptions options = new ExportOptions();
        options.includeHighlights = false;
        List<Stroke> visible = PageRenderFilter.visibleStrokes(pageWithContent(), options);
        assertEquals(2, visible.size());
        for (Stroke s : visible) {
            assertEquals(Stroke.Layer.INK, s.layer);
        }
    }

    @Test
    public void stickerToggleDropsStickersOnly() {
        ExportOptions options = new ExportOptions();
        options.includeStickers = false;
        List<PageObject> visible = PageRenderFilter.visibleObjects(pageWithContent(), options);
        assertEquals(1, visible.size());
        assertEquals(PageObject.ObjectType.SHAPE, visible.get(0).objectType);
    }

    @Test
    public void pixelSizeScalesWithDpi() {
        Page page = new Page(); // 595x842 pt
        assertEquals(595, PageRenderFilter.pixelWidth(page, 72));
        assertEquals(842, PageRenderFilter.pixelHeight(page, 72));
        assertEquals(1190, PageRenderFilter.pixelWidth(page, 144));
        assertEquals(1684, PageRenderFilter.pixelHeight(page, 144));
    }

    @Test
    public void pageRangeSelectsSubset() {
        NoteDocument doc = new NoteDocument();
        for (int i = 0; i < 5; i++) {
            doc.pages.add(new Page());
        }
        ExportOptions options = new ExportOptions();
        options.fromPage = 1;
        options.toPage = 3;
        assertEquals(3, PageRenderFilter.pagesInRange(doc, options).size());

        ExportOptions all = new ExportOptions();
        assertEquals(5, PageRenderFilter.pagesInRange(doc, all).size());

        ExportOptions clamped = new ExportOptions();
        clamped.fromPage = -2;
        clamped.toPage = 99;
        assertEquals(5, PageRenderFilter.pagesInRange(doc, clamped).size());

        ExportOptions empty = new ExportOptions();
        empty.fromPage = 4;
        empty.toPage = 1;
        assertTrue(PageRenderFilter.pagesInRange(doc, empty).isEmpty());
    }
}
