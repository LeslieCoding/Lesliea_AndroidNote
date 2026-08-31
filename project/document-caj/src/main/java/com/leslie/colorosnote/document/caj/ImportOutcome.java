package com.leslie.colorosnote.document.caj;

import java.io.File;

/**
 * Result of a CAJ import attempt. Success means a real PDF file exists at
 * {@link #getOutputPdf()}; every failure carries an explicit error code and a
 * user-readable Chinese message. A fake success is never produced.
 */
public final class ImportOutcome {

    public enum Status {
        /** A PDF was produced. */
        SUCCESS,
        /** Recognized CAJ variant we cannot parse without an external SDK. */
        UNSUPPORTED_CAJ_VARIANT,
        /** File is empty, truncated or structurally broken. */
        CORRUPT_FILE,
        /** Filesystem-level failure (permissions, disk full, ...). */
        IO_ERROR
    }

    private final Status status;
    private final File outputPdf;
    private final String userMessage;

    private ImportOutcome(Status status, File outputPdf, String userMessage) {
        this.status = status;
        this.outputPdf = outputPdf;
        this.userMessage = userMessage;
    }

    public static ImportOutcome success(File outputPdf, String userMessage) {
        if (outputPdf == null || !outputPdf.isFile()) {
            throw new IllegalArgumentException("success requires an existing PDF file");
        }
        return new ImportOutcome(Status.SUCCESS, outputPdf, userMessage);
    }

    public static ImportOutcome unsupportedVariant(String userMessage) {
        return new ImportOutcome(Status.UNSUPPORTED_CAJ_VARIANT, null, userMessage);
    }

    public static ImportOutcome corrupt(String userMessage) {
        return new ImportOutcome(Status.CORRUPT_FILE, null, userMessage);
    }

    public static ImportOutcome ioError(String userMessage) {
        return new ImportOutcome(Status.IO_ERROR, null, userMessage);
    }

    public Status getStatus() {
        return status;
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    /** The produced PDF, or null on any failure. */
    public File getOutputPdf() {
        return outputPdf;
    }

    /** Message safe to show directly in the import UI. */
    public String getUserMessage() {
        return userMessage;
    }

    @Override
    public String toString() {
        return "ImportOutcome{" + status + (outputPdf != null ? ", " + outputPdf : "") + '}';
    }
}
