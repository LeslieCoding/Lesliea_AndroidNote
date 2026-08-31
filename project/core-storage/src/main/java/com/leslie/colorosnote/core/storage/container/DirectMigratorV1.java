package com.leslie.colorosnote.core.storage.container;

import com.google.gson.JsonObject;
import com.leslie.colorosnote.core.model.InknoteContainer;

/**
 * Passthrough migrator for schema v1 documents (requirement 9): v1 is the
 * current format, so no transformation is needed. It exists as the explicit
 * chain element proving v1 is handled, and as the template for future
 * v1 -> v2 migrators.
 */
public final class DirectMigratorV1 implements DocumentMigrator {

    @Override
    public int fromVersion() {
        return InknoteContainer.SCHEMA_VERSION; // 1
    }

    @Override
    public JsonObject migrate(JsonObject documentJson) {
        return documentJson; // already current; nothing to do
    }
}
