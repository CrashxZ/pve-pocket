// SPDX-License-Identifier: GPL-3.0-or-later
// Homelab Pocket — Nonsecret global preference for telemetry auto-refresh cadence
package com.homelabpocket.scheduler;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Persists the non-secret global telemetry auto-refresh cadence preference.
 *
 * Supported intervals:
 * - 0: Off (manual refresh only)
 * - 5: 5 seconds
 * - 10: 10 seconds (Recommended default)
 * - 30: 30 seconds
 * - 60: 60 seconds (1 minute)
 *
 * Cadence targets represent foreground polling frequency, not measured hypervisor
 * API freshness. Upstream metrics (particularly on Proxmox Datacenter Manager)
 * are collected on PDM's own periodic cycle (~10s–30s).
 */
public class RefreshPreferenceStore {

    public static final String PREFS_NAME = "homelab_refresh_prefs";
    public static final String KEY_AUTO_REFRESH_SECONDS = "auto_refresh_seconds";

    public static final int INTERVAL_OFF = 0;
    public static final int INTERVAL_5S = 5;
    public static final int INTERVAL_10S = 10;
    public static final int INTERVAL_30S = 30;
    public static final int INTERVAL_60S = 60;
    public static final int DEFAULT_INTERVAL_SECONDS = INTERVAL_10S;

    public interface OnPreferenceChangeListener {
        void onRefreshPreferenceChanged(int newIntervalSeconds);
    }

    private final SharedPreferences prefs;
    private final List<OnPreferenceChangeListener> listeners = new CopyOnWriteArrayList<>();

    public RefreshPreferenceStore(Context context) {
        this(context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE));
    }

    public RefreshPreferenceStore(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    /**
     * Checks if the given interval in seconds is one of the supported values.
     */
    public static boolean isValidInterval(int seconds) {
        return seconds == INTERVAL_OFF ||
               seconds == INTERVAL_5S ||
               seconds == INTERVAL_10S ||
               seconds == INTERVAL_30S ||
               seconds == INTERVAL_60S;
    }

    /**
     * Returns the configured auto-refresh interval in seconds.
     * Defaults to 10 seconds if unset or invalid.
     */
    public int getRefreshIntervalSeconds() {
        if (prefs == null) {
            return DEFAULT_INTERVAL_SECONDS;
        }
        int interval = prefs.getInt(KEY_AUTO_REFRESH_SECONDS, DEFAULT_INTERVAL_SECONDS);
        return isValidInterval(interval) ? interval : DEFAULT_INTERVAL_SECONDS;
    }

    /**
     * Returns the auto-refresh interval in milliseconds.
     */
    public long getRefreshIntervalMillis() {
        return getRefreshIntervalSeconds() * 1000L;
    }

    /**
     * Returns true if auto-refresh is active (interval > 0).
     */
    public boolean isAutoRefreshEnabled() {
        return getRefreshIntervalSeconds() > 0;
    }

    /**
     * Persists a new interval setting. Rejects invalid interval values.
     * Notifies registered listeners upon change.
     *
     * @param seconds New interval in seconds (0, 5, 10, 30, or 60)
     * @return true if updated successfully, false if value invalid or commit failed
     */
    public boolean setRefreshIntervalSeconds(int seconds) {
        if (!isValidInterval(seconds)) {
            return false;
        }
        int oldVal = getRefreshIntervalSeconds();
        if (prefs != null) {
            boolean committed = prefs.edit().putInt(KEY_AUTO_REFRESH_SECONDS, seconds).commit();
            if (!committed) {
                return false;
            }
        }
        if (oldVal != seconds) {
            for (OnPreferenceChangeListener listener : listeners) {
                try {
                    listener.onRefreshPreferenceChanged(seconds);
                } catch (Throwable ignored) {}
            }
        }
        return true;
    }

    public void addListener(OnPreferenceChangeListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(OnPreferenceChangeListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }
}
