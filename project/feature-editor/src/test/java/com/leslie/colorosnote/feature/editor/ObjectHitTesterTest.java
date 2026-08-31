package com.leslie.colorosnote.feature.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.leslie.colorosnote.core.model.ImageObject;
import com.leslie.colorosnote.core.model.PageObject;
import com.leslie.colorosnote.core.model.TapeObject;
import com.leslie.colorosnote.core.model.TextBoxObject;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Pure-JVM tests for object hit-testing (spec 16/19). */
public class ObjectHitTesterTest {

    private static TextBoxObject box(float l, float t, float r, float b, int zIndex) {
        TextBoxObject o = new TextBoxObject();
        o.bounds = new float[]{l, t, r, b};
        o.zIndex = zIndex;
        return o;
    }

    @Test
    public void topmostWinsByZIndex() {
        TextBoxObject low = box(0f, 0f, 100f, 100f, 1);
        TextBoxObject high = box(0f, 0f, 100f, 100f, 5);
        List<PageObject> objects = Arrays.asList(low, high);
        assertSame(high, ObjectHitTester.topmostAt(objects, 50f, 50f, 0f));
        assertSame(high, ObjectHitTester.topmostAt(objects, 50f, 50f, 0f, true));
    }

    @Test
    public void missReturnsNull() {
        List<PageObject> objects = new ArrayList<>();
        objects.add(box(0f, 0f, 100f, 100f, 1));
        assertNull(ObjectHitTester.topmostAt(objects, 150f, 50f, 0f));
    }

    @Test
    public void translatedObjectHitAtNewLocation() {
        TextBoxObject o = box(0f, 0f, 100f, 100f, 1);
        // translate by (200, 300)
        o.transform = new float[]{1f, 0f, 200f, 0f, 1f, 300f, 0f, 0f, 1f};
        List<PageObject> objects = new ArrayList<>();
        objects.add(o);
        assertNull(ObjectHitTester.topmostAt(objects, 50f, 50f, 0f));
        assertSame(o, ObjectHitTester.topmostAt(objects, 250f, 350f, 0f));
    }

    @Test
    public void rotatedObjectHitTestingUsesLocalSpace() {
        TextBoxObject o = box(-50f, -10f, 50f, 10f, 1);
        // 90-degree rotation around origin: local +x becomes world -y... i.e.
        // world (0, 40) maps back to local (40, 0) inside the strip.
        o.transform = new float[]{0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f};
        List<PageObject> objects = new ArrayList<>();
        objects.add(o);
        assertSame(o, ObjectHitTester.topmostAt(objects, 0f, 40f, 0f));
        assertNull(ObjectHitTester.topmostAt(objects, 40f, 0f, 0f));
    }

    @Test
    public void lockedObjectsSkippedUnlessIncluded() {
        TextBoxObject o = box(0f, 0f, 100f, 100f, 1);
        o.locked = true;
        List<PageObject> objects = new ArrayList<>();
        objects.add(o);
        assertNull(ObjectHitTester.topmostAt(objects, 50f, 50f, 0f));
        assertSame(o, ObjectHitTester.topmostAt(objects, 50f, 50f, 0f, true));
    }

    @Test
    public void tapePathHitWithinHalfWidth() {
        TapeObject tape = new TapeObject();
        tape.path = new ArrayList<>(Arrays.asList(0f, 0f, 100f, 0f));
        tape.width = 40f;
        tape.bounds = TapeGeometry.pathBounds(tape.path, tape.width);
        tape.zIndex = 1;
        List<PageObject> objects = new ArrayList<>();
        objects.add(tape);
        // 15px off the centerline is inside the 20px half-width.
        assertSame(tape, ObjectHitTester.topmostAt(objects, 50f, 15f, 0f));
        // 30px off is outside half-width with zero slop.
        assertNull(ObjectHitTester.topmostAt(objects, 50f, 30f, 0f));
        // Slop extends the hit area.
        assertSame(tape, ObjectHitTester.topmostAt(objects, 50f, 30f, 12f));
    }

    @Test
    public void tapeRectHit() {
        TapeObject tape = new TapeObject();
        tape.rect = new float[]{10f, 10f, 210f, 50f};
        tape.bounds = tape.rect.clone();
        List<PageObject> objects = new ArrayList<>();
        objects.add(tape);
        assertSame(tape, ObjectHitTester.topmostAt(objects, 100f, 30f, 0f));
        assertNull(ObjectHitTester.topmostAt(objects, 100f, 60f, 0f));
    }

    @Test
    public void imageBoundsHit() {
        ImageObject image = new ImageObject();
        image.bounds = new float[]{0f, 0f, 200f, 150f};
        List<PageObject> objects = new ArrayList<>();
        objects.add(image);
        assertSame(image, ObjectHitTester.topmostAt(objects, 100f, 75f, 0f));
        assertNull(ObjectHitTester.topmostAt(objects, 100f, 200f, 0f));
    }
}
