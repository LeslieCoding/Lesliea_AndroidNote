package com.leslie.colorosnote.feature.audio;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Extracts text from Vosk recognizer JSON results. Recognizer results look
 * like {@code {"text": "你好 世界"}} and partials like
 * {@code {"partial": "你好"}}; garbage/empty input maps to "".
 * Pure Java (Gson), JVM-testable.
 */
public final class VoskJson {

    private VoskJson() {
    }

    /** Text of a finalized result ({@code getResult()}/{@code getFinalResult()}). */
    public static String extractText(String json) {
        return extract(json, "text");
    }

    /** Text of a partial (in-progress) result. */
    public static String extractPartial(String json) {
        return extract(json, "partial");
    }

    private static String extract(String json, String field) {
        if (json == null || json.isEmpty()) {
            return "";
        }
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            if (obj.has(field) && !obj.get(field).isJsonNull()) {
                return obj.get(field).getAsString().trim();
            }
        } catch (RuntimeException ignored) {
            // Malformed engine output: treat as empty.
        }
        return "";
    }
}
