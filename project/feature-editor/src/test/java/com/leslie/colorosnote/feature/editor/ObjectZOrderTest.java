package com.leslie.colorosnote.feature.editor;

import static org.junit.Assert.assertEquals;

import com.leslie.colorosnote.core.model.PageObject;
import com.leslie.colorosnote.core.model.TextBoxObject;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Pure-JVM tests for 置顶/置底 zIndex computation (spec 18.1). */
public class ObjectZOrderTest {

    private static TextBoxObject withZ(int zIndex) {
        TextBoxObject o = new TextBoxObject();
        o.zIndex = zIndex;
        return o;
    }

    @Test
    public void frontIsMaxPlusOne() {
        List<PageObject> objects = Arrays.asList(withZ(-2), withZ(7), withZ(3));
        assertEquals(8, ObjectZOrder.frontZIndex(objects));
    }

    @Test
    public void backIsMinMinusOne() {
        List<PageObject> objects = Arrays.asList(withZ(-2), withZ(7), withZ(3));
        assertEquals(-3, ObjectZOrder.backZIndex(objects));
    }

    @Test
    public void emptyListStartsAroundZero() {
        List<PageObject> objects = new ArrayList<>();
        assertEquals(1, ObjectZOrder.frontZIndex(objects));
        assertEquals(-1, ObjectZOrder.backZIndex(objects));
    }
}
