package com.leslie.colorosnote.core.render;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import com.leslie.colorosnote.core.model.PageObject;
import com.leslie.colorosnote.core.model.StickerObject;

import java.util.List;

/**
 * Default painter for {@link StickerObject} (spec 18.3). User-imported
 * stickers (PNG/WebP, {@code resourceId} set) render via the bitmap provider;
 * built-in stickers are drawn from {@link StickerGeometry} primitives scaled
 * from the normalized 100x100 sticker space into local bounds.
 */
public final class StickerPainter implements ObjectPainter {

    private static final float STICKER_SPACE = 100f;
    private static final int PLACEHOLDER_FILL = 0xFFF5F5F5;
    private static final int PLACEHOLDER_LINE = 0xFFB0BEC5;

    private final PageRenderer.ResourceBitmapProvider provider;
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF dst = new RectF();

    /** @param provider bitmap lookup for user-imported stickers; may be null. */
    public StickerPainter(PageRenderer.ResourceBitmapProvider provider) {
        this.provider = provider;
        fillPaint.setStyle(Paint.Style.FILL);
    }

    @Override
    public boolean supports(PageObject object) {
        return object instanceof StickerObject;
    }

    @Override
    public void paint(Canvas canvas, PageObject object) {
        StickerObject sticker = (StickerObject) object;
        float l = sticker.bounds[0];
        float t = sticker.bounds[1];
        float r = sticker.bounds[2];
        float b = sticker.bounds[3];

        if (sticker.resourceId != null && provider != null) {
            Bitmap bitmap = provider.getBitmap(sticker.resourceId);
            if (bitmap != null && !bitmap.isRecycled()) {
                dst.set(l, t, r, b);
                canvas.drawBitmap(bitmap, null, dst, bitmapPaint);
                return;
            }
        }

        List<StickerGeometry.Prim> prims = StickerGeometry.primsFor(sticker.stickerId);
        if (prims == null) {
            drawPlaceholder(canvas, l, t, r, b);
            return;
        }
        canvas.save();
        canvas.translate(l, t);
        canvas.scale((r - l) / STICKER_SPACE, (b - t) / STICKER_SPACE);
        for (StickerGeometry.Prim prim : prims) {
            fillPaint.setColor(prim.color);
            switch (prim.type) {
                case StickerGeometry.Prim.CIRCLE:
                    canvas.drawCircle(prim.cx, prim.cy, prim.radius, fillPaint);
                    break;
                case StickerGeometry.Prim.RECT:
                    canvas.drawRect(prim.left, prim.top, prim.right, prim.bottom, fillPaint);
                    break;
                case StickerGeometry.Prim.POLYGON:
                default:
                    drawPolygon(canvas, prim.points);
                    break;
            }
        }
        canvas.restore();
    }

    private void drawPolygon(Canvas canvas, float[] points) {
        if (points == null || points.length < 6) {
            return;
        }
        path.reset();
        path.moveTo(points[0], points[1]);
        for (int i = 2; i + 1 < points.length; i += 2) {
            path.lineTo(points[i], points[i + 1]);
        }
        path.close();
        canvas.drawPath(path, fillPaint);
    }

    private void drawPlaceholder(Canvas canvas, float l, float t, float r, float b) {
        boxPaint.setStyle(Paint.Style.FILL);
        boxPaint.setColor(PLACEHOLDER_FILL);
        canvas.drawRect(l, t, r, b, boxPaint);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(1.5f);
        boxPaint.setColor(PLACEHOLDER_LINE);
        canvas.drawRect(l, t, r, b, boxPaint);
    }
}
