// SPDX-License-Identifier: GPL-3.0-or-later
// Homelab Pocket — Standard system clock implementation
package com.homelabpocket.scheduler;

/**
 * Production Clock implementation delegating to System and Android platform clocks.
 */
public class SystemClockImpl implements Clock {

    @Override
    public long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    @Override
    public long elapsedRealtime() {
        try {
            return android.os.SystemClock.elapsedRealtime();
        } catch (Throwable fallback) {
            // JVM test fallback if running without Android framework
            return System.nanoTime() / 1_000_000L;
        }
    }
}
