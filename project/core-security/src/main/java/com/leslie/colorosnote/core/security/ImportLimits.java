package com.leslie.colorosnote.core.security;

import java.util.Locale;

/**
 * Import-time safety limits (spec 32/36): size caps, timeouts, zip bomb guards.
 * Pure JVM value object used by feature-import.
 */
public final class ImportLimits {

    /** Max accepted import file size: 512 MB. */
    public static final long MAX_IMPORT_FILE_BYTES = 512L * 1024 * 1024;
    /** Max total uncompressed size when unzipping: 2 GB. */
    public static final long MAX_UNZIP_TOTAL_BYTES = 2L * 1024 * 1024 * 1024;
    /** Max zip entries. */
    public static final int MAX_ZIP_ENTRIES = 100_000;
    /** Max compression ratio before we call it a zip bomb. */
    public static final int MAX_COMPRESSION_RATIO = 200;
    /** Per-import parse timeout. */
    public static final long IMPORT_TIMEOUT_MS = 5 * 60 * 1000;
    /** OCR / conversion memory guard: max decoded bitmap dimension. */
    public static final int MAX_BITMAP_DIMENSION = 8192;

    private ImportLimits() {
    }

    /** Validate file size and extension/content consistency before import. */
    public static String validate(String fileName, long sizeBytes, byte[] header) {
        if (sizeBytes > MAX_IMPORT_FILE_BYTES) {
            return "File too large: " + (sizeBytes / 1024 / 1024) + " MB";
        }
        String ext = "";
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        if (dot >= 0) {
            ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        }
        FileTypeDetector.FileType type = FileTypeDetector.detect(header);
        if (!FileTypeDetector.isConsistent(ext, type)) {
            return "File content does not match extension ." + ext;
        }
        return null; // ok
    }
}
