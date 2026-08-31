package com.leslie.colorosnote.feature.editor;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

/**
 * 奶白卡片式进度对话框（模型下载 / 识别中）。ML Kit 的模型下载 Task 不上报
 * 百分比进度，因此使用不确定进度条 + 说明文案。不可取消（中途取消下载会在
 * SDK 内部留下不一致状态），识别/下载结束由调用方 dismiss。
 */
public final class CreamProgressDialog {

    private CreamProgressDialog() {
    }

    public static AlertDialog show(Context context, int titleRes, int messageRes) {
        int pad = BrushPropertiesPopup.dp(context, 18);
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pad, pad, pad, pad);
        column.setBackgroundResource(R.drawable.bg_popup);

        TextView title = new TextView(context);
        title.setText(titleRes);
        title.setTextSize(15f);
        title.setTextColor(0xFF17181A);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.bottomMargin = BrushPropertiesPopup.dp(context, 12);
        column.addView(title, tlp);

        ProgressBar bar = new ProgressBar(context, null,
                android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.bottomMargin = BrushPropertiesPopup.dp(context, 10);
        column.addView(bar, blp);

        TextView message = new TextView(context);
        message.setText(messageRes);
        message.setTextSize(12f);
        message.setTextColor(0xFF6B7078);
        column.addView(message);

        AlertDialog dialog = new AlertDialog.Builder(context).setView(column).create();
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        return dialog;
    }
}
