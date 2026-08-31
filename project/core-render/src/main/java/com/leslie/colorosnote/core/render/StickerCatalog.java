package com.leslie.colorosnote.core.render;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Registry of built-in stickers (spec 18.3). All designs are geometric
 * compositions generated in code by {@link StickerGeometry} — no commercial
 * character assets. Pure JVM so picker UIs and tests can enumerate entries.
 */
public final class StickerCatalog {

    public static final String CAT_BASIC = "基础";
    public static final String CAT_MARK = "标记";
    public static final String CAT_FACE = "表情";
    public static final String CAT_DECOR = "装饰";

    public static final String ID_STAR = "builtin_star";
    public static final String ID_HEART = "builtin_heart";
    public static final String ID_RING = "builtin_ring";
    public static final String ID_SQUARE = "builtin_square";
    public static final String ID_ARROW = "builtin_arrow_right";
    public static final String ID_CHECK = "builtin_check";
    public static final String ID_CROSS = "builtin_cross";
    public static final String ID_EXCLAIM = "builtin_exclaim";
    public static final String ID_QUESTION = "builtin_question";
    public static final String ID_FLAG = "builtin_flag";
    public static final String ID_PIN = "builtin_pin";
    public static final String ID_SMILEY = "builtin_smiley";
    public static final String ID_SURPRISED = "builtin_surprised";
    public static final String ID_LAUGH = "builtin_laugh";
    public static final String ID_HEART_EYES = "builtin_heart_eyes";
    public static final String ID_CRY = "builtin_cry";
    public static final String ID_WINK = "builtin_wink";
    public static final String ID_SUN = "builtin_sun";
    public static final String ID_MOON = "builtin_moon";
    public static final String ID_CLOUD = "builtin_cloud";
    public static final String ID_FLOWER = "builtin_flower";
    public static final String ID_SNOWFLAKE = "builtin_snowflake";
    public static final String ID_CROWN = "builtin_crown";

    /** One built-in sticker entry. */
    public static final class Entry {
        public final String id;
        public final String category;
        public final String label;

        Entry(String id, String category, String label) {
            this.id = id;
            this.category = category;
            this.label = label;
        }
    }

    public static final List<Entry> BUILT_IN = Collections.unmodifiableList(Arrays.asList(
            new Entry(ID_STAR, CAT_BASIC, "星星"),
            new Entry(ID_HEART, CAT_BASIC, "爱心"),
            new Entry(ID_RING, CAT_BASIC, "圆环"),
            new Entry(ID_SQUARE, CAT_BASIC, "方块"),
            new Entry(ID_ARROW, CAT_MARK, "箭头"),
            new Entry(ID_CHECK, CAT_MARK, "对勾"),
            new Entry(ID_CROSS, CAT_MARK, "叉号"),
            new Entry(ID_EXCLAIM, CAT_MARK, "叹号"),
            new Entry(ID_QUESTION, CAT_MARK, "问号"),
            new Entry(ID_FLAG, CAT_MARK, "旗帜"),
            new Entry(ID_PIN, CAT_MARK, "图钉"),
            new Entry(ID_SMILEY, CAT_FACE, "微笑"),
            new Entry(ID_SURPRISED, CAT_FACE, "惊讶"),
            new Entry(ID_LAUGH, CAT_FACE, "大笑"),
            new Entry(ID_HEART_EYES, CAT_FACE, "爱心眼"),
            new Entry(ID_CRY, CAT_FACE, "哭泣"),
            new Entry(ID_WINK, CAT_FACE, "眨眼"),
            new Entry(ID_SUN, CAT_DECOR, "太阳"),
            new Entry(ID_MOON, CAT_DECOR, "月亮"),
            new Entry(ID_CLOUD, CAT_DECOR, "云朵"),
            new Entry(ID_FLOWER, CAT_DECOR, "花朵"),
            new Entry(ID_SNOWFLAKE, CAT_DECOR, "雪花"),
            new Entry(ID_CROWN, CAT_DECOR, "皇冠")));

    private StickerCatalog() {
    }

    /** Distinct categories in declaration order. */
    public static List<String> categories() {
        Set<String> seen = new LinkedHashSet<>();
        for (Entry e : BUILT_IN) {
            seen.add(e.category);
        }
        return new ArrayList<>(seen);
    }

    public static List<Entry> byCategory(String category) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : BUILT_IN) {
            if (e.category.equals(category)) {
                out.add(e);
            }
        }
        return out;
    }

    public static Entry find(String stickerId) {
        if (stickerId == null) {
            return null;
        }
        for (Entry e : BUILT_IN) {
            if (e.id.equals(stickerId)) {
                return e;
            }
        }
        return null;
    }

    public static boolean isBuiltIn(String stickerId) {
        return find(stickerId) != null;
    }
}
