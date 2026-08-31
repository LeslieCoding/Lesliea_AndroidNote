package com.leslie.colorosnote.feature.importer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.leslie.colorosnote.core.common.AppExecutors;
import com.leslie.colorosnote.core.common.IdGenerator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Serial queue for import jobs (spec 36: 大文件可后台继续，进度聚合回调).
 *
 * <p>Jobs run strictly one at a time; each job is dispatched onto
 * {@code AppExecutors.io()} (or an injected executor in tests) and the next one
 * is submitted only after the previous finishes, so large imports keep running
 * in the background without competing with each other for IO.
 *
 * <p>All {@link Listener} callbacks arrive on the worker thread running the
 * job — UI code must marshal to the main thread itself. The manager holds no
 * Android references; the UI layer binds via {@link #setListener}.
 */
public final class ImportJobManager {

    /** Aggregated progress/lifecycle callback for the whole queue. */
    public interface Listener {
        void onJobProgress(@NonNull String jobId, int percent, @NonNull ImportStage stage);

        void onJobFinished(@NonNull String jobId, @NonNull ImportJob.Status status,
                           @Nullable String errorMessage);

        /** All queued work has completed (queue is empty and nothing is running). */
        void onQueueDrained();
    }

    private final ImportJob.StageHandler handler;
    private final ExecutorService executor;

    private final ArrayDeque<ImportJob> pending = new ArrayDeque<>();
    private final Map<String, ImportJob> jobs = new LinkedHashMap<>();
    private boolean running;

    @Nullable
    private volatile Listener listener;

    public ImportJobManager(@NonNull ImportJob.StageHandler handler) {
        this(handler, AppExecutors.get().io());
    }

    /** Constructor with an explicit executor (tests pass a direct executor). */
    public ImportJobManager(@NonNull ImportJob.StageHandler handler,
                            @NonNull ExecutorService executor) {
        this.handler = handler;
        this.executor = executor;
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    /** Queues a new import for the given source reference; returns the job. */
    @NonNull
    public synchronized ImportJob enqueue(@NonNull String sourceRef) {
        // The listener needs the job id, but the job only exists after the
        // constructor returns — pass it through a one-slot holder. Callbacks
        // cannot fire before pumpLocked() runs, so the holder is set in time.
        final ImportJob[] ref = new ImportJob[1];
        ImportJob job = new ImportJob(IdGenerator.newId(), sourceRef, handler,
                new ImportJob.Listener() {
                    @Override
                    public void onProgress(int percent, ImportStage stage) {
                        Listener l = listener;
                        if (l != null) {
                            l.onJobProgress(ref[0].getId(), percent, stage);
                        }
                    }

                    @Override
                    public void onFinished(ImportJob.Status status, String errorMessage) {
                        Listener l = listener;
                        if (l != null) {
                            l.onJobFinished(ref[0].getId(), status, errorMessage);
                        }
                    }
                });
        ref[0] = job;
        jobs.put(job.getId(), job);
        pending.add(job);
        pumpLocked();
        return job;
    }

    /** Requests cancellation of a queued or running job; returns false if unknown/terminal. */
    public synchronized boolean cancel(@NonNull String jobId) {
        ImportJob job = jobs.get(jobId);
        if (job == null) {
            return false;
        }
        ImportJob.Status status = job.getStatus();
        if (status == ImportJob.Status.SUCCEEDED || status == ImportJob.Status.FAILED
                || status == ImportJob.Status.CANCELLED) {
            return false;
        }
        job.requestCancel();
        return true;
    }

    /** Re-queues a failed job; stage handlers are idempotent (spec 36: 支持 retry). */
    public synchronized boolean retry(@NonNull String jobId) {
        ImportJob job = jobs.get(jobId);
        if (job == null || job.getStatus() != ImportJob.Status.FAILED) {
            return false;
        }
        pending.add(job);
        pumpLocked();
        return true;
    }

    /** Snapshot of every job known to this manager, oldest first. */
    @NonNull
    public synchronized List<ImportJob> jobs() {
        return new ArrayList<>(jobs.values());
    }

    @Nullable
    public synchronized ImportJob job(@NonNull String jobId) {
        return jobs.get(jobId);
    }

    /** Number of jobs still waiting to start. */
    public synchronized int pendingCount() {
        return pending.size();
    }

    private void pumpLocked() {
        if (running || pending.isEmpty()) {
            return;
        }
        running = true;
        ImportJob job = pending.poll();
        executor.execute(() -> runJob(job));
    }

    private void runJob(ImportJob job) {
        try {
            if (job.getStatus() == ImportJob.Status.FAILED) {
                job.retry();
            } else {
                job.run();
            }
        } finally {
            ImportJob.Status status = job.getStatus();
            if (status != ImportJob.Status.SUCCEEDED && handler instanceof ImportCoordinator) {
                // Belt and braces: make sure no temp artifacts survive a cancel.
                ((ImportCoordinator) handler).discard(job.getId());
            }
            Listener drainedListener = null;
            synchronized (this) {
                running = false;
                pumpLocked();
                if (!running && pending.isEmpty()) {
                    drainedListener = listener;
                }
            }
            if (drainedListener != null) {
                drainedListener.onQueueDrained();
            }
        }
    }
}
