package com.leslie.colorosnote.feature.importer;

import androidx.annotation.NonNull;

import com.leslie.colorosnote.document.pdf.PdfEngine;

import java.io.File;
import java.io.IOException;

/**
 * Opens a {@link PdfEngine} on a sandboxed file. Abstracted so JVM tests can
 * substitute a fake engine (the production one is {@code AndroidPdfEngine},
 * which needs a {@code ParcelFileDescriptor}).
 */
public interface PdfEngineFactory {

    @NonNull
    PdfEngine open(@NonNull File pdfFile) throws IOException;
}
