package com.leslie.colorosnote.feature.importer;

import androidx.annotation.NonNull;

import com.leslie.colorosnote.core.model.NoteDocument;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * Persistence seam for finished imports. The production adapter wraps
 * {@code DocumentRepository} (Room + container writer); JVM tests inject a fake.
 */
public interface DocumentStore {

    /**
     * Persists the document: .inknote container plus metadata rows.
     *
     * @param extraEntries raw container entries keyed by full zip path
     *                     (e.g. {@code InknoteContainer.resourcePath(id)}); may
     *                     be empty, never null
     */
    void save(@NonNull NoteDocument document, @NonNull Map<String, byte[]> extraEntries)
            throws IOException;

    /**
     * Imports our own .inknote container: reads it, regenerates the document id
     * (and page ids) under {@code newDocumentId} and persists the copy.
     */
    @NonNull
    NoteDocument importInknote(@NonNull File containerFile, @NonNull String newDocumentId)
            throws IOException;

    /**
     * Deletes a partially persisted document after a failed import
     * (spec 36: 不在资料库留下半成品). Must be idempotent and never throw.
     */
    void deleteIncomplete(@NonNull String documentId);
}
