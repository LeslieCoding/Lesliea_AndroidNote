package com.leslie.colorosnote.feature.editor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.leslie.colorosnote.core.model.Page;
import com.leslie.colorosnote.core.model.PageObject;
import com.leslie.colorosnote.core.model.Stroke;
import com.leslie.colorosnote.core.model.StrokePoint;
import com.leslie.colorosnote.feature.handwriting.Candidate;
import com.leslie.colorosnote.feature.handwriting.HandwritingException;
import com.leslie.colorosnote.feature.handwriting.HandwritingRecognizer;
import com.leslie.colorosnote.feature.handwriting.InkGroup;
import com.leslie.colorosnote.feature.handwriting.InkGroupingUtil;
import com.leslie.colorosnote.feature.handwriting.RecognizeOptions;
import com.leslie.colorosnote.feature.handwriting.StrokeData;

import java.util.ArrayList;
import java.util.List;

/**
 * 手写转文字的纯 JVM 编排层（spec 15）：笔迹适配、分行识别与结果汇总、
 * 结果文本框的落位计算。Android 依赖全部隔离在接口后面
 * （{@link HandwritingRecognizer}），因此本类可完整单测。
 *
 * <p>识别策略：按 {@link InkGroupingUtil#groupIntoLines} 把整页笔迹分行，
 * 逐行识别并把已识别文本的末尾（最多 20 字符）作为下一行的 pre-context，
 * 这是 ML Kit Digital Ink 官方推荐的提精度手段。
 */
public final class HandwritingToTextPlanner {

    /** ML Kit pre-context 只取末尾 20 字符。 */
    static final int PRE_CONTEXT_CHARS = 20;

    /** 结果文本框字号（与文本框工具默认值一致）。 */
    public static final float BOX_FONT_SIZE = 18f;
    /** 结果文本框行距（与 TextBoxObject 默认一致）。 */
    public static final float BOX_LINE_SPACING = 1.2f;
    /** 页面左右留白（逻辑单位）。 */
    public static final float PAGE_MARGIN = 24f;
    /** 首个内容块距页面顶部的距离。 */
    public static final float TOP_MARGIN = 48f;
    /** 文本框与其上方内容之间的间隙。 */
    public static final float CONTENT_GAP = 16f;

    private HandwritingToTextPlanner() {
    }

    /**
     * 把页面笔迹适配成识别层的 {@link StrokeData}（point 时间戳直接用
     * {@link StrokePoint#timestampMs}）。无点的笔画被跳过。不做深拷贝：
     * 数组与页面共享，识别层约定只读。
     */
    @NonNull
    public static List<StrokeData> adaptStrokes(@Nullable List<Stroke> strokes) {
        List<StrokeData> out = new ArrayList<>();
        if (strokes == null) {
            return out;
        }
        for (Stroke s : strokes) {
            if (s == null || s.points == null || s.points.isEmpty()) {
                continue;
            }
            int n = s.points.size();
            float[] xs = new float[n];
            float[] ys = new float[n];
            long[] ts = new long[n];
            for (int i = 0; i < n; i++) {
                StrokePoint p = s.points.get(i);
                xs[i] = p.x;
                ys[i] = p.y;
                ts[i] = p.timestampMs;
            }
            out.add(new StrokeData(s.id, xs, ys, ts));
        }
        return out;
    }

    /**
     * 分行识别整页笔迹并汇总为一段文本（行间以 \n 连接）。
     * 识别不出任何内容的行被跳过；全部为空时返回空串（调用方提示
     * 「未识别到文字」）。引擎失败原样抛出 {@link HandwritingException}，
     * 绝不编造候选文本。
     */
    @NonNull
    public static String recognizePage(@NonNull HandwritingRecognizer recognizer,
                                       @NonNull List<StrokeData> strokes)
            throws HandwritingException {
        List<InkGroup> lines = InkGroupingUtil.groupIntoLines(strokes);
        StringBuilder text = new StringBuilder();
        for (InkGroup line : lines) {
            float areaW = Math.max(line.region.width(), 1f);
            float areaH = Math.max(line.region.height(), 1f);
            RecognizeOptions options = new RecognizeOptions(
                    null, 1, preContextTail(text), areaW, areaH);
            List<Candidate> candidates = recognizer.recognize(line.strokes, options);
            String best = bestText(candidates);
            if (best.isEmpty()) {
                continue;
            }
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(best);
        }
        return text.toString();
    }

    /** 累计文本末尾的 pre-context（不超过 {@link #PRE_CONTEXT_CHARS} 字符）。 */
    @Nullable
    static String preContextTail(StringBuilder recognized) {
        if (recognized.length() == 0) {
            return null;
        }
        int from = Math.max(0, recognized.length() - PRE_CONTEXT_CHARS);
        return recognized.substring(from);
    }

    @NonNull
    private static String bestText(@Nullable List<Candidate> candidates) {
        if (candidates == null || candidates.isEmpty() || candidates.get(0) == null
                || candidates.get(0).text == null) {
            return "";
        }
        return candidates.get(0).text.trim();
    }

    /**
     * 页面内容的下边缘（笔迹 + 对象的最大 bottom；对象忽略 transform 的近似）。
     * 空页面返回 0。
     */
    public static float contentBottom(@Nullable Page page) {
        if (page == null) {
            return 0f;
        }
        float bottom = 0f;
        boolean any = false;
        if (page.strokes != null) {
            for (Stroke s : page.strokes) {
                if (s == null || s.points == null || s.points.isEmpty()) {
                    continue;
                }
                bottom = any ? Math.max(bottom, s.computeBounds()[3]) : s.computeBounds()[3];
                any = true;
            }
        }
        if (page.objects != null) {
            for (PageObject o : page.objects) {
                if (o == null || o.bounds == null || o.bounds.length < 4) {
                    continue;
                }
                bottom = any ? Math.max(bottom, o.bounds[3]) : o.bounds[3];
                any = true;
            }
        }
        return any ? bottom : 0f;
    }

    /**
     * 结果文本框的落位：内容下方 {@link #CONTENT_GAP} 处的空白区；页面为
     * 空时从 {@link #TOP_MARGIN} 开始。高度按行数随内容自适应（公式与
     * 文本框编辑确认的 auto-height 一致），整体被夹在页面上下留白之间。
     *
     * @return {left, top, right, bottom}
     */
    @NonNull
    public static float[] insertBounds(float contentBottom, float pageWidth,
                                       float pageHeight, int lineCount) {
        int lines = Math.max(1, lineCount);
        float height = Math.max((lines + 0.6f) * BOX_FONT_SIZE * BOX_LINE_SPACING,
                BOX_FONT_SIZE * BOX_LINE_SPACING * 1.6f);
        float top = contentBottom > 0f ? contentBottom + CONTENT_GAP : TOP_MARGIN;
        float maxTop = pageHeight - PAGE_MARGIN - height;
        if (top > maxTop) {
            top = Math.max(PAGE_MARGIN, maxTop);
        }
        float left = PAGE_MARGIN;
        float right = Math.max(left + BOX_FONT_SIZE * 4f, pageWidth - PAGE_MARGIN);
        return new float[]{left, top, right, top + height};
    }
}
