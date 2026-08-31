package com.leslie.colorosnote.document.caj;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import java.util.Arrays;

/** Pure-JVM tests for magic detection and embedded-PDF location. */
public class CajFormatDetectorTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private CajFormatDetector detector;

    @Before
    public void setUp() {
        detector = new CajFormatDetector();
    }

    private File fileWith(String name, byte[] content) throws IOException {
        File f = tmp.newFile(name);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(content);
        }
        return f;
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    public void detectsPlainPdfRegardlessOfExtension() throws Exception {
        File f = fileWith("disguised.caj", ascii("%PDF-1.7\n%fake body\n%%EOF\n"));
        CajProbeResult r = detector.probe(f);
        assertEquals(CajFormat.PDF, r.getFormat());
        assertFalse(r.hasEmbeddedPdf());
        assertTrue(r.isConvertible());
    }

    @Test
    public void detectsCajMagic() throws Exception {
        File f = fileWith("a.caj", ascii("CAJ\0\1\2binary-payload-without-pdf"));
        assertEquals(CajFormat.CAJ, detector.probe(f).getFormat());
    }

    @Test
    public void detectsPercentCajMagic() throws Exception {
        File f = fileWith("a.caj", ascii("%CAJ something"));
        assertEquals(CajFormat.CAJ, detector.probe(f).getFormat());
    }

    @Test
    public void detectsHnKdhTebMagics() throws Exception {
        assertEquals(CajFormat.CAJ_HN,
                detector.probe(fileWith("n.nh", ascii("HN\0payload"))).getFormat());
        assertEquals(CajFormat.KDH,
                detector.probe(fileWith("k.kdh", ascii("KDH\0payload"))).getFormat());
        assertEquals(CajFormat.TEB,
                detector.probe(fileWith("t.teb", ascii("TEB\0payload"))).getFormat());
    }

    @Test
    public void unknownMagicYieldsUnknownWithHint() throws Exception {
        File f = fileWith("x.bin", ascii("MZ\u0090\0not-a-caj"));
        CajProbeResult r = detector.probe(f);
        assertEquals(CajFormat.UNKNOWN, r.getFormat());
        assertFalse(r.isConvertible());
        assertTrue(r.getMessage().contains("无法识别"));
    }

    @Test
    public void emptyFileIsUnknown() throws Exception {
        CajProbeResult r = detector.probe(tmp.newFile("empty.caj"));
        assertEquals(CajFormat.UNKNOWN, r.getFormat());
    }

    @Test
    public void locatesEmbeddedPdfRange() throws Exception {
        byte[] pdf = ascii("%PDF-1.4\n1 0 obj fake\n%%EOF\n");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(ascii("CAJ"));
        bos.write(new byte[]{0, 1, 2, 3, 4, 5});           // container header garbage
        bos.write(pdf);
        bos.write(ascii("CAJ-TRAILER-JUNK"));                 // trailing container data
        byte[] blob = bos.toByteArray();

        File f = fileWith("embedded.caj", blob);
        CajProbeResult r = detector.probe(f);

        assertEquals(CajFormat.CAJ, r.getFormat());
        assertTrue(r.hasEmbeddedPdf());
        assertTrue(r.isConvertible());
        assertEquals(9, r.getEmbeddedPdfStart());

        byte[] extracted = Arrays.copyOfRange(blob,
                (int) r.getEmbeddedPdfStart(), (int) r.getEmbeddedPdfEnd());
        assertArrayEquals(pdf, extracted);
    }

    @Test
    public void usesLastEofMarkerNotFirst() throws Exception {
        // An early stray %%EOF inside the payload must not truncate the range.
        byte[] content = ascii("CAJx%PDF-1.4\nbody with stray %%EOF inside\nmore\n%%EOF\ntail");
        File f = fileWith("multi.caj", content);
        CajProbeResult r = detector.probe(f);
        assertTrue(r.hasEmbeddedPdf());
        byte[] extracted = Arrays.copyOfRange(content,
                (int) r.getEmbeddedPdfStart(), (int) r.getEmbeddedPdfEnd());
        String s = new String(extracted, StandardCharsets.US_ASCII);
        assertTrue(s.endsWith("%%EOF\n"));
        assertTrue(s.contains("more"));
    }

    @Test
    public void headerWithoutEofIsNotReportedAsEmbedded() throws Exception {
        // "%PDF" header present but no %%EOF within the scan window: never fake it.
        File f = fileWith("truncated.caj", ascii("CAJ....%PDF-1.4\nno trailer here"));
        CajProbeResult r = detector.probe(f);
        assertFalse(r.hasEmbeddedPdf());
        assertFalse(r.isConvertible());
    }

    @Test
    public void scanWindowLimitHidesFarAwayPdf() throws Exception {
        CajFormatDetector smallWindow = new CajFormatDetector(64);
        byte[] big = new byte[4096];
        Arrays.fill(big, (byte) 'x');
        byte[] pdf = ascii("%PDF-1.4\n%%EOF\n");
        System.arraycopy(pdf, 0, big, 2048, pdf.length);
        File f = fileWith("far.caj", big);
        assertFalse(smallWindow.probe(f).hasEmbeddedPdf());
    }

    @Test
    public void extractEmbeddedPdfWritesExactRangeAtomically() throws Exception {
        byte[] pdf = ascii("%PDF-1.6\nfake\n%%EOF\n");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(ascii("HN"));
        bos.write(new byte[37]);
        bos.write(pdf);
        bos.write(ascii("junk-after"));
        File src = fileWith("ex.nh", bos.toByteArray());

        CajProbeResult r = detector.probe(src);
        assertTrue(r.hasEmbeddedPdf());

        File out = new File(tmp.getRoot(), "out/extracted.pdf");
        long written = detector.extractEmbeddedPdf(
                src, r.getEmbeddedPdfStart(), r.getEmbeddedPdfEnd(), out);

        assertEquals(pdf.length, written);
        byte[] got = new byte[(int) out.length()];
        try (FileInputStream in = new FileInputStream(out)) {
            int off = 0;
            int n;
            while ((n = in.read(got, off, got.length - off)) > 0) {
                off += n;
            }
        }
        assertArrayEquals(pdf, got);
        // No temp file must be left behind.
        assertFalse(new File(out.getAbsolutePath() + ".tmp").exists());
    }

    @Test(expected = IOException.class)
    public void extractRejectsRangeBeyondFile() throws Exception {
        File src = fileWith("short.caj", ascii("CAJ tiny"));
        detector.extractEmbeddedPdf(src, 0, 9999, new File(tmp.getRoot(), "o.pdf"));
    }
}
