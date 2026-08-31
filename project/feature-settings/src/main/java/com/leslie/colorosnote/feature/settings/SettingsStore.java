package com.leslie.colorosnote.feature.settings;

import android.content.Context;
import android.content.SharedPreferences;

import com.leslie.colorosnote.core.common.AppLogger;

/**
 * SharedPreferences-backed persistence for {@link EditorSettings} and library
 * view preferences (spec 23: grid/list choice persists; spec 29 gestures).
 */
public final class SettingsStore {

    private static final String TAG = "SettingsStore";
    private static final String PREFS = "inknote_settings";

    // Editor keys
    private static final String K_FINGER_MODE = "finger_mode";
    private static final String K_PALM_LEVEL = "palm_level";
    private static final String K_TWO_FINGER_UNDO = "two_finger_undo";
    private static final String K_THREE_FINGER_REDO = "three_finger_redo";
    private static final String K_LONG_PRESS_SHAPE = "long_press_shape";
    private static final String K_STYLUS_BUTTON = "stylus_button";
    private static final String K_ERASER_TAIL = "eraser_tail";
    private static final String K_TOOLBAR_SIDE = "toolbar_side";
    private static final String K_PRESSURE_CURVE = "pressure_curve";
    private static final String K_SIM_PRESSURE_HINT = "sim_pressure_hint";
    private static final String K_IMMERSIVE = "immersive";
    /** One-time migration: v1.1.7 pins the bars (no auto-hide) for existing installs. */
    private static final String K_MIG_IMMERSIVE_OFF = "mig_immersive_off_v117";

    // Library keys
    private static final String K_LIBRARY_GRID = "library_grid_mode";
    private static final String K_LIBRARY_SORT = "library_sort";

    private final SharedPreferences prefs;

    public SettingsStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public EditorSettings loadEditorSettings() {
        // One-time migration (v1.1.7): bars no longer auto-hide by default;
        // clear any persisted immersive=true from older defaults.
        if (!prefs.getBoolean(K_MIG_IMMERSIVE_OFF, false)) {
            prefs.edit().putBoolean(K_IMMERSIVE, false)
                    .putBoolean(K_MIG_IMMERSIVE_OFF, true).apply();
        }
        EditorSettings s = new EditorSettings();
        s.fingerMode = enumOr(EditorSettings.FingerMode.class,
                prefs.getString(K_FINGER_MODE, null), s.fingerMode);
        s.palmRejectionLevel = prefs.getInt(K_PALM_LEVEL, s.palmRejectionLevel);
        s.twoFingerTapUndo = prefs.getBoolean(K_TWO_FINGER_UNDO, s.twoFingerTapUndo);
        s.threeFingerTapRedo = prefs.getBoolean(K_THREE_FINGER_REDO, s.threeFingerTapRedo);
        s.longPressShapeFix = prefs.getBoolean(K_LONG_PRESS_SHAPE, s.longPressShapeFix);
        s.stylusButtonAction = enumOr(EditorSettings.StylusButtonAction.class,
                prefs.getString(K_STYLUS_BUTTON, null), s.stylusButtonAction);
        s.eraserTailAutoEraser = prefs.getBoolean(K_ERASER_TAIL, s.eraserTailAutoEraser);
        s.toolbarSide = enumOr(EditorSettings.HandSide.class,
                prefs.getString(K_TOOLBAR_SIDE, null), s.toolbarSide);
        s.globalPressureCurve = prefs.getString(K_PRESSURE_CURVE, s.globalPressureCurve);
        s.showSimulatedPressureHint = prefs.getBoolean(K_SIM_PRESSURE_HINT, s.showSimulatedPressureHint);
        s.immersiveMode = prefs.getBoolean(K_IMMERSIVE, s.immersiveMode);
        return s;
    }

    public void saveEditorSettings(EditorSettings s) {
        prefs.edit()
                .putString(K_FINGER_MODE, s.fingerMode.name())
                .putInt(K_PALM_LEVEL, s.palmRejectionLevel)
                .putBoolean(K_TWO_FINGER_UNDO, s.twoFingerTapUndo)
                .putBoolean(K_THREE_FINGER_REDO, s.threeFingerTapRedo)
                .putBoolean(K_LONG_PRESS_SHAPE, s.longPressShapeFix)
                .putString(K_STYLUS_BUTTON, s.stylusButtonAction.name())
                .putBoolean(K_ERASER_TAIL, s.eraserTailAutoEraser)
                .putString(K_TOOLBAR_SIDE, s.toolbarSide.name())
                .putString(K_PRESSURE_CURVE, s.globalPressureCurve)
                .putBoolean(K_SIM_PRESSURE_HINT, s.showSimulatedPressureHint)
                .putBoolean(K_IMMERSIVE, s.immersiveMode)
                .apply();
        AppLogger.d(TAG, "editor settings saved");
    }

    /** Library view: true = grid, false = list (spec 23 persists user choice). */
    public boolean isLibraryGridMode() {
        return prefs.getBoolean(K_LIBRARY_GRID, true);
    }

    public void setLibraryGridMode(boolean grid) {
        prefs.edit().putBoolean(K_LIBRARY_GRID, grid).apply();
    }

    /** Library sort: name | modified | created | type. */
    public String getLibrarySort() {
        return prefs.getString(K_LIBRARY_SORT, "modified");
    }

    public void setLibrarySort(String sort) {
        prefs.edit().putString(K_LIBRARY_SORT, sort).apply();
    }

    private static <E extends Enum<E>> E enumOr(Class<E> cls, String name, E fallback) {
        if (name == null) return fallback;
        try {
            return Enum.valueOf(cls, name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
