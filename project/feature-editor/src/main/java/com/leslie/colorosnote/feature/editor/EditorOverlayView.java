package com.leslie.colorosnote.feature.editor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import java.util.List;

/**
 * Non-interactive overlay above the ink surface: in-progress lasso path,
 * active selection bounds with corner handles, and the eraser cursor circle.
 * All drawing is in view coordinates; the gesture controller converts.
 */
public final class EditorOverlayView extends View {

    private final Paint lassoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint resizeFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint resizeRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint resizeGlyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint moveFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint eraserPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path lassoPath = new Path();
    private final RectF selectionRect = new RectF();

    private boolean lassoVisible;
    private boolean selectionVisible;
    private boolean eraserVisible;
    private float eraserX;
    private float eraserY;
    private float eraserRadiusPx;

    private static final long FLASH_DURATION_MS = 1400L;
    private final Paint flashFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flashStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF flashRect = new RectF();
    private long flashStartMs = -1L;

    public EditorOverlayView(Context context) {
        super(context);
        init();
    }

    public EditorOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        lassoPaint.setStyle(Paint.Style.STROKE);
        lassoPaint.setStrokeWidth(2f);
        lassoPaint.setColor(0xFF2F6BFF);
        lassoPaint.setPathEffect(new DashPathEffect(new float[]{10f, 8f}, 0f));

        selectionPaint.setStyle(Paint.Style.STROKE);
        selectionPaint.setStrokeWidth(2f);
        selectionPaint.setColor(0xFF2F6BFF);

        handlePaint.setStyle(Paint.Style.FILL);
        handlePaint.setColor(Color.WHITE);
        handlePaint.setShadowLayer(2f, 0f, 0f, 0x402F6BFF);

        // Bottom-right resize grip: filled accent disc, white ring, white
        // diagonal expand glyph (GoodNotes-style "blue circle = resize").
        resizeFillPaint.setStyle(Paint.Style.FILL);
        resizeFillPaint.setColor(0xFF2F6BFF);
        resizeFillPaint.setShadowLayer(3f, 0f, 1f, 0x402F6BFF);
        resizeRingPaint.setStyle(Paint.Style.STROKE);
        resizeRingPaint.setStrokeWidth(3f);
        resizeRingPaint.setColor(Color.WHITE);
        resizeGlyphPaint.setStyle(Paint.Style.STROKE);
        resizeGlyphPaint.setStrokeWidth(3.5f);
        resizeGlyphPaint.setStrokeCap(Paint.Cap.ROUND);
        resizeGlyphPaint.setColor(Color.WHITE);

        // Top-right move grip: orange disc + white four-arrow (✥) glyph.
        moveFillPaint.setStyle(Paint.Style.FILL);
        moveFillPaint.setColor(0xFFFF8A3D);
        moveFillPaint.setShadowLayer(3f, 0f, 1f, 0x40FF8A3D);

        eraserPaint.setStyle(Paint.Style.STROKE);
        eraserPaint.setStrokeWidth(1.5f);
        eraserPaint.setColor(0x806B7078);

        flashFillPaint.setStyle(Paint.Style.FILL);
        flashFillPaint.setColor(0xFF2F6BFF);
        flashStrokePaint.setStyle(Paint.Style.STROKE);
        flashStrokePaint.setStrokeWidth(3f);
        flashStrokePaint.setColor(0xFF2F6BFF);
    }

    // ------------------------------------------------------------------
    // Lasso in progress
    // ------------------------------------------------------------------

    /** Replaces the in-progress lasso path (flat screen coords x,y,...). */
    public void setLassoPath(List<Float> screenPoints, boolean closeAsRect) {
        lassoPath.reset();
        if (screenPoints != null && screenPoints.size() >= 4) {
            if (closeAsRect) {
                float x0 = screenPoints.get(0);
                float y0 = screenPoints.get(1);
                float x1 = screenPoints.get(screenPoints.size() - 2);
                float y1 = screenPoints.get(screenPoints.size() - 1);
                lassoPath.addRect(Math.min(x0, x1), Math.min(y0, y1),
                        Math.max(x0, x1), Math.max(y0, y1), Path.Direction.CW);
            } else {
                lassoPath.moveTo(screenPoints.get(0), screenPoints.get(1));
                for (int i = 2; i + 1 < screenPoints.size(); i += 2) {
                    lassoPath.lineTo(screenPoints.get(i), screenPoints.get(i + 1));
                }
            }
        }
        lassoVisible = screenPoints != null && screenPoints.size() >= 4;
        invalidate();
    }

    public void clearLasso() {
        lassoVisible = false;
        lassoPath.reset();
        invalidate();
    }

    // ------------------------------------------------------------------
    // Selection bounds
    // ------------------------------------------------------------------

    public void setSelectionRect(RectF screenRect) {
        selectionVisible = screenRect != null;
        if (screenRect != null) {
            selectionRect.set(screenRect);
        }
        invalidate();
    }

    public void clearSelection() {
        selectionVisible = false;
        invalidate();
    }

    // ------------------------------------------------------------------
    // Eraser cursor
    // ------------------------------------------------------------------

    public void setEraserCursor(float x, float y, float radiusPx) {
        eraserVisible = true;
        eraserX = x;
        eraserY = y;
        eraserRadiusPx = radiusPx;
        invalidate();
    }

    public void hideEraserCursor() {
        eraserVisible = false;
        invalidate();
    }

    // ------------------------------------------------------------------
    // Search-hit flash (spec 22.3)
    // ------------------------------------------------------------------

    /**
     * Flashes a translucent highlight over the given screen rect, fading out
     * over ~1.4s. Used to show where a search hit landed on the page.
     */
    public void flashRect(RectF screenRect) {
        if (screenRect == null || screenRect.isEmpty()) {
            return;
        }
        flashRect.set(screenRect);
        // Pad a little so thin text lines stay visible inside the highlight.
        flashRect.inset(-6f, -6f);
        flashStartMs = SystemClock.uptimeMillis();
        invalidate();
    }

    // ------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        if (lassoVisible) {
            canvas.drawPath(lassoPath, lassoPaint);
        }
        if (selectionVisible) {
            canvas.drawRect(selectionRect, selectionPaint);
            drawHandle(canvas, selectionRect.left, selectionRect.top);
            drawMoveHandle(canvas, selectionRect.right, selectionRect.top);
            drawHandle(canvas, selectionRect.left, selectionRect.bottom);
            drawResizeHandle(canvas, selectionRect.right, selectionRect.bottom);
        }
        if (eraserVisible) {
            canvas.drawCircle(eraserX, eraserY, eraserRadiusPx, eraserPaint);
        }
        drawFlash(canvas);
    }

    /** Fading search-hit highlight; drives its own animation frames. */
    private void drawFlash(Canvas canvas) {
        if (flashStartMs < 0) {
            return;
        }
        long elapsed = SystemClock.uptimeMillis() - flashStartMs;
        if (elapsed >= FLASH_DURATION_MS) {
            flashStartMs = -1L;
            return;
        }
        float t = 1f - (float) elapsed / (float) FLASH_DURATION_MS;
        flashFillPaint.setAlpha((int) (56 * t));
        flashStrokePaint.setAlpha((int) (220 * t));
        canvas.drawRoundRect(flashRect, 8f, 8f, flashFillPaint);
        canvas.drawRoundRect(flashRect, 8f, 8f, flashStrokePaint);
        postInvalidateOnAnimation();
    }

    private void drawHandle(Canvas canvas, float x, float y) {
        canvas.drawCircle(x, y, 10f, handlePaint);
        canvas.drawCircle(x, y, 10f, selectionPaint);
    }

    /** Bottom-right resize grip: accent disc with a white ↘ expand glyph. */
    private void drawResizeHandle(Canvas canvas, float x, float y) {
        canvas.drawCircle(x, y, 19f, resizeFillPaint);
        canvas.drawCircle(x, y, 19f, resizeRingPaint);
        // Diagonal shaft ↘
        canvas.drawLine(x - 8f, y - 8f, x + 8f, y + 8f, resizeGlyphPaint);
        // Arrowhead at the bottom-right end.
        canvas.drawLine(x + 8f, y + 8f, x + 2f, y + 8f, resizeGlyphPaint);
        canvas.drawLine(x + 8f, y + 8f, x + 8f, y + 2f, resizeGlyphPaint);
        // Arrowhead at the top-left end.
        canvas.drawLine(x - 8f, y - 8f, x - 2f, y - 8f, resizeGlyphPaint);
        canvas.drawLine(x - 8f, y - 8f, x - 8f, y - 2f, resizeGlyphPaint);
    }

    /** Top-right move grip: orange disc with a white four-arrow (✥) glyph. */
    private void drawMoveHandle(Canvas canvas, float x, float y) {
        canvas.drawCircle(x, y, 19f, moveFillPaint);
        canvas.drawCircle(x, y, 19f, resizeRingPaint);
        // Four outward chevrons (N/E/S/W) — reads as "move" without the
        // center-shaft clutter a full ✥ has at this size.
        // East
        canvas.drawLine(x + 10.5f, y, x + 5f, y - 5.5f, resizeGlyphPaint);
        canvas.drawLine(x + 10.5f, y, x + 5f, y + 5.5f, resizeGlyphPaint);
        // West
        canvas.drawLine(x - 10.5f, y, x - 5f, y - 5.5f, resizeGlyphPaint);
        canvas.drawLine(x - 10.5f, y, x - 5f, y + 5.5f, resizeGlyphPaint);
        // South
        canvas.drawLine(x, y + 10.5f, x - 5.5f, y + 5f, resizeGlyphPaint);
        canvas.drawLine(x, y + 10.5f, x + 5.5f, y + 5f, resizeGlyphPaint);
        // North
        canvas.drawLine(x, y - 10.5f, x - 5.5f, y - 5f, resizeGlyphPaint);
        canvas.drawLine(x, y - 10.5f, x + 5.5f, y - 5f, resizeGlyphPaint);
    }
}
