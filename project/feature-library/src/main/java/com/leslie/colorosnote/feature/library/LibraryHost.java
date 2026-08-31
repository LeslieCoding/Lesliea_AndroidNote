package com.leslie.colorosnote.feature.library;

import com.leslie.colorosnote.core.storage.db.InkNoteDatabase;
import com.leslie.colorosnote.core.storage.repository.DocumentRepository;

import java.util.List;

/**
 * Contract between the library UI and its host (app module MainActivity).
 * Search, import and export are owned by feature-search / feature-import /
 * feature-export; the library only signals intent through these callbacks.
 */
public interface LibraryHost {

    /** Storage entry point owned by the app module. */
    DocumentRepository documentRepository();

    /** Metadata database (folders, recents, favorites, tags, trash listings). */
    InkNoteDatabase database();

    /** Open a document in the editor. */
    void openDocument(String documentId);

    /**
     * Start an import flow; type is one of the {@code IMPORT_*} constants in
     * {@link NewItemBottomSheet}. Wired to feature-import by the host.
     */
    void startImport(String importType);

    /** Open the search UI owned by feature-search. */
    void openSearch();

    /** Export the given documents; wired to feature-export by the host. */
    void exportDocuments(List<String> documentIds);
}
