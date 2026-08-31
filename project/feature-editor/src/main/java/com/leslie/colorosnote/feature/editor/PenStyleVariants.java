package com.leslie.colorosnote.feature.editor;

import com.leslie.colorosnote.core.model.BrushPreset;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Fountain-pen style variants (v1.2.0). Parameters come from the third
 * research round in docs/BRUSH_RESEARCH.md (「第三轮调研：钢笔变体库」):
 * real nib widths mapped via baseWidth 3 ≈ Montblanc M 0.62mm, plus
 * GoodNotes/Concepts/Write-style speed- and taper-driven models.
 *
 * <p>A variant rewrites the preset's geometry/feel parameters only; the ink
 * color (and id) belong to the user and are preserved.
 */
public final class PenStyleVariants {

    /** One selectable pen style. */
    public static final class Variant {
        public final String id;
        public final String name;

        private final float baseWidth;
        private final float minFactor;
        private final float maxFactor;
        private final BrushPreset.PressureCurve curve;
        private final float pressureToWidth;
        private final float velocityToWidth;
        private final boolean tiltEnabled;
        private final float tiltToWidth;
        private final float smoothing;
        private final float stabilization;
        private final float taperStart;
        private final float taperEnd;

        Variant(String id, String name, float baseWidth,
                float minFactor, float maxFactor, BrushPreset.PressureCurve curve,
                float pressureToWidth, float velocityToWidth,
                boolean tiltEnabled, float tiltToWidth,
                float smoothing, float stabilization,
                float taperStart, float taperEnd) {
            this.id = id;
            this.name = name;
            this.baseWidth = baseWidth;
            this.minFactor = minFactor;
            this.maxFactor = maxFactor;
            this.curve = curve;
            this.pressureToWidth = pressureToWidth;
            this.velocityToWidth = velocityToWidth;
            this.tiltEnabled = tiltEnabled;
            this.tiltToWidth = tiltToWidth;
            this.smoothing = smoothing;
            this.stabilization = stabilization;
            this.taperStart = taperStart;
            this.taperEnd = taperEnd;
        }

        /** Copies this style's feel parameters into the preset. */
        public void applyTo(BrushPreset p) {
            p.brushType = BrushPreset.BrushType.FOUNTAIN_PEN;
            p.styleId = id;
            p.baseWidth = baseWidth;
            p.minWidth = baseWidth * minFactor;
            p.maxWidth = baseWidth * maxFactor;
            p.opacity = 1f;
            p.pressureEnabled = true;
            p.pressureCurve = curve;
            p.pressureToWidth = pressureToWidth;
            p.pressureToOpacity = 0f;
            p.velocityToWidth = velocityToWidth;
            p.tiltEnabled = tiltEnabled;
            p.tiltToWidth = tiltToWidth;
            p.tiltToOpacity = 0f;
            p.smoothing = smoothing;
            p.stabilization = stabilization;
            p.taperStart = taperStart;
            p.taperEnd = taperEnd;
            p.blendMode = BrushPreset.BlendMode.SRC_OVER;
            p.textureId = null;
        }
    }

    private static final List<Variant> FOUNTAIN_VARIANTS;

    static {
        List<Variant> v = new ArrayList<>(7);
        // Order: everyday note-taking first, creative styles last.
        v.add(new Variant("m", "标准尖", 3.0f, 0.4f, 1.9f,
                BrushPreset.PressureCurve.SOFT, 1.0f, 0.4f,
                false, 0f, 0.2f, 0.1f, 0.15f, 0.5f));
        v.add(new Variant("ef", "极细尖", 2.0f, 0.6f, 1.5f,
                BrushPreset.PressureCurve.BALANCED, 0.7f, 0.25f,
                false, 0f, 0.15f, 0.05f, 0.10f, 0.35f));
        v.add(new Variant("b", "粗尖", 4.5f, 0.5f, 1.6f,
                BrushPreset.PressureCurve.BALANCED, 0.85f, 0.25f,
                false, 0f, 0.3f, 0.15f, 0.10f, 0.4f));
        v.add(new Variant("speed", "疾书尖", 3.0f, 0.5f, 1.7f,
                BrushPreset.PressureCurve.FIRM, 0.3f, 0.6f,
                false, 0f, 0.15f, 0.05f, 0.10f, 0.55f));
        v.add(new Variant("flex", "弹性尖", 3.0f, 0.3f, 2.4f,
                BrushPreset.PressureCurve.SOFT, 1.0f, 0.2f,
                false, 0f, 0.25f, 0.15f, 0.2f, 0.6f));
        v.add(new Variant("brush", "毛笔锋", 3.5f, 0.2f, 2.2f,
                BrushPreset.PressureCurve.SOFT, 1.0f, 0.35f,
                false, 0f, 0.25f, 0.2f, 0.3f, 0.8f));
        v.add(new Variant("fude", "美工尖", 4.0f, 0.15f, 2.5f,
                BrushPreset.PressureCurve.BALANCED, 0.5f, 0.2f,
                true, 0.9f, 0.25f, 0.15f, 0.10f, 0.45f));
        FOUNTAIN_VARIANTS = Collections.unmodifiableList(v);
    }

    private PenStyleVariants() {
    }

    /** Style variants offered for the tool (empty = tool has none). */
    public static List<Variant> forTool(EditorTool tool) {
        return tool == EditorTool.FOUNTAIN_PEN
                ? FOUNTAIN_VARIANTS : Collections.emptyList();
    }
}
