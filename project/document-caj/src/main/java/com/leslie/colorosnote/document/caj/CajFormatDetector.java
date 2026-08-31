package com.leslie.colorosnote.document.caj;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Detects the real format of a file from its header bytes and locates complete
 * PDF documents embedded in CAJ-family containers.
 *
 * <p>Pure java.io — no Android dependencies — so the detection/extraction logic is
 * fully unit-testable on the JVM.
 *
 * <p>Embedded-PDF strategy: some CAJ files carry a whole PDF inside (viewer cache /
 * dual-format export). We scan the first {@link #DEFAULT_SCAN_BYTES} for the first
 * {@code %PDF} header and the last {@code %%EOF} after it; that byte range is then
 * extractable as a standalone, valid PDF. Files whose PDF tails sit beyond the scan
 * window are reported as non-embedded — we never claim success without a full
 * {@code %%EOF}.
 */
public final class CajFormatDetector {

    /** How many header bytes magic detection reads. */
    static final int HEADER_BYTES = 16;

    /** Default scan window for embedded PDF search: 32 MB. */
    public static final long DEFAULT_SCAN_BYTES = 32L * 1024 * 1024;

    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F'};
    private static final byte[] EOF_MARKER = {'%', '%', 'E', 'O', 'F'};

    private final long maxScanBytes;

    public CajFormatDetector() {
        this(DEFAULT_SCAN_BYTES);
    }

    public CajFormatDetector(long maxScanBytes) {
        if (maxScanBytes <= 0) {
            throw new IllegalArgumentException("maxScanBytes must be > 0");
        }
        this.maxScanBytes = maxScanBytes;
    }

    /** Reads the header magic and (when needed) scans for an embedded PDF. */
    public CajProbeResult probe(File file) throws IOException {
        if (file == null || !file.isFile()) {
            throw new IOException("Not a regular file: " + file);
        }
        byte[] header = new byte[HEADER_BYTES];
        int read;
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            read = in.read(header);
        }
        if (read <= 0) {
            return CajProbeResult.of(CajFormat.UNKNOWN, "空文件或无法读取文件头");
        }

        CajFormat format = matchMagic(header, read);
        if (format == CajFormat.PDF) {
            return CajProbeResult.of(CajFormat.PDF, "文件内容实为 PDF，可直接导入");
        }

        // CAJ variants and unknown files may still embed a complete PDF.
        EmbeddedRange range = scanEmbeddedPdf(file);
        if (range != null) {
            String container = format == CajFormat.UNKNOWN ? "未知容器" : format.name();
            return CajProbeResult.withEmbeddedPdf(format, range.start, range.endExclusive,
                    container + " 内检测到完整内嵌 PDF，已提取");
        }

        switch (format) {
            case CAJ:
            case CAJ_HN:
            case KDH:
            case TEB:
                return CajProbeResult.of(format,
                        "检测到 CAJ 变体（" + format.name() + "），暂无法解析，请先用外部工具转换为 PDF 再导入");
            default:
                return CajProbeResult.of(CajFormat.UNKNOWN,
                        "无法识别的文件格式，请确认文件未损坏，或先转换为 PDF 再导入");
        }
    }

    /** Matches header bytes against the known magic table. */
    static CajFormat matchMagic(byte[] header, int length) {
        if (startsWith(header, length, PDF_MAGIC)) {
            return CajFormat.PDF;
        }
        if (startsWith(header, length, new byte[]{'%', 'C', 'A', 'J'})
                || startsWith(header, length, new byte[]{'C', 'A', 'J'})) {
            return CajFormat.CAJ;
        }
        if (startsWith(header, length, new byte[]{'H', 'N'})) {
            return CajFormat.CAJ_HN;
        }
        if (startsWith(header, length, new byte[]{'K', 'D', 'H'})) {
            return CajFormat.KDH;
        }
        if (startsWith(header, length, new byte[]{'T', 'E', 'B'})) {
            return CajFormat.TEB;
        }
        return CajFormat.UNKNOWN;
    }

    private static boolean startsWith(byte[] data, int length, byte[] magic) {
        if (length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (data[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    /** Byte range [start, endExclusive) of an embedded PDF; null when none found. */
    static final class EmbeddedRange {
        final long start;
        final long endExclusive;

        EmbeddedRange(long start, long endExclusive) {
            this.start = start;
            this.endExclusive = endExclusive;
        }
    }

    /**
     * Scans up to {@link #maxScanBytes} for the first {@code %PDF} header and the
     * last {@code %%EOF} marker after it. Trailing CR/LF after {@code %%EOF} are
     * included so strict readers do not choke on the cut point.
     *
     * @return the range, or null when no complete PDF was found in the window
     */
    EmbeddedRange scanEmbeddedPdf(File file) throws IOException {
        long length = file.length();
        if (length < PDF_MAGIC.length) {
            return null;
        }
        int window = (int) Math.min(length, maxScanBytes);
        byte[] buf = new byte[window];
        int total = 0;
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            while (total < window) {
                int n = in.read(buf, total, window - total);
                if (n < 0) {
                    break;
                }
                total += n;
            }
        }
        if (total < PDF_MAGIC.length) {
            return null;
        }

        int start = indexOf(buf, total, PDF_MAGIC, 0);
        if (start < 0) {
            return null;
        }
        int eof = lastIndexOf(buf, total, EOF_MARKER, start + PDF_MAGIC.length);
        if (eof < 0) {
            return null;
        }
        int end = eof + EOF_MARKER.length;
        while (end < total && (buf[end] == '\r' || buf[end] == '\n' || buf[end] == ' ')) {
            end++;
        }
        return new EmbeddedRange(start, end);
    }

    /**
     * Copies the byte range [start, endExclusive) of {@code src} into {@code outFile}
     * via a temp file + rename, so a failure never leaves a partial output.
     *
     * @return bytes written
     */
    public long extractEmbeddedPdf(File src, long start, long endExclusive, File outFile) throws IOException {
        if (start < 0 || endExclusive <= start) {
            throw new IllegalArgumentException("invalid range [" + start + "," + endExclusive + ")");
        }
        if (endExclusive > src.length()) {
            throw new IOException("range exceeds file length " + src.length());
        }
        File parent = outFile.getAbsoluteFile().getParentFile();
        if (parent != null) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        File tmp = new File(outFile.getAbsolutePath() + ".tmp");
        long written = 0;
        try (FileInputStream in = new FileInputStream(src);
             java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
            in.getChannel().position(start);
            byte[] buf = new byte[64 * 1024];
            long remaining = endExclusive - start;
            while (remaining > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n < 0) {
                    throw new IOException("unexpected EOF at " + (start + written));
                }
                out.write(buf, 0, n);
                written += n;
                remaining -= n;
            }
            out.getFD().sync();
        } catch (IOException | RuntimeException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw e;
        }
        if (outFile.exists() && !outFile.delete()) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("could not replace " + outFile);
        }
        if (!tmp.renameTo(outFile)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("rename failed: " + tmp + " -> " + outFile);
        }
        return written;
    }

    private static int indexOf(byte[] haystack, int length, byte[] needle, int from) {
        outer:
        for (int i = Math.max(from, 0); i + needle.length <= length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static int lastIndexOf(byte[] haystack, int length, byte[] needle, int from) {
        for (int i = length - needle.length; i >= Math.max(from, 0); i--) {
            boolean match = true;
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return i;
            }
        }
        return -1;
    }
}
