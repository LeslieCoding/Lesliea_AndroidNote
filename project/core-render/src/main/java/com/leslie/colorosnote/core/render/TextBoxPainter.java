package com.leslie.colorosnote.core.render;

import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Typeface;

import com.leslie.colorosnote.core.model.PageObject;
import com.leslie.colorosnote.core.model.TextBoxObject;

/**
 * Default painter for {@link TextBoxObject}: multi-line text with alignment,
 * bold/italic/underline and optional background/border. Draws in local
 * coordinates; the renderer applies the object transform.
 *
 * <p>An empty box paints a dashed gray border with a centered 「输入文本」
 * placeholder so a freshly inserted box stays visible on the page; the
 * placeholder disappears as soon as the box has real text.
 */
public final class TextBoxPainter implements ObjectPainter {

    /** Placeholder drawn inside an empty box (no resources in core-render). */
    public static final String EMPTY_PLACEHOLDER = "输入文本";

    private static final int PLACEHOLDER_LINE = 0xFF9AA0A8;
    private static final int PLACEHOLDER_TEXT = 0xFF9AA0A8;

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    @Override
    public boolean supports(PageObject object) {
        return object instanceof TextBoxObject;
    }

    @Override
    public void paint(Canvas canvas, PageObject object) {
        TextBoxObject box = (TextBoxObject) object;
        float left = box.bounds[0];
        float top = box.bounds[1];
        float right = box.bounds[2];
        float bottom = box.bounds[3];

        if (box.text == null || box.text.isEmpty()) {
            paintEmptyPlaceholder(canvas, left, top, right, bottom, box.fontSize);
            return;
        }

        if (box.backgroundColor != null) {
            boxPaint.setStyle(Paint.Style.FILL);
            boxPaint.setColor(box.backgroundColor);
            canvas.drawRect(left, top, right, bottom, boxPaint);
        }
        if (box.borderColor != null) {
            boxPaint.setStyle(Paint.Style.STROKE);
            boxPaint.setStrokeWidth(1f);
            boxPaint.setColor(box.borderColor);
            canvas.drawRect(left, top, right, bottom, boxPaint);
        }

        textPaint.setColor(box.color);
        textPaint.setTextSize(box.fontSize);
        int style = (box.bold ? Typeface.BOLD : 0) | (box.italic ? Typeface.ITALIC : 0);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, style));
        textPaint.setUnderlineText(box.underline);
        switch (box.alignment) {
            case CENTER:
                textPaint.setTextAlign(Paint.Align.CENTER);
                break;
            case RIGHT:
                textPaint.setTextAlign(Paint.Align.RIGHT);
                break;
            case LEFT:
            default:
                textPaint.setTextAlign(Paint.Align.LEFT);
                break;
        }

        float anchorX = box.alignment == TextBoxObject.Alignment.CENTER
                ? (left + right) / 2f
                : box.alignment == TextBoxObject.Alignment.RIGHT ? right : left;
        float lineHeight = box.fontSize * box.lineSpacing;
        float y = top + box.fontSize; // first baseline
        String[] lines = box.text.split("\n", -1);
        for (String line : lines) {
            if (y - box.fontSize > bottom && !box.autoHeight) {
                break;
            }
            canvas.drawText(line, anchorX, y, textPaint);
            y += lineHeight;
        }
    }

    /** Dashed border + centered gray hint for a box without any text yet. */
    private void paintEmptyPlaceholder(Canvas canvas, float left, float top,
                                       float right, float bottom, float fontSize) {
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(1.5f);
        boxPaint.setColor(PLACEHOLDER_LINE);
        boxPaint.setPathEffect(new DashPathEffect(new float[]{8f, 6f}, 0f));
        canvas.drawRect(left + 1f, top + 1f, right - 1f, bottom - 1f, boxPaint);
        boxPaint.setPathEffect(null);

        textPaint.setTypeface(Typeface.DEFAULT);
        textPaint.setUnderlineText(false);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(PLACEHOLDER_TEXT);
        textPaint.setTextSize(fontSize * 0.85f);
        float baseline = (top + bottom) / 2f
                - (textPaint.descent() + textPaint.ascent()) / 2f;
        canvas.drawText(EMPTY_PLACEHOLDER, (left + right) / 2f, baseline, textPaint);
        textPaint.setTextAlign(Paint.Align.LEFT);
    }
}
