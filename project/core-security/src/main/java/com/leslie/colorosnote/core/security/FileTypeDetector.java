package com.leslie.colorosnote.core.security;

import java.util.HashMap;
import java.util.Map;

/**
 * File type detection by magic numbers (spec 32). Never trust extensions alone.
 * Pure JVM.
 */
public final class FileTypeDetector {

    public enum FileType {
        PDF, ZIP_CONTAINER, // docx/xlsx/pptx/.inknote are ZIP-based
        OLE2,               // doc/xls/ppt legacy
        PNG, JPEG, WEBP, GIF,
        CAJ, UNKNOWN
    }

    private static final Map<String, byte[]> MAGIC = new HashMap<>();

    static {
        MAGIC.put("PDF", new byte[]{0x25, 0x50, 0x44, 0x46});            // %PDF
        MAGIC.put("ZIP", new byte[]{0x50, 0x4B, 0x03, 0x04});            // PK\x03\x04
        MAGIC.put("ZIP_EMPTY", new byte[]{0x50, 0x4B, 0x05, 0x06});
        MAGIC.put("OLE2", new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
                (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1});
        MAGIC.put("PNG", new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});
        MAGIC.put("JPEG", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
        MAGIC.put("GIF", new byte[]{0x47, 0x49, 0x46, 0x38});            // GIF8
        MAGIC.put("WEBP", new byte[]{0x52, 0x49, 0x46, 0x46});           // RIFF....WEBP
    }

    private FileTypeDetector() {
    }

    public static FileType detect(byte[] header) {
        if (header == null || header.length < 4) {
            return FileType.UNKNOWN;
        }
        if (matches(header, MAGIC.get("PDF"))) return FileType.PDF;
        if (matches(header, MAGIC.get("ZIP")) || matches(header, MAGIC.get("ZIP_EMPTY")))
            return FileType.ZIP_CONTAINER;
        if (matches(header, MAGIC.get("OLE2"))) return FileType.OLE2;
        if (matches(header, MAGIC.get("PNG"))) return FileType.PNG;
        if (matches(header, MAGIC.get("JPEG"))) return FileType.JPEG;
        if (matches(header, MAGIC.get("GIF"))) return FileType.GIF;
        if (matches(header, MAGIC.get("WEBP")) && header.length >= 12
                && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P')
            return FileType.WEBP;
        // CAJ variants commonly start with "CAJ" or "HN" magic
        if (header.length >= 3 && header[0] == 'C' && header[1] == 'A' && header[2] == 'J')
            return FileType.CAJ;
        return FileType.UNKNOWN;
    }

    private static boolean matches(byte[] data, byte[] magic) {
        if (data.length < magic.length) return false;
        for (int i = 0; i < magic.length; i++) {
            if (data[i] != magic[i]) return false;
        }
        return true;
    }

    /**
     * Consistency check between a declared extension and actual content.
     * Returns true when the content plausibly matches the extension.
     */
    public static boolean isConsistent(String extension, FileType detected) {
        if (extension == null) return detected != FileType.UNKNOWN;
        switch (extension.toLowerCase(java.util.Locale.ROOT)) {
            case "pdf": return detected == FileType.PDF;
            case "docx": case "xlsx": case "pptx": case "inknote":
                return detected == FileType.ZIP_CONTAINER;
            case "doc": case "xls": case "ppt":
                return detected == FileType.OLE2;
            case "png": return detected == FileType.PNG;
            case "jpg": case "jpeg": return detected == FileType.JPEG;
            case "webp": return detected == FileType.WEBP;
            case "caj": case "kdh": case "nh": case "teb":
                return detected == FileType.CAJ || detected == FileType.PDF;
            default: return true; // unknown extension: don't block on content
        }
    }
}
