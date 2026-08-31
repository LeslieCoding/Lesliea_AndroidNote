package com.leslie.colorosnote.document.caj;

import java.io.File;
import java.io.IOException;

/**
 * Pluggable CAJ import capability (requirement §13). CAJ has no stable public
 * Android rendering API, so this is an adapter point: the default implementation
 * handles real PDFs and CAJ containers with an embedded PDF, and reports a clear
 * error otherwise. A future legal CAJ SDK/converter plugs in behind this interface
 * without touching feature code.
 */
public interface CajImportEngine {

    /**
     * Sniffs the real format of {@code src} from its magic bytes — never trusts
     * the file extension.
     */
    CajProbeResult probe(File src) throws IOException;

    /**
     * Converts {@code src} into a standalone PDF inside {@code outDir}.
     *
     * <p>Must never fake success: when the variant cannot be parsed, returns an
     * outcome with status {@link ImportOutcome.Status#UNSUPPORTED_CAJ_VARIANT} and
     * a message telling the user to convert externally to PDF first.
     *
     * @param progress may be null; called with monotonically increasing percents
     */
    ImportOutcome convert(File src, File outDir, ProgressCallback progress);
}
