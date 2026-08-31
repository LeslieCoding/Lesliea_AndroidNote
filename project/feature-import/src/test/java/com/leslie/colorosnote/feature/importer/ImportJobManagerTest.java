package com.leslie.colorosnote.feature.importer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

/** Queue semantics of {@link ImportJobManager}: serial order, retry, cancel, callbacks. */
public class ImportJobManagerTest {

    /** ExecutorService that runs everything inline, for deterministic tests. */
    private static final class DirectExecutor extends AbstractExecutorService {
        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }

    private static final class RecordingListener implements ImportJobManager.Listener {
        final List<String> events = new ArrayList<>();

        @Override
        public void onJobProgress(String jobId, int percent, ImportStage stage) {
        }

        @Override
        public void onJobFinished(String jobId, ImportJob.Status status, String errorMessage) {
            events.add("finished:" + status);
        }

        @Override
        public void onQueueDrained() {
            events.add("drained");
        }
    }

    @Test
    public void jobsRunSeriallyInEnqueueOrder() {
        List<String> order = new ArrayList<>();
        ImportJob.StageHandler handler = (stage, job) -> {
            if (stage == ImportStage.SELECTED) {
                order.add("start:" + job.getSourcePath());
            }
            if (stage == ImportStage.COMMIT) {
                order.add("end:" + job.getSourcePath());
            }
        };
        ImportJobManager manager = new ImportJobManager(handler, new DirectExecutor());
        RecordingListener listener = new RecordingListener();
        manager.setListener(listener);

        ImportJob a = manager.enqueue("content://x/a.pdf");
        ImportJob b = manager.enqueue("content://x/b.pdf");

        assertEquals(ImportJob.Status.SUCCEEDED, a.getStatus());
        assertEquals(ImportJob.Status.SUCCEEDED, b.getStatus());
        // Strictly serial: B does not start before A has finished.
        assertEquals("start:content://x/a.pdf", order.get(0));
        assertEquals("end:content://x/a.pdf", order.get(1));
        assertEquals("start:content://x/b.pdf", order.get(2));
        assertEquals("end:content://x/b.pdf", order.get(3));
        assertEquals(0, manager.pendingCount());
        assertEquals("drained", listener.events.get(listener.events.size() - 1));
    }

    @Test
    public void retryRequeuesFailedJob() {
        boolean[] failOnce = {true};
        ImportJob.StageHandler handler = (stage, job) -> {
            if (stage == ImportStage.CONVERT && failOnce[0]) {
                failOnce[0] = false;
                throw new RuntimeException("transient");
            }
        };
        ImportJobManager manager = new ImportJobManager(handler, new DirectExecutor());
        ImportJob job = manager.enqueue("content://x/c.pdf");
        assertEquals(ImportJob.Status.FAILED, job.getStatus());

        assertTrue(manager.retry(job.getId()));
        assertEquals(ImportJob.Status.SUCCEEDED, job.getStatus());
        // Retrying a healthy job is rejected.
        assertFalse(manager.retry(job.getId()));
        assertFalse(manager.retry("no-such-job"));
    }

    @Test
    public void cancelPropagatesToRunningJob() {
        ImportJobManager[] ref = new ImportJobManager[1];
        ImportJob.StageHandler handler = (stage, job) -> {
            if (stage == ImportStage.VALIDATE) {
                ref[0].cancel(job.getId());
            }
        };
        ImportJobManager manager = new ImportJobManager(handler, new DirectExecutor());
        ref[0] = manager;
        ImportJob job = manager.enqueue("content://x/d.pdf");
        assertEquals(ImportJob.Status.CANCELLED, job.getStatus());
        // Cancelling a terminal job fails cleanly.
        assertFalse(manager.cancel(job.getId()));
        assertFalse(manager.cancel("no-such-job"));
    }
}
