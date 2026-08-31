package com.leslie.colorosnote.feature.editor;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;
import android.widget.SeekBar;

import com.leslie.colorosnote.core.ink.EraserEngine;
import com.leslie.colorosnote.feature.editor.databinding.PopupEraserBinding;

/**
 * Eraser popover (second tap on the eraser tool): erase mode
 * (pixel/stroke/area), layer filter (all/ink-only/highlight-only) and size.
 * Mutates the shared {@link EraserConfig} live and reports via the listener.
 */
public final class EraserPopup {

    public interface Listener {
        void onEraserConfigChanged(EraserConfig config);
    }

    private EraserPopup() {
    }

    public static PopupWindow show(View anchor, EraserConfig config, Listener listener) {
        PopupEraserBinding b = PopupEraserBinding.inflate(
                LayoutInflater.from(anchor.getContext()));

        switch (config.mode) {
            case STROKE:
                b.groupEraserMode.check(R.id.radio_eraser_stroke);
                break;
            case AREA:
                b.groupEraserMode.check(R.id.radio_eraser_area);
                break;
            case PIXEL:
            default:
                b.groupEraserMode.check(R.id.radio_eraser_pixel);
                break;
        }
        switch (config.filter) {
            case INK_ONLY:
                b.groupEraserFilter.check(R.id.radio_filter_ink);
                break;
            case HIGHLIGHT_ONLY:
                b.groupEraserFilter.check(R.id.radio_filter_highlight);
                break;
            case ALL:
            default:
                b.groupEraserFilter.check(R.id.radio_filter_all);
                break;
        }
        b.seekEraserSize.setProgress(Math.round(config.radius));

        b.groupEraserMode.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.radio_eraser_stroke) {
                config.mode = EraserEngine.Mode.STROKE;
            } else if (checkedId == R.id.radio_eraser_area) {
                config.mode = EraserEngine.Mode.AREA;
            } else {
                config.mode = EraserEngine.Mode.PIXEL;
            }
            notifyChange(listener, config);
        });
        b.groupEraserFilter.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.radio_filter_ink) {
                config.filter = EraserEngine.TypeFilter.INK_ONLY;
            } else if (checkedId == R.id.radio_filter_highlight) {
                config.filter = EraserEngine.TypeFilter.HIGHLIGHT_ONLY;
            } else {
                config.filter = EraserEngine.TypeFilter.ALL;
            }
            notifyChange(listener, config);
        });
        b.seekEraserSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    config.radius = Math.max(4f, progress);
                    notifyChange(listener, config);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
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

    private static void notifyChange(Listener listener, EraserConfig config) {
        if (listener != null) {
            listener.onEraserConfigChanged(config);
        }
    }
}
