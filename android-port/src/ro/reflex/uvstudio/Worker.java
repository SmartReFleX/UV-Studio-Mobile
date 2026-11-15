package ro.reflex.uvstudio;

import android.os.Handler;
import android.os.Looper;

/**
 * Tiny background-job helper.
 *
 * Radio work (flashing, dumps) must never run on the UI thread, but the
 * screens should not all re-implement "new Thread + runOnUiThread".
 */
final class Worker {

    interface Task<T> {
        T run(Progress progress) throws Exception;
    }

    interface Callback<T> {
        void done(T value, Exception error);
    }

    interface Sink {
        void onProgress(int percent, String message);
    }

    static final class Progress {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private volatile Sink sink;
        private volatile boolean cancelled;

        void attach(Sink sink) {
            this.sink = sink;
        }

        /** Updates a progress bar and a status line; safe from any thread. */
        void publish(final int percent, final String message) {
            handler.post(() -> {
                Sink target = sink;
                if (target != null) target.onProgress(percent, message);
            });
        }

        void cancel() {
            cancelled = true;
        }

        boolean isCancelled() {
            return cancelled;
        }
    }

    private Worker() { }

    static <T> Progress run(String name, Task<T> task, Callback<T> callback) {
        Progress progress = new Progress();
        Thread thread = new Thread(() -> {
            T value = null;
            Exception error = null;
            try {
                value = task.run(progress);
            } catch (Exception e) {
                error = e;
            }
            final T result = value;
            final Exception failure = error;
            new Handler(Looper.getMainLooper()).post(() -> callback.done(result, failure));
        }, name);
        thread.setDaemon(true);
        thread.start();
        return progress;
    }
}
