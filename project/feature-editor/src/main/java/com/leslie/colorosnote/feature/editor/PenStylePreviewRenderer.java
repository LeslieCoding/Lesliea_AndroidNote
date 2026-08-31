package com.leslie.colorosnote.feature.editor;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

import com.leslie.colorosnote.core.ink.BrushEngine;
import com.leslie.colorosnote.core.ink.StrokeMesh;
import com.leslie.colorosnote.core.model.BrushPreset;
import com.leslie.colorosnote.core.model.StrokePoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders the small ink-trace previews on the pen-style cards in
 * {@link BrushPropertiesPopup}. The preview goes through the exact same
 * geometry pipeline as real writing: a synthetic signature-like sample
 * (with pressure / speed / tilt profiles) is meshed by
 * {@link BrushEngine#computeMesh(List, BrushPreset)} and filled as the
 * mesh's closed outline, so each card shows the genuine feel of its
 * variant — width envelope, pressure response, velocity thinning and the
 * start/end tapers all come from the variant's own parameters.
 */
public final class PenStylePreviewRenderer {

    /** Ink color of the preview trace (dark on the card's light fill). */
    private static final int INK_COLOR = 0xFF1A1A1A;
    /** Samples synthesized along the demo stroke. */
    private static final int SAMPLE_COUNT = 72;
    /** Logical stroke length (1 unit ≈ 1dp, matching nib baseWidth 2..4.5). */
    private static final float STROKE_LENGTH = 96f;
    /** Wave amplitude of the demo stroke. */
    private static final float AMPLITUDE = 11f;
    /** Wave frequency (radians over the stroke: ~1.1 full lobes). */
    private static final float PHASE_SPAN = (float) (Math.PI * 2.2);
    /** Fixed pen tilt (radians) demonstrating tilt-aware nibs (美工尖). */
    private static final float DEMO_TILT = 0.75f;
    /** Horizontal padding inside the bitmap, fraction of its width. */
    private static final float PAD_X_FRACTION = 0.08f;
    /** Vertical padding inside the bitmap, fraction of its height. */
    private static final float PAD_Y_FRACTION = 0.12f;

    private PenStylePreviewRenderer() {
    }

    /**
     * Renders one variant's ink trace into a transparent bitmap of the
     * requested pixel size (the stroke is auto-fitted and centered).
     */
    public static Bitmap render(PenStyleVariants.Variant variant,
                                int widthPx, int heightPx) {
        BrushPreset preset = BrushPreset.fountainPen();
        variant.applyTo(preset); // all feel params come from the variant itself
        StrokeMesh mesh = BrushEngine.computeMesh(demoStroke(), preset);

        Bitmap bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        // Fit the mesh bounds into the bitmap with padding, centered.
        float[] b = mesh.bounds;
        float bw = Math.max(1e-3f, b[2] - b[0]);
        float bh = Math.max(1e-3f, b[3] - b[1]);
        float padX = widthPx * PAD_X_FRACTION;
        float padY = heightPx * PAD_Y_FRACTION;
        float scale = Math.min((widthPx - 2f * padX) / bw, (heightPx - 2f * padY) / bh);
        float ox = (widthPx - bw * scale) / 2f - b[0] * scale;
        float oy = (heightPx - bh * scale) / 2f - b[1] * scale;

        float[] outline = mesh.closedOutline();
        Path path = new Path();
        path.moveTo(outline[0] * scale + ox, outline[1] * scale + oy);
        for (int i = 2; i + 1 < outline.length; i += 2) {
            path.lineTo(outline[i] * scale + ox, outline[i + 1] * scale + oy);
        }
        path.close();

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(INK_COLOR);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawPath(path, paint);
        return bitmap;
    }

    /**
     * A short signature-like double wave: heavy pressure on the lobes
     * (shows pressure response), fast through the midline crossings
     * (shows velocity thinning), fixed tilt throughout (shows 美工尖).
     */
    private static List<StrokePoint> demoStroke() {
        List<StrokePoint> pts = new ArrayList<>(SAMPLE_COUNT);
        for (int i = 0; i < SAMPLE_COUNT; i++) {
            float t = i / (float) (SAMPLE_COUNT - 1);
            float phase = t * PHASE_SPAN;
            float grow = 0.8f + 0.2f * t; // lobes grow slightly toward the end
            float x = t * STROKE_LENGTH;
            float y = -AMPLITUDE * grow * (float) Math.sin(phase);

            // Pen behavior: slow + pressed at the lobes, fast at crossings.
            float lobe = Math.abs((float) Math.sin(phase)); // 1 at lobe tips
            float pressure = 0.30f + 0.62f * lobe;
            float speed = 260f + 1500f * (1f - lobe);

            // Velocity vector along the travel direction (wave tangent).
            float dx = STROKE_LENGTH;
            float dy = -AMPLITUDE * (float) (PHASE_SPAN * grow * Math.cos(phase)
                    + 0.2f * Math.sin(phase));
            float len = (float) Math.hypot(dx, dy);

            StrokePoint p = new StrokePoint();
            p.x = x;
            p.y = y;
            p.pressure = Math.min(1f, pressure);
            p.timestampMs = i * 8L;
            p.tilt = DEMO_TILT;
            p.velocityX = speed * dx / len;
            p.velocityY = speed * dy / len;
            pts.add(p);
        }
        return pts;
    }
}
