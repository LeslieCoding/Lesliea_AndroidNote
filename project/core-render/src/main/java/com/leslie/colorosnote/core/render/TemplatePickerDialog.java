package com.leslie.colorosnote.core.render;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.PopupWindow;
import android.widget.TextView;

import com.leslie.colorosnote.core.model.PageBackground;

/**
 * Shared page-template picker (milky-white card, mirrors the shape picker):
 * a 3-column grid of cells, each showing a live preview rendered through
 * {@link TemplatePreviewRenderer} plus the template name; the currently used
 * template is highlighted. Used by both feature-editor (page background
 * switching) and feature-library (create note from template), so it lives in
 * core-render to avoid duplication. The layout is inflated from XML —
 * programmatic layouts collapse on ColorOS.
 */
public final class TemplatePickerDialog {

    public interface Listener {
        void onTemplatePicked(PageBackground.Type type);
    }

    /** All switchable templates, in picker order (document/image backgrounds excluded). */
    static final PageBackground.Type[] TYPES = {
            PageBackground.Type.BLANK, PageBackground.Type.LINED,
            PageBackground.Type.GRID, PageBackground.Type.DOT,
            PageBackground.Type.CORNELL, PageBackground.Type.CORNELL_GRID,
            PageBackground.Type.ENGLISH, PageBackground.Type.TIANZI,
            PageBackground.Type.MIZI, PageBackground.Type.MUSIC,
            PageBackground.Type.TODO, PageBackground.Type.DAILY,
            PageBackground.Type.WEEKLY, PageBackground.Type.MEETING};
    static final int[] LABELS = {
            R.string.template_blank, R.string.template_lined,
            R.string.template_grid, R.string.template_dot,
            R.string.template_cornell, R.string.template_cornell_grid,
            R.string.template_english, R.string.template_tianzi,
            R.string.template_mizi, R.string.template_music,
            R.string.template_todo, R.string.template_daily,
            R.string.template_weekly, R.string.template_meeting};

    private static final int COLOR_TEXT = 0xFF17181A;
    private static final int COLOR_ACCENT = 0xFF2F6BFF;

    private TemplatePickerDialog() {
    }

    /** Shows the picker centered over the anchor's window. */
    public static PopupWindow show(View anchor, PageBackground.Type current,
                                   Listener listener) {
        Context context = anchor.getContext();
        View content = LayoutInflater.from(context)
                .inflate(R.layout.popup_template_picker, null);
        GridLayout grid = content.findViewById(R.id.template_grid);
        int margin = dp(context, 4);
        int previewW = dp(context, 72);
        int previewH = dp(context, 96);
        LayoutInflater inflater = LayoutInflater.from(context);
        final PopupWindow[] holder = new PopupWindow[1];
        for (int i = 0; i < TYPES.length; i++) {
            PageBackground.Type type = TYPES[i];
            boolean selected = type == current;
            View cell = inflater.inflate(R.layout.item_template_cell, grid, false);
            ImageView preview = cell.findViewById(R.id.template_preview);
            preview.setImageBitmap(TemplatePreviewRenderer.render(type, previewW, previewH));
            TextView label = cell.findViewById(R.id.template_label);
            label.setText(LABELS[i]);
            label.setTextColor(selected ? COLOR_ACCENT : COLOR_TEXT);
            cell.setBackground(chipDrawable(context, selected));
            cell.setOnClickListener(v -> {
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
                listener.onTemplatePicked(type);
            });
            GridLayout.LayoutParams glp = new GridLayout.LayoutParams();
            glp.setMargins(margin, margin, margin, margin);
            grid.addView(cell, glp);
        }

        PopupWindow popup = new PopupWindow(content,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        holder[0] = popup;
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setElevation(dp(context, 16));
        popup.showAtLocation(anchor, Gravity.CENTER, 0, 0);
        return popup;
    }

    /** Chip card background, same tokens as the shape/brush pickers. */
    private static GradientDrawable chipDrawable(Context context, boolean selected) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(context, 15));
        d.setColor(selected ? 0xFFEDF3FF : 0x00FFFFFF);
        d.setStroke(dp(context, 1), selected ? COLOR_ACCENT : 0xFFE5E8ED);
        return d;
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
