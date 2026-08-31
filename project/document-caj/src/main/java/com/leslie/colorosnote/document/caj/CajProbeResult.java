package com.leslie.colorosnote.document.caj;

/**
 * Outcome of {@link CajFormatDetector#probe}: the detected format plus, when the
 * container embeds a complete PDF, the exact byte range that can be extracted.
 *
 * <p>Immutable. All offsets are absolute file offsets; {@code embeddedPdfEnd} is
 * exclusive.
 */
public final class CajProbeResult {

    private final CajFormat format;
    private final boolean embeddedPdf;
    private final long embeddedPdfStart;
    private final long embeddedPdfEnd;
    /** User-readable explanation (Chinese, shown in import dialogs). */
    private final String message;

    private CajProbeResult(CajFormat format, boolean embeddedPdf,
                           long embeddedPdfStart, long embeddedPdfEnd, String message) {
        this.format = format;
        this.embeddedPdf = embeddedPdf;
        this.embeddedPdfStart = embeddedPdfStart;
        this.embeddedPdfEnd = embeddedPdfEnd;
        this.message = message;
    }

    public static CajProbeResult of(CajFormat format, String message) {
        return new CajProbeResult(format, false, -1L, -1L, message);
    }

    public static CajProbeResult withEmbeddedPdf(CajFormat format, long start, long endExclusive, String message) {
        return new CajProbeResult(format, true, start, endExclusive, message);
    }

    public CajFormat getFormat() {
        return format;
    }

    /** True when a complete PDF ({@code %PDF…%%EOF}) was located inside the file. */
    public boolean hasEmbeddedPdf() {
        return embeddedPdf;
    }

    /** Absolute offset of the embedded {@code %PDF} header, or -1. */
    public long getEmbeddedPdfStart() {
        return embeddedPdfStart;
    }

    /** Absolute offset just past the embedded {@code %%EOF}, or -1. */
    public long getEmbeddedPdfEnd() {
        return embeddedPdfEnd;
    }

    public String getMessage() {
        return message;
    }

    /** True when the file is (or contains) something we can convert to PDF. */
    public boolean isConvertible() {
        return format == CajFormat.PDF || embeddedPdf;
    }

    @Override
    public String toString() {
        return "CajProbeResult{format=" + format
                + ", embeddedPdf=" + embeddedPdf
                + (embeddedPdf ? " [" + embeddedPdfStart + "," + embeddedPdfEnd + ")" : "")
                + '}';
    }
}
