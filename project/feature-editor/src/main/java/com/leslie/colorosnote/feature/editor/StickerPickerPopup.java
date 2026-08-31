package com.leslie.colorosnote.feature.editor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import com.leslie.colorosnote.core.render.StickerCatalog;
import com.leslie.colorosnote.core.render.StickerGeometry;
import com.leslie.colorosnote.core.render.StickerPainter;
import com.leslie.colorosnote.core.model.StickerObject;

import java.util.List;

/**
 * Built-in sticker picker (spec 18.3): a "最近使用" row followed by one row
 * per category. Previews are rendered in code by {@link StickerPainter} onto
 * small bitmaps — no shipped image assets.
 */
public final class StickerPickerPopup {

    public interface Listener {
        void onStickerPicked(String stickerId);
    }

    private StickerPickerPopup() {
    }

    public static PopupWindow show(View anchor, RecentStickers recent, Listener listener) {
        Context context = anchor.getContext();
        int pad = BrushPropertiesPopup.dp(context, 16);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(context);
        title.setText(R.string.obj_sticker_picker);
        title.setTextSize(15f);
        title.setTextColor(0xFF17181A);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        column.addView(title);

        if (!recent.isEmpty()) {
            addRow(context, column, context.getString(R.string.obj_sticker_recent),
                    recent.mostRecent(), listener);
        }
        for (String category : StickerCatalog.categories()) {
            List<String> ids = new java.util.ArrayList<>();
            for (StickerCatalog.Entry e : StickerCatalog.byCategory(category)) {
                ids.add(e.id);
            }
            addRow(context, column, category, ids, listener);
        }

        ScrollView scroll = new ScrollView(context);
        scroll.setBackgroundResource(R.drawable.bg_popup);
        scroll.addView(column);
        PopupWindow popup = new PopupWindow(scroll,
                BrushPropertiesPopup.dp(context, 360),
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        popup.setElevation(16f);
        popup.showAtLocation(anchor, Gravity.CENTER, 0, 0);
        return popup;
    }

    private static void addRow(Context context, LinearLayout column, String label,
                               List<String> stickerIds, Listener listener) {
        TextView header = new TextView(context);
        header.setText(label);
        header.setTextSize(12f);
        header.setLetterSpacing(0.06f);
        header.setTextColor(0xFF6B7078);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = BrushPropertiesPopup.dp(context, 12);
        header.setLayoutParams(hlp);
        column.addView(header);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int size = BrushPropertiesPopup.dp(context, 52);
        int margin = BrushPropertiesPopup.dp(context, 4);
        int cellPadH = BrushPropertiesPopup.dp(context, 8);
        int cellPadV = BrushPropertiesPopup.dp(context, 6);
        for (String id : stickerIds) {
            // Chip card (preview + name), same style as the brush style cards.
            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER);
            card.setPadding(cellPadH, cellPadV, cellPadH, cellPadV);
            card.setBackground(BrushPropertiesPopup.chipDrawable(context, false));

            ImageView preview = new ImageView(context);
            preview.setImageBitmap(renderPreview(id, size));
            card.addView(preview, new LinearLayout.LayoutParams(size, size));

            TextView name = new TextView(context);
            StickerCatalog.Entry entry = StickerCatalog.find(id);
            name.setText(entry != null ? entry.label : id);
            name.setTextSize(10f);
            name.setTextColor(0xFF17181A);
            name.setSingleLine(true);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nlp.topMargin = BrushPropertiesPopup.dp(context, 3);
            card.addView(name, nlp);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(margin, margin, margin, margin);
            card.setLayoutParams(lp);
            card.setOnClickListener(v -> listener.onStickerPicked(id));
            row.addView(card);
        }
        HorizontalScrollView scroller = new HorizontalScrollView(context);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.addView(row);
        column.addView(scroller);
    }

    /** Renders a built-in sticker preview bitmap via StickerPainter. */
    static Bitmap renderPreview(String stickerId, int sizePx) {
        float space = 100f; // StickerPainter's normalized sticker space
        Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        StickerObject object = new StickerObject();
        object.stickerId = stickerId;
        object.bounds = new float[]{0f, 0f, space, space};
        canvas.scale(sizePx / space, sizePx / space);
        new StickerPainter(null).paint(canvas, object);
        return bitmap;
    }
}
