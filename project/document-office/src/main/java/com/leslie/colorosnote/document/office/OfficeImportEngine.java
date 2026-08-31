package com.leslie.colorosnote.document.office;

import java.io.File;
import java.io.IOException;

/**
 * Converts an Office file into fixed-layout pages (draw commands + text layer +
 * image resources) for the annotation layer. This is deliberately NOT an Office
 * editor: read -> layout -> fixed base pages -> annotate (requirement section 12).
 *
 * <p>Contract: blocking, call off the main thread; implementations read the source
 * strictly read-only and must honor {@link ImportGuard} limits, the deadline and
 * cancellation. Partial output is never returned: failures throw, cancellations
 * throw {@link OfficeImportException#isCancelled()}.
 *
 * <p>Commercial SDKs (Apryse/PSPDFKit-class, requirement 12.1/49) plug in as an
 * adapter implementing this interface and are swapped in via
 * {@link OfficeEngineRegistry#registerEngine} — the rest of the app only ever
 * sees this interface and {@link PageLayout}.
 */
public interface OfficeImportEngine {

    /** Stable engine id, recorded in imported documents for diagnostics. */
    String id();

    boolean supports(OfficeType type);

    /**
     * Imports {@code source} as the given type. {@code declaredType} comes from the
     * file extension; implementations should verify the container matches and fail
     * with a clear message on mismatch/corruption rather than guessing.
     */
    OfficeImportResult importDocument(File source, OfficeType declaredType,
                                      ImportOptions options, ProgressCallback progress)
            throws OfficeImportException, IOException;
}
