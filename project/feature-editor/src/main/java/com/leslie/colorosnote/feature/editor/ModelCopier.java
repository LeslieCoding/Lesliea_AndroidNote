package com.leslie.colorosnote.feature.editor;

import com.leslie.colorosnote.core.common.IdGenerator;
import com.leslie.colorosnote.core.model.BrushPreset;
import com.leslie.colorosnote.core.model.ImageObject;
import com.leslie.colorosnote.core.model.Page;
import com.leslie.colorosnote.core.model.PageBackground;
import com.leslie.colorosnote.core.model.PageObject;
import com.leslie.colorosnote.core.model.ShapeObject;
import com.leslie.colorosnote.core.model.StickerObject;
import com.leslie.colorosnote.core.model.Stroke;
import com.leslie.colorosnote.core.model.StrokePoint;
import com.leslie.colorosnote.core.model.TapeObject;
import com.leslie.colorosnote.core.model.TextBoxObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Manual deep copies of model objects (clipboard, page duplication). Gson is an
 * internal dependency of core-storage and not on this module's compile
 * classpath, so copies are written out field by field.
 */
public final class ModelCopier {

    private ModelCopier() {
    }

    /** Deep copy with a fresh stroke id. */
    public static Stroke copyStroke(Stroke src) {
        Stroke c = new Stroke();
        c.id = IdGenerator.newId();
        c.brushPresetId = src.brushPresetId;
        c.brushPreset = src.brushPreset != null ? copyPreset(src.brushPreset) : null;
        c.color = src.color;
        c.zIndex = src.zIndex;
        c.createdAtMs = src.createdAtMs;
        c.strokeStartAudioTimeMs = src.strokeStartAudioTimeMs;
        c.strokeEndAudioTimeMs = src.strokeEndAudioTimeMs;
        c.layer = src.layer;
        c.points = new ArrayList<>(src.points.size());
        for (StrokePoint p : src.points) {
            c.points.add(copyPoint(p));
        }
        return c;
    }

    public static BrushPreset copyPreset(BrushPreset src) {
        BrushPreset c = new BrushPreset();
        c.id = src.id;
        c.name = src.name;
        c.brushType = src.brushType;
        c.baseWidth = src.baseWidth;
        c.minWidth = src.minWidth;
        c.maxWidth = src.maxWidth;
        c.opacity = src.opacity;
        c.color = src.color;
        c.pressureEnabled = src.pressureEnabled;
        c.pressureCurve = src.pressureCurve;
        c.customCurvePoints = new ArrayList<>(src.customCurvePoints);
        c.pressureToWidth = src.pressureToWidth;
        c.pressureToOpacity = src.pressureToOpacity;
        c.velocityToWidth = src.velocityToWidth;
        c.tiltEnabled = src.tiltEnabled;
        c.tiltToWidth = src.tiltToWidth;
        c.tiltToOpacity = src.tiltToOpacity;
        c.smoothing = src.smoothing;
        c.stabilization = src.stabilization;
        c.taperStart = src.taperStart;
        c.taperEnd = src.taperEnd;
        c.textureId = src.textureId;
        c.blendMode = src.blendMode;
        return c;
    }

    /** Deep copy of a page object with a fresh id, dispatched on objectType. */
    public static PageObject copyObject(PageObject src) {
        PageObject c;
        if (src instanceof ImageObject) {
            ImageObject s = (ImageObject) src;
            ImageObject o = new ImageObject();
            o.resourceId = s.resourceId;
            o.opacity = s.opacity;
            c = o;
        } else if (src instanceof TextBoxObject) {
            TextBoxObject s = (TextBoxObject) src;
            TextBoxObject o = new TextBoxObject();
            o.text = s.text;
            o.fontSize = s.fontSize;
            o.color = s.color;
            o.bold = s.bold;
            o.italic = s.italic;
            o.underline = s.underline;
            o.alignment = s.alignment;
            o.lineSpacing = s.lineSpacing;
            o.backgroundColor = s.backgroundColor;
            o.borderColor = s.borderColor;
            o.autoHeight = s.autoHeight;
            c = o;
        } else if (src instanceof ShapeObject) {
            ShapeObject s = (ShapeObject) src;
            ShapeObject o = new ShapeObject();
            o.shapeType = s.shapeType;
            o.strokeColor = s.strokeColor;
            o.fillColor = s.fillColor;
            o.strokeWidth = s.strokeWidth;
            o.points = new ArrayList<>(s.points);
            c = o;
        } else if (src instanceof StickerObject) {
            StickerObject s = (StickerObject) src;
            StickerObject o = new StickerObject();
            o.stickerId = s.stickerId;
            o.resourceId = s.resourceId;
            c = o;
        } else if (src instanceof TapeObject) {
            TapeObject s = (TapeObject) src;
            TapeObject o = new TapeObject();
            o.path = new ArrayList<>(s.path);
            o.rect = s.rect != null ? s.rect.clone() : null;
            o.width = s.width;
            o.color = s.color;
            o.pattern = s.pattern;
            o.isRevealed = s.isRevealed;
            o.revealMode = s.revealMode;
            c = o;
        } else {
            return null; // forward compat: unknown subclass from a newer module
        }
        c.id = IdGenerator.newId();
        c.zIndex = src.zIndex;
        c.transform = src.transform.clone();
        c.locked = src.locked;
        c.bounds = src.bounds.clone();
        return c;
    }

    /** Deep copy of a page (fresh ids, empty index — caller re-indexes). */
    public static Page copyPage(Page src) {
        Page c = new Page();
        c.id = IdGenerator.newId();
        c.width = src.width;
        c.height = src.height;
        c.background = copyBackground(src.background);
        for (Stroke s : src.strokes) {
            c.strokes.add(copyStroke(s));
        }
        for (PageObject o : src.objects) {
            PageObject copy = copyObject(o);
            if (copy != null) {
                c.objects.add(copy);
            }
        }
        c.title = src.title;
        c.bookmarked = src.bookmarked;
        long now = System.currentTimeMillis();
        c.createdAt = now;
        c.updatedAt = now;
        return c;
    }

    public static PageBackground copyBackground(PageBackground src) {
        PageBackground c = new PageBackground();
        if (src == null) {
            return c;
        }
        c.type = src.type;
        c.pdfPageIndex = src.pdfPageIndex;
        c.resourceId = src.resourceId;
        c.templateParams = new HashMap<>(src.templateParams);
        return c;
    }

    private static StrokePoint copyPoint(StrokePoint p) {
        StrokePoint c = new StrokePoint();
        c.x = p.x;
        c.y = p.y;
        c.timestampMs = p.timestampMs;
        c.pressure = p.pressure;
        c.tilt = p.tilt;
        c.orientation = p.orientation;
        c.toolType = p.toolType;
        c.pointerId = p.pointerId;
        c.velocityX = p.velocityX;
        c.velocityY = p.velocityY;
        return c;
    }

    /** Copies a list of strokes, generating fresh ids. */
    public static List<Stroke> copyStrokes(List<Stroke> strokes) {
        List<Stroke> out = new ArrayList<>(strokes.size());
        for (Stroke s : strokes) {
            out.add(copyStroke(s));
        }
        return out;
    }
}
