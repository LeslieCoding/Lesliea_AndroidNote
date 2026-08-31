package com.leslie.colorosnote.feature.editor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.ToggleButton;

import com.leslie.colorosnote.core.model.TapeObject;
import com.leslie.colorosnote.core.render.TapePainter;

/**
 * Tape tool configuration popover (spec 19): mode (hand-drawn / straight),
 * width, color, pattern, plus page-level "全部遮盖 / 全部显示". All state
 * lives in {@link Config}; page-level actions report back via the listener.
 */
public final class TapeConfigPopup {

    /** Mutable tape drawing configuration. */
    public static final class Config {
        public boolean straight;
        public float width = 40f;
        public int color = 0xFFE0B4B0;
        public TapeObject.TapePattern pattern = TapeObject.TapePattern.SOLID;
    }

    public interface Listener {
        void onCoverAll();

        void onRevealAll();
    }

    private static final float[] WIDTHS = {24f, 40f, 64f};
    private static final int[] WIDTH_LABELS = {
            R.string.obj_tape_width_thin, R.string.obj_tape_width_mid,
            R.string.obj_tape_width_thick};
    /** Morandi / mt-washi palette: low-saturation, slightly grayed tones. */
    private static final int[] TAPE_COLORS = {
            0xFFE0B4B0, // dusty rose 雾粉
            0xFFCE9B97, // bean-paste red 豆沙红
            0xFFEACFB4, // apricot cream 奶杏
            0xFFEAD9A6, // cream yellow 奶油黄
            0xFFB7C4A5, // sage green 鼠尾草绿
            0xFFA8BCC9, // haze blue 雾霾蓝
            0xFFBCB4CC, // lavender gray 薰衣草灰紫
            0xFFC6B8AC}; // taupe 浅灰棕
    private static final TapeObject.TapePattern[] PATTERNS = {
            TapeObject.TapePattern.SOLID, TapeObject.TapePattern.STRIPES,
            TapeObject.TapePattern.DOTS, TapeObject.TapePattern.GRID,
            TapeObject.TapePattern.CROSSHATCH, TapeObject.TapePattern.WAVES,
            TapeObject.TapePattern.HEARTS};
    private static final int[] PATTERN_LABELS = {
            R.string.obj_tape_pattern_solid, R.string.obj_tape_pattern_stripes,
            R.string.obj_tape_pattern_dots, R.string.obj_tape_pattern_grid,
            R.string.obj_tape_pattern_crosshatch, R.string.obj_tape_pattern_waves,
            R.string.obj_tape_pattern_hearts};

    private TapeConfigPopup() {
    }

    public static PopupWindow show(View anchor, Config config, Listener listener) {
        Context context = anchor.getContext();
        int pad = BrushPropertiesPopup.dp(context, 12);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pad, pad, pad, pad);
        column.setBackgroundResource(R.drawable.bg_popup);

        // Mode toggle (segmented pills on a light track).
        LinearLayout modeRow = segmentTrack(context);
        ToggleButton free = modeButton(context, R.string.obj_tape_mode_free, !config.straight);
        ToggleButton straight = modeButton(context, R.string.obj_tape_mode_straight, config.straight);
        free.setOnClickListener(v -> {
            config.straight = false;
            free.setChecked(true);
            straight.setChecked(false);
        });
        straight.setOnClickListener(v -> {
            config.straight = true;
            straight.setChecked(true);
            free.setChecked(false);
        });
        modeRow.addView(free);
        modeRow.addView(straight);
        column.addView(modeRow);

        // Pattern preview cards container (declared early: color changes
        // re-render the previews).
        LinearLayout patternCards = new LinearLayout(context);
        patternCards.setOrientation(LinearLayout.HORIZONTAL);

        // Width.
        column.addView(label(context, R.string.obj_tape_width));
        LinearLayout widthRow = segmentTrack(context);
        for (int i = 0; i < WIDTHS.length; i++) {
            float w = WIDTHS[i];
            ToggleButton b = modeButton(context, WIDTH_LABELS[i], config.width == w);
            b.setOnClickListener(v -> {
                config.width = w;
                for (int j = 0; j < widthRow.getChildCount(); j++) {
                    ((ToggleButton) widthRow.getChildAt(j)).setChecked(j == widthRow.indexOfChild(v));
                }
            });
            widthRow.addView(b);
        }
        column.addView(widthRow);

        // Color.
        column.addView(label(context, R.string.brush_color));
        LinearLayout colorRow = new LinearLayout(context);
        colorRow.setOrientation(LinearLayout.HORIZONTAL);
        int size = BrushPropertiesPopup.dp(context, 32);
        int margin = BrushPropertiesPopup.dp(context, 4);
        for (int color : TAPE_COLORS) {
            View cell = new View(context);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMargins(margin, margin, margin, margin);
            cell.setLayoutParams(lp);
            cell.setBackground(BrushPropertiesPopup.paletteDrawable(color, color == config.color));
            cell.setOnClickListener(v -> {
                config.color = color;
                for (int i = 0; i < colorRow.getChildCount(); i++) {
                    colorRow.getChildAt(i).setBackground(BrushPropertiesPopup.paletteDrawable(
                            TAPE_COLORS[i], i == colorRow.indexOfChild(v)));
                }
                refreshPatternPreviews(patternCards, config);
            });
            colorRow.addView(cell);
        }
        column.addView(colorRow);

        // Pattern: preview cards, each showing a real tape strip rendered by
        // TapePainter in the current color.
        column.addView(label(context, R.string.obj_tape_pattern));
        for (int i = 0; i < PATTERNS.length; i++) {
            TapeObject.TapePattern p = PATTERNS[i];
            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            int padH = BrushPropertiesPopup.dp(context, 8);
            card.setPadding(padH, BrushPropertiesPopup.dp(context, 6), padH,
                    BrushPropertiesPopup.dp(context, 7));
            card.setTag(p);

            ImageView preview = new ImageView(context);
            int previewW = BrushPropertiesPopup.dp(context, 64);
            int previewH = BrushPropertiesPopup.dp(context, 24);
            preview.setLayoutParams(new LinearLayout.LayoutParams(previewW, previewH));
            preview.setImageBitmap(renderTapePreview(p, config.color, previewW, previewH));
            card.addView(preview);

            TextView name = new TextView(context);
            name.setText(PATTERN_LABELS[i]);
            name.setTextSize(11f);
            name.setGravity(Gravity.CENTER);
            name.setSingleLine(true);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nlp.topMargin = BrushPropertiesPopup.dp(context, 4);
            card.addView(name, nlp);

            boolean selected = config.pattern == p;
            card.setBackground(BrushPropertiesPopup.chipDrawable(context, selected));
            name.setTextColor(selected ? 0xFF2F6BFF : 0xFF17181A);
            card.setOnClickListener(v -> {
                config.pattern = p;
                refreshPatternSelection(context, patternCards, p);
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.setMarginEnd(BrushPropertiesPopup.dp(context, 8));
            clp.topMargin = BrushPropertiesPopup.dp(context, 6);
            patternCards.addView(card, clp);
        }
        HorizontalScrollView patternScroll = new HorizontalScrollView(context);
        patternScroll.setHorizontalScrollBarEnabled(false);
        patternScroll.addView(patternCards);
        column.addView(patternScroll);

        // Page-level reveal actions (borderless accent buttons).
        LinearLayout pageRow = new LinearLayout(context);
        pageRow.setOrientation(LinearLayout.HORIZONTAL);
        pageRow.setPadding(0, BrushPropertiesPopup.dp(context, 8), 0, 0);
        Button coverAll = actionButton(context, R.string.obj_tape_cover_all);
        coverAll.setOnClickListener(v -> listener.onCoverAll());
        Button revealAll = actionButton(context, R.string.obj_tape_reveal_all);
        revealAll.setOnClickListener(v -> listener.onRevealAll());
        pageRow.addView(coverAll);
        pageRow.addView(revealAll);
        column.addView(pageRow);

        PopupWindow popup = new PopupWindow(column,
                BrushPropertiesPopup.dp(context, 340),
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        popup.setElevation(16f);
        popup.showAtLocation(anchor, Gravity.CENTER, 0, 0);
        return popup;
    }

    private static TextView label(Context context, int textRes) {
        TextView view = new TextView(context);
        view.setText(textRes);
        view.setTextSize(12f);
        view.setLetterSpacing(0.06f);
        view.setTextColor(0xFF6B7078);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = BrushPropertiesPopup.dp(context, 12);
        lp.leftMargin = BrushPropertiesPopup.dp(context, 2);
        view.setLayoutParams(lp);
        return view;
    }

    /** Light-gray rounded track holding segmented pill buttons. */
    private static LinearLayout segmentTrack(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundResource(R.drawable.bg_segment_track);
        int pad = BrushPropertiesPopup.dp(context, 3);
        row.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = BrushPropertiesPopup.dp(context, 6);
        row.setLayoutParams(lp);
        return row;
    }

    private static ToggleButton modeButton(Context context, int textRes, boolean checked) {
        ToggleButton button = new ToggleButton(context);
        button.setTextOn(context.getString(textRes));
        button.setTextOff(context.getString(textRes));
        button.setText(textRes);
        button.setTextSize(13f);
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

    /** Renders a short tape strip preview in the given color via TapePainter. */
    static Bitmap renderTapePreview(TapeObject.TapePattern pattern, int color,
                                    int widthPx, int heightPx) {
        // Logical preview space keeps the pattern spacing close to on-page look.
        float logicalW = 96f;
        float logicalH = 30f;
        Bitmap bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        TapeObject tape = new TapeObject();
        tape.color = color;
        tape.pattern = pattern;
        tape.rect = new float[]{3f, 3f, logicalW - 3f, logicalH - 3f};
        canvas.scale(widthPx / logicalW, heightPx / logicalH);
        new TapePainter().paint(canvas, tape);
        return bitmap;
    }

    private static void refreshPatternPreviews(LinearLayout cards, Config config) {
        Context context = cards.getContext();
        int previewW = BrushPropertiesPopup.dp(context, 64);
        int previewH = BrushPropertiesPopup.dp(context, 24);
        for (int i = 0; i < cards.getChildCount(); i++) {
            LinearLayout card = (LinearLayout) cards.getChildAt(i);
            TapeObject.TapePattern p = (TapeObject.TapePattern) card.getTag();
            ((ImageView) card.getChildAt(0)).setImageBitmap(
                    renderTapePreview(p, config.color, previewW, previewH));
        }
    }

    private static void refreshPatternSelection(Context context, LinearLayout cards,
                                                TapeObject.TapePattern selected) {
        for (int i = 0; i < cards.getChildCount(); i++) {
            LinearLayout card = (LinearLayout) cards.getChildAt(i);
            boolean isSelected = card.getTag() == selected;
            card.setBackground(BrushPropertiesPopup.chipDrawable(context, isSelected));
            TextView name = (TextView) card.getChildAt(1);
            name.setTextColor(isSelected ? 0xFF2F6BFF : 0xFF17181A);
        }
    }

    /** Borderless accent-text action button. */
    private static Button actionButton(Context context, int textRes) {
        Button button = new Button(context, null,
                android.R.attr.borderlessButtonStyle);
        button.setText(textRes);
        button.setTextSize(13f);
        button.setTextColor(0xFF2F6BFF);
        return button;
    }
}
