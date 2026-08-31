package com.leslie.colorosnote.feature.editor;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.ToggleButton;

import androidx.appcompat.app.AlertDialog;

/**
 * Text box editing dialog (spec 18.2): multi-line text with the system
 * default typeface (Chinese renders correctly), bold/italic/underline
 * toggles, font size slider and a color row. Styled as a milky-white
 * rounded card consistent with the editor popovers ({@code bg_popup});
 * returns an immutable {@link Draft} on confirm and reports a dismiss
 * without confirm via {@link Listener#onCancelled()} so callers can roll
 * back a freshly inserted empty box.
 */
public final class TextBoxEditDialog {

    /** Editable result of the dialog. */
    public static final class Draft {
        public String text = "";
        public float fontSize = 18f;
        public int color = 0xFF000000;
        public boolean bold;
        public boolean italic;
        public boolean underline;
    }

    public interface Listener {
        void onConfirmed(Draft draft);

        /** Dialog dismissed without confirming (取消 / 点外部 / 返回键). */
        void onCancelled();
    }

    private TextBoxEditDialog() {
    }

    public static void show(Context context,
                            com.leslie.colorosnote.core.model.TextBoxObject box,
                            Listener listener) {
        int pad = BrushPropertiesPopup.dp(context, 18);
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pad, pad, pad, BrushPropertiesPopup.dp(context, 10));
        column.setBackgroundResource(R.drawable.bg_popup);

        // Title.
        TextView title = new TextView(context);
        title.setText(R.string.aux_text_box);
        title.setTextSize(15f);
        title.setTextColor(0xFF17181A);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.bottomMargin = BrushPropertiesPopup.dp(context, 10);
        column.addView(title, tlp);

        EditText edit = new EditText(context);
        edit.setText(box.text);
        edit.setHint(R.string.obj_text_hint);
        edit.setMinLines(4);
        edit.setGravity(Gravity.TOP | Gravity.START);
        // System default typeface: Chinese text must not render as tofu.
        edit.setTypeface(Typeface.DEFAULT);
        edit.setTextColor(box.color);
        int editPad = BrushPropertiesPopup.dp(context, 12);
        edit.setPadding(editPad, editPad, editPad, editPad);
        edit.setBackgroundResource(R.drawable.bg_input);
        edit.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        column.addView(edit, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Style toggles: 粗体 / 斜体 / 下划线 (segmented pills on a light track).
        LinearLayout styleRow = segmentTrack(context);
        ToggleButton bold = styleButton(context, "B", box.bold);
        bold.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        ToggleButton italic = styleButton(context, "I", box.italic);
        italic.setTypeface(Typeface.DEFAULT, Typeface.ITALIC);
        ToggleButton underline = styleButton(context, "U", box.underline);
        underline.setPaintFlags(underline.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        styleRow.addView(bold);
        styleRow.addView(italic);
        styleRow.addView(underline);
        column.addView(styleRow);

        // Font size slider (12..48 logical units).
        LinearLayout sizeRow = new LinearLayout(context);
        sizeRow.setOrientation(LinearLayout.HORIZONTAL);
        sizeRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = BrushPropertiesPopup.dp(context, 10);
        sizeRow.setLayoutParams(slp);
        TextView sizeName = new TextView(context);
        sizeName.setText(R.string.obj_font_size);
        sizeName.setTextSize(12f);
        sizeName.setTextColor(0xFF6B7078);
        TextView sizeLabel = new TextView(context);
        sizeLabel.setTextSize(12f);
        sizeLabel.setTextColor(0xFF17181A);
        sizeLabel.setText(String.valueOf(Math.round(box.fontSize)));
        SeekBar sizeBar = new SeekBar(context);
        sizeBar.setMax(36);
        sizeBar.setProgress(Math.max(0, Math.min(36, Math.round(box.fontSize) - 12)));
        sizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                sizeLabel.setText(String.valueOf(progress + 12));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        sizeRow.addView(sizeName);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        barLp.setMargins(BrushPropertiesPopup.dp(context, 8), 0,
                BrushPropertiesPopup.dp(context, 8), 0);
        sizeRow.addView(sizeBar, barLp);
        sizeRow.addView(sizeLabel);
        column.addView(sizeRow);

        // Color row.
        LinearLayout colorRow = new LinearLayout(context);
        colorRow.setOrientation(LinearLayout.HORIZONTAL);
        colorRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = BrushPropertiesPopup.dp(context, 6);
        colorRow.setLayoutParams(clp);
        int cellSize = BrushPropertiesPopup.dp(context, 32);
        int margin = BrushPropertiesPopup.dp(context, 4);
        final int[] chosenColor = {box.color};
        for (int color : BrushPropertiesPopup.PALETTE) {
            View cell = new View(context);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cellSize, cellSize);
            lp.setMargins(margin, margin, margin, margin);
            cell.setLayoutParams(lp);
            cell.setBackground(BrushPropertiesPopup.paletteDrawable(color, color == box.color));
            cell.setOnClickListener(v -> {
                chosenColor[0] = color;
                for (int i = 0; i < colorRow.getChildCount(); i++) {
                    View c = colorRow.getChildAt(i);
                    c.setBackground(BrushPropertiesPopup.paletteDrawable(
                            BrushPropertiesPopup.PALETTE[i], i == colorRow.indexOfChild(v)));
                }
            });
            colorRow.addView(cell);
        }
        column.addView(colorRow);

        // Action row: 取消 / 确定 (text buttons, accent on confirm).
        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = BrushPropertiesPopup.dp(context, 12);
        actions.setLayoutParams(alp);
        TextView cancel = actionText(context, R.string.obj_cancel, 0xFF6B7078, false);
        TextView confirm = actionText(context, R.string.brush_apply, 0xFF2F6BFF, true);
        actions.addView(cancel);
        actions.addView(confirm);
        column.addView(actions);

        final boolean[] confirmed = {false};
        AlertDialog dialog = new AlertDialog.Builder(context).setView(column).create();
        dialog.setOnDismissListener(d -> {
            if (!confirmed[0]) {
                listener.onCancelled();
            }
        });
        cancel.setOnClickListener(v -> dialog.dismiss());
        confirm.setOnClickListener(v -> {
            confirmed[0] = true;
            Draft draft = new Draft();
            draft.text = edit.getText().toString();
            draft.bold = bold.isChecked();
            draft.italic = italic.isChecked();
            draft.underline = underline.isChecked();
            draft.fontSize = sizeBar.getProgress() + 12f;
            draft.color = chosenColor[0];
            listener.onConfirmed(draft);
            dialog.dismiss();
        });
        dialog.show();
        // Let the card's own rounded corners show through the dialog window.
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
    }

    /** Light-gray rounded track holding the style pills. */
    private static LinearLayout segmentTrack(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundResource(R.drawable.bg_segment_track);
        int pad = BrushPropertiesPopup.dp(context, 3);
        row.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = BrushPropertiesPopup.dp(context, 12);
        row.setLayoutParams(lp);
        return row;
    }

    private static ToggleButton styleButton(Context context, String label, boolean checked) {
        ToggleButton button = new ToggleButton(context);
        button.setTextOn(label);
        button.setTextOff(label);
        button.setText(label);
        button.setTextSize(15f);
        button.setTextColor(context.getResources().getColorStateList(
                R.color.segment_text, null));
        button.setBackgroundResource(R.drawable.bg_segment_item);
        button.setChecked(checked);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, BrushPropertiesPopup.dp(context, 36), 1f);
        int margin = BrushPropertiesPopup.dp(context, 2);
        lp.setMargins(margin, margin, margin, margin);
        button.setLayoutParams(lp);
        return button;
    }

    private static TextView actionText(Context context, int textRes, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(textRes);
        view.setTextSize(14f);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        int padH = BrushPropertiesPopup.dp(context, 16);
        int padV = BrushPropertiesPopup.dp(context, 8);
        view.setPadding(padH, padV, padH, padV);
        view.setBackgroundResource(R.drawable.bg_popup_item); // rounded ripple
        return view;
    }
}
