package com.leslie.colorosnote.feature.importer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.leslie.colorosnote.document.office.OfficeImportException;

import org.junit.Test;

import java.io.IOException;

/** Exception-to-typed-error mapping (spec 36: 可诊断错误). */
public class ImportErrorTest {

    @Test
    public void typedErrorsPassThrough() {
        ImportError original = ImportError.tooLarge(1024, 512);
        assertSame(original, ImportError.map(ImportStage.VALIDATE, original));
    }

    @Test
    public void officeCancellationMapsToCancelled() {
        ImportError e = ImportError.map(ImportStage.CONVERT, OfficeImportException.cancelled());
        assertEquals(ImportError.Type.CANCELLED, e.getType());
    }

    @Test
    public void officeUnsupportedMapsToUnsupportedFormat() {
        ImportError e = ImportError.map(ImportStage.CONVERT,
                OfficeImportException.unsupported("旧版 .ppt 暂不支持"));
        assertEquals(ImportError.Type.UNSUPPORTED_FORMAT, e.getType());
        // The "UNSUPPORTED: " marker prefix is stripped from the user message.
        assertEquals("旧版 .ppt 暂不支持", e.getUserMessage());
    }

    @Test
    public void officeGenericFailureMapsToConvertFailed() {
        ImportError e = ImportError.map(ImportStage.CONVERT,
                new OfficeImportException("layout exploded"));
        assertEquals(ImportError.Type.CONVERT_FAILED, e.getType());
        assertTrue(e.getDiagnostics().contains("CONVERT"));
    }

    @Test
    public void ioExceptionMapsToIoError() {
        ImportError e = ImportError.map(ImportStage.COPY_TO_SANDBOX,
                new IOException("disk full"));
        assertEquals(ImportError.Type.IO_ERROR, e.getType());
        assertTrue(e.getDiagnostics().contains("disk full"));
    }

    @Test
    public void unexpectedFailureDependsOnStage() {
        RuntimeException boom = new RuntimeException("boom");
        assertEquals(ImportError.Type.CONVERT_FAILED,
                ImportError.map(ImportStage.CONVERT, boom).getType());
        assertEquals(ImportError.Type.CORRUPT,
                ImportError.map(ImportStage.EXTRACT_METADATA, boom).getType());
    }

    @Test
    public void messageIsTheChineseUserMessage() {
        ImportError e = ImportError.contentMismatch("pdf vs png");
        // ImportJob stores getMessage() as its errorMessage: it must be displayable.
        assertEquals(e.getUserMessage(), e.getMessage());
    }
}
