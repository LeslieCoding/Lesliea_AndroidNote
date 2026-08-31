package com.leslie.colorosnote.feature.importer;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import com.leslie.colorosnote.core.common.AppLogger;
import com.leslie.colorosnote.core.common.IdGenerator;
import com.leslie.colorosnote.core.model.NoteDocument;
import com.leslie.colorosnote.core.model.Page;
import com.leslie.colorosnote.core.storage.container.InknoteReader;
import com.leslie.colorosnote.core.storage.container.InknoteWriter;
import com.leslie.colorosnote.core.storage.repository.DocumentRepository;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * {@link DocumentStore} on top of {@link DocumentRepository}.
 *
 * <p>{@link #save} writes the container twice when extra entries are present:
 * {@code saveDocument} keeps the Room metadata rows authoritative, and a second
 * {@link InknoteWriter} pass adds the raw resource entries the repository API
 * does not accept. Containers are written atomically, so the intermediate state
 * is never observable as corruption.
 */
public final class RepositoryDocumentStore implements DocumentStore {

    private static final String TAG = "RepositoryDocumentStore";

    private final DocumentRepository repository;

    public RepositoryDocumentStore(@NonNull DocumentRepository repository) {
        this.repository = repository;
    }

    @WorkerThread
    @Override
    public void save(@NonNull NoteDocument document, @NonNull Map<String, byte[]> extraEntries)
            throws IOException {
        repository.saveDocument(document);
        if (!extraEntries.isEmpty()) {
            new InknoteWriter().write(repository.fileFor(document.id), document, extraEntries);
        }
    }

    @WorkerThread
    @Override
    @NonNull
    public NoteDocument importInknote(@NonNull File containerFile, @NonNull String newDocumentId)
            throws IOException {
        // Mirrors DocumentRepository.duplicateDocument: fresh ids, same content.
        NoteDocument document = new InknoteReader().read(containerFile);
        long now = System.currentTimeMillis();
        document.id = newDocumentId;
        document.createdAt = now;
        document.updatedAt = now;
        int index = 0;
        for (Page page : document.pages) {
            page.id = IdGenerator.newId();
            page.index = index++;
            page.createdAt = now;
            page.updatedAt = now;
        }
        repository.saveDocument(document);
        return document;
    }

    @Override
    public void deleteIncomplete(@NonNull String documentId) {
        try {
            repository.deleteForever(documentId);
        } catch (RuntimeException e) {
            AppLogger.w(TAG, "failed to delete incomplete document " + documentId, e);
        }
    }
}
