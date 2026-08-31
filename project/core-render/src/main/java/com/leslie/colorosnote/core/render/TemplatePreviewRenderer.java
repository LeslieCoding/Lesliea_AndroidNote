package com.leslie.colorosnote.core.render;

import android.graphics.Bitmap;
import android.graphics.Canvas;

import com.leslie.colorosnote.core.model.Page;
import com.leslie.colorosnote.core.model.PageBackground;

/**
 * Renders a small offscreen preview of a background template for the cards in
 * {@link TemplatePickerDialog}. The preview goes through the exact same
 * {@link PageRenderer#drawBackground} pipeline as the real page: a temporary
 * page at a fixed logical size is drawn into the bitmap with a uniform scale,
 * so what the card shows is what the page will look like.
 */
public final class TemplatePreviewRenderer {

    /** Logical page size used for previews (same A4 portrait ratio as pages). */
    private static final float LOGICAL_WIDTH = 156f;
    private static final float LOGICAL_HEIGHT = 208f;

    private TemplatePreviewRenderer() {
    }

    /** Draws the template into a new ARGB bitmap of the requested pixel size. */
    public static Bitmap render(PageBackground.Type type, int widthPx, int heightPx) {
        Page page = new Page();
        page.width = LOGICAL_WIDTH;
        page.height = LOGICAL_HEIGHT;
        page.background = PageBackground.ofType(type);
        Bitmap bitmap = Bitmap.createBitmap(Math.max(1, widthPx), Math.max(1, heightPx),
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.scale(widthPx / LOGICAL_WIDTH, heightPx / LOGICAL_HEIGHT);
        new PageRenderer().drawBackground(canvas, page);
        return bitmap;
    }
}
