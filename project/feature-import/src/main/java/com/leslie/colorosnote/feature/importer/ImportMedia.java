package com.leslie.colorosnote.feature.importer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.leslie.colorosnote.document.pdf.PdfEngine;

import java.io.File;

/**
 * Bitmap-level media operations used by the pipeline (image probing and
 * first-page thumbnails). Abstracted from {@code BitmapFactory}/
 * {@code PdfThumbnailer} so the coordinator stays JVM-testable.
 */
public interface ImportMedia {

    /**
     * Width/height of an image file in pixels, or null when the file cannot be
     * decoded as an image.
     */
    @Nullable
    int[] probeImageSize(@NonNull File image);

    /**
     * Renders a first-page PDF thumbnail (~200 px wide) into {@code outDir}.
     *
     * @return true when a thumbnail was produced
     */
    boolean pdfThumbnail(@NonNull PdfEngine engine, @NonNull String docKey, @NonNull File outDir);

    /**
     * Writes a downscaled (~200 px wide) thumbnail of an image into {@code outDir}.
     *
     * @return true when a thumbnail was produced
     */
    boolean imageThumbnail(@NonNull File image, @NonNull File outDir);
}
