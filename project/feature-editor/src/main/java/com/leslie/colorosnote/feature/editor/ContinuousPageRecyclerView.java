package com.leslie.colorosnote.feature.editor;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Continuous-scroll page list. A stock RecyclerView intercepts every vertical
 * drag regardless of the input tool, which made the stylus "turn into a page
 * flipper" in continuous mode. Here a touch stream started by the stylus (or
 * the hardware eraser tail) is never intercepted: the page's InkView keeps the
 * stream and the pen always writes. Finger streams scroll normally.
 */
public final class ContinuousPageRecyclerView extends RecyclerView {

    /** True while the active touch stream was started by a stylus/eraser. */
    private boolean stylusStream;

    public ContinuousPageRecyclerView(@NonNull Context context) {
        super(context);
    }

    public ContinuousPageRecyclerView(@NonNull Context context,
                                      @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public ContinuousPageRecyclerView(@NonNull Context context,
                                      @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        trackStreamTool(e);
        if (stylusStream) {
            return false;
        }
        return super.onInterceptTouchEvent(e);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        trackStreamTool(e);
        if (stylusStream) {
            // The page InkView did not consume this stylus event; still refuse
            // to scroll with the pen.
            return false;
        }
        return super.onTouchEvent(e);
    }

    private void trackStreamTool(MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            int tool = e.getToolType(0);
            stylusStream = tool == MotionEvent.TOOL_TYPE_STYLUS
                    || tool == MotionEvent.TOOL_TYPE_ERASER;
        } else if (action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_CANCEL) {
            stylusStream = false;
        }
    }
}
