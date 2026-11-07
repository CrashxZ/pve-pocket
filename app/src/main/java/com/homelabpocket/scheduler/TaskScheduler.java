// SPDX-License-Identifier: GPL-3.0-or-later
// Homelab Pocket — Scheduled task dispatcher seam
package com.homelabpocket.scheduler;

/**
 * Task scheduling abstraction separating Android Handler execution from headless test dispatch.
 */
public interface TaskScheduler {

    /**
     * Handle representing a scheduled delayed task that can be cancelled.
     */
    interface Cancellable {
        void cancel();
        boolean isCancelled();
    }

    /**
     * Schedules a task to run after the given delay in milliseconds.
     *
     * @param task Runnable to execute
     * @param delayMillis Delay in milliseconds
     * @return Cancellable handle
     */
    Cancellable schedule(Runnable task, long delayMillis);
}
