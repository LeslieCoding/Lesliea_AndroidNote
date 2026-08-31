package com.leslie.colorosnote.core.common;

import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Centralized leveled logger with local diagnostic file output and rotation.
 *
 * <p>SENSITIVE DATA CONTRACT: callers must never log API keys, tokens, passwords,
 * note text content, stroke coordinates in bulk, or any user document payload.
 * Only log tags, coarse event names, sizes, counts, durations and error types.
 * Anything user-authored must go through {@link #redact(String)} before logging.
 */
public final class AppLogger {

    public enum Level {DEBUG, INFO, WARN, ERROR}

    /** Redaction placeholder returned by {@link #redact(String)}. */
    public static final String REDACTED = "***REDACTED***";

    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024; // 2 MB
    private static final int MAX_FILES = 5;                       // app.log + app.log.1..4
    private static final String LOG_FILE_NAME = "app.log";

    private static volatile Level minLevel = Level.DEBUG;
    private static File logFile;
    private static BufferedWriter writer;
    private static final Object FILE_LOCK = new Object();
    private static final ThreadLocal<SimpleDateFormat> TS_FORMAT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US));

    private AppLogger() {
    }

    /** Enables diagnostic file logging into the given directory (created if missing). */
    public static synchronized void init(File logDir) {
        synchronized (FILE_LOCK) {
            closeWriterLocked();
            if (logDir == null) {
                logFile = null;
                return;
            }
            //noinspection ResultOfMethodCallIgnored
            logDir.mkdirs();
            logFile = new File(logDir, LOG_FILE_NAME);
        }
    }

    public static void setMinLevel(Level level) {
        minLevel = level;
    }

    /**
     * Masks potentially sensitive text. Per the logging contract, user-authored
     * content must be redacted before being passed to any log method.
     */
    public static String redact(String ignored) {
        return REDACTED;
    }

    public static void d(String tag, String msg) {
        log(Level.DEBUG, tag, msg, null);
    }

    public static void i(String tag, String msg) {
        log(Level.INFO, tag, msg, null);
    }

    public static void w(String tag, String msg) {
        log(Level.WARN, tag, msg, null);
    }

    public static void w(String tag, String msg, Throwable tr) {
        log(Level.WARN, tag, msg, tr);
    }

    public static void e(String tag, String msg) {
        log(Level.ERROR, tag, msg, null);
    }

    public static void e(String tag, String msg, Throwable tr) {
        log(Level.ERROR, tag, msg, tr);
    }

    public static void log(Level level, String tag, String msg, Throwable tr) {
        if (level.ordinal() < minLevel.ordinal()) {
            return;
        }
        String safeTag = tag == null ? "App" : tag;
        String safeMsg = msg == null ? "" : msg;
        switch (level) {
            case DEBUG:
                Log.d(safeTag, safeMsg, tr);
                break;
            case INFO:
                Log.i(safeTag, safeMsg, tr);
                break;
            case WARN:
                Log.w(safeTag, safeMsg, tr);
                break;
            case ERROR:
                Log.e(safeTag, safeMsg, tr);
                break;
            default:
                break;
        }
        writeToFile(level, safeTag, safeMsg, tr);
    }

    /** Flushes and closes the diagnostic file writer. Safe to call on shutdown. */
    public static void shutdown() {
        synchronized (FILE_LOCK) {
            closeWriterLocked();
        }
    }

    private static void writeToFile(Level level, String tag, String msg, Throwable tr) {
        synchronized (FILE_LOCK) {
            if (logFile == null) {
                return;
            }
            try {
                if (writer == null) {
                    rotateIfNeededLocked(0);
                    writer = new BufferedWriter(new OutputStreamWriter(
                            new FileOutputStream(logFile, true), StandardCharsets.UTF_8));
                }
                String line = TS_FORMAT.get().format(new Date())
                        + " " + level.name().charAt(0) + "/" + tag + ": " + msg;
                writer.write(line);
                writer.newLine();
                if (tr != null) {
                    writer.write("    " + tr.getClass().getSimpleName() + ": " + tr.getMessage());
                    writer.newLine();
                }
                writer.flush();
                rotateIfNeededLocked(logFile.length());
            } catch (IOException ignored) {
                // Diagnostic logging must never crash the app.
            }
        }
    }

    private static void rotateIfNeededLocked(long pendingSize) throws IOException {
        if (logFile == null) {
            return;
        }
        if (logFile.length() + pendingSize < MAX_FILE_BYTES) {
            return;
        }
        closeWriterLocked();
        File oldest = new File(logFile.getParentFile(), LOG_FILE_NAME + "." + (MAX_FILES - 1));
        if (oldest.exists()) {
            //noinspection ResultOfMethodCallIgnored
            oldest.delete();
        }
        for (int i = MAX_FILES - 2; i >= 1; i--) {
            File from = new File(logFile.getParentFile(), LOG_FILE_NAME + "." + i);
            if (from.exists()) {
                //noinspection ResultOfMethodCallIgnored
                from.renameTo(new File(logFile.getParentFile(), LOG_FILE_NAME + "." + (i + 1)));
            }
        }
        if (logFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            logFile.renameTo(new File(logFile.getParentFile(), LOG_FILE_NAME + ".1"));
        }
    }

    private static void closeWriterLocked() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {
                // ignore
            }
            writer = null;
        }
    }
}
