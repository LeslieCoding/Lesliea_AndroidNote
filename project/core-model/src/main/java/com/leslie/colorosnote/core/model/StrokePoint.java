package com.leslie.colorosnote.core.model;

import com.google.gson.annotations.SerializedName;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A single ink input sample. All values are raw MotionEvent-derived data;
 * pressure is normalized to [0,1] by the ink engine when real pressure exists.
 *
 * <p>Supports a compact versioned binary encoding (see {@link #encodeList} /
 * {@link #decodeList}) used for the strokes/*.bin payloads inside .inknote files.
 */
public final class StrokePoint {

    /** Binary format version written as the first byte of every encoded blob. */
    public static final byte BINARY_VERSION = 1;

    @SerializedName("x")
    public float x;
    @SerializedName("y")
    public float y;
    @SerializedName("timestampMs")
    public long timestampMs;
    @SerializedName("pressure")
    public float pressure;
    /** Stylus tilt in radians (MotionEvent#getAxisValue AXIS_TILT), 0 when unsupported. */
    @SerializedName("tilt")
    public float tilt;
    /** Stylus orientation/azimuth in radians, 0 when unsupported. */
    @SerializedName("orientation")
    public float orientation;
    /** MotionEvent tool type (TOOL_TYPE_FINGER / TOOL_TYPE_STYLUS / ...). */
    @SerializedName("toolType")
    public int toolType;
    @SerializedName("pointerId")
    public int pointerId;
    @SerializedName("velocityX")
    public float velocityX;
    @SerializedName("velocityY")
    public float velocityY;

    public StrokePoint() {
    }

    public StrokePoint(float x, float y, long timestampMs, float pressure) {
        this.x = x;
        this.y = y;
        this.timestampMs = timestampMs;
        this.pressure = pressure;
    }

    // ------------------------------------------------------------------
    // Compact binary codec
    // ------------------------------------------------------------------

    /** Encodes a single point (version byte + one record). */
    public byte[] encodeToBytes() {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(48);
        DataOutputStream out = new DataOutputStream(bos);
        try {
            out.writeByte(BINARY_VERSION);
            writeRecord(out, this);
            out.flush();
        } catch (IOException e) {
            throw new IllegalStateException("ByteArrayOutputStream cannot fail", e);
        }
        return bos.toByteArray();
    }

    /** Decodes a blob produced by {@link #encodeToBytes()}. */
    public static StrokePoint decodeFromBytes(byte[] data) {
        List<StrokePoint> points = decodeList(data, 1);
        if (points.isEmpty()) {
            throw new IllegalArgumentException("No point record in blob");
        }
        return points.get(0);
    }

    /** Encodes a point list: [version byte][int count][records...]. */
    public static byte[] encodeList(List<StrokePoint> points) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(8 + points.size() * 40);
        DataOutputStream out = new DataOutputStream(bos);
        try {
            out.writeByte(BINARY_VERSION);
            out.writeInt(points.size());
            for (StrokePoint p : points) {
                writeRecord(out, p);
            }
            out.flush();
        } catch (IOException e) {
            throw new IllegalStateException("ByteArrayOutputStream cannot fail", e);
        }
        return bos.toByteArray();
    }

    /** Decodes a blob produced by {@link #encodeList(List)}. */
    public static List<StrokePoint> decodeList(byte[] data) {
        return decodeList(data, Integer.MAX_VALUE);
    }

    private static List<StrokePoint> decodeList(byte[] data, int maxPoints) {
        List<StrokePoint> points = new ArrayList<>();
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        try {
            byte version = in.readByte();
            if (version != BINARY_VERSION) {
                throw new IllegalArgumentException("Unsupported StrokePoint binary version: " + version);
            }
            int count;
            if (maxPoints == 1) {
                count = 1; // single-point blob has no count field
            } else {
                count = in.readInt();
                if (count < 0 || count > maxPoints) {
                    throw new IllegalArgumentException("Invalid point count: " + count);
                }
            }
            for (int i = 0; i < count; i++) {
                points.add(readRecord(in));
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Corrupt StrokePoint blob", e);
        }
        return points;
    }

    private static void writeRecord(DataOutputStream out, StrokePoint p) throws IOException {
        out.writeFloat(p.x);
        out.writeFloat(p.y);
        out.writeLong(p.timestampMs);
        out.writeFloat(p.pressure);
        out.writeFloat(p.tilt);
        out.writeFloat(p.orientation);
        out.writeInt(p.toolType);
        out.writeInt(p.pointerId);
        out.writeFloat(p.velocityX);
        out.writeFloat(p.velocityY);
    }

    private static StrokePoint readRecord(DataInputStream in) throws IOException {
        StrokePoint p = new StrokePoint();
        p.x = in.readFloat();
        p.y = in.readFloat();
        p.timestampMs = in.readLong();
        p.pressure = in.readFloat();
        p.tilt = in.readFloat();
        p.orientation = in.readFloat();
        p.toolType = in.readInt();
        p.pointerId = in.readInt();
        p.velocityX = in.readFloat();
        p.velocityY = in.readFloat();
        return p;
    }
}
