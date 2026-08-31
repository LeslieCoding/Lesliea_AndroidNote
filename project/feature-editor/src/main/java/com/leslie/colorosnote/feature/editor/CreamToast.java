package com.leslie.colorosnote.feature.editor;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;
import android.widget.TextView;

/**
 * Milky-white centered hint card ("已激活工具"反馈). A non-touchable,
 * non-focusable PopupWindow so page touches keep flowing to the armed tool;
 * auto-dismisses after a short dwell with a fade.
 */
public final class CreamToast {

    private static final long DWELL_MS = 1400L;
    private static final long FADE_MS = 220L;

    private CreamToast() {
    }

    public static void show(View anchor, int textRes) {
        if (anchor == null) {
            return;
        }
        Context context = anchor.getContext();
        TextView text = new TextView(context);
        text.setText(textRes);
        text.setTextSize(13f);
        text.setTextColor(0xFF17181A);
        int padH = BrushPropertiesPopup.dp(context, 20);
        int padV = BrushPropertiesPopup.dp(context, 10);
        text.setPadding(padH, padV, padH, padV);
        text.setBackgroundResource(R.drawable.bg_popup);

        final PopupWindow popup = new PopupWindow(text,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, false);
        popup.setTouchable(false);
        popup.setOutsideTouchable(false);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setElevation(16f);
        popup.showAtLocation(anchor, Gravity.CENTER, 0, 0);
        text.postDelayed(() -> text.animate().alpha(0f).setDuration(FADE_MS)
                .withEndAction(popup::dismiss).start(), DWELL_MS);
    }
}
