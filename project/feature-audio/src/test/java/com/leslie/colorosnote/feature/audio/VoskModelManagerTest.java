package com.leslie.colorosnote.feature.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class VoskModelManagerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File filesDir;
    private VoskModelManager manager;

    @Before
    public void setUp() {
        filesDir = new File(tmp.getRoot(), "files");
        manager = new VoskModelManager(filesDir, (url, expected, dest, cb) -> {
            throw new IOException("no network in tests");
        });
    }

    private File modelDir() {
        return new File(new File(filesDir, "models"), VoskModelManager.MODEL_ID);
    }

    private void writeModelFile(String relative) throws IOException {
        File f = new File(modelDir(), relative);
        assertTrue(f.getParentFile().mkdirs() || f.getParentFile().isDirectory());
        Files.write(f.toPath(), new byte[]{1, 2, 3});
    }

    @Test
    public void notReadyWhenDirMissing() {
        assertFalse(manager.isModelReady());
        assertEquals(VoskModelManager.State.NOT_INSTALLED, manager.state());
    }

    @Test
    public void structureCheckRequiresAllMarkerFiles() throws IOException {
        writeModelFile("am/final.mdl");
        assertFalse(manager.isModelReady());
        writeModelFile("graph/HCLG.fst");
        assertFalse(manager.isModelReady());
        writeModelFile("graph/words.txt");
        assertTrue(manager.isModelReady());
        assertEquals(VoskModelManager.State.READY, manager.state());
    }

    @Test
    public void emptyMarkerFileCountsAsBroken() throws IOException {
        writeModelFile("am/final.mdl");
        writeModelFile("graph/HCLG.fst");
        File words = new File(modelDir(), "graph/words.txt");
        assertTrue(words.getParentFile().isDirectory());
        assertTrue(words.createNewFile()); // zero bytes
        assertFalse(manager.isModelReady());
    }

    @Test
    public void smallModelSplitGraphCountsAsReady() throws IOException {
        // vosk-model-small-cn-0.22 ships HCLr.fst + Gr.fst and no words.txt.
        writeModelFile("am/final.mdl");
        writeModelFile("graph/HCLr.fst");
        assertFalse(manager.isModelReady());
        writeModelFile("graph/Gr.fst");
        assertTrue(manager.isModelReady());
        assertEquals(VoskModelManager.State.READY, manager.state());
    }

    @Test
    public void ensureModelDownloadsUnzipsAndMarksReady() throws Exception {
        File zip = buildModelZip();
        VoskModelManager mgr = new VoskModelManager(filesDir,
                (url, expected, dest, cb) -> {
                    assertEquals(VoskModelManager.MODEL_URL, url);
                    Files.copy(zip.toPath(), dest.toPath());
                    if (cb != null) cb.onProgress(95, "downloading");
                });
        mgr.ensureModel(null);
        assertTrue(mgr.isModelReady());
        // Temp zip cleaned up after extraction.
        File[] leftovers = new File(filesDir, "models").listFiles((d, n) -> n.endsWith(".tmp"));
        assertTrue(leftovers == null || leftovers.length == 0);
    }

    @Test
    public void ensureModelNoopWhenAlreadyReady() throws Exception {
        writeModelFile("am/final.mdl");
        writeModelFile("graph/HCLG.fst");
        writeModelFile("graph/words.txt");
        manager.ensureModel(null); // downloader would throw if invoked
        assertTrue(manager.isModelReady());
    }

    @Test
    public void downloadFailureSurfacesChineseError() {
        try {
            manager.ensureModel(null);
            fail("expected IOException");
        } catch (IOException e) {
            assertEquals("no network in tests", e.getMessage());
        }
        assertEquals(VoskModelManager.State.ERROR, manager.state());
        assertFalse(manager.isModelReady());
    }

    @Test
    public void corruptedZipFailsVerification() throws Exception {
        // Zip without the required model structure.
        File zip = tmp.newFile("bad.zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("vosk-model-small-cn-0.22/README"));
            out.write("junk".getBytes());
            out.closeEntry();
        }
        VoskModelManager mgr = new VoskModelManager(filesDir,
                (url, expected, dest, cb) -> Files.copy(zip.toPath(), dest.toPath()));
        try {
            mgr.ensureModel(null);
            fail("expected IOException");
        } catch (IOException e) {
            assertEquals("语音模型解压后校验失败，请重试", e.getMessage());
        }
        assertFalse(mgr.isModelReady());
    }

    @Test
    public void deleteModelRemovesDirectory() throws IOException {
        writeModelFile("am/final.mdl");
        writeModelFile("graph/HCLG.fst");
        writeModelFile("graph/words.txt");
        assertTrue(manager.deleteModel());
        assertFalse(manager.isModelReady());
    }

    private File buildModelZip() throws IOException {
        File zip = tmp.newFile("model.zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            String base = VoskModelManager.MODEL_ID + "/";
            out.putNextEntry(new ZipEntry(base + "am/"));
            out.closeEntry();
            out.putNextEntry(new ZipEntry(base + "am/final.mdl"));
            out.write(new byte[100]);
            out.closeEntry();
            out.putNextEntry(new ZipEntry(base + "graph/HCLG.fst"));
            out.write(new byte[100]);
            out.closeEntry();
            out.putNextEntry(new ZipEntry(base + "graph/words.txt"));
            out.write("你好 1\n".getBytes());
            out.closeEntry();
        }
        return zip;
    }
}
