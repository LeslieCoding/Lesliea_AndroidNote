package com.leslie.colorosnote.document.office;

/**
 * Safety caps applied while parsing hostile or pathological documents
 * (requirement 12.3: timeout, memory and size bounds for imports).
 */
public class ImportLimits {

    /** Max source file size accepted for import. */
    public long maxFileBytes = 200L * 1024 * 1024;

    /** Max structural items (paragraphs, cells, shapes) processed per document. */
    public int maxBlocks = 100_000;

    /** Max produced pages; layout stops with an error beyond this. */
    public int maxPages = 5_000;

    /** Max cumulative extracted text characters. */
    public long maxTotalTextChars = 20_000_000L;

    /** Max embedded images extracted. */
    public int maxImages = 2_000;

    /** Max cumulative embedded image bytes. */
    public long maxTotalImageBytes = 256L * 1024 * 1024;

    public static ImportLimits defaults() {
        return new ImportLimits();
    }
}
