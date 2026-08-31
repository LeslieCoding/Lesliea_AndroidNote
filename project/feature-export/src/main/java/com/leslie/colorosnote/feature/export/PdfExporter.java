package com.leslie.colorosnote.feature.export;

import android.graphics.pdf.PdfDocument;

import com.leslie.colorosnote.core.model.NoteDocument;
import com.leslie.colorosnote.core.model.Page;
import com.leslie.colorosnote.core.render.PageRenderer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * Exports a {@link NoteDocument} to PDF via android.graphics.pdf.PdfDocument
 * (spec 37): one PDF page per selected note page, rendered through
 * {@link PageRenderer} so ink, backgrounds and objects match the editor.
 */
public final class PdfExporter {

    private PdfExporter() {
    }

    /**
     * @param bitmapProvider optional lookup for IMAGE/CUSTOM background bitmaps
     * @return number of pages written
     */
    public static int export(NoteDocument doc, ExportOptions options, File outFile,
                             PageRenderer.ResourceBitmapProvider bitmapProvider) throws IOException {
        List<Page> pages = PageRenderFilter.pagesInRange(doc, options);
        if (pages.isEmpty()) {
            throw new IOException("no pages in export range");
        }
        int dpi = options.clampDpi();
        PdfDocument pdf = new PdfDocument();
        try {
            int pageNumber = 1;
            for (Page page : pages) {
                int w = PageRenderFilter.pixelWidth(page, dpi);
                int h = PageRenderFilter.pixelHeight(page, dpi);
                PdfDocument.PageInfo info =
                        new PdfDocument.PageInfo.Builder(w, h, pageNumber++).create();
                PdfDocument.Page pdfPage = pdf.startPage(info);
                PageBitmapRenderer.renderInto(pdfPage.getCanvas(), page, options, bitmapProvider);
                pdf.finishPage(pdfPage);
            }
            try (OutputStream out = new FileOutputStream(outFile)) {
                pdf.writeTo(out);
            }
        } finally {
            pdf.close();
        }
        return pages.size();
    }
}
