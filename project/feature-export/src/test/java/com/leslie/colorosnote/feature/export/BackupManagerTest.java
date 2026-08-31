package com.leslie.colorosnote.feature.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

public class BackupManagerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File fakeNote(File dir, String name, String content) throws Exception {
        File f = new File(dir, name);
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(content.getBytes("UTF-8"));
        }
        return f;
    }

    @Test
    public void exportThenRestoreRoundTrip() throws Exception {
        File docs = tmp.newFolder("docs");
        fakeNote(docs, "a.inknote", "AAA");
        File sub = new File(docs, "sub");
        //noinspection ResultOfMethodCallIgnored
        sub.mkdirs();
        fakeNote(sub, "b.inknote", "BBB");

        File zip = tmp.newFile("backup.zip");
        int count = BackupManager.exportLibrary(docs, zip, null);
        assertEquals(2, count);

        File restoreDir = tmp.newFolder("restore");
        List<String> restored = BackupManager.restoreLibrary(zip, restoreDir);
        assertEquals(2, restored.size());
        assertTrue(new File(restoreDir, "a.inknote").exists());
        assertTrue(new File(restoreDir, "b.inknote").exists());
    }

    @Test
    public void restoreNeverOverwrites() throws Exception {
        File docs = tmp.newFolder("docs");
        fakeNote(docs, "a.inknote", "original");

        File exportSrc = tmp.newFolder("src");
        fakeNote(exportSrc, "a.inknote", "new-content");
        File zip = tmp.newFile("b.zip");
        BackupManager.exportLibrary(exportSrc, zip, null);

        List<String> restored = BackupManager.restoreLibrary(zip, docs);
        assertEquals("a (2).inknote", restored.get(0));
        // original untouched
        assertTrue(new File(docs, "a.inknote").exists());
        assertTrue(new File(docs, "a (2).inknote").exists());
    }

    @Test
    public void ignoresNonInknoteEntries() throws Exception {
        File docs = tmp.newFolder("docs");
        fakeNote(docs, "x.inknote", "X");
        fakeNote(docs, "junk.txt", "junk");
        File zip = tmp.newFile("c.zip");
        assertEquals(1, BackupManager.exportLibrary(docs, zip, null));
    }
}
