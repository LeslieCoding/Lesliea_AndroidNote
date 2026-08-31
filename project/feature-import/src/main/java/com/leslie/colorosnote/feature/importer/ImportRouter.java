package com.leslie.colorosnote.feature.importer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.leslie.colorosnote.core.security.FileTypeDetector;
import com.leslie.colorosnote.document.office.OfficeType;

import java.util.Locale;

/**
 * Format decision table for {@link ImportStage#DETECT_FORMAT} (spec 36).
 *
 * <p>Extension decides between the ZIP-based containers (docx/xlsx/pptx/.inknote
 * share the same magic) and the CAJ family (a .caj holding a real PDF must go
 * through the CAJ engine, which handles that case). Magic bytes decide between
 * PDF and images. Extension/content consistency has already been enforced at
 * {@link ImportStage#VALIDATE} by {@code ImportLimits.validate}, so a mismatched
 * pair never reaches this table.
 *
 * <p>Pure JVM and static so the whole dispatch table is unit-testable.
 */
public final class ImportRouter {

    private ImportRouter() {
    }

    /**
     * @param fileName display name of the source file (extension used)
     * @param magic    content type detected from the header bytes
     * @throws ImportError {@link ImportError.Type#UNSUPPORTED_FORMAT} when the
     *                     combination is not importable
     */
    @NonNull
    public static ImportKind route(@Nullable String fileName,
                                   @NonNull FileTypeDetector.FileType magic) throws ImportError {
        String ext = extension(fileName);
        if ("inknote".equals(ext)) {
            return ImportKind.INKNOTE;
        }
        if (isCajExtension(ext)) {
            // Includes .caj files that are really PDFs: the CAJ engine detects
            // that from content and short-circuits to a plain copy.
            return ImportKind.CAJ;
        }
        if (OfficeType.fromFileName(fileName) != null) {
            return ImportKind.OFFICE;
        }
        switch (magic) {
            case PDF:
                return ImportKind.PDF;
            case PNG:
            case JPEG:
            case WEBP:
            case GIF:
                return ImportKind.IMAGE;
            default:
                throw ImportError.unsupportedFormat(
                        "不支持的文件格式" + (ext.isEmpty() ? "" : "（." + ext + "）"),
                        "ext=" + ext + " magic=" + magic);
        }
    }

    static boolean isCajExtension(@NonNull String ext) {
        switch (ext) {
            case "caj":
            case "kdh":
            case "nh":
            case "teb":
                return true;
            default:
                return false;
        }
    }

    @NonNull
    static String extension(@Nullable String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
