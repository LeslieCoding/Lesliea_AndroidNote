package com.leslie.colorosnote.document.office;

/**
 * Enforces {@link ImportLimits}, the caller deadline and cancellation while an
 * engine walks a document. All layouters must tick this guard per structural
 * block and per produced page so malicious files fail fast with a clear error.
 */
public final class ImportGuard {

    private final ImportLimits limits;
    private final long deadlineMs;
    private final ProgressCallback callback;

    private int blocks;
    private int pages;
    private long textChars;
    private int images;
    private long imageBytes;

    public ImportGuard(ImportOptions options, ProgressCallback callback) {
        this.limits = options.getLimits();
        this.deadlineMs = options.getDeadlineMs();
        this.callback = callback == null ? ProgressCallback.NONE : callback;
    }

    /** Call once per structural item (paragraph, table row, cell, shape, ...). */
    public void tickBlock() throws OfficeImportException {
        blocks++;
        if (blocks > limits.maxBlocks) {
            throw new OfficeImportException("Document has too many elements (> " + limits.maxBlocks + ")");
        }
        checkClock();
    }

    /** Call once per produced page. */
    public void startPage() throws OfficeImportException {
        pages++;
        if (pages > limits.maxPages) {
            throw new OfficeImportException("Document produces too many pages (> " + limits.maxPages + ")");
        }
        checkClock();
    }

    public void addText(int chars) throws OfficeImportException {
        textChars += chars;
        if (textChars > limits.maxTotalTextChars) {
            throw new OfficeImportException("Document text exceeds import limit");
        }
    }

    public void addImage(long bytes) throws OfficeImportException {
        images++;
        imageBytes += bytes;
        if (images > limits.maxImages || imageBytes > limits.maxTotalImageBytes) {
            throw new OfficeImportException("Embedded images exceed import limit");
        }
    }

    private void checkClock() throws OfficeImportException {
        if (callback.isCancelled()) {
            throw OfficeImportException.cancelled();
        }
        if (deadlineMs > 0 && System.currentTimeMillis() > deadlineMs) {
            throw new OfficeImportException("Import timed out (deadline exceeded)");
        }
    }
}
