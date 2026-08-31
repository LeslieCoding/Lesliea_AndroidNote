package com.leslie.colorosnote.feature.handwriting;

/**
 * Typed handwriting-recognition failure. Implementations must surface these
 * instead of crashing or returning fake candidates (ColorOS may lack GMS).
 */
public final class HandwritingException extends Exception {

    public enum ErrorCode {
        /** Engine or model delivery unavailable (e.g. no Play Services). */
        ENGINE_UNAVAILABLE,
        /** The language model is not downloaded on this device. */
        MODEL_NOT_DOWNLOADED,
        /** Engine ran but failed to produce candidates. */
        RECOGNITION_FAILED,
        /** Unknown language tag or unusable stroke input. */
        INVALID_INPUT
    }

    private final ErrorCode code;

    public HandwritingException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public HandwritingException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
