package com.leslie.colorosnote.feature.editor;

import android.graphics.Bitmap;

import com.leslie.colorosnote.core.common.IdGenerator;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Session-level bitmap store for inserted image / user-sticker resources,
 * keyed by resourceId. Doubles as the PageRenderer.ResourceBitmapProvider for
 * the editor surface.
 *
 * <p>Persistence: the bytes live in the .inknote container under
 * {@code resources/<resourceId>} — EditorActivity stages them via
 * DocumentRepository.putResource on insert, and EditorFragment re-registers
 * decoded container resources here when a document opens. The registry key is
 * always the entry name verbatim (extension included when present, e.g. the
 * import flow's "image_0"), matching how PageRenderer resolves background
 * resourceIds through the same provider.
 */
public final class EditorBitmapRegistry {

    private static final int MAX_ENTRIES = 32;

    private static final Map<String, Bitmap> BITMAPS =
            new LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };

    private EditorBitmapRegistry() {
    }

    /** Registers a bitmap under a fresh resource id. */
    public static synchronized String put(Bitmap bitmap) {
        String id = IdGenerator.newId();
        BITMAPS.put(id, bitmap);
        return id;
    }

    /** Registers a bitmap under an existing resource id (document reopen). */
    public static synchronized void put(String resourceId, Bitmap bitmap) {
        if (resourceId != null && bitmap != null) {
            BITMAPS.put(resourceId, bitmap);
        }
    }

    /** Bitmap for a resource id, or null (LRU-touching). */
    public static synchronized Bitmap get(String resourceId) {
        return resourceId == null ? null : BITMAPS.get(resourceId);
    }

    public static synchronized void remove(String resourceId) {
        if (resourceId != null) {
            BITMAPS.remove(resourceId);
        }
    }
}
