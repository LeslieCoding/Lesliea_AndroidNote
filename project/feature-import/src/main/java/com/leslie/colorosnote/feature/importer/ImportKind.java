package com.leslie.colorosnote.feature.importer;

/** Importable format families decided at {@link ImportStage#DETECT_FORMAT}. */
public enum ImportKind {
    PDF,
    OFFICE,
    IMAGE,
    CAJ,
    /** Our own container format; imported via the restore/copy path. */
    INKNOTE
}
