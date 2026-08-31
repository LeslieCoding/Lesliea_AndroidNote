package com.leslie.colorosnote.feature.library;

/**
 * Flat list model for the library grid/list: folders, documents, trash entries
 * and tags share one adapter. Built on a background thread, diffed with
 * DiffUtil on the main thread.
 */
final class LibraryEntry {

    static final int TYPE_FOLDER = 0;
    static final int TYPE_DOCUMENT = 1;
    static final int TYPE_TRASH = 2;
    static final int TYPE_TAG = 3;

    final int type;
    final String id;
    String title = "";
    /** Preformatted subtitle (modified time, trash days or doc count). */
    String subtitle = "";
    long updatedAt;
    long createdAt;
    /** {@code NoteDocument.DocType} name for documents. */
    String docType = "";
    boolean favorite;
    /** Days until auto-purge; only meaningful for {@link #TYPE_TRASH}. */
    long trashDays = -1;

    LibraryEntry(int type, String id) {
        this.type = type;
        this.id = id;
    }

    boolean sameContent(LibraryEntry other) {
        return type == other.type
                && title.equals(other.title)
                && subtitle.equals(other.subtitle)
                && updatedAt == other.updatedAt
                && createdAt == other.createdAt
                && docType.equals(other.docType)
                && favorite == other.favorite
                && trashDays == other.trashDays;
    }
}
