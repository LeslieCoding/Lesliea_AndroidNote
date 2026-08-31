package com.leslie.colorosnote.document.pdf;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

/**
 * Facade for page-text extraction that hides the engine's real capability.
 *
 * <p>Reality check: the platform {@code PdfRenderer} exposes no text API, so with
 * {@link AndroidPdfEngine} every query reports "unknown / empty". This class keeps
 * the call sites stable so a future engine (commercial SDK, MuPDF...) with real
 * text extraction can be swapped in without touching feature code — the fallback
 * for textless pages is OCR (feature-ocr).
 */
public final class PdfTextExtractor {

    private final PdfEngine engine;

    public PdfTextExtractor(@NonNull PdfEngine engine) {
        this.engine = engine;
    }

    /**
     * Text of the page, or "" when the engine cannot extract text or the page is
     * textless. Callers must not treat "" as proof that the page has no text layer
     * unless {@link #isTextLayerStateKnown()} is true.
     */
    @WorkerThread
    @NonNull
    public String extractPageText(int page) {
        if (!engine.supportsTextExtraction()) {
            return "";
        }
        String text = engine.extractText(page);
        return text == null ? "" : text;
    }

    /**
     * Whether the engine can state at all if a page has a text layer. False means
     * "unknown" (current PdfRenderer-based engine), not "no text".
     */
    public boolean isTextLayerStateKnown() {
        return engine.supportsTextExtraction();
    }

    /**
     * Best-effort answer: true only when the engine supports extraction AND the
     * page yields text; false means "no text OR unknown".
     */
    @WorkerThread
    public boolean hasTextLayer(int page) {
        return engine.supportsTextExtraction() && !extractPageText(page).isEmpty();
    }

    /** Extracts text for a page range, concatenated with '\n' separators. */
    @WorkerThread
    @NonNull
    public String extractRange(int fromPage, int toPageInclusive, @Nullable CancelCheck cancel) {
        StringBuilder sb = new StringBuilder();
        for (int p = fromPage; p <= toPageInclusive; p++) {
            if (cancel != null && cancel.isCancelled()) {
                break;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(extractPageText(p));
        }
        return sb.toString();
    }

    /** Cooperative cancellation for long extraction runs. */
    public interface CancelCheck {
        boolean isCancelled();
    }
}
