package com.leslie.colorosnote;

import android.content.Context;

import com.leslie.colorosnote.core.storage.db.InkNoteDatabase;
import com.leslie.colorosnote.core.storage.repository.DocumentRepository;

import java.io.File;

/**
 * App-level singletons: the metadata database and the document repository.
 * Created lazily on first access so Application.onCreate stays cheap.
 */
public final class AppServices {

    private static volatile InkNoteDatabase database;
    private static volatile DocumentRepository repository;

    private AppServices() {
    }

    public static InkNoteDatabase database(Context context) {
        if (database == null) {
            synchronized (AppServices.class) {
                if (database == null) {
                    database = InkNoteDatabase.build(context.getApplicationContext());
                }
            }
        }
        return database;
    }

    public static DocumentRepository repository(Context context) {
        if (repository == null) {
            synchronized (AppServices.class) {
                if (repository == null) {
                    Context app = context.getApplicationContext();
                    File documentsDir = new File(app.getFilesDir(), "documents");
                    //noinspection ResultOfMethodCallIgnored
                    documentsDir.mkdirs();
                    repository = new DocumentRepository(database(app), documentsDir);
                }
            }
        }
        return repository;
    }
}
