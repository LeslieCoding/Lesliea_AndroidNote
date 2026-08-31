package com.leslie.colorosnote.document.office;

import com.leslie.colorosnote.core.common.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Android-safe .pptx parser: reads the OOXML package directly with
 * {@link java.util.zip.ZipFile} + {@link XmlPullParser} (built into Android;
 * kxml2 on the JVM test classpath). No POI XSLF and no AWT — XSLF's API
 * surface is bound to AWT, which does not exist on Android.
 *
 * <p>Coverage (deliberately the essentials, requirement 12.2):
 * <ul>
 *   <li>Slide size from {@code ppt/presentation.xml} {@code p:sldSz} (EMU);
 *       each slide becomes one {@link PageLayout} at the native aspect ratio.</li>
 *   <li>{@code p:sp} shapes: {@code a:xfrm} position/size (EMU -> px @96dpi),
 *       solid srgbClr fill as a Rect, text paragraphs {@code a:p}/{@code a:r}/
 *       {@code a:t} with per-run size ({@code a:rPr@sz}, 1/100pt), bold
 *       ({@code @b}) and srgbClr color; {@code a:pPr@algn} maps to alignment.</li>
 *   <li>{@code p:pic} pictures: {@code a:blip@r:embed} resolved through
 *       {@code slideN.xml.rels} to media bytes, registered as ImageResource.</li>
 * </ul>
 *
 * <p>Not covered (recorded as warnings, never silently faked): theme/scheme
 * colors, slide-master/layout inheritance (shapes without explicit {@code a:xfrm}
 * stack from the slide top), {@code p:graphicFrame} tables/charts, rotation,
 * embedded audio/video. Animations are dropped by design.
 */
final class PptxParser {

    private static final String PML = "http://schemas.openxmlformats.org/presentationml/2006/main";
    private static final String DML = "http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final String REL_NS =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    private static final String SLIDE_DIR = "ppt/slides/";
    /** Default slide size 10in x 7.5in when p:sldSz is missing. */
    private static final long DEFAULT_SLIDE_CX_EMU = 9144000L;
    private static final long DEFAULT_SLIDE_CY_EMU = 6858000L;
    private static final float DEFAULT_FONT_PT = 18f;
    private static final int DEFAULT_TEXT_COLOR = 0xFF000000;

    private final ImportGuard guard;
    private final ResourceTable resources;
    private final List<String> warnings;

    PptxParser(ImportGuard guard, ResourceTable resources, List<String> warnings) {
        this.guard = guard;
        this.resources = resources;
        this.warnings = warnings;
    }

    List<PageLayout> parse(File source) throws OfficeImportException, IOException {
        try (ZipFile zip = new ZipFile(source)) {
            long[] slideSize = readSlideSize(zip);
            int pageW = Math.max(1, Math.round(TextMetrics.emuToPx(slideSize[0])));
            int pageH = Math.max(1, Math.round(TextMetrics.emuToPx(slideSize[1])));

            List<String> slideEntries = listSlideEntries(zip);
            if (slideEntries.isEmpty()) {
                throw new OfficeImportException("pptx contains no slides");
            }
            List<PageLayout> pages = new ArrayList<>();
            int index = 0;
            for (String entryName : slideEntries) {
                index++;
                guard.startPage();
                pages.add(parseSlide(zip, entryName, index, pageW, pageH));
            }
            return pages;
        }
    }

    // ------------------------------------------------------------ slide size

    private long[] readSlideSize(ZipFile zip) throws IOException, OfficeImportException {
        ZipEntry entry = zip.getEntry("ppt/presentation.xml");
        if (entry == null) {
            throw new OfficeImportException("Not a valid pptx (missing ppt/presentation.xml)");
        }
        try (InputStream in = zip.getInputStream(entry)) {
            XmlPullParser pp = newParser(in);
            int event = pp.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && is(pp, PML, "sldSz")) {
                    long cx = parseLong(pp.getAttributeValue(null, "cx"), DEFAULT_SLIDE_CX_EMU);
                    long cy = parseLong(pp.getAttributeValue(null, "cy"), DEFAULT_SLIDE_CY_EMU);
                    return new long[]{cx, cy};
                }
                event = pp.next();
            }
        } catch (org.xmlpull.v1.XmlPullParserException e) {
            throw new OfficeImportException("Corrupt ppt/presentation.xml", e);
        }
        return new long[]{DEFAULT_SLIDE_CX_EMU, DEFAULT_SLIDE_CY_EMU};
    }

    private static List<String> listSlideEntries(ZipFile zip) {
        List<String> names = new ArrayList<>();
        java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (name.matches("ppt/slides/slide\\d+\\.xml")) {
                names.add(name);
            }
        }
        Collections.sort(names, Comparator.comparingInt(PptxParser::slideNumber));
        return names;
    }

    private static int slideNumber(String entryName) {
        int start = SLIDE_DIR.length() + "slide".length();
        int end = entryName.length() - ".xml".length();
        try {
            return Integer.parseInt(entryName.substring(start, end));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    // ---------------------------------------------------------------- slides

    private PageLayout parseSlide(ZipFile zip, String entryName, int index, int pageW, int pageH)
            throws OfficeImportException, IOException {
        Map<String, String> rels = readRelationships(zip, relsEntryName(entryName), index);
        List<DrawCommand> commands = new ArrayList<>();
        StringBuilder text = new StringBuilder();

        ZipEntry entry = zip.getEntry(entryName);
        if (entry == null) {
            throw new OfficeImportException("Missing slide entry " + entryName);
        }
        try (InputStream in = zip.getInputStream(entry)) {
            XmlPullParser pp = newParser(in);
            ShapeCtx shape = null;
            ParaCtx para = null;
            RunCtx run = null;
            boolean inXfrm = false;
            boolean inSolidFill = false;
            boolean inRPr = false;
            boolean inText = false;
            int skipDepth = 0; // inside a dropped subtree (graphicFrame)
            float looseTextY = 0f; // stacking position for shapes without xfrm

            int event = initialEvent(pp, entryName);
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    if (skipDepth > 0) {
                        skipDepth++;
                    } else if (is(pp, PML, "graphicFrame")) {
                        warnings.add("Slide " + index + ": table/chart (graphicFrame) dropped");
                        skipDepth = 1;
                    } else if (is(pp, PML, "sp") || is(pp, PML, "pic")) {
                        guard.tickBlock();
                        shape = new ShapeCtx(is(pp, PML, "pic"));
                    } else if (shape != null && is(pp, DML, "xfrm")) {
                        inXfrm = true;
                    } else if (shape != null && inXfrm && is(pp, DML, "off")) {
                        shape.xEmu = parseLong(pp.getAttributeValue(null, "x"), 0);
                        shape.yEmu = parseLong(pp.getAttributeValue(null, "y"), 0);
                        shape.hasXfrm = true;
                    } else if (shape != null && inXfrm && is(pp, DML, "ext")) {
                        shape.cxEmu = parseLong(pp.getAttributeValue(null, "cx"), 0);
                        shape.cyEmu = parseLong(pp.getAttributeValue(null, "cy"), 0);
                    } else if (shape != null && is(pp, DML, "solidFill")) {
                        inSolidFill = true;
                    } else if (shape != null && inSolidFill && is(pp, DML, "srgbClr")) {
                        int color = parseColor(pp.getAttributeValue(null, "val"));
                        if (inRPr && run != null) {
                            run.colorArgb = color;
                        } else {
                            shape.fillArgb = color;
                        }
                    } else if (shape != null && inSolidFill && is(pp, DML, "schemeClr")) {
                        shape.sawSchemeColor = true;
                    } else if (shape != null && !shape.isPicture && is(pp, DML, "p")) {
                        para = new ParaCtx();
                    } else if (para != null && is(pp, DML, "pPr")) {
                        para.align = mapAlign(pp.getAttributeValue(null, "algn"));
                    } else if (para != null && (is(pp, DML, "r") || is(pp, DML, "fld"))) {
                        run = new RunCtx();
                    } else if (run != null && is(pp, DML, "rPr")) {
                        inRPr = true;
                        String sz = pp.getAttributeValue(null, "sz");
                        if (sz != null) {
                            run.sizePx = TextMetrics.ptToPx(parseLong(sz, 1800) / 100f);
                        }
                        String b = pp.getAttributeValue(null, "b");
                        run.bold = "1".equals(b) || "true".equals(b);
                    } else if (run != null && is(pp, DML, "t")) {
                        inText = true;
                    } else if (shape != null && shape.isPicture && is(pp, DML, "blip")) {
                        shape.embedRelId = pp.getAttributeValue(REL_NS, "embed");
                    } else if (para != null && is(pp, DML, "br")) {
                        // Line break inside a paragraph: close and reopen.
                        shape.paragraphs.add(para);
                        para = new ParaCtx();
                    }
                } else if (event == XmlPullParser.TEXT) {
                    if (inText && run != null) {
                        run.text.append(pp.getText());
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    if (skipDepth > 0) {
                        skipDepth--;
                    } else if (is(pp, DML, "t")) {
                        inText = false;
                    } else if (is(pp, DML, "rPr")) {
                        inRPr = false;
                    } else if (is(pp, DML, "r") || is(pp, DML, "fld")) {
                        if (para != null && run != null && run.text.length() > 0) {
                            para.runs.add(run);
                        }
                        run = null;
                    } else if (is(pp, DML, "p")) {
                        if (shape != null && para != null) {
                            shape.paragraphs.add(para);
                        }
                        para = null;
                    } else if (is(pp, DML, "xfrm")) {
                        inXfrm = false;
                    } else if (is(pp, DML, "solidFill")) {
                        inSolidFill = false;
                    } else if (is(pp, PML, "sp") || is(pp, PML, "pic")) {
                        if (shape != null) {
                            looseTextY = finishShape(zip, rels, shape, commands, text, index, looseTextY);
                        }
                        shape = null;
                    }
                }
                event = next(pp, entryName);
            }
        }
        return new PageLayout(pageW, pageH, "Slide " + index, commands, text.toString());
    }

    /** Emits the draw commands for one shape; returns the next loose-text y. */
    private float finishShape(ZipFile zip, Map<String, String> rels, ShapeCtx shape,
                              List<DrawCommand> commands, StringBuilder text,
                              int slideIndex, float looseTextY) throws OfficeImportException {
        if (shape.sawSchemeColor) {
            warnings.add("Slide " + slideIndex + ": theme (scheme) color substituted with default");
            shape.sawSchemeColor = false;
        }
        float x = TextMetrics.emuToPx(shape.xEmu);
        float y = TextMetrics.emuToPx(shape.yEmu);
        float w = Math.max(TextMetrics.emuToPx(shape.cxEmu), 1f);
        float h = Math.max(TextMetrics.emuToPx(shape.cyEmu), 1f);
        if (!shape.hasXfrm) {
            // Position normally inherited from layout/master which we do not parse.
            warnings.add("Slide " + slideIndex + ": shape without explicit position stacked at top");
            x = 0f;
            y = looseTextY;
            w = 400f;
        }

        if (shape.isPicture) {
            emitPicture(zip, rels, shape, commands, slideIndex, x, y, w, h);
            return looseTextY;
        }
        if (shape.fillArgb != null && shape.hasXfrm) {
            commands.add(new DrawCommand.Rect(x, y, w, h, shape.fillArgb, true, 0f));
        }
        float cursorY = y;
        for (ParaCtx para : shape.paragraphs) {
            float paraWidth = 0f;
            float paraHeight = TextMetrics.lineHeight(TextMetrics.ptToPx(DEFAULT_FONT_PT));
            for (RunCtx run : para.runs) {
                paraWidth += TextMetrics.measure(run.text.toString(), run.sizePx);
                paraHeight = Math.max(paraHeight, TextMetrics.lineHeight(run.sizePx));
            }
            if (para.runs.isEmpty()) {
                cursorY += paraHeight;
                continue;
            }
            float runX = x;
            if (para.align == TextAlign.CENTER) {
                runX = x + Math.max(0f, (w - paraWidth) / 2f);
            } else if (para.align == TextAlign.RIGHT) {
                runX = x + Math.max(0f, w - paraWidth);
            }
            StringBuilder paraText = new StringBuilder();
            for (RunCtx run : para.runs) {
                String runText = run.text.toString();
                guard.addText(runText.length());
                paraText.append(runText);
                commands.add(new DrawCommand.Text(runX, cursorY + paraHeight * 0.8f, runText,
                        run.sizePx, run.colorArgb, run.bold, false, para.align));
                runX += TextMetrics.measure(runText, run.sizePx);
            }
            text.append(paraText).append('\n');
            cursorY += paraHeight;
        }
        if (!shape.hasXfrm) {
            looseTextY = cursorY + 8f;
        }
        return looseTextY;
    }

    private void emitPicture(ZipFile zip, Map<String, String> rels, ShapeCtx shape,
                             List<DrawCommand> commands, int slideIndex,
                             float x, float y, float w, float h) throws OfficeImportException {
        if (shape.embedRelId == null) {
            warnings.add("Slide " + slideIndex + ": picture without embedded reference dropped");
            return;
        }
        String mediaPath = rels.get(shape.embedRelId);
        ZipEntry media = mediaPath == null ? null : zip.getEntry(mediaPath);
        if (media == null) {
            warnings.add("Slide " + slideIndex + ": missing media for " + shape.embedRelId);
            return;
        }
        try (InputStream in = zip.getInputStream(media)) {
            byte[] bytes = IoUtils.readAll(in);
            String id = resources.add(bytes, extensionOf(mediaPath));
            if (id != null) {
                commands.add(new DrawCommand.Image(x, y, w, h, id));
            }
        } catch (IOException e) {
            warnings.add("Slide " + slideIndex + ": unreadable media " + mediaPath);
        }
    }

    // ------------------------------------------------------------------ rels

    private Map<String, String> readRelationships(ZipFile zip, String relsEntry, int slideIndex)
            throws IOException, OfficeImportException {
        Map<String, String> rels = new HashMap<>();
        ZipEntry entry = zip.getEntry(relsEntry);
        if (entry == null) {
            return rels;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            XmlPullParser pp = newParser(in);
            int event = initialEvent(pp, relsEntry);
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && "Relationship".equals(pp.getName())) {
                    String id = pp.getAttributeValue(null, "Id");
                    String target = pp.getAttributeValue(null, "Target");
                    String type = pp.getAttributeValue(null, "Type");
                    if (id == null || target == null) {
                        event = next(pp, relsEntry);
                        continue;
                    }
                    if (type != null && (type.endsWith("/video") || type.endsWith("/audio")
                            || type.endsWith("/media"))) {
                        warnings.add("Slide " + slideIndex
                                + ": embedded media dropped (audio/video not imported)");
                    } else if (isMediaFileName(target)) {
                        warnings.add("Slide " + slideIndex
                                + ": embedded media dropped (audio/video not imported)");
                    } else {
                        rels.put(id, resolveTarget(SLIDE_DIR, target));
                    }
                }
                event = next(pp, relsEntry);
            }
        }
        return rels;
    }

    private static String relsEntryName(String slideEntry) {
        // ppt/slides/slideN.xml -> ppt/slides/_rels/slideN.xml.rels
        int slash = slideEntry.lastIndexOf('/');
        return slideEntry.substring(0, slash + 1) + "_rels/" + slideEntry.substring(slash + 1) + ".rels";
    }

    /** Resolves a relationship target relative to the slide directory. */
    private static String resolveTarget(String baseDir, String target) {
        if (target.startsWith("/")) {
            return target.substring(1);
        }
        List<String> segments = new ArrayList<>();
        for (String seg : (baseDir + target).split("/")) {
            if (seg.equals("..")) {
                if (!segments.isEmpty()) {
                    segments.remove(segments.size() - 1);
                }
            } else if (!seg.equals(".") && !seg.isEmpty()) {
                segments.add(seg);
            }
        }
        return String.join("/", segments);
    }

    private static boolean isMediaFileName(String target) {
        String lower = target.toLowerCase(java.util.Locale.US);
        return lower.endsWith(".mp4") || lower.endsWith(".avi") || lower.endsWith(".mov")
                || lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".wma")
                || lower.endsWith(".m4a") || lower.endsWith(".wmv");
    }

    // ---------------------------------------------------------------- helpers

    private static XmlPullParser newParser(InputStream in)
            throws OfficeImportException, IOException {
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(true);
            XmlPullParser pp = factory.newPullParser();
            pp.setInput(in, "UTF-8");
            return pp;
        } catch (org.xmlpull.v1.XmlPullParserException e) {
            throw new OfficeImportException("No XML pull parser available", e);
        }
    }

    private static int next(XmlPullParser pp, String what) throws OfficeImportException {
        try {
            return pp.next();
        } catch (org.xmlpull.v1.XmlPullParserException | IOException e) {
            throw new OfficeImportException("Corrupt XML in " + what, e);
        }
    }

    private static int initialEvent(XmlPullParser pp, String what) throws OfficeImportException {
        try {
            return pp.getEventType();
        } catch (org.xmlpull.v1.XmlPullParserException e) {
            throw new OfficeImportException("Corrupt XML in " + what, e);
        }
    }

    /** Namespace-tolerant tag match: local name must fit; namespace if present must fit too. */
    private static boolean is(XmlPullParser pp, String ns, String name) {
        if (!name.equals(pp.getName())) {
            return false;
        }
        String actual = pp.getNamespace();
        return actual == null || actual.isEmpty() || ns.equals(actual);
    }

    private static long parseLong(String value, long fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int parseColor(String hex) {
        if (hex == null || hex.length() != 6) {
            return DEFAULT_TEXT_COLOR;
        }
        try {
            return 0xFF000000 | (Integer.parseInt(hex, 16) & 0xFFFFFF);
        } catch (NumberFormatException e) {
            return DEFAULT_TEXT_COLOR;
        }
    }

    private static TextAlign mapAlign(String algn) {
        if (algn == null) {
            return TextAlign.LEFT;
        }
        switch (algn) {
            case "ctr":
                return TextAlign.CENTER;
            case "r":
                return TextAlign.RIGHT;
            default:
                return TextAlign.LEFT;
        }
    }

    private static String extensionOf(String path) {
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot + 1) : "bin";
    }

    // ------------------------------------------------------------- contexts

    private static final class ShapeCtx {
        final boolean isPicture;
        boolean hasXfrm;
        long xEmu;
        long yEmu;
        long cxEmu;
        long cyEmu;
        Integer fillArgb;
        boolean sawSchemeColor;
        String embedRelId;
        final List<ParaCtx> paragraphs = new ArrayList<>();

        ShapeCtx(boolean isPicture) {
            this.isPicture = isPicture;
        }
    }

    private static final class ParaCtx {
        TextAlign align = TextAlign.LEFT;
        final List<RunCtx> runs = new ArrayList<>();
    }

    private static final class RunCtx {
        float sizePx = TextMetrics.ptToPx(DEFAULT_FONT_PT);
        boolean bold;
        int colorArgb = DEFAULT_TEXT_COLOR;
        final StringBuilder text = new StringBuilder();
    }
}
