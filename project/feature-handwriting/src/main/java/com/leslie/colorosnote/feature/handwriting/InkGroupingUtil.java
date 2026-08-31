package com.leslie.colorosnote.feature.handwriting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Groups strokes into lines and blocks by spatial + temporal proximity
 * (spec 15: background handwriting search recognizes strokes in groups).
 *
 * <p>Pure geometry, no engine calls - strokes are sorted by start time, then a
 * stroke joins the current group unless it sits clearly below/beside the group
 * or follows a long pause. Group boundaries scale with the median stroke height
 * so the same thresholds work across pen sizes. Fully JVM-testable.
 */
public final class InkGroupingUtil {

    /** Tunable thresholds; {@link #defaults()} matches typical handwriting. */
    public static final class Config {
        /** Vertical center distance (x median stroke height) that starts a new line. */
        public float lineGapFactor = 0.9f;
        /** Horizontal gap (x median stroke height) that starts a new line. */
        public float horizontalGapFactor = 1.0f;
        /** Pause between strokes (ms) that starts a new line. */
        public long timeGapMs = 2500;
        /** Vertical center distance (x median height) that starts a new block. */
        public float blockGapFactor = 2.2f;
        /** Pause (ms) that starts a new block regardless of geometry. */
        public long blockTimeGapMs = 6000;

        public static Config defaults() {
            return new Config();
        }
    }

    private InkGroupingUtil() {
    }

    public static List<InkGroup> groupIntoLines(List<StrokeData> strokes) {
        return groupIntoLines(strokes, Config.defaults());
    }

    public static List<InkGroup> groupIntoLines(List<StrokeData> strokes, Config config) {
        return group(strokes, InkGroup.Kind.LINE,
                config.lineGapFactor, config.horizontalGapFactor, config.timeGapMs);
    }

    /** Coarser grouping for batch recognition; same algorithm, wider thresholds. */
    public static List<InkGroup> groupIntoBlocks(List<StrokeData> strokes) {
        return groupIntoBlocks(strokes, Config.defaults());
    }

    public static List<InkGroup> groupIntoBlocks(List<StrokeData> strokes, Config config) {
        return group(strokes, InkGroup.Kind.BLOCK,
                config.blockGapFactor, config.horizontalGapFactor, config.blockTimeGapMs);
    }

    private static List<InkGroup> group(List<StrokeData> strokes, InkGroup.Kind kind,
                                        float verticalGapFactor, float horizontalGapFactor,
                                        long timeGapMs) {
        if (strokes == null || strokes.isEmpty()) {
            return Collections.emptyList();
        }
        List<StrokeData> sorted = new ArrayList<>(strokes);
        sorted.sort(Comparator.comparingLong(StrokeData::startTime));

        float medianHeight = medianStrokeHeight(sorted);
        float maxVerticalGap = verticalGapFactor * medianHeight;
        float maxHorizontalGap = horizontalGapFactor * medianHeight;

        List<InkGroup> groups = new ArrayList<>();
        List<StrokeData> current = new ArrayList<>();
        InkRect currentRegion = null;
        StrokeData last = null;

        for (StrokeData s : sorted) {
            if (last != null) {
                InkRect b = s.bounds();
                long pause = s.startTime() - last.endTime();
                float verticalGap = Math.abs(b.centerY() - last.bounds().centerY());
                float horizontalGap = horizontalGap(b, currentRegion);
                boolean sameGroup = pause <= timeGapMs
                        && verticalGap <= maxVerticalGap
                        && horizontalGap <= maxHorizontalGap;
                if (!sameGroup) {
                    groups.add(new InkGroup(kind, current));
                    current = new ArrayList<>();
                    currentRegion = null;
                }
            }
            current.add(s);
            currentRegion = currentRegion == null ? s.bounds() : currentRegion.union(s.bounds());
            last = s;
        }
        if (!current.isEmpty()) {
            groups.add(new InkGroup(kind, current));
        }
        return groups;
    }

    /** Gap between two horizontal ranges; 0 when they overlap. */
    private static float horizontalGap(InkRect a, InkRect b) {
        return Math.max(0f, Math.max(a.left - b.right, b.left - a.right));
    }

    private static float medianStrokeHeight(List<StrokeData> strokes) {
        List<Float> heights = new ArrayList<>(strokes.size());
        for (StrokeData s : strokes) {
            heights.add(Math.max(s.bounds().height(), 1e-3f));
        }
        Collections.sort(heights);
        return heights.get(heights.size() / 2);
    }
}
