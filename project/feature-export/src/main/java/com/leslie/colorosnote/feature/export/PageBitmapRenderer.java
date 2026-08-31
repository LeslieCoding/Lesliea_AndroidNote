package com.leslie.colorosnote.feature.export;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;

import com.leslie.colorosnote.core.model.Page;
import com.leslie.colorosnote.core.render.PageRenderer;

/**
 * Renders a {@link Page} to a Bitmap at the requested dpi for PDF/PNG/JPEG
 * export (spec 37). Rendering reuses {@link PageRenderer} so export output
 * matches the on-screen editor exactly.
 */
public final class PageBitmapRenderer {

    private PageBitmapRenderer() {
    }

    /**
     * Renders one page to a new ARGB_8888 bitmap. The caller owns recycling.
     *
     * @param bitmapProvider optional lookup for IMAGE/CUSTOM background bitmaps
     */
    public static Bitmap render(Page page, ExportOptions options,
                                PageRenderer.ResourceBitmapProvider bitmapProvider) {
        int dpi = options.clampDpi();
        int w = PageRenderFilter.pixelWidth(page, dpi);
        int h = PageRenderFilter.pixelHeight(page, dpi);
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        renderInto(new Canvas(bitmap), page, options, bitmapProvider);
        return bitmap;
    }

    /** Draws a page into an already-sized canvas (used by PdfExporter). */
    public static void renderInto(Canvas canvas, Page page, ExportOptions options,
                                  PageRenderer.ResourceBitmapProvider bitmapProvider) {
        int dpi = options.clampDpi();
        float scale = dpi / 72f;
        canvas.save();
        canvas.scale(scale, scale);
        // JPEG and PDF pages have no alpha — always lay down white first.
        canvas.drawColor(Color.WHITE);
        PageRenderer renderer = new PageRenderer();
        renderer.setResourceBitmapProvider(bitmapProvider);
        if (options.includeBackground) {
            renderer.drawBackground(canvas, page);
        }
        renderer.drawStrokes(canvas, PageRenderFilter.visibleStrokes(page, options));
        renderer.drawObjects(canvas, PageRenderFilter.visibleObjects(page, options));
        canvas.restore();
    }
}
