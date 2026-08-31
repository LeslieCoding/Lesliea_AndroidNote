package com.leslie.colorosnote.feature.editor;

import android.content.Context;

import com.leslie.colorosnote.core.ink.InkCommands;
import com.leslie.colorosnote.core.model.Page;
import com.leslie.colorosnote.core.model.TextBoxObject;

/**
 * 把识别结果文本插入当前页为文本框（手写转文字 / 图片 OCR 共用）：
 * 落在页面内容下方的空白处，走 {@link InkCommands.AddObject} 撤销栈，
 * 并同步增量搜索索引（与文本框编辑确认路径一致，spec 22）。
 */
final class RecognizedTextInserter {

    private RecognizedTextInserter() {
    }

    static void insertBelowContent(EditorFragment fragment, String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        Page page = fragment.getCurrentPage();
        Context context = fragment.getContext();
        if (page == null || context == null) {
            return;
        }
        TextBoxObject box = new TextBoxObject();
        box.text = text;
        box.fontSize = HandwritingToTextPlanner.BOX_FONT_SIZE;
        box.lineSpacing = HandwritingToTextPlanner.BOX_LINE_SPACING;
        int lines = text.split("\n", -1).length;
        box.bounds = HandwritingToTextPlanner.insertBounds(
                HandwritingToTextPlanner.contentBottom(page),
                page.width, page.height, lines);
        box.zIndex = ObjectZOrder.frontZIndex(page.objects);
        fragment.getUndoStack().execute(new InkCommands.AddObject(page, box));
        fragment.notifyObjectToolEdited();
        // Keep the incremental search index in sync (spec 22).
        EditorServices.get(context).searchIndexer()
                .indexTextBox(fragment.getDocumentId(), page.id, text);
    }
}
