package com.leslie.colorosnote.feature.export;

import com.leslie.colorosnote.core.model.NoteDocument;
import com.leslie.colorosnote.core.model.Page;
import com.leslie.colorosnote.core.model.PageObject;
import com.leslie.colorosnote.core.model.TextBoxObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Exports the textual content of a note (text boxes, page titles) to
 * TXT / Markdown (spec 37). Pure JVM.
 */
public final class NoteTextExporter {

    private NoteTextExporter() {
    }

    public static String toMarkdown(NoteDocument doc, ExportOptions options) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(doc.title == null ? "Untitled" : doc.title).append("\n\n");
        for (Page page : pagesInRange(doc, options)) {
            if (page.title != null && !page.title.isEmpty()) {
                sb.append("## ").append(page.title).append("\n\n");
            }
            for (String text : textsOf(page)) {
                sb.append(text).append("\n\n");
            }
        }
        return sb.toString();
    }

    public static String toTxt(NoteDocument doc, ExportOptions options) {
        StringBuilder sb = new StringBuilder();
        if (doc.title != null) {
            sb.append(doc.title).append("\n\n");
        }
        for (Page page : pagesInRange(doc, options)) {
            if (page.title != null && !page.title.isEmpty()) {
                sb.append("== ").append(page.title).append(" ==\n");
            }
            for (String text : textsOf(page)) {
                sb.append(text).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static List<Page> pagesInRange(NoteDocument doc, ExportOptions options) {
        List<Page> out = new ArrayList<>();
        if (doc.pages == null) return out;
        int i = 0;
        for (Page p : doc.pages) {
            if (options == null || options.isPageIncluded(i, doc.pages.size())) {
                out.add(p);
            }
            i++;
        }
        return out;
    }

    /** Text boxes ordered top-to-bottom, left-to-right (reading order). */
    private static List<String> textsOf(Page page) {
        List<TextBoxObject> boxes = new ArrayList<>();
        if (page.objects != null) {
            for (PageObject o : page.objects) {
                if (o instanceof TextBoxObject) {
                    TextBoxObject tb = (TextBoxObject) o;
                    if (tb.text != null && !tb.text.trim().isEmpty()) {
                        boxes.add(tb);
                    }
                }
            }
        }
        boxes.sort(Comparator
                .comparing((TextBoxObject t) -> t.bounds == null ? 0f : t.bounds[1])
                .thenComparing(t -> t.bounds == null ? 0f : t.bounds[0]));
        List<String> out = new ArrayList<>();
        for (TextBoxObject tb : boxes) {
            out.add(tb.text);
        }
        return out;
    }
}
