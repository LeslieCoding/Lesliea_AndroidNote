package com.leslie.colorosnote.core.storage.db;

import androidx.annotation.NonNull;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

/**
 * Migration policy (requirement 34):
 *
 * <ul>
 *   <li>Destructive migration (fallbackToDestructiveMigration) is FORBIDDEN in
 *       production; every schema change ships an explicit {@link Migration}.</li>
 *   <li>On any schema change: bump {@link InkNoteDatabase#VERSION}, add a
 *       Migration constant here, register it in {@link #ALL}, and add a
 *       MigrationTestHelper instrumentation test that upgrades a real v(N-1)
 *       database file to vN.</li>
 *   <li>Room-exported schemas (core-storage/schemas/) are the diff baseline.</li>
 * </ul>
 *
 * <p>Version 1 is the initial schema, so {@link #ALL} is empty; the
 * {@link #MIGRATION_1_2} constant below is a ready-to-adapt template.
 */
public final class Migrations {

    private Migrations() {
    }

    /**
     * Template for the first real migration (example: per-document pinned flag).
     * Not registered until the database version is bumped to 2.
     */
    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            // Example shape only — replace with the real v2 delta when needed:
            // db.execSQL("ALTER TABLE documents ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0");
            throw new UnsupportedOperationException(
                    "Template only; implement the real v1->v2 delta before registering");
        }
    };

    /** All registered migrations, in order. Empty while the schema is v1. */
    public static final Migration[] ALL = new Migration[0];
}
