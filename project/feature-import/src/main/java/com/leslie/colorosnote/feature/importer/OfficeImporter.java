package com.leslie.colorosnote.feature.importer;

import androidx.annotation.NonNull;

import com.leslie.colorosnote.document.office.ImportOptions;
import com.leslie.colorosnote.document.office.ImportedDocument;
import com.leslie.colorosnote.document.office.OfficeImportException;
import com.leslie.colorosnote.document.office.ProgressCallback;

import java.io.File;
import java.io.IOException;

/**
 * Office conversion step behind an interface: {@code OfficeImportCoordinator}
 * is a final concrete class, so tests inject a fake through this seam.
 */
public interface OfficeImporter {

    @NonNull
    ImportedDocument importDocument(@NonNull File source, @NonNull File sandboxDir,
                                    @NonNull ImportOptions options,
                                    @NonNull ProgressCallback progress)
            throws OfficeImportException, IOException;
}
