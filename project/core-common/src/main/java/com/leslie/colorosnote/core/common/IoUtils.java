package com.leslie.colorosnote.core.common;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * IO helpers: atomic file writes, directory sizing, and zip-bomb-safe unzip.
 */
public final class IoUtils {

    private static final int BUFFER_SIZE = 64 * 1024;

    private IoUtils() {
    }

    /** Callback for streaming atomic writes. */
    public interface Writer {
        void write(FileOutputStream out) throws IOException;
    }

    /** Atomically writes the given bytes to target (temp file + fsync + rename). */
    public static void atomicWrite(File target, byte[] data) throws IOException {
        atomicWrite(target, out -> out.write(data));
    }

    /**
     * Atomically writes to target: streams into a sibling temp file, fsyncs,
     * then renames over the target. On failure the temp file is deleted and the
     * original target is left untouched.
     */
    public static void atomicWrite(File target, Writer writer) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create directory: " + parent);
        }
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        boolean ok = false;
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            writer.write(out);
            out.flush();
            out.getFD().sync();
            ok = true;
        } finally {
            if (!ok && tmp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
        if (target.exists() && !target.delete()) {
            // Required on platforms where renameTo cannot overwrite.
            tmp.delete();
            throw new IOException("Cannot replace existing file: " + target);
        }
        if (!tmp.renameTo(target)) {
            tmp.delete();
            throw new IOException("Atomic rename failed: " + tmp + " -> " + target);
        }
    }

    /** Recursively computes the total size in bytes of a file or directory. */
    public static long directorySize(File root) {
        if (root == null || !root.exists()) {
            return 0L;
        }
        if (root.isFile()) {
            return root.length();
        }
        long total = 0L;
        File[] children = root.listFiles();
        if (children != null) {
            for (File child : children) {
                total += directorySize(child);
            }
        }
        return total;
    }

    // ------------------------------------------------------------------
    // Safe unzip (zip bomb protection, for core-security import checks)
    // ------------------------------------------------------------------

    /** Limits enforced while extracting a zip archive. */
    public static final class ZipLimits {
        /** Max number of entries allowed in the archive. */
        public int maxEntries = 10_000;
        /** Max cumulative uncompressed bytes across all entries. */
        public long maxTotalUncompressedBytes = 512L * 1024 * 1024; // 512 MB
        /** Max uncompressed/compressed ratio per entry (defends zip bombs). */
        public float maxCompressionRatio = 100f;

        public static ZipLimits defaults() {
            return new ZipLimits();
        }
    }

    /** Thrown when an archive violates the given {@link ZipLimits} or tries path traversal. */
    public static final class UnsafeZipException extends IOException {
        public UnsafeZipException(String message) {
            super(message);
        }
    }

    /**
     * Extracts a zip file while enforcing the given limits:
     * entry count, cumulative uncompressed size, per-entry compression ratio,
     * and zip-slip (path traversal) protection.
     */
    public static void unzipSafely(File zipFile, File destDir, ZipLimits limits) throws IOException {
        String destCanonical = destDir.getCanonicalPath() + File.separator;
        long totalUncompressed = 0L;
        int entryCount = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > limits.maxEntries) {
                    throw new UnsafeZipException("Too many entries (> " + limits.maxEntries + ")");
                }
                File outFile = new File(destDir, entry.getName());
                if (!outFile.getCanonicalPath().startsWith(destCanonical)) {
                    throw new UnsafeZipException("Zip slip entry: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    if (!outFile.exists() && !outFile.mkdirs()) {
                        throw new IOException("Cannot create directory: " + outFile);
                    }
                    continue;
                }
                long declaredSize = entry.getSize();
                long compressedSize = entry.getCompressedSize();
                if (declaredSize >= 0 && compressedSize > 0
                        && (float) declaredSize / (float) compressedSize > limits.maxCompressionRatio) {
                    throw new UnsafeZipException("Suspicious compression ratio for " + entry.getName());
                }
                File parent = outFile.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("Cannot create directory: " + parent);
                }
                long entryBytes = 0L;
                try (FileOutputStream out = new FileOutputStream(outFile)) {
                    int read;
                    while ((read = zin.read(buffer)) != -1) {
                        entryBytes += read;
                        totalUncompressed += read;
                        if (totalUncompressed > limits.maxTotalUncompressedBytes) {
                            throw new UnsafeZipException("Total uncompressed size exceeds "
                                    + limits.maxTotalUncompressedBytes + " bytes");
                        }
                        out.write(buffer, 0, read);
                    }
                }
                if (compressedSize > 0
                        && (float) entryBytes / (float) compressedSize > limits.maxCompressionRatio) {
                    // Actual ratio check for entries whose declared sizes were unknown.
                    //noinspection ResultOfMethodCallIgnored
                    outFile.delete();
                    throw new UnsafeZipException("Compression ratio too high for " + entry.getName());
                }
            }
        }
    }

    /** Reads an input stream fully into a byte array. */
    public static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
