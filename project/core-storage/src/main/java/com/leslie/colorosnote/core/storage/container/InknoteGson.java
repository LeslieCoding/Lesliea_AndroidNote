package com.leslie.colorosnote.core.storage.container;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.leslie.colorosnote.core.model.ImageObject;
import com.leslie.colorosnote.core.model.PageObject;
import com.leslie.colorosnote.core.model.ShapeObject;
import com.leslie.colorosnote.core.model.StickerObject;
import com.leslie.colorosnote.core.model.TapeObject;
import com.leslie.colorosnote.core.model.TextBoxObject;

/**
 * Shared Gson configuration for all .inknote JSON payloads.
 *
 * <ul>
 *   <li>Polymorphic {@link PageObject} handling keyed on {@code objectType}.</li>
 *   <li>Lenient by default: Gson ignores unknown JSON fields, so documents
 *       written by a newer schema still open (unknown fields are skipped on
 *       load, never fatal).</li>
 * </ul>
 *
 * Pure JVM (gson + core-model only); used by container and journal code.
 */
public final class InknoteGson {

    private static volatile Gson instance;

    private InknoteGson() {
    }

    public static Gson get() {
        if (instance == null) {
            synchronized (InknoteGson.class) {
                if (instance == null) {
                    instance = create();
                }
            }
        }
        return instance;
    }

    public static Gson create() {
        RuntimeTypeAdapterFactory<PageObject> pageObjectFactory =
                RuntimeTypeAdapterFactory.of(PageObject.class, "objectType")
                        .registerSubtype(ImageObject.class, PageObject.ObjectType.IMAGE.name())
                        .registerSubtype(TextBoxObject.class, PageObject.ObjectType.TEXT_BOX.name())
                        .registerSubtype(ShapeObject.class, PageObject.ObjectType.SHAPE.name())
                        .registerSubtype(StickerObject.class, PageObject.ObjectType.STICKER.name())
                        .registerSubtype(TapeObject.class, PageObject.ObjectType.TAPE.name());
        return new GsonBuilder()
                .registerTypeAdapterFactory(pageObjectFactory)
                .create();
    }
}
