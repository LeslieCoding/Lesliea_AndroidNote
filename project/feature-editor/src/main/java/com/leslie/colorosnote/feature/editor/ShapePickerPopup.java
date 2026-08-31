package com.leslie.colorosnote.feature.editor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.PopupWindow;
import android.widget.TextView;

import com.leslie.colorosnote.core.model.ShapeObject;
import com.leslie.colorosnote.core.render.ShapePainter;

import java.util.Arrays;

/**
 * Shape picker popover (replaces the old system PopupMenu that anchored in a
 * corner and read as a gray hint): a milky-white card with a 3x3 grid of
 * chip cards, each showing a live icon preview rendered through
 * {@link ShapePainter} plus the shape name. Picking a card arms the
 * drag-to-draw shape flow in {@link ObjectToolController}.
 */
public final class ShapePickerPopup {

    public interface Listener {
        void onShapePicked(ShapeObject.ShapeType type);
    }

    private static final ShapeObject.ShapeType[] TYPES = {
            ShapeObject.ShapeType.LINE, ShapeObject.ShapeType.ARROW,
            ShapeObject.ShapeType.DOUBLE_ARROW, ShapeObject.ShapeType.DASHED_LINE,
            ShapeObject.ShapeType.RECT, ShapeObject.ShapeType.ROUNDED_RECT,
            ShapeObject.ShapeType.ELLIPSE, ShapeObject.ShapeType.TRIANGLE,
            ShapeObject.ShapeType.DIAMOND};
    private static final int[] LABELS = {
            R.string.obj_shape_line, R.string.obj_shape_arrow,
            R.string.obj_shape_double_arrow, R.string.obj_shape_dashed,
            R.string.obj_shape_rect, R.string.obj_shape_rounded_rect,
            R.string.obj_shape_ellipse, R.string.obj_shape_triangle,
            R.string.obj_shape_diamond};

    private ShapePickerPopup() {
    }

    public static PopupWindow show(View anchor, ShapeObject.ShapeType current,
                                   Listener listener) {
        Context context = anchor.getContext();
        View content = LayoutInflater.from(context)
                .inflate(R.layout.popup_shape_picker, null);
        GridLayout grid = content.findViewById(R.id.shape_grid);
        int margin = BrushPropertiesPopup.dp(context, 4);
        LayoutInflater inflater = LayoutInflater.from(context);
        for (int i = 0; i < TYPES.length; i++) {
            ShapeObject.ShapeType type = TYPES[i];
            View cell = inflater.inflate(R.layout.item_shape_cell, grid, false);
            ImageView icon = cell.findViewById(R.id.shape_icon);
            icon.setImageBitmap(renderShapeIcon(type,
                    BrushPropertiesPopup.dp(context, 40)));
            TextView label = cell.findViewById(R.id.shape_label);
            label.setText(LABELS[i]);
            label.setTextColor(type == current ? 0xFF2F6BFF : 0xFF17181A);
            cell.setBackground(BrushPropertiesPopup.chipDrawable(context, type == current));
            cell.setOnClickListener(v -> listener.onShapePicked(type));
            GridLayout.LayoutParams glp = new GridLayout.LayoutParams();
            glp.setMargins(margin, margin, margin, margin);
            grid.addView(cell, glp);
        }

        PopupWindow popup = new PopupWindow(content,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setElevation(16f);
        popup.showAtLocation(anchor, Gravity.CENTER, 0, 0);
        return popup;
    }

    /** Offscreen icon preview drawn with the real ShapePainter pipeline. */
    static Bitmap renderShapeIcon(ShapeObject.ShapeType type, int sizePx) {
        Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        ShapeObject shape = new ShapeObject();
        shape.shapeType = type;
        shape.strokeColor = 0xFF3A3F47;
        shape.strokeWidth = Math.max(2f, sizePx / 22f);
        float pad = sizePx * 0.16f;
        float l = pad;
        float t = pad;
        float r = sizePx - pad;
        float b = sizePx - pad;
        // ShapePainter needs a little slack for stroke width.
        shape.bounds = new float[]{0f, 0f, sizePx, sizePx};
        switch (type) {
            case LINE:
            case ARROW:
            case DOUBLE_ARROW:
            case DASHED_LINE:
                // Slight ascending diagonal reads better than flat lines.
                shape.points = Arrays.asList(l, sizePx * 0.68f, r, sizePx * 0.32f);
                break;
            case TRIANGLE:
                shape.points = Arrays.asList(l, t, r, b);
                break;
            case DIAMOND:
                shape.points = Arrays.asList(l, t, r, b);
                break;
            default:
                shape.points = Arrays.asList(l, t, r, b);
                break;
        }
        // Bounds drive rect/ellipse/triangle/diamond geometry; shrink them so
        // the stroke stays inside the bitmap.
        float inset = shape.strokeWidth / 2f + 1f;
        shape.bounds = new float[]{l + inset, t + inset, r - inset, b - inset};
        new ShapePainter().paint(canvas, shape);
        return bitmap;
    }
}
