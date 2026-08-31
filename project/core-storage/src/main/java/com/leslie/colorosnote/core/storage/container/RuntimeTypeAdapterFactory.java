package com.leslie.colorosnote.core.storage.container;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal self-contained RuntimeTypeAdapterFactory (no gson-extras dependency):
 * polymorphic (de)serialization keyed on a discriminator field that lives
 * inside the JSON object itself.
 *
 * <p>Unknown labels deserialize to {@code null} (lenient forward compat — a
 * newer app version's object type must not break opening the document; callers
 * filter nulls). Unknown labels on write throw, since persisting an
 * unregistered type would silently lose data.
 */
public final class RuntimeTypeAdapterFactory<T> implements TypeAdapterFactory {

    private final Class<?> baseType;
    private final String typeFieldName;
    private final Map<String, Class<?>> labelToSubtype = new LinkedHashMap<>();
    private final Map<Class<?>, String> subtypeToLabel = new LinkedHashMap<>();

    private RuntimeTypeAdapterFactory(Class<?> baseType, String typeFieldName) {
        if (baseType == null || typeFieldName == null) {
            throw new NullPointerException();
        }
        this.baseType = baseType;
        this.typeFieldName = typeFieldName;
    }

    public static <T> RuntimeTypeAdapterFactory<T> of(Class<T> baseType, String typeFieldName) {
        return new RuntimeTypeAdapterFactory<>(baseType, typeFieldName);
    }

    public RuntimeTypeAdapterFactory<T> registerSubtype(Class<? extends T> subtype, String label) {
        if (subtype == null || label == null) {
            throw new NullPointerException();
        }
        if (labelToSubtype.containsKey(label) || subtypeToLabel.containsKey(subtype)) {
            throw new IllegalArgumentException("Subtype/label already registered: " + label);
        }
        labelToSubtype.put(label, subtype);
        subtypeToLabel.put(subtype, label);
        return this;
    }

    @Override
    public <R> TypeAdapter<R> create(Gson gson, TypeToken<R> type) {
        if (!baseType.isAssignableFrom(type.getRawType())) {
            return null;
        }
        Map<String, TypeAdapter<?>> labelToDelegate = new LinkedHashMap<>();
        Map<Class<?>, TypeAdapter<?>> subtypeToDelegate = new LinkedHashMap<>();
        for (Map.Entry<String, Class<?>> e : labelToSubtype.entrySet()) {
            // getDelegateAdapter skips this factory to avoid infinite recursion.
            TypeAdapter<?> delegate = gson.getDelegateAdapter(this, TypeToken.get(e.getValue()));
            labelToDelegate.put(e.getKey(), delegate);
            subtypeToDelegate.put(e.getValue(), delegate);
        }
        return new TypeAdapter<R>() {
            @Override
            public void write(JsonWriter out, R value) throws IOException {
                if (value == null) {
                    out.nullValue();
                    return;
                }
                Class<?> srcClass = value.getClass();
                String label = subtypeToLabel.get(srcClass);
                @SuppressWarnings("unchecked")
                TypeAdapter<R> delegate = (TypeAdapter<R>) subtypeToDelegate.get(srcClass);
                if (delegate == null || label == null) {
                    throw new JsonParseException(
                            "Unregistered subtype of " + baseType.getName() + ": " + srcClass.getName());
                }
                JsonObject jsonObject = delegate.toJsonTree(value).getAsJsonObject();
                if (!jsonObject.has(typeFieldName)) {
                    jsonObject.addProperty(typeFieldName, label);
                }
                gson.toJson(jsonObject, out);
            }

            @Override
            public R read(JsonReader in) throws IOException {
                JsonElement jsonElement = JsonParser.parseReader(in);
                if (jsonElement == null || !jsonElement.isJsonObject()) {
                    return null;
                }
                JsonObject jsonObject = jsonElement.getAsJsonObject();
                JsonElement labelElement = jsonObject.get(typeFieldName);
                String label = labelElement == null || !labelElement.isJsonPrimitive()
                        ? null : labelElement.getAsString();
                TypeAdapter<?> delegate = label == null ? null : labelToDelegate.get(label);
                if (delegate == null) {
                    // Lenient: unknown/absent discriminator -> null, caller filters.
                    return null;
                }
                @SuppressWarnings("unchecked")
                R result = (R) delegate.fromJsonTree(jsonObject);
                return result;
            }
        }.nullSafe();
    }
}
