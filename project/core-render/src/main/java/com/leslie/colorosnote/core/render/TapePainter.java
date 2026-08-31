package com.leslie.colorosnote.core.render;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import com.leslie.colorosnote.core.model.PageObject;
import com.leslie.colorosnote.core.model.TapeObject;

import java.util.List;

/**
 * Default painter for {@link TapeObject} (spec 19, memory tape). Covered
 * tapes render as a translucent washi strip: a ~75% alpha base coat, a
 * fine two-tone pattern (solid / pinstripes / mizutama dots / graph grid /
 * crosshatch / waves / hearts) scaled to the strip width, and a slightly
 * darker edge line. Revealed
 * tapes render as a faint translucent overlay so the underlying content
 * shows through while the tape's footprint stays visible. Export flattens
 * the current state because this painter is all PageRenderer knows.
 *
 * <p>A null pattern (lenient read of a value from a newer app version) is
 * treated as {@link TapeObject.TapePattern#SOLID}.
 */
public final class TapePainter implements ObjectPainter {

    /** Fill alpha for a revealed tape (content underneath stays readable). */
    private static final int REVEALED_FILL_ALPHA = 48;
    private static final int REVEALED_LINE_ALPHA = 140;
    /** Washi translucency of a covered tape's base coat (~75%). */
    private static final int WASHI_ALPHA = 0xC0;
    /** Soft ink alpha for the overprinted pattern (keeps the two-tone gentle). */
    private static final int PATTERN_ALPHA = 150;
    private static final int EDGE_ALPHA = 110;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Reused to convert the freehand strip's stroke into its fill outline. */
    private final Paint strokeToFill = new Paint();
    private final Path path = new Path();
    /** Holds the freehand strip's path while pattern tiling reuses {@link #path}. */
    private final Path stripPath = new Path();
    /** Fill outline of the current freehand strip, used as the pattern clip. */
    private final Path bandPath = new Path();
    private final RectF rect = new RectF();
    /**
     * Thickness the current pattern is scaled to: the rect's short side for
     * straight tape, the stroke width for freehand tape (a curved strip's
     * bounding box is much larger than the strip itself).
     */
    private float patternThickness;

    public TapePainter() {
        fillPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        strokeToFill.setStyle(Paint.Style.STROKE);
        strokeToFill.setStrokeCap(Paint.Cap.ROUND);
        strokeToFill.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override
    public boolean supports(PageObject object) {
        return object instanceof TapeObject;
    }

    @Override
    public void paint(Canvas canvas, PageObject object) {
        TapeObject tape = (TapeObject) object;
        if (tape.rect != null && tape.rect.length >= 4) {
            paintRectTape(canvas, tape);
        } else if (tape.path != null && tape.path.size() >= 4) {
            paintPathTape(canvas, tape);
        }
    }

    private static TapeObject.TapePattern patternOf(TapeObject tape) {
        return tape.pattern == null ? TapeObject.TapePattern.SOLID : tape.pattern;
    }

    // ------------------------------------------------------------------
    // Straight (rect) tape
    // ------------------------------------------------------------------

    private void paintRectTape(Canvas canvas, TapeObject tape) {
        rect.set(tape.rect[0], tape.rect[1], tape.rect[2], tape.rect[3]);
        patternThickness = Math.min(rect.width(), rect.height());
        if (tape.isRevealed) {
            fillPaint.setColor(tape.color);
            fillPaint.setAlpha(REVEALED_FILL_ALPHA);
            canvas.drawRect(rect, fillPaint);
            fillPaint.setAlpha(255);
            strokePaint.setPathEffect(null);
            strokePaint.setStrokeWidth(1.5f);
            strokePaint.setColor(withAlpha(shade(tape.color), REVEALED_LINE_ALPHA));
            canvas.drawRect(rect, strokePaint);
            return;
        }
        // Translucent washi base coat.
        fillPaint.setColor(tape.color);
        fillPaint.setAlpha(WASHI_ALPHA);
        canvas.drawRect(rect, fillPaint);
        fillPaint.setAlpha(255);
        switch (patternOf(tape)) {
            case STRIPES:
                paintRectStripes(canvas, tape);
                break;
            case DOTS:
                paintRectDots(canvas, tape);
                break;
            case GRID:
                paintRectGrid(canvas, tape);
                break;
            case CROSSHATCH:
                paintRectCrosshatch(canvas, tape);
                break;
            case WAVES:
                paintRectWaves(canvas, tape);
                break;
            case HEARTS:
                paintRectHearts(canvas, tape);
                break;
            case SOLID:
            default:
                break;
        }
        // Slightly darker hairline reads as the tape's cut edge.
        strokePaint.setPathEffect(null);
        strokePaint.setStrokeWidth(1f);
        strokePaint.setColor(withAlpha(shade(tape.color), EDGE_ALPHA));
        canvas.drawRect(rect, strokePaint);
    }

    /** Fine 45-degree pinstripes, spacing proportional to the strip width. */
    private void paintRectStripes(Canvas canvas, TapeObject tape) {
        float t = thickness();
        float spacing = stripeSpacing(t);
        strokePaint.setPathEffect(null);
        strokePaint.setStrokeWidth(fineLine(t));
        strokePaint.setColor(ink(tape.color));
        float h = rect.height();
        canvas.save();
        canvas.clipRect(rect);
        for (float x = rect.left - h; x < rect.right + h; x += spacing) {
            canvas.drawLine(x, rect.bottom, x + h, rect.top, strokePaint);
        }
        canvas.restore();
    }

    /** Mizutama dots: a small staggered lattice, odd rows offset half a cell. */
    private void paintRectDots(Canvas canvas, TapeObject tape) {
        float t = thickness();
        float spacing = dotSpacing(t);
        float radius = dotRadius(t);
        fillPaint.setColor(ink(tape.color));
        canvas.save();
        canvas.clipRect(rect);
        int row = 0;
        for (float y = rect.top + spacing / 2f; y < rect.bottom; y += spacing) {
            float offset = (row++ % 2 == 0) ? 0f : spacing / 2f;
            for (float x = rect.left + spacing / 2f + offset; x < rect.right; x += spacing) {
                canvas.drawCircle(x, y, radius, fillPaint);
            }
        }
        canvas.restore();
    }

    /** Graph-paper grid: hairline strokes, not gingham bands. */
    private void paintRectGrid(Canvas canvas, TapeObject tape) {
        float t = thickness();
        float spacing = gridSpacing(t);
        strokePaint.setPathEffect(null);
        strokePaint.setStrokeWidth(hairLine(t));
        strokePaint.setColor(ink(tape.color));
        canvas.save();
        canvas.clipRect(rect);
        for (float x = rect.left + spacing; x < rect.right; x += spacing) {
            canvas.drawLine(x, rect.top, x, rect.bottom, strokePaint);
        }
        for (float y = rect.top + spacing; y < rect.bottom; y += spacing) {
            canvas.drawLine(rect.left, y, rect.right, y, strokePaint);
        }
        canvas.restore();
    }

    /** Two hairline diagonal sets at +/-45 degrees. */
    private void paintRectCrosshatch(Canvas canvas, TapeObject tape) {
        float t = thickness();
        float spacing = hatchSpacing(t);
        strokePaint.setPathEffect(null);
        strokePaint.setStrokeWidth(hairLine(t));
        strokePaint.setColor(ink(tape.color));
        float h = rect.height();
        canvas.save();
        canvas.clipRect(rect);
        for (float x = rect.left - h; x < rect.right + h; x += spacing) {
            canvas.drawLine(x, rect.bottom, x + h, rect.top, strokePaint);
            canvas.drawLine(x, rect.top, x + h, rect.bottom, strokePaint);
        }
        canvas.restore();
    }

    /** Rows of fine sine waves running along the strip. */
    private void paintRectWaves(Canvas canvas, TapeObject tape) {
        float t = thickness();
        float spacing = waveSpacing(t);
        float amplitude = t * 0.07f;
        float wavelength = t * 0.55f;
        strokePaint.setPathEffect(null);
        strokePaint.setStrokeWidth(hairLine(t));
        strokePaint.setColor(ink(tape.color));
        canvas.save();
        canvas.clipRect(rect);
        for (float y = rect.top + spacing * 0.6f; y < rect.bottom; y += spacing) {
            path.reset();
            path.moveTo(rect.left, y);
            for (float x = rect.left; x <= rect.right; x += 2f) {
                float dy = (float) Math.sin((x - rect.left) / wavelength * Math.PI * 2.0)
                        * amplitude;
                path.lineTo(x, y + dy);
            }
            canvas.drawPath(path, strokePaint);
        }
        canvas.restore();
    }

    /** Tiled tiny hearts, odd rows offset by half a cell. */
    private void paintRectHearts(Canvas canvas, TapeObject tape) {
        float t = thickness();
        float spacing = heartSpacing(t);
        float size = t * 0.085f;
        fillPaint.setColor(ink(tape.color));
        canvas.save();
        canvas.clipRect(rect);
        int row = 0;
        for (float y = rect.top + spacing * 0.6f; y < rect.bottom; y += spacing) {
            float offset = (row++ % 2 == 0) ? 0f : spacing / 2f;
            for (float x = rect.left + spacing * 0.6f + offset; x < rect.right;
                 x += spacing) {
                canvas.drawPath(heartPath(x, y, size), fillPaint);
            }
        }
        canvas.restore();
    }

    private Path heartPath(float cx, float cy, float s) {
        path.reset();
        path.moveTo(cx, cy + 0.85f * s);
        path.cubicTo(cx - 1.15f * s, cy + 0.05f * s,
                cx - 0.6f * s, cy - 0.95f * s, cx, cy - 0.3f * s);
        path.cubicTo(cx + 0.6f * s, cy - 0.95f * s,
                cx + 1.15f * s, cy + 0.05f * s, cx, cy + 0.85f * s);
        path.close();
        return path;
    }

    // ------------------------------------------------------------------
    // Hand-drawn (path) tape
    // ------------------------------------------------------------------

    private void paintPathTape(Canvas canvas, TapeObject tape) {
        stripPath.reset();
        List<Float> pts = tape.path;
        stripPath.moveTo(pts.get(0), pts.get(1));
        float left = pts.get(0);
        float top = pts.get(1);
        float right = left;
        float bottom = top;
        for (int i = 2; i + 1 < pts.size(); i += 2) {
            float x = pts.get(i);
            float y = pts.get(i + 1);
            stripPath.lineTo(x, y);
            left = Math.min(left, x);
            right = Math.max(right, x);
            top = Math.min(top, y);
            bottom = Math.max(bottom, y);
        }
        float half = tape.width / 2f;
        rect.set(left - half, top - half, right + half, bottom + half);
        patternThickness = tape.width;
        if (tape.isRevealed) {
            strokePaint.setPathEffect(null);
            strokePaint.setStrokeWidth(tape.width);
            strokePaint.setColor(withAlpha(tape.color, REVEALED_FILL_ALPHA));
            canvas.drawPath(stripPath, strokePaint);
            strokePaint.setStrokeWidth(1.5f);
            strokePaint.setColor(withAlpha(shade(tape.color), REVEALED_LINE_ALPHA));
            canvas.drawPath(stripPath, strokePaint);
            return;
        }
        strokePaint.setPathEffect(null);
        strokePaint.setStrokeWidth(tape.width);
        strokePaint.setColor(withAlpha(tape.color, WASHI_ALPHA));
        canvas.drawPath(stripPath, strokePaint);
        // Clip the fine rect pattern to the strip's stroked outline so the
        // texture follows the freehand curve: convert the stroke to its fill
        // outline and clip, which composits identically on hardware and
        // software canvases (unlike a saveLayer + DST_IN mask).
        TapeObject.TapePattern pattern = patternOf(tape);
        if (pattern != TapeObject.TapePattern.SOLID) {
            strokeToFill.setStrokeWidth(tape.width);
            bandPath.reset();
            strokeToFill.getFillPath(stripPath, bandPath);
            canvas.save();
            canvas.clipPath(bandPath);
            switch (pattern) {
                case STRIPES:
                    paintRectStripes(canvas, tape);
                    break;
                case DOTS:
                    paintRectDots(canvas, tape);
                    break;
                case GRID:
                    paintRectGrid(canvas, tape);
                    break;
                case CROSSHATCH:
                    paintRectCrosshatch(canvas, tape);
                    break;
                case WAVES:
                    paintRectWaves(canvas, tape);
                    break;
                case HEARTS:
                    paintRectHearts(canvas, tape);
                    break;
                case SOLID:
                default:
                    break;
            }
            canvas.restore();
        }
    }

    // ------------------------------------------------------------------
    // Pattern metrics — everything derives from the strip thickness so the
    // texture stays fine and even at any tape width.

    /** Strip thickness the pattern metrics scale to (set before painting). */
    private float thickness() {
        return patternThickness;
    }

    static float stripeSpacing(float t) {
        return Math.max(6f, t * 0.30f);
    }

    static float dotSpacing(float t) {
        return Math.max(6f, t * 0.30f);
    }

    static float dotRadius(float t) {
        return Math.max(1.1f, t * 0.055f);
    }

    static float gridSpacing(float t) {
        return Math.max(6f, t * 0.30f);
    }

    static float hatchSpacing(float t) {
        return Math.max(6.5f, t * 0.34f);
    }

    static float waveSpacing(float t) {
        return Math.max(6.5f, t * 0.34f);
    }

    static float heartSpacing(float t) {
        return Math.max(8f, t * 0.44f);
    }

    /** Fine pattern line width (~1.4 at the reference width). */
    static float fineLine(float t) {
        return Math.max(1.1f, t * 0.035f);
    }

    /** Hairline for grids/crosshatch (~1.1 at the reference width). */
    static float hairLine(float t) {
        return Math.max(1f, t * 0.028f);
    }

    /** Soft two-tone pattern ink: the shaded tape color at pattern alpha. */
    static int ink(int color) {
        return withAlpha(shade(color), PATTERN_ALPHA);
    }

    /** Multiplies RGB by 0.82, keeping alpha — the pattern shade. */
    static int shade(int color) {
        int a = color & 0xFF000000;
        int r = (int) (((color >> 16) & 0xFF) * 0.82f);
        int g = (int) (((color >> 8) & 0xFF) * 0.82f);
        int b = (int) ((color & 0xFF) * 0.82f);
        return a | (r << 16) | (g << 8) | b;
    }

    /** Mixes RGB 35% towards white, keeping alpha — the two-tone light band. */
    static int lighten(int color) {
        int a = color & 0xFF000000;
        int r = mix((color >> 16) & 0xFF);
        int g = mix((color >> 8) & 0xFF);
        int b = mix(color & 0xFF);
        return a | (r << 16) | (g << 8) | b;
    }

    private static int mix(int channel) {
        return channel + (int) ((255 - channel) * 0.35f);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }
}
