package com.leslie.colorosnote.feature.export;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Local backup/restore (spec 33): a backup ZIP simply packages every
 * .inknote project file plus a backup manifest. Restore renames on conflict
 * and never silently overwrites. Pure java.io, JVM-testable.
 */
public final class BackupManager {

    private static final String MANIFEST = "backup_manifest.json";

    public interface ProgressCallback {
        void onProgress(int done, int total, String currentName);
    }

    /** Export all .inknote files under documentsDir into one backup zip. */
    public static int exportLibrary(File documentsDir, File outZip, ProgressCallback cb) throws IOException {
        List<File> notes = new ArrayList<>();
        collectInknotes(documentsDir, notes);
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outZip))) {
            String manifest = "{\"schemaVersion\":1,\"count\":" + notes.size() + "}";
            zos.putNextEntry(new ZipEntry(MANIFEST));
            zos.write(manifest.getBytes("UTF-8"));
            zos.closeEntry();
            int i = 0;
            for (File f : notes) {
                if (cb != null) cb.onProgress(i, notes.size(), f.getName());
                zos.putNextEntry(new ZipEntry("notes/" + f.getName()));
                try (InputStream in = new FileInputStream(f)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        zos.write(buf, 0, n);
                    }
                }
                zos.closeEntry();
                i++;
            }
            zos.finish();
        }
        return notes.size();
    }

    /**
     * Restore a backup zip into documentsDir. Conflicting names are renamed
     * ("name (2).inknote"); never overwrites. Returns restored file names.
     */
    public static List<String> restoreLibrary(File backupZip, File documentsDir) throws IOException {
        List<String> restored = new ArrayList<>();
        //noinspection ResultOfMethodCallIgnored
        documentsDir.mkdirs();
        long total = 0;
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(backupZip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory() || !e.getName().startsWith("notes/")
                        || !e.getName().endsWith(".inknote")) {
                    continue;
                }
                // zip-slip + size guard
                String base = new File(e.getName()).getName();
                total += e.getSize() > 0 ? e.getSize() : 0;
                if (total > 4L * 1024 * 1024 * 1024) {
                    throw new IOException("backup too large, aborting");
                }
                File out = uniqueFile(documentsDir, base);
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = zis.read(buf)) >= 0) {
                        fos.write(buf, 0, n);
                    }
                    fos.getFD().sync();
                }
                restored.add(out.getName());
            }
        }
        return restored;
    }

    /** "a.inknote" -> "a (2).inknote" -> "a (3).inknote" ... never overwrites. */
    static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        String stem = name.endsWith(".inknote")
                ? name.substring(0, name.length() - ".inknote".length()) : name;
        String ext = name.endsWith(".inknote") ? ".inknote" : "";
        for (int i = 2; ; i++) {
            File cand = new File(dir, stem + " (" + i + ")" + ext);
            if (!cand.exists()) return cand;
        }
    }

    private static void collectInknotes(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                collectInknotes(f, out);
            } else if (f.getName().endsWith(".inknote")) {
                out.add(f);
            }
        }
    }
}
