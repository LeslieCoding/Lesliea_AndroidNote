package com.leslie.colorosnote.core.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FileTypeDetectorTest {

    @Test
    public void detectsPdf() {
        assertEquals(FileTypeDetector.FileType.PDF,
                FileTypeDetector.detect("%PDF-1.7".getBytes()));
    }

    @Test
    public void detectsZipForDocx() {
        assertEquals(FileTypeDetector.FileType.ZIP_CONTAINER,
                FileTypeDetector.detect(new byte[]{0x50, 0x4B, 0x03, 0x04, 0x14, 0x00}));
    }

    @Test
    public void detectsOle2ForLegacyDoc() {
        assertEquals(FileTypeDetector.FileType.OLE2,
                FileTypeDetector.detect(new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
                        (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1}));
    }

    @Test
    public void detectsImages() {
        assertEquals(FileTypeDetector.FileType.PNG,
                FileTypeDetector.detect(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A}));
        assertEquals(FileTypeDetector.FileType.JPEG,
                FileTypeDetector.detect(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0}));
    }

    @Test
    public void detectsCaj() {
        assertEquals(FileTypeDetector.FileType.CAJ,
                FileTypeDetector.detect("CAJViewer".getBytes()));
    }

    @Test
    public void unknownForGarbage() {
        assertEquals(FileTypeDetector.FileType.UNKNOWN,
                FileTypeDetector.detect(new byte[]{1, 2, 3, 4, 5}));
    }

    @Test
    public void consistencyCheck() {
        assertTrue(FileTypeDetector.isConsistent("pdf", FileTypeDetector.FileType.PDF));
        assertFalse(FileTypeDetector.isConsistent("pdf", FileTypeDetector.FileType.PNG));
        assertTrue(FileTypeDetector.isConsistent("docx", FileTypeDetector.FileType.ZIP_CONTAINER));
        assertTrue(FileTypeDetector.isConsistent("doc", FileTypeDetector.FileType.OLE2));
    }
}
