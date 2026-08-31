package com.leslie.colorosnote.feature.handwriting;

import com.google.gson.Gson;

import java.util.List;

/**
 * One searchable handwriting unit destined for core-search (spec 15: results
 * must link back to the page and the region so tapping a result jumps there and
 * briefly highlights it). Gson-serializable; core-search adapts this into its
 * own SearchDocument.
 */
public final class HandwritingIndexEntry {

    private static final Gson GSON = new Gson();

    public String documentId;
    public String pageId;
    public String text;
    /** Hit region in page coordinates: [left, top, right, bottom]. */
    public float[] region;
    /** Strokes this text was recognized from, in writing order. */
    public List<String> strokeIds;
    /** Start time of the grouped strokes (ms). */
    public long timestamp;
    /** Low-score results rank lower in search instead of being trusted. */
    public boolean lowConfidence;

    public HandwritingIndexEntry() {
    }

    public HandwritingIndexEntry(String documentId, String pageId, String text,
                                 float[] region, List<String> strokeIds,
                                 long timestamp, boolean lowConfidence) {
        this.documentId = documentId;
        this.pageId = pageId;
        this.text = text;
        this.region = region;
        this.strokeIds = strokeIds;
        this.timestamp = timestamp;
        this.lowConfidence = lowConfidence;
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public static HandwritingIndexEntry fromJson(String json) {
        return GSON.fromJson(json, HandwritingIndexEntry.class);
    }
}
