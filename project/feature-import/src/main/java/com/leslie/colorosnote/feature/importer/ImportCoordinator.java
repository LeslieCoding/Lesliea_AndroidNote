package com.leslie.colorosnote.feature.importer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.leslie.colorosnote.core.common.AppLogger;
import com.leslie.colorosnote.core.common.IdGenerator;
import com.leslie.colorosnote.core.common.IoUtils;
import com.leslie.colorosnote.core.model.InknoteContainer;
import com.leslie.colorosnote.core.model.NoteDocument;
import com.leslie.colorosnote.core.model.Page;
import com.leslie.colorosnote.core.model.PageBackground;
import com.leslie.colorosnote.core.security.FileTypeDetector;
import com.leslie.colorosnote.core.security.ImportLimits;
import com.leslie.colorosnote.document.caj.CajImportEngine;
import com.leslie.colorosnote.document.caj.ImportOutcome;
import com.leslie.colorosnote.document.office.ImportOptions;
import com.leslie.colorosnote.document.office.ImportedDocument;
import com.leslie.colorosnote.document.office.ImportedDocumentJson;
import com.leslie.colorosnote.document.office.ImportedPage;
import com.leslie.colorosnote.document.office.OfficeImportException;
import com.leslie.colorosnote.document.office.ProgressCallback;
import com.leslie.colorosnote.document.pdf.PdfEngine;
import com.leslie.colorosnote.document.pdf.PdfTextExtractor;
import com.leslie.colorosnote.feature.ocr.OcrCoordinator;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wires the 12 {@link ImportStage}s of {@link ImportJob} to the real
 * implementations (spec 36): SAF source → validation → sandbox copy → format
 * routing → metadata → conversion → thumbnails → text → OCR decision →
 * document creation → indexing → commit.
 *
 * <p>Threading: every stage performs blocking IO and must run on a worker
 * thread (the manager schedules jobs on {@code AppExecutors.io()}); nothing
 * here touches the main thread.
 *
 * <p>Failure contract: any stage failure (or cancellation) removes the whole
 * per-job sandbox, the thumbnail dir and any partially persisted document —
 * the library never sees a half-finished import (spec 36). Stages are written
 * idempotently so {@link ImportJob#retry()} can re-run the pipeline.
 *
 * <p>Android/Room dependencies sit behind injected interfaces
 * ({@link ImportSourceResolver}, {@link PdfEngineFactory}, {@link OfficeImporter},
 * {@link ImportMedia}, {@link DocumentStore}), so the pipeline is drivable from
 * plain JVM tests stage by stage.
 */
public final class ImportCoordinator implements ImportJob.StageHandler {

    private static final String TAG = "ImportCoordinator";

    /** Header bytes sniffed for magic detection. */
    static final int HEADER_SNIFF_BYTES = 16;
    /** Container resource id under which the imported PDF itself is stored. */
    static final String PDF_RESOURCE_ID = "source.pdf";
    /** Container resource id prefix for an imported image page. */
    static final String IMAGE_RESOURCE_ID = "image_0";
    /**
     * Container resource id of the serialized Office layout
     * ({@link ImportedDocumentJson}); the editor re-renders page backgrounds
     * from it. Sits under resources/, an extras prefix the container keeps.
     */
    static final String OFFICE_LAYOUT_RESOURCE_ID = "office.json";

    private static final int COPY_BUFFER_BYTES = 64 * 1024;

    /** Per-job mutable state carried across stages. Package-visible for tests. */
    static final class JobContext {
        final String jobId;
        /** Pre-generated id of the document this import will create. */
        final String documentId = IdGenerator.newId();
        final long startedAtMs = System.currentTimeMillis();

        String sourceRef;
        String fileName = "imported";
        long declaredSizeBytes = -1L;
        byte[] header = new byte[0];

        File sandboxDir;
        File thumbDir;
        /** Byte-identical copy of the source inside the sandbox. */
        File sandboxFile;
        long sizeBytes;

        ImportKind kind;
        /** After CAJ conversion: the extracted PDF (otherwise equals sandboxFile). */
        File effectiveFile;

        PdfEngine pdfEngine;
        int pageCount;
        /** Logical page sizes in points/pixels: [width, height] per page. */
        final List<float[]> pageSizes = new ArrayList<>();

        ImportedDocument importedOffice;
        int[] imageSize;

        final List<String> pageTexts = new ArrayList<>();
        int nativeTextLength;
        boolean ocrPlanned;

        NoteDocument document;
        boolean documentSaved;

        JobContext(String jobId) {
            this.jobId = jobId;
        }
    }

    private final ImportSourceResolver sources;
    private final PdfEngineFactory pdfFactory;
    private final OfficeImporter officeImporter;
    private final CajImportEngine cajEngine;
    @Nullable
    private final OcrCoordinator ocrCoordinator;
    private final DocumentStore documentStore;
    private final ImportMedia media;
    private final File sandboxRoot;
    private final File thumbnailRoot;
    @Nullable
    private final IndexSink indexSink;
    @Nullable
    private final OcrWorkSink ocrWorkSink;
    private final long maxFileBytes;

    /** Test seam: contexts of in-flight jobs, keyed by job id. */
    final Map<String, JobContext> contexts = new ConcurrentHashMap<>();

    public ImportCoordinator(@NonNull ImportSourceResolver sources,
                             @NonNull PdfEngineFactory pdfFactory,
                             @NonNull OfficeImporter officeImporter,
                             @NonNull CajImportEngine cajEngine,
                             @Nullable OcrCoordinator ocrCoordinator,
                             @NonNull DocumentStore documentStore,
                             @NonNull ImportMedia media,
                             @NonNull File sandboxRoot,
                             @NonNull File thumbnailRoot,
                             @Nullable IndexSink indexSink,
                             @Nullable OcrWorkSink ocrWorkSink) {
        this(sources, pdfFactory, officeImporter, cajEngine, ocrCoordinator,
                documentStore, media, sandboxRoot, thumbnailRoot, indexSink, ocrWorkSink,
                ImportLimits.MAX_IMPORT_FILE_BYTES);
    }

    /** Package-private constructor with a size-cap override for tests. */
    ImportCoordinator(ImportSourceResolver sources, PdfEngineFactory pdfFactory,
                      OfficeImporter officeImporter, CajImportEngine cajEngine,
                      OcrCoordinator ocrCoordinator, DocumentStore documentStore,
                      ImportMedia media, File sandboxRoot, File thumbnailRoot,
                      IndexSink indexSink, OcrWorkSink ocrWorkSink, long maxFileBytes) {
        this.sources = sources;
        this.pdfFactory = pdfFactory;
        this.officeImporter = officeImporter;
        this.cajEngine = cajEngine;
        this.ocrCoordinator = ocrCoordinator;
        this.documentStore = documentStore;
        this.media = media;
        this.sandboxRoot = sandboxRoot;
        this.thumbnailRoot = thumbnailRoot;
        this.indexSink = indexSink;
        this.ocrWorkSink = ocrWorkSink;
        this.maxFileBytes = maxFileBytes;
    }

    /** Context of an in-flight job, or null. Test/diagnostics hook. */
    @Nullable
    JobContext contextOf(@NonNull String jobId) {
        return contexts.get(jobId);
    }

    // ------------------------------------------------------------------
    // StageHandler
    // ------------------------------------------------------------------

    @WorkerThread
    @Override
    public void runStage(ImportStage stage, ImportJob job) throws Exception {
        JobContext ctx;
        if (stage == ImportStage.SELECTED) {
            ctx = new JobContext(job.getId());
            contexts.put(job.getId(), ctx);
        } else {
            ctx = contexts.get(job.getId());
            if (ctx == null) {
                throw ImportError.io("导入任务状态丢失", "no context for job " + job.getId());
            }
        }
        try {
            dispatch(stage, ctx, job);
        } catch (ImportError e) {
            discard(job.getId());
            if (e.getType() == ImportError.Type.CANCELLED) {
                // Do not rethrow: the state machine reports CANCELLED at the next
                // stage boundary instead of FAILED with a misleading message.
                AppLogger.i(TAG, "job " + job.getId() + " cancelled during " + stage);
                return;
            }
            AppLogger.w(TAG, "job " + job.getId() + " failed at " + stage
                    + " [" + e.getType() + "]: " + e.getDiagnostics());
            throw e;
        } catch (Exception e) {
            ImportError err = ImportError.map(stage, e);
            discard(job.getId());
            AppLogger.w(TAG, "job " + job.getId() + " failed at " + stage
                    + " [" + err.getType() + "]: " + err.getDiagnostics());
            throw err;
        }
    }

    private void dispatch(ImportStage stage, JobContext ctx, ImportJob job) throws Exception {
        switch (stage) {
            case SELECTED:
                stageSelected(ctx, job);
                break;
            case VALIDATE:
                stageValidate(ctx);
                break;
            case COPY_TO_SANDBOX:
                stageCopyToSandbox(ctx, job);
                break;
            case DETECT_FORMAT:
                stageDetectFormat(ctx);
                break;
            case EXTRACT_METADATA:
                stageExtractMetadata(ctx);
                break;
            case CONVERT:
                stageConvert(ctx, job);
                break;
            case GENERATE_THUMBNAILS:
                stageGenerateThumbnails(ctx);
                break;
            case EXTRACT_TEXT:
                stageExtractText(ctx, job);
                break;
            case OCR:
                stageOcr(ctx);
                break;
            case CREATE_DOCUMENT:
                stageCreateDocument(ctx);
                break;
            case INDEX:
                stageIndex(ctx);
                break;
            case COMMIT:
                stageCommit(ctx);
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------
    // Stages
    // ------------------------------------------------------------------

    private void stageSelected(JobContext ctx, ImportJob job) throws IOException, ImportError {
        ctx.sourceRef = job.getSourcePath();
        String name = sources.displayName(ctx.sourceRef);
        if (name != null && !name.trim().isEmpty()) {
            ctx.fileName = name.trim();
        }
        ctx.declaredSizeBytes = sources.sizeBytes(ctx.sourceRef);
        ctx.sandboxDir = new File(sandboxRoot, ctx.jobId);
        ctx.thumbDir = new File(thumbnailRoot, ctx.documentId);
        if (!ctx.sandboxDir.exists() && !ctx.sandboxDir.mkdirs()) {
            throw ImportError.io("无法创建导入临时目录", "mkdirs failed: " + ctx.sandboxDir);
        }
        AppLogger.i(TAG, "job " + ctx.jobId + " selected: ext="
                + ImportRouter.extension(ctx.fileName)
                + " declaredSize=" + ctx.declaredSizeBytes);
    }

    private void stageValidate(JobContext ctx) throws IOException, ImportError {
        if (ctx.declaredSizeBytes > maxFileBytes) {
            throw ImportError.tooLarge(ctx.declaredSizeBytes, maxFileBytes);
        }
        byte[] header = new byte[HEADER_SNIFF_BYTES];
        int read;
        try (InputStream in = new BufferedInputStream(sources.open(ctx.sourceRef))) {
            read = in.read(header);
        }
        if (read <= 0) {
            throw ImportError.corrupt("文件为空或无法读取", "header read=" + read);
        }
        if (read < header.length) {
            byte[] trimmed = new byte[read];
            System.arraycopy(header, 0, trimmed, 0, read);
            header = trimmed;
        }
        ctx.header = header;

        long sizeForCheck = Math.max(ctx.declaredSizeBytes, 0L);
        String rejection = ImportLimits.validate(ctx.fileName, sizeForCheck, header);
        if (rejection != null) {
            if (rejection.startsWith("File too large")) {
                throw ImportError.tooLarge(sizeForCheck, maxFileBytes);
            }
            throw ImportError.contentMismatch(rejection + " (file=" + ctx.fileName + ")");
        }
    }

    private void stageCopyToSandbox(JobContext ctx, ImportJob job) throws IOException, ImportError {
        String ext = ImportRouter.extension(ctx.fileName);
        File target = new File(ctx.sandboxDir, "source" + (ext.isEmpty() ? "" : "." + ext));
        long total = 0L;
        boolean cancelled = false;
        try (InputStream in = new BufferedInputStream(sources.open(ctx.sourceRef));
             OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
            byte[] buf = new byte[COPY_BUFFER_BYTES];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (job.isCancelRequested()) {
                    cancelled = true;
                    break;
                }
                total += n;
                if (total > maxFileBytes) {
                    // Close streams before deleting the partial file.
                    try {
                        in.close();
                    } catch (IOException ignored) {
                        // nothing useful to add
                    }
                    out.close();
                    //noinspection ResultOfMethodCallIgnored
                    target.delete();
                    throw ImportError.tooLarge(total, maxFileBytes);
                }
                out.write(buf, 0, n);
            }
        }
        if (cancelled) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            throw ImportError.cancelled();
        }
        ctx.sandboxFile = target;
        ctx.effectiveFile = target;
        ctx.sizeBytes = total;
        AppLogger.i(TAG, "job " + ctx.jobId + " sandboxed " + total + " bytes");
    }

    private void stageDetectFormat(JobContext ctx) throws ImportError {
        FileTypeDetector.FileType magic = FileTypeDetector.detect(ctx.header);
        ctx.kind = ImportRouter.route(ctx.fileName, magic);
        AppLogger.i(TAG, "job " + ctx.jobId + " routed: magic=" + magic + " -> " + ctx.kind);
    }

    private void stageExtractMetadata(JobContext ctx) throws ImportError {
        switch (ctx.kind) {
            case PDF:
                openPdf(ctx, ctx.effectiveFile);
                break;
            case IMAGE:
                ctx.imageSize = media.probeImageSize(ctx.effectiveFile);
                if (ctx.imageSize == null) {
                    throw ImportError.corrupt("图片无法解码，文件可能已损坏",
                            "undecodable: " + ctx.fileName);
                }
                ctx.pageCount = 1;
                break;
            default:
                // Office metadata arrives from the conversion stage; CAJ is not
                // readable until converted; .inknote carries its own metadata.
                AppLogger.d(TAG, "job " + ctx.jobId + " metadata deferred for " + ctx.kind);
                break;
        }
    }

    private void stageConvert(JobContext ctx, ImportJob job) throws Exception {
        switch (ctx.kind) {
            case OFFICE:
                convertOffice(ctx, job);
                break;
            case CAJ:
                convertCaj(ctx);
                break;
            default:
                // PDF/IMAGE need no conversion; .inknote is restored at CREATE_DOCUMENT.
                break;
        }
    }

    private void convertOffice(JobContext ctx, ImportJob job) throws Exception {
        long deadline = System.currentTimeMillis() + ImportLimits.IMPORT_TIMEOUT_MS;
        ImportOptions options = new ImportOptions(
                com.leslie.colorosnote.document.office.ImportLimits.defaults(), deadline);
        ProgressCallback progress = new ProgressCallback() {
            @Override
            public void onProgress(int percent, String stage) {
                AppLogger.d(TAG, "job " + ctx.jobId + " office " + stage + " " + percent + "%");
            }

            @Override
            public boolean isCancelled() {
                return job.isCancelRequested();
            }
        };
        try {
            ctx.importedOffice = officeImporter.importDocument(
                    ctx.sandboxFile, ctx.sandboxDir, options, progress);
        } catch (OfficeImportException | IOException e) {
            throw ImportError.map(ImportStage.CONVERT, e);
        }
        ctx.pageCount = ctx.importedOffice.getPages().size();
        if (ctx.pageCount == 0) {
            throw ImportError.convertFailed("文档没有可导入的页面", "office produced 0 pages");
        }
    }

    private void convertCaj(JobContext ctx) throws ImportError {
        File outDir = new File(ctx.sandboxDir, "caj");
        ImportOutcome outcome = cajEngine.convert(ctx.sandboxFile, outDir, null);
        switch (outcome.getStatus()) {
            case SUCCESS:
                // Extracted/copied PDF: continue down the PDF path.
                ctx.effectiveFile = outcome.getOutputPdf();
                ctx.kind = ImportKind.PDF;
                AppLogger.i(TAG, "job " + ctx.jobId + " CAJ converted: " + outcome.getUserMessage());
                openPdf(ctx, ctx.effectiveFile);
                break;
            case UNSUPPORTED_CAJ_VARIANT:
                throw ImportError.unsupportedFormat(outcome.getUserMessage(),
                        "caj engine: " + outcome);
            case CORRUPT_FILE:
                throw ImportError.corrupt(outcome.getUserMessage(), "caj engine: " + outcome);
            case IO_ERROR:
            default:
                throw ImportError.io(outcome.getUserMessage(), "caj engine: " + outcome);
        }
    }

    private void stageGenerateThumbnails(JobContext ctx) {
        boolean ok;
        switch (ctx.kind) {
            case PDF:
                ok = ctx.pdfEngine != null
                        && media.pdfThumbnail(ctx.pdfEngine, ctx.documentId, ctx.thumbDir);
                break;
            case IMAGE:
                ok = media.imageThumbnail(ctx.effectiveFile, ctx.thumbDir);
                break;
            default:
                // Office pages cannot be rasterized without the renderer module;
                // .inknote documents bring their own thumbnails.
                AppLogger.d(TAG, "job " + ctx.jobId + " thumbnails skipped for " + ctx.kind);
                return;
        }
        if (!ok) {
            // Thumbnails are best-effort; never fail the import over them.
            AppLogger.w(TAG, "job " + ctx.jobId + " thumbnail generation failed (non-fatal)");
        }
    }

    private void stageExtractText(JobContext ctx, ImportJob job) {
        ctx.pageTexts.clear();
        switch (ctx.kind) {
            case PDF: {
                if (ctx.pdfEngine == null) {
                    break;
                }
                PdfTextExtractor extractor = new PdfTextExtractor(ctx.pdfEngine);
                for (int p = 0; p < ctx.pageCount; p++) {
                    if (job.isCancelRequested()) {
                        return; // state machine reports CANCELLED at the next boundary
                    }
                    ctx.pageTexts.add(extractor.extractPageText(p));
                }
                break;
            }
            case OFFICE: {
                for (ImportedPage page : ctx.importedOffice.getPages()) {
                    ctx.pageTexts.add(page.getTextLayer());
                }
                break;
            }
            default:
                break;
        }
        int total = 0;
        for (String t : ctx.pageTexts) {
            total += t == null ? 0 : t.length();
        }
        ctx.nativeTextLength = total;
    }

    private void stageOcr(JobContext ctx) {
        if (ocrCoordinator == null) {
            AppLogger.i(TAG, "job " + ctx.jobId + " OCR SKIPPED (no engine installed)");
            return;
        }
        OcrCoordinator.Plan plan = ocrCoordinator.plan(ctx.nativeTextLength);
        if (plan == OcrCoordinator.Plan.USE_NATIVE_TEXT) {
            AppLogger.i(TAG, "job " + ctx.jobId + " OCR SKIPPED (native text, "
                    + ctx.nativeTextLength + " chars)");
            return;
        }
        if (ctx.kind == ImportKind.PDF) {
            // Scanned PDF: OCR needs rendered bitmaps and ML Kit; it runs in the
            // background after COMMIT (spec 36: 后台执行), never blocking import.
            ctx.ocrPlanned = true;
            AppLogger.i(TAG, "job " + ctx.jobId + " OCR deferred to background ("
                    + ctx.pageCount + " pages)");
        } else {
            AppLogger.i(TAG, "job " + ctx.jobId + " OCR SKIPPED (kind " + ctx.kind + ")");
        }
    }

    private void stageCreateDocument(JobContext ctx) throws Exception {
        if (ctx.kind == ImportKind.INKNOTE) {
            ctx.document = documentStore.importInknote(ctx.sandboxFile, ctx.documentId);
            ctx.documentSaved = true;
            AppLogger.i(TAG, "job " + ctx.jobId + " imported .inknote as " + ctx.documentId);
            return;
        }

        long now = System.currentTimeMillis();
        NoteDocument document = new NoteDocument();
        document.id = ctx.documentId;
        document.title = stripExtension(ctx.fileName);
        document.createdAt = now;
        document.updatedAt = now;
        document.pages.clear();

        Map<String, byte[]> extras = new LinkedHashMap<>();
        switch (ctx.kind) {
            case PDF:
                document.docType = NoteDocument.DocType.PDF_IMPORT;
                for (int i = 0; i < ctx.pageCount; i++) {
                    float[] size = ctx.pageSizes.get(i);
                    Page page = newPage(i, size[0], size[1], now);
                    page.background = PageBackground.ofType(PageBackground.Type.PDF);
                    page.background.pdfPageIndex = i;
                    // The editor's PDF engine resolves the source via this id.
                    page.background.resourceId = PDF_RESOURCE_ID;
                    document.pages.add(page);
                }
                extras.put(InknoteContainer.resourcePath(PDF_RESOURCE_ID),
                        readAll(ctx.effectiveFile));
                break;
            case IMAGE:
                document.docType = NoteDocument.DocType.IMAGE_IMPORT;
                Page page = newPage(0, ctx.imageSize[0], ctx.imageSize[1], now);
                page.background = PageBackground.ofType(PageBackground.Type.IMAGE);
                page.background.resourceId = IMAGE_RESOURCE_ID;
                document.pages.add(page);
                extras.put(InknoteContainer.resourcePath(IMAGE_RESOURCE_ID),
                        readAll(ctx.effectiveFile));
                break;
            case OFFICE:
                document.docType = NoteDocument.DocType.OFFICE_IMPORT;
                int index = 0;
                for (ImportedPage imported : ctx.importedOffice.getPages()) {
                    Page officePage = newPage(index, imported.getWidthPx(),
                            imported.getHeightPx(), now);
                    officePage.title = imported.getName() == null ? "" : imported.getName();
                    officePage.background = PageBackground.ofType(PageBackground.Type.OFFICE);
                    officePage.background.pdfPageIndex = imported.getPageNumber() - 1;
                    document.pages.add(officePage);
                    index++;
                }
                // Embedded images extracted by document-office move from the
                // sandbox into the container.
                for (Map.Entry<String, File> e : ctx.importedOffice.getResourceFiles().entrySet()) {
                    extras.put(InknoteContainer.resourcePath(e.getKey()), readAll(e.getValue()));
                }
                // The page layout itself (sizes, text/rect/line/image draw
                // commands) is serialized so the editor can re-render the
                // Office backgrounds long after the sandbox is deleted.
                extras.put(InknoteContainer.resourcePath(OFFICE_LAYOUT_RESOURCE_ID),
                        ImportedDocumentJson.toBytes(ctx.importedOffice));
                break;
            default:
                throw ImportError.convertFailed("内部错误：无法为该格式创建文档", "kind=" + ctx.kind);
        }

        documentStore.save(document, extras);
        ctx.document = document;
        ctx.documentSaved = true;
        AppLogger.i(TAG, "job " + ctx.jobId + " created document " + ctx.documentId
                + " (" + document.docType + ", " + document.pages.size() + " pages)");
    }

    private void stageIndex(JobContext ctx) {
        if (indexSink == null) {
            AppLogger.d(TAG, "job " + ctx.jobId + " INDEX skipped (no IndexSink installed)");
            return;
        }
        NoteDocument document = ctx.document;
        indexSink.index(document.id, "", IndexSink.Source.FILE_NAME, false, document.title);
        IndexSink.Source source = document.docType == NoteDocument.DocType.OFFICE_IMPORT
                ? IndexSink.Source.OFFICE_TEXT : IndexSink.Source.PDF_TEXT;
        for (int i = 0; i < ctx.pageTexts.size() && i < document.pages.size(); i++) {
            String text = ctx.pageTexts.get(i);
            if (text != null && !text.trim().isEmpty()) {
                indexSink.index(document.id, document.pages.get(i).id, source, false, text);
            }
        }
    }

    private void stageCommit(JobContext ctx) {
        contexts.remove(ctx.jobId);
        closeEngine(ctx);
        deleteRecursive(ctx.sandboxDir);
        if (ctx.ocrPlanned && ocrWorkSink != null) {
            try {
                ocrWorkSink.schedulePdfOcr(ctx.documentId, ctx.pageCount);
            } catch (RuntimeException e) {
                // Deferred OCR is best-effort; never fail a committed import over it.
                AppLogger.w(TAG, "failed to schedule OCR for " + ctx.documentId, e);
            }
        }
        AppLogger.i(TAG, "job " + ctx.jobId + " committed in "
                + (System.currentTimeMillis() - ctx.startedAtMs) + " ms");
    }

    // ------------------------------------------------------------------
    // Failure/cancel cleanup (spec 36: 删除不完整临时产物，不留半成品)
    // ------------------------------------------------------------------

    /**
     * Drops every artifact of a job: sandbox, thumbnails, the PDF engine and a
     * partially persisted document. Idempotent; safe after COMMIT (no-op).
     */
    public void discard(@NonNull String jobId) {
        JobContext ctx = contexts.remove(jobId);
        if (ctx == null) {
            return;
        }
        closeEngine(ctx);
        deleteRecursive(ctx.sandboxDir);
        deleteRecursive(ctx.thumbDir);
        if (ctx.documentSaved) {
            documentStore.deleteIncomplete(ctx.documentId);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void openPdf(JobContext ctx, File pdfFile) throws ImportError {
        closeEngine(ctx);
        ctx.pageSizes.clear();
        PdfEngine engine;
        try {
            engine = pdfFactory.open(pdfFile);
        } catch (IOException e) {
            throw ImportError.map(ImportStage.EXTRACT_METADATA, e);
        }
        ctx.pdfEngine = engine;
        int count = engine.pageCount();
        if (count <= 0) {
            throw ImportError.corrupt("PDF 没有页面或已损坏", "pageCount=" + count);
        }
        ctx.pageCount = count;
        for (int i = 0; i < count; i++) {
            float width = Page.DEFAULT_WIDTH;
            float height = Page.DEFAULT_HEIGHT;
            try {
                android.util.SizeF size = engine.pageSizePt(i);
                if (size != null && size.getWidth() > 0 && size.getHeight() > 0) {
                    width = size.getWidth();
                    height = size.getHeight();
                }
            } catch (RuntimeException e) {
                // A single unreadable page size must not fail the whole import.
                AppLogger.w(TAG, "job " + ctx.jobId + " page " + i + " size unreadable", e);
            }
            ctx.pageSizes.add(new float[]{width, height});
        }
    }

    private static Page newPage(int index, float width, float height, long now) {
        Page page = new Page();
        page.index = index;
        page.width = width;
        page.height = height;
        page.createdAt = now;
        page.updatedAt = now;
        return page;
    }

    private static byte[] readAll(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return IoUtils.readAll(in);
        }
    }

    @NonNull
    static String stripExtension(@NonNull String fileName) {
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        return base.isEmpty() ? fileName : base;
    }

    private static void closeEngine(JobContext ctx) {
        if (ctx.pdfEngine != null) {
            try {
                ctx.pdfEngine.close();
            } catch (RuntimeException e) {
                AppLogger.w(TAG, "failed to close PDF engine", e);
            }
            ctx.pdfEngine = null;
        }
    }

    static void deleteRecursive(@Nullable File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.isDirectory() ? file.listFiles() : null;
        if (children != null) {
            for (File child : children) {
                deleteRecursive(child);
            }
        }
        if (!file.delete()) {
            AppLogger.w(TAG, "failed to delete temp artifact " + file);
        }
    }
}
