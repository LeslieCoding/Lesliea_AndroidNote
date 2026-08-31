package com.leslie.colorosnote.core.render;

import android.graphics.Canvas;

import com.leslie.colorosnote.core.model.PageObject;

/**
 * SPI for painting object-layer items (image / text box / shape / sticker /
 * tape). Feature modules register painters on {@link PageRenderer}; the
 * renderer calls them in page coordinates with the object transform already
 * applied to the canvas.
 */
public interface ObjectPainter {

    /** True when this painter can draw the given object. */
    boolean supports(PageObject object);

    /** Draws the object in its local (pre-transform) coordinate space. */
    void paint(Canvas canvas, PageObject object);
}
