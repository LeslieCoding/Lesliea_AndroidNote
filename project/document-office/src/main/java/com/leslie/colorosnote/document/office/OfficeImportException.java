package com.leslie.colorosnote.document.office;

/**
 * Failure of an Office import: corrupt file, limit violation, timeout,
 * cancellation or a format this build genuinely cannot handle.
 */
public class OfficeImportException extends Exception {

    private final boolean cancelled;
    private final boolean unsupported;

    public OfficeImportException(String message) {
        this(message, null, false, false);
    }

    public OfficeImportException(String message, Throwable cause) {
        this(message, cause, false, false);
    }

    private OfficeImportException(String message, Throwable cause, boolean cancelled, boolean unsupported) {
        super(message, cause);
        this.cancelled = cancelled;
        this.unsupported = unsupported;
    }

    /** Creates the exception thrown when the caller cancels via {@link ProgressCallback}. */
    public static OfficeImportException cancelled() {
        return new OfficeImportException("Import cancelled", null, true, false);
    }

    /**
     * Creates a hard "format unsupported" failure (e.g. legacy .ppt on Android).
     * The UI should surface the message verbatim — it contains the user-facing
     * guidance — rather than a generic error. Never used for fake successes.
     */
    public static OfficeImportException unsupported(String message) {
        return new OfficeImportException("UNSUPPORTED: " + message, null, false, true);
    }

    /** True when this failure is a user cancellation rather than an error. */
    public boolean isCancelled() {
        return cancelled;
    }

    /** True when the format is fundamentally unsupported (not a corruption/bug). */
    public boolean isUnsupported() {
        return unsupported;
    }
}
