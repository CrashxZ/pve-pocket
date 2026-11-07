// SPDX-License-Identifier: GPL-3.0-or-later
// Homelab Pocket — Time seam for lifecycle-aware auto-refresh scheduling
package com.homelabpocket.scheduler;

/**
 * Clock abstraction enabling deterministic headless unit testing without thread sleep.
 */
public interface Clock {
    /**
     * Returns wall-clock milliseconds since UNIX epoch (matches System.currentTimeMillis()).
     */
    long currentTimeMillis();

    /**
     * Returns monotonic milliseconds since boot (matches android.os.SystemClock.elapsedRealtime()).
     */
    long elapsedRealtime();
}
