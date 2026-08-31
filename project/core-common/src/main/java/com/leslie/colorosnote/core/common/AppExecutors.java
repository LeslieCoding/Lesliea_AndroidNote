package com.leslie.colorosnote.core.common;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * App-wide thread scheduling. Singleton with named daemon threads.
 *
 * <ul>
 *   <li>{@link #io()} cached pool for file/network IO.</li>
 *   <li>{@link #compute()} fixed pool sized to CPU cores for CPU-bound work.</li>
 *   <li>{@link #background()} single low-priority thread for indexing/OCR and other
 *       work that must never compete with the real-time ink pipeline.</li>
 *   <li>{@link #mainThread()} handler bound to the main looper.</li>
 * </ul>
 */
public final class AppExecutors {

    private static volatile AppExecutors instance;

    private final ExecutorService ioExecutor;
    private final ExecutorService computeExecutor;
    private final ExecutorService backgroundExecutor;
    private volatile Handler mainHandler;

    private AppExecutors() {
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        ioExecutor = Executors.newCachedThreadPool(new NamedDaemonThreadFactory("io", Thread.NORM_PRIORITY));
        computeExecutor = Executors.newFixedThreadPool(cores, new NamedDaemonThreadFactory("compute", Thread.NORM_PRIORITY));
        backgroundExecutor = Executors.newSingleThreadExecutor(new NamedDaemonThreadFactory("background", Thread.MIN_PRIORITY));
    }

    public static AppExecutors get() {
        if (instance == null) {
            synchronized (AppExecutors.class) {
                if (instance == null) {
                    instance = new AppExecutors();
                }
            }
        }
        return instance;
    }

    /** Cached pool for IO-bound tasks (file reads/writes, zip, network). */
    public ExecutorService io() {
        return ioExecutor;
    }

    /** Fixed pool (size = CPU cores) for CPU-bound tasks. */
    public ExecutorService compute() {
        return computeExecutor;
    }

    /**
     * Single-thread, low-priority executor for indexing, OCR, thumbnails and
     * similar tasks that must not steal CPU from the ink rendering pipeline.
     */
    public ExecutorService background() {
        return backgroundExecutor;
    }

    /** Handler bound to the main looper for posting work back to the UI thread. */
    public Handler mainThread() {
        if (mainHandler == null) {
            synchronized (this) {
                if (mainHandler == null) {
                    mainHandler = new Handler(Looper.getMainLooper());
                }
            }
        }
        return mainHandler;
    }

    /** Shuts down all pools. Intended for tests or process teardown. */
    public synchronized void shutdown() {
        ioExecutor.shutdown();
        computeExecutor.shutdown();
        backgroundExecutor.shutdown();
    }

    private static final class NamedDaemonThreadFactory implements ThreadFactory {
        private final String prefix;
        private final int priority;
        private final AtomicInteger counter = new AtomicInteger(1);

        NamedDaemonThreadFactory(String prefix, int priority) {
            this.prefix = prefix;
            this.priority = priority;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "leslie-" + prefix + "-" + counter.getAndIncrement());
            t.setDaemon(true);
            t.setPriority(priority);
            return t;
        }
    }
}
