package com.leslie.colorosnote.document.office;

/**
 * Progress + cancellation channel for long imports. Called on the worker thread
 * performing the import; implementations must marshal to the UI thread themselves.
 */
public interface ProgressCallback {

    /** No-op callback that never cancels. */
    ProgressCallback NONE = new ProgressCallback() {
        @Override
        public void onProgress(int percent, String stage) {
            // no-op
        }
    };

    /**
     * @param percent 0..100, monotonically non-decreasing within one import
     * @param stage   short machine-stable stage name: "reading", "extracting",
     *                "layout", "resources", "done"
     */
    void onProgress(int percent, String stage);

    /** Return true to abort the import at the next checkpoint (throws cancelled). */
    default boolean isCancelled() {
        return false;
    }
}
