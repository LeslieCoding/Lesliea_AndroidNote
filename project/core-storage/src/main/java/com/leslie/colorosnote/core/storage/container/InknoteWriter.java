package com.leslie.colorosnote.core.storage.container;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.leslie.colorosnote.core.common.IoUtils;
import com.leslie.colorosnote.core.model.InknoteContainer;
import com.leslie.colorosnote.core.model.NoteDocument;
import com.leslie.colorosnote.core.model.Page;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes versioned .inknote containers (requirement 9):
 *
 * <pre>
 * manifest.json        schemaVersion + document id/page count
 * document.json        document metadata + ordered pageIds (no page payloads)
 * pages/{id}.json      page incl. objects/background, strokes stripped
 * strokes/{id}.bin     binary stroke payload (see StrokeBinCodec)
 * (extras)             resources/, audio/, thumbnails/ raw entries
 * </pre>
 *
 * <p>The file is streamed into a temp file and atomically renamed via
 * {@link IoUtils#atomicWrite}, so a crash mid-write never corrupts the
 * previous good copy (requirement 10).
 *
 * <p>Pure JVM: only java.io/java.util.zip + gson + core-model, so the whole
 * container format is covered by local unit tests.
 */
public final class InknoteWriter {

    private final Gson gson = InknoteGson.get();

    public void write(File target, NoteDocument document) throws IOException {
        write(target, document, Collections.emptyMap());
    }

    /**
     * @param extraEntries raw zip entries keyed by full path (use
     *                     {@link InknoteContainer#resourcePath} etc.), e.g.
     *                     images, audio segments, thumbnails.
     */
    public void write(File target, NoteDocument document, Map<String, byte[]> extraEntries)
            throws IOException {
        IoUtils.atomicWrite(target, out -> writeZip(out, document, extraEntries));
    }

    /** Streams the container zip into the given output stream (NOT closed — the
     *  caller owns it and may fsync it after {@link ZipOutputStream#finish()}). */
    public void writeZip(OutputStream rawOut, NoteDocument document, Map<String, byte[]> extraEntries)
            throws IOException {
        ZipOutputStream zip = new ZipOutputStream(rawOut);
        putEntry(zip, InknoteContainer.MANIFEST,
                gson.toJson(buildManifest(document)).getBytes(StandardCharsets.UTF_8));
        putEntry(zip, InknoteContainer.DOCUMENT,
                gson.toJson(buildDocumentJson(document)).getBytes(StandardCharsets.UTF_8));
        for (Page page : document.pages) {
            JsonObject pageJson = gson.toJsonTree(page).getAsJsonObject();
            pageJson.remove("strokes"); // strokes live in the binary payload
            putEntry(zip, InknoteContainer.pagePath(page.id),
                    gson.toJson(pageJson).getBytes(StandardCharsets.UTF_8));
            if (!page.strokes.isEmpty()) {
                putEntry(zip, InknoteContainer.strokesPath(page.id),
                        StrokeBinCodec.encode(page.strokes));
            }
        }
        if (extraEntries != null) {
            for (Map.Entry<String, byte[]> e : extraEntries.entrySet()) {
                putEntry(zip, e.getKey(), e.getValue());
            }
        }
        zip.finish();
        zip.flush();
    }

    private JsonObject buildManifest(NoteDocument document) {
        JsonObject manifest = new JsonObject();
        manifest.addProperty(InknoteContainer.MANIFEST_FIELD_SCHEMA_VERSION,
                InknoteContainer.SCHEMA_VERSION);
        manifest.addProperty("documentId", document.id);
        manifest.addProperty("pageCount", document.pages.size());
        manifest.addProperty("writtenAtMs", System.currentTimeMillis());
        return manifest;
    }

    private JsonObject buildDocumentJson(NoteDocument document) {
        JsonObject docJson = gson.toJsonTree(document).getAsJsonObject();
        docJson.remove("pages");
        docJson.addProperty("schemaVersion", InknoteContainer.SCHEMA_VERSION);
        JsonArray pageIds = new JsonArray();
        for (Page page : document.pages) {
            pageIds.add(page.id);
        }
        docJson.add("pageIds", pageIds);
        return docJson;
    }

    private static void putEntry(ZipOutputStream zip, String path, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(data);
        zip.closeEntry();
    }
}
