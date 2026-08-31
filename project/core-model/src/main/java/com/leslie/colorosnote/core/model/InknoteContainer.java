package com.leslie.colorosnote.core.model;

/**
 * Constants of the .inknote container format (requirement 9): a versioned ZIP
 * with a manifest, the document JSON, per-page JSON, binary stroke payloads and
 * loose resource/audio/thumbnail/OCR/search/attachment directories.
 */
public final class InknoteContainer {

    /** Container schema version written into manifest.json. */
    public static final int SCHEMA_VERSION = 1;

    public static final String FILE_EXTENSION = ".inknote";

    public static final String MANIFEST = "manifest.json";
    public static final String DOCUMENT = "document.json";
    public static final String DIR_PAGES = "pages/";
    public static final String DIR_STROKES = "strokes/";
    public static final String DIR_RESOURCES = "resources/";
    public static final String DIR_AUDIO = "audio/";
    public static final String DIR_THUMBNAILS = "thumbnails/";
    public static final String DIR_OCR = "ocr/";
    public static final String DIR_SEARCH = "search/";
    public static final String DIR_ATTACHMENTS = "attachments/";

    /** manifest.json field carrying the container schema version. */
    public static final String MANIFEST_FIELD_SCHEMA_VERSION = "schemaVersion";

    private InknoteContainer() {
    }

    /** Zip path of a page's JSON: pages/{pageId}.json */
    public static String pagePath(String pageId) {
        return DIR_PAGES + pageId + ".json";
    }

    /** Zip path of a page's binary strokes: strokes/{pageId}.bin */
    public static String strokesPath(String pageId) {
        return DIR_STROKES + pageId + ".bin";
    }

    /** Zip path of a binary resource: resources/{resourceId} */
    public static String resourcePath(String resourceId) {
        return DIR_RESOURCES + resourceId;
    }

    /** Zip path of an audio segment: audio/{audioId} */
    public static String audioPath(String audioId) {
        return DIR_AUDIO + audioId;
    }

    /** Zip path of a page thumbnail: thumbnails/{pageId}.webp */
    public static String thumbnailPath(String pageId) {
        return DIR_THUMBNAILS + pageId + ".webp";
    }
}
