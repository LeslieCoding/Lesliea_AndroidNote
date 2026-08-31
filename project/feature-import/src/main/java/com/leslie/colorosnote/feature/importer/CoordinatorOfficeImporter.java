package com.leslie.colorosnote.feature.importer;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import com.leslie.colorosnote.document.office.ImportOptions;
import com.leslie.colorosnote.document.office.ImportedDocument;
import com.leslie.colorosnote.document.office.OfficeImportCoordinator;
import com.leslie.colorosnote.document.office.OfficeImportException;
import com.leslie.colorosnote.document.office.ProgressCallback;

import java.io.File;
import java.io.IOException;

/** {@link OfficeImporter} delegating to the real {@link OfficeImportCoordinator}. */
public final class CoordinatorOfficeImporter implements OfficeImporter {

    private final OfficeImportCoordinator delegate = new OfficeImportCoordinator();

    @WorkerThread
    @Override
    @NonNull
    public ImportedDocument importDocument(@NonNull File source, @NonNull File sandboxDir,
                                           @NonNull ImportOptions options,
                                           @NonNull ProgressCallback progress)
            throws OfficeImportException, IOException {
        return delegate.importDocument(source, sandboxDir, options, progress);
    }
}
