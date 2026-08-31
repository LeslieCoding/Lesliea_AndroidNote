package com.leslie.colorosnote.core.security;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ImportLimitsTest {

    private static final byte[] PDF_HEADER = "%PDF-1.7".getBytes();

    @Test
    public void acceptsReasonablePdf() {
        assertNull(ImportLimits.validate("lecture.pdf", 10_000_000, PDF_HEADER));
    }

    @Test
    public void rejectsOversize() {
        assertNotNull(ImportLimits.validate("huge.pdf",
                ImportLimits.MAX_IMPORT_FILE_BYTES + 1, PDF_HEADER));
    }

    @Test
    public void rejectsMismatchedContent() {
        // .pdf extension but PNG content
        assertNotNull(ImportLimits.validate("fake.pdf", 1000,
                new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47}));
    }

    @Test
    public void acceptsMatchingDocx() {
        assertNull(ImportLimits.validate("slides.docx", 1000,
                new byte[]{0x50, 0x4B, 0x03, 0x04}));
    }
}
