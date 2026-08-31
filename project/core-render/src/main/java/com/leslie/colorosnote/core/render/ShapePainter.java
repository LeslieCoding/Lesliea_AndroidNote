package com.leslie.colorosnote.core.render;

import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import com.leslie.colorosnote.core.model.PageObject;
import com.leslie.colorosnote.core.model.ShapeObject;

import java.util.List;

/**
 * Default painter for {@link ShapeObject}: basic coverage of the shape types
 * (line/arrow family, rect, ellipse, triangle, diamond, polygon). Draws in
 * local coordinates using the shape's bounds and defining points.
 */
public final class ShapePainter implements ObjectPainter {

    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    public ShapePainter() {
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        fillPaint.setStyle(Paint.Style.FILL);
    }

    @Override
    public boolean supports(PageObject object) {
        return object instanceof ShapeObject;
    }

    @Override
    public void paint(Canvas canvas, PageObject object) {
        ShapeObject shape = (ShapeObject) object;
        if (shape.shapeType == null) {
            // Lenient read of a shape type from a newer app version.
            return;
        }
        strokePaint.setColor(shape.strokeColor);
        strokePaint.setStrokeWidth(shape.strokeWidth);
        float l = shape.bounds[0];
        float t = shape.bounds[1];
        float r = shape.bounds[2];
        float b = shape.bounds[3];

        switch (shape.shapeType) {
            case RECT:
                drawWithOptionalFill(canvas, shape, new RectF(l, t, r, b), true);
                break;
            case ROUNDED_RECT: {
                RectF box = new RectF(l, t, r, b);
                float radius = Math.min(box.width(), box.height()) * 0.14f;
                if (shape.fillColor != null) {
                    fillPaint.setColor(shape.fillColor);
                    canvas.drawRoundRect(box, radius, radius, fillPaint);
                }
                canvas.drawRoundRect(box, radius, radius, strokePaint);
                break;
            }
            case ELLIPSE:
                drawWithOptionalFill(canvas, shape, new RectF(l, t, r, b), false);
                break;
            case LINE:
            case DASHED_LINE:
            case ARROW:
            case DOUBLE_ARROW:
                drawLineFamily(canvas, shape);
                break;
            case TRIANGLE:
                drawPolygon(canvas, shape, new float[]{
                        (l + r) / 2f, t, r, b, l, b});
                break;
            case DIAMOND:
                drawPolygon(canvas, shape, new float[]{
                        (l + r) / 2f, t, r, (t + b) / 2f,
                        (l + r) / 2f, b, l, (t + b) / 2f});
                break;
            case POLYGON:
            default: {
                List<Float> pts = shape.points;
                if (pts.size() >= 6) {
                    float[] flat = new float[pts.size()];
                    for (int i = 0; i < pts.size(); i++) {
                        flat[i] = pts.get(i);
                    }
                    drawPolygon(canvas, shape, flat);
                }
                break;
            }
        }
    }

    private void drawWithOptionalFill(Canvas canvas, ShapeObject shape, RectF r, boolean rect) {
        if (shape.fillColor != null) {
            fillPaint.setColor(shape.fillColor);
            if (rect) {
                canvas.drawRect(r, fillPaint);
            } else {
                canvas.drawOval(r, fillPaint);
            }
        }
        if (rect) {
            canvas.drawRect(r, strokePaint);
        } else {
            canvas.drawOval(r, strokePaint);
        }
    }

    private void drawLineFamily(Canvas canvas, ShapeObject shape) {
        List<Float> pts = shape.points;
        if (pts.size() < 4) {
            return;
        }
        float x1 = pts.get(0);
        float y1 = pts.get(1);
        float x2 = pts.get(2);
        float y2 = pts.get(3);
        if (shape.shapeType == ShapeObject.ShapeType.DASHED_LINE) {
            strokePaint.setPathEffect(new DashPathEffect(
                    new float[]{Math.max(6f, shape.strokeWidth * 4f),
                            Math.max(4f, shape.strokeWidth * 2.5f)}, 0f));
        }
        canvas.drawLine(x1, y1, x2, y2, strokePaint);
        if (shape.shapeType == ShapeObject.ShapeType.ARROW
                || shape.shapeType == ShapeObject.ShapeType.DOUBLE_ARROW) {
            drawArrowHead(canvas, x1, y1, x2, y2, shape.strokeWidth);
        }
        if (shape.shapeType == ShapeObject.ShapeType.DOUBLE_ARROW) {
            drawArrowHead(canvas, x2, y2, x1, y1, shape.strokeWidth);
        }
        strokePaint.setPathEffect(null);
    }

    private void drawArrowHead(Canvas canvas, float fromX, float fromY,
                               float toX, float toY, float strokeWidth) {
        float headLen = Math.max(8f, strokeWidth * 4f);
        double angle = Math.atan2(toY - fromY, toX - fromX);
        double spread = Math.PI / 7.0;
        path.reset();
        path.moveTo(toX, toY);
        path.lineTo((float) (toX - headLen * Math.cos(angle - spread)),
                (float) (toY - headLen * Math.sin(angle - spread)));
        path.moveTo(toX, toY);
        path.lineTo((float) (toX - headLen * Math.cos(angle + spread)),
                (float) (toY - headLen * Math.sin(angle + spread)));
        canvas.drawPath(path, strokePaint);
    }

    private void drawPolygon(Canvas canvas, ShapeObject shape, float[] vertices) {
        path.reset();
        path.moveTo(vertices[0], vertices[1]);
        for (int i = 2; i + 1 < vertices.length; i += 2) {
            path.lineTo(vertices[i], vertices[i + 1]);
        }
        path.close();
        if (shape.fillColor != null) {
            fillPaint.setColor(shape.fillColor);
            canvas.drawPath(path, fillPaint);
        }
        canvas.drawPath(path, strokePaint);
    }
}
