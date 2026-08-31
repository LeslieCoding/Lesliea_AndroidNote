package com.leslie.colorosnote.feature.importer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class ImportJobTest {

    private static ImportJob.StageHandler okHandler() {
        return (stage, job) -> { /* success */ };
    }

    @Test
    public void successRunsAllStagesInOrder() {
        List<ImportStage> seen = new ArrayList<>();
        ImportJob job = new ImportJob("j1", "/tmp/a.pdf", okHandler(),
                new ImportJob.Listener() {
                    @Override
                    public void onProgress(int percent, ImportStage stage) {
                        seen.add(stage);
                    }

                    @Override
                    public void onFinished(ImportJob.Status status, String errorMessage) {
                    }
                });
        assertEquals(ImportJob.Status.SUCCEEDED, job.run());
        // one progress call per stage plus a final 100% COMMIT callback
        assertEquals(ImportStage.values().length + 1, seen.size());
        assertEquals(ImportStage.SELECTED, seen.get(0));
        assertEquals(ImportStage.COMMIT, seen.get(seen.size() - 1));
        for (ImportStage s : ImportStage.values()) {
            assertTrue(seen.contains(s));
        }
    }

    @Test
    public void failureRecordsStageAndMessage() {
        ImportJob job = new ImportJob("j2", "/tmp/b.pdf", (stage, job2) -> {
            if (stage == ImportStage.CONVERT) throw new RuntimeException("boom");
        }, null);
        assertEquals(ImportJob.Status.FAILED, job.run());
        assertEquals(ImportStage.CONVERT, job.getFailedStage());
        assertEquals("boom", job.getErrorMessage());
    }

    @Test
    public void cancelStopsBeforeNextStage() {
        final ImportJob[] ref = new ImportJob[1];
        ImportJob job = new ImportJob("j3", "/tmp/c.pdf", (stage, j) -> {
            if (stage == ImportStage.VALIDATE) ref[0].requestCancel();
        }, null);
        ref[0] = job;
        assertEquals(ImportJob.Status.CANCELLED, job.run());
        assertTrue(job.getCurrentStage().baseProgress < ImportStage.COMMIT.baseProgress);
    }

    @Test
    public void retryAfterFailure() {
        final boolean[] failOnce = {true};
        ImportJob job = new ImportJob("j4", "/tmp/d.pdf", (stage, j) -> {
            if (stage == ImportStage.DETECT_FORMAT && failOnce[0]) {
                failOnce[0] = false;
                throw new RuntimeException("transient");
            }
        }, null);
        assertEquals(ImportJob.Status.FAILED, job.run());
        assertEquals(ImportJob.Status.SUCCEEDED, job.retry());
    }

    @Test
    public void retryOnlyFromFailedState() {
        ImportJob job = new ImportJob("j5", "/tmp/e.pdf", okHandler(), null);
        assertEquals(ImportJob.Status.PENDING, job.retry());
    }
}
