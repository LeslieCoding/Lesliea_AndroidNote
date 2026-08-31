package com.leslie.colorosnote.core.render;

import android.graphics.Matrix;
import android.graphics.RectF;

/**
 * Page-coordinate <-> screen-coordinate transform (zoom/pan). Wraps an
 * Android Matrix; the ink pipeline and hit testers use it to convert stylus
 * input into logical page units and to compute visible regions.
 */
public final class ViewportTransform {

    private final Matrix pageToScreen = new Matrix();
    private final Matrix screenToPage = new Matrix();
    /**
     * True once the user pinched/panned; {@link #fitPage} clears it. Hosts use
     * it to avoid re-fitting (resetting the user's zoom) on view resizes such
     * as bars auto-hiding in immersive mode.
     */
    private boolean userTransformed;

    /** Maximum zoom as a multiple of the fitted (100%) scale. */
    private static final float MAX_ZOOM_FACTOR = 10f;

    /** Scale captured by the last {@link #fitPage} call (the 100% baseline). */
    private float fitScale = 1f;

    /** Fits the page centered into the viewport with the given padding (px). */
    public void fitPage(float pageWidth, float pageHeight,
                        float viewWidth, float viewHeight, float paddingPx) {
        float usableW = Math.max(1f, viewWidth - 2f * paddingPx);
        float usableH = Math.max(1f, viewHeight - 2f * paddingPx);
        float scale = Math.min(usableW / pageWidth, usableH / pageHeight);
        float dx = (viewWidth - pageWidth * scale) / 2f;
        float dy = (viewHeight - pageHeight * scale) / 2f;
        pageToScreen.reset();
        pageToScreen.setScale(scale, scale);
        pageToScreen.postTranslate(dx, dy);
        fitScale = scale;
        userTransformed = false;
        updateInverse();
    }

    /**
     * Maps an arbitrary page-space rectangle onto the whole view, left/top
     * aligned and using a single uniform scale (the smaller of the two axes'
     * ratios, so nothing in the requested rect is cropped).
     *
     * <p>Used by the zoom-writing window, which shows one magnified band of
     * the page in a separate view. {@code fitScale} is still set to the scale
     * at which the *entire* page would fit this view, so quality budgets that
     * are expressed as zoom levels (see {@code LiveInkQualityPolicy}) keep
     * their normal meaning here.
     *
     * @param pageWidth  full page width, for the fit-scale reference
     * @param pageHeight full page height, for the fit-scale reference
     */
    public void showPageRect(float rectLeft, float rectTop,
                             float rectWidth, float rectHeight,
                             float pageWidth, float pageHeight,
                             float viewWidth, float viewHeight) {
        float w = Math.max(1e-3f, rectWidth);
        float h = Math.max(1e-3f, rectHeight);
        float scale = Math.min(viewWidth / w, viewHeight / h);
        pageToScreen.reset();
        pageToScreen.setScale(scale, scale);
        pageToScreen.postTranslate(-rectLeft * scale, -rectTop * scale);
        fitScale = Math.min(viewWidth / Math.max(1e-3f, pageWidth),
                viewHeight / Math.max(1e-3f, pageHeight));
        userTransformed = true;
        updateInverse();
    }

    /** Pans by screen-space deltas. */
    public void panBy(float dxScreen, float dyScreen) {
        pageToScreen.postTranslate(dxScreen, dyScreen);
        userTransformed = true;
        updateInverse();
    }

    /** Zooms around a screen-space focus point, capped at {@link #MAX_ZOOM_FACTOR}. */
    public void zoomBy(float factor, float focusX, float focusY) {
        float maxScale = fitScale * MAX_ZOOM_FACTOR;
        float current = getScale();
        if (factor > 1f && current >= maxScale) {
            return; // already at the cap
        }
        if (current * factor > maxScale) {
            factor = maxScale / current;
        }
        pageToScreen.postScale(factor, factor, focusX, focusY);
        userTransformed = true;
        updateInverse();
    }

    /** True when the user has manually panned/zoomed since the last fit. */
    public boolean hasUserTransform() {
        return userTransformed;
    }

    public float getScale() {
        float[] v = new float[9];
        pageToScreen.getValues(v);
        return v[Matrix.MSCALE_X];
    }

    /**
     * Scale at which the page exactly fits the viewport — the "100%" the zoom
     * indicator and {@link #MAX_ZOOM_FACTOR} are measured against. It depends
     * on the screen and the page size, so quality budgets that are meant to be
     * "per zoom level" must divide by this rather than compare against an
     * absolute scale.
     */
    public float getFitScale() {
        return fitScale;
    }

    /** Matrix mapping page coordinates to screen coordinates. */
    public Matrix getPageToScreen() {
        return pageToScreen;
    }

    /** Converts a view/screen point to page coordinates into out[2]. */
    public void screenToPage(float screenX, float screenY, float[] out2) {
        float[] pts = {screenX, screenY};
        screenToPage.mapPoints(pts);
        out2[0] = pts[0];
        out2[1] = pts[1];
    }

    /** Converts a page point to screen coordinates into out[2]. */
    public void pageToScreen(float pageX, float pageY, float[] out2) {
        float[] pts = {pageX, pageY};
        pageToScreen.mapPoints(pts);
        out2[0] = pts[0];
        out2[1] = pts[1];
    }

    /** Maps a page-space rect to screen space. */
    public RectF pageRectToScreen(float left, float top, float right, float bottom) {
        RectF r = new RectF(left, top, right, bottom);
        pageToScreen.mapRect(r);
        return r;
    }

    /** Visible region in page coordinates for the given viewport size. */
    public RectF visiblePageBounds(float viewWidth, float viewHeight) {
        RectF r = new RectF(0f, 0f, viewWidth, viewHeight);
        screenToPage.mapRect(r);
        return r;
    }

    private void updateInverse() {
        pageToScreen.invert(screenToPage);
    }
}
