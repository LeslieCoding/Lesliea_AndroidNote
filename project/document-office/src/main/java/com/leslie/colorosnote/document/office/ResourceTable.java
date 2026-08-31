package com.leslie.colorosnote.document.office;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Collects embedded images during extraction, assigns stable ids
 * ("img-001.png") and enforces image limits via the guard.
 */
final class ResourceTable {

    private final ImportGuard guard;
    private final List<ImageResource> resources = new ArrayList<>();

    ResourceTable(ImportGuard guard) {
        this.guard = guard;
    }

    /** Registers image bytes; returns the resource id to reference from draw commands. */
    String add(byte[] data, String extension) throws OfficeImportException {
        if (data == null || data.length == 0) {
            return null;
        }
        guard.addImage(data.length);
        String ext = (extension == null || extension.isEmpty()) ? "bin"
                : extension.toLowerCase(Locale.US);
        if (ext.startsWith(".")) {
            ext = ext.substring(1);
        }
        String id = String.format(Locale.US, "img-%03d.%s", resources.size() + 1, ext);
        resources.add(new ImageResource(id, mimeFor(ext), data));
        return id;
    }

    List<ImageResource> all() {
        return resources;
    }

    private static String mimeFor(String ext) {
        switch (ext) {
            case "png": return "image/png";
            case "jpg":
            case "jpeg": return "image/jpeg";
            case "gif": return "image/gif";
            case "bmp": return "image/bmp";
            case "wmf": return "image/x-wmf";
            case "emf": return "image/x-emf";
            default: return "application/octet-stream";
        }
    }
}
