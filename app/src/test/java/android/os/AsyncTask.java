// SPDX-License-Identifier: GPL-3.0-or-later
package android.os;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Headless JVM test double for android.os.AsyncTask providing background thread execution,
 * thread interruption on cancel(true), and onCancelled / onPostExecute callback dispatch.
 *
 * CAVEATS & LIMITATIONS — NOT FAITHFUL ANDROID:
 * - Threading: In Android, onPostExecute(), onCancelled(), and onPreExecute() run on the
 *   Android main/UI thread (via Looper.getMainLooper()). In this headless JVM test double,
 *   onPostExecute() and onCancelled() execute directly on background worker threads without
 *   an Android Looper/Handler main queue.
 * - Default Executor: Real Android AsyncTask.execute() defaults to SERIAL_EXECUTOR (queueing
 *   one task at a time), whereas this JVM stub routes execute() directly to THREAD_POOL_EXECUTOR.
 * - Queued Cancellation: Real Android handles cancellation while tasks are queued in the
 *   serial executor and delivers cancellation callbacks; this JVM double uses
 *   Future.cancel(mayInterruptIfRunning) on actively scheduled threads and does not model
 *   queued serial cancellation prior to task startup.
 * - Background Throwables: Real Android wraps uncaught background exceptions into a
 *   RuntimeException on the worker thread. This test double preserves and logs background
 *   throwables (accessible via getBackgroundThrowable()) to prevent silent error swallowing.
 */
public abstract class AsyncTask<Params, Progress, Result> {

    public static final Executor THREAD_POOL_EXECUTOR = Executors.newCachedThreadPool();

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile Future<?> future;
    private volatile Throwable backgroundThrowable;

    public AsyncTask() {}

    public final boolean isCancelled() {
        return cancelled.get();
    }

    public final Throwable getBackgroundThrowable() {
        return backgroundThrowable;
    }

    public final boolean cancel(boolean mayInterruptIfRunning) {
        boolean wasCancelled = cancelled.getAndSet(true);
        if (future != null) {
            future.cancel(mayInterruptIfRunning);
        }
        return !wasCancelled;
    }

    protected void onPreExecute() {}

    protected abstract Result doInBackground(Params... params);

    protected void onProgressUpdate(Progress... values) {}

    protected void onPostExecute(Result result) {}

    protected void onCancelled(Result result) {
        onCancelled();
    }

    protected void onCancelled() {}

    @SafeVarargs
    public final AsyncTask<Params, Progress, Result> execute(Params... params) {
        return executeOnExecutor(THREAD_POOL_EXECUTOR, params);
    }

    @SafeVarargs
    public final AsyncTask<Params, Progress, Result> executeOnExecutor(Executor exec, Params... params) {
        onPreExecute();
        java.util.concurrent.ExecutorService service;
        if (exec instanceof java.util.concurrent.ExecutorService) {
            service = (java.util.concurrent.ExecutorService) exec;
        } else {
            service = Executors.newSingleThreadExecutor();
        }
        future = service.submit(() -> {
            Result res = null;
            try {
                if (!isCancelled()) {
                    res = doInBackground(params);
                }
            } catch (Throwable t) {
                backgroundThrowable = t;
                System.err.println("ASYNC_TASK_UNCAUGHT_THROWABLE: " + t);
                t.printStackTrace();
            }
            if (isCancelled()) {
                onCancelled(res);
            } else {
                onPostExecute(res);
            }
        });
        return this;
    }
}
