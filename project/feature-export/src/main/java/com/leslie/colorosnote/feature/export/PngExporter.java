package com.leslie.colorosnote.feature.export;

import android.graphics.Bitmap;

import com.leslie.colorosnote.core.model.NoteDocument;
import com.leslie.colorosnote.core.model.Page;
import com.leslie.colorosnote.core.render.PageRenderer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Exports note pages as PNG or JPEG images (spec 37), one file per page:
 * {@code <title>_p01.png}. Files land in the given output directory.
 */
public final class PngExporter {

    private PngExporter() {
    }

    /**
     * @param bitmapProvider optional lookup for IMAGE/CUSTOM background bitmaps
     * @return the files written, in page order
     */
    public static List<File> export(NoteDocument doc, ExportOptions options, File outDir,
                                    PageRenderer.ResourceBitmapProvider bitmapProvider)
            throws IOException {
        List<Page> pages = PageRenderFilter.pagesInRange(doc, options);
        if (pages.isEmpty()) {
            throw new IOException("no pages in export range");
        }
        if (!outDir.isDirectory() && !outDir.mkdirs()) {
            throw new IOException("cannot create output dir: " + outDir);
        }
        boolean jpeg = options.format == ExportOptions.Format.JPEG;
        Bitmap.CompressFormat compressFormat =
                jpeg ? Bitmap.CompressFormat.JPEG : Bitmap.CompressFormat.PNG;
        String ext = jpeg ? ".jpg" : ".png";
        String base = sanitize(doc.title.isEmpty() ? "note" : doc.title);

        List<File> written = new ArrayList<>();
        int n = 1;
        for (Page page : pages) {
            Bitmap bitmap = PageBitmapRenderer.render(page, options, bitmapProvider);
            File out = new File(outDir,
                    String.format(Locale.US, "%s_p%02d%s", base, n++, ext));
            try (OutputStream os = new FileOutputStream(out)) {
                if (!bitmap.compress(compressFormat, 92, os)) {
                    throw new IOException("bitmap compression failed");
                }
            } finally {
                bitmap.recycle();
            }
            written.add(out);
        }
        return written;
    }

    private static String sanitize(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }
}
