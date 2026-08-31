package com.leslie.colorosnote.document.caj;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Default {@link CajImportEngine} with no third-party CAJ SDK.
 *
 * <p>Conversion paths, in order:
 * <ol>
 *   <li>File is already a PDF (magic says so, extension be damned) → atomic copy
 *       into outDir.</li>
 *   <li>CAJ/unknown container with a complete embedded PDF → extract that byte
 *       range into a standalone .pdf.</li>
 *   <li>Anything else → {@link ImportOutcome.Status#UNSUPPORTED_CAJ_VARIANT} with
 *       the user-facing hint to convert externally first. Never faked success.</li>
 * </ol>
 *
 * <p>Pure java.io on purpose: fully unit-testable on the JVM.
 */
public final class DefaultCajImportEngine implements CajImportEngine {

    /** Standard user hint for unparseable CAJ variants (requirement §13, step 5). */
    public static final String UNSUPPORTED_HINT = "请先用外部工具转换为 PDF 再导入";

    private final CajFormatDetector detector;

    public DefaultCajImportEngine() {
        this(new CajFormatDetector());
    }

    public DefaultCajImportEngine(CajFormatDetector detector) {
        this.detector = detector;
    }

    @Override
    public CajProbeResult probe(File src) throws IOException {
        return detector.probe(src);
    }

    @Override
    public ImportOutcome convert(File src, File outDir, ProgressCallback progress) {
        if (src == null || !src.isFile()) {
            return ImportOutcome.ioError("文件不存在或不可读");
        }
        if (src.length() == 0L) {
            return ImportOutcome.corrupt("文件为空");
        }
        report(progress, 5, "探测文件格式");

        final CajProbeResult probe;
        try {
            probe = detector.probe(src);
        } catch (IOException e) {
            return ImportOutcome.ioError("读取文件失败：" + e.getMessage());
        }

        //noinspection ResultOfMethodCallIgnored
        outDir.mkdirs();
        if (!outDir.isDirectory()) {
            return ImportOutcome.ioError("输出目录不可用");
        }

        if (probe.getFormat() == CajFormat.PDF) {
            report(progress, 30, "复制 PDF");
            File out = outputFile(outDir, src.getName());
            try {
                copyAtomic(src, out);
            } catch (IOException e) {
                return ImportOutcome.ioError("写入 PDF 失败：" + e.getMessage());
            }
            report(progress, 100, "完成");
            return ImportOutcome.success(out, probe.getMessage());
        }

        if (probe.hasEmbeddedPdf()) {
            report(progress, 30, "提取内嵌 PDF");
            File out = outputFile(outDir, src.getName());
            try {
                detector.extractEmbeddedPdf(
                        src, probe.getEmbeddedPdfStart(), probe.getEmbeddedPdfEnd(), out);
            } catch (IOException | RuntimeException e) {
                return ImportOutcome.ioError("提取内嵌 PDF 失败：" + e.getMessage());
            }
            report(progress, 100, "完成");
            return ImportOutcome.success(out, probe.getMessage());
        }

        report(progress, 100, "无法转换");
        if (probe.getFormat() == CajFormat.UNKNOWN) {
            return ImportOutcome.corrupt("无法识别的文件格式；" + UNSUPPORTED_HINT);
        }
        return ImportOutcome.unsupportedVariant(
                "检测到 " + probe.getFormat().name() + " 格式，当前无法解析；" + UNSUPPORTED_HINT);
    }

    /** Derives {@code base.pdf} from the source name (extension stripped). */
    static File outputFile(File outDir, String srcName) {
        String base = srcName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        base = base.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (base.isEmpty()) {
            base = "imported";
        }
        return new File(outDir, base + ".pdf");
    }

    private static void copyAtomic(File src, File out) throws IOException {
        File tmp = new File(out.getAbsolutePath() + ".tmp");
        try (InputStream in = new BufferedInputStream(new FileInputStream(src));
             FileOutputStream fos = new FileOutputStream(tmp);
             OutputStream os = new BufferedOutputStream(fos)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) {
                os.write(buf, 0, n);
            }
            os.flush();
            fos.getFD().sync();
        } catch (IOException | RuntimeException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw e;
        }
        if (out.exists() && !out.delete()) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("could not replace " + out);
        }
        if (!tmp.renameTo(out)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("rename failed: " + tmp + " -> " + out);
        }
    }

    private static void report(ProgressCallback cb, int percent, String stage) {
        if (cb != null) {
            cb.onProgress(percent, stage);
        }
    }
}
