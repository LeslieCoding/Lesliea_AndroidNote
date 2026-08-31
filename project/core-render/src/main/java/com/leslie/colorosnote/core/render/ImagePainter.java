package com.leslie.colorosnote.core.render;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import com.leslie.colorosnote.core.model.ImageObject;
import com.leslie.colorosnote.core.model.PageObject;

/**
 * Default painter for {@link ImageObject} (spec 18.1): draws the resource
 * bitmap scaled into the object's local bounds honoring {@code opacity}.
 * Bitmaps come from a {@link PageRenderer.ResourceBitmapProvider}; when the
 * provider is missing or returns null (e.g. resource not loaded yet) a
 * placeholder box with a diagonal cross is drawn instead of failing.
 */
public final class ImagePainter implements ObjectPainter {

    private static final int PLACEHOLDER_FILL = 0xFFECEFF1;
    private static final int PLACEHOLDER_LINE = 0xFF90A4AE;

    private final PageRenderer.ResourceBitmapProvider provider;
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();

    /** @param provider bitmap lookup by resource id; may be null. */
    public ImagePainter(PageRenderer.ResourceBitmapProvider provider) {
        this.provider = provider;
    }

    @Override
    public boolean supports(PageObject object) {
        return object instanceof ImageObject;
    }

    @Override
    public void paint(Canvas canvas, PageObject object) {
        ImageObject image = (ImageObject) object;
        float l = image.bounds[0];
        float t = image.bounds[1];
        float r = image.bounds[2];
        float b = image.bounds[3];
        dst.set(l, t, r, b);

        Bitmap bitmap = provider != null && image.resourceId != null
                ? provider.getBitmap(image.resourceId) : null;
        if (bitmap != null && !bitmap.isRecycled()) {
            int alpha = Math.round(255f * Math.max(0f, Math.min(1f, image.opacity)));
            bitmapPaint.setAlpha(alpha);
            canvas.drawBitmap(bitmap, null, dst, bitmapPaint);
            return;
        }
        drawPlaceholder(canvas, l, t, r, b);
    }

    private void drawPlaceholder(Canvas canvas, float l, float t, float r, float b) {
        boxPaint.setStyle(Paint.Style.FILL);
        boxPaint.setColor(PLACEHOLDER_FILL);
        canvas.drawRect(l, t, r, b, boxPaint);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(1.5f);
        boxPaint.setColor(PLACEHOLDER_LINE);
        canvas.drawRect(l, t, r, b, boxPaint);
        canvas.drawLine(l, t, r, b, boxPaint);
        canvas.drawLine(l, b, r, t, boxPaint);
    }
}
