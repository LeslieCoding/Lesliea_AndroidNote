package com.leslie.colorosnote.core.model;

import com.google.gson.annotations.SerializedName;
import com.leslie.colorosnote.core.common.IdGenerator;

import java.util.ArrayList;
import java.util.List;

/**
 * Serializable brush preset (requirement section 6). Every tool parameter lives
 * here instead of being hardcoded in UI. Widths are in logical page units.
 */
public final class BrushPreset {

    public enum BrushType {FOUNTAIN_PEN, CALLIGRAPHY_PEN, BALLPOINT, PENCIL, HIGHLIGHTER}

    public enum PressureCurve {SOFT, BALANCED, FIRM, CUSTOM}

    /** Porter-Duff-ish blend modes understood by the renderer. */
    public enum BlendMode {SRC_OVER, MULTIPLY, SCREEN, OVERLAY}

    /** Allowed number of control points for a CUSTOM pressure curve. */
    public static final int MIN_CUSTOM_CURVE_POINTS = 4;
    public static final int MAX_CUSTOM_CURVE_POINTS = 6;

    @SerializedName("id")
    public String id = IdGenerator.newId();
    @SerializedName("name")
    public String name = "";
    @SerializedName("brushType")
    public BrushType brushType = BrushType.FOUNTAIN_PEN;
    /** Style variant id within the brush type (e.g. fountain "ef"/"flex");
     *  empty = default/unnamed. */
    @SerializedName("styleId")
    public String styleId = "";

    @SerializedName("baseWidth")
    public float baseWidth = 3f;
    @SerializedName("minWidth")
    public float minWidth = 0.5f;
    @SerializedName("maxWidth")
    public float maxWidth = 8f;
    /** 0..1 */
    @SerializedName("opacity")
    public float opacity = 1f;
    /** ARGB color. */
    @SerializedName("color")
    public int color = 0xFF000000;

    @SerializedName("pressureEnabled")
    public boolean pressureEnabled = true;
    @SerializedName("pressureCurve")
    public PressureCurve pressureCurve = PressureCurve.BALANCED;
    /**
     * Custom pressure curve control points as flat {x0,y0,x1,y1,...} pairs with
     * x,y in [0,1]; only used when pressureCurve == CUSTOM. 4..6 points.
     */
    @SerializedName("customCurvePoints")
    public List<Float> customCurvePoints = new ArrayList<>();
    /** How strongly pressure scales width, 0..1 (1 = full range min..max). */
    @SerializedName("pressureToWidth")
    public float pressureToWidth = 1f;
    /** How strongly pressure scales opacity, 0..1. */
    @SerializedName("pressureToOpacity")
    public float pressureToOpacity = 0f;
    /** How strongly velocity counter-scales width (faster = thinner), 0..1. */
    @SerializedName("velocityToWidth")
    public float velocityToWidth = 0f;

    @SerializedName("tiltEnabled")
    public boolean tiltEnabled = false;
    @SerializedName("tiltToWidth")
    public float tiltToWidth = 0f;
    @SerializedName("tiltToOpacity")
    public float tiltToOpacity = 0f;

    /** EMA smoothing factor 0..1; higher = smoother but laggier. */
    @SerializedName("smoothing")
    public float smoothing = 0.2f;
    /** Pull-string stabilization 0..1; higher = stronger lag. */
    @SerializedName("stabilization")
    public float stabilization = 0f;
    /** Stroke start taper length as fraction of baseWidth, 0..1. */
    @SerializedName("taperStart")
    public float taperStart = 0f;
    /** Stroke end taper length as fraction of baseWidth, 0..1. */
    @SerializedName("taperEnd")
    public float taperEnd = 0f;

    /** Optional grain/texture resource id (e.g. pencil paper grain); null = none. */
    @SerializedName("textureId")
    public String textureId;
    @SerializedName("blendMode")
    public BlendMode blendMode = BlendMode.SRC_OVER;

    public BrushPreset() {
    }

    /** True when a CUSTOM curve has a valid number of control points. */
    public boolean hasValidCustomCurve() {
        int points = customCurvePoints.size() / 2;
        return pressureCurve != PressureCurve.CUSTOM
                || (points >= MIN_CUSTOM_CURVE_POINTS && points <= MAX_CUSTOM_CURVE_POINTS);
    }

    // ------------------------------------------------------------------
    // Default presets (recommended ranges from requirement 6.2-6.5)
    // ------------------------------------------------------------------

    /**
     * Fountain pen: pressure is first-class. Low pressure ~0.4x base width,
     * high pressure up to ~1.9x (v1.1.6: wider dynamic range for a real
     * ink-pen feel). SOFT curve keeps light strokes responsive; velocity
     * thinning plus eased tapers reproduce nib touch-down / lift-off.
     * v2.0.0: velocityToWidth 0.4 -> 0.5 — the v2.0.0 smoothstep^1.5 velocity
     * map keeps the mid range nearly penalty-free, so a deeper coefficient
     * only shows up in true flicks (expressive lift-off) without slimming
     * ordinary fast writing.
     */
    public static BrushPreset fountainPen() {
        BrushPreset p = new BrushPreset();
        p.brushType = BrushType.FOUNTAIN_PEN;
        p.name = "Fountain Pen";
        p.baseWidth = 3f;
        p.minWidth = 3f * 0.4f;
        p.maxWidth = 3f * 1.9f;
        p.pressureEnabled = true;
        p.pressureCurve = PressureCurve.SOFT;
        p.pressureToWidth = 1f;
        p.pressureToOpacity = 0f;
        p.velocityToWidth = 0.5f;
        p.smoothing = 0.2f;
        p.stabilization = 0.1f;
        p.taperStart = 0.15f;
        p.taperEnd = 0.5f;
        return p;
    }

    /**
     * Calligraphy pen: like the fountain pen, but the (simulated) nib orientation
     * participates in width computation via tilt/orientation sensitivity.
     */
    public static BrushPreset calligraphyPen() {
        BrushPreset p = fountainPen();
        p.id = IdGenerator.newId();
        p.brushType = BrushType.CALLIGRAPHY_PEN;
        p.name = "Calligraphy Pen";
        p.baseWidth = 4f;
        p.minWidth = 1f;
        p.maxWidth = 6.5f;
        p.tiltEnabled = true;
        p.tiltToWidth = 0.6f;
        p.pressureToWidth = 0.7f;
        p.smoothing = 0.25f;
        return p;
    }

    /**
     * Ballpoint: completely fixed line width — like a real ballpoint the ink
     * flow is constant, so pressure/velocity never change the width and there
     * is no start/end taper (round head, round tail). Matches the GoodNotes
     * ballpoint definition (not pressure-sensitive, fixed line width).
     * v1.4.16: removed the former 0.85x..1.2x pressure/velocity/taper response.
     * v2.0.0: smoothing 0.1 -> 0.2 — the retuned 1€ filter (dc=2Hz, beta=0.07)
     * denoises slow writing better at the same slider value, so a slightly
     * stronger default steadies ballpoint lines without adding lag.
     */
    public static BrushPreset ballpoint() {
        BrushPreset p = new BrushPreset();
        p.brushType = BrushType.BALLPOINT;
        p.name = "Ballpoint";
        p.baseWidth = 2.5f;
        p.minWidth = 2.5f;
        p.maxWidth = 2.5f;
        p.pressureEnabled = false;
        p.pressureCurve = PressureCurve.FIRM;
        p.pressureToWidth = 0f;
        p.pressureToOpacity = 0f;
        p.velocityToWidth = 0f;
        p.smoothing = 0.2f;
        p.stabilization = 0f;
        p.taperStart = 0f;
        p.taperEnd = 0f;
        return p;
    }

    /**
     * Pencil (HB): fixed line width with no taper; pressure maps to darkness
     * (opacity) only — light pressure draws pale grey, heavy pressure dark —
     * matching the Notability pencil model. Tilt adds side-shading darkness
     * but never width. Paper grain texture enabled.
     * v1.4.16: removed the former pressure/velocity/tilt width response.
     * v2.0.0: tiltToOpacity 0.3 -> 0.45 (side-shading darkening more
     * pronounced — the pencil's signature gesture) and smoothing 0.15 -> 0.2
     * (retuned 1€ filter denoises better at the same slider value).
     */
    public static BrushPreset pencilHb() {
        BrushPreset p = new BrushPreset();
        p.brushType = BrushType.PENCIL;
        p.name = "Pencil HB";
        p.baseWidth = 2f;
        p.minWidth = 2f;
        p.maxWidth = 2f;
        p.opacity = 0.85f;
        p.pressureEnabled = true;
        p.pressureCurve = PressureCurve.SOFT;
        p.pressureToWidth = 0f;
        p.pressureToOpacity = 0.8f;
        p.velocityToWidth = 0f;
        p.tiltEnabled = true;
        p.tiltToWidth = 0f;
        p.tiltToOpacity = 0.45f;
        p.smoothing = 0.2f;
        p.textureId = "texture_pencil_grain";
        return p;
    }

    /**
     * Highlighter: translucent, wide, MULTIPLY blend so overlapping passes and
     * underlying text/PDF content stay readable. Pressure off by default.
     * v2.0.0: smoothing 0.1 -> 0.15 (steadier edges with the retuned filter).
     */
    public static BrushPreset highlighter() {
        BrushPreset p = new BrushPreset();
        p.brushType = BrushType.HIGHLIGHTER;
        p.name = "Highlighter";
        p.baseWidth = 14f;
        p.minWidth = 14f;
        p.maxWidth = 14f;
        p.opacity = 0.4f;
        p.color = 0xFFFFF200; // translucent yellow applied via opacity
        p.pressureEnabled = false;
        p.pressureCurve = PressureCurve.BALANCED;
        p.pressureToWidth = 0f;
        p.pressureToOpacity = 0f;
        p.velocityToWidth = 0f;
        p.smoothing = 0.15f;
        p.stabilization = 0f;
        p.blendMode = BlendMode.MULTIPLY;
        return p;
    }

    /** All five default presets: fountain/calligraphy/ballpoint/pencil HB/highlighter. */
    public static List<BrushPreset> defaults() {
        List<BrushPreset> list = new ArrayList<>(5);
        list.add(fountainPen());
        list.add(calligraphyPen());
        list.add(ballpoint());
        list.add(pencilHb());
        list.add(highlighter());
        return list;
    }
}
