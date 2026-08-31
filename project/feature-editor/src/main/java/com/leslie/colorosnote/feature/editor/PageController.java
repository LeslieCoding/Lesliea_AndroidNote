package com.leslie.colorosnote.feature.editor;

import com.leslie.colorosnote.core.common.IdGenerator;
import com.leslie.colorosnote.core.ink.UndoRedoStack;
import com.leslie.colorosnote.core.model.NoteDocument;
import com.leslie.colorosnote.core.model.Page;
import com.leslie.colorosnote.core.model.PageBackground;

/**
 * Page-system operations (spec 26), all undoable through the shared stack:
 * add/delete/duplicate/move pages, template switching, bookmark and title.
 * {@link #insertBlankPageAfter(int)} is the hook for inserting blank pages
 * between PDF pages once the PDF substrate lands (currently identical to
 * {@link #addPageAfter}).
 */
public final class PageController {

    private final NoteDocument document;
    private final UndoRedoStack undoStack;

    public PageController(NoteDocument document, UndoRedoStack undoStack) {
        this.document = document;
        this.undoStack = undoStack;
    }

    public int pageCount() {
        return document.pages.size();
    }

    public Page pageAt(int index) {
        return document.pages.get(index);
    }

    /** Adds a new page after the given index with the requested template. */
    public Page addPageAfter(int index, PageBackground.Type template) {
        Page page = newPage(index + 1, template);
        undoStack.execute(new EditorCommands.AddPage(document, page, index + 1));
        return page;
    }

    /**
     * Hook for "insert a blank page between PDF pages" (spec 26). For PDF
     * documents the background pipeline will attach the neighboring PDF pages;
     * the editor-side contract is simply: a new blank page at index + 1.
     */
    public Page insertBlankPageAfter(int index) {
        return addPageAfter(index, PageBackground.Type.BLANK);
    }

    /** Deletes the page at the index. Returns false when it is the last page. */
    public boolean deletePage(int index) {
        if (document.pages.size() <= 1 || index < 0 || index >= document.pages.size()) {
            return false;
        }
        undoStack.execute(new EditorCommands.RemovePage(document, document.pages.get(index)));
        return true;
    }

    /** Duplicates the page (deep copy, fresh ids) right after the original. */
    public Page duplicatePage(int index) {
        if (index < 0 || index >= document.pages.size()) {
            return null;
        }
        Page copy = ModelCopier.copyPage(document.pages.get(index));
        copy.index = index + 1;
        undoStack.execute(new EditorCommands.AddPage(document, copy, index + 1));
        return copy;
    }

    /** Moves a page; used by thumbnail-bar move operations. */
    public void movePage(int from, int to) {
        if (from == to || from < 0 || to < 0
                || from >= document.pages.size() || to >= document.pages.size()) {
            return;
        }
        undoStack.execute(new EditorCommands.MovePage(document, from, to));
    }

    /** Switches the background template of a page (undoable). */
    public void setTemplate(Page page, PageBackground.Type type) {
        if (page.background != null && page.background.type == type) {
            return;
        }
        PageBackground bg = ModelCopier.copyBackground(page.background);
        bg.type = type;
        // Template switch drops the source-document link.
        bg.pdfPageIndex = null;
        bg.resourceId = null;
        undoStack.execute(new EditorCommands.ChangeBackground(page, bg));
    }

    public void toggleBookmark(Page page) {
        page.bookmarked = !page.bookmarked;
        page.updatedAt = System.currentTimeMillis();
    }

    /**
     * Rotates the page 90° by swapping its logical size (spec 26 页面旋转).
     * Stroke coordinates are unchanged; callers should re-fit the viewport.
     * Not part of the undo history (spec 30 does not list page rotation).
     */
    public void rotatePage(Page page) {
        float w = page.width;
        page.width = page.height;
        page.height = w;
        page.updatedAt = System.currentTimeMillis();
    }

    public void setPageTitle(Page page, String title) {
        page.title = title != null ? title : "";
        page.updatedAt = System.currentTimeMillis();
    }

    private Page newPage(int index, PageBackground.Type template) {
        Page page = new Page();
        page.id = IdGenerator.newId();
        page.index = index;
        page.background = PageBackground.ofType(template);
        long now = System.currentTimeMillis();
        page.createdAt = now;
        page.updatedAt = now;
        return page;
    }
}
