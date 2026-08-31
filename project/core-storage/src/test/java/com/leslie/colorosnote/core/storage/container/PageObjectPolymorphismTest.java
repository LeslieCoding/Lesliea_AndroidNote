package com.leslie.colorosnote.core.storage.container;

import com.google.gson.Gson;
import com.leslie.colorosnote.core.model.ImageObject;
import com.leslie.colorosnote.core.model.Page;
import com.leslie.colorosnote.core.model.ShapeObject;
import com.leslie.colorosnote.core.model.TextBoxObject;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Polymorphic PageObject (de)serialization keyed on the objectType field. */
public class PageObjectPolymorphismTest {

    private final Gson gson = InknoteGson.create();

    @Test
    public void subtypeRoundTripPreservesConcreteTypes() {
        Page page = new Page();
        TextBoxObject text = new TextBoxObject();
        text.text = "abc";
        ImageObject image = new ImageObject();
        image.resourceId = "r1";
        ShapeObject shape = new ShapeObject();
        shape.shapeType = ShapeObject.ShapeType.ELLIPSE;
        page.objects.add(text);
        page.objects.add(image);
        page.objects.add(shape);

        Page restored = gson.fromJson(gson.toJson(page), Page.class);
        assertEquals(3, restored.objects.size());
        assertTrue(restored.objects.get(0) instanceof TextBoxObject);
        assertEquals("abc", ((TextBoxObject) restored.objects.get(0)).text);
        assertTrue(restored.objects.get(1) instanceof ImageObject);
        assertEquals("r1", ((ImageObject) restored.objects.get(1)).resourceId);
        assertTrue(restored.objects.get(2) instanceof ShapeObject);
        assertEquals(ShapeObject.ShapeType.ELLIPSE,
                ((ShapeObject) restored.objects.get(2)).shapeType);
    }

    @Test
    public void discriminatorIsWritten() {
        TextBoxObject text = new TextBoxObject();
        String json = gson.toJson(text);
        assertTrue(json.contains("\"objectType\":\"TEXT_BOX\""));
    }

    @Test
    public void unknownSubtypeReadsAsNullForLeniency() {
        // An object type written by a future app version must not break parsing.
        String json = "{\"id\":\"p1\",\"objects\":["
                + "{\"objectType\":\"FUTURE_WIDGET\",\"id\":\"o1\"}]}";
        Page restored = gson.fromJson(json, Page.class);
        assertEquals(1, restored.objects.size());
        assertNull(restored.objects.get(0)); // InknoteReader filters these out
    }

    @Test
    public void unknownFieldsAreTolerated() {
        // Extra fields added by a newer schema are skipped, not fatal.
        String json = "{\"objectType\":\"TEXT_BOX\",\"id\":\"o1\",\"text\":\"hi\","
                + "\"futureField\":{\"nested\":true}}";
        TextBoxObject restored = (TextBoxObject) gson.fromJson(json,
                com.leslie.colorosnote.core.model.PageObject.class);
        assertEquals("hi", restored.text);
        assertEquals("o1", restored.id);
    }
}
