package com.leslie.colorosnote.core.storage.container;

import com.google.gson.JsonObject;

/**
 * Migration hook between container schema versions (requirement 9): when a
 * document written by an older app version is opened, registered migrators
 * upgrade the raw document.json tree step by step (N -> N+1) until it reaches
 * the current schema version.
 */
public interface DocumentMigrator {

    /** The schema version this migrator upgrades FROM (target is from+1). */
    int fromVersion();

    /**
     * Migrates the parsed document.json object. Unknown fields must be left
     * untouched so no data is lost on the round trip.
     */
    JsonObject migrate(JsonObject documentJson);
}
