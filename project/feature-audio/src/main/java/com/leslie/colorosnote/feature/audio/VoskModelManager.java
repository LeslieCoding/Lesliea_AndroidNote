package com.leslie.colorosnote.feature.audio;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Vosk offline Chinese speech model manager (spec 21.1, vosk-model-small-cn-0.22,
 * ~42MB). The model is NOT bundled in the APK; it is downloaded on first use,
 * unzipped under {@code filesDir/models/} and verified structurally.
 *
 * <p>Layout after install (also the layout an {@code adb push} fallback must
 * reproduce):
 * <pre>
 * filesDir/models/vosk-model-small-cn-0.22/
 *     am/final.mdl
 *     conf/mfcc.conf, conf/model.conf
 *     graph/HCLG.fst, graph/words.txt, ...   (big models; small models ship
     *                                            HCLr.fst + Gr.fst instead)
 *     ivector/...
 * </pre>
 * Readiness is structural (not a marker file) so a manually pushed model
 * directory is picked up without any extra step: {@link #isModelReady()}
 * requires {@code am/final.mdl} plus either the fused graph
 * ({@code graph/HCLG.fst} + {@code graph/words.txt}) or the split graph
 * ({@code graph/HCLr.fst} + {@code graph/Gr.fst}), all non-empty.
 *
 * <p>Pure java.io + java.net; the download transport is injectable so the
 * state machine is JVM-testable.
 */
public final class VoskModelManager {

    public static final String MODEL_ID = "vosk-model-small-cn-0.22";
    public static final String MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip";
    /** Exact Content-Length of the official zip (verified 2026-08). */
    public static final long MODEL_ZIP_BYTES = 43_898_754L;

    public enum State {NOT_INSTALLED, DOWNLOADING, EXTRACTING, READY, ERROR}

    /** Progress is reported on the calling (worker) thread. */
    public interface ProgressCallback {
        /** @param percent 0..100 across both stages; stage is "downloading"/"extracting" */
        void onProgress(int percent, String stage);
    }

    /** Transport seam for tests: streams the zip to {@code dest}. */
    public interface ZipDownloader {
        void downloadTo(String url, long expectedBytes, File dest,
                        ProgressCallback cb) throws IOException;
    }

    private final File modelsRoot;
    private final ZipDownloader downloader;
    private volatile State state = State.NOT_INSTALLED;
    private volatile String lastError;

    public VoskModelManager(File filesDir) {
        this(filesDir, new HttpZipDownloader());
    }

    public VoskModelManager(File filesDir, ZipDownloader downloader) {
        this.modelsRoot = new File(filesDir, "models");
        this.downloader = downloader;
    }

    /** Directory the recognizer is loaded from. */
    public File modelDir() {
        return new File(modelsRoot, MODEL_ID);
    }

    public State state() {
        if (state == State.DOWNLOADING || state == State.EXTRACTING) {
            return state;
        }
        return isModelReady() ? State.READY : state == State.ERROR ? State.ERROR : State.NOT_INSTALLED;
    }

    /** Structural readiness check; true for adb-pushed model dirs too. */
    public boolean isModelReady() {
        return isModelStructureComplete(modelDir());
    }

    /** Nullable Chinese message of the last failed install attempt. */
    public String lastError() {
        return lastError;
    }

    /**
     * Model files present and non-empty = usable by org.vosk.Model.
     * Big models ship a fused {@code graph/HCLG.fst} + {@code graph/words.txt};
     * small models (e.g. vosk-model-small-cn-0.22) split the graph into
     * {@code HCLr.fst} + {@code Gr.fst} and omit words.txt — accept both.
     */
    public static boolean isModelStructureComplete(File dir) {
        if (!isNonEmptyFile(new File(dir, "am/final.mdl"))) {
            return false;
        }
        boolean fusedGraph = isNonEmptyFile(new File(dir, "graph/HCLG.fst"))
                && isNonEmptyFile(new File(dir, "graph/words.txt"));
        boolean splitGraph = isNonEmptyFile(new File(dir, "graph/HCLr.fst"))
                && isNonEmptyFile(new File(dir, "graph/Gr.fst"));
        return fusedGraph || splitGraph;
    }

    private static boolean isNonEmptyFile(File f) {
        return f.isFile() && f.length() > 0;
    }

    /**
     * Downloads (when missing) and extracts the model. Blocking; call from a
     * worker thread. No-op when the model is already ready.
     *
     * @throws IOException with a Chinese user-facing message on failure
     */
    public void ensureModel(ProgressCallback cb) throws IOException {
        if (isModelReady()) {
            state = State.READY;
            if (cb != null) cb.onProgress(100, "extracting");
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        modelsRoot.mkdirs();
        File zipTmp = new File(modelsRoot, MODEL_ID + ".zip.tmp");
        state = State.DOWNLOADING;
        lastError = null;
        try {
            downloader.downloadTo(MODEL_URL, MODEL_ZIP_BYTES, zipTmp, cb);
            state = State.EXTRACTING;
            if (cb != null) cb.onProgress(95, "extracting");
            unzip(zipTmp, modelsRoot);
            if (!isModelReady()) {
                deleteQuietly(modelDir());
                throw new IOException("语音模型解压后校验失败，请重试");
            }
            state = State.READY;
            if (cb != null) cb.onProgress(100, "extracting");
        } catch (IOException | RuntimeException e) {
            state = State.ERROR;
            lastError = e.getMessage() == null ? "语音模型下载失败" : e.getMessage();
            throw e instanceof IOException ? (IOException) e : new IOException(lastError, e);
        } finally {
            deleteQuietly(zipTmp);
        }
    }

    /** Removes the installed model (settings "free space" entry point). */
    public boolean deleteModel() {
        state = State.NOT_INSTALLED;
        return deleteRecursively(modelDir());
    }

    /** Extracts a model zip into {@code destRoot}, guarding against zip-slip. */
    static void unzip(File zipFile, File destRoot) throws IOException {
        String rootPath = destRoot.getCanonicalPath() + File.separator;
        try (ZipInputStream in = new ZipInputStream(new FileInputStream(zipFile))) {
            byte[] buf = new byte[64 * 1024];
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                File out = new File(destRoot, entry.getName());
                if (!out.getCanonicalPath().startsWith(rootPath)) {
                    throw new IOException("语音模型压缩包内容异常");
                }
                if (entry.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    out.mkdirs();
                    continue;
                }
                //noinspection ResultOfMethodCallIgnored
                out.getParentFile().mkdirs();
                try (OutputStream fileOut = new FileOutputStream(out)) {
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        fileOut.write(buf, 0, n);
                    }
                }
            }
        }
    }

    private static boolean deleteRecursively(File f) {
        if (!f.exists()) {
            return true;
        }
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        return f.delete();
    }

    private static void deleteQuietly(File f) {
        try {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        } catch (RuntimeException ignored) {
            // best effort
        }
    }

    /** Default transport: plain HttpURLConnection with progress callbacks. */
    private static final class HttpZipDownloader implements ZipDownloader {
        @Override
        public void downloadTo(String url, long expectedBytes, File dest,
                               ProgressCallback cb) throws IOException {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(30_000);
            try {
                if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    throw new IOException("语音模型下载失败（网络错误 " + conn.getResponseCode() + "）");
                }
                long total = conn.getContentLength() > 0 ? conn.getContentLength() : expectedBytes;
                try (InputStream in = conn.getInputStream();
                     OutputStream out = new FileOutputStream(dest)) {
                    byte[] buf = new byte[64 * 1024];
                    long downloaded = 0;
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        out.write(buf, 0, n);
                        downloaded += n;
                        if (cb != null && total > 0) {
                            // Download occupies 0..95% of the overall progress.
                            cb.onProgress((int) Math.min(95, downloaded * 95 / total),
                                    "downloading");
                        }
                    }
                    out.flush();
                }
            } finally {
                conn.disconnect();
            }
        }
    }
}
