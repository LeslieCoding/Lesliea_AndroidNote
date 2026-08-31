package com.leslie.colorosnote.document.office;

/** Per-import knobs: safety limits plus an optional wall-clock deadline. */
public final class ImportOptions {

    private final ImportLimits limits;
    /** Absolute epoch-millis deadline; 0 means no timeout. Checked per block and per page. */
    private final long deadlineMs;

    public ImportOptions(ImportLimits limits, long deadlineMs) {
        this.limits = limits == null ? ImportLimits.defaults() : limits;
        this.deadlineMs = deadlineMs;
    }

    public static ImportOptions defaults() {
        return new ImportOptions(ImportLimits.defaults(), 0L);
    }

    public ImportLimits getLimits() {
        return limits;
    }

    public long getDeadlineMs() {
        return deadlineMs;
    }
}
