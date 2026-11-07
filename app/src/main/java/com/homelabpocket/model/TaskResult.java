// SPDX-License-Identifier: GPL-3.0-or-later
// Homelab Pocket — API task result for polling
package com.homelabpocket.model;

/**
 * Represents a Proxmox API task (UPID) result during/after polling.
 * Tasks are started by VM/LXC control operations and polled until completion.
 */
public class TaskResult {

    public enum TaskState {
        RUNNING,    // Task is still executing
        OK,         // Task completed successfully
        WARNINGS,   // Task completed with warnings
        ERROR,      // Task failed with error
        TIMEOUT,    // Polling timed out without completion
        UNKNOWN     // State indeterminate
    }

    /** Full UPID string from API */
    public final String upid;

    /** Current state */
    public final TaskState state;

    /** Exit status from API (e.g. "OK", error message) */
    public final String exitStatus;

    /** Progress message from logs, may be null */
    public final String progressText;

    /** Time task started (epoch seconds) */
    public final long startTime;

    /** Time task ended (epoch seconds), 0 if still running */
    public final long endTime;

    /** Node this task runs on */
    public final String node;

    public TaskResult(String upid, TaskState state, String exitStatus,
                      String progressText, long startTime, long endTime,
                      String node) {
        this.upid = upid;
        this.state = state;
        this.exitStatus = exitStatus;
        this.progressText = progressText;
        this.startTime = startTime;
        this.endTime = endTime;
        this.node = node;
    }

    public boolean isFinished() {
        return state != TaskState.RUNNING;
    }

    public boolean isSuccess() {
        return state == TaskState.OK || state == TaskState.WARNINGS;
    }

    /** Human-readable summary */
    public String getSummary() {
        switch (state) {
            case RUNNING: return "Running…" + (progressText != null ? " " + progressText : "");
            case OK: return "Completed successfully";
            case WARNINGS: return "Completed with warnings: " + (exitStatus != null ? exitStatus : "");
            case ERROR: return "Failed: " + (exitStatus != null ? exitStatus : "unknown error");
            case TIMEOUT: return "Timed out waiting for completion";
            default: return "Unknown state";
        }
    }
}
