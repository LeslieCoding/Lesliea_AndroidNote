package com.leslie.colorosnote.core.render;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-JVM geometry for the built-in stickers (spec 18.3): geometric
 * designs generated in code, no commercial character assets. All primitives
 * live in a normalized 100x100 box; {@link StickerPainter} scales them into
 * the object's local bounds. Kept free of android.graphics so the shapes are
 * unit-testable on the JVM.
 *
 * <p>Designs are layered multi-color fills (face + features + highlights /
 * cheeks, two-tone decor) — draw order is back to front.
 */
public final class StickerGeometry {

    static final int GOLD = 0xFFFFC107;
    static final int LIGHT_GOLD = 0xFFFFE082;
    static final int RED = 0xFFEF5350;
    static final int DARK_RED = 0xFFC62828;
    static final int BLUE = 0xFF42A5F5;
    static final int LIGHT_BLUE = 0xFFB3E5FC;
    static final int TEAR = 0xFF4FC3F7;
    static final int GREEN = 0xFF66BB6A;
    static final int ORANGE = 0xFFFFA726;
    static final int FACE = 0xFFFFD54F;
    static final int PINK = 0xFFF48FB1;
    static final int DARK = 0xFF5D4037;
    static final int WHITE = 0xFFFFFFFF;

    /** One drawable primitive in normalized 100x100 sticker space. */
    public static final class Prim {
        public static final int CIRCLE = 0;
        public static final int POLYGON = 1;
        public static final int RECT = 2;

        public final int type;
        public final int color;
        /** CIRCLE. */
        public float cx;
        public float cy;
        public float radius;
        /** POLYGON, flat {x0,y0,...} (implicitly closed). */
        public float[] points;
        /** RECT. */
        public float left;
        public float top;
        public float right;
        public float bottom;

        private Prim(int type, int color) {
            this.type = type;
            this.color = color;
        }

        public static Prim circle(int color, float cx, float cy, float radius) {
            Prim p = new Prim(CIRCLE, color);
            p.cx = cx;
            p.cy = cy;
            p.radius = radius;
            return p;
        }

        public static Prim polygon(int color, float[] points) {
            Prim p = new Prim(POLYGON, color);
            p.points = points;
            return p;
        }

        public static Prim rect(int color, float left, float top, float right, float bottom) {
            Prim p = new Prim(RECT, color);
            p.left = left;
            p.top = top;
            p.right = right;
            p.bottom = bottom;
            return p;
        }
    }

    private StickerGeometry() {
    }

    /** Primitives for a built-in sticker id, or null when unknown. */
    public static List<Prim> primsFor(String stickerId) {
        if (stickerId == null) {
            return null;
        }
        List<Prim> prims = new ArrayList<>();
        switch (stickerId) {
            case StickerCatalog.ID_STAR:
                prims.add(Prim.polygon(GOLD, star(50f, 52f, 46f, 19f, 5)));
                prims.add(Prim.polygon(LIGHT_GOLD, star(50f, 52f, 28f, 12f, 5)));
                break;
            case StickerCatalog.ID_HEART:
                prims.add(Prim.circle(RED, 35f, 38f, 16f));
                prims.add(Prim.circle(RED, 65f, 38f, 16f));
                prims.add(Prim.polygon(RED, new float[]{20f, 44f, 80f, 44f, 50f, 88f}));
                prims.add(Prim.circle(PINK, 36f, 32f, 5f)); // glossy highlight
                break;
            case StickerCatalog.ID_RING:
                prims.add(Prim.circle(BLUE, 50f, 50f, 46f));
                prims.add(Prim.circle(WHITE, 50f, 50f, 30f));
                break;
            case StickerCatalog.ID_SQUARE:
                prims.add(Prim.rect(GREEN, 14f, 14f, 86f, 86f));
                prims.add(Prim.rect(WHITE, 22f, 22f, 78f, 78f));
                prims.add(Prim.rect(GREEN, 30f, 30f, 70f, 70f));
                break;
            case StickerCatalog.ID_ARROW:
                prims.add(Prim.polygon(BLUE, new float[]{
                        12f, 38f, 58f, 38f, 58f, 20f, 92f, 50f,
                        58f, 80f, 58f, 62f, 12f, 62f}));
                break;
            case StickerCatalog.ID_CHECK:
                prims.add(Prim.circle(GREEN, 50f, 50f, 47f));
                prims.add(Prim.polygon(WHITE, new float[]{
                        22f, 52f, 32f, 42f, 44f, 56f, 72f, 26f, 82f, 36f, 46f, 74f}));
                break;
            case StickerCatalog.ID_CROSS:
                prims.add(Prim.circle(RED, 50f, 50f, 47f));
                prims.add(Prim.polygon(WHITE, rotatedRect(50f, 50f, 62f, 16f, 45.0)));
                prims.add(Prim.polygon(WHITE, rotatedRect(50f, 50f, 62f, 16f, -45.0)));
                break;
            case StickerCatalog.ID_EXCLAIM:
                prims.add(Prim.circle(ORANGE, 50f, 50f, 47f));
                prims.add(Prim.rect(WHITE, 44f, 20f, 56f, 58f));
                prims.add(Prim.circle(WHITE, 50f, 72f, 7f));
                break;
            case StickerCatalog.ID_QUESTION:
                prims.add(Prim.circle(BLUE, 50f, 50f, 47f));
                prims.add(Prim.polygon(WHITE, annularSector(50f, 38f, 17f, 10f, 180.0, 450.0)));
                prims.add(Prim.rect(WHITE, 45f, 46f, 55f, 60f));
                prims.add(Prim.circle(WHITE, 50f, 74f, 6.5f));
                break;
            case StickerCatalog.ID_FLAG:
                prims.add(Prim.rect(DARK, 16f, 8f, 21f, 92f));
                prims.add(Prim.polygon(RED, new float[]{
                        21f, 10f, 74f, 16f, 64f, 30f, 78f, 42f, 21f, 38f}));
                prims.add(Prim.polygon(DARK_RED, new float[]{21f, 10f, 40f, 22f, 21f, 38f}));
                break;
            case StickerCatalog.ID_PIN:
                prims.add(Prim.circle(RED, 50f, 38f, 26f));
                prims.add(Prim.polygon(RED, new float[]{27f, 52f, 73f, 52f, 50f, 94f}));
                prims.add(Prim.circle(WHITE, 50f, 38f, 11f));
                break;
            case StickerCatalog.ID_SMILEY:
                prims.add(Prim.circle(FACE, 50f, 50f, 47f));
                prims.add(Prim.circle(DARK, 35f, 40f, 6f));
                prims.add(Prim.circle(DARK, 65f, 40f, 6f));
                prims.add(Prim.polygon(DARK, annularSector(50f, 52f, 26f, 15f, 20.0, 160.0)));
                prims.add(Prim.circle(PINK, 28f, 57f, 5.5f)); // cheeks
                prims.add(Prim.circle(PINK, 72f, 57f, 5.5f));
                break;
            case StickerCatalog.ID_SURPRISED:
                prims.add(Prim.circle(FACE, 50f, 50f, 47f));
                prims.add(Prim.circle(DARK, 35f, 38f, 6f));
                prims.add(Prim.circle(DARK, 65f, 38f, 6f));
                prims.add(Prim.circle(DARK, 50f, 68f, 10f));
                prims.add(Prim.circle(PINK, 27f, 56f, 5f));
                prims.add(Prim.circle(PINK, 73f, 56f, 5f));
                break;
            case StickerCatalog.ID_LAUGH:
                prims.add(Prim.circle(FACE, 50f, 50f, 47f));
                // Happy closed "^^" eyes.
                prims.add(Prim.polygon(DARK, annularSector(34f, 42f, 10f, 6f, 180.0, 360.0)));
                prims.add(Prim.polygon(DARK, annularSector(66f, 42f, 10f, 6f, 180.0, 360.0)));
                // Open mouth with tongue.
                prims.add(Prim.polygon(DARK, annularSector(50f, 50f, 23f, 0f, 0.0, 180.0)));
                prims.add(Prim.polygon(RED, annularSector(50f, 58f, 13f, 0f, 20.0, 160.0)));
                break;
            case StickerCatalog.ID_HEART_EYES:
                prims.add(Prim.circle(FACE, 50f, 50f, 47f));
                miniHeart(prims, 35f, 40f);
                miniHeart(prims, 65f, 40f);
                prims.add(Prim.polygon(DARK, annularSector(50f, 54f, 20f, 13f, 25.0, 155.0)));
                break;
            case StickerCatalog.ID_CRY:
                prims.add(Prim.circle(FACE, 50f, 50f, 47f));
                prims.add(Prim.circle(DARK, 35f, 40f, 5f));
                prims.add(Prim.circle(DARK, 65f, 40f, 5f));
                // Tears under both eyes.
                prims.add(Prim.circle(TEAR, 30f, 58f, 4.5f));
                prims.add(Prim.polygon(TEAR, new float[]{30f, 49f, 25f, 58f, 35f, 58f}));
                prims.add(Prim.circle(TEAR, 70f, 58f, 4.5f));
                prims.add(Prim.polygon(TEAR, new float[]{70f, 49f, 65f, 58f, 75f, 58f}));
                // Sad mouth (∩, upper arc with y down).
                prims.add(Prim.polygon(DARK, annularSector(50f, 80f, 12f, 7f, 180.0, 360.0)));
                break;
            case StickerCatalog.ID_WINK:
                prims.add(Prim.circle(FACE, 50f, 50f, 47f));
                prims.add(Prim.circle(DARK, 35f, 40f, 5.5f));
                prims.add(Prim.polygon(DARK, rotatedRect(65f, 40f, 14f, 3.5f, 0.0)));
                prims.add(Prim.polygon(DARK, annularSector(50f, 52f, 22f, 15f, 20.0, 160.0)));
                prims.add(Prim.circle(PINK, 28f, 57f, 5f));
                prims.add(Prim.circle(PINK, 72f, 57f, 5f));
                break;
            case StickerCatalog.ID_SUN:
                for (int i = 0; i < 8; i++) {
                    double angle = i * 45.0;
                    double rad = Math.toRadians(angle);
                    prims.add(Prim.polygon(ORANGE, rotatedRect(
                            (float) (50 + 36 * Math.cos(rad)),
                            (float) (50 + 36 * Math.sin(rad)), 15f, 5.5f, angle)));
                }
                prims.add(Prim.circle(GOLD, 50f, 50f, 23f));
                prims.add(Prim.circle(LIGHT_GOLD, 50f, 50f, 14f));
                break;
            case StickerCatalog.ID_MOON:
                prims.add(Prim.polygon(GOLD, crescent(48f, 50f, 42f, 66f, 50f, 34f,
                        70.0, 290.0)));
                prims.add(Prim.circle(LIGHT_GOLD, 38f, 40f, 4f)); // surface dots
                prims.add(Prim.circle(LIGHT_GOLD, 32f, 60f, 3f));
                break;
            case StickerCatalog.ID_CLOUD:
                prims.add(Prim.circle(LIGHT_BLUE, 36f, 58f, 17f));
                prims.add(Prim.circle(LIGHT_BLUE, 58f, 48f, 21f));
                prims.add(Prim.circle(LIGHT_BLUE, 74f, 60f, 14f));
                prims.add(Prim.rect(LIGHT_BLUE, 24f, 58f, 80f, 74f));
                prims.add(Prim.circle(WHITE, 47f, 46f, 8f)); // highlight
                break;
            case StickerCatalog.ID_FLOWER:
                prims.add(Prim.rect(GREEN, 46f, 58f, 54f, 96f));
                prims.add(Prim.polygon(GREEN, new float[]{47f, 72f, 28f, 62f, 47f, 86f}));
                for (int i = 0; i < 6; i++) {
                    double rad = Math.toRadians(i * 60.0 - 90.0);
                    prims.add(Prim.circle(PINK,
                            (float) (50 + 15 * Math.cos(rad)),
                            (float) (40 + 15 * Math.sin(rad)), 11.5f));
                }
                prims.add(Prim.circle(GOLD, 50f, 40f, 9f));
                break;
            case StickerCatalog.ID_SNOWFLAKE:
                for (int i = 0; i < 6; i++) {
                    double rad = Math.toRadians(i * 60.0);
                    prims.add(Prim.circle(BLUE,
                            (float) (50 + 38 * Math.cos(rad)),
                            (float) (50 + 38 * Math.sin(rad)), 4f));
                }
                for (int i = 0; i < 3; i++) {
                    prims.add(Prim.polygon(BLUE, rotatedRect(50f, 50f, 78f, 5f, i * 60.0)));
                }
                prims.add(Prim.circle(BLUE, 50f, 50f, 7f));
                prims.add(Prim.circle(WHITE, 50f, 50f, 3f));
                break;
            case StickerCatalog.ID_CROWN:
                prims.add(Prim.circle(GOLD, 24f, 28f, 4.5f));
                prims.add(Prim.circle(GOLD, 50f, 20f, 4.5f));
                prims.add(Prim.circle(GOLD, 76f, 28f, 4.5f));
                prims.add(Prim.polygon(GOLD, new float[]{
                        22f, 62f, 22f, 32f, 38f, 50f, 50f, 24f, 62f, 50f, 78f, 32f, 78f, 62f}));
                prims.add(Prim.rect(LIGHT_GOLD, 22f, 62f, 78f, 76f));
                prims.add(Prim.circle(RED, 36f, 69f, 4f));
                prims.add(Prim.circle(WHITE, 50f, 69f, 4f));
                prims.add(Prim.circle(BLUE, 64f, 69f, 4f));
                break;
            default:
                return null;
        }
        return prims;
    }

    /** Small red heart (two lobes + pointed bottom) centered at (cx, cy). */
    private static void miniHeart(List<Prim> prims, float cx, float cy) {
        prims.add(Prim.circle(RED, cx - 3.5f, cy - 2.5f, 4.5f));
        prims.add(Prim.circle(RED, cx + 3.5f, cy - 2.5f, 4.5f));
        prims.add(Prim.polygon(RED, new float[]{
                cx - 7f, cy - 0.5f, cx + 7f, cy - 0.5f, cx, cy + 9f}));
    }

    /** Regular star polygon, flat vertices (outer/inner alternating). */
    static float[] star(float cx, float cy, float rOuter, float rInner, int points) {
        float[] out = new float[points * 4];
        for (int i = 0; i < points * 2; i++) {
            double angle = -Math.PI / 2.0 + i * Math.PI / points;
            float r = (i % 2 == 0) ? rOuter : rInner;
            out[i * 2] = (float) (cx + r * Math.cos(angle));
            out[i * 2 + 1] = (float) (cy + r * Math.sin(angle));
        }
        return out;
    }

    /** Axis-aligned rect of width w / height h rotated around its center. */
    static float[] rotatedRect(float cx, float cy, float w, float h, double degrees) {
        double rad = Math.toRadians(degrees);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        float[] out = new float[8];
        float[] xs = {-w / 2f, w / 2f, w / 2f, -w / 2f};
        float[] ys = {-h / 2f, -h / 2f, h / 2f, h / 2f};
        for (int i = 0; i < 4; i++) {
            out[i * 2] = (float) (cx + xs[i] * cos - ys[i] * sin);
            out[i * 2 + 1] = (float) (cy + xs[i] * sin + ys[i] * cos);
        }
        return out;
    }

    /**
     * Ring-sector polygon between two radii and angles (degrees, y-axis down,
     * 0 = +x). Used for the smiley mouth.
     */
    static float[] annularSector(float cx, float cy, float rOuter, float rInner,
                                 double startDeg, double endDeg) {
        int steps = Math.max(2, (int) Math.ceil(Math.abs(endDeg - startDeg) / 8.0));
        float[] out = new float[(steps + 1) * 4];
        int n = 0;
        for (int i = 0; i <= steps; i++) {
            double rad = Math.toRadians(startDeg + (endDeg - startDeg) * i / steps);
            out[n++] = (float) (cx + rOuter * Math.cos(rad));
            out[n++] = (float) (cy + rOuter * Math.sin(rad));
        }
        for (int i = steps; i >= 0; i--) {
            double rad = Math.toRadians(startDeg + (endDeg - startDeg) * i / steps);
            out[n++] = (float) (cx + rInner * Math.cos(rad));
            out[n++] = (float) (cy + rInner * Math.sin(rad));
        }
        return out;
    }

    /**
     * Crescent polygon: outer arc from startDeg to endDeg, closed by an
     * inner arc on a shifted center (degrees, y-axis down, 0 = +x). Used for
     * the moon.
     */
    static float[] crescent(float cx, float cy, float rOuter,
                            float innerCx, float innerCy, float rInner,
                            double startDeg, double endDeg) {
        int steps = Math.max(4, (int) Math.ceil(Math.abs(endDeg - startDeg) / 8.0));
        float[] out = new float[(steps + 1) * 4];
        int n = 0;
        for (int i = 0; i <= steps; i++) {
            double rad = Math.toRadians(startDeg + (endDeg - startDeg) * i / steps);
            out[n++] = (float) (cx + rOuter * Math.cos(rad));
            out[n++] = (float) (cy + rOuter * Math.sin(rad));
        }
        for (int i = steps; i >= 0; i--) {
            double rad = Math.toRadians(startDeg + (endDeg - startDeg) * i / steps);
            out[n++] = (float) (innerCx + rInner * Math.cos(rad));
            out[n++] = (float) (innerCy + rInner * Math.sin(rad));
        }
        return out;
    }
}
