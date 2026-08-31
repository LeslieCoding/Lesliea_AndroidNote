package com.leslie.colorosnote.feature.editor;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

/**
 * 奶白卡片式提示对话框：标题 + 一段说明 + 按钮行。用于识别引擎的明确
 * 中文错误提示（无 GMS / 模型下载失败 / 识别失败）。{@code onRetry} 非空
 * 时追加一个「重试」按钮。
 */
public final class CreamNoticeDialog {

    private CreamNoticeDialog() {
    }

    public static void show(Context context, int titleRes, int messageRes) {
        show(context, titleRes, messageRes, null);
    }

    public static void show(Context context, int titleRes, int messageRes,
                            @Nullable Runnable onRetry) {
        int pad = BrushPropertiesPopup.dp(context, 18);
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pad, pad, pad, BrushPropertiesPopup.dp(context, 10));
        column.setBackgroundResource(R.drawable.bg_popup);

        TextView title = new TextView(context);
        title.setText(titleRes);
        title.setTextSize(15f);
        title.setTextColor(0xFF17181A);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.bottomMargin = BrushPropertiesPopup.dp(context, 8);
        column.addView(title, tlp);

        TextView message = new TextView(context);
        message.setText(messageRes);
        message.setTextSize(13f);
        message.setTextColor(0xFF6B7078);
        column.addView(message);

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = BrushPropertiesPopup.dp(context, 14);
        actions.setLayoutParams(alp);
        column.addView(actions);

        AlertDialog dialog = new AlertDialog.Builder(context).setView(column).create();

        if (onRetry != null) {
            TextView retry = actionText(context, R.string.recog_retry, 0xFF2F6BFF, false);
            retry.setOnClickListener(v -> {
                dialog.dismiss();
                onRetry.run();
            });
            actions.addView(retry);
        }
        TextView ok = actionText(context, R.string.recog_ok, 0xFF6B7078, onRetry == null);
        ok.setOnClickListener(v -> dialog.dismiss());
        actions.addView(ok);

        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
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
        view.setBackgroundResource(R.drawable.bg_popup_item);
        return view;
    }
}
