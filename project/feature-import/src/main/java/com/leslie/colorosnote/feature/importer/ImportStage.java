package com.leslie.colorosnote.feature.importer;

/**
 * Import pipeline stages (spec 36). Every import moves strictly forward
 * through these stages; failure leaves no half-finished document behind.
 */
public enum ImportStage {
    SELECTED(0),
    VALIDATE(10),
    COPY_TO_SANDBOX(20),
    DETECT_FORMAT(30),
    EXTRACT_METADATA(40),
    CONVERT(55),
    GENERATE_THUMBNAILS(70),
    EXTRACT_TEXT(78),
    OCR(85),
    CREATE_DOCUMENT(92),
    INDEX(96),
    COMMIT(100);

    public final int baseProgress;

    ImportStage(int baseProgress) {
        this.baseProgress = baseProgress;
    }
}
