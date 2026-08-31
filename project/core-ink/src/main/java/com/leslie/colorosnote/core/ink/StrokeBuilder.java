package com.leslie.colorosnote.core.ink;

import android.view.MotionEvent;

import com.leslie.colorosnote.core.model.BrushPreset;
import com.leslie.colorosnote.core.model.Stroke;
import com.leslie.colorosnote.core.model.StrokePoint;

import java.util.ArrayList;
import java.util.List;

/**
 * MotionEvent -> {@link Stroke} pipeline (requirements 5.1, 5.2, 7). Android
 * shell only: all math lives in {@link StrokeSmoother}, {@link VelocityTracker}
 * and {@link PressureSource}.
 *
 * <p>Behavior:
 * <ul>
 *   <li>Stylus first: TOOL_TYPE_STYLUS writes; fingers follow the
 *       {@link PalmRejectionPolicy}; once a stylus is down, fingers are
 *       rejected for the rest of the gesture (palm rejection).</li>
 *   <li>Batched historical samples are fully consumed
 *       (getHistoricalX/Y/Pressure/EventTime + historical tilt/orientation).</li>
 *   <li>Optional motion prediction via {@link MotionPredictor}; predicted
 *       points are exposed separately and never committed to the stroke.</li>
 *   <li>Pressure degradation: {@link PressureSource} decides REAL/SIMULATED/
 *       UNAVAILABLE; query it to show "simulated pressure" in the UI.</li>
 * </ul>
 */
public final class StrokeBuilder {

    /** Maps view coordinates to logical page coordinates. */
    public interface CoordinateMapper {
        void viewToPage(float viewX, float viewY, float[] out2);
    }

    /** Stroke lifecycle callbacks (always on the thread feeding events). */
    public interface Listener {
        void onStrokeStarted();

        /**
         * One or more smoothed points were appended.
         *
         * @param dirtyBounds page-space bounds {l,t,r,b} of the newly added
         *                    segment only (seeded from the previous last point
         *                    so the old transient tail is covered), already
         *                    padded by the brush width
         */
        void onPointsAdded(List<StrokePoint> newPoints, float[] dirtyBounds);

        void onStrokeFinished(Stroke stroke);

        void onStrokeCancelled();
    }

    /** Padding beyond half the brush width for dirty rectangles. */
    private static final float DIRTY_PADDING = 4f;

    private final CoordinateMapper mapper;
    private final PalmRejectionPolicy policy;
    private final Listener listener;

    private BrushPreset preset = BrushPreset.fountainPen();
    private final StrokeSmoother smoother = new StrokeSmoother();
    private final VelocityTracker velocityTracker = new VelocityTracker();
    private final PressureSource pressureSource = new PressureSource();
    private MotionPredictor predictor;
    private boolean predictionEnabled = true;

    private final List<StrokePoint> points = new ArrayList<>();
    private final List<StrokePoint> predictedPoints = new ArrayList<>();
    private final float[] dirtyBounds = new float[4];

    private int activePointerId = -1;
    private boolean stylusDown;
    private boolean gestureRejected;
    private boolean stroking;

    public StrokeBuilder(CoordinateMapper mapper, PalmRejectionPolicy policy,
                         Listener listener) {
        this.mapper = mapper;
        this.policy = policy;
        this.listener = listener;
    }

    // ------------------------------------------------------------------
    // Configuration / state
    // ------------------------------------------------------------------

    /** Sets the brush for the NEXT stroke; takes effect on stroke start. */
    public void setBrush(BrushPreset brush) {
        this.preset = brush;
    }

    /**
     * Scales the pull-string stabilizer radius by 1/zoom so a deep-zoom short
     * stroke is not held on the anchor and dropped (v3.2.4). Called by the
     * host on stroke start from the current viewport.
     */
    public void setStabilizerZoomFactor(float factor) {
        smoother.setZoomScaleFactor(factor);
    }

    public BrushPreset getBrush() {
        return preset;
    }

    /** Attaches a motion predictor (null disables prediction). */
    public void setMotionPredictor(MotionPredictor predictor) {
        this.predictor = predictor;
    }

    public void setPredictionEnabled(boolean enabled) {
        predictionEnabled = enabled;
    }

    /** Pressure mode (REAL/SIMULATED/UNAVAILABLE) for the current stroke. */
    public PressureSource getPressureSource() {
        return pressureSource;
    }

    public boolean isStroking() {
        return stroking;
    }

    /** Smoothed points of the in-progress stroke (page coordinates). */
    public List<StrokePoint> getPoints() {
        return points;
    }

    /** Predicted future points for low-latency rendering; never committed. */
    public List<StrokePoint> getPredictedPoints() {
        return predictedPoints;
    }

    /** Page-space dirty bounds of everything drawn so far, or null. */
    public float[] getDirtyBounds() {
        return points.isEmpty() ? null : dirtyBounds.clone();
    }

    // ------------------------------------------------------------------
    // Event intake
    // ------------------------------------------------------------------

    /** Feed touch events from the host View's onTouchEvent. */
    public boolean onTouchEvent(MotionEvent event) {
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
                cancelStroke();
                return true;
            default:
                return false;
        }
    }

    /** Aborts the in-progress stroke without emitting it. */
    public void cancelStroke() {
        if (stroking) {
            stroking = false;
            points.clear();
            predictedPoints.clear();
            activePointerId = -1;
            listener.onStrokeCancelled();
        }
        gestureRejected = false;
        stylusDown = false;
    }

    private boolean handleDown(MotionEvent event) {
        stylusDown = false;
        gestureRejected = false;
        int toolType = event.getToolType(0);
        if (toolType == MotionEvent.TOOL_TYPE_STYLUS) {
            stylusDown = true;
            beginStroke(event, 0);
            return true;
        }
        if (policy == PalmRejectionPolicy.FINGER_AND_STYLUS) {
            beginStroke(event, 0);
            return true;
        }
        gestureRejected = true;
        return false;
    }

    private boolean handlePointerDown(MotionEvent event) {
        int index = event.getActionIndex();
        int toolType = event.getToolType(index);
        if (toolType == MotionEvent.TOOL_TYPE_STYLUS) {
            stylusDown = true;
            // A stylus arriving mid-gesture takes over from a finger stroke.
            int currentIndex = findPointerIndex(event);
            if (stroking && currentIndex >= 0
                    && event.getToolType(currentIndex) != MotionEvent.TOOL_TYPE_STYLUS) {
                cancelStroke();
            }
            // v3.0.1: the pen always wins, even when the gesture opened with a
            // touch that palm rejection turned away.
            //
            // This used to clear gestureRejected only inside the branch above —
            // that is, only when a finger stroke was actually in progress, which
            // under STYLUS_ONLY never happens because the finger is rejected
            // outright. So when the palm landed a moment BEFORE the pen (what
            // naturally happens writing quickly), gestureRejected stayed true,
            // beginStroke was skipped, and the whole pen stroke was silently
            // discarded until the hand lifted and a fresh gesture began. That is
            // the "sometimes writing fast just doesn't register" report.
            gestureRejected = false;
            if (!stroking) {
                beginStroke(event, index);
            }
            return true;
        }
        // Finger while the stylus is down (or rejected policy): palm touch.
        if (stroking && policy == PalmRejectionPolicy.FINGER_AND_STYLUS && !stylusDown) {
            return true; // second finger: ignore, keep the first pointer
        }
        return stroking;
    }

    private boolean handleMove(MotionEvent event) {
        if (!stroking || gestureRejected) {
            return false;
        }
        int index = findPointerIndex(event);
        if (index < 0) {
            return false;
        }
        List<StrokePoint> batch = new ArrayList<>();
        // Every batched historical sample must be consumed, oldest first.
        int historySize = event.getHistorySize();
        for (int h = 0; h < historySize; h++) {
            StrokePoint p = readSample(event, index, h);
            if (p != null) {
                batch.add(p);
            }
        }
        StrokePoint current = readSample(event, index, -1);
        if (current != null) {
            batch.add(current);
        }
        if (!batch.isEmpty()) {
            // Per-batch dirty bounds (v1.4.11): invalidate only the new
            // segment, seeded from the previous last point so the transient
            // tail quad's old position is repainted too. The cumulative
            // bounds remain available via getDirtyBounds().
            float[] batchBounds;
            if (points.isEmpty()) {
                batchBounds = dirtyBounds.clone();
            } else {
                batchBounds = new float[]{Float.MAX_VALUE, Float.MAX_VALUE,
                        -Float.MAX_VALUE, -Float.MAX_VALUE};
                StrokePoint prev = points.get(points.size() - 1);
                growDirtyBounds(batchBounds, prev.x, prev.y);
                for (StrokePoint p : batch) {
                    growDirtyBounds(batchBounds, p.x, p.y);
                }
            }
            points.addAll(batch);
            listener.onPointsAdded(batch, batchBounds);
        }
        updatePrediction(event);
        return true;
    }

    private boolean handlePointerUp(MotionEvent event) {
        int pointerId = event.getPointerId(event.getActionIndex());
        if (pointerId == activePointerId && stroking) {
            finishStroke();
            // Remaining fingers are treated as resting palm: reject the rest.
            gestureRejected = true;
        }
        return true;
    }

    private boolean handleUp(MotionEvent event) {
        if (stroking) {
            int index = findPointerIndex(event);
            if (index >= 0) {
                StrokePoint last = readSample(event, index, -1);
                if (last != null) {
                    points.add(last);
                }
            }
            finishStroke();
        }
        stylusDown = false;
        gestureRejected = false;
        return true;
    }

    // ------------------------------------------------------------------
    // Stroke lifecycle
    // ------------------------------------------------------------------

    private void beginStroke(MotionEvent event, int pointerIndex) {
        activePointerId = event.getPointerId(pointerIndex);
        stroking = true;
        points.clear();
        predictedPoints.clear();
        dirtyBounds[0] = Float.MAX_VALUE;
        dirtyBounds[1] = Float.MAX_VALUE;
        dirtyBounds[2] = -Float.MAX_VALUE;
        dirtyBounds[3] = -Float.MAX_VALUE;
        smoother.configure(preset);
        smoother.reset();
        velocityTracker.reset();
        pressureSource.reset();
        StrokePoint first = readSample(event, pointerIndex, -1);
        if (first != null) {
            points.add(first);
        }
        listener.onStrokeStarted();
    }

    private void finishStroke() {
        stroking = false;
        activePointerId = -1;
        predictedPoints.clear();
        if (points.isEmpty()) {
            listener.onStrokeCancelled();
            return;
        }
        Stroke stroke = new Stroke();
        stroke.brushPreset = copyPreset(preset);
        stroke.brushPresetId = preset.id;
        stroke.color = preset.color;
        stroke.points = new ArrayList<>(points);
        stroke.layer = preset.brushType == BrushPreset.BrushType.HIGHLIGHTER
                ? Stroke.Layer.HIGHLIGHT : Stroke.Layer.INK;
        stroke.createdAtMs = System.currentTimeMillis();
        points.clear();
        listener.onStrokeFinished(stroke);
    }

    // ------------------------------------------------------------------
    // Sampling
    // ------------------------------------------------------------------

    /**
     * Reads one sample (historical when historyPos >= 0, else current) into a
     * fully populated, smoothed StrokePoint in page coordinates.
     */
    private StrokePoint readSample(MotionEvent event, int pointerIndex, int historyPos) {
        boolean historical = historyPos >= 0;
        float viewX = historical ? event.getHistoricalX(pointerIndex, historyPos)
                : event.getX(pointerIndex);
        float viewY = historical ? event.getHistoricalY(pointerIndex, historyPos)
                : event.getY(pointerIndex);
        float rawPressure = historical
                ? event.getHistoricalPressure(pointerIndex, historyPos)
                : event.getPressure(pointerIndex);
        float tilt = historical
                ? event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, pointerIndex, historyPos)
                : event.getAxisValue(MotionEvent.AXIS_TILT, pointerIndex);
        float orientation = historical
                ? event.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, pointerIndex, historyPos)
                : event.getAxisValue(MotionEvent.AXIS_ORIENTATION, pointerIndex);
        long time = historical ? event.getHistoricalEventTime(historyPos)
                : event.getEventTime();

        float[] page = new float[2];
        mapper.viewToPage(viewX, viewY, page);

        velocityTracker.addSample(page[0], page[1], time);
        float speed = velocityTracker.getSpeed();

        StrokePoint raw = new StrokePoint();
        raw.toolType = event.getToolType(pointerIndex);
        raw.pressure = rawPressure;
        pressureSource.onSample(raw);
        float resolved = pressureSource.resolvePressure(rawPressure, speed);

        float[] smoothed = smoother.process(page[0], page[1], resolved, time, null);

        StrokePoint p = new StrokePoint();
        p.x = smoothed[0];
        p.y = smoothed[1];
        p.timestampMs = time;
        p.pressure = smoothed[2];
        p.tilt = tilt;
        p.orientation = orientation;
        p.toolType = raw.toolType;
        p.pointerId = event.getPointerId(pointerIndex);
        p.velocityX = velocityTracker.getVelocityX();
        p.velocityY = velocityTracker.getVelocityY();

        growDirtyBounds(dirtyBounds, p.x, p.y);
        return p;
    }

    private void updatePrediction(MotionEvent event) {
        predictedPoints.clear();
        if (!predictionEnabled || predictor == null) {
            return;
        }
        predictor.record(event);
        MotionEvent predicted = predictor.predict();
        if (predicted == null) {
            return;
        }
        try {
            float[] page = new float[2];
            int historySize = predicted.getHistorySize();
            for (int h = 0; h < historySize; h++) {
                addPredicted(predicted.getHistoricalX(0, h),
                        predicted.getHistoricalY(0, h),
                        predicted.getHistoricalPressure(0, h),
                        predicted.getHistoricalEventTime(h), page);
            }
            addPredicted(predicted.getX(), predicted.getY(), predicted.getPressure(),
                    predicted.getEventTime(), page);
        } finally {
            predicted.recycle();
        }
        // The library only predicts ~1 frame out; stretch the translucent
        // strip to the full target horizon so it meets the pen tip.
        PredictionExtender.extend(predictedPoints, event.getEventTime());
    }

    private void addPredicted(float viewX, float viewY, float pressure, long time,
                              float[] page) {
        mapper.viewToPage(viewX, viewY, page);
        StrokePoint p = new StrokePoint();
        p.x = page[0];
        p.y = page[1];
        p.pressure = pressure;
        p.timestampMs = time;
        p.toolType = MotionEvent.TOOL_TYPE_STYLUS;
        predictedPoints.add(p);
    }

    private void growDirtyBounds(float[] bounds, float x, float y) {
        float pad = preset.maxWidth / 2f + DIRTY_PADDING;
        bounds[0] = Math.min(bounds[0], x - pad);
        bounds[1] = Math.min(bounds[1], y - pad);
        bounds[2] = Math.max(bounds[2], x + pad);
        bounds[3] = Math.max(bounds[3], y + pad);
    }

    private int findPointerIndex(MotionEvent event) {
        return activePointerId < 0 ? -1 : event.findPointerIndex(activePointerId);
    }

    // ------------------------------------------------------------------
    // Preset snapshot
    // ------------------------------------------------------------------

    /** Deep-enough copy so later preset edits never alter finished strokes. */
    /** Deep copy of a preset; public so renderers can derive scaled variants. */
    public static BrushPreset copyPreset(BrushPreset src) {
        BrushPreset c = new BrushPreset();
        c.id = src.id;
        c.name = src.name;
        c.brushType = src.brushType;
        c.baseWidth = src.baseWidth;
        c.minWidth = src.minWidth;
        c.maxWidth = src.maxWidth;
        c.opacity = src.opacity;
        c.color = src.color;
        c.pressureEnabled = src.pressureEnabled;
        c.pressureCurve = src.pressureCurve;
        c.customCurvePoints = new ArrayList<>(src.customCurvePoints);
        c.pressureToWidth = src.pressureToWidth;
        c.pressureToOpacity = src.pressureToOpacity;
        c.velocityToWidth = src.velocityToWidth;
        c.tiltEnabled = src.tiltEnabled;
        c.tiltToWidth = src.tiltToWidth;
        c.tiltToOpacity = src.tiltToOpacity;
        c.smoothing = src.smoothing;
        c.stabilization = src.stabilization;
        c.taperStart = src.taperStart;
        c.taperEnd = src.taperEnd;
        c.textureId = src.textureId;
        c.blendMode = src.blendMode;
        return c;
    }
}
