package com.leslie.colorosnote.document.pdf;

import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import java.io.File;
import java.io.IOException;

/**
 * Opens an {@link AndroidPdfEngine} over a read-only descriptor of a local
 * file. Lives next to the engine so any module with a PDF file on disk (the
 * import pipeline, the editor's PDF background) can open it without depending
 * on feature-import.
 */
public final class AndroidPdfEngineFactory {

    @WorkerThread
    @NonNull
    public PdfEngine open(@NonNull File pdfFile) throws IOException {
        AndroidPdfEngine engine = new AndroidPdfEngine();
        engine.open(ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY));
        return engine;
    }
}
