package com.leslie.colorosnote.feature.ocr;

/**
 * Typed OCR failure. Engines must surface these instead of crashing or
 * returning fake data (spec 14: ColorOS devices may lack GMS entirely).
 */
public final class OcrException extends Exception {

    public enum ErrorCode {
        /** Engine or on-device model delivery is unavailable (e.g. no Play Services). */
        ENGINE_UNAVAILABLE,
        /** The recognition model is not (yet) downloaded on this device. */
        MODEL_NOT_READY,
        /** Engine ran but failed to produce a result. */
        RECOGNITION_FAILED,
        /** Caller passed an unusable input (null/empty bitmap). */
        INVALID_INPUT
    }

    private final ErrorCode code;

    public OcrException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public OcrException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
