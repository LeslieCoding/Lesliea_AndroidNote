package com.leslie.colorosnote.feature.editor;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.SeekBar;
import android.widget.TextView;

import com.leslie.colorosnote.core.model.BrushPreset;
import com.leslie.colorosnote.feature.editor.databinding.PopupBrushPropsBinding;

/**
 * Brush properties popover (second tap on an already-selected pen tool):
 * 12-color palette + custom hex color and numeric slider rows — width (pt),
 * opacity, pressure sensitivity, smoothing (denoise), velocity and tilt
 * (v1.4.21: the former coarse pressure-curve radio group was replaced by the
 * numeric rows) — plus the highlighter "straighten" placeholder toggle.
 * Mutations are applied live to the preset instance and reported via the
 * listener so the host can persist them.
 *
 * <p>The content scrolls when taller than the space below the anchor: the
 * layout root is a ScrollView and the popup height is capped to the available
 * space instead of clipping (see popup_brush_props.xml header).
 */
public final class BrushPropertiesPopup {

    public interface Listener {
        void onPresetChanged(EditorTool tool, BrushPreset preset, boolean straighten);
    }

    /** Preset palette (12 colors); shared with the selection color picker. */
    static final int[] PALETTE = {
            0xFF17181A, 0xFF6B7078, 0xFFB9BEC6, 0xFFFFFFFF,
            0xFFD92D20, 0xFFF97316, 0xFFFFF200, 0xFF12B76A,
            0xFF0E7490, 0xFF2F6BFF, 0xFF7A5AF8, 0xFFEC4899
    };

    private interface FloatChange {
        void onChanged(float v);
    }

    private BrushPropertiesPopup() {
    }

    public static PopupWindow show(View anchor, EditorTool tool, BrushPreset preset,
                                   boolean straighten, Listener listener) {
        Context context = anchor.getContext();
        PopupBrushPropsBinding b = PopupBrushPropsBinding.inflate(LayoutInflater.from(context));

        // v3.2.3: the fountain pen's slider floor is raised (see
        // WidthSliderMapper). A stored sub-floor preset (e.g. 0.5pt from an
        // older install) is bumped up here so the slider, the preset and the
        // persisted value always agree.
        float widthMin = WidthSliderMapper.minWidthFor(tool);
        if (preset.baseWidth < widthMin) {
            float factor = widthMin / Math.max(0.01f, preset.baseWidth);
            preset.baseWidth = widthMin;
            preset.minWidth *= factor;
            preset.maxWidth *= factor;
            notifyChange(listener, tool, preset, straighten);
        }

        buildStyleRow(context, b, tool, preset, widthMin, listener);

        buildPalette(context, b.paletteGrid, preset, selected -> {
            preset.color = selected;
            b.editCustomColor.setText(String.format("#%08X", selected));
            notifyChange(listener, tool, preset, b.switchStraighten.isChecked());
        });

        b.editCustomColor.setText(String.format("#%08X", preset.color));
        b.btnApplyColor.setOnClickListener(v -> {
            try {
                int color = Color.parseColor(b.editCustomColor.getText().toString().trim());
                preset.color = color;
                refreshPaletteSelection(b.paletteGrid, color);
                notifyChange(listener, tool, preset, b.switchStraighten.isChecked());
            } catch (IllegalArgumentException e) {
                b.editCustomColor.setError("#RRGGBB");
            }
        });

        // Width: min..60pt in 0.5pt steps (WidthSliderMapper; the fountain pen
        // floor is raised per-tool); min/max scaled proportionally so
        // pressure-sensitive brushes keep their dynamic range.
        b.seekWidth.setMax(WidthSliderMapper.maxProgress(widthMin));
        b.seekWidth.setProgress(WidthSliderMapper.widthToProgress(preset.baseWidth, widthMin));
        b.textWidthValue.setText(WidthSliderMapper.label(preset.baseWidth, widthMin));
        b.seekWidth.setOnSeekBarChangeListener(new SimpleSeekBar() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                float newBase = WidthSliderMapper.progressToWidth(progress, widthMin);
                float factor = newBase / Math.max(0.01f, preset.baseWidth);
                preset.baseWidth = newBase;
                preset.minWidth *= factor;
                preset.maxWidth *= factor;
                if (preset.brushType == BrushPreset.BrushType.HIGHLIGHTER
                        || preset.brushType == BrushPreset.BrushType.BALLPOINT
                        || preset.brushType == BrushPreset.BrushType.PENCIL) {
                    preset.minWidth = newBase;
                    preset.maxWidth = newBase;
                }
                // Re-apply the pressure spread on fixed-width brushes so an
                // active pressure sensitivity survives width changes.
                BrushParamMapping.applyFixedWidthSpread(preset);
                b.textWidthValue.setText(WidthSliderMapper.label(newBase, widthMin));
                notifyChange(listener, tool, preset, b.switchStraighten.isChecked());
            }
        });

        // Opacity: 10%..100%.
        b.seekOpacity.setProgress(clamp(Math.round(preset.opacity * 100f) - 10,
                0, b.seekOpacity.getMax()));
        b.seekOpacity.setOnSeekBarChangeListener(new SimpleSeekBar() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                preset.opacity = (progress + 10) / 100f;
                notifyChange(listener, tool, preset, b.switchStraighten.isChecked());
            }
        });

        // Numeric rows (v1.4.21): pressure sensitivity / smoothing / velocity / tilt.
        bindPercentRow(b.seekPressure, b.textPressureValue,
                BrushParamMapping.pressureSensitivity(preset), v -> {
                    BrushParamMapping.setPressureSensitivity(preset, v);
                    notifyChange(listener, tool, preset, b.switchStraighten.isChecked());
                });
        bindPercentRow(b.seekSmoothing, b.textSmoothingValue, preset.smoothing, v -> {
            preset.smoothing = v;
            notifyChange(listener, tool, preset, b.switchStraighten.isChecked());
        });
        bindPercentRow(b.seekVelocity, b.textVelocityValue, preset.velocityToWidth, v -> {
            preset.velocityToWidth = v;
            notifyChange(listener, tool, preset, b.switchStraighten.isChecked());
        });
        bindPercentRow(b.seekTilt, b.textTiltValue,
                BrushParamMapping.tiltSensitivity(preset), v -> {
                    BrushParamMapping.setTiltSensitivity(preset, v);
                    notifyChange(listener, tool, preset, b.switchStraighten.isChecked());
                });

        // Highlighter straighten: placeholder, persisted per tool; the
        // straightening itself lands with the shape-correction pipeline.
        b.switchStraighten.setVisibility(
                tool == EditorTool.HIGHLIGHTER ? View.VISIBLE : View.GONE);
        b.switchStraighten.setChecked(straighten);
        b.switchStraighten.setOnCheckedChangeListener((buttonView, isChecked) ->
                notifyChange(listener, tool, preset, isChecked));

        // Cap the popup height to the space below the anchor so overflow
        // scrolls (the layout root is a ScrollView) instead of clipping.
        int widthPx = dp(context, 300);
        b.getRoot().measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.UNSPECIFIED);
        int contentHeight = b.getRoot().getMeasuredHeight();
        int[] anchorLoc = new int[2];
        anchor.getLocationOnScreen(anchorLoc);
        int available = context.getResources().getDisplayMetrics().heightPixels
                - (anchorLoc[1] + anchor.getHeight()) - dp(context, 12);
        int heightPx = Math.min(contentHeight, Math.max(available, dp(context, 200)));

        PopupWindow popup = new PopupWindow(b.getRoot(), widthPx, heightPx, true);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        popup.setElevation(16f);
        popup.showAsDropDown(anchor, 0, dp(context, 4));
        return popup;
    }

    /** Wires a 0..100% SeekBar + value label to a 0..1 preset factor. */
    private static void bindPercentRow(SeekBar seek, TextView value, float initial,
                                       FloatChange onChange) {
        seek.setProgress(PercentSliderMapper.valueToProgress(initial));
        value.setText(PercentSliderMapper.label(seek.getProgress()));
        seek.setOnSeekBarChangeListener(new SimpleSeekBar() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                onChange.onChanged(PercentSliderMapper.progressToValue(progress));
                value.setText(PercentSliderMapper.label(progress));
            }
        });
    }

    /** Re-syncs all numeric rows after a style variant rewrote the preset. */
    private static void refreshNumericRows(PopupBrushPropsBinding b, BrushPreset preset,
                                           float widthMin) {
        b.seekWidth.setProgress(WidthSliderMapper.widthToProgress(preset.baseWidth, widthMin));
        b.textWidthValue.setText(WidthSliderMapper.label(preset.baseWidth, widthMin));
        b.seekOpacity.setProgress(clamp(Math.round(preset.opacity * 100f) - 10,
                0, b.seekOpacity.getMax()));
        b.seekPressure.setProgress(PercentSliderMapper.valueToProgress(
                BrushParamMapping.pressureSensitivity(preset)));
        b.textPressureValue.setText(PercentSliderMapper.label(b.seekPressure.getProgress()));
        b.seekSmoothing.setProgress(PercentSliderMapper.valueToProgress(preset.smoothing));
        b.textSmoothingValue.setText(PercentSliderMapper.label(b.seekSmoothing.getProgress()));
        b.seekVelocity.setProgress(PercentSliderMapper.valueToProgress(preset.velocityToWidth));
        b.textVelocityValue.setText(PercentSliderMapper.label(b.seekVelocity.getProgress()));
        b.seekTilt.setProgress(PercentSliderMapper.valueToProgress(
                BrushParamMapping.tiltSensitivity(preset)));
        b.textTiltValue.setText(PercentSliderMapper.label(b.seekTilt.getProgress()));
    }

    // ------------------------------------------------------------------
    // Style variants
    // ------------------------------------------------------------------

    /**
     * Style-variant cards (fountain pen gets 7 nib styles; other tools hide
     * the row). Each card stacks the style name above a real ink-trace
     * preview rendered through the same BrushEngine pipeline as writing.
     * Applying a variant rewrites feel parameters but keeps color.
     */
    private static void buildStyleRow(Context context, PopupBrushPropsBinding b,
                                      EditorTool tool, BrushPreset preset,
                                      float widthMin, Listener listener) {
        java.util.List<PenStyleVariants.Variant> variants = PenStyleVariants.forTool(tool);
        if (variants.isEmpty()) {
            return; // label + scroll stay GONE
        }
        b.labelStyle.setVisibility(View.VISIBLE);
        b.styleScroll.setVisibility(View.VISIBLE);
        b.styleRow.removeAllViews();
        for (PenStyleVariants.Variant variant : variants) {
            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            int padH = dp(context, 10);
            card.setPadding(padH, dp(context, 6), padH, dp(context, 8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(context, 8));
            card.setLayoutParams(lp);
            card.setTag(variant.id);

            TextView name = new TextView(context);
            name.setText(variant.name);
            name.setTextSize(12f);
            name.setGravity(Gravity.CENTER);
            name.setSingleLine(true);
            card.addView(name);

            ImageView preview = new ImageView(context);
            int previewW = dp(context, 64);
            int previewH = dp(context, 26);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(previewW, previewH);
            plp.topMargin = dp(context, 4);
            preview.setLayoutParams(plp);
            preview.setImageBitmap(
                    PenStylePreviewRenderer.render(variant, previewW, previewH));
            card.addView(preview);

            boolean selected = variant.id.equals(preset.styleId);
            card.setBackground(chipDrawable(context, selected));
            name.setTextColor(selected ? 0xFF2F6BFF : 0xFF17181A);
            card.setOnClickListener(v -> {
                variant.applyTo(preset); // preserves preset.color
                refreshNumericRows(b, preset, widthMin);
                refreshStyleSelection(context, b.styleRow, variant.id);
                notifyChange(listener, tool, preset, b.switchStraighten.isChecked());
            });
            b.styleRow.addView(card);
        }
    }

    private static void refreshStyleSelection(Context context, LinearLayout row,
                                              String selectedId) {
        for (int i = 0; i < row.getChildCount(); i++) {
            LinearLayout card = (LinearLayout) row.getChildAt(i);
            boolean selected = selectedId.equals(card.getTag());
            card.setBackground(chipDrawable(context, selected));
            TextView name = (TextView) card.getChildAt(0);
            name.setTextColor(selected ? 0xFF2F6BFF : 0xFF17181A);
        }
    }

    static GradientDrawable chipDrawable(Context context, boolean selected) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(context, 15));
        d.setColor(selected ? 0xFFEDF3FF : 0xFFF4F5F7);
        d.setStroke(dp(context, 1), selected ? 0xFF2F6BFF : 0xFFE5E8ED);
        return d;
    }

    // ------------------------------------------------------------------
    // Palette
    // ------------------------------------------------------------------

    private interface ColorPick {
        void onPick(int color);
    }

    private static void buildPalette(Context context, GridLayout grid,
                                     BrushPreset preset, ColorPick pick) {
        int size = dp(context, 36);
        int margin = dp(context, 4);
        for (int color : PALETTE) {
            View cell = new View(context);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = size;
            lp.height = size;
            lp.setMargins(margin, margin, margin, margin);
            cell.setLayoutParams(lp);
            cell.setTag(color);
            cell.setBackground(paletteDrawable(color, color == preset.color));
            cell.setOnClickListener(v -> {
                int picked = (Integer) v.getTag();
                refreshPaletteSelection(grid, picked);
                pick.onPick(picked);
            });
            grid.addView(cell);
        }
    }

    private static void refreshPaletteSelection(GridLayout grid, int selectedColor) {
        for (int i = 0; i < grid.getChildCount(); i++) {
            View child = grid.getChildAt(i);
            int color = (Integer) child.getTag();
            child.setBackground(paletteDrawable(color, color == selectedColor));
        }
    }

    static GradientDrawable paletteDrawable(int color, boolean selected) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        d.setStroke(selected ? 6 : 2,
                selected ? 0xFF2F6BFF : 0xFFE5E8ED);
        return d;
    }

    // ------------------------------------------------------------------

    private static void notifyChange(Listener listener, EditorTool tool,
                                     BrushPreset preset, boolean straighten) {
        if (listener != null) {
            listener.onPresetChanged(tool, preset, straighten);
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** SeekBar listener with only onProgressChanged to implement. */
    private abstract static class SimpleSeekBar implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}
