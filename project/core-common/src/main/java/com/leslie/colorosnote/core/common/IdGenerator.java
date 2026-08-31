package com.leslie.colorosnote.core.common;

import java.util.UUID;

/** UUID-based id generation for all mutable document objects. */
public final class IdGenerator {

    private IdGenerator() {
    }

    /** Returns a new random UUID string (canonical 36-char form). */
    public static String newId() {
        return UUID.randomUUID().toString();
    }
}
