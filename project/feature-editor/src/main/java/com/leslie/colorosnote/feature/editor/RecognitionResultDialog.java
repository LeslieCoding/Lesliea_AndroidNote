package com.leslie.colorosnote.feature.editor;

import android.content.ClipData;
import android.content.ClipboardManager;
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
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

/**
 * 识别结果对话框（手写转文字 / 图片 OCR 共用）：可编辑多行文本框展示识别
 * 结果，按钮「取消」「复制」「插入为文本框」。奶白卡片样式与编辑器其他
 * 弹窗（{@link TextBoxEditDialog}）一致。复制后立即入剪贴板并保留对话框，
 * 方便用户继续插入。
 */
public final class RecognitionResultDialog {

    public interface Listener {
        /** 「插入为文本框」，参数为（可能已被用户编辑的）最终文本。 */
        void onInsertTextBox(String text);
    }

    private RecognitionResultDialog() {
    }

    public static void show(Context context, int titleRes, String text,
                            Listener listener, View toastAnchor) {
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
        tlp.bottomMargin = BrushPropertiesPopup.dp(context, 10);
        column.addView(title, tlp);

        EditText edit = new EditText(context);
        edit.setText(text);
        edit.setMinLines(4);
        edit.setGravity(Gravity.TOP | Gravity.START);
        // System default typeface: Chinese text must not render as tofu.
        edit.setTypeface(Typeface.DEFAULT);
        edit.setTextColor(0xFF17181A);
        edit.setTextSize(14f);
        int editPad = BrushPropertiesPopup.dp(context, 12);
        edit.setPadding(editPad, editPad, editPad, editPad);
        edit.setBackgroundResource(R.drawable.bg_input);
        edit.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        edit.setSelection(edit.getText().length());
        column.addView(edit, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = BrushPropertiesPopup.dp(context, 12);
        actions.setLayoutParams(alp);
        TextView cancel = actionText(context, R.string.obj_cancel, 0xFF6B7078, false);
        TextView copy = actionText(context, R.string.recog_copy, 0xFF6B7078, false);
        TextView insert = actionText(context, R.string.recog_insert_textbox, 0xFF2F6BFF, true);
        actions.addView(cancel);
        actions.addView(copy);
        actions.addView(insert);
        column.addView(actions);

        AlertDialog dialog = new AlertDialog.Builder(context).setView(column).create();
        cancel.setOnClickListener(v -> dialog.dismiss());
        copy.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager)
                    context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText(
                        "recognized_text", edit.getText().toString()));
            }
            CreamToast.show(toastAnchor != null ? toastAnchor : column,
                    R.string.recog_copied);
        });
        insert.setOnClickListener(v -> {
            String finalText = edit.getText().toString();
            dialog.dismiss();
            listener.onInsertTextBox(finalText);
        });
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
