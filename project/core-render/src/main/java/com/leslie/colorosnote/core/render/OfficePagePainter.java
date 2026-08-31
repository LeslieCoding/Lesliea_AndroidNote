package com.leslie.colorosnote.core.render;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import com.leslie.colorosnote.document.office.DrawCommand;
import com.leslie.colorosnote.document.office.ImportedDocument;
import com.leslie.colorosnote.document.office.ImportedPage;
import com.leslie.colorosnote.document.office.TextAlign;

import java.io.File;
import java.util.List;

/**
 * Draws an {@link ImportedPage}'s draw commands (96 dpi pixel space, y
 * downwards, text y at the baseline) onto a Bitmap. Android Canvas only — no
 * new dependencies.
 */
final class OfficePagePainter {

    private OfficePagePainter() {
    }

    /** Renders one page scaled so the output is exactly {@code widthPx} wide. */
    static Bitmap render(ImportedPage page, ImportedDocument document, int widthPx) {
        float scale = widthPx / (float) Math.max(1, page.getWidthPx());
        int heightPx = Math.max(1, Math.round(page.getHeightPx() * scale));
        Bitmap bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888);
        // Office pages are not opaque by definition; start from white paper.
        bmp.eraseColor(Color.WHITE);
        Canvas canvas = new Canvas(bmp);
        canvas.scale(scale, scale);
        drawCommands(canvas, page.getCommands(), document);
        return bmp;
    }

    static void drawCommands(Canvas canvas, List<DrawCommand> commands,
                             ImportedDocument document) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        RectF dst = new RectF();
        for (DrawCommand cmd : commands) {
            if (cmd instanceof DrawCommand.Text) {
                drawText(canvas, (DrawCommand.Text) cmd, paint);
            } else if (cmd instanceof DrawCommand.Rect) {
                drawRect(canvas, (DrawCommand.Rect) cmd, paint);
            } else if (cmd instanceof DrawCommand.Line) {
                drawLine(canvas, (DrawCommand.Line) cmd, paint);
            } else if (cmd instanceof DrawCommand.Image) {
                drawImage(canvas, (DrawCommand.Image) cmd, document, paint, dst);
            }
        }
    }

    private static void drawText(Canvas canvas, DrawCommand.Text t, Paint paint) {
        paint.reset();
        paint.setFlags(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(t.colorArgb);
        paint.setTextSize(t.textSizePx);
        int style = (t.bold ? Typeface.BOLD : 0) | (t.italic ? Typeface.ITALIC : 0);
        paint.setTypeface(Typeface.defaultFromStyle(style));
        paint.setTextAlign(t.align == TextAlign.CENTER ? Paint.Align.CENTER
                : t.align == TextAlign.RIGHT ? Paint.Align.RIGHT
                : Paint.Align.LEFT);
        canvas.drawText(t.text, t.x, t.y, paint);
    }

    private static void drawRect(Canvas canvas, DrawCommand.Rect r, Paint paint) {
        paint.reset();
        paint.setFlags(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(r.colorArgb);
        if (r.fill) {
            paint.setStyle(Paint.Style.FILL);
        } else {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(r.strokeWidthPx);
        }
        canvas.drawRect(r.x, r.y, r.x + r.width, r.y + r.height, paint);
    }

    private static void drawLine(Canvas canvas, DrawCommand.Line l, Paint paint) {
        paint.reset();
        paint.setFlags(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(l.colorArgb);
        paint.setStrokeWidth(l.strokeWidthPx);
        canvas.drawLine(l.x1, l.y1, l.x2, l.y2, paint);
    }

    private static void drawImage(Canvas canvas, DrawCommand.Image img,
                                  ImportedDocument document, Paint paint, RectF dst) {
        File file = document.resourceFile(img.resourceId);
        if (file == null) {
            return; // missing resource: leave the placeholder area empty
        }
        Bitmap bmp = android.graphics.BitmapFactory.decodeFile(file.getPath());
        if (bmp == null) {
            return;
        }
        paint.reset();
        paint.setFlags(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        dst.set(img.x, img.y, img.x + img.width, img.y + img.height);
        canvas.drawBitmap(bmp, null, dst, paint);
        bmp.recycle();
    }
}
