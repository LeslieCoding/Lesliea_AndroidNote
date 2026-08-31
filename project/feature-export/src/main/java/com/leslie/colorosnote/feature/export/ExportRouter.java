package com.leslie.colorosnote.feature.export;

import com.leslie.colorosnote.core.model.NoteDocument;
import com.leslie.colorosnote.core.render.PageRenderer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;

/**
 * Single entry point for exporting one document (spec 37). Dispatches on
 * {@link ExportOptions.Format} to the format-specific exporters. Runs on a
 * background thread; callers report progress through their own UI.
 */
public final class ExportRouter {

    private ExportRouter() {
    }

    /**
     * Exports one document into {@code outDir}.
     *
     * @param doc            loaded document
     * @param containerFile  the document's .inknote container (only used for
     *                       INKNOTE export; may be null otherwise)
     * @param bitmapProvider optional lookup for IMAGE/CUSTOM background bitmaps
     * @return the primary output file (for PNG/JPEG: the directory holding the pages)
     */
    public static File export(NoteDocument doc, File containerFile, ExportOptions options,
                              File outDir, PageRenderer.ResourceBitmapProvider bitmapProvider)
            throws IOException {
        if (!outDir.isDirectory() && !outDir.mkdirs()) {
            throw new IOException("cannot create output dir: " + outDir);
        }
        String base = sanitize(doc.title.isEmpty() ? "note" : doc.title);
        switch (options.format) {
            case PDF: {
                File out = unique(outDir, base, ".pdf");
                PdfExporter.export(doc, options, out, bitmapProvider);
                return out;
            }
            case PNG:
            case JPEG: {
                File dir = uniqueDir(outDir, base);
                PngExporter.export(doc, options, dir, bitmapProvider);
                return dir;
            }
            case TXT: {
                File out = unique(outDir, base, ".txt");
                writeText(out, NoteTextExporter.toTxt(doc, options));
                return out;
            }
            case MARKDOWN: {
                File out = unique(outDir, base, ".md");
                writeText(out, NoteTextExporter.toMarkdown(doc, options));
                return out;
            }
            case INKNOTE: {
                if (containerFile == null || !containerFile.isFile()) {
                    throw new IOException("container file missing for " + doc.id);
                }
                File out = unique(outDir, base, ".inknote");
                Files.copy(containerFile.toPath(), out.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
                return out;
            }
            case BACKUP_ZIP:
                throw new IOException("BACKUP_ZIP is a library-level export; use BackupManager");
            default:
                throw new IOException("unsupported format: " + options.format);
        }
    }

    private static void writeText(File out, String text) throws IOException {
        Files.write(out.toPath(), text.getBytes("UTF-8"));
    }

    static String sanitize(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    /** Picks a non-existing file name, appending " (n)" when taken. */
    static File unique(File dir, String base, String ext) {
        File f = new File(dir, base + ext);
        for (int n = 2; f.exists(); n++) {
            f = new File(dir, String.format(Locale.US, "%s (%d)%s", base, n, ext));
        }
        return f;
    }

    private static File uniqueDir(File dir, String base) {
        File f = new File(dir, base);
        for (int n = 2; f.exists(); n++) {
            f = new File(dir, String.format(Locale.US, "%s (%d)", base, n));
        }
        return f;
    }
}
