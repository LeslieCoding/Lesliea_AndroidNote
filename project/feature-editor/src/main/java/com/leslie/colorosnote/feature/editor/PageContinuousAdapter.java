package com.leslie.colorosnote.feature.editor;

import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.leslie.colorosnote.core.ink.PalmRejectionPolicy;
import com.leslie.colorosnote.core.model.BrushPreset;
import com.leslie.colorosnote.core.model.Page;
import com.leslie.colorosnote.core.model.Stroke;
import com.leslie.colorosnote.core.render.InkView;
import com.leslie.colorosnote.core.render.PageBackgroundProvider;
import com.leslie.colorosnote.feature.editor.databinding.ItemPageContinuousBinding;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Continuous vertical scroll mode (spec 26): one InkView per page inside a
 * RecyclerView. Each InkView keeps its own fit-to-width viewport; fingers
 * scroll the list (pages are stylus-only here) and the stylus writes on the
 * page under it. The stylus long-press-erase shortcut works per page via
 * {@link EraseCallback}; the toolbar eraser/lasso stay single-page-only.
 */
public final class PageContinuousAdapter
        extends RecyclerView.Adapter<PageContinuousAdapter.VH> {

    public interface Listener {
        void onStrokeFinished(Page page, Stroke stroke);
    }

    /** Host-side erase plumbing for the stylus long-press shortcut. */
    public interface EraseCallback {
        void onContinuousEraseBegin(Page page);

        void onContinuousEraseDelta(Page page, float[] pagePath);

        void onContinuousEraseEnd(Page page);

        /** Current eraser radius in page units (for the cursor overlay). */
        float eraserRadiusPage();
    }

    private final List<Page> pages;
    private final Listener listener;
    private final Map<String, InkView> activeViews = new HashMap<>();
    private BrushPreset brush = BrushPreset.fountainPen();
    private boolean straightenEnabled;
    private PageBackgroundProvider backgroundProvider;
    private EraseCallback eraseCallback;

    public PageContinuousAdapter(List<Page> pages, Listener listener) {
        this.pages = pages;
        this.listener = listener;
    }

    public void setEraseCallback(EraseCallback callback) {
        eraseCallback = callback;
    }

    /**
     * Document background provider (PDF/OFFICE imports); applied to every
     * page InkView's renderer, current and future.
     */
    public void setPageBackgroundProvider(PageBackgroundProvider provider) {
        backgroundProvider = provider;
        for (InkView v : activeViews.values()) {
            v.getRenderer().setPageBackgroundProvider(provider);
        }
    }

    public void setBrush(BrushPreset preset) {
        brush = preset;
        for (InkView v : activeViews.values()) {
            v.setBrush(preset);
        }
    }

    /** Highlighter straighten flag, forwarded to every page InkView. */
    public void setStraightenEnabled(boolean enabled) {
        straightenEnabled = enabled;
        for (InkView v : activeViews.values()) {
            v.setStraightenEnabled(enabled);
        }
    }

    /** Re-renders the completed layer of a page if its view is attached. */
    public void refreshPage(String pageId) {
        InkView v = activeViews.get(pageId);
        if (v != null) {
            v.refreshCompletedLayer();
        }
    }

    public void refreshAllPages() {
        for (InkView v : activeViews.values()) {
            v.refreshCompletedLayer();
        }
    }

    public void releaseAll() {
        for (InkView v : activeViews.values()) {
            v.release();
        }
        activeViews.clear();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(ItemPageContinuousBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        Page page = pages.get(position);
        InkView inkView = holder.binding.pageInkView;

        // Fit the page aspect ratio to the list width. During onBind the
        // item view may not be attached to the RecyclerView yet, so the
        // parent can be null — fall back to a posted pass in that case.
        ViewGroup parent = (ViewGroup) holder.itemView.getParent();
        int parentWidth = parent != null ? parent.getWidth() : 0;
        if (parentWidth > 0) {
            applyHeight(holder, page, parentWidth);
        } else {
            holder.itemView.post(() -> {
                ViewGroup p = (ViewGroup) holder.itemView.getParent();
                int w = p != null ? p.getWidth() : 0;
                if (w > 0) {
                    applyHeight(holder, page, w);
                }
            });
        }

        // Fingers scroll the list: stylus-only writing in continuous mode.
        inkView.setPalmRejectionPolicy(PalmRejectionPolicy.STYLUS_ONLY);
        inkView.setBrush(brush);
        inkView.setStraightenEnabled(straightenEnabled);
        inkView.getRenderer().setPageBackgroundProvider(backgroundProvider);
        inkView.setStrokeListener(stroke -> listener.onStrokeFinished(page, stroke));
        inkView.setOnTouchListener(new LongPressEraseTouchListener(page));
        if (inkView.getPage() == page) {
            inkView.refreshCompletedLayer();
        } else {
            inkView.setPage(page);
        }
        holder.bindPageId = page.id;
    }

    @Override
    public void onViewAttachedToWindow(@NonNull VH holder) {
        if (holder.bindPageId != null) {
            activeViews.put(holder.bindPageId, holder.binding.pageInkView);
        }
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull VH holder) {
        if (holder.bindPageId != null) {
            activeViews.remove(holder.bindPageId);
        }
    }

    @Override
    public int getItemCount() {
        return pages.size();
    }

    private static void applyHeight(VH holder, Page page, int parentWidth) {
        int height = Math.max(1, Math.round(parentWidth * page.height / page.width));
        ViewGroup.LayoutParams lp = holder.binding.pageInkView.getLayoutParams();
        if (lp.height != height) {
            lp.height = height;
            holder.binding.pageInkView.setLayoutParams(lp);
        }
    }

    /**
     * Per-page touch watcher implementing the stylus long-press-erase shortcut
     * in continuous mode. Until the long-press fires, every event falls through
     * to the InkView untouched (writing); once fired, the barely-started stroke
     * is cancelled and the rest of the stream erases on this page. Movement
     * beyond the touch slop before the timeout cancels the shortcut, so normal
     * writing never triggers it.
     */
    private final class LongPressEraseTouchListener implements View.OnTouchListener {

        private final Page page;
        private float downX;
        private float downY;
        /** Pointer id the shortcut is armed for; -1 = not armed. */
        private int stylusPointerId = -1;
        private boolean moved;
        private boolean erasing;
        private final Runnable longPressRunnable = this::onLongPressTimeout;
        private InkView view;

        LongPressEraseTouchListener(Page page) {
            this.page = page;
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            InkView inkView = (InkView) v;
            // v1.1.9: standard long-press semantics (1x touch slop). The
            // v1.1.7 3x slop let slow small handwriting stay "stationary"
            // for 500ms and fire the eraser mid-writing.
            float slopPx = ViewConfiguration.get(v.getContext()).getScaledTouchSlop();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    this.view = inkView;
                    moved = false;
                    erasing = false;
                    stylusPointerId = -1;
                    if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
                            && eraseCallback != null) {
                        arm(inkView, event.getPointerId(0),
                                event.getX(0), event.getY(0));
                    }
                    return false; // let the InkView start the stroke
                case MotionEvent.ACTION_POINTER_DOWN: {
                    int ai = event.getActionIndex();
                    if (event.getToolType(ai) == MotionEvent.TOOL_TYPE_STYLUS) {
                        // Pen joins while a palm/finger is already down: arm
                        // the shortcut for the pen's own pointer.
                        if (stylusPointerId < 0 && eraseCallback != null && !erasing) {
                            arm(inkView, event.getPointerId(ai),
                                    event.getX(ai), event.getY(ai));
                        }
                    } else {
                        // A finger joined while the pen was down: that is a
                        // palm — the eraser must never arm mid-gesture.
                        inkView.removeCallbacks(longPressRunnable);
                    }
                    return erasing;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (stylusPointerId < 0) {
                        return false;
                    }
                    int idx = event.findPointerIndex(stylusPointerId);
                    if (idx < 0) {
                        return erasing;
                    }
                    if (erasing) {
                        float[] delta = collectPageDelta(inkView, event, idx);
                        if (delta.length >= 2) {
                            eraseCallback.onContinuousEraseDelta(page, delta);
                        }
                        showCursor(inkView, event.getX(idx), event.getY(idx));
                        return true; // consumed: the cancelled stroke must not resume
                    }
                    if (!moved) {
                        float dx = event.getX(idx) - downX;
                        float dy = event.getY(idx) - downY;
                        if (dx * dx + dy * dy > slopPx * slopPx) {
                            moved = true;
                            inkView.removeCallbacks(longPressRunnable);
                        }
                    }
                    return false;
                }
                case MotionEvent.ACTION_POINTER_UP:
                    if (event.getPointerId(event.getActionIndex()) == stylusPointerId) {
                        return finish(inkView);
                    }
                    return erasing;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    return finish(inkView);
                default:
                    return erasing;
            }
        }

        /** Arms the long-press timer for one stylus pointer. */
        private void arm(InkView inkView, int pointerId, float x, float y) {
            stylusPointerId = pointerId;
            downX = x;
            downY = y;
            inkView.postDelayed(longPressRunnable,
                    ViewConfiguration.getLongPressTimeout());
        }

        /** Ends the gesture: stops erasing, hides the cursor, disarms. */
        private boolean finish(InkView inkView) {
            inkView.removeCallbacks(longPressRunnable);
            stylusPointerId = -1;
            if (erasing) {
                erasing = false;
                inkView.hideEraserCursor();
                eraseCallback.onContinuousEraseEnd(page);
                return true;
            }
            return false;
        }

        private void showCursor(InkView inkView, float viewX, float viewY) {
            if (eraseCallback == null) {
                return;
            }
            float radiusPx = eraseCallback.eraserRadiusPage()
                    * inkView.getViewport().getScale();
            inkView.showEraserCursor(viewX, viewY, radiusPx);
        }

        private void onLongPressTimeout() {
            InkView inkView = view;
            if (inkView == null || stylusPointerId < 0 || moved || erasing
                    || eraseCallback == null) {
                return;
            }
            // View-recycling guard: a stale runnable from a previous bind must
            // not fire on a page this holder no longer displays.
            if (inkView.getPage() != page) {
                return;
            }
            erasing = true;
            if (inkView.getStrokeBuilder() != null) {
                inkView.getStrokeBuilder().cancelStroke();
            }
            eraseCallback.onContinuousEraseBegin(page);
            float[] pt = new float[2];
            inkView.getViewport().screenToPage(downX, downY, pt);
            eraseCallback.onContinuousEraseDelta(page, pt);
            showCursor(inkView, downX, downY);
        }

        /** Historical + current points of this MOVE in page coordinates. */
        private float[] collectPageDelta(InkView inkView, MotionEvent event, int idx) {
            int history = event.getHistorySize();
            float[] out = new float[(history + 1) * 2];
            float[] tmp = new float[2];
            int n = 0;
            for (int h = 0; h < history; h++) {
                inkView.getViewport().screenToPage(
                        event.getHistoricalX(idx, h), event.getHistoricalY(idx, h), tmp);
                out[n++] = tmp[0];
                out[n++] = tmp[1];
            }
            inkView.getViewport().screenToPage(event.getX(idx), event.getY(idx), tmp);
            out[n++] = tmp[0];
            out[n++] = tmp[1];
            return n == out.length ? out : java.util.Arrays.copyOf(out, n);
        }
    }

    static final class VH extends RecyclerView.ViewHolder {
        final ItemPageContinuousBinding binding;
        String bindPageId;

        VH(ItemPageContinuousBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
