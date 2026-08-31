package com.leslie.colorosnote.feature.ocr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Structured OCR output: blocks -> lines -> elements, each with text, a bounding
 * box and an optional confidence (null when the engine does not report one, e.g.
 * parts of ML Kit text recognition).
 *
 * <p>All coordinates are relative to the bitmap the engine actually processed
 * ({@link #imageWidth} x {@link #imageHeight}); use {@link #scaledTo(int, int)}
 * to map them back to the original page image. Pure JVM type.
 */
public final class OcrResult {

    /** Smallest recognized unit (usually a word or a single CJK character). */
    public static final class Element {
        public final String text;
        public final OcrBox box;
        public final Float confidence; // nullable

        public Element(String text, OcrBox box, Float confidence) {
            this.text = text;
            this.box = box;
            this.confidence = confidence;
        }
    }

    public static final class Line {
        public final String text;
        public final OcrBox box;
        public final Float confidence; // nullable
        public final List<Element> elements;

        public Line(String text, OcrBox box, Float confidence, List<Element> elements) {
            this.text = text;
            this.box = box;
            this.confidence = confidence;
            this.elements = elements == null
                    ? Collections.emptyList() : Collections.unmodifiableList(elements);
        }
    }

    public static final class Block {
        public final String text;
        public final OcrBox box;
        public final List<Line> lines;

        public Block(String text, OcrBox box, List<Line> lines) {
            this.text = text;
            this.box = box;
            this.lines = lines == null
                    ? Collections.emptyList() : Collections.unmodifiableList(lines);
        }
    }

    public final List<Block> blocks;
    /** Width of the bitmap the engine processed (after preprocessing/scaling). */
    public final int imageWidth;
    /** Height of the bitmap the engine processed (after preprocessing/scaling). */
    public final int imageHeight;

    public OcrResult(List<Block> blocks, int imageWidth, int imageHeight) {
        this.blocks = blocks == null
                ? Collections.emptyList() : Collections.unmodifiableList(blocks);
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
    }

    /** Concatenated text of all blocks, separated by newlines. */
    public String allText() {
        StringBuilder sb = new StringBuilder();
        for (Block b : blocks) {
            if (b.text == null || b.text.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(b.text);
        }
        return sb.toString();
    }

    /** Mean element confidence over the elements that report one, or null if none do. */
    public Float meanConfidence() {
        double sum = 0;
        int count = 0;
        for (Block b : blocks) {
            for (Line l : b.lines) {
                for (Element e : l.elements) {
                    if (e.confidence != null) {
                        sum += e.confidence;
                        count++;
                    }
                }
            }
        }
        return count == 0 ? null : (float) (sum / count);
    }

    /**
     * Returns a copy with every box mapped from the processed bitmap size to the
     * given target size (typically the original page image). Identity when sizes
     * match or are unknown.
     */
    public OcrResult scaledTo(int targetWidth, int targetHeight) {
        if (imageWidth <= 0 || imageHeight <= 0
                || (imageWidth == targetWidth && imageHeight == targetHeight)) {
            return this;
        }
        float sx = (float) targetWidth / imageWidth;
        float sy = (float) targetHeight / imageHeight;
        List<Block> scaled = new ArrayList<>(blocks.size());
        for (Block b : blocks) {
            List<Line> lines = new ArrayList<>(b.lines.size());
            for (Line l : b.lines) {
                List<Element> elements = new ArrayList<>(l.elements.size());
                for (Element e : l.elements) {
                    elements.add(new Element(e.text, scaleBox(e.box, sx, sy), e.confidence));
                }
                lines.add(new Line(l.text, scaleBox(l.box, sx, sy), l.confidence, elements));
            }
            scaled.add(new Block(b.text, scaleBox(b.box, sx, sy), lines));
        }
        return new OcrResult(scaled, targetWidth, targetHeight);
    }

    private static OcrBox scaleBox(OcrBox box, float sx, float sy) {
        return box == null ? null : box.scaled(sx, sy);
    }
}
