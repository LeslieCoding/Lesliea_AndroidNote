package com.leslie.colorosnote.feature.importer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.leslie.colorosnote.document.office.OfficeImportException;

import java.io.IOException;
import java.util.Locale;

/**
 * Typed import failure (spec 36): a machine-readable {@link Type} for the UI and
 * retry logic, a Chinese user-facing message shown verbatim in dialogs, and a
 * technical diagnostics string that goes to {@code AppLogger} only.
 *
 * <p>{@link #getMessage()} returns the user message so {@link ImportJob}'s
 * {@code errorMessage} stays displayable.
 */
public final class ImportError extends Exception {

    public enum Type {
        /** Format genuinely not importable (unknown type, CAJ variant, legacy Office). */
        UNSUPPORTED_FORMAT,
        /** File exceeds the import size cap. */
        FILE_TOO_LARGE,
        /** Magic bytes contradict the file extension (spec 32). */
        CONTENT_MISMATCH,
        /** Conversion/rendering pipeline failed mid-import. */
        CONVERT_FAILED,
        /** File is empty, truncated or structurally broken. */
        CORRUPT,
        /** Filesystem-level failure (permissions, disk full, ...). */
        IO_ERROR,
        /** User cancelled; not an error for UI purposes. */
        CANCELLED
    }

    private final Type type;
    private final String userMessage;
    private final String diagnostics;

    public ImportError(@NonNull Type type, @NonNull String userMessage, @Nullable String diagnostics) {
        super(userMessage);
        this.type = type;
        this.userMessage = userMessage;
        this.diagnostics = diagnostics == null ? "" : diagnostics;
    }

    @NonNull
    public Type getType() {
        return type;
    }

    /** Chinese message safe to show directly in the import UI. */
    @NonNull
    public String getUserMessage() {
        return userMessage;
    }

    /** Technical detail for diagnostic logs; never shown to users. */
    @NonNull
    public String getDiagnostics() {
        return diagnostics;
    }

    // ------------------------------------------------------------------
    // Factories
    // ------------------------------------------------------------------

    public static ImportError tooLarge(long sizeBytes, long maxBytes) {
        return new ImportError(Type.FILE_TOO_LARGE,
                "文件过大（" + (sizeBytes / 1024 / 1024) + " MB），最大支持 "
                        + (maxBytes / 1024 / 1024) + " MB",
                "size=" + sizeBytes + " max=" + maxBytes);
    }

    public static ImportError contentMismatch(@NonNull String detail) {
        return new ImportError(Type.CONTENT_MISMATCH,
                "文件内容与扩展名不符，文件可能被伪装或已损坏", detail);
    }

    public static ImportError unsupportedFormat(@NonNull String userMessage, @Nullable String diagnostics) {
        return new ImportError(Type.UNSUPPORTED_FORMAT, userMessage, diagnostics);
    }

    public static ImportError convertFailed(@NonNull String userMessage, @Nullable String diagnostics) {
        return new ImportError(Type.CONVERT_FAILED, userMessage, diagnostics);
    }

    public static ImportError corrupt(@NonNull String userMessage, @Nullable String diagnostics) {
        return new ImportError(Type.CORRUPT, userMessage, diagnostics);
    }

    public static ImportError io(@NonNull String userMessage, @Nullable String diagnostics) {
        return new ImportError(Type.IO_ERROR, userMessage, diagnostics);
    }

    public static ImportError cancelled() {
        return new ImportError(Type.CANCELLED, "导入已取消", null);
    }

    // ------------------------------------------------------------------
    // Exception mapping
    // ------------------------------------------------------------------

    /**
     * Maps an arbitrary stage failure onto a typed {@link ImportError}
     * (spec 36: 可诊断). Already-typed errors pass through; Office cancellation
     * and hard-unsupported failures keep their semantics; IO failures map to
     * {@link Type#IO_ERROR}; anything else is a conversion/corruption failure
     * depending on the stage.
     */
    @NonNull
    public static ImportError map(@NonNull ImportStage stage, @NonNull Throwable t) {
        if (t instanceof ImportError) {
            return (ImportError) t;
        }
        if (t instanceof OfficeImportException) {
            OfficeImportException oie = (OfficeImportException) t;
            if (oie.isCancelled()) {
                return cancelled();
            }
            if (oie.isUnsupported()) {
                // Message carries the user-facing guidance; strip the marker prefix.
                String msg = String.valueOf(oie.getMessage());
                if (msg.startsWith("UNSUPPORTED: ")) {
                    msg = msg.substring("UNSUPPORTED: ".length());
                }
                return unsupportedFormat(msg, "office engine: " + oie.getMessage());
            }
            return convertFailed("文档转换失败：" + oie.getMessage(),
                    diag(stage, t));
        }
        if (t instanceof IOException) {
            return io("文件读写失败，请检查存储空间后重试", diag(stage, t));
        }
        if (stage == ImportStage.CONVERT) {
            return convertFailed("文档转换失败，文件可能已损坏", diag(stage, t));
        }
        return corrupt("文件损坏或无法解析", diag(stage, t));
    }

    private static String diag(ImportStage stage, Throwable t) {
        return String.format(Locale.US, "stage=%s cause=%s: %s",
                stage, t.getClass().getName(), String.valueOf(t.getMessage()));
    }
}
