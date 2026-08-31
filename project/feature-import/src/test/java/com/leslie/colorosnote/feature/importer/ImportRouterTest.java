package com.leslie.colorosnote.feature.importer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.leslie.colorosnote.core.security.FileTypeDetector;

import org.junit.Test;

/** Dispatch-table tests for {@link ImportRouter} (spec 36 DETECT_FORMAT). */
public class ImportRouterTest {

    private static ImportKind route(String name, FileTypeDetector.FileType magic) throws ImportError {
        return ImportRouter.route(name, magic);
    }

    @Test
    public void pdfByExtensionAndMagic() throws Exception {
        assertEquals(ImportKind.PDF, route("paper.pdf", FileTypeDetector.FileType.PDF));
        // No extension: magic alone decides.
        assertEquals(ImportKind.PDF, route("download", FileTypeDetector.FileType.PDF));
    }

    @Test
    public void officeByExtension() throws Exception {
        assertEquals(ImportKind.OFFICE, route("a.docx", FileTypeDetector.FileType.ZIP_CONTAINER));
        assertEquals(ImportKind.OFFICE, route("a.doc", FileTypeDetector.FileType.OLE2));
        assertEquals(ImportKind.OFFICE, route("a.pptx", FileTypeDetector.FileType.ZIP_CONTAINER));
        assertEquals(ImportKind.OFFICE, route("a.ppt", FileTypeDetector.FileType.OLE2));
        assertEquals(ImportKind.OFFICE, route("a.xlsx", FileTypeDetector.FileType.ZIP_CONTAINER));
        assertEquals(ImportKind.OFFICE, route("a.xls", FileTypeDetector.FileType.OLE2));
    }

    @Test
    public void imagesByMagic() throws Exception {
        assertEquals(ImportKind.IMAGE, route("a.png", FileTypeDetector.FileType.PNG));
        assertEquals(ImportKind.IMAGE, route("a.jpg", FileTypeDetector.FileType.JPEG));
        assertEquals(ImportKind.IMAGE, route("a.jpeg", FileTypeDetector.FileType.JPEG));
        assertEquals(ImportKind.IMAGE, route("a.webp", FileTypeDetector.FileType.WEBP));
        assertEquals(ImportKind.IMAGE, route("a.gif", FileTypeDetector.FileType.GIF));
    }

    @Test
    public void cajFamilyByExtension() throws Exception {
        assertEquals(ImportKind.CAJ, route("paper.caj", FileTypeDetector.FileType.CAJ));
        assertEquals(ImportKind.CAJ, route("paper.kdh", FileTypeDetector.FileType.CAJ));
        assertEquals(ImportKind.CAJ, route("paper.nh", FileTypeDetector.FileType.CAJ));
        assertEquals(ImportKind.CAJ, route("paper.teb", FileTypeDetector.FileType.CAJ));
        // A .caj that is really a PDF still goes through the CAJ engine, which
        // short-circuits to a plain copy after content detection.
        assertEquals(ImportKind.CAJ, route("paper.caj", FileTypeDetector.FileType.PDF));
    }

    @Test
    public void inknoteByExtension() throws Exception {
        assertEquals(ImportKind.INKNOTE, route("backup.inknote", FileTypeDetector.FileType.ZIP_CONTAINER));
    }

    @Test
    public void unknownCombinationRejected() {
        ImportError e = assertThrows(ImportError.class,
                () -> route("archive.bin", FileTypeDetector.FileType.UNKNOWN));
        assertEquals(ImportError.Type.UNSUPPORTED_FORMAT, e.getType());
        assertTrue(e.getUserMessage().contains(".bin"));
    }

    @Test
    public void zipContainerWithoutKnownExtensionRejected() {
        // A ZIP that is neither Office nor .inknote is not importable.
        ImportError e = assertThrows(ImportError.class,
                () -> route("mystery.zip", FileTypeDetector.FileType.ZIP_CONTAINER));
        assertEquals(ImportError.Type.UNSUPPORTED_FORMAT, e.getType());
    }
}
