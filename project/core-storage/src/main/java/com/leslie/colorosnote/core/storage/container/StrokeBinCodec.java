package com.leslie.colorosnote.core.storage.container;

import com.google.gson.Gson;
import com.leslie.colorosnote.core.model.Stroke;
import com.leslie.colorosnote.core.model.StrokePoint;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Binary codec for the strokes/{pageId}.bin payloads of a .inknote container.
 *
 * <p>Layout: [byte version][int strokeCount] then per stroke
 * [int metaLen][meta JSON bytes][int pointsLen][points blob]. The meta JSON is
 * the full {@link Stroke} minus its points; the points blob reuses the
 * versioned {@link StrokePoint#encodeList} format. Pure JVM.
 */
public final class StrokeBinCodec {

    public static final byte VERSION = 1;

    private StrokeBinCodec() {
    }

    public static byte[] encode(List<Stroke> strokes) {
        Gson gson = InknoteGson.get();
        ByteArrayOutputStream bos = new ByteArrayOutputStream(16 + strokes.size() * 256);
        DataOutputStream out = new DataOutputStream(bos);
        try {
            out.writeByte(VERSION);
            out.writeInt(strokes.size());
            for (Stroke stroke : strokes) {
                byte[] meta = gson.toJson(stripPoints(stroke)).getBytes(StandardCharsets.UTF_8);
                byte[] points = StrokePoint.encodeList(stroke.points);
                out.writeInt(meta.length);
                out.write(meta);
                out.writeInt(points.length);
                out.write(points);
            }
            out.flush();
        } catch (IOException e) {
            throw new IllegalStateException("ByteArrayOutputStream cannot fail", e);
        }
        return bos.toByteArray();
    }

    public static List<Stroke> decode(byte[] data) {
        Gson gson = InknoteGson.get();
        List<Stroke> strokes = new ArrayList<>();
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        try {
            byte version = in.readByte();
            if (version != VERSION) {
                throw new IllegalArgumentException("Unsupported strokes bin version: " + version);
            }
            int count = in.readInt();
            if (count < 0 || count > 1_000_000) {
                throw new IllegalArgumentException("Invalid stroke count: " + count);
            }
            for (int i = 0; i < count; i++) {
                int metaLen = in.readInt();
                if (metaLen < 0 || metaLen > 16 * 1024 * 1024) {
                    throw new IllegalArgumentException("Invalid stroke meta length: " + metaLen);
                }
                byte[] metaBytes = new byte[metaLen];
                in.readFully(metaBytes);
                int pointsLen = in.readInt();
                if (pointsLen < 0 || pointsLen > 256 * 1024 * 1024) {
                    throw new IllegalArgumentException("Invalid points length: " + pointsLen);
                }
                byte[] pointsBytes = new byte[pointsLen];
                in.readFully(pointsBytes);
                Stroke stroke = gson.fromJson(new String(metaBytes, StandardCharsets.UTF_8), Stroke.class);
                if (stroke == null) {
                    throw new IllegalArgumentException("Null stroke meta at index " + i);
                }
                stroke.points = StrokePoint.decodeList(pointsBytes);
                strokes.add(stroke);
            }
        } catch (EOFException e) {
            throw new IllegalArgumentException("Truncated strokes bin", e);
        } catch (IOException e) {
            throw new IllegalArgumentException("Corrupt strokes bin", e);
        }
        return strokes;
    }

    /** Shallow copy of the stroke with an empty point list for the JSON meta. */
    private static Stroke stripPoints(Stroke stroke) {
        Stroke meta = new Stroke();
        meta.id = stroke.id;
        meta.brushPresetId = stroke.brushPresetId;
        meta.brushPreset = stroke.brushPreset;
        meta.color = stroke.color;
        meta.zIndex = stroke.zIndex;
        meta.createdAtMs = stroke.createdAtMs;
        meta.strokeStartAudioTimeMs = stroke.strokeStartAudioTimeMs;
        meta.strokeEndAudioTimeMs = stroke.strokeEndAudioTimeMs;
        meta.layer = stroke.layer;
        return meta;
    }
}
