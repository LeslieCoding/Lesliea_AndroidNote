package com.leslie.colorosnote.document.caj;

/** Progress report during CAJ conversion; invoked on the calling worker thread. */
public interface ProgressCallback {

    /**
     * @param percent 0..100
     * @param stage   short user-readable stage label (may be shown in UI)
     */
    void onProgress(int percent, String stage);
}
