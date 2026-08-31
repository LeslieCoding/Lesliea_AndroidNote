package com.leslie.colorosnote.feature.handwriting;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns grouped handwriting recognition results into search index entries
 * (spec 15: background grouping -> recognize -> index -> jump-to-region).
 *
 * <p>Pure JVM except Gson; unit-tested off-device. Persistence itself belongs
 * to core-search / core-storage - this class only builds and (de)serializes
 * entries.
 */
public final class HandwritingSearchIndexer {

    /** Best-candidate score below this marks the entry low-confidence. */
    public static final float LOW_SCORE_THRESHOLD = 0.5f;

    private static final Gson GSON = new Gson();
    private static final Type ENTRY_LIST_TYPE =
            new TypeToken<List<HandwritingIndexEntry>>() { }.getType();

    /** A stroke group plus the candidates its recognition produced. */
    public static final class RecognizedGroup {
        public final InkGroup group;
        public final List<Candidate> candidates; // best first; may be empty

        public RecognizedGroup(InkGroup group, List<Candidate> candidates) {
            this.group = group;
            this.candidates = candidates;
        }
    }

    /**
     * Builds one entry per group from its best candidate. Groups without any
     * non-blank candidate are skipped (never index fabricated text).
     */
    public List<HandwritingIndexEntry> buildEntries(String documentId, String pageId,
                                                    List<RecognizedGroup> groups) {
        List<HandwritingIndexEntry> entries = new ArrayList<>();
        if (groups == null) {
            return entries;
        }
        for (RecognizedGroup rg : groups) {
            if (rg == null || rg.group == null || rg.candidates == null || rg.candidates.isEmpty()) {
                continue;
            }
            Candidate best = rg.candidates.get(0);
            if (best == null || best.text == null || best.text.trim().isEmpty()) {
                continue;
            }
            boolean lowConfidence = best.score != null && best.score < LOW_SCORE_THRESHOLD;
            entries.add(new HandwritingIndexEntry(documentId, pageId, best.text.trim(),
                    rg.group.region.toArray(), rg.group.strokeIds,
                    rg.group.startTime, lowConfidence));
        }
        return entries;
    }

    public String entriesToJson(List<HandwritingIndexEntry> entries) {
        return GSON.toJson(entries);
    }

    public List<HandwritingIndexEntry> entriesFromJson(String json) {
        List<HandwritingIndexEntry> entries = GSON.fromJson(json, ENTRY_LIST_TYPE);
        return entries == null ? new ArrayList<>() : entries;
    }
}
