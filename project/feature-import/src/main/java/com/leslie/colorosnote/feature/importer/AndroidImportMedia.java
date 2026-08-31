package com.leslie.colorosnote.feature.importer;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.leslie.colorosnote.core.common.AppLogger;
import com.leslie.colorosnote.core.common.IoUtils;
import com.leslie.colorosnote.core.security.ImportLimits;
import com.leslie.colorosnote.document.pdf.PdfEngine;
import com.leslie.colorosnote.document.pdf.PdfThumbnailer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

/**
 * Production {@link ImportMedia}: {@link BitmapFactory} for images,
 * {@link PdfThumbnailer} (disk-cached into the document thumbnail dir) for PDFs.
 */
public final class AndroidImportMedia implements ImportMedia {

    private static final String TAG = "AndroidImportMedia";
    private static final int THUMB_WIDTH_PX = 200;

    @WorkerThread
    @Override
    @Nullable
    public int[] probeImageSize(@NonNull File image) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(image.getAbsolutePath(), opts);
        if (opts.outWidth <= 0 || opts.outHeight <= 0
                || opts.outWidth > ImportLimits.MAX_BITMAP_DIMENSION
                || opts.outHeight > ImportLimits.MAX_BITMAP_DIMENSION) {
            return null;
        }
        return new int[]{opts.outWidth, opts.outHeight};
    }

    @WorkerThread
    @Override
    public boolean pdfThumbnail(@NonNull PdfEngine engine, @NonNull String docKey,
                                @NonNull File outDir) {
        try {
            return new PdfThumbnailer(outDir).thumbnail(engine, docKey, 0) != null;
        } catch (RuntimeException e) {
            AppLogger.w(TAG, "PDF thumbnail failed", e);
            return false;
        }
    }

    @WorkerThread
    @Override
    public boolean imageThumbnail(@NonNull File image, @NonNull File outDir) {
        int[] size = probeImageSize(image);
        if (size == null) {
            return false;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = Math.max(1, Math.min(size[0], size[1]) / THUMB_WIDTH_PX);
        Bitmap bmp = BitmapFactory.decodeFile(image.getAbsolutePath(), opts);
        if (bmp == null) {
            return false;
        }
        try {
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            if (!bmp.compress(Bitmap.CompressFormat.PNG, 90, png)) {
                return false;
            }
            IoUtils.atomicWrite(new File(outDir, "page_0.png"), png.toByteArray());
            return true;
        } catch (IOException e) {
            AppLogger.w(TAG, "image thumbnail write failed", e);
            return false;
        } finally {
            bmp.recycle();
        }
    }
}
