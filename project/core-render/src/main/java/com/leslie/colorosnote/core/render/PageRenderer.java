package com.leslie.colorosnote.core.render;

import android.graphics.Bitmap;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Matrix;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.os.Build;

import com.leslie.colorosnote.core.ink.BrushEngine;
import com.leslie.colorosnote.core.ink.StrokeGeometry;
import com.leslie.colorosnote.core.ink.StrokeMesh;
import com.leslie.colorosnote.core.model.BrushPreset;
import com.leslie.colorosnote.core.model.Page;
import com.leslie.colorosnote.core.model.PageBackground;
import com.leslie.colorosnote.core.model.PageObject;
import com.leslie.colorosnote.core.model.Stroke;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Draws a {@link Page} onto a Canvas in logical page coordinates: background
 * template, vector strokes (as {@link StrokeMesh} polygon fills — never
 * per-segment drawPath), and the object layer via {@link ObjectPainter} SPI.
 *
 * <p>The caller is responsible for applying the viewport matrix to the canvas
 * (see {@link InkView}). Rendering is allocation-light and does no IO.
 */
public final class PageRenderer {

    /** Optional lookup for IMAGE/CUSTOM background bitmaps by resource id. */
    public interface ResourceBitmapProvider {
        Bitmap getBitmap(String resourceId);
    }

    private static final float DEFAULT_LINE_SPACING = 32f;
    private static final float DEFAULT_MARGIN = 48f;
    private static final int DEFAULT_BG_COLOR = Color.WHITE;
    private static final int DEFAULT_LINE_COLOR = 0xFFB0BEC5;
    /** Small gray labels (timeline hours, weekday headers, field names). */
    private static final int DEFAULT_LABEL_COLOR = 0xFF8A9099;
    /** Document backgrounds render at 2x logical page units, like InkView's offscreen layer. */
    private static final float DOCUMENT_BG_SCALE = 2f;

    private final List<ObjectPainter> painters = new ArrayList<>();
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint();
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final DashPathEffect dashEffect = new DashPathEffect(new float[]{6f, 5f}, 0f);
    private final Path strokePath = new Path();
    /** Scratch quad for the non-self-intersecting round-segment renderer. */
    private final float[] segmentQuad = new float[8];
    /** Scratch outer-wedge join triangle. */
    private final float[] joinTriangle = new float[6];
    private final android.graphics.RectF pageRect = new android.graphics.RectF();
    private ResourceBitmapProvider bitmapProvider;
    private PageBackgroundProvider backgroundProvider;

    public PageRenderer() {
        strokePaint.setStyle(Paint.Style.FILL);
        painters.add(new TextBoxPainter());
        painters.add(new ShapePainter());
    }

    /** Registers an object painter; earlier registrations win. */
    public void registerPainter(int index, ObjectPainter painter) {
        painters.add(index, painter);
    }

    public void addPainter(ObjectPainter painter) {
        painters.add(painter);
    }

    public void setResourceBitmapProvider(ResourceBitmapProvider provider) {
        bitmapProvider = provider;
    }

    /**
     * Sets the provider for PDF/OFFICE document backgrounds. The provider is
     * bound to one source document; {@link PageBackground#pdfPageIndex} picks
     * the page. Calls into the provider may block on rendering — pre-warm off
     * the main thread.
     */
    public void setPageBackgroundProvider(PageBackgroundProvider provider) {
        backgroundProvider = provider;
    }

    // ------------------------------------------------------------------
    // Full page
    // ------------------------------------------------------------------

    /** Draws background, strokes (zIndex order) and objects onto the canvas. */
    public void drawPage(Canvas canvas, Page page) {
        drawBackground(canvas, page);
        drawStrokes(canvas, page.strokes);
        drawObjects(canvas, page.objects);
    }

    // ------------------------------------------------------------------
    // Background templates (requirement 8)
    // ------------------------------------------------------------------

    public void drawBackground(Canvas canvas, Page page) {
        PageBackground bg = page.background;
        Map<String, String> params = bg.templateParams;
        int bgColor = parseColor(params, "backgroundColor", DEFAULT_BG_COLOR);
        canvas.drawRect(0f, 0f, page.width, page.height, paint(bgPaint, bgColor));

        switch (bg.type) {
            case LINED:
                drawLined(canvas, page, params, false);
                break;
            case GRID:
                drawGrid(canvas, page, params);
                break;
            case DOT:
                drawDots(canvas, page, params);
                break;
            case CORNELL:
                drawLined(canvas, page, params, true);
                break;
            case CORNELL_GRID:
                drawCornellGrid(canvas, page, params);
                break;
            case ENGLISH:
                drawEnglish(canvas, page, params);
                break;
            case TIANZI:
                drawCharacterGrid(canvas, page, params, false);
                break;
            case MIZI:
                drawCharacterGrid(canvas, page, params, true);
                break;
            case MUSIC:
                drawMusicStaff(canvas, page, params);
                break;
            case TODO:
                drawTodo(canvas, page, params);
                break;
            case DAILY:
                drawDaily(canvas, page, params);
                break;
            case WEEKLY:
                drawWeekly(canvas, page, params);
                break;
            case MEETING:
                drawMeeting(canvas, page, params);
                break;
            case IMAGE:
            case CUSTOM: {
                if (bitmapProvider != null && bg.resourceId != null) {
                    Bitmap bmp = bitmapProvider.getBitmap(bg.resourceId);
                    if (bmp != null) {
                        canvas.drawBitmap(bmp, null,
                                new android.graphics.RectF(0f, 0f, page.width, page.height),
                                null);
                    }
                }
                break;
            }
            case PDF:
            case OFFICE: {
                // Document page bitmaps come from the document-pdf /
                // document-office modules via a PageBackgroundProvider.
                if (backgroundProvider != null && bg.pdfPageIndex != null) {
                    Bitmap bmp = backgroundProvider.getPageBitmap(bg.pdfPageIndex,
                            Math.max(1, Math.round(page.width * DOCUMENT_BG_SCALE)));
                    if (bmp != null && !bmp.isRecycled()) {
                        pageRect.set(0f, 0f, page.width, page.height);
                        canvas.drawBitmap(bmp, null, pageRect, bitmapPaint);
                    }
                }
                break;
            }
            case BLANK:
            default:
                break;
        }
    }

    private void drawLined(Canvas canvas, Page page, Map<String, String> params,
                           boolean cornell) {
        float spacing = parseFloat(params, "lineSpacing", DEFAULT_LINE_SPACING);
        float margin = parseFloat(params, "marginLeft", cornell ? 72f : DEFAULT_MARGIN);
        int color = parseColor(params, "lineColor", DEFAULT_LINE_COLOR);
        linePaint.setColor(color);
        linePaint.setStrokeWidth(1f);

        float top = cornell ? parseFloat(params, "headerHeight", 72f) : spacing;
        float bottom = cornell
                ? page.height - parseFloat(params, "summaryHeight", 96f)
                : page.height;
        for (float y = top; y <= bottom; y += spacing) {
            canvas.drawLine(0f, y, page.width, y, linePaint);
        }
        // Margin / Cornell cue column.
        canvas.drawLine(margin, 0f, margin, page.height, linePaint);
        if (cornell) {
            canvas.drawLine(0f, top, page.width, top, linePaint);
            canvas.drawLine(0f, bottom, page.width, bottom, linePaint);
        }
    }

    private void drawGrid(Canvas canvas, Page page, Map<String, String> params) {
        float spacing = parseFloat(params, "gridSpacing", DEFAULT_LINE_SPACING);
        int color = parseColor(params, "lineColor", DEFAULT_LINE_COLOR);
        linePaint.setColor(color);
        linePaint.setStrokeWidth(1f);
        for (float x = spacing; x < page.width; x += spacing) {
            canvas.drawLine(x, 0f, x, page.height, linePaint);
        }
        for (float y = spacing; y < page.height; y += spacing) {
            canvas.drawLine(0f, y, page.width, y, linePaint);
        }
    }

    private void drawDots(Canvas canvas, Page page, Map<String, String> params) {
        float spacing = parseFloat(params, "dotSpacing", DEFAULT_LINE_SPACING);
        int color = parseColor(params, "dotColor", DEFAULT_LINE_COLOR);
        float radius = parseFloat(params, "dotRadius", 1.2f);
        linePaint.setColor(color);
        for (float x = spacing; x < page.width; x += spacing) {
            for (float y = spacing; y < page.height; y += spacing) {
                canvas.drawCircle(x, y, radius, linePaint);
            }
        }
    }

    /** Cornell three-part structure (header / cue column / summary) with a grid main area. */
    private void drawCornellGrid(Canvas canvas, Page page, Map<String, String> params) {
        float spacing = parseFloat(params, "gridSpacing", DEFAULT_LINE_SPACING);
        float margin = parseFloat(params, "marginLeft", 72f);
        float top = parseFloat(params, "headerHeight", 72f);
        float bottom = page.height - parseFloat(params, "summaryHeight", 96f);
        int color = parseColor(params, "lineColor", DEFAULT_LINE_COLOR);
        linePaint.setColor(color);
        linePaint.setStrokeWidth(1f);
        // Structure: header divider, summary divider, cue column.
        canvas.drawLine(0f, top, page.width, top, linePaint);
        canvas.drawLine(0f, bottom, page.width, bottom, linePaint);
        canvas.drawLine(margin, top, margin, bottom, linePaint);
        // Grid inside the main note area.
        for (float x = margin + spacing; x < page.width; x += spacing) {
            canvas.drawLine(x, top, x, bottom, linePaint);
        }
        for (float y = top + spacing; y < bottom; y += spacing) {
            canvas.drawLine(margin, y, page.width, y, linePaint);
        }
    }

    /** English four-line guide: solid top/bottom, dashed midlines, wide group gap. */
    private void drawEnglish(Canvas canvas, Page page, Map<String, String> params) {
        float spacing = parseFloat(params, "lineSpacing", 30f);
        float groupGap = parseFloat(params, "groupSpacing", spacing * 1.5f);
        float left = parseFloat(params, "marginLeft", 0f);
        float right = page.width - parseFloat(params, "marginRight", 0f);
        int color = parseColor(params, "lineColor", DEFAULT_LINE_COLOR);
        linePaint.setColor(color);
        linePaint.setStrokeWidth(1f);
        float pitch = 3f * spacing + groupGap;
        for (float y = spacing; y + 3f * spacing <= page.height; y += pitch) {
            canvas.drawLine(left, y, right, y, linePaint);
            canvas.drawLine(left, y + 3f * spacing, right, y + 3f * spacing, linePaint);
            linePaint.setPathEffect(dashEffect);
            canvas.drawLine(left, y + spacing, right, y + spacing, linePaint);
            canvas.drawLine(left, y + 2f * spacing, right, y + 2f * spacing, linePaint);
            linePaint.setPathEffect(null);
        }
    }

    /**
     * Chinese character practice grid (田字格 / 米字格): solid squares with a
     * dashed cross per cell; mizi adds dashed diagonals.
     */
    private void drawCharacterGrid(Canvas canvas, Page page, Map<String, String> params,
                                   boolean mizi) {
        float size = parseFloat(params, "cellSize", 56f);
        float margin = parseFloat(params, "margin", 32f);
        int color = parseColor(params, "lineColor", DEFAULT_LINE_COLOR);
        linePaint.setColor(color);
        linePaint.setStrokeWidth(1f);
        int cols = (int) ((page.width - 2f * margin) / size);
        int rows = (int) ((page.height - 2f * margin) / size);
        if (cols <= 0 || rows <= 0) {
            return;
        }
        float right = margin + cols * size;
        float bottom = margin + rows * size;
        for (int c = 0; c <= cols; c++) {
            float x = margin + c * size;
            canvas.drawLine(x, margin, x, bottom, linePaint);
        }
        for (int r = 0; r <= rows; r++) {
            float y = margin + r * size;
            canvas.drawLine(margin, y, right, y, linePaint);
        }
        linePaint.setPathEffect(dashEffect);
        float half = size / 2f;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                float x = margin + c * size;
                float y = margin + r * size;
                canvas.drawLine(x, y + half, x + size, y + half, linePaint);
                canvas.drawLine(x + half, y, x + half, y + size, linePaint);
                if (mizi) {
                    canvas.drawLine(x, y, x + size, y + size, linePaint);
                    canvas.drawLine(x + size, y, x, y + size, linePaint);
                }
            }
        }
        linePaint.setPathEffect(null);
    }

    /** Music staff: five solid lines per group, repeated with a wide gap. */
    private void drawMusicStaff(Canvas canvas, Page page, Map<String, String> params) {
        float staffSpacing = parseFloat(params, "staffSpacing", 14f);
        float groupGap = parseFloat(params, "groupSpacing", 64f);
        float left = parseFloat(params, "marginLeft", 0f);
        float right = page.width - parseFloat(params, "marginRight", 0f);
        int color = parseColor(params, "lineColor", DEFAULT_LINE_COLOR);
        linePaint.setColor(color);
        linePaint.setStrokeWidth(1.2f);
        float staffHeight = 4f * staffSpacing;
        float pitch = staffHeight + groupGap;
        for (float y = groupGap; y + staffHeight <= page.height; y += pitch) {
            for (int i = 0; i < 5; i++) {
                float ly = y + i * staffSpacing;
                canvas.drawLine(left, ly, right, ly, linePaint);
            }
        }
    }

    /** Todo list: a small checkbox outline plus a ruled line per row. */
    private void drawTodo(Canvas canvas, Page page, Map<String, String> params) {
        float spacing = parseFloat(params, "lineSpacing", 48f);
        float margin = parseFloat(params, "marginLeft", 28f);
        float box = parseFloat(params, "checkboxSize", 18f);
        int color = parseColor(params, "lineColor", DEFAULT_LINE_COLOR);
        linePaint.setColor(color);
        linePaint.setStrokeWidth(1.2f);
        linePaint.setStyle(Paint.Style.STROKE);
        for (float y = spacing; y <= page.height; y += spacing) {
            canvas.drawRect(margin, y - box, margin + box, y, linePaint);
            canvas.drawLine(margin + box + 14f, y, page.width - 14f, y, linePaint);
        }
        linePaint.setStyle(Paint.Style.FILL);
    }

    /** Day planner: hourly timeline 8:00–22:00 with a ruled line per hour. */
    private void drawDaily(Canvas canvas, Page page, Map<String, String> params) {
        int startHour = (int) parseFloat(params, "startHour", 8f);
        int endHour = (int) parseFloat(params, "endHour", 22f);
        float labelWidth = parseFloat(params, "labelWidth", 64f);
        float textSize = parseFloat(params, "labelTextSize", 18f);
        int color = parseColor(params, "lineColor", DEFAULT_LINE_COLOR);
        int labelColor = parseColor(params, "labelColor", DEFAULT_LABEL_COLOR);
        int slots = Math.max(1, endHour - startHour + 1);
        float rowHeight = (page.height - 2f * DEFAULT_MARGIN) / slots;
        linePaint.setColor(color);
        linePaint.setStrokeWidth(1f);
        textPaint.setColor(labelColor);
        textPaint.setTextSize(textSize);
        for (int i = 0; i < slots; i++) {
            float y = DEFAULT_MARGIN + (i + 1) * rowHeight;
            canvas.drawLine(labelWidth, y, page.width - 14f, y, linePaint);
            canvas.drawText((startHour + i) + ":00", 14f, y - rowHeight / 2f + textSize / 2f,
                    textPaint);
        }
    }

    /** Weekly planner: header band with weekday labels above seven columns. */
    private void drawWeekly(Canvas canvas, Page page, Map<String, String> params) {
        float headerHeight = parseFloat(params, "headerHeight", 56f);
        float textSize = parseFloat(params, "labelTextSize", 20f);
        int color = parseColor(params, "lineColor", DEFAULT_LINE_COLOR);
        int labelColor = parseColor(params, "labelColor", DEFAULT_LABEL_COLOR);
        linePaint.setColor(color);
        linePaint.setStrokeWidth(1f);
        textPaint.setColor(labelColor);
        textPaint.setTextSize(textSize);
        canvas.drawLine(0f, headerHeight, page.width, headerHeight, linePaint);
        float colWidth = page.width / 7f;
        for (int i = 1; i < 7; i++) {
            float x = i * colWidth;
            canvas.drawLine(x, headerHeight, x, page.height, linePaint);
        }
        for (int i = 0; i < 7; i++) {
            String label = WEEKDAY_LABELS[i];
            float x = i * colWidth + (colWidth - textPaint.measureText(label)) / 2f;
            canvas.drawText(label, x, headerHeight / 2f + textSize / 2f, textPaint);
        }
    }

    /** Meeting minutes: labeled header fields, ruled body, summary section. */
    private void drawMeeting(Canvas canvas, Page page, Map<String, String> params) {
        float spacing = parseFloat(params, "lineSpacing", DEFAULT_LINE_SPACING);
        float margin = parseFloat(params, "marginLeft", DEFAULT_MARGIN);
        float summaryHeight = parseFloat(params, "summaryHeight", 96f);
        float textSize = parseFloat(params, "labelTextSize", 20f);
        int color = parseColor(params, "lineColor", DEFAULT_LINE_COLOR);
        int labelColor = parseColor(params, "labelColor", DEFAULT_LABEL_COLOR);
        linePaint.setColor(color);
        linePaint.setStrokeWidth(1f);
        textPaint.setColor(labelColor);
        textPaint.setTextSize(textSize);
        float lineX = margin + 72f;
        // Header: three labeled fields (主题 / 日期 / 参会).
        float headerBottom = margin + MEETING_FIELDS.length * spacing + spacing;
        for (int i = 0; i < MEETING_FIELDS.length; i++) {
            float y = margin + (i + 1) * spacing;
            canvas.drawText(MEETING_FIELDS[i], margin, y - 4f, textPaint);
            canvas.drawLine(lineX, y, page.width - margin, y, linePaint);
        }
        // Ruled body between header and summary.
        float summaryTop = page.height - summaryHeight;
        for (float y = headerBottom; y < summaryTop; y += spacing) {
            canvas.drawLine(0f, y, page.width, y, linePaint);
        }
        // Summary section divider + label.
        canvas.drawLine(0f, summaryTop, page.width, summaryTop, linePaint);
        canvas.drawText("总结", margin, summaryTop + spacing - 4f, textPaint);
    }

    private static final String[] WEEKDAY_LABELS = {
            "周一", "周二", "周三", "周四", "周五", "周六", "周日"};
    private static final String[] MEETING_FIELDS = {"主题", "日期", "参会"};

    // ------------------------------------------------------------------
    // Strokes
    // ------------------------------------------------------------------

    /** Draws strokes in zIndex order. */
    public void drawStrokes(Canvas canvas, List<Stroke> strokes) {
        List<Stroke> sorted = new ArrayList<>(strokes);
        Collections.sort(sorted, Comparator.comparingInt(s -> s.zIndex));
        for (Stroke stroke : sorted) {
            if (isBelowVisibleDetail(canvas, stroke)) {
                continue;
            }
            drawStroke(canvas, stroke);
        }
    }

    /**
     * Below this projected size a stroke cannot render as more than a faint
     * dot. Deliberately under one device pixel: this is a performance filter,
     * never a visible change.
     */
    private static final float MIN_VISIBLE_STROKE_PX = 0.75f;
    private final float[] lodMatrixValues = new float[9];

    /**
     * Level-of-detail culling (v3.0.0) — the technique ZoomNotes documents as
     * "View Filtering": skip strokes whose projection is too small to show
     * anything.
     *
     * <p>On a dense page viewed zoomed out (a full lecture of notes, or an
     * imported PDF with hundreds of annotations) most strokes land on a handful
     * of pixels each, and meshing them costs far more than the grey speck they
     * contribute.
     */
    private boolean isBelowVisibleDetail(Canvas canvas, Stroke stroke) {
        if (stroke == null || stroke.points == null || stroke.points.isEmpty()) {
            return true;
        }
        canvas.getMatrix().getValues(lodMatrixValues);
        float scale = lodMatrixValues[Matrix.MSCALE_X];
        if (!(scale > 0f) || !Float.isFinite(scale)) {
            return false; // unknown transform: never cull
        }
        float[] b = stroke.computeBounds();
        float diagonalPx = (float) Math.hypot(b[2] - b[0], b[3] - b[1]) * scale;
        return diagonalPx < MIN_VISIBLE_STROKE_PX;
    }

    /** Meshes and draws a single stroke as a filled variable-width polygon. */
    public void drawStroke(Canvas canvas, Stroke stroke) {
        if (stroke.brushPreset == null || stroke.points.isEmpty()) {
            return;
        }
        float renderScale = canvasScale(canvas);
        StrokeMesh mesh = BrushEngine.computeMesh(
                stroke.points, stroke.brushPreset, renderScale);
        drawMesh(canvas, mesh, stroke.brushPreset.blendMode, renderScale);
    }

    /** Draws a pre-built mesh (live stroke path reuse). */
    public void drawMesh(Canvas canvas, StrokeMesh mesh, BrushPreset.BlendMode blendMode) {
        drawMesh(canvas, mesh, blendMode, canvasScale(canvas));
    }

    private void drawMesh(Canvas canvas, StrokeMesh mesh,
                          BrushPreset.BlendMode blendMode, float renderScale) {
        if (mesh.pointCount() == 0) {
            return;
        }
        buildRoundUnionPath(mesh, strokePath, segmentQuad, joinTriangle);

        strokePaint.setColor(mesh.color);
        strokePaint.setAlpha((int) (255f * mesh.averageOpacity() + 0.5f));
        applyBlendMode(strokePaint, blendMode);
        canvas.drawPath(strokePath, strokePaint);
        strokePaint.setXfermode(null);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            strokePaint.setBlendMode(BlendMode.SRC_OVER);
        }
    }

    /** Builds the outline Path for a mesh (exposed for custom painting). */
    public static Path buildPath(StrokeMesh mesh) {
        Path path = new Path();
        buildRoundUnionPath(mesh, path, new float[8], new float[6]);
        return path;
    }

    /**
     * Builds the stroke as a non-zero-winding union of safe segment quads and
     * round knot discs.  Unlike a single left-rail/right-rail outline, this
     * remains valid for hairpins and when the zoomed brush radius is larger
     * than the local turn radius.
     */
    private static void buildRoundUnionPath(StrokeMesh mesh, Path path,
                                            float[] quad, float[] join) {
        path.reset();
        path.setFillType(Path.FillType.WINDING);
        int n = mesh.pointCount();
        for (int i = 0; i + 1 < n; i++) {
            float x0 = mesh.centerline[i * 2];
            float y0 = mesh.centerline[i * 2 + 1];
            float x1 = mesh.centerline[(i + 1) * 2];
            float y1 = mesh.centerline[(i + 1) * 2 + 1];
            if (StrokeGeometry.segmentQuad(x0, y0, mesh.widths[i],
                    x1, y1, mesh.widths[i + 1], quad)) {
                appendPolygon(path, quad, 4);
            }
        }
        // Lightweight bevel wedges close ordinary refined turns. Only very
        // sharp/reversing joins retain a circle; endpoints always get caps.
        for (int i = 1; i + 1 < n; i++) {
            float px = mesh.centerline[(i - 1) * 2];
            float py = mesh.centerline[(i - 1) * 2 + 1];
            float cx = mesh.centerline[i * 2];
            float cy = mesh.centerline[i * 2 + 1];
            float nx = mesh.centerline[(i + 1) * 2];
            float ny = mesh.centerline[(i + 1) * 2 + 1];
            int kind = StrokeGeometry.joinTriangle(
                    cx - px, cy - py, nx - cx, ny - cy,
                    cx, cy, mesh.widths[i], join);
            if (kind == StrokeGeometry.JOIN_TRIANGLE) {
                appendPolygon(path, join, 3);
                // v3.2.10: the bevel only patches the OUTER corner; the INNER
                // crescent (where the two quads meet with different normals)
                // stays open. A thick pen hides it, but the fountain pen at its
                // 1.13pt minimum made it read as white tears. A round knot disc
                // fills the crescent without changing the outer silhouette.
                addRoundDisc(path, cx, cy, mesh.widths[i]);
            } else if (kind == StrokeGeometry.JOIN_ROUND) {
                addRoundDisc(path, cx, cy, mesh.widths[i]);
            } else {
                // Nearly straight runs normally need no knot disc (the quads
                // tile seamlessly), but a sharp width ramp — e.g. the pen's
                // end taper — can still leave an unfilled inner notch; fill it
                // only where the width actually changes.
                float wPrev = mesh.widths[i - 1];
                float wNext = mesh.widths[i + 1];
                float lo = Math.min(wPrev, wNext);
                float hi = Math.max(wPrev, wNext);
                if (lo <= 0f || hi / lo > 1.5f) {
                    addRoundDisc(path, cx, cy, mesh.widths[i]);
                }
            }
        }
        if (n > 0) {
            addRoundDisc(path, mesh.centerline[0], mesh.centerline[1], mesh.widths[0]);
            if (n > 1) {
                addRoundDisc(path, mesh.centerline[(n - 1) * 2],
                        mesh.centerline[(n - 1) * 2 + 1], mesh.widths[n - 1]);
            }
        }
    }

    private static void addRoundDisc(Path path, float x, float y, float width) {
        float radius = Math.max(0f, width) * 0.5f;
        if (radius > 0f && Float.isFinite(x) && Float.isFinite(y)) {
            // segmentQuad is counter-clockwise in Android's y-down coordinate
            // space, so circles must use the same direction.
            path.addCircle(x, y, radius, Path.Direction.CCW);
        }
    }

    private static void appendPolygon(Path path, float[] xy, int points) {
        path.moveTo(xy[0], xy[1]);
        for (int i = 1; i < points; i++) {
            path.lineTo(xy[i * 2], xy[i * 2 + 1]);
        }
        path.close();
    }

    private static float canvasScale(Canvas canvas) {
        Matrix matrix = canvas.getMatrix();
        float[] values = new float[9];
        matrix.getValues(values);
        float sx = (float) Math.hypot(
                values[Matrix.MSCALE_X], values[Matrix.MSKEW_Y]);
        return Float.isFinite(sx) && sx > 0f ? sx : 1f;
    }

    private static void applyBlendMode(Paint paint, BrushPreset.BlendMode mode) {
        if (mode == null || mode == BrushPreset.BlendMode.SRC_OVER) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            switch (mode) {
                case MULTIPLY:
                    paint.setBlendMode(BlendMode.MULTIPLY);
                    break;
                case SCREEN:
                    paint.setBlendMode(BlendMode.SCREEN);
                    break;
                case OVERLAY:
                    paint.setBlendMode(BlendMode.OVERLAY);
                    break;
                default:
                    break;
            }
        } else if (mode == BrushPreset.BlendMode.MULTIPLY) {
            // API 28 fallback: MULTIPLY via Porter-Duff xfermode.
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.MULTIPLY));
        }
    }

    // ------------------------------------------------------------------
    // Objects
    // ------------------------------------------------------------------

    /** Draws the object layer in zIndex order using registered painters. */
    public void drawObjects(Canvas canvas, List<PageObject> objects) {
        List<PageObject> sorted = new ArrayList<>(objects);
        Collections.sort(sorted, Comparator.comparingInt(o -> o.zIndex));
        for (PageObject object : sorted) {
            for (ObjectPainter painter : painters) {
                if (painter.supports(object)) {
                    canvas.save();
                    canvas.concat(object.toMatrix());
                    painter.paint(canvas, object);
                    canvas.restore();
                    break;
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Template param parsing
    // ------------------------------------------------------------------

    private static Paint paint(Paint paint, int color) {
        paint.setColor(color);
        return paint;
    }

    static float parseFloat(Map<String, String> params, String key, float fallback) {
        String v = params.get(key);
        if (v == null) {
            return fallback;
        }
        try {
            return Float.parseFloat(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static int parseColor(Map<String, String> params, String key, int fallback) {
        String v = params.get(key);
        if (v == null) {
            return fallback;
        }
        try {
            return v.startsWith("#") ? Color.parseColor(v) : (int) Long.parseLong(v);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
