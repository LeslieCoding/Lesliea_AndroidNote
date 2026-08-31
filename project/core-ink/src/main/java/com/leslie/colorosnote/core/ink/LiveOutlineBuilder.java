package com.leslie.colorosnote.core.ink;

/**
 * Incremental outline builder for the in-progress stroke (v1.4.10).
 *
 * <p>Fed with styled points {x, y, width} from {@link BrushEngine#addPoint}
 * as they arrive; each accepted point beyond the second commits the segment
 * before it (Catmull-Rom subdivided when it bends, v1.4.18 — see
 * {@link SplineRefiner}) as one or more quads (left/right offsets along the
 * tangent normal) into a growable flat buffer, so the renderer only appends
 * new geometry instead of re-meshing the whole stroke every frame.
 *
 * <p>Normals follow {@link BrushEngine#buildMesh}: central difference for
 * interior points (direction from previous to next), forward difference at
 * the head. A point's normal is final only once its successor is known, so
 * committed quads lag one segment behind; the newest segment is exposed via
 * {@link #tailQuad} (endpoint normal, not final) and gets replaced by the
 * committed quad when the next point arrives. Because consecutive committed
 * quads share the exact same joint chord, the union fills seamlessly even at
 * sharp corners.
 *
 * <p>Too-close points are welded with the same width-proportional minimum
 * distance as the full mesh (pen-lift noise and end taper stay exclusive to
 * the commit-time {@link BrushEngine#computeMesh}, so final quality is
 * unchanged).
 *
 * <p>Round caps (v1.4.17): {@link #headCap} / {@link #tailCap} emit the same
 * semicircle polygons the committed mesh produces, so the in-progress stroke
 * is capsule-ended too (previously the live overlay had flat butt ends).
 *
 * <p>Pure JVM — no android.graphics imports; the Android shell turns quads
 * into Path commands.
 */
public final class LiveOutlineBuilder {

    // Weld constants mirrored from BrushEngine (private there) so the live
    // outline tracks the committed mesh's vertex density.
    /** Absolute weld floor for centerline points (avoids zero normals). */
    private static final float WELD_DISTANCE = 0.05f;
    /** Center weld distance also scales with local width (outline dedup). */
    private static final float WELD_WIDTH_FACTOR = 0.5f;
    /** Direction vectors shorter than this yield a zero normal. */
    private static final float MIN_DIRECTION_LEN = 1e-6f;

    /**
     * v2.1.3 live latency cap. The angular tolerance in
     * {@link SplineRefiner#subdivisions} (6°/√scale) is tuned for the committed
     * mesh and yields 50+ sub-quads for one 90° sample at deep zoom — at
     * 240 Hz that appends thousands of path elements per second while
     * {@code InkView} re-rasterizes the whole cumulative live Path each frame
     * (the "extreme zoom fountain-pen lag" complaint). Live ink does not need
     * that facet density: the cover discs below absorb the chord error, and
     * knot positions still match the committed mesh exactly. v2.1.3: with the
     * viewport capped at 10x the fitted scale, the per-frame path budget that
     * forced 20 sub-quads is gone; 24 halves the remaining chord error of a
     * capped 90° bend and tightens the cover-disc spacing accordingly while
     * staying well under the committed mesh's demand.
     */
    private static final int LIVE_MAX_SUBDIVISIONS = 24;

    /** Cap for the transient tail strip (rebuilt on every accepted point). */
    private static final int TAIL_MAX_SUBDIVISIONS = 8;

    /**
     * v2.2.0 cover-disc spacing. Discs are decoupled from the subdivision:
     * a bent segment emits ceil(1.15·chord / (0.85·minWidth)) discs spread
     * evenly along the spline, so neighboring disc centers stay within
     * ~0.85·width — always inside the 2·radius contact distance that keeps
     * the union seamless, regardless of width. The old per-sub-vertex +
     * per-sub-midpoint scheme emitted 2·sub discs per segment INDEPENDENT of
     * width: at the 24-cap that was ~48 discs per accepted point (each an
     * addCircle in the Path), which — batched over 120 Hz stylus samples —
     * stalled fast wide-brush handwriting at deep zoom (the v2.2.0 "any wide
     * brush lags at 1000%" report). Wide strokes now emit FEWER, larger
     * discs; thin strokes emit more, smaller ones. Straight runs (sub == 1)
     * still emit nothing: a straight sub-quad has no sagitta to cover.
     */
    private static final float DISC_SPAN_FACTOR = 1.15f;
    private static final float DISC_SPACING_FACTOR = 0.85f;
    private static final int DISC_MAX_PER_SEGMENT = 48;

    /** Committed quads, 8 floats each: {l0x,l0y, l1x,l1y, r1x,r1y, r0x,r0y}. */
    private float[] quads = new float[64 * 8];
    private int quadCount;

    /**
     * Sagitta-cover discs, 3 floats each: {x, y, width} on the actual
     * Catmull-Rom (not the chord). v2.2.0: a bent segment emits discs spread
     * evenly along the spline so neighboring centers stay within
     * ~0.85·width (see DISC_SPACING_FACTOR) — the union blankets the outer
     * bulge at any width, and the per-point disc count scales DOWN as the
     * brush widens (fewer, larger discs) instead of being pinned to the
     * subdivision count. Straight runs (sub == 1) emit nothing.
     */
    private float[] midpoints = new float[64 * 3];
    private int midpointCount;

    /**
     * Transient tail strip (v2.2.0): the newest segment (x0,y0)→(x1,y1)
     * subdivided along the spline (Catmull-Rom with the 4th control point
     * folded onto x1 — its successor is unknown) into at most
     * {@link #TAIL_MAX_SUBDIVISIONS} quads plus per-sub-interval cover discs.
     * Rebuilt on every accepted point; the renderer draws it transiently each
     * frame. The former single straight tail quad cut bends by their sagitta
     * and read as a tear right at the pen tip on thin brushes at deep zoom.
     */
    private final float[] tailQuads = new float[TAIL_MAX_SUBDIVISIONS * 8];
    private int tailQuadCount;
    private final float[] tailDiscs = new float[TAIL_MAX_SUBDIVISIONS * 3];
    private int tailDiscCount;

    /** Last two accepted points; p0's normal is final once a quad committed. */
    private int accepted;
    private float x0, y0, w0; // start of the next committed/tail segment
    private float x1, y1, w1; // newest point (tail end)
    /** Final normal at (x0,y0), reused as the next quad's start normal. */
    private float nx0, ny0;

    /** Stroke head (first accepted point) + its forward-difference normal,
     *  kept for the round start cap (v1.4.17). */
    private float headX, headY, headW, headNx, headNy;
    private boolean headValid;

    /** Knot before (x0,y0) — the spline's leading control point (v1.4.18).
     *  Until the first commit there is none and (x0,y0) itself is padded. */
    private float zx, zy;
    private boolean hasZ;
    /** Scratch for {@link SplineRefiner#eval}. */
    private final float[] crOut = new float[4];
    private final float[] crScratch = new float[4];
    /** Scratch for the sagitta-cover midpoint eval (kept separate so the
     *  interior-knot eval in the quad loop is never clobbered). */
    private final float[] midOut = new float[4];
    private final float[] midScratch = new float[4];
    /** Final device pixels per page unit for scale-adaptive tessellation. */
    private float pixelsPerPageUnit = 1f;

    public void setPixelsPerPageUnit(float scale) {
        pixelsPerPageUnit = Float.isFinite(scale) && scale > 1f ? scale : 1f;
    }

    /** Drops all state; call on stroke start/cancel. */
    public void reset() {
        quadCount = 0;
        midpointCount = 0;
        tailQuadCount = 0;
        tailDiscCount = 0;
        accepted = 0;
        headValid = false;
        hasZ = false;
    }

    /**
     * Accepts one styled point. Points closer than the weld distance to the
     * previous accepted point are dropped (same rule as the full mesh).
     * Every accepted point beyond the second commits one quad.
     */
    public void addPoint(float x, float y, float width) {
        if (accepted == 0) {
            x0 = x;
            y0 = y;
            w0 = width;
            accepted = 1;
            return;
        }
        float dx = x - x1OrX0();
        float dy = y - y1OrY0();
        float minDist = Math.max(WELD_DISTANCE, width * WELD_WIDTH_FACTOR);
        if (dx * dx + dy * dy < minDist * minDist) {
            return; // welded: vertex density tracks stroke width
        }
        if (accepted == 1) {
            x1 = x;
            y1 = y;
            w1 = width;
            accepted = 2;
            // Head normal is final now (forward difference onto the second
            // point) — freeze it for the round start cap.
            headX = x0;
            headY = y0;
            headW = w0;
            headNx = forwardNormalX(x0, y0, x1, y1);
            headNy = forwardNormalY(x0, y0, x1, y1);
            headValid = true;
            rebuildTailStrip();
            return;
        }
        // Third point known: (x0,y0)'s successor (x1,y1) gets its final
        // central-difference normal; commit segment (x0,y0) -> (x1,y1),
        // Catmull-Rom subdivided when it bends (v1.4.18).
        float nx1 = centralNormalX(x0, y0, x, y);
        float ny1 = centralNormalY(x0, y0, x, y);
        if (quadCount == 0) {
            // Head point: forward difference, like the full mesh.
            nx0 = forwardNormalX(x0, y0, x1, y1);
            ny0 = forwardNormalY(x0, y0, x1, y1);
        }
        float cx0 = hasZ ? zx : x0;
        float cy0 = hasZ ? zy : y0;
        int demand = SplineRefiner.subdivisions(
                cx0, cy0, x0, y0, x1, y1, x, y, pixelsPerPageUnit);
        int sub = Math.min(demand, LIVE_MAX_SUBDIVISIONS);
        // Sub-knot chain: q holds the current sub-segment start; each
        // iteration appends one quad to the next sub-point. End normals at
        // the two real knots are the exact joint chords shared with the
        // neighboring committed segments; interior normals follow the spline
        // tangent.
        float qx = x0, qy = y0, qnx = nx0, qny = ny0, qw = w0;
        for (int j = 1; j <= sub; j++) {
            float ex;
            float ey;
            float enx;
            float eny;
            float ew;
            if (j == sub) {
                ex = x1;
                ey = y1;
                enx = nx1;
                eny = ny1;
                ew = w1;
            } else {
                float t = (float) j / sub;
                SplineRefiner.eval(cx0, cy0, x0, y0, x1, y1, x, y, t, crOut, crScratch);
                ex = crOut[0];
                ey = crOut[1];
                float tl = (float) Math.hypot(crOut[2], crOut[3]);
                if (tl > MIN_DIRECTION_LEN) {
                    enx = -crOut[3] / tl;
                    eny = crOut[2] / tl;
                } else {
                    enx = qnx;
                    eny = qny;
                }
                ew = w0 + (w1 - w0) * t;
            }
            float h0 = qw / 2f;
            float h1 = ew / 2f;
            appendQuad(qx + qnx * h0, qy + qny * h0,
                    ex + enx * h1, ey + eny * h1,
                    ex - enx * h1, ey - eny * h1,
                    qx - qnx * h0, qy - qny * h0);
            qx = ex;
            qy = ey;
            qnx = enx;
            qny = eny;
            qw = ew;
        }
        if (sub > 1) {
            // v2.2.0 width-adaptive cover discs (see DISC_SPACING_FACTOR):
            // spread along the spline so neighbors stay within ~0.85·width.
            float chord = (float) Math.hypot(x1 - x0, y1 - y0);
            float minW = Math.max(Math.min(w0, w1), 1e-3f);
            int discs = (int) Math.ceil(DISC_SPAN_FACTOR * chord
                    / (DISC_SPACING_FACTOR * minW));
            discs = Math.max(1, Math.min(discs, DISC_MAX_PER_SEGMENT));
            for (int i = 0; i < discs; i++) {
                float t = (i + 0.5f) / discs;
                SplineRefiner.eval(cx0, cy0, x0, y0, x1, y1, x, y, t,
                        midOut, midScratch);
                appendMidpoint(midOut[0], midOut[1], w0 + (w1 - w0) * t);
            }
        }
        zx = x0;
        zy = y0;
        hasZ = true;
        nx0 = nx1;
        ny0 = ny1;
        x0 = x1;
        y0 = y1;
        w0 = w1;
        x1 = x;
        y1 = y;
        w1 = width;
        rebuildTailStrip();
    }

    /** Number of committed quads in {@link #getQuads()}. */
    public int getQuadCount() {
        return quadCount;
    }

    /**
     * Committed quad buffer; only the first {@link #getQuadCount()} * 8
     * elements are valid. Quads never change once committed — the caller can
     * append them to a Path incrementally.
     */
    public float[] getQuads() {
        return quads;
    }

    /**
     * Sagitta-cover disc centers emitted for the committed (curving) quads:
     * each entry is {x, y, width} on the Catmull-Rom midpoint of one sub-quad.
     * The renderer draws an extra round disc at each, so the outer bulge a
     * straight sub-quad cuts at deep zoom is always covered. Only the first
     * {@link #getMidpointCount()} * 3 elements are valid.
     */
    public float[] getMidpoints() {
        return midpoints;
    }

    /** Number of committed sagitta-cover discs (curving sub-quads). */
    public int getMidpointCount() {
        return midpointCount;
    }

    // ------------------------------------------------------------------
    // Transient tail strip (v2.2.0)
    // ------------------------------------------------------------------

    /**
     * Rebuilds the transient tail strip for the newest segment
     * (x0,y0)→(x1,y1): the spline (Catmull-Rom over z, x0, x1, x1 — the
     * successor is unknown, so the 4th control point folds onto x1)
     * subdivided into at most {@link #TAIL_MAX_SUBDIVISIONS} quads, with a
     * cover disc per sub-interval. The renderer draws these transiently each
     * frame so the pen tip hugs the curve even before the segment's normal
     * is final (the old single straight tail quad tore on bends).
     */
    private void rebuildTailStrip() {
        tailQuadCount = 0;
        tailDiscCount = 0;
        if (accepted < 2) {
            return;
        }
        float czx = hasZ ? zx : x0;
        float czy = hasZ ? zy : y0;
        int demand = SplineRefiner.subdivisions(
                czx, czy, x0, y0, x1, y1, x1, y1, pixelsPerPageUnit);
        int sub = Math.min(Math.max(demand, 1), TAIL_MAX_SUBDIVISIONS);
        float startNx = quadCount == 0
                ? forwardNormalX(x0, y0, x1, y1) : nx0;
        float startNy = quadCount == 0
                ? forwardNormalY(x0, y0, x1, y1) : ny0;
        float endNx = forwardNormalX(x0, y0, x1, y1);
        float endNy = forwardNormalY(x0, y0, x1, y1);
        float qx = x0, qy = y0, qnx = startNx, qny = startNy, qw = w0;
        for (int j = 1; j <= sub; j++) {
            float ex;
            float ey;
            float enx;
            float eny;
            float ew;
            float tm = (j - 0.5f) / sub;
            SplineRefiner.eval(czx, czy, x0, y0, x1, y1, x1, y1, tm,
                    crOut, crScratch);
            appendTailDisc(crOut[0], crOut[1], w0 + (w1 - w0) * tm);
            if (j == sub) {
                ex = x1;
                ey = y1;
                enx = endNx;
                eny = endNy;
                ew = w1;
            } else {
                float t = (float) j / sub;
                SplineRefiner.eval(czx, czy, x0, y0, x1, y1, x1, y1, t,
                        crOut, crScratch);
                ex = crOut[0];
                ey = crOut[1];
                float tl = (float) Math.hypot(crOut[2], crOut[3]);
                if (tl > MIN_DIRECTION_LEN) {
                    enx = -crOut[3] / tl;
                    eny = crOut[2] / tl;
                } else {
                    enx = qnx;
                    eny = qny;
                }
                ew = w0 + (w1 - w0) * t;
            }
            float h0 = qw / 2f;
            float h1 = ew / 2f;
            appendTailQuad(qx + qnx * h0, qy + qny * h0,
                    ex + enx * h1, ey + eny * h1,
                    ex - enx * h1, ey - eny * h1,
                    qx - qnx * h0, qy - qny * h0);
            qx = ex;
            qy = ey;
            qnx = enx;
            qny = eny;
            qw = ew;
        }
    }

    /**
     * Transient tail strip quads, 8 floats each, same layout as
     * {@link #getQuads()}; only the first {@link #getTailQuadCount()} * 8
     * elements are valid. Rewritten on every accepted point.
     */
    public float[] getTailQuads() {
        return tailQuads;
    }

    /** Number of transient tail strip quads. */
    public int getTailQuadCount() {
        return tailQuadCount;
    }

    /**
     * Transient tail strip cover discs, 3 floats each {x, y, width}; only
     * the first {@link #getTailDiscCount()} * 3 elements are valid.
     */
    public float[] getTailDiscs() {
        return tailDiscs;
    }

    /** Number of transient tail strip cover discs. */
    public int getTailDiscCount() {
        return tailDiscCount;
    }

    private void appendTailQuad(float l0x, float l0y, float l1x, float l1y,
                                float r1x, float r1y, float r0x, float r0y) {
        if (tailQuadCount >= TAIL_MAX_SUBDIVISIONS) {
            return;
        }
        int o = tailQuadCount * 8;
        tailQuads[o] = l0x;
        tailQuads[o + 1] = l0y;
        tailQuads[o + 2] = l1x;
        tailQuads[o + 3] = l1y;
        tailQuads[o + 4] = r1x;
        tailQuads[o + 5] = r1y;
        tailQuads[o + 6] = r0x;
        tailQuads[o + 7] = r0y;
        tailQuadCount++;
    }

    private void appendTailDisc(float x, float y, float width) {
        if (tailDiscCount >= TAIL_MAX_SUBDIVISIONS) {
            return;
        }
        int o = tailDiscCount * 3;
        tailDiscs[o] = x;
        tailDiscs[o + 1] = y;
        tailDiscs[o + 2] = width;
        tailDiscCount++;
    }

    private void appendMidpoint(float x, float y, float width) {
        int need = (midpointCount + 1) * 3;
        if (need > midpoints.length) {
            float[] grown = new float[Math.max(need, midpoints.length * 2)];
            System.arraycopy(midpoints, 0, grown, 0, midpointCount * 3);
            midpoints = grown;
        }
        int o = midpointCount * 3;
        midpoints[o] = x;
        midpoints[o + 1] = y;
        midpoints[o + 2] = width;
        midpointCount++;
    }

    /**
     * Transient quad for the newest segment (x0,y0) -> (x1,y1): start normal
     * is final (matches the last committed quad's end chord), end normal is a
     * forward difference and may change when the next point arrives. The
     * caller should rebuild its tail path from this on every batch.
     *
     * @return false when fewer than two points were accepted
     */
    public boolean tailQuad(float[] out8) {
        if (accepted < 2) {
            return false;
        }
        float startNx = quadCount == 0
                ? forwardNormalX(x0, y0, x1, y1) : nx0;
        float startNy = quadCount == 0
                ? forwardNormalY(x0, y0, x1, y1) : ny0;
        float endNx = forwardNormalX(x0, y0, x1, y1);
        float endNy = forwardNormalY(x0, y0, x1, y1);
        float h0 = w0 / 2f;
        float h1 = w1 / 2f;
        out8[0] = x0 + startNx * h0;
        out8[1] = y0 + startNy * h0;
        out8[2] = x1 + endNx * h1;
        out8[3] = y1 + endNy * h1;
        out8[4] = x1 - endNx * h1;
        out8[5] = y1 - endNy * h1;
        out8[6] = x0 - startNx * h0;
        out8[7] = y0 - startNy * h0;
        return true;
    }

    /** Newest accepted styled point as {x, y, width}; false when empty. */
    public boolean getLastPoint(float[] out3) {
        if (accepted == 0) {
            return false;
        }
        out3[0] = accepted == 1 ? x0 : x1;
        out3[1] = accepted == 1 ? y0 : y1;
        out3[2] = accepted == 1 ? w0 : w1;
        return true;
    }

    /**
     * Round start cap (v1.4.17): closed sub-path polygon for the semicircle
     * at the first accepted point — left outline point, arc bulging backward
     * (against the stroke direction), right outline point — same geometry as
     * the start cap of {@link StrokeMesh#closedOutline()}. The caller draws
     * it once (moveTo -> lineTo... -> close) right after the head normal
     * becomes final.
     *
     * @param out capacity {@code (StrokeMesh.MAX_CAP_SEGMENTS + 1) * 2}
     * @return number of xy pairs written; 0 when fewer than two points were
     *         accepted (head normal not final yet)
     */
    public int headCap(float[] out) {
        if (!headValid) {
            return 0;
        }
        float r = headW / 2f;
        float base = (float) Math.atan2(headNy, headNx);
        int capSegments = StrokeMesh.capSegmentsForWidth(headW, pixelsPerPageUnit);
        int o = 0;
        out[o++] = headX + headNx * r; // left head point
        out[o++] = headY + headNy * r;
        for (int s = 1; s < capSegments; s++) {
            float t = (float) s / capSegments;
            // Left -> right sweeping through the backward bulge, like the
            // start cap in StrokeMesh (base + PI - t*PI reversed).
            float angle = base + t * (float) Math.PI;
            out[o++] = headX + r * (float) Math.cos(angle);
            out[o++] = headY + r * (float) Math.sin(angle);
        }
        out[o++] = headX - headNx * r; // right head point
        out[o++] = headY - headNy * r;
        return o / 2;
    }

    /**
     * Round tail cap (v1.4.17): closed sub-path polygon for the semicircle
     * at the newest point — left outline point, arc bulging forward (along
     * the stroke direction), right outline point — same geometry as the end
     * cap of {@link StrokeMesh#closedOutline()}. Transient like
     * {@link #tailQuad}: rebuild every batch.
     *
     * @param out capacity {@code (StrokeMesh.MAX_CAP_SEGMENTS + 1) * 2}
     * @return number of xy pairs written; 0 when fewer than two points were
     *         accepted
     */
    public int tailCap(float[] out) {
        if (accepted < 2) {
            return 0;
        }
        float nx = forwardNormalX(x0, y0, x1, y1);
        float ny = forwardNormalY(x0, y0, x1, y1);
        float base = (float) Math.atan2(ny, nx);
        float r = w1 / 2f;
        int capSegments = StrokeMesh.capSegmentsForWidth(w1, pixelsPerPageUnit);
        int o = 0;
        out[o++] = x1 + nx * r; // left tail point
        out[o++] = y1 + ny * r;
        for (int s = 1; s < capSegments; s++) {
            float t = (float) s / capSegments;
            // Left -> right sweeping through the forward bulge, like the
            // end cap in StrokeMesh (base - t*PI).
            float angle = base - t * (float) Math.PI;
            out[o++] = x1 + r * (float) Math.cos(angle);
            out[o++] = y1 + r * (float) Math.sin(angle);
        }
        out[o++] = x1 - nx * r; // right tail point
        out[o++] = y1 - ny * r;
        return o / 2;
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private float x1OrX0() {
        return accepted == 1 ? x0 : x1;
    }

    private float y1OrY0() {
        return accepted == 1 ? y0 : y1;
    }

    private void appendQuad(float l0x, float l0y, float l1x, float l1y,
                            float r1x, float r1y, float r0x, float r0y) {
        int need = (quadCount + 1) * 8;
        if (need > quads.length) {
            float[] grown = new float[Math.max(need, quads.length * 2)];
            System.arraycopy(quads, 0, grown, 0, quadCount * 8);
            quads = grown;
        }
        int o = quadCount * 8;
        quads[o] = l0x;
        quads[o + 1] = l0y;
        quads[o + 2] = l1x;
        quads[o + 3] = l1y;
        quads[o + 4] = r1x;
        quads[o + 5] = r1y;
        quads[o + 6] = r0x;
        quads[o + 7] = r0y;
        quadCount++;
    }

    /** Central-difference direction normal: perpendicular to (next - prev). */
    private static float centralNormalX(float px, float py, float nx, float ny) {
        return normalX(nx - px, ny - py);
    }

    private static float centralNormalY(float px, float py, float nx, float ny) {
        return normalY(nx - px, ny - py);
    }

    /** Forward-difference direction normal: perpendicular to (b - a). */
    private static float forwardNormalX(float ax, float ay, float bx, float by) {
        return normalX(bx - ax, by - ay);
    }

    private static float forwardNormalY(float ax, float ay, float bx, float by) {
        return normalY(bx - ax, by - ay);
    }

    /** Left normal of direction (dx,dy): (-dy, dx) normalized; 0 when degenerate. */
    private static float normalX(float dx, float dy) {
        float len = (float) Math.hypot(dx, dy);
        return len > MIN_DIRECTION_LEN ? -dy / len : 0f;
    }

    private static float normalY(float dx, float dy) {
        float len = (float) Math.hypot(dx, dy);
        return len > MIN_DIRECTION_LEN ? dx / len : 0f;
    }
}
