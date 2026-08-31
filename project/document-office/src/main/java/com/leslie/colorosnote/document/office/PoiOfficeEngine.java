package com.leslie.colorosnote.document.office;

import com.leslie.colorosnote.core.common.AppLogger;

import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Built-in engine on Apache POI (poi / poi-ooxml / poi-scratchpad 5.2.5) plus a
 * hand-rolled XmlPull-based .pptx parser.
 *
 * <p>Android compatibility notes (POI is a server-side library):
 * <ul>
 *   <li>We only touch the XmlBeans DOM code paths (XWPF/XSSF/HSSF/HWPF), never the
 *       StAX {@code javax.xml.stream} streaming readers, which Android lacks.</li>
 *   <li>POI's slide APIs (XSLF/HSLF) are bound to AWT (Color, Dimension,
 *       Rectangle2D) which does not exist on Android. Slides therefore bypass POI:
 *       .pptx is parsed directly from the OOXML zip by {@link PptxParser}, and
 *       legacy .ppt is rejected with an explicit UNSUPPORTED error instead of
 *       faking output.</li>
 *   <li>POI needs {@code poi-ooxml} with the full schema jar on Android;
 *       packaging exclusions for duplicate META-INF live in this module's
 *       build.gradle and in :app.</li>
 *   <li>Macros/VBA are never executed — POI is a pure parser with no execution
 *       engine (requirement 12.3). OLE2/OOXML containers are opened read-only.</li>
 *   <li>Everything is bounded by {@link ImportGuard}: file size, block count,
 *       text size, image bytes, page count, deadline and cancellation.</li>
 * </ul>
 *
 * <p>Threading: blocking; run on {@code AppExecutors.io()} or a dedicated thread.
 */
public final class PoiOfficeEngine implements OfficeImportEngine {

    public static final String ENGINE_ID = "apache-poi";

    private static final String TAG = "PoiOfficeEngine";

    @Override
    public String id() {
        return ENGINE_ID;
    }

    @Override
    public boolean supports(OfficeType type) {
        return true; // all three families; legacy .ppt is rejected with UNSUPPORTED at import time
    }

    @Override
    public OfficeImportResult importDocument(File source, OfficeType declaredType,
                                             ImportOptions options, ProgressCallback progress)
            throws OfficeImportException, IOException {
        ProgressCallback cb = progress == null ? ProgressCallback.NONE : progress;
        ImportGuard guard = new ImportGuard(options, cb);

        if (source == null || !source.isFile() || !source.canRead()) {
            throw new OfficeImportException("Source file not readable: " + source);
        }
        if (source.length() > options.getLimits().maxFileBytes) {
            throw new OfficeImportException("File too large to import safely");
        }
        cb.onProgress(2, "reading");
        Container container = sniffContainer(source);

        List<String> warnings = new ArrayList<>();
        ResourceTable resources = new ResourceTable(guard);
        List<PageLayout> pages;

        switch (declaredType) {
            case WORD:
                pages = importWord(source, container, guard, resources, warnings, cb);
                break;
            case SLIDES:
                pages = importSlides(source, container, guard, resources, warnings, cb);
                break;
            case SHEET:
                pages = importSheets(source, container, guard, resources, warnings, cb);
                break;
            default:
                throw new OfficeImportException("Unsupported office type: " + declaredType);
        }
        cb.onProgress(100, "done");
        AppLogger.i(TAG, "Imported " + declaredType + " pages=" + pages.size()
                + " images=" + resources.all().size() + " warnings=" + warnings.size());
        return new OfficeImportResult(pages, resources.all(), warnings);
    }

    // --------------------------------------------------------------- per type

    private List<PageLayout> importWord(File source, Container container, ImportGuard guard,
                                        ResourceTable resources, List<String> warnings,
                                        ProgressCallback cb)
            throws OfficeImportException, IOException {
        WordExtractor extractor = new WordExtractor(guard, resources, warnings);
        List<WordBlock> blocks;
        cb.onProgress(10, "extracting");
        if (container == Container.OOXML) {
            try (InputStream in = new FileInputStream(source);
                 XWPFDocument doc = new XWPFDocument(in)) {
                blocks = extractor.fromXWPF(doc);
            } catch (RuntimeException e) {
                throw new OfficeImportException("Cannot parse .docx (corrupt or unsupported)", e);
            }
        } else if (container == Container.OLE2) {
            try (InputStream in = new FileInputStream(source);
                 HWPFDocument doc = new HWPFDocument(in)) {
                blocks = extractor.fromHWPF(doc);
            } catch (RuntimeException e) {
                throw new OfficeImportException("Cannot parse legacy .doc (corrupt or unsupported)", e);
            }
        } else {
            throw new OfficeImportException("Not a Word container (expected zip/OOXML or OLE2)");
        }
        cb.onProgress(45, "layout");
        List<PageLayout> pages = new WordPageLayouter(guard).layout(blocks);
        cb.onProgress(90, "layout");
        return pages;
    }

    private List<PageLayout> importSlides(File source, Container container, ImportGuard guard,
                                          ResourceTable resources, List<String> warnings,
                                          ProgressCallback cb)
            throws OfficeImportException, IOException {
        if (container == Container.OLE2) {
            // POI HSLF (the only Java parser for legacy .ppt) is bound to
            // AWT, which Android does not have — there is no honest
            // on-device path here. Fail loudly; do not fake output.
            throw OfficeImportException.unsupported(
                    "Legacy .ppt files cannot be imported on-device. "
                            + "Please convert the file to .pptx or PDF first, then import again.");
        }
        if (container != Container.OOXML) {
            throw new OfficeImportException("Not a PowerPoint container (expected zip/OOXML)");
        }
        cb.onProgress(10, "extracting");
        List<PageLayout> pages = new PptxParser(guard, resources, warnings).parse(source);
        cb.onProgress(90, "layout");
        return pages;
    }

    private List<PageLayout> importSheets(File source, Container container, ImportGuard guard,
                                          ResourceTable resources, List<String> warnings,
                                          ProgressCallback cb)
            throws OfficeImportException, IOException {
        if (container != Container.OOXML && container != Container.OLE2) {
            throw new OfficeImportException("Not an Excel container (expected zip/OOXML or OLE2)");
        }
        cb.onProgress(10, "extracting");
        // WorkbookFactory handles both XSSF (.xlsx) and HSSF (.xls).
        try (InputStream in = new FileInputStream(source);
             Workbook wb = WorkbookFactory.create(in)) {
            List<PageLayout> pages = new SheetLayouter(guard, warnings).fromWorkbook(wb);
            cb.onProgress(90, "layout");
            return pages;
        } catch (RuntimeException e) {
            throw new OfficeImportException("Cannot parse spreadsheet (corrupt or unsupported)", e);
        }
    }

    // ---------------------------------------------------------------- helpers

    private enum Container {OOXML, OLE2, UNKNOWN}

    /** Magic-byte sniffing: PK zip for OOXML, D0CF11E0 for OLE2 compound files. */
    private static Container sniffContainer(File source) throws IOException {
        byte[] head = new byte[4];
        int n;
        try (InputStream in = new FileInputStream(source)) {
            n = in.read(head);
        }
        if (n >= 2 && head[0] == 'P' && head[1] == 'K') {
            return Container.OOXML;
        }
        if (n >= 4 && (head[0] & 0xFF) == 0xD0 && (head[1] & 0xFF) == 0xCF
                && (head[2] & 0xFF) == 0x11 && (head[3] & 0xFF) == 0xE0) {
            return Container.OLE2;
        }
        return Container.UNKNOWN;
    }
}
