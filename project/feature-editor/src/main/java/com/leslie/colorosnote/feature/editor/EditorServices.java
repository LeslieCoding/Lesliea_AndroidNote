package com.leslie.colorosnote.feature.editor;

import android.content.Context;

import com.leslie.colorosnote.core.render.ThumbnailRenderer;
import com.leslie.colorosnote.core.storage.db.InkNoteDatabase;
import com.leslie.colorosnote.core.storage.journal.JournalStore;
import com.leslie.colorosnote.core.storage.repository.DocumentRepository;
import com.leslie.colorosnote.feature.search.SearchIndexScheduler;
import com.leslie.colorosnote.feature.settings.SettingsStore;

import java.io.File;

/**
 * Process-wide services for the editor feature. Manual DI so the editor never
 * constructs heavy singletons per fragment: one Room database, one repository,
 * one thumbnail cache. Documents live in filesDir/documents (same convention
 * as the app's workers).
 */
public final class EditorServices {

    private static volatile EditorServices instance;

    private final Context appContext;
    private final File documentsDir;
    private final SettingsStore settingsStore;
    private final ThumbnailRenderer thumbnailRenderer;
    // Real engine: Vosk offline ASR (model downloaded on demand, filesDir/models).
    private final SpeechTranscriptionGateway speechTranscription;
    private volatile InkNoteDatabase db;
    private volatile DocumentRepository repository;
    private volatile SearchIndexScheduler searchIndexer;

    private EditorServices(Context context) {
        appContext = context.getApplicationContext();
        documentsDir = new File(appContext.getFilesDir(), "documents");
        settingsStore = new SettingsStore(appContext);
        thumbnailRenderer = new ThumbnailRenderer();
        speechTranscription = new VoskSpeechTranscriptionGateway(appContext);
    }

    public static EditorServices get(Context context) {
        if (instance == null) {
            synchronized (EditorServices.class) {
                if (instance == null) {
                    instance = new EditorServices(context);
                }
            }
        }
        return instance;
    }

    public DocumentRepository repository() {
        if (repository == null) {
            synchronized (this) {
                if (repository == null) {
                    if (db == null) {
                        db = InkNoteDatabase.build(appContext);
                    }
                    repository = new DocumentRepository(db, documentsDir);
                }
            }
        }
        return repository;
    }

    public JournalStore journalFor(String documentId) {
        return repository().journalFor(documentId);
    }

    public SettingsStore settings() {
        return settingsStore;
    }

    public ThumbnailRenderer thumbnails() {
        return thumbnailRenderer;
    }

    /** Lazily built incremental search indexer (spec 22/35). */
    public SearchIndexScheduler searchIndexer() {
        if (searchIndexer == null) {
            synchronized (this) {
                if (searchIndexer == null) {
                    repository(); // ensures db exists
                    searchIndexer = new SearchIndexScheduler(db.indexDao());
                }
            }
        }
        return searchIndexer;
    }

    /** Shared Room database (read path: in-editor search queries the FTS index). */
    public InkNoteDatabase database() {
        repository(); // ensures db exists
        return db;
    }

    public File documentsDir() {
        return documentsDir;
    }

    /** Directory for the recording tool's rolling segment files. */
    public File audioDirFor(String documentId) {
        return new File(new File(appContext.getFilesDir(), "audio"), documentId);
    }

    /** Speech-to-text gateway for recordings (Vosk offline engine). */
    public SpeechTranscriptionGateway speechTranscription() {
        return speechTranscription;
    }
}
