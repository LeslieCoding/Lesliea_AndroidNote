package com.leslie.colorosnote.feature.importer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;

/**
 * Read access to the import source (a SAF content Uri in production).
 * Abstracted from {@code ContentResolver} so the import pipeline stays
 * unit-testable on the JVM. All methods are blocking IO — worker thread only.
 */
public interface ImportSourceResolver {

    /** Display name of the source, or null when the provider does not report one. */
    @Nullable
    String displayName(@NonNull String sourceRef) throws IOException;

    /** Declared size in bytes, or -1 when unknown (the copy stage re-measures). */
    long sizeBytes(@NonNull String sourceRef) throws IOException;

    /** Opens a fresh stream over the source; caller closes. */
    @NonNull
    InputStream open(@NonNull String sourceRef) throws IOException;
}
