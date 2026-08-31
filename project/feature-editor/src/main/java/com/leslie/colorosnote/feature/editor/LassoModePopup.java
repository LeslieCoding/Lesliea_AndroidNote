package com.leslie.colorosnote.feature.editor;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;

import com.leslie.colorosnote.core.ink.LassoEngine;
import com.leslie.colorosnote.feature.editor.databinding.PopupLassoModeBinding;

/**
 * Lasso mode popover (second tap on the lasso tool): free-form or rectangle
 * selection, in the same premium card style as the brush/eraser popovers.
 */
public final class LassoModePopup {

    public interface Listener {
        void onLassoModeChanged(LassoEngine.Mode mode);
    }

    private LassoModePopup() {
    }

    public static PopupWindow show(View anchor, LassoEngine.Mode current,
                                   Listener listener) {
        PopupLassoModeBinding b = PopupLassoModeBinding.inflate(
                LayoutInflater.from(anchor.getContext()));
        b.groupLassoMode.check(current == LassoEngine.Mode.RECT
                ? R.id.radio_lasso_rect : R.id.radio_lasso_free);
        b.groupLassoMode.setOnCheckedChangeListener((group, checkedId) -> {
            if (listener != null) {
                listener.onLassoModeChanged(checkedId == R.id.radio_lasso_rect
                        ? LassoEngine.Mode.RECT : LassoEngine.Mode.FREE);
            }
        });

        PopupWindow popup = new PopupWindow(b.getRoot(),
                BrushPropertiesPopup.dp(anchor.getContext(), 280),
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                android.graphics.Color.TRANSPARENT));
        popup.setElevation(16f);
        popup.showAsDropDown(anchor, 0, 8);
        return popup;
    }
}
