package com.leslie.colorosnote.core.storage.db;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.leslie.colorosnote.core.storage.db.dao.DocumentDao;
import com.leslie.colorosnote.core.storage.db.dao.FolderDao;
import com.leslie.colorosnote.core.storage.db.dao.IndexDao;
import com.leslie.colorosnote.core.storage.db.dao.JobDao;
import com.leslie.colorosnote.core.storage.db.dao.LibraryExtrasDao;
import com.leslie.colorosnote.core.storage.db.dao.PageDao;
import com.leslie.colorosnote.core.storage.db.dao.TagDao;
import com.leslie.colorosnote.core.storage.db.dao.TrashDao;
import com.leslie.colorosnote.core.storage.db.entity.AudioRecordEntity;
import com.leslie.colorosnote.core.storage.db.entity.DocumentEntity;
import com.leslie.colorosnote.core.storage.db.entity.DocumentTagEntity;
import com.leslie.colorosnote.core.storage.db.entity.ExportJobEntity;
import com.leslie.colorosnote.core.storage.db.entity.FavoriteEntity;
import com.leslie.colorosnote.core.storage.db.entity.FolderEntity;
import com.leslie.colorosnote.core.storage.db.entity.HandwritingIndexEntity;
import com.leslie.colorosnote.core.storage.db.entity.ImportJobEntity;
import com.leslie.colorosnote.core.storage.db.entity.OcrBlockEntity;
import com.leslie.colorosnote.core.storage.db.entity.PageEntity;
import com.leslie.colorosnote.core.storage.db.entity.RecentDocumentEntity;
import com.leslie.colorosnote.core.storage.db.entity.ResourceEntity;
import com.leslie.colorosnote.core.storage.db.entity.SearchIndexEntity;
import com.leslie.colorosnote.core.storage.db.entity.TagEntity;
import com.leslie.colorosnote.core.storage.db.entity.TranscriptEntity;
import com.leslie.colorosnote.core.storage.db.entity.TrashEntity;

/**
 * Room metadata database (requirement 34). Stores only indexable metadata;
 * heavy payloads stay inside the .inknote container files.
 *
 * <ul>
 *   <li>exportSchema = true; schemas land in core-storage/schemas (build.gradle).</li>
 *   <li>WAL journal mode for concurrent reads during writes.</li>
 *   <li>Foreign keys enforced per connection in {@code onOpen}.</li>
 *   <li>Destructive migration is forbidden; see {@link Migrations}.</li>
 * </ul>
 */
@Database(
        entities = {
                DocumentEntity.class,
                FolderEntity.class,
                PageEntity.class,
                TagEntity.class,
                DocumentTagEntity.class,
                ResourceEntity.class,
                AudioRecordEntity.class,
                TranscriptEntity.class,
                OcrBlockEntity.class,
                HandwritingIndexEntity.class,
                SearchIndexEntity.class,
                TrashEntity.class,
                RecentDocumentEntity.class,
                FavoriteEntity.class,
                ImportJobEntity.class,
                ExportJobEntity.class
        },
        version = InkNoteDatabase.VERSION,
        exportSchema = true)
public abstract class InkNoteDatabase extends RoomDatabase {

    public static final int VERSION = 1;
    public static final String NAME = "inknote.db";

    public abstract DocumentDao documentDao();

    public abstract FolderDao folderDao();

    public abstract PageDao pageDao();

    public abstract TagDao tagDao();

    public abstract TrashDao trashDao();

    public abstract LibraryExtrasDao libraryExtrasDao();

    public abstract JobDao jobDao();

    public abstract IndexDao indexDao();

    /**
     * Builds the production database. WAL + foreign keys; never destructive.
     */
    public static InkNoteDatabase build(Context context) {
        return Room.databaseBuilder(context.getApplicationContext(), InkNoteDatabase.class, NAME)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(Migrations.ALL)
                .addCallback(new Callback() {
                    @Override
                    public void onOpen(@NonNull SupportSQLiteDatabase db) {
                        super.onOpen(db);
                        // Room does not enable FK enforcement by itself.
                        if (!db.isReadOnly()) {
                            db.setForeignKeyConstraintsEnabled(true);
                        }
                    }
                })
                .build();
    }
}
