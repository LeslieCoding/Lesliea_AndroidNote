package com.leslie.colorosnote.feature.importer;

/**
 * State machine for one import job (spec 36): progress 0-100%, current stage,
 * cancellable, retryable after failure, temp artifacts cleaned on failure.
 * Pure JVM; stage execution is delegated to a {@link StageHandler}.
 */
public final class ImportJob {

    public enum Status { PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED }

    /** Executes one stage; throws on failure, returns normally on success. */
    public interface StageHandler {
        void runStage(ImportStage stage, ImportJob job) throws Exception;
    }

    public interface Listener {
        void onProgress(int percent, ImportStage stage);

        void onFinished(Status status, String errorMessage);
    }

    private final String id;
    private final String sourcePath;
    private final StageHandler handler;
    private final Listener listener;

    private volatile Status status = Status.PENDING;
    private volatile boolean cancelRequested;
    private volatile ImportStage currentStage = ImportStage.SELECTED;
    private volatile String errorMessage;
    /** Stage to resume from when retrying (stages before it are re-run too, they are idempotent). */
    private ImportStage failedStage;

    public ImportJob(String id, String sourcePath, StageHandler handler, Listener listener) {
        this.id = id;
        this.sourcePath = sourcePath;
        this.handler = handler;
        this.listener = listener;
    }

    public String getId() {
        return id;
    }

    public String getSourcePath() {
        return sourcePath;
    }

    public Status getStatus() {
        return status;
    }

    public ImportStage getCurrentStage() {
        return currentStage;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public boolean isCancelRequested() {
        return cancelRequested;
    }

    public void requestCancel() {
        cancelRequested = true;
    }

    /** Run the whole pipeline on the calling (worker) thread. */
    public synchronized Status run() {
        if (status == Status.RUNNING) return status;
        status = Status.RUNNING;
        errorMessage = null;
        for (ImportStage stage : ImportStage.values()) {
            if (cancelRequested) {
                status = Status.CANCELLED;
                notifyFinished();
                return status;
            }
            currentStage = stage;
            if (listener != null) listener.onProgress(stage.baseProgress, stage);
            try {
                handler.runStage(stage, this);
            } catch (Exception e) {
                failedStage = stage;
                errorMessage = e.getMessage();
                status = Status.FAILED;
                notifyFinished();
                return status;
            }
        }
        if (listener != null) listener.onProgress(100, ImportStage.COMMIT);
        status = Status.SUCCEEDED;
        notifyFinished();
        return status;
    }

    /** Retry after failure; stage handlers must be idempotent. */
    public synchronized Status retry() {
        if (status != Status.FAILED) return status;
        cancelRequested = false;
        return run();
    }

    /** Stage the job failed at, for diagnostics (spec 36: 保留可诊断日志). */
    public ImportStage getFailedStage() {
        return failedStage;
    }

    private void notifyFinished() {
        if (listener != null) listener.onFinished(status, errorMessage);
    }
}
