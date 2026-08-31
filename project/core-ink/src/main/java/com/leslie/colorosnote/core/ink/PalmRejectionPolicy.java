package com.leslie.colorosnote.core.ink;

/**
 * How finger input is treated while writing (requirement 5.1 palm rejection).
 */
public enum PalmRejectionPolicy {
    /** Only TOOL_TYPE_STYLUS produces ink; fingers are always rejected. */
    STYLUS_ONLY,
    /**
     * Fingers may write too, but once a stylus touches down, finger events
     * are rejected for the rest of the gesture (palm rejection).
     */
    FINGER_AND_STYLUS
}
