package com.leslie.colorosnote.core.storage.container;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.leslie.colorosnote.core.common.IoUtils;
import com.leslie.colorosnote.core.model.InknoteContainer;
import com.leslie.colorosnote.core.model.NoteDocument;
import com.leslie.colorosnote.core.model.Page;
import com.leslie.colorosnote.core.model.PageObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads .inknote containers written by {@link InknoteWriter} (requirement 9).
 *
 * <ul>
 *   <li>Checks manifest schemaVersion: newer-than-supported files are
 *       rejected; older files are upgraded through the registered
 *       {@link DocumentMigrator} chain.</li>
 *   <li>Lenient parsing: unknown JSON fields are ignored by Gson, unknown
 *       object types deserialize to null and are filtered out, so a document
 *       from a newer app version degrades instead of crashing.</li>
 * </ul>
 *
 * Pure JVM; container files are trusted (import of foreign .inknote files is
 * validated by core-security before they reach this reader).
 */
public final class InknoteReader {

    private final Gson gson = InknoteGson.get();
    private final List<DocumentMigrator> migrators;

    public InknoteReader() {
        this(Collections.singletonList(new DirectMigratorV1()));
    }

    public InknoteReader(List<DocumentMigrator> migrators) {
        this.migrators = migrators != null ? migrators : Collections.emptyList();
    }

    /** Reads and fully reconstructs the document, applying migrations. */
    public NoteDocument read(File file) throws IOException {
        Map<String, byte[]> entries = readEntries(file);

        byte[] manifestBytes = entries.get(InknoteContainer.MANIFEST);
        if (manifestBytes == null) {
            throw new IOException("Not an .inknote container (no manifest.json): " + file.getName());
        }
        JsonObject manifest = JsonParser.parseString(
                new String(manifestBytes, StandardCharsets.UTF_8)).getAsJsonObject();
        int schemaVersion = manifest.has(InknoteContainer.MANIFEST_FIELD_SCHEMA_VERSION)
                ? manifest.get(InknoteContainer.MANIFEST_FIELD_SCHEMA_VERSION).getAsInt() : 1;
        if (schemaVersion > InknoteContainer.SCHEMA_VERSION) {
            throw new IOException("Unsupported .inknote schema version " + schemaVersion
                    + " (this build supports up to " + InknoteContainer.SCHEMA_VERSION + ")");
        }

        byte[] docBytes = entries.get(InknoteContainer.DOCUMENT);
        if (docBytes == null) {
            throw new IOException("Corrupt .inknote container (no document.json): " + file.getName());
        }
        JsonObject docJson = JsonParser.parseString(
                new String(docBytes, StandardCharsets.UTF_8)).getAsJsonObject();
        docJson = migrate(docJson, schemaVersion);

        List<String> pageIds = readPageIds(docJson, entries);
        docJson.remove("pageIds");

        NoteDocument document = gson.fromJson(docJson, NoteDocument.class);
        if (document == null) {
            throw new IOException("Corrupt document.json in " + file.getName());
        }
        document.schemaVersion = InknoteContainer.SCHEMA_VERSION;
        document.pages = new ArrayList<>(pageIds.size());
        for (String pageId : pageIds) {
            Page page = readPage(entries, pageId);
            if (page != null) {
                document.pages.add(page);
            }
        }
        return document;
    }

    /** Reads only the manifest schemaVersion without parsing the document. */
    public int readSchemaVersion(File file) throws IOException {
        Map<String, byte[]> entries = readEntries(file);
        byte[] manifestBytes = entries.get(InknoteContainer.MANIFEST);
        if (manifestBytes == null) {
            throw new IOException("Not an .inknote container (no manifest.json): " + file.getName());
        }
        JsonObject manifest = JsonParser.parseString(
                new String(manifestBytes, StandardCharsets.UTF_8)).getAsJsonObject();
        return manifest.has(InknoteContainer.MANIFEST_FIELD_SCHEMA_VERSION)
                ? manifest.get(InknoteContainer.MANIFEST_FIELD_SCHEMA_VERSION).getAsInt() : 1;
    }

    /**
     * Reads every loose extra entry (resources/, audio/, thumbnails/, ocr/,
     * search/, attachments/) keyed by zip path. Returns an empty map when the
     * file does not exist, so a full rewrite can merge extras unconditionally.
     */
    public Map<String, byte[]> readExtras(File file) throws IOException {
        Map<String, byte[]> extras = new LinkedHashMap<>();
        if (!file.exists()) {
            return extras;
        }
        for (Map.Entry<String, byte[]> e : readEntries(file).entrySet()) {
            if (isExtraPath(e.getKey())) {
                extras.put(e.getKey(), e.getValue());
            }
        }
        return extras;
    }

    /** True for loose payload entries that must survive a full document rewrite. */
    public static boolean isExtraPath(String path) {
        return path.startsWith(InknoteContainer.DIR_RESOURCES)
                || path.startsWith(InknoteContainer.DIR_AUDIO)
                || path.startsWith(InknoteContainer.DIR_THUMBNAILS)
                || path.startsWith(InknoteContainer.DIR_OCR)
                || path.startsWith(InknoteContainer.DIR_SEARCH)
                || path.startsWith(InknoteContainer.DIR_ATTACHMENTS);
    }

    /** Reads a raw extra entry (resource/audio/thumbnail) by zip path; null if absent. */
    public byte[] readEntry(File file, String path) throws IOException {
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(file))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.getName().equals(path)) {
                    return IoUtils.readAll(zin);
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------

    /** Applies the migrator chain from {@code fromVersion} to the current schema. */
    private JsonObject migrate(JsonObject docJson, int fromVersion) throws IOException {
        int version = fromVersion;
        JsonObject current = docJson;
        while (version < InknoteContainer.SCHEMA_VERSION) {
            DocumentMigrator step = null;
            for (DocumentMigrator m : migrators) {
                if (m.fromVersion() == version) {
                    step = m;
                    break;
                }
            }
            if (step == null) {
                throw new IOException("No migration path from schema v" + version
                        + " to v" + (version + 1));
            }
            current = step.migrate(current);
            version++;
        }
        return current;
    }

    /** Ordered page ids from document.json; falls back to scanning pages/ entries. */
    private static List<String> readPageIds(JsonObject docJson, Map<String, byte[]> entries) {
        List<String> pageIds = new ArrayList<>();
        JsonElement pageIdsElement = docJson.get("pageIds");
        if (pageIdsElement != null && pageIdsElement.isJsonArray()) {
            JsonArray array = pageIdsElement.getAsJsonArray();
            for (JsonElement id : array) {
                pageIds.add(id.getAsString());
            }
            return pageIds;
        }
        // Legacy/fallback: derive order from sorted page file names.
        Map<String, byte[]> pageEntries = new TreeMap<>();
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            if (e.getKey().startsWith(InknoteContainer.DIR_PAGES) && e.getKey().endsWith(".json")) {
                pageEntries.put(e.getKey(), e.getValue());
            }
        }
        for (String path : pageEntries.keySet()) {
            pageIds.add(path.substring(InknoteContainer.DIR_PAGES.length(), path.length() - ".json".length()));
        }
        return pageIds;
    }

    private Page readPage(Map<String, byte[]> entries, String pageId) throws IOException {
        byte[] pageBytes = entries.get(InknoteContainer.pagePath(pageId));
        if (pageBytes == null) {
            return null; // dangling page id; tolerate
        }
        Page page = gson.fromJson(new String(pageBytes, StandardCharsets.UTF_8), Page.class);
        if (page == null) {
            return null;
        }
        // Filter nulls produced by unknown PageObject subtypes (forward compat).
        if (page.objects != null) {
            List<PageObject> filtered = new ArrayList<>(page.objects.size());
            for (PageObject object : page.objects) {
                if (object != null) {
                    filtered.add(object);
                }
            }
            page.objects = filtered;
        }
        byte[] strokes = entries.get(InknoteContainer.strokesPath(pageId));
        if (strokes != null) {
            page.strokes = StrokeBinCodec.decode(strokes);
        }
        return page;
    }

    private static Map<String, byte[]> readEntries(File file) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(file))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    entries.put(entry.getName(), IoUtils.readAll(zin));
                }
            }
        }
        return entries;
    }
}
