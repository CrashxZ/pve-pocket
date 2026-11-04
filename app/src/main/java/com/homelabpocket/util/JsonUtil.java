// SPDX-License-Identifier: GPL-3.0-or-later
// Homelab Pocket — JSON parser utility (no external library dependency)
package com.homelabpocket.util;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Lightweight helpers for parsing Proxmox API JSON responses.
 * Proxmox API wraps results in {"data": ...} envelope.
 */
public class JsonUtil {

    /**
     * Extracts the "data" field from a Proxmox API response.
     * @throws JSONException if parsing fails
     */
    public static JSONObject getDataObject(String jsonResponse) throws JSONException {
        JSONObject root = new JSONObject(jsonResponse);
        return root.getJSONObject("data");
    }

    public static JSONArray getDataArray(String jsonResponse) throws JSONException {
        JSONObject root = new JSONObject(jsonResponse);
        return root.getJSONArray("data");
    }

    public static String optString(JSONObject obj, String key, String fallback) {
        try {
            String val = obj.optString(key, fallback);
            return (val == null || val.isEmpty()) ? fallback : val;
        } catch (Exception e) {
            return fallback;
        }
    }

    public static long optLong(JSONObject obj, String key, long fallback) {
        try {
            if (obj.isNull(key)) return fallback;
            return obj.optLong(key, fallback);
        } catch (Exception e) {
            return fallback;
        }
    }

    public static double optDouble(JSONObject obj, String key, double fallback) {
        try {
            if (obj.isNull(key)) return fallback;
            return obj.optDouble(key, fallback);
        } catch (Exception e) {
            return fallback;
        }
    }

    public static int optInt(JSONObject obj, String key, int fallback) {
        try {
            if (obj.isNull(key)) return fallback;
            return obj.optInt(key, fallback);
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * Formats bytes as human-readable string (KB, MB, GB, TB).
     */
    public static String formatBytes(long bytes) {
        if (bytes < 0) return "?";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        if (bytes < 1024L * 1024 * 1024 * 1024) return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
        return String.format("%.2f TB", bytes / (1024.0 * 1024 * 1024 * 1024));
    }

    /**
     * Formats uptime seconds to "Xd Xh Xm" format.
     */
    public static String formatUptime(long seconds) {
        if (seconds < 0) return "offline";
        if (seconds == 0) return "0m";
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0) sb.append(hours).append("h ");
        sb.append(minutes).append("m");
        return sb.toString().trim();
    }

    /**
     * Formats a data rate in bytes/sec.
     */
    public static String formatRate(long bytesPerSec) {
        if (bytesPerSec < 0) return "?/s";
        return formatBytes(bytesPerSec) + "/s";
    }

    /**
     * Sanitizes a string for logging — removes anything that looks like
     * a credential token (long hex strings, PVEAuthCookie values, etc.)
     */
    public static String sanitizeForLog(String input) {
        if (input == null) return null;
        // Redact PVEAuthCookie and similar patterns
        return input
            .replaceAll("PVEAuthCookie=[^;\\s&]+", "PVEAuthCookie=REDACTED")
            .replaceAll("PVEAPIToken=[^\\s]+", "PVEAPIToken=REDACTED")
            .replaceAll("CSRFPreventionToken:[^\\r\\n]+", "CSRFPreventionToken:REDACTED")
            .replaceAll("\"ticket\"\\s*:\\s*\"[^\"]+\"", "\"ticket\":\"REDACTED\"")
            .replaceAll("\"password\"\\s*:\\s*\"[^\"]+\"", "\"password\":\"REDACTED\"");
    }
}
