package com.leslie.colorosnote.feature.editor;

import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.util.List;

/**
 * Small premium context menu: a milky card of icon+label rows in the same
 * style as the "⋯" more-menu (popup_more_menu / EditorMenuRow). Used for the
 * blank-area long-press menu and the recording chooser, replacing the
 * system-gray {@link android.widget.PopupMenu}.
 */
public final class MenuRowPopup {

    /** One row: icon, label, enabled state and the action to run on tap. */
    public static final class Item {
        public final int iconRes;
        public final String label;
        public final boolean enabled;
        public final Runnable action;

        public Item(int iconRes, String label, boolean enabled, Runnable action) {
            this.iconRes = iconRes;
            this.label = label;
            this.enabled = enabled;
            this.action = action;
        }
    }

    private MenuRowPopup() {
    }

    /** Drops the card below an anchor view (toolbar buttons etc.). */
    public static PopupWindow showAsDropDown(View anchor, List<Item> items) {
        PopupWindow popup = build(anchor, items);
        popup.showAsDropDown(anchor, 0, BrushPropertiesPopup.dp(anchor.getContext(), 6),
                Gravity.END);
        return popup;
    }

    /** Shows the card at a position in the anchor view's local coordinates. */
    public static PopupWindow showAt(View anchor, float viewX, float viewY, List<Item> items) {
        PopupWindow popup = build(anchor, items);
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        popup.showAtLocation(anchor, Gravity.TOP | Gravity.START,
                loc[0] + Math.round(viewX), loc[1] + Math.round(viewY));
        return popup;
    }

    private static PopupWindow build(View anchor, List<Item> items) {
        LayoutInflater inflater = LayoutInflater.from(anchor.getContext());
        LinearLayout container = (LinearLayout) inflater.inflate(
                R.layout.popup_menu_rows, null);
        PopupWindow popup = new PopupWindow(container,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true);
        for (Item item : items) {
            View row = inflater.inflate(R.layout.item_menu_row, container, false);
            ((ImageView) row.findViewById(R.id.row_icon)).setImageResource(item.iconRes);
            ((TextView) row.findViewById(R.id.row_label)).setText(item.label);
            if (item.enabled) {
                row.setOnClickListener(v -> {
                    popup.dismiss();
                    item.action.run();
                });
            } else {
                row.setAlpha(0.4f);
            }
            container.addView(row);
        }
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                android.graphics.Color.TRANSPARENT));
        popup.setElevation(12f);
        return popup;
    }
}
