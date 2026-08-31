package com.leslie.colorosnote.document.office;

import com.leslie.colorosnote.core.common.AppLogger;
import com.leslie.colorosnote.core.common.IoUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bridges engines and the rest of the app: picks the registered engine for the
 * file type, runs the import with progress/cancellation, persists extracted
 * images into the caller-provided sandbox directory (atomic writes), and
 * packages everything as an {@link ImportedDocument}.
 *
 * <p>Call from a background executor ({@code AppExecutors.io()}); all work is
 * blocking. On failure the sandbox may contain partially written resources —
 * callers should treat the sandbox as disposable per import job.
 */
public final class OfficeImportCoordinator {

    private static final String TAG = "OfficeImport";
    private static final String RESOURCE_DIR_NAME = "resources";

    /**
     * Imports {@code source}, writing embedded images under
     * {@code sandboxDir/resources/}.
     *
     * @param source     office file (.doc/.docx/.ppt/.pptx/.xls/.xlsx)
     * @param sandboxDir per-import writable directory owned by the caller
     */
    public ImportedDocument importDocument(File source, File sandboxDir,
                                           ImportOptions options, ProgressCallback progress)
            throws OfficeImportException, IOException {
        ProgressCallback cb = progress == null ? ProgressCallback.NONE : progress;
        OfficeType type = OfficeType.fromFileName(source == null ? null : source.getName());
        if (type == null) {
            throw new OfficeImportException("Unsupported extension: "
                    + (source == null ? "<null>" : source.getName()));
        }
        OfficeImportEngine engine = OfficeEngineRegistry.engineFor(type);
        if (engine == null) {
            throw new OfficeImportException("No engine registered for " + type);
        }
        AppLogger.i(TAG, "Importing " + type + " with engine " + engine.id());
        OfficeImportResult result = engine.importDocument(source, type, options, cb);

        cb.onProgress(92, "resources");
        File resourceDir = new File(sandboxDir, RESOURCE_DIR_NAME);
        Map<String, File> resourceFiles = new LinkedHashMap<>();
        for (ImageResource image : result.getImages()) {
            if (cb.isCancelled()) {
                throw OfficeImportException.cancelled();
            }
            File target = new File(resourceDir, image.getId()); // ids are safe file names
            IoUtils.atomicWrite(target, image.getData());
            resourceFiles.put(image.getId(), target);
        }

        List<ImportedPage> pages = new ArrayList<>();
        int number = 0;
        for (PageLayout page : result.getPages()) {
            number++;
            pages.add(new ImportedPage(number, page.getName(), page.getWidthPx(),
                    page.getHeightPx(), page.getCommands(), page.getTextLayer()));
        }
        cb.onProgress(100, "done");
        return new ImportedDocument(source.getName(), type, engine.id(), pages,
                result.getWarnings(), resourceDir, resourceFiles);
    }

    /** Convenience overload with default options and no progress reporting. */
    public ImportedDocument importDocument(File source, File sandboxDir)
            throws OfficeImportException, IOException {
        return importDocument(source, sandboxDir, ImportOptions.defaults(), ProgressCallback.NONE);
    }
}
