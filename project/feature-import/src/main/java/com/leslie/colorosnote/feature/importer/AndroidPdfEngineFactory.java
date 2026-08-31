package com.leslie.colorosnote.feature.importer;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import com.leslie.colorosnote.document.pdf.PdfEngine;

import java.io.File;
import java.io.IOException;

/**
 * Production {@link PdfEngineFactory}: delegates to
 * {@link com.leslie.colorosnote.document.pdf.AndroidPdfEngineFactory}, which
 * owns the actual engine setup so the editor can share it.
 */
public final class AndroidPdfEngineFactory implements PdfEngineFactory {

    private final com.leslie.colorosnote.document.pdf.AndroidPdfEngineFactory delegate =
            new com.leslie.colorosnote.document.pdf.AndroidPdfEngineFactory();

    @WorkerThread
    @Override
    @NonNull
    public PdfEngine open(@NonNull File pdfFile) throws IOException {
        return delegate.open(pdfFile);
    }
}
