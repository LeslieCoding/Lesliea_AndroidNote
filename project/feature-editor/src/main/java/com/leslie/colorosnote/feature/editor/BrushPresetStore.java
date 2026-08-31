package com.leslie.colorosnote.feature.editor;

import android.content.Context;
import android.content.SharedPreferences;

import com.leslie.colorosnote.core.common.AppLogger;
import com.leslie.colorosnote.core.ink.EraserEngine;
import com.leslie.colorosnote.core.ink.LassoEngine;
import com.leslie.colorosnote.core.model.BrushPreset;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Per-tool brush preset persistence (SharedPreferences + JSON, spec: every pen
 * keeps its own preset). Uses org.json to avoid adding a Gson dependency to
 * this module; unknown/absent fields fall back to the built-in defaults.
 */
public final class BrushPresetStore {

    private static final String TAG = "BrushPresetStore";
    private static final String PREFS = "editor_tool_prefs";
    private static final String KEY_PRESET_PREFIX = "preset_";
    private static final String KEY_ERASER = "eraser_config";
    private static final String KEY_LASSO_MODE = "lasso_mode";
    /** Current preset defaults version (see presetFor). */
    private static final int PRESET_VERSION = 5;

    private final SharedPreferences prefs;

    public BrushPresetStore(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------
    // Brush presets
    // ------------------------------------------------------------------

    /** Loads the preset for a tool; defaults come from the BrushPreset factory. */
    public BrushPreset presetFor(EditorTool tool, String globalPressureCurve) {
        BrushPreset preset = defaultPreset(tool);
        // Stable per-tool id so strokes reference the same preset over time.
        preset.id = "builtin_" + tool.name().toLowerCase(Locale.US);
        applyGlobalCurve(preset, globalPressureCurve);

        String json = prefs.getString(KEY_PRESET_PREFIX + tool.name(), null);
        if (json == null) {
            return preset;
        }
        try {
            JSONObject o = new JSONObject(json);
            // Preset defaults version: stale entries are dropped so improved
            // factory defaults reach existing installs. v1.4.16 (version 3):
            // ballpoint/pencil defaults became fixed-width with no taper, so
            // their v2 presets (which encode the old taper behavior) are
            // dropped; other tools' defaults are unchanged, so their v2
            // presets still load. v1.4.21 (version 4): factory defaults are
            // unchanged from v3 — v3 presets load as-is; the newly persisted
            // tilt fields simply fall back to factory defaults when absent.
            // v2.0.0 (version 5): factory defaults changed (fountain
            // velocityToWidth 0.4->0.5, ballpoint smoothing 0.1->0.2, pencil
            // tiltToOpacity 0.3->0.45 + smoothing 0.15->0.2, highlighter
            // smoothing 0.1->0.15) but EXISTING user tuning is preserved —
            // v4/v3 presets load as-is; the new defaults only reach fresh
            // installs or entries already dropped by an earlier migration.
            int storedVersion = o.optInt("presetVersion", 0);
            if (!acceptsPresetVersion(storedVersion, tool)) {
                return preset;
            }
            preset.baseWidth = (float) o.optDouble("baseWidth", preset.baseWidth);
            preset.minWidth = (float) o.optDouble("minWidth", preset.minWidth);
            preset.maxWidth = (float) o.optDouble("maxWidth", preset.maxWidth);
            preset.opacity = (float) o.optDouble("opacity", preset.opacity);
            preset.color = (int) o.optLong("color", preset.color & 0xFFFFFFFFL);
            preset.pressureEnabled = o.optBoolean("pressureEnabled", preset.pressureEnabled);
            preset.pressureCurve = enumOr(BrushPreset.PressureCurve.class,
                    o.optString("pressureCurve", null), preset.pressureCurve);
            preset.pressureToWidth = (float) o.optDouble("pressureToWidth", preset.pressureToWidth);
            preset.pressureToOpacity = (float) o.optDouble("pressureToOpacity", preset.pressureToOpacity);
            preset.velocityToWidth = (float) o.optDouble("velocityToWidth", preset.velocityToWidth);
            preset.tiltEnabled = o.optBoolean("tiltEnabled", preset.tiltEnabled);
            preset.tiltToWidth = (float) o.optDouble("tiltToWidth", preset.tiltToWidth);
            preset.tiltToOpacity = (float) o.optDouble("tiltToOpacity", preset.tiltToOpacity);
            preset.smoothing = (float) o.optDouble("smoothing", preset.smoothing);
            preset.stabilization = (float) o.optDouble("stabilization", preset.stabilization);
            preset.taperStart = (float) o.optDouble("taperStart", preset.taperStart);
            preset.taperEnd = (float) o.optDouble("taperEnd", preset.taperEnd);
            preset.blendMode = enumOr(BrushPreset.BlendMode.class,
                    o.optString("blendMode", null), preset.blendMode);
            preset.styleId = o.optString("styleId", "");
            JSONArray curve = o.optJSONArray("customCurvePoints");
            if (curve != null && curve.length() % 2 == 0) {
                preset.customCurvePoints = new ArrayList<>(curve.length());
                for (int i = 0; i < curve.length(); i++) {
                    preset.customCurvePoints.add((float) curve.optDouble(i, 0));
                }
            }
        } catch (JSONException e) {
            AppLogger.w(TAG, "corrupt preset json for " + tool + ", using defaults");
        }
        return preset;
    }

    public void savePreset(EditorTool tool, BrushPreset p, boolean straighten) {
        try {
            JSONObject o = new JSONObject();
            o.put("presetVersion", PRESET_VERSION);
            o.put("styleId", p.styleId == null ? "" : p.styleId);
            o.put("baseWidth", p.baseWidth);
            o.put("minWidth", p.minWidth);
            o.put("maxWidth", p.maxWidth);
            o.put("opacity", p.opacity);
            o.put("color", p.color & 0xFFFFFFFFL);
            o.put("pressureEnabled", p.pressureEnabled);
            o.put("pressureCurve", p.pressureCurve.name());
            o.put("pressureToWidth", p.pressureToWidth);
            o.put("pressureToOpacity", p.pressureToOpacity);
            o.put("velocityToWidth", p.velocityToWidth);
            o.put("tiltEnabled", p.tiltEnabled);
            o.put("tiltToWidth", p.tiltToWidth);
            o.put("tiltToOpacity", p.tiltToOpacity);
            o.put("smoothing", p.smoothing);
            o.put("stabilization", p.stabilization);
            o.put("taperStart", p.taperStart);
            o.put("taperEnd", p.taperEnd);
            o.put("blendMode", p.blendMode.name());
            o.put("straighten", straighten);
            JSONArray curve = new JSONArray();
            for (Float v : p.customCurvePoints) {
                curve.put(v.doubleValue());
            }
            o.put("customCurvePoints", curve);
            prefs.edit().putString(KEY_PRESET_PREFIX + tool.name(), o.toString()).apply();
        } catch (JSONException e) {
            AppLogger.w(TAG, "failed to save preset for " + tool);
        }
    }

    /** Highlighter "straighten" toggle placeholder (rendering lands later). */
    public boolean straightenFor(EditorTool tool) {
        String json = prefs.getString(KEY_PRESET_PREFIX + tool.name(), null);
        if (json == null) {
            return false;
        }
        try {
            return new JSONObject(json).optBoolean("straighten", false);
        } catch (JSONException e) {
            return false;
        }
    }

    public BrushPreset defaultPreset(EditorTool tool) {
        switch (tool) {
            case BALLPOINT:
                return BrushPreset.ballpoint();
            case PENCIL:
                return BrushPreset.pencilHb();
            case HIGHLIGHTER:
                return BrushPreset.highlighter();
            case FOUNTAIN_PEN:
            default:
                return BrushPreset.fountainPen();
        }
    }

    /**
     * Whether a stored preset of the given defaults version may load (see the
     * migration history in {@link #presetFor}). Pure JVM for unit tests.
     */
    static boolean acceptsPresetVersion(int storedVersion, EditorTool tool) {
        if (storedVersion == PRESET_VERSION || storedVersion == 4 || storedVersion == 3) {
            return true;
        }
        return storedVersion == 2
                && tool != EditorTool.BALLPOINT
                && tool != EditorTool.PENCIL;
    }

    private static void applyGlobalCurve(BrushPreset preset, String globalCurve) {
        if (globalCurve == null || !preset.pressureEnabled) {
            return;
        }
        preset.pressureCurve = enumOr(BrushPreset.PressureCurve.class,
                globalCurve, preset.pressureCurve);
    }

    // ------------------------------------------------------------------
    // Eraser + lasso configuration
    // ------------------------------------------------------------------

    public EraserConfig loadEraserConfig() {
        EraserConfig config = new EraserConfig();
        String json = prefs.getString(KEY_ERASER, null);
        if (json == null) {
            return config;
        }
        try {
            JSONObject o = new JSONObject(json);
            config.mode = enumOr(EraserEngine.Mode.class,
                    o.optString("mode", null), config.mode);
            config.filter = enumOr(EraserEngine.TypeFilter.class,
                    o.optString("filter", null), config.filter);
            config.radius = (float) o.optDouble("radius", config.radius);
        } catch (JSONException e) {
            AppLogger.w(TAG, "corrupt eraser config, using defaults");
        }
        return config;
    }

    public void saveEraserConfig(EraserConfig config) {
        try {
            JSONObject o = new JSONObject();
            o.put("mode", config.mode.name());
            o.put("filter", config.filter.name());
            o.put("radius", config.radius);
            prefs.edit().putString(KEY_ERASER, o.toString()).apply();
        } catch (JSONException e) {
            AppLogger.w(TAG, "failed to save eraser config");
        }
    }

    public LassoEngine.Mode lassoMode() {
        return enumOr(LassoEngine.Mode.class,
                prefs.getString(KEY_LASSO_MODE, null), LassoEngine.Mode.FREE);
    }

    public void saveLassoMode(LassoEngine.Mode mode) {
        prefs.edit().putString(KEY_LASSO_MODE, mode.name()).apply();
    }

    private static <E extends Enum<E>> E enumOr(Class<E> cls, String name, E fallback) {
        if (name == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(cls, name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
