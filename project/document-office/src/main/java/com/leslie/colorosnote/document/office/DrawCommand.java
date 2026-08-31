package com.leslie.colorosnote.document.office;

/**
 * A single drawing instruction on a fixed-layout page. Pure data, Android-free:
 * core-render (or a commercial SDK adapter) interprets these onto a Canvas/bitmap.
 *
 * <p>Coordinate system: pixels at 96 dpi, origin at the page top-left, y growing
 * downwards. Text {@code y} is the baseline.
 */
public abstract class DrawCommand {

    private DrawCommand() {
    }

    /** A run of text sharing one style. */
    public static final class Text extends DrawCommand {
        public final float x;
        /** Baseline y. */
        public final float y;
        public final String text;
        public final float textSizePx;
        public final int colorArgb;
        public final boolean bold;
        public final boolean italic;
        public final TextAlign align;

        public Text(float x, float y, String text, float textSizePx,
                    int colorArgb, boolean bold, boolean italic, TextAlign align) {
            this.x = x;
            this.y = y;
            this.text = text;
            this.textSizePx = textSizePx;
            this.colorArgb = colorArgb;
            this.bold = bold;
            this.italic = italic;
            this.align = align;
        }
    }

    /** A placed image; the bytes live in the resource table under {@link #resourceId}. */
    public static final class Image extends DrawCommand {
        public final float x;
        public final float y;
        public final float width;
        public final float height;
        public final String resourceId;

        public Image(float x, float y, float width, float height, String resourceId) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.resourceId = resourceId;
        }
    }

    /** A rectangle (table cell borders, simple shapes, underline rules). */
    public static final class Rect extends DrawCommand {
        public final float x;
        public final float y;
        public final float width;
        public final float height;
        public final int colorArgb;
        public final boolean fill;
        public final float strokeWidthPx;

        public Rect(float x, float y, float width, float height,
                    int colorArgb, boolean fill, float strokeWidthPx) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.colorArgb = colorArgb;
            this.fill = fill;
            this.strokeWidthPx = strokeWidthPx;
        }
    }

    /** A straight line segment (spreadsheet grid rules, shape connectors). */
    public static final class Line extends DrawCommand {
        public final float x1;
        public final float y1;
        public final float x2;
        public final float y2;
        public final int colorArgb;
        public final float strokeWidthPx;

        public Line(float x1, float y1, float x2, float y2, int colorArgb, float strokeWidthPx) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.colorArgb = colorArgb;
            this.strokeWidthPx = strokeWidthPx;
        }
    }
}
