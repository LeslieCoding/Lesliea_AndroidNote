package com.leslie.colorosnote.document.office;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/** End-to-end coordinator test: file -> ImportedDocument with resources on disk. */
public class OfficeImportCoordinatorTest {

    private static File writeTemp(byte[] bytes, String suffix) throws Exception {
        File f = File.createTempFile("coordinator-test", suffix);
        f.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(bytes);
        }
        return f;
    }

    private static byte[] buildDocxWithImage() throws Exception {
        XWPFDocument doc = new XWPFDocument();
        XWPFParagraph p = doc.createParagraph();
        XWPFRun run = p.createRun();
        run.setText("Coordinator smoke test paragraph.");
        run.addPicture(new ByteArrayInputStream(WordImportTest.PNG_1PX),
                XWPFDocument.PICTURE_TYPE_PNG, "pixel.png", 96 * 9525, 96 * 9525);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        doc.write(bos);
        doc.close();
        return bos.toByteArray();
    }

    @Test
    public void importWritesResourcesAndBuildsDocument() throws Exception {
        File docx = writeTemp(buildDocxWithImage(), ".docx");
        File sandbox = new File(System.getProperty("java.io.tmpdir"),
                "office-import-sandbox-" + System.nanoTime());
        assertTrue(sandbox.mkdirs());

        List<Integer> progressSeen = new ArrayList<>();
        ImportedDocument imported = new OfficeImportCoordinator().importDocument(
                docx, sandbox, ImportOptions.defaults(), new ProgressCallback() {
                    @Override
                    public void onProgress(int percent, String stage) {
                        progressSeen.add(percent);
                    }
                });

        assertEquals(OfficeType.WORD, imported.getType());
        assertEquals(PoiOfficeEngine.ENGINE_ID, imported.getEngineId());
        assertTrue(imported.getPages().size() >= 1);
        assertTrue(imported.getPages().get(0).getTextLayer().contains("Coordinator smoke test"));

        // Image persisted into the sandbox and resolvable by id.
        assertEquals(1, imported.getResourceFiles().size());
        String id = imported.getResourceFiles().keySet().iterator().next();
        File onDisk = imported.resourceFile(id);
        assertNotNull(onDisk);
        assertTrue(onDisk.isFile());
        assertEquals(WordImportTest.PNG_1PX.length, onDisk.length());
        assertTrue(onDisk.getAbsolutePath().startsWith(sandbox.getAbsolutePath()));

        // Progress reached 100.
        assertTrue(progressSeen.contains(100));

        // Cleanup best effort.
        for (File f : imported.getResourceFiles().values()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        //noinspection ResultOfMethodCallIgnored
        imported.getResourceDir().delete();
        //noinspection ResultOfMethodCallIgnored
        sandbox.delete();
    }

    @Test
    public void unknownExtensionRejected() throws Exception {
        File bogus = writeTemp("x".getBytes("UTF-8"), ".txt");
        File sandbox = new File(System.getProperty("java.io.tmpdir"),
                "office-import-sandbox-" + System.nanoTime());
        try {
            new OfficeImportCoordinator().importDocument(bogus, sandbox);
            org.junit.Assert.fail("expected rejection");
        } catch (OfficeImportException expected) {
            assertTrue(expected.getMessage().contains("Unsupported extension"));
        }
    }
}
