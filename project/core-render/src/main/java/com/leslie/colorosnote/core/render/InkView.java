package com.leslie.colorosnote.core.render;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Matrix;
import android.graphics.Region;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.leslie.colorosnote.core.common.AppExecutors;
import com.leslie.colorosnote.core.common.AppLogger;
import com.leslie.colorosnote.core.ink.BrushEngine;
import com.leslie.colorosnote.core.ink.LiveOutlineBuilder;
import com.leslie.colorosnote.core.ink.MotionPredictor;
import com.leslie.colorosnote.core.ink.PalmRejectionPolicy;
import com.leslie.colorosnote.core.ink.StrokeBuilder;
import com.leslie.colorosnote.core.ink.StrokeGeometry;
import com.leslie.colorosnote.core.model.BrushPreset;
import com.leslie.colorosnote.core.model.Page;
import com.leslie.colorosnote.core.model.Stroke;
import com.leslie.colorosnote.core.model.StrokePoint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Low-latency ink surface (requirement 5.3). Composition in onDraw:
 * background template -> completed-strokes offscreen bitmap -> in-progress
 * stroke (plus predicted points). The completed layer is cached per page in
 * an LRU of offscreen bitmaps; the live stroke is drawn as a variable-width
 * mesh straight onto the View's hardware-accelerated canvas.
 *
 * <p>Main thread does zero IO: the initial per-page bitmap render runs on the
 * compute executor; incremental stroke commits are plain Canvas draws.
 *
 * <p>Re-renders (erase/undo) never blank the layer: the new bitmap is rendered
 * off-thread from a stroke-list snapshot and swapped in atomically when ready
 * (v1.1.7 — previously the old bitmap was dropped first, so erasing visibly
 * flashed the page empty on every refresh).
 *
 * <p>Bake resolution is zoom-adaptive (v1.4.12, see {@link RenderScalePolicy}):
 * the layer starts at 2x and is re-baked sharper (debounced 300ms) when the
 * user pinches more than 1.25x past the baked scale — a fixed 2x bitmap turned
 * ink into stretched mush at deep zoom.
 *
 * <p>Past the full-page bake cap (~4.58x for A4) the completed layer switches
 * to a viewport-window bitmap (v1.4.13): the visible page region, expanded 15%
 * per side, is re-rendered off-thread at device resolution and drawn on top of
 * the (now softer) full-page base. Pan/zoom re-renders it debounced; zooming
 * back under the cap discards it. Memory is bounded (~viewPx·1.3²·4B ≈ 27MB),
 * independent of zoom. Since v1.4.15 the window engages the instant the zoom
 * passes the baked scale (entry ×1.0, exit ×0.98) — the former ×1.25 entry
 * left a blur band between the cap and 1.25x past it. Also since v1.4.15,
 * while the window holds every stroke of the page, the soft base bitmap is
 * clipped OUT of the window rect (Region.Op.DIFFERENCE): the stretched base
 * copy is rasterized on a coarser pixel grid, and its sub-pixel offset peeked
 * out around the sharp window copy as a gray halo that grew with zoom.
 */
public class InkView extends View {

    /** Receives finished strokes so the host can persist them / push undo. */
    public interface StrokeListener {
        void onStrokeFinished(Stroke stroke);
    }

    private static final String TAG = "InkView";
    /** LRU byte budget for completed-stroke bitmaps (~3 A4 pages @2x). */
    private static final long BITMAP_BUDGET_BYTES = 48L * 1024 * 1024;
    /** Debounce before re-baking the completed layer at a higher zoom. */
    private static final long REBAKE_DEBOUNCE_MS = 300L;

    private final ViewportTransform viewport = new ViewportTransform();
    private final PageRenderer renderer = new PageRenderer();
    /**
     * Off-thread stroke rasterization (v1.4.15): the compute executor is a
     * fixed pool sized to CPU cores, so a full-page re-bake and a viewport-
     * window render run CONCURRENTLY. PageRenderer keeps mutable scratch
     * state (strokePath/strokePaint in drawMesh), and two interleaved
     * drawStrokes calls corrupted each other's paths — the giant wedges /
     * striped blobs that showed up after undo at deep zoom (also the
     * unexplained v1.4.13/14 polygon). All bitmap rendering therefore goes
     * through this dedicated instance, serialized by {@link #offscreenLock};
     * {@link #renderer} stays on the UI thread (background/objects in onDraw).
     * BrushEngine.computeMesh itself is static and stateless.
     */
    private final PageRenderer offscreenRenderer = new PageRenderer();
    private final Object offscreenLock = new Object();
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint workspacePaint = new Paint();

    private Page page;
    private StrokeBuilder strokeBuilder;
    private StrokeListener strokeListener;
    private PalmRejectionPolicy palmPolicy = PalmRejectionPolicy.FINGER_AND_STYLUS;
    private BrushPreset currentBrush = BrushPreset.fountainPen();

    /**
     * Live stroke render state (v1.4.10): the outline is built incrementally
     * as points arrive ({@link LiveOutlineBuilder}) instead of re-meshing the
     * whole stroke every frame. livePath accumulates committed segment quads
     * (plus the round start cap, v1.4.17); tailPath holds the transient
     * newest segment and the round tail cap; predictedPath is the cheap
     * prediction strip rebuilt per frame from a handful of points.
     */
    private final BrushEngine liveBrushEngine = new BrushEngine();
    private final LiveOutlineBuilder liveOutline = new LiveOutlineBuilder();
    private final LiveOutlineBuilder predictedOutline = new LiveOutlineBuilder();
    private final Path livePath = new Path();
    private final Path tailPath = new Path();
    private final Path predictedPath = new Path();
    /**
     * v2.2.0 translucent pens: offscreen layer paint carrying the brush
     * alpha, so the body blit and the tail rasterize opaque inside one
     * saveLayer and get alpha-composited exactly once (no double darkening
     * at overlaps, no per-frame copy of the cumulative livePath).
     */
    private final Paint translucentLayerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /**
     * v2.1.4 fountain-pen latency: the committed live geometry used to be
     * re-rasterized as one giant cumulative {@link #livePath} on every frame —
     * O(stroke length) rasterization cost that fell behind fast handwriting
     * at extreme zoom. New quads/discs now go through {@link #liveBatchPath}
     * and are drawn once into {@link #liveBaked} (screen-space bitmap); each
     * frame blits that bitmap plus the transient tail — O(1) regardless of
     * stroke length. {@link #livePath} remains the rebake source when the
     * viewport moves mid-stroke.
     */
    private final Path liveBatchPath = new Path();
    private Bitmap liveBaked;
    private final Canvas liveBakedCanvas = new Canvas();
    private final Matrix liveBakedInverse = new Matrix();
    /** Viewport matrix at bake time vs now, compared per batch (no alloc). */
    private final float[] liveBakedMatrixValues = new float[9];
    private final float[] liveMatrixScratch = new float[9];
    private boolean liveBakedValid;
    /**
     * v2.2.1: page-space union of the current stroke's samples (width-padded,
     * seeded by StrokeBuilder's per-batch dirty bounds). Bounds the
     * translucent pen's per-frame offscreen layer to the ink instead of the
     * whole clip, cutting the GPU layer size dramatically for local strokes.
     */
    private final RectF liveLayerBoundsPage = new RectF();
    private boolean liveLayerBoundsValid;
    private final Paint livePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] liveStyleOut = new float[4];
    private final float[] liveQuadOut = new float[8];
    /** Safe segment-local replacement for possibly crossed tangent quads. */
    private final float[] safeQuadOut = new float[8];
    private final float[] liveJoinOut = new float[6];
    private boolean hasLiveSegmentDirection;
    private float livePreviousDx;
    private float livePreviousDy;
    private int liveQuadsDrawn;
    /** Number of committed sagitta-cover discs already appended (deep zoom
     *  live under-tessellation would otherwise show a blank outer crescent on
     *  every turn — see LiveOutlineBuilder#getMidpoints). */
    private int liveMidpointsDrawn;
    private float liveOpacity = 1f;

    /** Eraser cursor overlay (screen-space circle, v1.1.7). */
    private final Paint eraserCursorFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint eraserCursorStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float eraserCursorX;
    private float eraserCursorY;
    private float eraserCursorRadius;
    private boolean eraserCursorVisible;

    /** A completed-stroke bitmap plus the resolution it was rendered at. */
    private static final class BakedPage {
        final Bitmap bitmap;
        final float scale;
        int strokeCount;

        BakedPage(Bitmap bitmap, float scale, int strokeCount) {
            this.bitmap = bitmap;
            this.scale = scale;
            this.strokeCount = strokeCount;
        }
    }

    /** Completed-stroke bitmaps, keyed by page id (LRU, byte-budgeted). */
    private final LinkedHashMap<String, BakedPage> pageBitmaps =
            new LinkedHashMap<String, BakedPage>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, BakedPage> eldest) {
                    if (cacheBytes > BITMAP_BUDGET_BYTES) {
                        cacheBytes -= byteSize(eldest.getValue().bitmap);
                        eldest.getValue().bitmap.recycle();
                        return true;
                    }
                    return false;
                }
            };
    private long cacheBytes;

    /** Debounced zoom re-bake (v1.4.12); fires on the UI thread. */
    private boolean rebakePending;
    private final Runnable rebakeRunnable = () -> {
        rebakePending = false;
        if (needsSharperBake() && page != null) {
            requestRender(page);
        }
    };

    /**
     * Viewport-window layer (v1.4.13): past the full-page bake cap, the
     * visible page region is re-rendered at device resolution. Cached for the
     * current page only and accounted separately from the page LRU.
     *
     * <p>{@code strokeCount} tracks how many of the page's strokes the bitmap
     * holds (render snapshot size + later direct commits). onDraw clips the
     * soft full-page base OUT of the window rect only while the count matches
     * the live stroke list (v1.4.15): otherwise the stretched base's half-pixel
     * rasterization offset peeks out around the sharp window copy as a gray
     * halo that grows with zoom (~8px at 74x — the "deep zoom still blurry"
     * complaint). While a render/erase/undo is catching up the count differs
     * and the base stays drawn, so fresh ink never disappears mid-flight.
     */
    private static final class WindowLayer {
        final Bitmap bitmap;
        final RenderScalePolicy.WindowSpec spec;
        int strokeCount;

        WindowLayer(Bitmap bitmap, RenderScalePolicy.WindowSpec spec,
                    int strokeCount) {
            this.bitmap = bitmap;
            this.spec = spec;
            this.strokeCount = strokeCount;
        }
    }

    private WindowLayer windowLayer;
    private String windowPageId;
    /** Debounce posted; the runnable re-evaluates against the live viewport. */
    private boolean windowScheduled;
    /** At most one window render runs; requests during it coalesce to dirty. */
    private boolean windowRenderInFlight;
    /** Spec of the render currently in flight; null when none is running. */
    private RenderScalePolicy.WindowSpec windowRenderingSpec;
    private boolean windowDirty;
    private final Runnable windowRunnable = () -> {
        windowScheduled = false;
        refreshWindowIfStale();
    };

    /**
     * Off-thread re-render bookkeeping, guarded by itself: a page with a render
     * in flight gets at most one follow-up render coalesced (erase refreshes
     * arrive every ~60ms, far faster than a full page render).
     */
    private final Set<String> renderInFlight = new HashSet<>();
    private final Set<String> renderPendingAgain = new HashSet<>();

    public InkView(Context context) {
        super(context);
        init();
    }

    public InkView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public InkView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        // No LAYER_TYPE_HARDWARE (removed in v1.4.11): the fullscreen layer
        // texture cost an extra full-screen blit every frame while inking.
        // HWUI render nodes already avoid re-rasterizing this view when the
        // parent scrolls, and the live stroke paints plain SRC_OVER (no blend
        // mode needing layer isolation), so the layer only added overhead.
        workspacePaint.setColor(0xFFECEFF1); // workspace gray around the page
        workspacePaint.setStyle(Paint.Style.FILL);
        livePaint.setStyle(Paint.Style.FILL);
        float dp = getResources().getDisplayMetrics().density;
        eraserCursorFill.setStyle(Paint.Style.FILL);
        eraserCursorFill.setColor(0x59FFFFFF); // translucent milk-white fill
        eraserCursorStroke.setStyle(Paint.Style.STROKE);
        eraserCursorStroke.setStrokeWidth(1.5f * dp);
        eraserCursorStroke.setColor(0xFF8A8F98); // soft gray outline
        rebuildStrokeBuilder();
    }

    // ------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------

    public void setStrokeListener(StrokeListener listener) {
        strokeListener = listener;
    }

    public void setBrush(BrushPreset brush) {
        currentBrush = brush;
        if (strokeBuilder != null) {
            strokeBuilder.setBrush(brush);
        }
    }

    public void setPalmRejectionPolicy(PalmRejectionPolicy policy) {
        palmPolicy = policy;
        rebuildStrokeBuilder();
    }

    public StrokeBuilder getStrokeBuilder() {
        return strokeBuilder;
    }

    public ViewportTransform getViewport() {
        return viewport;
    }

    public PageRenderer getRenderer() {
        return renderer;
    }

    /** Sets the page and kicks off the (background) completed-layer render. */
    public void setPage(Page newPage) {
        page = newPage;
        livePath.reset();
        liveBakedValid = false;
        if (newPage == null || !newPage.id.equals(windowPageId)) {
            discardWindow();
        }
        if (newPage != null) {
            ensurePageBitmap(newPage);
        }
        invalidate();
    }

    public Page getPage() {
        return page;
    }

    /**
     * Re-renders the completed layer, e.g. after undo/redo or erasing. The old
     * bitmap stays on screen until the new one is ready (no blank flash).
     */
    public void refreshCompletedLayer() {
        if (page == null) {
            return;
        }
        requestRender(page);
        // The window layer caches the same strokes (v1.4.13): refresh in step.
        if (windowLayer != null && page.id.equals(windowPageId)) {
            startWindowRender();
        }
        invalidate();
    }

    // ------------------------------------------------------------------
    // Eraser cursor overlay
    // ------------------------------------------------------------------

    /** Shows/moves the eraser cursor circle (view coordinates, radius in px). */
    public void showEraserCursor(float viewX, float viewY, float radiusPx) {
        eraserCursorX = viewX;
        eraserCursorY = viewY;
        eraserCursorRadius = Math.max(0f, radiusPx);
        eraserCursorVisible = true;
        invalidate();
    }

    public void hideEraserCursor() {
        if (eraserCursorVisible) {
            eraserCursorVisible = false;
            invalidate();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        liveBakedValid = false;
        // Only auto-fit before the user takes over the viewport. Re-fitting on
        // every resize (e.g. immersive bars fading) would reset the user's
        // deliberate zoom/pan and shift the ink mid-stroke.
        if (page != null && w > 0 && h > 0 && !viewport.hasUserTransform()) {
            viewport.fitPage(page.width, page.height, w, h, 24f);
        }
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Ask the system to skip MotionEvent batching for stylus streams
        // (v1.1.8): samples arrive as they are produced instead of queued
        // per frame — lower latency and less velocity jitter at 240Hz.
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                && event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
            requestUnbufferedDispatch(event);
        }
        return strokeBuilder != null && strokeBuilder.onTouchEvent(event);
    }

    private void rebuildStrokeBuilder() {
        strokeBuilder = new StrokeBuilder(
                (viewX, viewY, out) -> viewport.screenToPage(viewX, viewY, out),
                palmPolicy,
                new StrokeBuilder.Listener() {
                    @Override
                    public void onStrokeStarted() {
                        liveOpacity = currentBrush.opacity;
                        livePaint.setColor(currentBrush.color);
                        livePath.reset();
                        tailPath.reset();
                        predictedPath.reset();
                        liveBrushEngine.beginStroke(currentBrush);
                        liveOutline.reset();
                        liveQuadsDrawn = 0;
                        liveMidpointsDrawn = 0;
                        hasLiveSegmentDirection = false;
                        liveLayerBoundsValid = false;
                        setupLiveBake();
                        // The ACTION_DOWN sample is already in the builder's
                        // list (added before this callback); feed it so the
                        // streaming style state matches the full re-mesh.
                        for (StrokePoint p : strokeBuilder.getPoints()) {
                            addLivePoint(p);
                        }
                    }

                    @Override
                    public void onPointsAdded(List<StrokePoint> newPoints, float[] dirtyBounds) {
                        for (StrokePoint p : newPoints) {
                            addLivePoint(p);
                        }
                        rebuildTailPath();
                        // v2.2.1: accumulate the stroke's bounds for the
                        // translucent pen's per-frame offscreen layer.
                        if (liveLayerBoundsValid) {
                            liveLayerBoundsPage.union(dirtyBounds[0], dirtyBounds[1],
                                    dirtyBounds[2], dirtyBounds[3]);
                        } else {
                            liveLayerBoundsPage.set(dirtyBounds[0], dirtyBounds[1],
                                    dirtyBounds[2], dirtyBounds[3]);
                            liveLayerBoundsValid = true;
                        }
                        invalidatePageRect(dirtyBounds);
                    }

                    @Override
                    public void onStrokeFinished(Stroke stroke) {
                        // v2.2.1: commit BEFORE resetting the live state — the
                        // fast path folds the transient tail into the bake and
                        // blits it, so the tail must still exist here.
                        commitStrokeToBitmap(stroke);
                        livePath.reset();
                        tailPath.reset();
                        predictedPath.reset();
                        if (strokeListener != null) {
                            strokeListener.onStrokeFinished(stroke);
                        }
                        invalidatePageRect(stroke.computeBounds());
                    }

                    @Override
                    public void onStrokeCancelled() {
                        livePath.reset();
                        tailPath.reset();
                        predictedPath.reset();
                        liveBakedValid = false;
                        invalidate();
                    }
                });
        strokeBuilder.setBrush(currentBrush);
        strokeBuilder.setMotionPredictor(MotionPredictor.create(this));
    }

    /**
     * Styles one new sample via the streaming brush engine and feeds the
     * outline builder; newly committed quads/discs are appended to
     * {@link #liveBatchPath}, merged into {@link #livePath} (the rebake
     * source) and drawn once into {@link #liveBaked} so frames stay O(1)
     * (see the liveBatchPath doc).
     */
    private void addLivePoint(StrokePoint p) {
        liveOutline.setPixelsPerPageUnit(LiveInkQualityPolicy.tessellationScale(
                viewport.getScale(), p.velocityX, p.velocityY));
        liveBrushEngine.addPoint(p, liveStyleOut);
        liveOutline.addPoint(liveStyleOut[0], liveStyleOut[1], liveStyleOut[2]);
        ensureLiveBakeFresh();
        int fromQuad = liveQuadsDrawn;
        float[] quads = liveOutline.getQuads();
        int count = liveOutline.getQuadCount();
        liveBatchPath.reset();
        for (int i = fromQuad; i < count; i++) {
            appendStableQuad(liveBatchPath, quads, i * 8);
        }
        liveQuadsDrawn = count;
        // Sagitta-cover discs: cover the outer bulge a straight sub-quad cuts
        // on deep-zoom turns (see LiveOutlineBuilder#getMidpoints).
        float[] mids = liveOutline.getMidpoints();
        int midCount = liveOutline.getMidpointCount();
        for (int i = liveMidpointsDrawn; i < midCount; i++) {
            int o = i * 3;
            addRoundDisc(liveBatchPath, mids[o], mids[o + 1], mids[o + 2]);
        }
        liveMidpointsDrawn = midCount;
        livePath.addPath(liveBatchPath);
        if (liveBakedValid) {
            // One rasterization of just this batch; onDraw blits the bitmap.
            liveBakedCanvas.drawPath(liveBatchPath, livePaint);
        }
    }

    /**
     * Keeps {@link #liveBaked} aligned with the viewport: reinitializes the
     * bake bitmap (and re-bakes the whole committed outline) when the view
     * size or the viewport matrix changed since the last batch. Normal
     * handwriting never triggers the rebake (no pinch mid-stroke); it only
     * covers the rare viewport-drag-while-writing case.
     */
    private void ensureLiveBakeFresh() {
        if (page == null) {
            liveBakedValid = false;
            return;
        }
        viewport.getPageToScreen().getValues(liveMatrixScratch);
        if (liveBakedValid && liveBaked != null
                && liveBaked.getWidth() == getWidth()
                && liveBaked.getHeight() == getHeight()
                && Arrays.equals(liveMatrixScratch, liveBakedMatrixValues)) {
            return;
        }
        setupLiveBake();
        if (liveBakedValid && liveQuadsDrawn > 0) {
            liveBakedCanvas.drawPath(livePath, livePaint);
        }
    }

    /**
     * Allocates/clears the screen-space live bake bitmap and captures the
     * current viewport (page clip + page-to-screen matrix) onto its canvas.
     */
    private void setupLiveBake() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            liveBakedValid = false;
            return;
        }
        if (liveBaked == null || liveBaked.isRecycled()
                || liveBaked.getWidth() != w || liveBaked.getHeight() != h) {
            if (liveBaked != null) {
                liveBaked.recycle();
            }
            liveBaked = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            liveBakedCanvas.setBitmap(liveBaked);
        }
        liveBaked.eraseColor(Color.TRANSPARENT);
        liveBakedCanvas.setMatrix(null);
        RectF pageScreen = viewport.pageRectToScreen(
                0f, 0f, page.width, page.height);
        liveBakedCanvas.clipRect(pageScreen);
        liveBakedCanvas.concat(viewport.getPageToScreen());
        viewport.getPageToScreen().getValues(liveBakedMatrixValues);
        liveBakedValid = true;
    }

    /**
     * Rebuilds the transient tail strip (v2.2.0): the newest segment
     * spline-subdivided with cover discs, tip round cap included.
     */
    private void rebuildTailPath() {
        tailPath.reset();
        // v2.2.0: the tail is a spline-subdivided strip with cover discs, so
        // the pen tip hugs the curve even though the newest segment's end
        // normal is not final yet (the old single straight tail quad cut
        // bends by their sagitta and read as a tear right at the pen tip).
        float[] tq = liveOutline.getTailQuads();
        int tqCount = liveOutline.getTailQuadCount();
        for (int i = 0; i < tqCount; i++) {
            appendRoundQuad(tailPath, tq, i * 8, i == tqCount - 1);
        }
        float[] td = liveOutline.getTailDiscs();
        int tdCount = liveOutline.getTailDiscCount();
        for (int i = 0; i < tdCount; i++) {
            int o = i * 3;
            addRoundDisc(tailPath, td[o], td[o + 1], td[o + 2]);
        }
        if (tqCount == 0 && liveOutline.tailQuad(liveQuadOut)) {
            // Fallback for the single-sample edge case.
            appendRoundQuad(tailPath, liveQuadOut, 0, true);
        }
    }

    /**
     * Rebuilds the prediction strip: last styled point + predicted samples at
     * the last styled width. A handful of points per frame — far cheaper than
     * the old per-frame full computeMesh on the predicted list.
     */
    private void rebuildPredictedPath(List<StrokePoint> predicted) {
        predictedPath.reset();
        if (predicted.isEmpty() || !liveOutline.getLastPoint(liveStyleOut)) {
            return;
        }
        predictedOutline.reset();
        // Prediction is replaced every frame and must remain exceptionally
        // cheap; assume fast writing for its transient tessellation budget.
        predictedOutline.setPixelsPerPageUnit(LiveInkQualityPolicy.tessellationScale(
                viewport.getScale(), 1800f / Math.max(1f, viewport.getScale()), 0f));
        float w = liveStyleOut[2];
        predictedOutline.addPoint(liveStyleOut[0], liveStyleOut[1], w);
        for (StrokePoint p : predicted) {
            predictedOutline.addPoint(p.x, p.y, w);
        }
        float[] quads = predictedOutline.getQuads();
        for (int i = 0; i < predictedOutline.getQuadCount(); i++) {
            appendRoundQuad(predictedPath, quads, i * 8, false);
        }
        if (predictedOutline.tailQuad(liveQuadOut)) {
            appendRoundQuad(predictedPath, liveQuadOut, 0, true);
        }
    }

    /** Appends one quad {l0, l1, r1, r0} at the given flat offset to a path. */
    private void appendStableQuad(Path path, float[] quads, int o) {
        float x0 = (quads[o] + quads[o + 6]) * 0.5f;
        float y0 = (quads[o + 1] + quads[o + 7]) * 0.5f;
        float x1 = (quads[o + 2] + quads[o + 4]) * 0.5f;
        float y1 = (quads[o + 3] + quads[o + 5]) * 0.5f;
        float w0 = (float) Math.hypot(
                quads[o] - quads[o + 6], quads[o + 1] - quads[o + 7]);
        float w1 = (float) Math.hypot(
                quads[o + 2] - quads[o + 4], quads[o + 3] - quads[o + 5]);
        float dx = x1 - x0;
        float dy = y1 - y0;
        if (!StrokeGeometry.segmentQuad(x0, y0, w0, x1, y1, w1, safeQuadOut)) {
            return;
        }
        appendPolygon(path, safeQuadOut, 4);
        if (!hasLiveSegmentDirection) {
            addRoundDisc(path, x0, y0, w0);
        } else {
            int kind = StrokeGeometry.joinTriangle(livePreviousDx, livePreviousDy,
                    dx, dy, x0, y0, w0, liveJoinOut);
            if (kind == StrokeGeometry.JOIN_TRIANGLE) {
                appendPolygon(path, liveJoinOut, 3);
            } else if (kind == StrokeGeometry.JOIN_ROUND) {
                addRoundDisc(path, x0, y0, w0);
            }
        }
        hasLiveSegmentDirection = true;
        livePreviousDx = dx;
        livePreviousDy = dy;
    }

    private void appendRoundQuad(Path path, float[] quads, int o, boolean addEndCap) {
        // Recover the two center/radius pairs from the outline chord, then
        // rebuild the body in the segment's own frame. Averaged knot normals
        // can flip at a tight turn and turn the original quad into a bow-tie.
        float x0 = (quads[o] + quads[o + 6]) * 0.5f;
        float y0 = (quads[o + 1] + quads[o + 7]) * 0.5f;
        float x1 = (quads[o + 2] + quads[o + 4]) * 0.5f;
        float y1 = (quads[o + 3] + quads[o + 5]) * 0.5f;
        float w0 = (float) Math.hypot(
                quads[o] - quads[o + 6], quads[o + 1] - quads[o + 7]);
        float w1 = (float) Math.hypot(
                quads[o + 2] - quads[o + 4], quads[o + 3] - quads[o + 5]);
        if (StrokeGeometry.segmentQuad(x0, y0, w0, x1, y1, w1, safeQuadOut)) {
            appendPolygon(path, safeQuadOut, 4);
        }
        // Same-direction subpaths under WINDING fill form a true union. These
        // discs close every outside-corner gap and cannot create white holes.
        addRoundDisc(path, x0, y0, w0);
        // Stable consecutive quads add the shared knot as the next quad's
        // start circle, so emitting every end circle duplicated nearly half
        // the live Path. Only transient tails need their final round cap.
        if (addEndCap) {
            addRoundDisc(path, x1, y1, w1);
        }
    }

    private static void addRoundDisc(Path path, float x, float y, float width) {
        float radius = Math.max(0f, width) * 0.5f;
        if (radius > 0f && Float.isFinite(x) && Float.isFinite(y)) {
            path.addCircle(x, y, radius, Path.Direction.CCW);
        }
    }

    /** Appends a flat xy-pair polygon (round cap) as a closed sub-path. */
    private static void appendPolygon(Path path, float[] xy, int points) {
        path.moveTo(xy[0], xy[1]);
        for (int i = 1; i < points; i++) {
            path.lineTo(xy[i * 2], xy[i * 2 + 1]);
        }
        path.close();
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawRect(0f, 0f, getWidth(), getHeight(), workspacePaint);
        if (page == null) {
            return;
        }
        canvas.save();
        canvas.concat(viewport.getPageToScreen());
        canvas.clipRect(0f, 0f, page.width, page.height);

        // 1. Background template.
        renderer.drawBackground(canvas, page);

        // 2. Completed strokes from the offscreen bitmap.
        WindowLayer win = windowLayer;
        boolean winActive = win != null && page.id.equals(windowPageId)
                && !win.bitmap.isRecycled();
        BakedPage completed = pageBitmaps.get(page.id);
        if (completed != null && !completed.bitmap.isRecycled()) {
            if (winActive && win.strokeCount == page.strokes.size()) {
                // The window re-renders every stroke in its rect at device
                // resolution; the stretched base underneath adds nothing but
                // a rasterization-offset halo (v1.4.15), so clip it out.
                canvas.save();
                canvas.clipRect(win.spec.left, win.spec.top,
                        win.spec.right, win.spec.bottom, Region.Op.DIFFERENCE);
                canvas.drawBitmap(completed.bitmap, null,
                        new RectF(0f, 0f, page.width, page.height), bitmapPaint);
                canvas.restore();
            } else {
                canvas.drawBitmap(completed.bitmap, null,
                        new RectF(0f, 0f, page.width, page.height), bitmapPaint);
            }
        }

        // 2.1. Viewport window (v1.4.13): past the bake cap, the visible
        //      region re-rendered at device resolution, over the softer base.
        if (winActive) {
            canvas.drawBitmap(win.bitmap, null,
                    new RectF(win.spec.left, win.spec.top,
                            win.spec.right, win.spec.bottom), bitmapPaint);
        }

        // 2.5. Page objects (text boxes, shapes, tape, stickers, images).
        //      Objects are mutable (move/edit/recolor), so they are painted
        //      live on top of the stroke bitmap instead of being baked in.
        if (!page.objects.isEmpty()) {
            renderer.drawObjects(canvas, page.objects);
        }

        // 3. In-progress stroke: persistent incremental outline (committed
        //    quads) + transient tail strip. Nothing is re-meshed per frame.
        if (strokeBuilder != null && strokeBuilder.isStroking()) {
            if (liveOpacity >= 0.995f) {
                // v2.1.4: the committed outline is blitted from the bake
                // bitmap (O(1), see liveBatchPath); only the transient tail
                // is rasterized per frame. Falls back to the cumulative path
                // when the bake is unavailable (no view size yet).
                livePaint.setAlpha(255);
                canvas.drawPath(tailPath, livePaint);
                if (liveBakedValid && liveBaked != null
                        && !liveBaked.isRecycled()) {
                    liveBakedInverse.set(viewport.getPageToScreen());
                    liveBakedInverse.invert(liveBakedInverse);
                    canvas.save();
                    canvas.concat(liveBakedInverse);
                    canvas.drawBitmap(liveBaked, 0f, 0f, null);
                    canvas.restore();
                } else {
                    canvas.drawPath(livePath, livePaint);
                }
            } else {
                // v2.2.0: translucent pens (pencil/highlighter) compose the
                // body blit and the tail inside one offscreen layer whose
                // paint carries the brush alpha — the shapes rasterize
                // opaque and are alpha-composited ONCE, so overlaps never
                // darken. The old path copied and re-rasterized the entire
                // cumulative livePath every frame (wide translucent pens
                // stalled at deep zoom). v2.2.1 bounds the layer to the
                // stroke's ink (page-space union, clamped to the clip)
                // instead of the whole viewport.
                translucentLayerPaint.setAlpha(
                        (int) (255f * liveOpacity + 0.5f));
                RectF layerBounds = null;
                if (liveLayerBoundsValid) {
                    RectF s = viewport.pageRectToScreen(
                            liveLayerBoundsPage.left, liveLayerBoundsPage.top,
                            liveLayerBoundsPage.right, liveLayerBoundsPage.bottom);
                    RectF clip = new RectF(canvas.getClipBounds());
                    layerBounds = new RectF(
                            Math.max(s.left, clip.left) - 4f,
                            Math.max(s.top, clip.top) - 4f,
                            Math.min(s.right, clip.right) + 4f,
                            Math.min(s.bottom, clip.bottom) + 4f);
                }
                int saveCount = canvas.saveLayer(layerBounds, translucentLayerPaint);
                if (liveBakedValid && liveBaked != null
                        && !liveBaked.isRecycled()) {
                    liveBakedInverse.set(viewport.getPageToScreen());
                    liveBakedInverse.invert(liveBakedInverse);
                    canvas.save();
                    canvas.concat(liveBakedInverse);
                    canvas.drawBitmap(liveBaked, 0f, 0f, null);
                    canvas.restore();
                } else {
                    livePaint.setAlpha(255);
                    canvas.drawPath(livePath, livePaint);
                }
                livePaint.setAlpha(255);
                canvas.drawPath(tailPath, livePaint);
                canvas.restoreToCount(saveCount);
            }

            // 4. Predicted points (low latency), drawn slightly translucent.
            List<StrokePoint> predicted = strokeBuilder.getPredictedPoints();
            if (!predicted.isEmpty()) {
                rebuildPredictedPath(predicted);
                livePaint.setAlpha((int) (120f * liveOpacity + 0.5f));
                canvas.drawPath(predictedPath, livePaint);
            }
        }
        canvas.restore();

        // Zoomed past the baked resolution? Debounce a sharper re-bake.
        scheduleRebakeIfNeeded();

        // Past the bake cap? Debounce a device-resolution window re-render.
        scheduleWindowIfNeeded();

        // 5. Eraser cursor in screen space (not clipped to the page).
        if (eraserCursorVisible && eraserCursorRadius > 0f) {
            canvas.drawCircle(eraserCursorX, eraserCursorY, eraserCursorRadius,
                    eraserCursorFill);
            canvas.drawCircle(eraserCursorX, eraserCursorY, eraserCursorRadius,
                    eraserCursorStroke);
        }
    }

    /** Maps a page-space dirty rect to screen space and invalidates it. */
    private void invalidatePageRect(float[] pageBounds) {
        RectF screen = viewport.pageRectToScreen(
                pageBounds[0], pageBounds[1], pageBounds[2], pageBounds[3]);
        Rect r = new Rect((int) Math.floor(screen.left) - 2,
                (int) Math.floor(screen.top) - 2,
                (int) Math.ceil(screen.right) + 2,
                (int) Math.ceil(screen.bottom) + 2);
        invalidate(r);
    }

    /**
     * Re-bakes the completed layer sharper after deep zoom (v1.4.12), debounced
     * so a pinch gesture triggers at most one render once the fingers settle.
     * Zooming out never re-bakes down: the extra sharpness is harmless and the
     * LRU reclaims the memory when other pages need it.
     */
    private void scheduleRebakeIfNeeded() {
        if (rebakePending || !needsSharperBake()) {
            return;
        }
        rebakePending = true;
        postDelayed(rebakeRunnable, REBAKE_DEBOUNCE_MS);
    }

    private boolean needsSharperBake() {
        if (page == null) {
            return false;
        }
        BakedPage baked = pageBitmaps.get(page.id);
        if (baked == null || baked.bitmap.isRecycled()) {
            return false; // initial render already running, or nothing to beat
        }
        return RenderScalePolicy.needsRebake(
                viewport.getScale(), baked.scale, page.width, page.height);
    }

    // ------------------------------------------------------------------
    // Viewport-window layer (v1.4.13)
    // ------------------------------------------------------------------

    /**
     * Keeps the window layer in sync with the viewport, called at the end of
     * every onDraw: engages past the bake cap (with hysteresis), schedules a
     * debounced re-render when the window no longer covers the view, and
     * discards the window once the zoom is back under the cap.
     */
    private void scheduleWindowIfNeeded() {
        if (page == null) {
            discardWindow();
            return;
        }
        BakedPage baked = pageBitmaps.get(page.id);
        boolean want = baked != null && !baked.bitmap.isRecycled()
                && RenderScalePolicy.shouldUseWindow(
                        viewport.getScale(), baked.scale, page.width, page.height,
                        windowLayer != null && page.id.equals(windowPageId));
        if (!want) {
            discardWindow();
            return;
        }
        if (windowScheduled) {
            return;
        }
        if (windowRenderInFlight) {
            // Coalesce only when the render in flight will land STALE (the
            // viewport moved after it started). Flagging dirty on every
            // frame self-sustained an infinite re-render loop (v1.4.14):
            // swap -> invalidate -> onDraw lands mid-render -> dirty ->
            // swap re-renders — ~50 fresh window bitmaps per second, each
            // ~21MB, churning the GPU until the canvas shows black/garbage
            // and input starves. A still-covering in-flight render settles
            // fresh on its own.
            if (windowRenderingSpec == null || !specCoversView(windowRenderingSpec)) {
                windowDirty = true;
            }
            return;
        }
        if (isWindowFresh()) {
            return;
        }
        windowScheduled = true;
        postDelayed(windowRunnable, REBAKE_DEBOUNCE_MS);
    }

    /** True when the cached window still covers the view at the current zoom. */
    private boolean isWindowFresh() {
        WindowLayer win = windowLayer;
        if (win == null || win.bitmap.isRecycled()
                || page == null || !page.id.equals(windowPageId)) {
            return false;
        }
        return specCoversView(win.spec);
    }

    /** True when the given spec still covers the visible region at the current zoom. */
    private boolean specCoversView(RenderScalePolicy.WindowSpec spec) {
        if (page == null) {
            return false;
        }
        RectF vis = viewport.visiblePageBounds(getWidth(), getHeight());
        return RenderScalePolicy.windowCovers(spec, page.width, page.height,
                vis.left, vis.top, vis.right, vis.bottom, viewport.getScale());
    }

    /** Debounce callback: re-checks the live viewport, then renders if stale. */
    private void refreshWindowIfStale() {
        if (page == null) {
            discardWindow();
            return;
        }
        BakedPage baked = pageBitmaps.get(page.id);
        if (baked == null || baked.bitmap.isRecycled()
                || !RenderScalePolicy.shouldUseWindow(
                        viewport.getScale(), baked.scale,
                        page.width, page.height, true)) {
            discardWindow();
            invalidate();
            return;
        }
        if (!isWindowFresh()) {
            startWindowRender();
        }
    }

    /**
     * Renders the visible region into a fresh window bitmap off-thread and
     * swaps it in atomically (same no-flash discipline as the base layer).
     * At most one render runs; calls during it coalesce via windowDirty.
     */
    private void startWindowRender() {
        if (page == null) {
            return;
        }
        if (windowRenderInFlight) {
            windowDirty = true;
            return;
        }
        windowRenderInFlight = true;
        final Page target = page;
        final String id = target.id;
        // Snapshot on the UI thread: erasing mutates page.strokes live.
        final List<Stroke> snapshot = new ArrayList<>(target.strokes);
        RectF vis = viewport.visiblePageBounds(getWidth(), getHeight());
        final RenderScalePolicy.WindowSpec spec = RenderScalePolicy.computeWindow(
                viewport.getScale(), target.width, target.height,
                vis.left, vis.top, vis.right, vis.bottom);
        windowRenderingSpec = spec;
        final Bitmap bmp = Bitmap.createBitmap(
                spec.pixelW, spec.pixelH, Bitmap.Config.ARGB_8888);
        AppExecutors.get().compute().execute(() -> {
            // createBitmap does not guarantee zeroed pixels: right after a
            // large bitmap recycle (window discard, LRU evict) the allocator
            // can hand back the dirty block, and stale ink then leaks through
            // the transparent regions (v1.4.15 "ghost strokes" repro).
            bmp.eraseColor(Color.TRANSPARENT);
            Canvas c = new Canvas(bmp);
            c.scale(spec.scale, spec.scale);
            c.translate(-spec.left, -spec.top);
            c.clipRect(spec.left, spec.top, spec.right, spec.bottom);
            // Serialized: offscreenRenderer's scratch Path/Paint are not
            // thread-safe (see the field comment).
            synchronized (offscreenLock) {
                offscreenRenderer.drawStrokes(c, snapshot);
            }
            post(() -> swapWindowBitmap(target, id, snapshot, bmp, spec));
        });
    }

    private void swapWindowBitmap(Page target, String id, List<Stroke> snapshot,
                                  Bitmap bmp, RenderScalePolicy.WindowSpec spec) {
        List<Stroke> current = new ArrayList<>(target.strokes);
        StrokeSnapshotDelta delta = StrokeSnapshotDelta.between(snapshot, current);
        if (delta.appendOnly && !delta.additions.isEmpty()) {
            synchronized (offscreenLock) {
                Canvas c = new Canvas(bmp);
                c.scale(spec.scale, spec.scale);
                c.translate(-spec.left, -spec.top);
                c.clipRect(spec.left, spec.top, spec.right, spec.bottom);
                offscreenRenderer.drawStrokes(c, delta.additions);
            }
        }
        int renderedCount = delta.appendOnly ? current.size() : snapshot.size();
        WindowLayer old = windowLayer;
        windowLayer = new WindowLayer(bmp, spec, renderedCount);
        windowPageId = id;
        if (old != null && old.bitmap != bmp && !old.bitmap.isRecycled()) {
            old.bitmap.recycle();
        }
        windowRenderInFlight = false;
        windowRenderingSpec = null;
        boolean again = windowDirty;
        windowDirty = false;
        // The stroke list changed while rendering (erase/undo mid-render).
        if (!again && !delta.appendOnly) {
            again = true;
        }
        if (page != null && page.id.equals(id)) {
            invalidate();
        }
        if (again && page != null && page.id.equals(id)) {
            BakedPage baked = pageBitmaps.get(id);
            if (baked != null && !baked.bitmap.isRecycled()
                    && RenderScalePolicy.shouldUseWindow(
                            viewport.getScale(), baked.scale,
                            page.width, page.height, true)) {
                startWindowRender();
            }
        }
    }

    /** Recycles the window bitmap and cancels pending window work. */
    private void discardWindow() {
        removeCallbacks(windowRunnable);
        windowScheduled = false;
        windowDirty = false;
        windowRenderingSpec = null;
        WindowLayer win = windowLayer;
        if (win != null) {
            windowLayer = null;
            windowPageId = null;
            if (!win.bitmap.isRecycled()) {
                win.bitmap.recycle();
            }
            invalidate();
        }
    }

    // ------------------------------------------------------------------
    // Completed-stroke bitmap cache
    // ------------------------------------------------------------------

    /** Draws one finished stroke into the page's offscreen bitmap. */
    private void commitStrokeToBitmap(Stroke stroke) {
        BakedPage baked = pageBitmaps.get(page != null ? page.id : null);
        if (baked == null || baked.bitmap.isRecycled()) {
            return; // initial render still running; it includes this stroke
        }
        // v2.2.1 fast path: blit the screen-resolution live bake into the
        // committed layers instead of re-rasterizing the whole stroke — a
        // long wide stroke used to cost tens of ms of full re-meshing on
        // stroke end, hitching exactly when the pen lifts. The bake already
        // holds the whole stroke incrementally (opaque pixels, unioned), the
        // transient tail is folded in first, and a single alpha
        // multiplication applies the brush opacity uniformly.
        if (liveBakedValid && liveBaked != null && !liveBaked.isRecycled()
                && page != null) {
            livePaint.setAlpha(255);
            liveBakedCanvas.drawPath(tailPath, livePaint);
            liveBakedValid = false;
            // screen pixels -> page units.
            Matrix screenToPage = new Matrix(viewport.getPageToScreen());
            screenToPage.invert(screenToPage);
            // screen pixels -> baked-layer pixels.
            Matrix screenToBaked = new Matrix(screenToPage);
            screenToBaked.postScale(baked.scale, baked.scale);
            Paint blit = new Paint(translucentLayerPaint);
            blit.setAlpha((int) (255f * liveOpacity + 0.5f));
            synchronized (offscreenLock) {
                Canvas c = new Canvas(baked.bitmap);
                c.drawBitmap(liveBaked, screenToBaked, blit);
                baked.strokeCount++;
                // Same content into the viewport window (v1.4.13).
                WindowLayer win = windowLayer;
                if (win != null && page.id.equals(windowPageId)
                        && !win.bitmap.isRecycled()) {
                    float[] b = stroke.computeBounds();
                    RenderScalePolicy.WindowSpec s = win.spec;
                    boolean hit = b[2] + 4f >= s.left && b[0] - 4f <= s.right
                            && b[3] + 4f >= s.top && b[1] - 4f <= s.bottom;
                    if (hit) {
                        Canvas wc = new Canvas(win.bitmap);
                        wc.scale(s.scale, s.scale);
                        wc.translate(-s.left, -s.top);
                        wc.concat(screenToPage);
                        wc.drawBitmap(liveBaked, 0f, 0f, blit);
                    }
                }
            }
            // Count the stroke as covered by the window even when it misses
            // the window rect entirely (see the tail comment below).
            WindowLayer committedWin = windowLayer;
            if (committedWin != null && page.id.equals(windowPageId)) {
                committedWin.strokeCount++;
            }
            return;
        }
        liveBakedValid = false;
        // Runs on the UI thread concurrently with the off-thread base/window
        // re-renders: serialize through offscreenLock + offscreenRenderer so
        // nobody shares a renderer's mutable scratch Path/Paint (v1.4.15).
        WindowLayer win = windowLayer;
        synchronized (offscreenLock) {
            Canvas c = new Canvas(baked.bitmap);
            c.scale(baked.scale, baked.scale);
            offscreenRenderer.drawStroke(c, stroke);
            baked.strokeCount++;
            // Also paint into the viewport window (v1.4.13) when the stroke
            // intersects it — otherwise fresh ink would stay soft until the
            // next debounced window refresh. Bounds slack covers stroke width.
            if (win != null && page != null && page.id.equals(windowPageId)
                    && !win.bitmap.isRecycled()) {
                float[] b = stroke.computeBounds();
                RenderScalePolicy.WindowSpec s = win.spec;
                boolean hit = b[2] + 4f >= s.left && b[0] - 4f <= s.right
                        && b[3] + 4f >= s.top && b[1] - 4f <= s.bottom;
                if (hit) {
                    Canvas wc = new Canvas(win.bitmap);
                    wc.scale(s.scale, s.scale);
                    wc.translate(-s.left, -s.top);
                    offscreenRenderer.drawStroke(wc, stroke);
                }
            }
        }
        // Count the stroke as covered by the window even when it misses the
        // window rect entirely (nothing to draw there): the count gates the
        // v1.4.15 base-clip, and a non-intersecting stroke leaves the
        // window's content just as complete.
        if (win != null && page != null && page.id.equals(windowPageId)
                && !win.bitmap.isRecycled()) {
            win.strokeCount++;
        }
    }

    /** Returns or creates the offscreen bitmap; full render runs off-thread. */
    private void ensurePageBitmap(Page target) {
        BakedPage cached = pageBitmaps.get(target.id);
        if (cached != null && !cached.bitmap.isRecycled()) {
            return;
        }
        requestRender(target);
    }

    /**
     * Renders the page's completed layer off-thread into a fresh bitmap and
     * swaps it into the cache atomically. Calls while a render is in flight
     * are coalesced into one follow-up render (erase refresh storm).
     */
    private void requestRender(final Page target) {
        final String id = target.id;
        synchronized (renderInFlight) {
            if (renderInFlight.contains(id)) {
                renderPendingAgain.add(id);
                return;
            }
            renderInFlight.add(id);
        }
        // Snapshot on the UI thread: erasing mutates page.strokes live.
        final List<Stroke> snapshot = new ArrayList<>(target.strokes);
        // Bake resolution (v1.4.12): zoom-adaptive, but never below the
        // resolution already cached — an erase/undo refresh while zoomed in
        // must not downgrade the bitmap back to a blurry one.
        float scale = RenderScalePolicy.initialScale(
                viewport.getScale(), target.width, target.height);
        BakedPage existing = pageBitmaps.get(id);
        if (existing != null && !existing.bitmap.isRecycled()) {
            scale = Math.max(scale, existing.scale);
        }
        final float bakeScale = scale;
        int w = Math.max(1, Math.round(target.width * bakeScale));
        int h = Math.max(1, Math.round(target.height * bakeScale));
        final Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        AppExecutors.get().compute().execute(() -> {
            // See startWindowRender: recycled-memory reuse can leave dirty
            // pixels; clear before drawing (v1.4.15).
            bmp.eraseColor(Color.TRANSPARENT);
            Canvas c = new Canvas(bmp);
            c.scale(bakeScale, bakeScale);
            synchronized (offscreenLock) {
                offscreenRenderer.drawStrokes(c, snapshot);
            }
            post(() -> swapRenderedBitmap(target, id, snapshot, bmp, bakeScale));
        });
    }

    private void swapRenderedBitmap(Page target, String id, List<Stroke> snapshot,
                                    Bitmap bmp, float scale) {
        List<Stroke> current = new ArrayList<>(target.strokes);
        StrokeSnapshotDelta delta = StrokeSnapshotDelta.between(snapshot, current);
        if (delta.appendOnly && !delta.additions.isEmpty()) {
            synchronized (offscreenLock) {
                Canvas c = new Canvas(bmp);
                c.scale(scale, scale);
                offscreenRenderer.drawStrokes(c, delta.additions);
            }
        }
        int renderedCount = delta.appendOnly ? current.size() : snapshot.size();
        BakedPage old = pageBitmaps.put(
                id, new BakedPage(bmp, scale, renderedCount));
        if (old != null && old.bitmap != bmp && !old.bitmap.isRecycled()) {
            cacheBytes -= byteSize(old.bitmap);
            old.bitmap.recycle();
        }
        cacheBytes += byteSize(bmp);
        boolean again;
        synchronized (renderInFlight) {
            renderInFlight.remove(id);
            again = renderPendingAgain.remove(id);
        }
        // The stroke list changed while rendering (e.g. a stroke committed
        // right after an undo-triggered refresh): schedule one more pass.
        if (!again && !delta.appendOnly) {
            again = true;
        }
        if (page != null && page.id.equals(id)) {
            invalidate();
        }
        if (again) {
            requestRender(target);
        }
    }

    private static int byteSize(Bitmap bmp) {
        return bmp.getWidth() * bmp.getHeight() * 4;
    }

    /** Recycles all cached bitmaps; call from the host's onDestroy. */
    public void release() {
        removeCallbacks(rebakeRunnable);
        rebakePending = false;
        discardWindow();
        for (BakedPage baked : pageBitmaps.values()) {
            baked.bitmap.recycle();
        }
        pageBitmaps.clear();
        cacheBytes = 0;
        AppLogger.d(TAG, "released bitmap cache");
    }
}
