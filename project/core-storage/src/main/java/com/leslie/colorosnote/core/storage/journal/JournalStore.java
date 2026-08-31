package com.leslie.colorosnote.core.storage.journal;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.leslie.colorosnote.core.model.NoteDocument;
import com.leslie.colorosnote.core.model.Page;
import com.leslie.colorosnote.core.model.PageObject;
import com.leslie.colorosnote.core.model.Stroke;
import com.leslie.colorosnote.core.storage.container.InknoteGson;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Incremental change journal for crash recovery (requirement 10).
 *
 * <p>Every completed stroke / finished object edit is appended to a per-document
 * JSON-lines journal file ({@code <docId>.journal} next to the .inknote) and
 * fsynced immediately, so a crash loses at most the in-flight stroke (< 2 s of
 * work). Removals (undo / erase / cut) append a {@code stroke_delete} tombstone
 * instead of rewriting the file, so an undone stroke can never be resurrected
 * by a replay. After a successful full save the journal is
 * {@linkplain #compact() compacted} (deleted). On open,
 * {@link #recover(NoteDocument)} replays the journal onto the loaded document,
 * deduplicating by stroke/object id so a replay never produces duplicate
 * strokes.
 *
 * <p>Thread-safe, also across instances pointing at the same file (the lock is
 * shared); all methods perform blocking IO and must be called off the
 * main thread — use the {@code *Async} variants which hop to an
 * {@link Executor} (AppExecutors.io() in production).
 *
 * <p>Pure JVM (java.io + gson + model) so replay/dedup is unit-testable.
 */
public final class JournalStore {

    public static final String JOURNAL_SUFFIX = ".journal";

    private static final String OP_STROKE = "stroke";
    private static final String OP_STROKE_DELETE = "stroke_delete";
    private static final String OP_OBJECT = "object";

    /**
     * Shared across all instances: callers obtain a fresh {@code JournalStore}
     * per operation ({@code DocumentRepository.journalFor}), so a per-instance
     * lock would not serialize concurrent appends to the same file.
     */
    private static final Object LOCK = new Object();

    private final File journalFile;
    private final Gson gson = InknoteGson.get();

    public JournalStore(File journalFile) {
        this.journalFile = journalFile;
    }

    /** Journal file for a document stored as {@code documentsDir/<docId>.inknote}. */
    public static File journalFileFor(File documentsDir, String documentId) {
        return new File(documentsDir, documentId + JOURNAL_SUFFIX);
    }

    public File journalFile() {
        return journalFile;
    }

    /** True when there are unreplayed/uncompacted changes on disk. */
    public boolean hasPending() {
        return journalFile.exists() && journalFile.length() > 0L;
    }

    // ------------------------------------------------------------------
    // Appends (called from ink/edit pipeline on stroke end / object edit end)
    // ------------------------------------------------------------------

    /** Appends a finished stroke and fsyncs, so it survives a process kill. */
    public void appendStroke(String pageId, Stroke stroke) throws IOException {
        append(OP_STROKE, pageId, gson.toJsonTree(stroke).getAsJsonObject());
    }

    /** Appends a finished object edit (insert/move/scale/rotate) and fsyncs. */
    public void appendObject(String pageId, PageObject object) throws IOException {
        append(OP_OBJECT, pageId, gson.toJsonTree(object).getAsJsonObject());
    }

    /**
     * Appends a tombstone for a removed stroke (undo / erase / cut) and fsyncs.
     * Replay deletes by id; the tombstone is a no-op when the stroke is absent
     * (e.g. it was never journaled because it came from the last full save).
     */
    public void appendStrokeDelete(String pageId, String strokeId) throws IOException {
        JsonObject payload = new JsonObject();
        payload.addProperty("id", strokeId);
        append(OP_STROKE_DELETE, pageId, payload);
    }

    public void appendStrokeAsync(Executor executor, String pageId, Stroke stroke) {
        executor.execute(() -> {
            try {
                appendStroke(pageId, stroke);
            } catch (IOException ignored) {
                // Best effort: the next stroke/compact still keeps the journal usable.
            }
        });
    }

    public void appendStrokeDeleteAsync(Executor executor, String pageId, String strokeId) {
        executor.execute(() -> {
            try {
                appendStrokeDelete(pageId, strokeId);
            } catch (IOException ignored) {
                // Best effort, see above.
            }
        });
    }

    public void appendObjectAsync(Executor executor, String pageId, PageObject object) {
        executor.execute(() -> {
            try {
                appendObject(pageId, object);
            } catch (IOException ignored) {
                // Best effort, see above.
            }
        });
    }

    // ------------------------------------------------------------------
    // Recovery
    // ------------------------------------------------------------------

    /**
     * Replays pending journal entries onto the given document. Deduplicates by
     * stroke/object id (last write wins) so replaying after a crash between
     * journal-write and compact never duplicates strokes.
     *
     * <p>Tolerates a corrupt trailing line (crash mid-append): replay stops at
     * the first unparseable line and keeps everything before it.
     *
     * @return number of entries applied
     */
    public int recover(NoteDocument document) {
        synchronized (LOCK) {
            if (!hasPending()) {
                return 0;
            }
            int applied = 0;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(journalFile), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        continue;
                    }
                    JsonObject entry;
                    try {
                        entry = JsonParser.parseString(line).getAsJsonObject();
                    } catch (JsonParseException | IllegalStateException e) {
                        break; // corrupt tail from a crash mid-append; keep the rest
                    }
                    if (apply(document, entry)) {
                        applied++;
                    }
                }
            } catch (IOException e) {
                return applied; // unreadable journal must not block opening
            }
            return applied;
        }
    }

    private boolean apply(NoteDocument document, JsonObject entry) {
        JsonElementHelper h = new JsonElementHelper(entry);
        String op = h.string("op");
        String pageId = h.string("pageId");
        if (op == null || pageId == null) {
            return false;
        }
        Page page = findPage(document, pageId);
        if (page == null) {
            return false; // page deleted after the change was journaled
        }
        switch (op) {
            case OP_STROKE: {
                Stroke stroke = gson.fromJson(entry.get("payload"), Stroke.class);
                if (stroke == null || stroke.id == null) {
                    return false;
                }
                upsertStroke(page, stroke);
                return true;
            }
            case OP_STROKE_DELETE: {
                if (!entry.has("payload") || !entry.get("payload").isJsonObject()) {
                    return false;
                }
                String strokeId =
                        new JsonElementHelper(entry.getAsJsonObject("payload")).string("id");
                if (strokeId == null) {
                    return false;
                }
                return removeStroke(page, strokeId);
            }
            case OP_OBJECT: {
                PageObject object = gson.fromJson(entry.get("payload"), PageObject.class);
                if (object == null || object.id == null) {
                    return false; // unknown object type from a newer version
                }
                upsertObject(page, object);
                return true;
            }
            default:
                return false; // forward compat: ignore unknown ops
        }
    }

    // ------------------------------------------------------------------
    // Dedup (pure functions, unit-tested directly)
    // ------------------------------------------------------------------

    /**
     * Inserts or replaces a stroke by id. Replace keeps the original position
     * so z-order survives a replayed edit.
     */
    public static void upsertStroke(Page page, Stroke stroke) {
        List<Stroke> strokes = page.strokes;
        for (int i = 0; i < strokes.size(); i++) {
            if (stroke.id.equals(strokes.get(i).id)) {
                strokes.set(i, stroke);
                return;
            }
        }
        strokes.add(stroke);
    }

    /** Inserts or replaces a page object by id; same semantics as strokes. */
    public static void upsertObject(Page page, PageObject object) {
        List<PageObject> objects = page.objects;
        for (int i = 0; i < objects.size(); i++) {
            if (object.id.equals(objects.get(i).id)) {
                objects.set(i, object);
                return;
            }
        }
        objects.add(object);
    }

    /** Removes a stroke by id (tombstone replay); true when something changed. */
    public static boolean removeStroke(Page page, String strokeId) {
        List<Stroke> strokes = page.strokes;
        for (int i = 0; i < strokes.size(); i++) {
            if (strokeId.equals(strokes.get(i).id)) {
                strokes.remove(i);
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Compaction
    // ------------------------------------------------------------------

    /**
     * Deletes the journal after its entries were merged into the main document
     * by a successful full save. No-op when no journal exists.
     */
    public void compact() {
        synchronized (LOCK) {
            if (journalFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                journalFile.delete();
            }
        }
    }

    // ------------------------------------------------------------------

    private void append(String op, String pageId, JsonObject payload) throws IOException {
        JsonObject entry = new JsonObject();
        entry.addProperty("op", op);
        entry.addProperty("pageId", pageId);
        entry.add("payload", payload);
        entry.addProperty("ts", System.currentTimeMillis());
        synchronized (LOCK) {
            File parent = journalFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("Cannot create journal directory: " + parent);
            }
            try (FileOutputStream fos = new FileOutputStream(journalFile, true);
                 Writer writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                writer.write(gson.toJson(entry));
                writer.write('\n');
                writer.flush();
                fos.getFD().sync(); // durability: survive process kill / power loss
            }
        }
    }

    private static Page findPage(NoteDocument document, String pageId) {
        for (Page page : document.pages) {
            if (pageId.equals(page.id)) {
                return page;
            }
        }
        return null;
    }

    /** Tiny null-safe accessor to keep {@link #apply} readable. */
    private static final class JsonElementHelper {
        private final JsonObject object;

        JsonElementHelper(JsonObject object) {
            this.object = object;
        }

        String string(String field) {
            return object.has(field) && object.get(field).isJsonPrimitive()
                    ? object.get(field).getAsString() : null;
        }
    }
}
