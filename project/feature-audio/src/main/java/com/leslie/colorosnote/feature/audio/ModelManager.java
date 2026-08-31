package com.leslie.colorosnote.feature.audio;

import com.leslie.colorosnote.core.common.AppLogger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Manages on-demand ASR model files (spec 21.1): models are NOT bundled in the
 * APK; they are downloaded on first use, size is shown up front, and users can
 * delete them. Pure java.io so it is JVM-testable.
 */
public final class ModelManager {

    private static final String TAG = "ModelManager";

    public static final class ModelInfo {
        public final String id;
        public final String downloadUrl;
        public final long sizeBytes;
        public final String sha256; // nullable
        public final File localFile;

        public ModelInfo(String id, String downloadUrl, long sizeBytes, String sha256, File localFile) {
            this.id = id;
            this.downloadUrl = downloadUrl;
            this.sizeBytes = sizeBytes;
            this.sha256 = sha256;
            this.localFile = localFile;
        }
    }

    private final File modelDir;

    public ModelManager(File modelDir) {
        this.modelDir = modelDir;
    }

    public boolean isInstalled(ModelInfo info) {
        return info.localFile.exists() && info.localFile.length() == info.sizeBytes;
    }

    public boolean delete(ModelInfo info) {
        return !info.localFile.exists() || info.localFile.delete();
    }

    public interface DownloadCallback {
        void onProgress(long downloadedBytes, long totalBytes);
    }

    /**
     * Download with resume-free simple fetch + sha256 check (when provided).
     * Runs on the calling thread; callers must use a worker thread.
     */
    public boolean download(ModelInfo info, DownloadCallback cb) throws Exception {
        //noinspection ResultOfMethodCallIgnored
        modelDir.mkdirs();
        File tmp = new File(modelDir, info.id + ".tmp");
        HttpURLConnection conn = (HttpURLConnection) new URL(info.downloadUrl).openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);
        try (InputStream in = conn.getInputStream();
             OutputStream out = new FileOutputStream(tmp)) {
            MessageDigest digest = info.sha256 != null ? MessageDigest.getInstance("SHA-256") : null;
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                if (digest != null) digest.update(buf, 0, n);
                total += n;
                if (cb != null) cb.onProgress(total, info.sizeBytes);
            }
            out.flush();
            if (digest != null) {
                StringBuilder hex = new StringBuilder();
                for (byte b : digest.digest()) hex.append(String.format("%02x", b));
                if (!hex.toString().equalsIgnoreCase(info.sha256)) {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                    AppLogger.e(TAG, "model checksum mismatch");
                    return false;
                }
            }
        } finally {
            conn.disconnect();
        }
        if (!tmp.renameTo(info.localFile)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
        return true;
    }
}
