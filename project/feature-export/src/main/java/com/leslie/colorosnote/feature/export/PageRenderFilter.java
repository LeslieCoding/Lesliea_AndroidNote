package com.leslie.colorosnote.feature.export;

import com.leslie.colorosnote.core.model.Page;
import com.leslie.colorosnote.core.model.PageObject;
import com.leslie.colorosnote.core.model.Stroke;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure content-filtering logic for export (spec 37): decides which strokes and
 * objects of a page participate in an export run, driven by
 * {@link ExportOptions}. Kept free of android.graphics so it is JVM-testable.
 */
public final class PageRenderFilter {

    private PageRenderFilter() {
    }

    /** Strokes to draw, honoring includeInk / includeHighlights. */
    public static List<Stroke> visibleStrokes(Page page, ExportOptions options) {
        List<Stroke> out = new ArrayList<>();
        for (Stroke s : page.strokes) {
            if (s.layer == Stroke.Layer.HIGHLIGHT) {
                if (options.includeHighlights) {
                    out.add(s);
                }
            } else if (options.includeInk) {
                out.add(s);
            }
        }
        return out;
    }

    /** Objects to draw, honoring includeStickers. */
    public static List<PageObject> visibleObjects(Page page, ExportOptions options) {
        if (options.includeStickers) {
            return new ArrayList<>(page.objects);
        }
        List<PageObject> out = new ArrayList<>();
        for (PageObject o : page.objects) {
            if (o.objectType != PageObject.ObjectType.STICKER) {
                out.add(o);
            }
        }
        return out;
    }

    /** Pixel width of a page rendered at the given dpi (page units are 1/72"). */
    public static int pixelWidth(Page page, int dpi) {
        return Math.max(1, Math.round(page.width * dpi / 72f));
    }

    /** Pixel height of a page rendered at the given dpi. */
    public static int pixelHeight(Page page, int dpi) {
        return Math.max(1, Math.round(page.height * dpi / 72f));
    }

    /** Pages selected by the export page range. */
    public static List<Page> pagesInRange(com.leslie.colorosnote.core.model.NoteDocument doc,
                                          ExportOptions options) {
        List<Page> out = new ArrayList<>();
        int count = doc.pages.size();
        for (int i = 0; i < count; i++) {
            if (options.isPageIncluded(i, count)) {
                out.add(doc.pages.get(i));
            }
        }
        return out;
    }
}
