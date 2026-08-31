package com.leslie.colorosnote.document.caj;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Pure-JVM tests for the conversion policy (success paths and honest failures). */
public class DefaultCajImportEngineTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private DefaultCajImportEngine engine;
    private File outDir;

    @Before
    public void setUp() {
        engine = new DefaultCajImportEngine();
        outDir = new File(tmp.getRoot(), "out");
    }

    private File src(String name, byte[] content) throws IOException {
        File f = tmp.newFile(name);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(content);
        }
        return f;
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static class Recorder implements ProgressCallback {
        final List<Integer> percents = new ArrayList<>();

        @Override
        public void onProgress(int percent, String stage) {
            percents.add(percent);
        }
    }

    @Test
    public void convertsRealPdfDisguisedAsCaj() throws Exception {
        byte[] pdf = ascii("%PDF-1.7\nbody\n%%EOF\n");
        File f = src("paper.caj", pdf);
        Recorder rec = new Recorder();

        ImportOutcome o = engine.convert(f, outDir, rec);

        assertTrue(o.isSuccess());
        assertEquals(ImportOutcome.Status.SUCCESS, o.getStatus());
        assertNotNull(o.getOutputPdf());
        assertTrue(o.getOutputPdf().getName().endsWith(".pdf"));
        // Content copied verbatim.
        assertEquals(pdf.length, o.getOutputPdf().length());
        // Progress reached 100 monotonically.
        assertEquals(Integer.valueOf(100), rec.percents.get(rec.percents.size() - 1));
        for (int i = 1; i < rec.percents.size(); i++) {
            assertTrue(rec.percents.get(i) >= rec.percents.get(i - 1));
        }
    }

    @Test
    public void extractsEmbeddedPdfFromFakeCaj() throws Exception {
        byte[] pdf = ascii("%PDF-1.4\nfake pages\n%%EOF\n");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(ascii("CAJ"));
        bos.write(new byte[128]);
        bos.write(pdf);
        bos.write(ascii("trailer"));
        File f = src("thesis.caj", bos.toByteArray());

        ImportOutcome o = engine.convert(f, outDir, null);

        assertTrue(o.isSuccess());
        File out = o.getOutputPdf();
        assertEquals("thesis.pdf", out.getName());
        byte[] head = new byte[4];
        try (FileInputStream in = new FileInputStream(out)) {
            //noinspection ResultOfMethodCallIgnored
            in.read(head);
        }
        assertArrayEqualsPdfHeader(head);
        assertEquals(pdf.length, out.length());
    }

    private static void assertArrayEqualsPdfHeader(byte[] head) {
        assertEquals('%', head[0]);
        assertEquals('P', head[1]);
        assertEquals('D', head[2]);
        assertEquals('F', head[3]);
    }

    @Test
    public void unparseableKdhYieldsUnsupportedNeverFakeSuccess() throws Exception {
        File f = src("paper.kdh", ascii("KDH\0proprietary-binary-payload"));
        ImportOutcome o = engine.convert(f, outDir, null);

        assertFalse(o.isSuccess());
        assertEquals(ImportOutcome.Status.UNSUPPORTED_CAJ_VARIANT, o.getStatus());
        assertNull(o.getOutputPdf());
        assertTrue(o.getUserMessage().contains(DefaultCajImportEngine.UNSUPPORTED_HINT));
        // No stray output/temp files.
        File[] leftovers = outDir.isDirectory() ? outDir.listFiles() : null;
        assertTrue(leftovers == null || leftovers.length == 0);
    }

    @Test
    public void unknownBinaryYieldsCorruptNotSuccess() throws Exception {
        File f = src("mystery.caj", ascii("\0\1\2\3random"));
        ImportOutcome o = engine.convert(f, outDir, null);
        assertFalse(o.isSuccess());
        assertEquals(ImportOutcome.Status.CORRUPT_FILE, o.getStatus());
        assertTrue(o.getUserMessage().contains(DefaultCajImportEngine.UNSUPPORTED_HINT));
    }

    @Test
    public void missingFileIsIoError() {
        ImportOutcome o = engine.convert(new File(tmp.getRoot(), "nope.caj"), outDir, null);
        assertEquals(ImportOutcome.Status.IO_ERROR, o.getStatus());
        assertFalse(o.isSuccess());
    }

    @Test
    public void emptyFileIsCorrupt() throws Exception {
        File f = src("empty.caj", new byte[0]);
        ImportOutcome o = engine.convert(f, outDir, null);
        assertEquals(ImportOutcome.Status.CORRUPT_FILE, o.getStatus());
    }

    @Test
    public void outputFileNameSanitizesAndStripsExtension() {
        File out = DefaultCajImportEngine.outputFile(new File("x"), "my:paper?.caj");
        assertEquals("my_paper_.pdf", out.getName());
        File noExt = DefaultCajImportEngine.outputFile(new File("x"), "README");
        assertEquals("README.pdf", noExt.getName());
    }
}
