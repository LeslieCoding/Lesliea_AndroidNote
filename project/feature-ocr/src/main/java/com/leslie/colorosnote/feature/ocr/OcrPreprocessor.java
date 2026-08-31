package com.leslie.colorosnote.feature.ocr;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure bitmap preprocessing before OCR (spec 14.2): smart scaling, grayscale +
 * contrast enhancement, projection-variance deskew, and exposure quality flags.
 * No engine calls; every step works fully offline.
 */
public final class OcrPreprocessor {

    /** Exposure analysis of the (scaled) input image. */
    public static final class QualityReport {
        /** Mean luminance 0..255. */
        public final float meanLuminance;
        public final boolean tooDark;
        public final boolean tooBright;

        public QualityReport(float meanLuminance, boolean tooDark, boolean tooBright) {
            this.meanLuminance = meanLuminance;
            this.tooDark = tooDark;
            this.tooBright = tooBright;
        }
    }

    public static final class PreprocessResult {
        /** Processed bitmap; may be the source instance when nothing was needed. */
        public final Bitmap bitmap;
        public final QualityReport quality;
        /** Deskew angle actually applied, in degrees (0 when skipped or below noise). */
        public final float deskewAngleDegrees;

        public PreprocessResult(Bitmap bitmap, QualityReport quality, float deskewAngleDegrees) {
            this.bitmap = bitmap;
            this.quality = quality;
            this.deskewAngleDegrees = deskewAngleDegrees;
        }
    }

    private static final float DARK_THRESHOLD = 60f;
    private static final float BRIGHT_THRESHOLD = 200f;
    private static final float MAX_SKEW_DEGREES = 10f;
    private static final float SKEW_STEP_DEGREES = 0.5f;
    private static final float MIN_SKEW_TO_APPLY = 0.4f;
    private static final int SKEW_PROBE_LONG_EDGE = 400;
    private static final int QUALITY_PROBE_LONG_EDGE = 128;
    private static final float CONTRAST_FACTOR = 1.4f;

    /** Full pipeline: scale -> quality probe -> grayscale+contrast -> deskew. */
    public PreprocessResult preprocess(Bitmap src, OcrOptions options) {
        Bitmap work = scaleToMaxLongEdge(src, options.maxLongEdge);
        QualityReport quality = analyzeQuality(work);
        if (options.enhanceContrast) {
            work = enhanceContrast(toGrayscale(work));
        }
        float angle = 0f;
        if (options.deskew) {
            float detected = detectSkewAngle(work);
            if (Math.abs(detected) >= MIN_SKEW_TO_APPLY) {
                work = rotate(work, detected);
                angle = detected;
            }
        }
        return new PreprocessResult(work, quality, angle);
    }

    /** Returns src unchanged when already within the limit, else a scaled copy. */
    public static Bitmap scaleToMaxLongEdge(Bitmap src, int maxLongEdge) {
        int w = src.getWidth();
        int h = src.getHeight();
        int longEdge = Math.max(w, h);
        if (longEdge <= 0 || longEdge <= maxLongEdge) {
            return src;
        }
        float scale = (float) maxLongEdge / longEdge;
        return Bitmap.createScaledBitmap(src,
                Math.max(1, Math.round(w * scale)),
                Math.max(1, Math.round(h * scale)), true);
    }

    /** Mean-luminance probe with too-dark / too-bright flags (spec 14.2). */
    public static QualityReport analyzeQuality(Bitmap src) {
        Bitmap probe = scaleToMaxLongEdge(src, QUALITY_PROBE_LONG_EDGE);
        int w = probe.getWidth();
        int h = probe.getHeight();
        int[] px = new int[w * h];
        probe.getPixels(px, 0, w, 0, 0, w, h);
        double sum = 0;
        for (int c : px) {
            sum += 0.299 * ((c >> 16) & 0xFF) + 0.587 * ((c >> 8) & 0xFF) + 0.114 * (c & 0xFF);
        }
        float mean = (float) (sum / (w * h));
        return new QualityReport(mean, mean < DARK_THRESHOLD, mean > BRIGHT_THRESHOLD);
    }

    public static Bitmap toGrayscale(Bitmap src) {
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(0f);
        return applyColorMatrix(src, cm);
    }

    /** Linear contrast stretch around mid-gray. */
    public static Bitmap enhanceContrast(Bitmap src) {
        float s = CONTRAST_FACTOR;
        float o = 128f * (1f - s);
        ColorMatrix cm = new ColorMatrix(new float[]{
                s, 0, 0, 0, o,
                0, s, 0, 0, o,
                0, 0, s, 0, o,
                0, 0, 0, 1, 0});
        return applyColorMatrix(src, cm);
    }

    public static Bitmap rotate(Bitmap src, float degrees) {
        Matrix m = new Matrix();
        m.postRotate(degrees);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
    }

    /**
     * Estimates the correction angle in [-10, 10] degrees by searching for the
     * rotation that maximizes the variance of the horizontal projection profile
     * of dark pixels. Cheap probe on a downscaled copy; 0 means "no clear skew".
     */
    public static float detectSkewAngle(Bitmap src) {
        Bitmap probe = scaleToMaxLongEdge(src, SKEW_PROBE_LONG_EDGE);
        int w = probe.getWidth();
        int h = probe.getHeight();
        int[] px = new int[w * h];
        probe.getPixels(px, 0, w, 0, 0, w, h);

        // Binarize against the mean luminance.
        double sum = 0;
        float[] lum = new float[w * h];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            lum[i] = (float) (0.299 * ((c >> 16) & 0xFF) + 0.587 * ((c >> 8) & 0xFF) + 0.114 * (c & 0xFF));
            sum += lum[i];
        }
        float threshold = (float) (sum / px.length);

        // Collect dark pixel coordinates once; reused for every candidate angle.
        List<Integer> darkX = new ArrayList<>();
        List<Integer> darkY = new ArrayList<>();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (lum[y * w + x] < threshold) {
                    darkX.add(x);
                    darkY.add(y);
                }
            }
        }
        int n = darkX.size();
        if (n < 16) {
            return 0f; // Too little content to judge skew.
        }

        float bestAngle = 0f;
        double bestVariance = -1;
        double[] rowSums = new double[h * 2]; // rotated y can exceed [0,h)
        for (float a = -MAX_SKEW_DEGREES; a <= MAX_SKEW_DEGREES + 1e-4f; a += SKEW_STEP_DEGREES) {
            double rad = Math.toRadians(a);
            double sin = Math.sin(rad);
            double cos = Math.cos(rad);
            java.util.Arrays.fill(rowSums, 0);
            for (int i = 0; i < n; i++) {
                // Counter-clockwise rotation maps (x,y) -> (x cos - y sin, x sin + y cos).
                int ry = (int) Math.round(darkX.get(i) * sin + darkY.get(i) * cos) + h / 2;
                if (ry >= 0 && ry < rowSums.length) {
                    rowSums[ry]++;
                }
            }
            double mean = (double) n / rowSums.length;
            double variance = 0;
            for (double s : rowSums) {
                double d = s - mean;
                variance += d * d;
            }
            if (variance > bestVariance) {
                bestVariance = variance;
                bestAngle = a;
            }
        }
        return bestAngle;
    }

    private static Bitmap applyColorMatrix(Bitmap src, ColorMatrix cm) {
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(),
                src.getConfig() != null ? src.getConfig() : Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColorFilter(new ColorMatrixColorFilter(cm));
        canvas.drawBitmap(src, 0, 0, paint);
        return out;
    }
}
