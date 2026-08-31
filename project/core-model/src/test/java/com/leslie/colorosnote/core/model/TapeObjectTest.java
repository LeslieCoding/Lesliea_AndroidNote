package com.leslie.colorosnote.core.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.google.gson.Gson;

import org.junit.Test;

/**
 * Serialization contract for the extended object enums: new values round-trip
 * and unknown values (written by a newer app version) degrade leniently to
 * null instead of crashing the reader.
 */
public class TapeObjectTest {

    private final Gson gson = new Gson();

    @Test
    public void everyTapePatternRoundTrips() {
        for (TapeObject.TapePattern pattern : TapeObject.TapePattern.values()) {
            TapeObject tape = new TapeObject();
            tape.pattern = pattern;
            TapeObject read = gson.fromJson(gson.toJson(tape), TapeObject.class);
            assertEquals(pattern, read.pattern);
        }
    }

    @Test
    public void tapePattern_hasExtendedWashiSet() {
        // SOLID/STRIPES/DOTS + the v1.3.0 washi additions.
        assertEquals(7, TapeObject.TapePattern.values().length);
        assertEquals(TapeObject.TapePattern.GRID,
                TapeObject.TapePattern.valueOf("GRID"));
        assertEquals(TapeObject.TapePattern.CROSSHATCH,
                TapeObject.TapePattern.valueOf("CROSSHATCH"));
        assertEquals(TapeObject.TapePattern.WAVES,
                TapeObject.TapePattern.valueOf("WAVES"));
        assertEquals(TapeObject.TapePattern.HEARTS,
                TapeObject.TapePattern.valueOf("HEARTS"));
    }

    @Test
    public void unknownTapePatternReadsAsNull() {
        TapeObject read = gson.fromJson("{\"pattern\":\"FUTURE_PATTERN\"}", TapeObject.class);
        assertNull(read.pattern); // painters treat null as SOLID
    }

    @Test
    public void roundedRectShapeTypeRoundTrips() {
        ShapeObject shape = new ShapeObject();
        shape.shapeType = ShapeObject.ShapeType.ROUNDED_RECT;
        ShapeObject read = gson.fromJson(gson.toJson(shape), ShapeObject.class);
        assertEquals(ShapeObject.ShapeType.ROUNDED_RECT, read.shapeType);
    }

    @Test
    public void unknownShapeTypeReadsAsNull() {
        ShapeObject read = gson.fromJson("{\"shapeType\":\"FUTURE_SHAPE\"}", ShapeObject.class);
        assertNull(read.shapeType); // painter treats null as "skip"
    }
}
