package com.leslie.colorosnote.feature.editor;

import android.content.Context;
import android.graphics.Matrix;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;

import com.leslie.colorosnote.core.ink.LassoEngine;
import com.leslie.colorosnote.core.render.InkView;
import com.leslie.colorosnote.core.render.ViewportTransform;
import com.leslie.colorosnote.feature.settings.EditorSettings;

import java.util.ArrayList;
import java.util.List;

/**
 * Touch routing for the single-page editor (spec 29). Sits in front of
 * {@link InkView} as its OnTouchListener: events it does not consume fall
 * through to InkView's own StrokeBuilder pipeline (zero-copy passthrough for
 * pen writing).
 *
 * <ul>
 *   <li>Stylus writes; fingers pan or draw per EditorSettings.fingerMode.</li>
 *   <li>Two fingers: pinch zoom + pan; two/three-finger tap: undo/redo.</li>
 *   <li>Eraser (incl. BUTTON_STYLUS_PRIMARY and TOOL_TYPE_ERASER tail) and
 *       lasso paths are tracked here and reported to the host.</li>
 *   <li>With an active lasso selection: one-finger drag moves, two-finger
 *       pinch scales/rotates the selection (page-space affine steps).</li>
 *   <li>Long-press on blank space (finger, pan mode) opens the context menu.</li>
 * </ul>
 */
public final class EditorGestureController implements View.OnTouchListener {

    /** Editor-side callbacks; all invoked on the main thread. */
    public interface Host {
        EditorSettings settings();

        EditorTool currentTool();

        LassoEngine.Mode lassoMode();

        EraserConfig eraserConfig();

        ViewportTransform viewport();

        boolean selectionActive();

        boolean selectionContainsPagePoint(float x, float y);

        /** Center {cx, cy} of the active selection in page units, or null. */
        float[] selectionCenterPage();

        void clearSelection();

        void onWritingStateChanged(boolean writing);

        void onEraseBegin();

        /** One new eraser path segment, flat page coords {x0,y0,...}. */
        void onEraseDelta(float[] pagePath);

        void onEraseEnd();

        /** AREA eraser finished with a free-form polygon (page coords). */
        void onAreaErase(float[] pagePolygon);

        /** Lasso finished with a region (FREE: polygon; RECT: {l,t,r,b}). */
        void onLassoFinished(float[] pageRegion, LassoEngine.Mode mode);

        /**
         * Lasso gesture had too few points for a region — a tap. Hit-test the
         * page point for tap-to-select (v1.4.6).
         */
        void onLassoTap(float pageX, float pageY);

        void onSelectionTransformBegin();

        /** Incremental affine step (Android Matrix values order, page space). */
        void onSelectionTransform(float[] matrix9);

        void onSelectionTransformEnd();

        void onViewportChanged();

        void onUndoGesture();

        void onRedoGesture();

        void onLongPressBlank(float viewX, float viewY);

        /** Stylus button / eraser tail temporarily switched the tool. */
        void onTemporaryToolBegin(EditorTool tool);

        void onTemporaryToolEnd();
    }

    /**
     * Optional front-of-queue touch hook (object tools: shape drag, tape
     * drawing, sticker/text/image placement). When set, it sees every event
     * before the normal dispatch; returning true consumes the event entirely.
     */
    public interface TouchInterceptor {
        boolean onTouchEvent(MotionEvent event);
    }

    private enum State {
        IDLE, WRITE_PASSTHROUGH, PAN, ERASE, AREA_ERASE, LASSO,
        SELECT_DRAG, SELECT_TRANSFORM, VIEWPORT, SHORTCUT_CONSUMED
    }

    private static final long TAP_WINDOW_MS = 300;
    private static final float MIN_LASSO_POINTS = 3;

    private final Host host;
    private final InkView inkView;
    private final EditorOverlayView overlay;
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector longPressDetector;
    private final float tapSlop;

    private State state = State.IDLE;
    private int activePointerId = -1;
    private float lastX;
    private float lastY;

    /** Erase path / lasso region accumulation (page + screen coords). */
    private final List<Float> pagePoints = new ArrayList<>();
    private final List<Float> screenPoints = new ArrayList<>();

    /** Two-pointer tracking (selection transform + viewport pan). */
    private int pointerId1 = -1;
    private int pointerId2 = -1;
    private float prevMidX;
    private float prevMidY;
    private float prevDist = -1f;
    private float prevAngle;
    private float[] transformCenterPage;

    /** Multi-finger tap detection. */
    private int tapPointers;
    private long tapDownTime;
    private float tapMoveAccum;

    private boolean temporaryToolActive;
    private boolean transformBegun;
    private TouchInterceptor touchInterceptor;

    /** Stylus shortcut tracking (long-press erase). */
    private float stylusDownX;
    private float stylusDownY;
    private boolean stylusMoved;
    /** Disarm radius for the long-press-erase shortcut (see constructor). */
    private final float stylusLongPressSlop;
    /** Movement tracking while erasing (slop threshold). */
    private float eraseDownX;
    private float eraseDownY;
    private boolean eraseMoved;
    /** True when erase was entered via stylus long-press: the first wipe
     *  waits for real movement past the hold point (v4.0.0). */
    private boolean eraseRequiresMove;
    private final Runnable stylusLongPressRunnable = this::onStylusLongPressTimeout;

    public EditorGestureController(Context context, Host host, InkView inkView,
                                   EditorOverlayView overlay) {
        this.host = host;
        this.inkView = inkView;
        this.overlay = overlay;
        tapSlop = ViewConfiguration.get(context).getScaledTouchSlop() * 2f;
        // v4.0.0: the stylus long-press-erase disarm radius must be much
        // smaller than tapSlop. tapSlop (2x scaled slop, ~44px) spans a whole
        // character at 1000% zoom, so careful slow writing never exceeded it
        // within the 500ms dwell: the shortcut fired mid-stroke, cancelled
        // the in-progress stroke and erased nearby ink ("笔画被偷掉").
        stylusLongPressSlop =
                ViewConfiguration.get(context).getScaledTouchSlop() * 0.5f;
        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScaleBegin(ScaleGestureDetector detector) {
                        return state == State.VIEWPORT && tapPointers == 0;
                    }

                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        host.viewport().zoomBy(detector.getScaleFactor(),
                                detector.getFocusX(), detector.getFocusY());
                        host.onViewportChanged();
                        return true;
                    }
                });
        longPressDetector = new GestureDetector(context,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public void onLongPress(MotionEvent e) {
                        if (state == State.PAN) {
                            host.onLongPressBlank(e.getX(), e.getY());
                        }
                    }

                    @Override
                    public boolean onDown(MotionEvent e) {
                        return true;
                    }
                });
    }

    /** Installs this controller on the InkView (single-page mode). */
    public void attach() {
        inkView.setOnTouchListener(this);
    }

    /** Detaches (continuous-scroll mode: InkViews handle their own input). */
    public void detach() {
        inkView.setOnTouchListener(null);
        cancelAll();
    }

    /** Installs the object-tool touch hook (null clears it). */
    public void setTouchInterceptor(TouchInterceptor interceptor) {
        touchInterceptor = interceptor;
    }

    // ------------------------------------------------------------------
    // Event dispatch
    // ------------------------------------------------------------------

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if (touchInterceptor != null && touchInterceptor.onTouchEvent(event)) {
            return true;
        }
        scaleDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return handleDown(event);
            case MotionEvent.ACTION_POINTER_DOWN:
                return handlePointerDown(event);
            case MotionEvent.ACTION_MOVE:
                return handleMove(event);
            case MotionEvent.ACTION_POINTER_UP:
                return handlePointerUp(event);
            case MotionEvent.ACTION_UP:
                return handleUp(event);
            case MotionEvent.ACTION_CANCEL:
                cancelAll();
                return true;
            default:
                return state != State.IDLE;
        }
    }

    private boolean handleDown(MotionEvent event) {
        resetTap();
        int toolType = event.getToolType(0);

        // Eraser tail (hardware eraser end).
        if (toolType == MotionEvent.TOOL_TYPE_ERASER
                && host.settings().eraserTailAutoEraser) {
            host.onTemporaryToolBegin(EditorTool.ERASER);
            temporaryToolActive = true;
            beginErase(event, 0);
            return true;
        }

        // Stylus side button.
        boolean stylusButton =
                (event.getButtonState() & MotionEvent.BUTTON_STYLUS_PRIMARY) != 0;
        if (toolType == MotionEvent.TOOL_TYPE_STYLUS && stylusButton
                && host.settings().stylusButtonAction
                        != EditorSettings.StylusButtonAction.NONE) {
            EditorTool tool = host.settings().stylusButtonAction
                    == EditorSettings.StylusButtonAction.LASSO
                    ? EditorTool.LASSO : EditorTool.ERASER;
            host.onTemporaryToolBegin(tool);
            temporaryToolActive = true;
            if (tool == EditorTool.ERASER) {
                beginErase(event, 0);
            } else {
                beginLasso(event, 0);
            }
            return true;
        }

        // Finger in pan-only mode scrolls regardless of the active tool.
        if (toolType == MotionEvent.TOOL_TYPE_FINGER
                && host.settings().fingerMode == EditorSettings.FingerMode.PAN_ONLY) {
            beginPan(event, 0);
            return true;
        }

        EditorTool tool = host.currentTool();
        switch (tool) {
            case ERASER:
                beginErase(event, 0);
                return true;
            case LASSO:
                if (host.selectionActive()) {
                    float[] page = pagePoint(event.getX(0), event.getY(0));
                    if (host.selectionContainsPagePoint(page[0], page[1])) {
                        state = State.SELECT_DRAG;
                        activePointerId = event.getPointerId(0);
                        lastX = event.getX(0);
                        lastY = event.getY(0);
                        beginSelectionTransform();
                        return true;
                    }
                    host.clearSelection();
                }
                beginLasso(event, 0);
                return true;
            default:
                // Pen tools: stylus (or finger in draw mode) writes; the event
                // falls through to InkView's StrokeBuilder untouched.
                if (host.selectionActive()) {
                    // Tapping outside an active selection clears it first
                    // (GoodNotes-style); the tap is consumed, not drawn.
                    float[] pagePt = pagePoint(event.getX(0), event.getY(0));
                    if (!host.selectionContainsPagePoint(pagePt[0], pagePt[1])) {
                        host.clearSelection();
                        state = State.SHORTCUT_CONSUMED;
                        return true;
                    }
                }
                if (toolType == MotionEvent.TOOL_TYPE_STYLUS) {
                    // Stylus long-press erases while held.
                    stylusDownX = event.getX(0);
                    stylusDownY = event.getY(0);
                    stylusMoved = false;
                    inkView.postDelayed(stylusLongPressRunnable,
                            ViewConfiguration.getLongPressTimeout());
                }
                state = State.WRITE_PASSTHROUGH;
                activePointerId = event.getPointerId(0);
                host.onWritingStateChanged(true);
                return false;
        }
    }

    private boolean handlePointerDown(MotionEvent event) {
        int count = event.getPointerCount();

        // Palm rejection (spec 29): while the stylus writes, an extra finger
        // must not cancel the stroke — StrokeBuilder already rejects finger
        // input for the rest of the gesture, so just pass the stream through.
        if (state == State.WRITE_PASSTHROUGH
                && event.getToolType(event.getActionIndex()) == MotionEvent.TOOL_TYPE_FINGER
                && host.settings().fingerMode == EditorSettings.FingerMode.PAN_ONLY) {
            // v1.1.9: a joining palm must still disarm the pending
            // long-press-erase timer, or it fires mid-writing and erases.
            inkView.removeCallbacks(stylusLongPressRunnable);
            return false;
        }

        // A second pointer aborts any single-pointer operation.
        switch (state) {
            case WRITE_PASSTHROUGH:
                inkView.removeCallbacks(stylusLongPressRunnable);
                if (inkView.getStrokeBuilder() != null) {
                    inkView.getStrokeBuilder().cancelStroke();
                }
                host.onWritingStateChanged(false);
                break;
            case ERASE:
                host.onEraseEnd();
                overlay.hideEraserCursor();
                break;
            case AREA_ERASE:
            case LASSO:
                overlay.clearLasso();
                break;
            case PAN:
            default:
                break;
        }

        if (count == 2 && tapPointers == 0) {
            tapPointers = 2;
            tapDownTime = event.getEventTime();
            tapMoveAccum = 0f;
        } else if (count == 3 && tapPointers == 2
                && event.getEventTime() - tapDownTime < TAP_WINDOW_MS) {
            tapPointers = 3;
        }

        if (count >= 2) {
            if (state == State.SELECT_DRAG || state == State.SELECT_TRANSFORM) {
                state = State.SELECT_TRANSFORM;
                initTwoPointer(event);
                return true;
            }
            if (host.selectionActive() && host.currentTool() == EditorTool.LASSO) {
                state = State.SELECT_TRANSFORM;
                beginSelectionTransform();
                initTwoPointer(event);
                return true;
            }
            state = State.VIEWPORT;
            initTwoPointer(event);
            return true;
        }
        return true;
    }

    private boolean handleMove(MotionEvent event) {
        switch (state) {
            case WRITE_PASSTHROUGH: {
                // Watch for movement so a real stroke cancels the pending
                // long-press-erase shortcut. The event itself stays untouched.
                if (!stylusMoved) {
                    int idx = pointerIndex(event);
                    if (idx >= 0) {
                        float dx = event.getX(idx) - stylusDownX;
                        float dy = event.getY(idx) - stylusDownY;
                        // v4.0.0: disarm on a small stylus-specific slop, not
                        // tapSlop — any real writing motion cancels the
                        // shortcut before it can fire (see constructor doc).
                        if (dx * dx + dy * dy
                                > stylusLongPressSlop * stylusLongPressSlop) {
                            stylusMoved = true;
                            inkView.removeCallbacks(stylusLongPressRunnable);
                        }
                    }
                }
                return false; // untouched by us: InkView consumes
            }
            case PAN:
                longPressDetector.onTouchEvent(event);
                return movePan(event);
            case ERASE:
                return moveErase(event);
            case AREA_ERASE:
                return moveRegion(event, false);
            case LASSO:
                return moveRegion(event, host.lassoMode() == LassoEngine.Mode.RECT);
            case SELECT_DRAG:
                return moveSelectionDrag(event);
            case SELECT_TRANSFORM:
                return moveSelectionTransform(event);
            case VIEWPORT:
                return moveViewport(event);
            default:
                return state != State.IDLE;
        }
    }

    private boolean handlePointerUp(MotionEvent event) {
        if (state == State.WRITE_PASSTHROUGH) {
            return false; // StrokeBuilder needs the pointer-up stream
        }
        if (state == State.SELECT_TRANSFORM && event.getPointerCount() == 2) {
            // One finger remains: continue as a drag with that pointer.
            int remaining = event.getActionIndex() == 0 ? 1 : 0;
            state = State.SELECT_DRAG;
            activePointerId = event.getPointerId(remaining);
            lastX = event.getX(remaining);
            lastY = event.getY(remaining);
            prevDist = -1f;
            return true;
        }
        return true;
    }

    private boolean handleUp(MotionEvent event) {
        State finished = state;
        state = State.IDLE;
        activePointerId = -1;
        switch (finished) {
            case WRITE_PASSTHROUGH:
                inkView.removeCallbacks(stylusLongPressRunnable);
                host.onWritingStateChanged(false);
                endTemporaryTool();
                return false; // let InkView finish the stroke
            case PAN:
                break;
            case ERASE:
                host.onEraseEnd();
                overlay.hideEraserCursor();
                break;
            case AREA_ERASE:
                if (pagePoints.size() >= MIN_LASSO_POINTS * 2) {
                    host.onAreaErase(toArray(pagePoints));
                }
                overlay.clearLasso();
                break;
            case LASSO:
                if (pagePoints.size() >= MIN_LASSO_POINTS * 2) {
                    host.onLassoFinished(toArray(pagePoints), host.lassoMode());
                } else if (pagePoints.size() >= 2) {
                    // v1.4.6: tap-to-select — a lasso gesture that never grew
                    // into a region is a tap on its DOWN point (small drifts
                    // below MIN_LASSO_POINTS count as taps too).
                    host.onLassoTap(pagePoints.get(0), pagePoints.get(1));
                }
                overlay.clearLasso();
                break;
            case SELECT_DRAG:
            case SELECT_TRANSFORM:
                endSelectionTransform();
                break;
            case VIEWPORT:
                maybeFireTap(event);
                break;
            default:
                break;
        }
        endTemporaryTool();
        resetTap();
        return true;
    }

    // ------------------------------------------------------------------
    // Per-state MOVE handling
    // ------------------------------------------------------------------

    private boolean movePan(MotionEvent event) {
        int idx = pointerIndex(event);
        if (idx < 0) {
            return true;
        }
        float x = event.getX(idx);
        float y = event.getY(idx);
        host.viewport().panBy(x - lastX, y - lastY);
        lastX = x;
        lastY = y;
        host.onViewportChanged();
        return true;
    }

    private boolean moveErase(MotionEvent event) {
        int idx = pointerIndex(event);
        if (idx < 0) {
            return true;
        }
        if (!eraseMoved) {
            float dx = event.getX(idx) - eraseDownX;
            float dy = event.getY(idx) - eraseDownY;
            float slop = eraseRequiresMove ? stylusLongPressSlop : tapSlop;
            if (dx * dx + dy * dy > slop * slop) {
                eraseMoved = true;
            }
        }
        // v4.0.0: a long-press-entered erase erases nothing until the pen
        // actually leaves the hold point. A false trigger (pause while
        // writing) followed by tremor or a lift then costs zero ink; only a
        // deliberate drag starts wiping.
        if (eraseRequiresMove && !eraseMoved) {
            overlay.setEraserCursor(event.getX(idx), event.getY(idx),
                    host.eraserConfig().radius * host.viewport().getScale());
            return true;
        }
        // Delta of PAGE-space samples for this move event (screen list unused).
        List<Float> delta = new ArrayList<>();
        int history = event.getHistorySize();
        for (int h = 0; h < history; h++) {
            appendPoint(null, delta,
                    event.getHistoricalX(idx, h), event.getHistoricalY(idx, h));
        }
        appendPoint(null, delta, event.getX(idx), event.getY(idx));
        if (delta.size() >= 2) {
            host.onEraseDelta(toArray(delta));
        }
        overlay.setEraserCursor(event.getX(idx), event.getY(idx),
                host.eraserConfig().radius * host.viewport().getScale());
        return true;
    }

    private boolean moveRegion(MotionEvent event, boolean rect) {
        int idx = pointerIndex(event);
        if (idx < 0) {
            return true;
        }
        int history = event.getHistorySize();
        for (int h = 0; h < history; h++) {
            appendPoint(screenPoints, pagePoints,
                    event.getHistoricalX(idx, h), event.getHistoricalY(idx, h));
        }
        appendPoint(screenPoints, pagePoints, event.getX(idx), event.getY(idx));
        overlay.setLassoPath(screenPoints, rect);
        return true;
    }

    private boolean moveSelectionDrag(MotionEvent event) {
        int idx = pointerIndex(event);
        if (idx < 0) {
            return true;
        }
        float x = event.getX(idx);
        float y = event.getY(idx);
        float scale = Math.max(0.01f, host.viewport().getScale());
        float[] m = SelectionController.translation(
                (x - lastX) / scale, (y - lastY) / scale);
        lastX = x;
        lastY = y;
        host.onSelectionTransform(m);
        return true;
    }

    private boolean moveSelectionTransform(MotionEvent event) {
        float[] m = computeTwoPointerMatrix(event, transformCenterPage);
        if (m != null) {
            host.onSelectionTransform(m);
        }
        return true;
    }

    private boolean moveViewport(MotionEvent event) {
        // Two-finger pan (pinch zoom is handled by the ScaleGestureDetector).
        int i1 = event.findPointerIndex(pointerId1);
        int i2 = event.findPointerIndex(pointerId2);
        if (i1 < 0 || i2 < 0) {
            return true;
        }
        float midX = (event.getX(i1) + event.getX(i2)) / 2f;
        float midY = (event.getY(i1) + event.getY(i2)) / 2f;
        float dx = midX - prevMidX;
        float dy = midY - prevMidY;
        tapMoveAccum += Math.abs(dx) + Math.abs(dy);
        if (tapMoveAccum > tapSlop) {
            tapPointers = 0; // real gesture, not a tap
        }
        host.viewport().panBy(dx, dy);
        prevMidX = midX;
        prevMidY = midY;
        host.onViewportChanged();
        return true;
    }

    // ------------------------------------------------------------------
    // Gesture entry points
    // ------------------------------------------------------------------

    private void beginPan(MotionEvent event, int index) {
        state = State.PAN;
        activePointerId = event.getPointerId(index);
        lastX = event.getX(index);
        lastY = event.getY(index);
        longPressDetector.onTouchEvent(event);
    }

    /**
     * Stylus long-press fired: the pen stroke that barely started is cancelled
     * and the rest of this gesture erases (temporary tool, reverts on lift).
     *
     * <p>v4.0.0: do NOT erase at the down point here. If the timer fired on a
     * false trigger (a slow careful stroke that never left the disarm radius),
     * wiping the ink under the pen is exactly the "笔画被偷掉" damage. Erasing
     * now starts only when the pen actually moves (the first MOVE delta in
     * {@link #moveErase}), so a mistaken hold-and-lift costs nothing but the
     * cancelled, sub-slop stroke start.
     */
    private void onStylusLongPressTimeout() {
        if (state != State.WRITE_PASSTHROUGH || stylusMoved) {
            return;
        }
        if (inkView.getStrokeBuilder() != null) {
            inkView.getStrokeBuilder().cancelStroke();
        }
        host.onTemporaryToolBegin(EditorTool.ERASER);
        temporaryToolActive = true;
        state = State.ERASE;
        eraseDownX = stylusDownX;
        eraseDownY = stylusDownY;
        eraseMoved = false;
        eraseRequiresMove = true;
        host.onEraseBegin();
        overlay.setEraserCursor(stylusDownX, stylusDownY,
                host.eraserConfig().radius * host.viewport().getScale());
    }

    private void beginErase(MotionEvent event, int index) {
        eraseDownX = event.getX(index);
        eraseDownY = event.getY(index);
        eraseMoved = false;
        eraseRequiresMove = false;
        EraserConfig config = host.eraserConfig();
        state = config.mode == com.leslie.colorosnote.core.ink.EraserEngine.Mode.AREA
                ? State.AREA_ERASE : State.ERASE;
        activePointerId = event.getPointerId(index);
        pagePoints.clear();
        screenPoints.clear();
        if (state == State.ERASE) {
            host.onEraseBegin();
            float[] page = pagePoint(event.getX(index), event.getY(index));
            host.onEraseDelta(page); // single point = tap erase
            overlay.setEraserCursor(event.getX(index), event.getY(index),
                    config.radius * host.viewport().getScale());
        } else {
            appendPoint(screenPoints, pagePoints, event.getX(index), event.getY(index));
            overlay.setLassoPath(screenPoints, false);
        }
    }

    private void beginLasso(MotionEvent event, int index) {
        state = State.LASSO;
        activePointerId = event.getPointerId(index);
        pagePoints.clear();
        screenPoints.clear();
        appendPoint(screenPoints, pagePoints, event.getX(index), event.getY(index));
        overlay.setLassoPath(screenPoints, host.lassoMode() == LassoEngine.Mode.RECT);
    }

    private void beginSelectionTransform() {
        if (!transformBegun) {
            transformBegun = true;
            host.onSelectionTransformBegin();
        }
    }

    private void endSelectionTransform() {
        if (transformBegun) {
            transformBegun = false;
            host.onSelectionTransformEnd();
        }
    }

    private void initTwoPointer(MotionEvent event) {
        pointerId1 = event.getPointerId(0);
        pointerId2 = event.getPointerId(1);
        int i1 = event.findPointerIndex(pointerId1);
        int i2 = event.findPointerIndex(pointerId2);
        prevMidX = (event.getX(i1) + event.getX(i2)) / 2f;
        prevMidY = (event.getY(i1) + event.getY(i2)) / 2f;
        prevDist = distance(event, i1, i2);
        prevAngle = angle(event, i1, i2);
        transformCenterPage = host.selectionCenterPage();
    }

    /** Incremental scale+rotate+translate step from the two tracked pointers. */
    private float[] computeTwoPointerMatrix(MotionEvent event, float[] centerPage) {
        int i1 = event.findPointerIndex(pointerId1);
        int i2 = event.findPointerIndex(pointerId2);
        if (i1 < 0 || i2 < 0 || prevDist <= 0f) {
            return null;
        }
        float midX = (event.getX(i1) + event.getX(i2)) / 2f;
        float midY = (event.getY(i1) + event.getY(i2)) / 2f;
        float dist = distance(event, i1, i2);
        float ang = angle(event, i1, i2);

        float scale = dist / prevDist;
        float rotation = (float) Math.toDegrees(ang - prevAngle);
        float cx = centerPage != null ? centerPage[0] : 0f;
        float cy = centerPage != null ? centerPage[1] : 0f;

        float[] prevMidPage = pagePoint(prevMidX, prevMidY);
        float[] midPage = pagePoint(midX, midY);

        Matrix m = new Matrix();
        m.postScale(scale, scale, cx, cy);
        m.postRotate(rotation, cx, cy);
        m.postTranslate(midPage[0] - prevMidPage[0], midPage[1] - prevMidPage[1]);

        prevMidX = midX;
        prevMidY = midY;
        prevDist = dist;
        prevAngle = ang;

        float[] values = new float[9];
        m.getValues(values);
        return values;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void maybeFireTap(MotionEvent event) {
        if (tapPointers >= 2 && tapMoveAccum <= tapSlop
                && event.getEventTime() - tapDownTime < TAP_WINDOW_MS) {
            if (tapPointers == 2 && host.settings().twoFingerTapUndo) {
                host.onUndoGesture();
            } else if (tapPointers == 3 && host.settings().threeFingerTapRedo) {
                host.onRedoGesture();
            }
        }
    }

    private void cancelAll() {
        inkView.removeCallbacks(stylusLongPressRunnable);
        switch (state) {
            case WRITE_PASSTHROUGH:
                host.onWritingStateChanged(false);
                break;
            case ERASE:
                host.onEraseEnd();
                overlay.hideEraserCursor();
                break;
            case AREA_ERASE:
            case LASSO:
                overlay.clearLasso();
                break;
            case SELECT_DRAG:
            case SELECT_TRANSFORM:
                endSelectionTransform();
                break;
            default:
                break;
        }
        state = State.IDLE;
        activePointerId = -1;
        endTemporaryTool();
        resetTap();
    }

    private void endTemporaryTool() {
        if (temporaryToolActive) {
            temporaryToolActive = false;
            host.onTemporaryToolEnd();
        }
    }

    private void resetTap() {
        tapPointers = 0;
        tapMoveAccum = 0f;
    }

    private int pointerIndex(MotionEvent event) {
        return activePointerId < 0 ? -1 : event.findPointerIndex(activePointerId);
    }

    private float[] pagePoint(float x, float y) {
        float[] out = new float[2];
        host.viewport().screenToPage(x, y, out);
        return out;
    }

    /** Adds the screen point to screenList and the page point to pageList. */
    private void appendPoint(List<Float> screenList, List<Float> pageList,
                             float screenX, float screenY) {
        if (screenList != null) {
            screenList.add(screenX);
            screenList.add(screenY);
        }
        if (pageList != null) {
            float[] page = pagePoint(screenX, screenY);
            pageList.add(page[0]);
            pageList.add(page[1]);
        }
    }

    private static float distance(MotionEvent e, int i1, int i2) {
        float dx = e.getX(i1) - e.getX(i2);
        float dy = e.getY(i1) - e.getY(i2);
        return (float) Math.hypot(dx, dy);
    }

    private static float angle(MotionEvent e, int i1, int i2) {
        return (float) Math.atan2(e.getY(i2) - e.getY(i1), e.getX(i2) - e.getX(i1));
    }

    private static float[] toArray(List<Float> list) {
        float[] out = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            out[i] = list.get(i);
        }
        return out;
    }
}
