package com.leslie.colorosnote.core.storage.repository;

import com.leslie.colorosnote.core.model.InknoteContainer;
import com.leslie.colorosnote.core.model.NoteDocument;
import com.leslie.colorosnote.core.storage.container.InknoteReader;
import com.leslie.colorosnote.core.storage.container.InknoteWriter;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Extra-entry (resources/, audio/, ...) persistence of {@link DocumentRepository}:
 * full saves must not drop payloads already inside the container, staged
 * resources must land on the next save, and duplicates must carry extras.
 *
 * <p>Pure JVM: uses the container-only constructor (no Room metadata).
 */
public class DocumentRepositoryExtrasTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private DocumentRepository repository;

    @Before
    public void setUp() {
        repository = new DocumentRepository(tmp.getRoot());
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void savePreservesExistingExtras() throws Exception {
        // Simulate a PDF import: container written directly with extras.
        NoteDocument doc = repository.createDocument("imported", null);
        File file = repository.fileFor(doc.id);
        byte[] pdf = bytes("fake-pdf");
        byte[] audio = bytes("fake-audio");
        Map<String, byte[]> extras = new java.util.LinkedHashMap<>();
        extras.put(InknoteContainer.resourcePath("source.pdf"), pdf);
        extras.put(InknoteContainer.audioPath("seg-0"), audio);
        new InknoteWriter().write(file, doc, extras);

        // Editor-style full save without extras must not drop them.
        repository.saveDocument(doc);

        InknoteReader reader = new InknoteReader();
        assertArrayEquals(pdf, reader.readEntry(file, InknoteContainer.resourcePath("source.pdf")));
        assertArrayEquals(audio, reader.readEntry(file, InknoteContainer.audioPath("seg-0")));
        // And the document itself still round-trips.
        assertEquals(doc.id, reader.read(file).id);
    }

    @Test
    public void putResourceLandsOnNextSave() throws Exception {
        NoteDocument doc = repository.createDocument("with-image", null);
        String path = InknoteContainer.resourcePath("img-1");
        byte[] png = bytes("fake-png");

        repository.putResource(doc.id, path, png);
        // Staged bytes are readable before any save hit the disk.
        assertArrayEquals(png, repository.readResource(doc.id, path));

        repository.saveDocument(doc);

        assertArrayEquals(png,
                new InknoteReader().readEntry(repository.fileFor(doc.id), path));
        // The staged entry was consumed by the save but the bytes live in the
        // container now, so reads still resolve.
        assertArrayEquals(png, repository.readResource(doc.id, path));
    }

    @Test
    public void putResourceOverwritesExistingEntryOnSave() throws Exception {
        NoteDocument doc = repository.createDocument("replace", null);
        String path = InknoteContainer.resourcePath("img-2");
        repository.putResource(doc.id, path, bytes("v1"));
        repository.saveDocument(doc);

        repository.putResource(doc.id, path, bytes("v2"));
        repository.saveDocument(doc);

        assertArrayEquals(bytes("v2"),
                new InknoteReader().readEntry(repository.fileFor(doc.id), path));
    }

    @Test
    public void putResourceRejectsNonExtraPath() throws Exception {
        NoteDocument doc = repository.createDocument("guard", null);
        try {
            repository.putResource(doc.id, InknoteContainer.DOCUMENT, bytes("x"));
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("putResource must reject non-extra paths");
    }

    @Test
    public void duplicateCarriesExtras() throws Exception {
        NoteDocument doc = repository.createDocument("source", null);
        byte[] png = bytes("fake-png");
        repository.putResource(doc.id, InknoteContainer.resourcePath("img-3"), png);
        repository.saveDocument(doc);

        NoteDocument copy = repository.duplicateDocument(doc.id);

        assertTrue(!copy.id.equals(doc.id));
        File copyFile = repository.fileFor(copy.id);
        assertTrue(copyFile.exists());
        assertArrayEquals(png,
                new InknoteReader().readEntry(copyFile, InknoteContainer.resourcePath("img-3")));
        assertEquals(1, new InknoteReader().read(copyFile).pages.size());
    }

    @Test
    public void readExtrasMergesStagedWithOnDisk() throws Exception {
        NoteDocument doc = repository.createDocument("mixed", null);
        repository.putResource(doc.id, InknoteContainer.resourcePath("on-disk"), bytes("a"));
        repository.saveDocument(doc);
        repository.putResource(doc.id, InknoteContainer.resourcePath("staged"), bytes("b"));

        Map<String, byte[]> extras = repository.readExtras(doc.id);

        assertEquals(2, extras.size());
        assertArrayEquals(bytes("a"), extras.get(InknoteContainer.resourcePath("on-disk")));
        assertArrayEquals(bytes("b"), extras.get(InknoteContainer.resourcePath("staged")));
    }

    @Test
    public void readExtrasOfMissingDocumentIsEmpty() throws Exception {
        assertTrue(repository.readExtras("no-such-doc").isEmpty());
        assertNull(repository.readResource("no-such-doc",
                InknoteContainer.resourcePath("img-x")));
    }

    @Test
    public void saveWithoutAnyExtrasStillWorks() throws Exception {
        NoteDocument doc = repository.createDocument("plain", null);
        repository.saveDocument(doc);
        Map<String, byte[]> extras = new InknoteReader()
                .readExtras(repository.fileFor(doc.id));
        assertEquals(Collections.emptyMap(), extras);
    }
}
