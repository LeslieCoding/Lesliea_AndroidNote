package com.leslie.colorosnote.feature.importer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.SizeF;

import com.leslie.colorosnote.document.caj.DefaultCajImportEngine;
import com.leslie.colorosnote.document.office.ImportedDocument;
import com.leslie.colorosnote.document.office.ImportedDocumentJson;
import com.leslie.colorosnote.document.office.ImportedPage;
import com.leslie.colorosnote.document.office.OfficeImportException;
import com.leslie.colorosnote.document.office.OfficeType;
import com.leslie.colorosnote.document.pdf.PdfEngine;
import com.leslie.colorosnote.core.model.InknoteContainer;
import com.leslie.colorosnote.core.model.NoteDocument;
import com.leslie.colorosnote.core.model.PageBackground;
import com.leslie.colorosnote.core.security.ImportLimits;
import com.leslie.colorosnote.feature.ocr.OcrCoordinator;
import com.leslie.colorosnote.feature.ocr.OcrEngine;
import com.leslie.colorosnote.feature.ocr.OcrOptions;
import com.leslie.colorosnote.feature.ocr.OcrResult;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pipeline tests driving {@link ImportCoordinator} stage by stage (and end to
 * end through {@link ImportJob}) with fakes for every Android/Room dependency.
 */
public class ImportCoordinatorTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private File sandboxRoot;
    private File thumbnailRoot;
    private FakeDocumentStore store;
    private FakeMedia media;
    private FakeIndexSink indexSink;
    private RecordingOcrWorkSink ocrWorkSink;

    private static final byte[] PDF_BYTES = "%PDF-1.7\nfake pdf body\n%%EOF\n"
            .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PNG_BYTES = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};
    private static final byte[] ZIP_BYTES = {0x50, 0x4B, 0x03, 0x04, 1, 2, 3, 4};

    @Before
    public void setUp() {
        sandboxRoot = new File(tmp.getRoot(), "sandbox");
        thumbnailRoot = new File(tmp.getRoot(), "thumbs");
        store = new FakeDocumentStore();
        media = new FakeMedia();
        indexSink = new FakeIndexSink();
        ocrWorkSink = new RecordingOcrWorkSink();
    }

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

    private static class FakeSource implements ImportSourceResolver {
        final String name;
        final byte[] data;
        final long declaredSize;

        FakeSource(String name, byte[] data) {
            this(name, data, data.length);
        }

        FakeSource(String name, byte[] data, long declaredSize) {
            this.name = name;
            this.data = data;
            this.declaredSize = declaredSize;
        }

        @Override
        public String displayName(String sourceRef) {
            return name;
        }

        @Override
        public long sizeBytes(String sourceRef) {
            return declaredSize;
        }

        @Override
        public InputStream open(String sourceRef) {
            return new ByteArrayInputStream(data);
        }
    }

    /** PdfEngine stub: no Android behavior is exercised (pageSizePt returns null). */
    private static final class FakePdfEngine implements PdfEngine {
        final int pages;
        final boolean textSupported;
        final String textPerPage;
        boolean closed;

        FakePdfEngine(int pages, boolean textSupported, String textPerPage) {
            this.pages = pages;
            this.textSupported = textSupported;
            this.textPerPage = textPerPage;
        }

        @Override
        public void open(ParcelFileDescriptor pfd) {
        }

        @Override
        public void open(ContentResolver resolver, Uri uri) {
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public boolean isOpen() {
            return !closed;
        }

        @Override
        public int pageCount() {
            return pages;
        }

        @Override
        public SizeF pageSizePt(int page) {
            return null; // coordinator falls back to the default A4 size
        }

        @Override
        public Bitmap renderPage(int page, int widthPx, int heightPx) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Bitmap renderPage(int page, int widthPx, int heightPx, int rotation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Bitmap renderPageRegion(int page, RectF regionPt, float scale) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String extractText(int page) {
            return textPerPage;
        }

        @Override
        public boolean supportsTextExtraction() {
            return textSupported;
        }
    }

    private static final class FakePdfFactory implements PdfEngineFactory {
        final FakePdfEngine engine;
        int openCount;

        FakePdfFactory(FakePdfEngine engine) {
            this.engine = engine;
        }

        @Override
        public PdfEngine open(File pdfFile) {
            openCount++;
            return engine;
        }
    }

    private static final class FakeOfficeImporter implements OfficeImporter {
        ImportedDocument result;
        OfficeImportException failure;

        @Override
        public ImportedDocument importDocument(File source, File sandboxDir,
                                               com.leslie.colorosnote.document.office.ImportOptions options,
                                               com.leslie.colorosnote.document.office.ProgressCallback progress)
                throws OfficeImportException {
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }

    private static ImportedDocument twoPageOfficeDoc() {
        List<ImportedPage> pages = new ArrayList<>();
        pages.add(new ImportedPage(1, "Page 1", 800, 1100, Collections.emptyList(), "第一页文本"));
        pages.add(new ImportedPage(2, "Page 2", 800, 1100, Collections.emptyList(), "第二页文本"));
        return new ImportedDocument("deck.pptx", OfficeType.SLIDES, "fake", pages,
                Collections.emptyList(), new File("unused"), Collections.emptyMap());
    }

    private static final class FakeDocumentStore implements DocumentStore {
        NoteDocument saved;
        Map<String, byte[]> savedExtras;
        final List<String> deletedIds = new ArrayList<>();

        @Override
        public void save(NoteDocument document, Map<String, byte[]> extraEntries) {
            saved = document;
            savedExtras = new LinkedHashMap<>(extraEntries);
        }

        @Override
        public NoteDocument importInknote(File containerFile, String newDocumentId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteIncomplete(String documentId) {
            deletedIds.add(documentId);
        }
    }

    private static final class FakeMedia implements ImportMedia {
        int[] imageSize = {1200, 900};
        boolean pdfThumbCalled;
        boolean imageThumbCalled;

        @Override
        public int[] probeImageSize(File image) {
            return imageSize;
        }

        @Override
        public boolean pdfThumbnail(PdfEngine engine, String docKey, File outDir) {
            pdfThumbCalled = true;
            return true;
        }

        @Override
        public boolean imageThumbnail(File image, File outDir) {
            imageThumbCalled = true;
            return true;
        }
    }

    private static class FakeIndexSink implements IndexSink {
        final List<String> entries = new ArrayList<>();

        @Override
        public void index(String documentId, String pageId, Source source,
                          boolean lowConfidence, String text) {
            entries.add(documentId + '|' + pageId + '|' + source + '|' + text);
        }
    }

    private static final class RecordingOcrWorkSink implements OcrWorkSink {
        final List<String> scheduled = new ArrayList<>();

        @Override
        public void schedulePdfOcr(String documentId, int pageCount) {
            scheduled.add(documentId + ':' + pageCount);
        }
    }

    private static OcrCoordinator ocrCoordinator() {
        return new OcrCoordinator(new OcrEngine() {
            @Override
            public OcrResult recognize(Bitmap bitmap, OcrOptions options) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public void close() {
            }
        });
    }

    private ImportCoordinator coordinator(ImportSourceResolver sources,
                                          PdfEngineFactory pdfFactory,
                                          OfficeImporter officeImporter) {
        return new ImportCoordinator(sources, pdfFactory, officeImporter,
                new DefaultCajImportEngine(), ocrCoordinator(), store, media,
                sandboxRoot, thumbnailRoot, indexSink, ocrWorkSink);
    }

    private static ImportJob job(ImportCoordinator coordinator, String id) {
        return new ImportJob(id, "content://fake/" + id, coordinator, null);
    }

    private static void runThrough(ImportCoordinator c, ImportJob j, ImportStage last)
            throws Exception {
        for (ImportStage stage : ImportStage.values()) {
            c.runStage(stage, j);
            if (stage == last) {
                return;
            }
        }
    }

    // ------------------------------------------------------------------
    // Sandbox copy
    // ------------------------------------------------------------------

    @Test
    public void copyToSandboxStreamsContentVerbatim() throws Exception {
        ImportCoordinator c = coordinator(new FakeSource("doc.pdf", PDF_BYTES),
                new FakePdfFactory(new FakePdfEngine(2, true, "text")), new FakeOfficeImporter());
        ImportJob j = job(c, "j-copy");
        runThrough(c, j, ImportStage.COPY_TO_SANDBOX);

        ImportCoordinator.JobContext ctx = c.contextOf("j-copy");
        assertNotNull(ctx);
        assertTrue(ctx.sandboxFile.isFile());
        assertArrayEquals(PDF_BYTES, Files.readAllBytes(ctx.sandboxFile.toPath()));
        assertEquals(PDF_BYTES.length, ctx.sizeBytes);
        c.discard("j-copy");
    }

    @Test
    public void copyAbortsBeyondSizeCapAndCleansPartialFile() {
        byte[] big = new byte[100_000];
        System.arraycopy(PDF_BYTES, 0, big, 0, PDF_BYTES.length);
        // Tiny cap override: no need to allocate the real 512 MB in a test.
        ImportCoordinator c = new ImportCoordinator(
                new FakeSource("big.pdf", big, -1L),
                new FakePdfFactory(new FakePdfEngine(1, true, "x")), new FakeOfficeImporter(),
                new DefaultCajImportEngine(), null, store, media,
                sandboxRoot, thumbnailRoot, null, null, 8L);
        ImportJob j = job(c, "j-big");
        assertEquals(ImportJob.Status.FAILED, j.run());
        assertEquals(ImportStage.COPY_TO_SANDBOX, j.getFailedStage());
        // Whole sandbox removed, nothing half-written left behind.
        assertFalse(new File(sandboxRoot, "j-big").exists());
        assertNull(c.contextOf("j-big"));
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    @Test
    public void validateRejectsDeclaredOversize() {
        ImportCoordinator c = coordinator(
                new FakeSource("huge.pdf", PDF_BYTES, ImportLimits.MAX_IMPORT_FILE_BYTES + 1),
                new FakePdfFactory(new FakePdfEngine(1, true, "x")), new FakeOfficeImporter());
        ImportJob j = job(c, "j-huge");
        assertEquals(ImportJob.Status.FAILED, j.run());
        assertEquals(ImportStage.VALIDATE, j.getFailedStage());
        assertTrue(j.getErrorMessage().contains("文件过大"));
    }

    @Test
    public void validateRejectsContentExtensionMismatch() {
        ImportCoordinator c = coordinator(new FakeSource("fake.pdf", PNG_BYTES),
                new FakePdfFactory(new FakePdfEngine(1, true, "x")), new FakeOfficeImporter());
        ImportJob j = job(c, "j-mismatch");
        assertEquals(ImportJob.Status.FAILED, j.run());
        assertEquals(ImportStage.VALIDATE, j.getFailedStage());
        assertTrue(j.getErrorMessage().contains("不符"));
    }

    // ------------------------------------------------------------------
    // CAJ -> PDF chain
    // ------------------------------------------------------------------

    private static byte[] fakeCajWithEmbeddedPdf() {
        byte[] head = "CAJ-viewer-cache\0\0\0\0".getBytes(StandardCharsets.US_ASCII);
        byte[] pdf = "%PDF-1.4\nembedded body\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
        byte[] tail = "trailing-caj-data".getBytes(StandardCharsets.US_ASCII);
        byte[] all = new byte[head.length + pdf.length + tail.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(pdf, 0, all, head.length, pdf.length);
        System.arraycopy(tail, 0, all, head.length + pdf.length, tail.length);
        return all;
    }

    @Test
    public void cajWithEmbeddedPdfSwitchesToPdfPipeline() throws Exception {
        FakePdfFactory pdfFactory = new FakePdfFactory(new FakePdfEngine(3, true, "text"));
        ImportCoordinator c = coordinator(new FakeSource("paper.caj", fakeCajWithEmbeddedPdf()),
                pdfFactory, new FakeOfficeImporter());
        ImportJob j = job(c, "j-caj");
        runThrough(c, j, ImportStage.CONVERT);

        ImportCoordinator.JobContext ctx = c.contextOf("j-caj");
        assertNotNull(ctx);
        assertEquals(ImportKind.PDF, ctx.kind); // converted: now on the PDF path
        assertNotNull(ctx.effectiveFile);
        assertTrue(ctx.effectiveFile.getName().endsWith(".pdf"));
        byte[] extracted = Files.readAllBytes(ctx.effectiveFile.toPath());
        assertEquals('%', extracted[0]);
        assertEquals(1, pdfFactory.openCount);
        assertEquals(3, ctx.pageCount);
        c.discard("j-caj");
    }

    @Test
    public void cajUnsupportedVariantFailsWithClearMessage() {
        byte[] unparseable = "CAJ-but-no-pdf-inside-at-all".getBytes(StandardCharsets.US_ASCII);
        ImportCoordinator c = coordinator(new FakeSource("paper.caj", unparseable),
                new FakePdfFactory(new FakePdfEngine(1, true, "x")), new FakeOfficeImporter());
        ImportJob j = job(c, "j-caj-bad");
        assertEquals(ImportJob.Status.FAILED, j.run());
        assertEquals(ImportStage.CONVERT, j.getFailedStage());
        assertTrue(j.getErrorMessage().contains("PDF")); // 请先转换为 PDF 再导入
        assertFalse(new File(sandboxRoot, "j-caj-bad").exists());
        assertNull(c.contextOf("j-caj-bad"));
    }

    // ------------------------------------------------------------------
    // Office dispatch + failure cleanup
    // ------------------------------------------------------------------

    @Test
    public void officeImportBuildsDocumentWithOfficeBackgrounds() {
        FakeOfficeImporter office = new FakeOfficeImporter();
        office.result = twoPageOfficeDoc();
        ImportCoordinator c = coordinator(new FakeSource("deck.pptx", ZIP_BYTES),
                new FakePdfFactory(new FakePdfEngine(1, true, "x")), office);
        ImportJob j = job(c, "j-office");
        assertEquals(ImportJob.Status.SUCCEEDED, j.run());

        NoteDocument doc = store.saved;
        assertNotNull(doc);
        assertEquals(NoteDocument.DocType.OFFICE_IMPORT, doc.docType);
        assertEquals("deck", doc.title);
        assertEquals(2, doc.pages.size());
        assertEquals(PageBackground.Type.OFFICE, doc.pages.get(0).background.type);
        assertEquals(Integer.valueOf(0), doc.pages.get(0).background.pdfPageIndex);
        assertEquals(Integer.valueOf(1), doc.pages.get(1).background.pdfPageIndex);
        assertEquals(800f, doc.pages.get(0).width, 0.01f);
        // Text layers were indexed per page plus the file name segment.
        assertEquals(3, indexSink.entries.size());
        assertTrue(indexSink.entries.get(0).contains(IndexSink.Source.FILE_NAME.name()));
        assertTrue(indexSink.entries.get(1).contains(IndexSink.Source.OFFICE_TEXT.name()));
        // The serialized layout rides along as a container extra so the editor
        // can re-render Office backgrounds after the sandbox is gone.
        byte[] layout = store.savedExtras.get(
                InknoteContainer.resourcePath(ImportCoordinator.OFFICE_LAYOUT_RESOURCE_ID));
        assertNotNull(layout);
        ImportedDocument restored = ImportedDocumentJson.fromBytes(layout, new File("unused"));
        assertEquals(2, restored.getPages().size());
        assertEquals("第一页文本", restored.getPages().get(0).getTextLayer());
        // Sandbox cleaned on COMMIT, context dropped.
        assertFalse(new File(sandboxRoot, "j-office").exists());
        assertNull(c.contextOf("j-office"));
    }

    @Test
    public void officeConversionFailureCleansSandboxAndMapsError() {
        FakeOfficeImporter office = new FakeOfficeImporter();
        office.failure = OfficeImportException.unsupported("旧版格式暂不支持");
        ImportCoordinator c = coordinator(new FakeSource("old.docx", ZIP_BYTES),
                new FakePdfFactory(new FakePdfEngine(1, true, "x")), office);
        ImportJob j = job(c, "j-office-bad");
        assertEquals(ImportJob.Status.FAILED, j.run());
        assertEquals(ImportStage.CONVERT, j.getFailedStage());
        assertEquals("旧版格式暂不支持", j.getErrorMessage());
        assertFalse(new File(sandboxRoot, "j-office-bad").exists());
    }

    // ------------------------------------------------------------------
    // Full PDF pipeline
    // ------------------------------------------------------------------

    @Test
    public void pdfPipelineCreatesDocumentIndexesAndCommits() {
        String longText = "这一页有足够多的原生文本内容供搜索索引使用";
        FakePdfEngine engine = new FakePdfEngine(2, true, longText);
        ImportCoordinator c = coordinator(new FakeSource("report.pdf", PDF_BYTES),
                new FakePdfFactory(engine), new FakeOfficeImporter());
        ImportJob j = job(c, "j-pdf");
        assertEquals(ImportJob.Status.SUCCEEDED, j.run());

        NoteDocument doc = store.saved;
        assertNotNull(doc);
        assertEquals(NoteDocument.DocType.PDF_IMPORT, doc.docType);
        assertEquals("report", doc.title);
        assertEquals(2, doc.pages.size());
        assertEquals(PageBackground.Type.PDF, doc.pages.get(1).background.type);
        assertEquals(Integer.valueOf(1), doc.pages.get(1).background.pdfPageIndex);
        // The source PDF is embedded as a container resource.
        assertTrue(store.savedExtras.containsKey(
                InknoteContainer.resourcePath(ImportCoordinator.PDF_RESOURCE_ID)));
        assertArrayEquals(PDF_BYTES,
                store.savedExtras.get(InknoteContainer.resourcePath(ImportCoordinator.PDF_RESOURCE_ID)));

        // file name + one PDF_TEXT segment per page.
        assertEquals(3, indexSink.entries.size());
        assertTrue(indexSink.entries.get(1).contains(IndexSink.Source.PDF_TEXT.name()));
        assertTrue(indexSink.entries.get(1).contains(doc.pages.get(0).id));

        // Native text was sufficient: OCR skipped, nothing scheduled.
        assertTrue(media.pdfThumbCalled);
        assertTrue(ocrWorkSink.scheduled.isEmpty());
        assertTrue(engine.closed);
        assertFalse(new File(sandboxRoot, "j-pdf").exists());
    }

    @Test
    public void scannedPdfDefersOcrToBackground() {
        // supportsTextExtraction=false -> no native text -> RUN_OCR plan.
        FakePdfEngine engine = new FakePdfEngine(4, false, "");
        ImportCoordinator c = coordinator(new FakeSource("scan.pdf", PDF_BYTES),
                new FakePdfFactory(engine), new FakeOfficeImporter());
        ImportJob j = job(c, "j-scan");
        assertEquals(ImportJob.Status.SUCCEEDED, j.run());
        assertEquals(1, ocrWorkSink.scheduled.size());
        assertTrue(ocrWorkSink.scheduled.get(0).endsWith(":4"));
    }

    // ------------------------------------------------------------------
    // Image import
    // ------------------------------------------------------------------

    @Test
    public void imageImportCreatesSingleImagePage() {
        ImportCoordinator c = coordinator(new FakeSource("photo.png", PNG_BYTES),
                new FakePdfFactory(new FakePdfEngine(1, true, "x")), new FakeOfficeImporter());
        ImportJob j = job(c, "j-img");
        assertEquals(ImportJob.Status.SUCCEEDED, j.run());

        NoteDocument doc = store.saved;
        assertEquals(NoteDocument.DocType.IMAGE_IMPORT, doc.docType);
        assertEquals(1, doc.pages.size());
        assertEquals(PageBackground.Type.IMAGE, doc.pages.get(0).background.type);
        assertEquals(ImportCoordinator.IMAGE_RESOURCE_ID, doc.pages.get(0).background.resourceId);
        assertEquals(1200f, doc.pages.get(0).width, 0.01f);
        assertTrue(store.savedExtras.containsKey(
                InknoteContainer.resourcePath(ImportCoordinator.IMAGE_RESOURCE_ID)));
        assertTrue(media.imageThumbCalled);
    }

    @Test
    public void undecodableImageFailsAsCorrupt() {
        media.imageSize = null; // probe fails
        ImportCoordinator c = coordinator(new FakeSource("broken.png", PNG_BYTES),
                new FakePdfFactory(new FakePdfEngine(1, true, "x")), new FakeOfficeImporter());
        ImportJob j = job(c, "j-img-bad");
        assertEquals(ImportJob.Status.FAILED, j.run());
        assertEquals(ImportStage.EXTRACT_METADATA, j.getFailedStage());
        assertTrue(j.getErrorMessage().contains("图片"));
    }

    // ------------------------------------------------------------------
    // Cancellation + late failure cleanup
    // ------------------------------------------------------------------

    @Test
    public void cancelDuringCopyReportsCancelledAndCleansUp() {
        final ImportJob[] ref = new ImportJob[1];
        final int[] openCount = {0};
        FakeSource source = new FakeSource("doc.pdf", PDF_BYTES) {
            @Override
            public InputStream open(String sourceRef) {
                openCount[0]++;
                if (openCount[0] < 2) {
                    // First open is the VALIDATE header sniff: leave it alone.
                    return new ByteArrayInputStream(data);
                }
                return new ByteArrayInputStream(data) {
                    @Override
                    public synchronized int read(byte[] b, int off, int len) {
                        ref[0].requestCancel();
                        return super.read(b, off, len);
                    }
                };
            }
        };
        ImportCoordinator c = coordinator(source,
                new FakePdfFactory(new FakePdfEngine(1, true, "x")), new FakeOfficeImporter());
        ImportJob j = job(c, "j-cancel");
        ref[0] = j;
        assertEquals(ImportJob.Status.CANCELLED, j.run());
        assertFalse(new File(sandboxRoot, "j-cancel").exists());
        assertNull(c.contextOf("j-cancel"));
        assertTrue(store.deletedIds.isEmpty());
    }

    @Test
    public void failureAfterDocumentSavedDeletesIncompleteDocument() {
        FakeIndexSink explodingSink = new FakeIndexSink() {
            @Override
            public void index(String documentId, String pageId, Source source,
                              boolean lowConfidence, String text) {
                throw new RuntimeException("indexer down");
            }
        };
        ImportCoordinator c = new ImportCoordinator(new FakeSource("doc.pdf", PDF_BYTES),
                new FakePdfFactory(new FakePdfEngine(1, true, "text")),
                new FakeOfficeImporter(), new DefaultCajImportEngine(), null, store, media,
                sandboxRoot, thumbnailRoot, explodingSink, null);
        ImportJob j = job(c, "j-late-fail");
        assertEquals(ImportJob.Status.FAILED, j.run());
        assertEquals(ImportStage.INDEX, j.getFailedStage());
        // The already-saved document is removed again: no half-finished import.
        assertNotNull(store.saved);
        assertEquals(1, store.deletedIds.size());
        assertEquals(store.saved.id, store.deletedIds.get(0));
        assertFalse(new File(sandboxRoot, "j-late-fail").exists());
    }
}
