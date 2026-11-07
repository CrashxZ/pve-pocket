// SPDX-License-Identifier: GPL-3.0-or-later
// Homelab Pocket — Android Handler-based TaskScheduler
package com.homelabpocket.scheduler;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Production TaskScheduler implementation using Android Handler on the UI thread.
 */
public class HandlerTaskScheduler implements TaskScheduler {

    private final Handler handler;

    public HandlerTaskScheduler() {
        this(new Handler(Looper.getMainLooper()));
    }

    public HandlerTaskScheduler(Handler handler) {
        this.handler = handler;
    }

    @Override
    public Cancellable schedule(Runnable task, long delayMillis) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Runnable wrapper = () -> {
            if (!cancelled.get()) {
                task.run();
            }
        };

        handler.postDelayed(wrapper, Math.max(0, delayMillis));

        return new Cancellable() {
            @Override
            public void cancel() {
                cancelled.set(true);
                handler.removeCallbacks(wrapper);
            }

            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }
        };
    }
}
