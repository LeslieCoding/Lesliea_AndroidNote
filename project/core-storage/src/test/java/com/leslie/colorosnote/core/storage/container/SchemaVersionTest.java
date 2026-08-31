package com.leslie.colorosnote.core.storage.container;

import com.google.gson.JsonObject;
import com.leslie.colorosnote.core.model.InknoteContainer;
import com.leslie.colorosnote.core.model.NoteDocument;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** schemaVersion checks and the DocumentMigrator chain of {@link InknoteReader}. */
public class SchemaVersionTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private File containerWith(int schemaVersion, String documentJson) throws IOException {
        File file = tmp.newFile("v" + schemaVersion + ".inknote");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            zip.putNextEntry(new ZipEntry(InknoteContainer.MANIFEST));
            zip.write(("{\"" + InknoteContainer.MANIFEST_FIELD_SCHEMA_VERSION + "\":" + schemaVersion + "}")
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(InknoteContainer.DOCUMENT));
            zip.write(documentJson.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return file;
    }

    @Test
    public void newerSchemaIsRejected() throws Exception {
        File file = containerWith(InknoteContainer.SCHEMA_VERSION + 1, "{\"id\":\"d1\"}");
        try {
            new InknoteReader().read(file);
            fail("newer schema version must be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Unsupported"));
        }
    }

    @Test
    public void missingManifestIsRejected() throws Exception {
        File file = tmp.newFile("broken.inknote");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("whatever.txt"));
            zip.write(1);
            zip.closeEntry();
        }
        try {
            new InknoteReader().read(file);
            fail("missing manifest must be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("manifest"));
        }
    }

    @Test
    public void olderSchemaRunsMigratorChain() throws Exception {
        // A "v0" container without pageIds; the migrator stamps a marker field.
        AtomicBoolean migratorCalled = new AtomicBoolean(false);
        DocumentMigrator v0 = new DocumentMigrator() {
            @Override
            public int fromVersion() {
                return 0;
            }

            @Override
            public JsonObject migrate(JsonObject documentJson) {
                migratorCalled.set(true);
                documentJson.addProperty("migrated", true);
                return documentJson;
            }
        };
        File file = containerWith(0, "{\"id\":\"d0\",\"title\":\"legacy\"}");
        NoteDocument doc = new InknoteReader(Collections.singletonList(v0)).read(file);
        assertTrue(migratorCalled.get());
        assertEquals("d0", doc.id);
        assertEquals("legacy", doc.title);
        assertEquals(InknoteContainer.SCHEMA_VERSION, doc.schemaVersion);
    }

    @Test
    public void missingMigrationPathIsRejected() throws Exception {
        File file = containerWith(0, "{\"id\":\"d0\"}");
        try {
            new InknoteReader(Collections.emptyList()).read(file);
            fail("gap in the migrator chain must be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("migration path"));
        }
    }

    @Test
    public void readSchemaVersionMatchesManifest() throws Exception {
        File file = containerWith(1, "{\"id\":\"d1\"}");
        assertEquals(1, new InknoteReader().readSchemaVersion(file));
    }
}
