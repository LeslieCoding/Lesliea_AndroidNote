package com.leslie.colorosnote.core.storage.repository;

import com.leslie.colorosnote.core.common.AppExecutors;
import com.leslie.colorosnote.core.common.AppLogger;
import com.leslie.colorosnote.core.common.Result;
import com.leslie.colorosnote.core.model.InknoteContainer;
import com.leslie.colorosnote.core.model.NoteDocument;
import com.leslie.colorosnote.core.model.Page;
import com.leslie.colorosnote.core.storage.container.InknoteReader;
import com.leslie.colorosnote.core.storage.container.InknoteWriter;
import com.leslie.colorosnote.core.storage.db.InkNoteDatabase;
import com.leslie.colorosnote.core.storage.db.entity.DocumentEntity;
import com.leslie.colorosnote.core.storage.db.entity.PageEntity;
import com.leslie.colorosnote.core.storage.db.entity.RecentDocumentEntity;
import com.leslie.colorosnote.core.storage.journal.JournalStore;
import com.leslie.colorosnote.core.storage.trash.TrashManager;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Unified entry point for document persistence (requirements 9/10/25/34):
 * coordinates the .inknote container files, the change journal and the Room
 * metadata database behind one API.
 *
 * <p>All synchronous methods perform blocking IO — call them on
 * AppExecutors.io(). The {@code *Async} variants do the hop themselves and
 * deliver a {@link Result} on the callback executor.
 */
public final class DocumentRepository {

    private static final String TAG = "DocumentRepository";

    /** Async result callback; invoked on the executor passed to the *Async call. */
    public interface Callback<T> {
        void onResult(Result<T> result);
    }

    private final InkNoteDatabase db;
    private final File documentsDir;
    private final InknoteWriter writer;
    private final InknoteReader reader;
    private final TrashManager trashManager;

    /** Serializes pending-extra staging against container read-merge-write. */
    private final Object extrasLock = new Object();
    /** Extra bytes staged by {@link #putResource}, merged on the next save. */
    private final Map<String, Map<String, byte[]>> pendingExtras = new HashMap<>();

    public DocumentRepository(InkNoteDatabase db, File documentsDir) {
        this.db = db;
        this.documentsDir = documentsDir;
        this.writer = new InknoteWriter();
        this.reader = new InknoteReader();
        this.trashManager = new TrashManager(db, documentsDir);
        //noinspection ResultOfMethodCallIgnored
        documentsDir.mkdirs();
    }

    /**
     * Container-only mode for pure-JVM unit tests: container, journal and
     * resource APIs work; Room-backed metadata/trash operations are skipped.
     */
    DocumentRepository(File documentsDir) {
        this(null, documentsDir);
    }

    public TrashManager trashManager() {
        return trashManager;
    }

    /** Container file for a document id. */
    public File fileFor(String documentId) {
        return new File(documentsDir, documentId + InknoteContainer.FILE_EXTENSION);
    }

    /** Journal store for an open document (strokes append here on completion). */
    public JournalStore journalFor(String documentId) {
        return new JournalStore(JournalStore.journalFileFor(documentsDir, documentId));
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /** Creates a document with one blank page: container first, then metadata. */
    public NoteDocument createDocument(String title, String folderId) throws IOException {
        long now = System.currentTimeMillis();
        NoteDocument document = new NoteDocument();
        document.title = title != null ? title : "";
        document.folderId = folderId;
        document.createdAt = now;
        document.updatedAt = now;
        Page page = new Page();
        page.index = 0;
        page.createdAt = now;
        page.updatedAt = now;
        document.pages.add(page);

        writer.write(fileFor(document.id), document);
        upsertMetadata(document, now);
        return document;
    }

    /**
     * Opens a document: reads the container, then replays any pending journal
     * (crash recovery, requirement 10) so no committed stroke is lost.
     */
    public NoteDocument openDocument(String documentId) throws IOException {
        NoteDocument document = reader.read(fileFor(documentId));
        int recovered = journalFor(documentId).recover(document);
        if (recovered > 0) {
            AppLogger.i(TAG, "recovered " + recovered + " journal entries for " + documentId);
        }
        long now = System.currentTimeMillis();
        RecentDocumentEntity recent = new RecentDocumentEntity(documentId);
        recent.openedAt = now;
        if (db != null) {
            db.libraryExtrasDao().touchRecent(recent);
        }
        return document;
    }

    /**
     * Full save: atomically rewrites the container, syncs metadata rows, then
     * compacts the journal (its entries are now part of the main document).
     *
     * <p>The rewrite preserves the container's existing extra entries
     * (resources/, audio/, thumbnails/, ...) and merges bytes staged via
     * {@link #putResource}, so inserted images and imported payloads survive
     * the editor's debounced full saves.
     */
    public void saveDocument(NoteDocument document) throws IOException {
        long now = System.currentTimeMillis();
        document.updatedAt = now;
        File file = fileFor(document.id);
        synchronized (extrasLock) {
            Map<String, byte[]> extras = reader.readExtras(file);
            Map<String, byte[]> pending = pendingExtras.get(document.id);
            if (pending != null) {
                extras.putAll(pending); // staged bytes win over on-disk entries
            }
            writer.write(file, document, extras);
            pendingExtras.remove(document.id);
        }
        upsertMetadata(document, now);
        journalFor(document.id).compact();
    }

    /** Non-trashed documents of a folder (null = library root), newest first. */
    public List<DocumentEntity> listDocuments(String folderId) {
        return db.documentDao().listByFolder(folderId);
    }

    // ------------------------------------------------------------------
    // Trash delegation
    // ------------------------------------------------------------------

    public void moveToTrash(String documentId) {
        trashManager.moveToTrash(documentId);
    }

    public boolean restore(String documentId) {
        return trashManager.restore(documentId);
    }

    public void deleteForever(String documentId) {
        trashManager.deleteForever(documentId);
    }

    // ------------------------------------------------------------------
    // Duplicate
    // ------------------------------------------------------------------

    /**
     * Deep-copies a document into a new container + metadata rows. Document and
     * page ids are regenerated (page ids are primary keys in the pages table);
     * stroke ids are kept since they are container-scoped.
     */
    public NoteDocument duplicateDocument(String documentId) throws IOException {
        NoteDocument source = reader.read(fileFor(documentId));
        Map<String, byte[]> extras = reader.readExtras(fileFor(documentId));
        long now = System.currentTimeMillis();
        source.id = com.leslie.colorosnote.core.common.IdGenerator.newId();
        source.title = source.title.isEmpty() ? "Copy" : source.title + " copy";
        source.createdAt = now;
        source.updatedAt = now;
        int index = 0;
        for (Page page : source.pages) {
            page.id = com.leslie.colorosnote.core.common.IdGenerator.newId();
            page.index = index++;
            page.createdAt = now;
            page.updatedAt = now;
        }
        writer.write(fileFor(source.id), source, extras);
        upsertMetadata(source, now);
        return source;
    }

    // ------------------------------------------------------------------
    // Extra entries (resources/, audio/, thumbnails/, ...)
    // ------------------------------------------------------------------

    /**
     * Stages raw bytes for a container extra entry (e.g.
     * {@code InknoteContainer.resourcePath(resourceId)} for an inserted
     * image). The bytes are merged into the .inknote container on the next
     * {@link #saveDocument}; until then they live in memory only, so a crash
     * before the next full save loses them — the same window the debounced
     * autosave already has for document edits. Staged bytes survive failed
     * saves and are retried on the next one.
     */
    public void putResource(String documentId, String entryPath, byte[] data) {
        if (!InknoteReader.isExtraPath(entryPath)) {
            throw new IllegalArgumentException(
                    "Not an extra entry path (resources//audio//thumbnails//...): " + entryPath);
        }
        synchronized (extrasLock) {
            pendingExtras.computeIfAbsent(documentId, k -> new LinkedHashMap<>())
                    .put(entryPath, data);
        }
    }

    /**
     * Reads one extra entry: staged-but-unsaved bytes win over the on-disk
     * container; null when the entry (or the container) does not exist.
     */
    public byte[] readResource(String documentId, String entryPath) throws IOException {
        synchronized (extrasLock) {
            Map<String, byte[]> pending = pendingExtras.get(documentId);
            if (pending != null) {
                byte[] staged = pending.get(entryPath);
                if (staged != null) {
                    return staged;
                }
            }
        }
        File file = fileFor(documentId);
        return file.exists() ? reader.readEntry(file, entryPath) : null;
    }

    /**
     * All extra entries of a document (on-disk container merged with staged
     * bytes), keyed by zip path. Empty map when the container does not exist.
     */
    public Map<String, byte[]> readExtras(String documentId) throws IOException {
        synchronized (extrasLock) {
            Map<String, byte[]> extras = reader.readExtras(fileFor(documentId));
            Map<String, byte[]> pending = pendingExtras.get(documentId);
            if (pending != null) {
                extras.putAll(pending);
            }
            return extras;
        }
    }

    // ------------------------------------------------------------------
    // Async convenience (IO on AppExecutors.io(), never the main thread)
    // ------------------------------------------------------------------

    public void openDocumentAsync(String documentId, Callback<NoteDocument> callback) {
        AppExecutors.get().io().execute(() ->
                deliver(callback, () -> openDocument(documentId)));
    }

    public void saveDocumentAsync(NoteDocument document, Callback<NoteDocument> callback) {
        AppExecutors.get().io().execute(() ->
                deliver(callback, () -> {
                    saveDocument(document);
                    return document;
                }));
    }

    public void createDocumentAsync(String title, String folderId, Callback<NoteDocument> callback) {
        AppExecutors.get().io().execute(() ->
                deliver(callback, () -> createDocument(title, folderId)));
    }

    public void putResourceAsync(String documentId, String entryPath, byte[] data,
                                 Callback<Void> callback) {
        AppExecutors.get().io().execute(() ->
                deliver(callback, () -> {
                    putResource(documentId, entryPath, data);
                    return null;
                }));
    }

    public void readResourceAsync(String documentId, String entryPath, Callback<byte[]> callback) {
        AppExecutors.get().io().execute(() ->
                deliver(callback, () -> readResource(documentId, entryPath)));
    }

    // ------------------------------------------------------------------

    private interface IoCall<T> {
        T run() throws IOException;
    }

    private static <T> void deliver(Callback<T> callback, IoCall<T> call) {
        try {
            callback.onResult(Result.success(call.run()));
        } catch (IOException | RuntimeException e) {
            callback.onResult(Result.failure(e));
        }
    }

    /** Mirrors the in-memory document into the Room metadata tables. */
    private void upsertMetadata(NoteDocument document, long now) {
        if (db == null) {
            return; // container-only mode (unit tests)
        }
        DocumentEntity entity = new DocumentEntity(document.id);
        entity.title = document.title;
        entity.docType = document.docType != null ? document.docType.name()
                : NoteDocument.DocType.NOTE.name();
        entity.folderId = document.folderId;
        entity.fileName = fileFor(document.id).getName();
        entity.pageCount = document.pages.size();
        entity.schemaVersion = InknoteContainer.SCHEMA_VERSION;
        entity.createdAt = document.createdAt;
        entity.updatedAt = now;
        db.documentDao().upsert(entity);

        List<PageEntity> pages = new ArrayList<>(document.pages.size());
        List<String> pageIds = new ArrayList<>(document.pages.size());
        for (int i = 0; i < document.pages.size(); i++) {
            Page page = document.pages.get(i);
            page.index = i;
            PageEntity pe = new PageEntity(page.id, document.id);
            pe.pageIndex = i;
            pe.title = page.title;
            pe.bookmarked = page.bookmarked;
            pe.width = page.width;
            pe.height = page.height;
            pe.createdAt = page.createdAt;
            pe.updatedAt = now;
            pages.add(pe);
            pageIds.add(page.id);
        }
        db.pageDao().upsertAll(pages);
        if (pageIds.isEmpty()) {
            db.pageDao().deleteForDocument(document.id);
        } else {
            db.pageDao().deleteNotIn(document.id, pageIds);
        }
    }
}
