package com.leslie.colorosnote.feature.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;

import com.leslie.colorosnote.core.model.BrushPreset;
import com.leslie.colorosnote.core.model.Page;
import com.leslie.colorosnote.core.model.PageBackground;
import com.leslie.colorosnote.core.model.Stroke;
import com.leslie.colorosnote.core.model.StrokePoint;
import com.leslie.colorosnote.core.model.TextBoxObject;

import org.junit.Test;

/** Pure-JVM tests for manual model deep copies (clipboard / duplicate page). */
public class ModelCopierTest {

    @Test
    public void copyStrokeIsDeepWithFreshId() {
        Stroke src = new Stroke();
        src.brushPreset = BrushPreset.fountainPen();
        src.brushPresetId = src.brushPreset.id;
        src.color = 0xFF112233;
        src.points.add(new StrokePoint(1f, 2f, 3L, 0.5f));
        src.points.add(new StrokePoint(4f, 5f, 6L, 0.7f));

        Stroke copy = ModelCopier.copyStroke(src);
        assertNotEquals(src.id, copy.id);
        assertNotSame(src.points, copy.points);
        assertNotSame(src.brushPreset, copy.brushPreset);
        assertEquals(src.color, copy.color);
        assertEquals(src.points.size(), copy.points.size());

        // Mutating the source must not leak into the copy.
        src.points.get(0).x = 99f;
        src.brushPreset.baseWidth = 99f;
        assertEquals(1f, copy.points.get(0).x, 1e-6f);
        assertEquals(3f, copy.brushPreset.baseWidth, 1e-6f);
    }

    @Test
    public void copyPageRegeneratesIdsAndCopiesContent() {
        Page src = new Page();
        src.background = PageBackground.ofType(PageBackground.Type.DOT);
        src.background.templateParams.put("dotSpacing", "24");
        src.title = "第 1 页";
        src.bookmarked = true;
        Stroke s = new Stroke();
        s.points.add(new StrokePoint(0f, 0f, 0L, 1f));
        src.strokes.add(s);
        TextBoxObject box = new TextBoxObject();
        box.text = "hello";
        box.bounds = new float[]{0f, 0f, 50f, 20f};
        src.objects.add(box);

        Page copy = ModelCopier.copyPage(src);
        assertNotEquals(src.id, copy.id);
        assertEquals(PageBackground.Type.DOT, copy.background.type);
        assertEquals("24", copy.background.templateParams.get("dotSpacing"));
        assertEquals("第 1 页", copy.title);
        assertEquals(1, copy.strokes.size());
        assertNotEquals(s.id, copy.strokes.get(0).id);
        assertEquals(1, copy.objects.size());
        TextBoxObject copiedBox = (TextBoxObject) copy.objects.get(0);
        assertNotEquals(box.id, copiedBox.id);
        assertEquals("hello", copiedBox.text);
        assertNotSame(box.bounds, copiedBox.bounds);
    }
}
