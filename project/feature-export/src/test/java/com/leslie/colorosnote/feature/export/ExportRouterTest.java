package com.leslie.colorosnote.feature.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.leslie.colorosnote.core.model.NoteDocument;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public class ExportRouterTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void inknoteExportCopiesContainer() throws Exception {
        NoteDocument doc = new NoteDocument();
        doc.title = "周会记录";
        File container = tmp.newFile("src.inknote");
        Files.write(container.toPath(), new byte[]{1, 2, 3, 4});

        ExportOptions options = new ExportOptions();
        options.format = ExportOptions.Format.INKNOTE;
        File outDir = tmp.newFolder("out");

        File out = ExportRouter.export(doc, container, options, outDir, null);
        assertTrue(out.isFile());
        assertEquals("周会记录.inknote", out.getName());
        assertEquals(4, out.length());
    }

    @Test
    public void inknoteExportRequiresContainer() throws Exception {
        NoteDocument doc = new NoteDocument();
        doc.title = "x";
        ExportOptions options = new ExportOptions();
        options.format = ExportOptions.Format.INKNOTE;
        try {
            ExportRouter.export(doc, null, options, tmp.newFolder("out2"), null);
            fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("container"));
        }
    }

    @Test
    public void uniqueNameAvoidsCollisions() throws Exception {
        File dir = tmp.newFolder("uniq");
        assertTrue(new File(dir, "a.pdf").createNewFile());
        assertTrue(new File(dir, "a (2).pdf").createNewFile());
        assertEquals("a (3).pdf", ExportRouter.unique(dir, "a", ".pdf").getName());
    }

    @Test
    public void backupZipRejectedAtDocumentLevel() throws Exception {
        NoteDocument doc = new NoteDocument();
        ExportOptions options = new ExportOptions();
        options.format = ExportOptions.Format.BACKUP_ZIP;
        try {
            ExportRouter.export(doc, null, options, tmp.newFolder("out3"), null);
            fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("BackupManager"));
        }
    }
}
