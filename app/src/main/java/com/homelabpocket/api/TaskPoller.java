// SPDX-License-Identifier: GPL-3.0-or-later
// Homelab Pocket — Shared Bounded Task Polling Owner
package com.homelabpocket.api;

import com.homelabpocket.model.AuthCredentials;
import com.homelabpocket.model.TaskResult;

/**
 * Shared production bounded polling owner for Proxmox API tasks (UPIDs).
 *
 * Requirements & Security Guarantees:
 * - Owns bounded task polling lifecycle across all callers (GuestsView, VmDetailActivity).
 * - Preserves the ActionGuard lock during all intermediate RUNNING states to prevent double-submits.
 * - Guarantees ActionGuard release in finally block upon any terminal state, timeout, or cancellation.
 * - Enforces monotonic timeout bounds (Clock.monotonicTimeMs) to guard against clock skew or infinite loops.
 * - Surfaces explicit disclosures for OK, ERROR, TIMEOUT, and UNKNOWN states.
 * - Supports cooperative cancellation and progress dispatch to UI callbacks.
 */
public class TaskPoller {

    public interface TaskStatusFetcher {
        TaskResult poll(String nodeName, String upid, AuthCredentials auth) throws Exception;
    }

    public interface BoundedTaskStatusFetcher {
        TaskResult poll(String nodeName, String upid, AuthCredentials auth, long remainingBudgetMs) throws Exception;
    }

    public interface PollingCallback {
        void onProgress(TaskResult progress);
        boolean isCancelled();
    }

    public interface Clock {
        long monotonicTimeMs();
        void sleep(long millis) throws InterruptedException;
    }

    public static final Clock SYSTEM_CLOCK = new Clock() {
        @Override
        public long monotonicTimeMs() {
            return System.nanoTime() / 1_000_000L;
        }

        @Override
        public void sleep(long millis) throws InterruptedException {
            Thread.sleep(millis);
        }
    };

    public static final long DEFAULT_POLL_INTERVAL_MS = 1000L;
    public static final long DEFAULT_TIMEOUT_MS = 60_000L; // 60s monotonic limit

    private static final TaskPoller INSTANCE = new TaskPoller();

    public static TaskPoller getInstance() {
        return INSTANCE;
    }

    private final Clock clock;
    private final long defaultPollIntervalMs;
    private final long defaultTimeoutMs;

    public TaskPoller() {
        this(SYSTEM_CLOCK, DEFAULT_POLL_INTERVAL_MS, DEFAULT_TIMEOUT_MS);
    }

    public TaskPoller(Clock clock, long defaultPollIntervalMs, long defaultTimeoutMs) {
        this.clock = clock != null ? clock : SYSTEM_CLOCK;
        this.defaultPollIntervalMs = defaultPollIntervalMs > 0 ? defaultPollIntervalMs : DEFAULT_POLL_INTERVAL_MS;
        this.defaultTimeoutMs = defaultTimeoutMs > 0 ? defaultTimeoutMs : DEFAULT_TIMEOUT_MS;
    }

    /**
     * Polls a Proxmox task (UPID) until completion, deadline, or cancellation.
     * Preserves actionKey in ActionGuard until execution reaches terminal state.
     *
     * @param actionKey ActionGuard key currently acquired (released in finally)
     * @param nodeName Host node name
     * @param upid Task UPID string
     * @param auth Server credentials
     * @param fetcher Implementation calling pve.pollTask
     * @param callback Optional progress and cancellation callback
     * @return Terminal TaskResult (OK, ERROR, TIMEOUT, or UNKNOWN)
     */
    public TaskResult pollUntilFinished(String actionKey,
                                       String nodeName,
                                       String upid,
                                       AuthCredentials auth,
                                       TaskStatusFetcher fetcher,
                                       PollingCallback callback) {
        return pollUntilFinished(actionKey, nodeName, upid, auth,
                fetcher != null ? (n, u, a, b) -> fetcher.poll(n, u, a) : (BoundedTaskStatusFetcher) null,
                callback, defaultTimeoutMs, defaultPollIntervalMs);
    }

    public TaskResult pollUntilFinished(String actionKey,
                                       String nodeName,
                                       String upid,
                                       AuthCredentials auth,
                                       TaskStatusFetcher fetcher,
                                       PollingCallback callback,
                                       long timeoutMs,
                                       long pollIntervalMs) {
        return pollUntilFinished(actionKey, nodeName, upid, auth,
                fetcher != null ? (n, u, a, b) -> fetcher.poll(n, u, a) : (BoundedTaskStatusFetcher) null,
                callback, timeoutMs, pollIntervalMs);
    }

    public TaskResult pollUntilFinished(String actionKey,
                                       String nodeName,
                                       String upid,
                                       AuthCredentials auth,
                                       BoundedTaskStatusFetcher fetcher,
                                       PollingCallback callback) {
        return pollUntilFinished(actionKey, nodeName, upid, auth, fetcher, callback,
                defaultTimeoutMs, defaultPollIntervalMs);
    }

    public TaskResult pollUntilFinished(String actionKey,
                                       String nodeName,
                                       String upid,
                                       AuthCredentials auth,
                                       BoundedTaskStatusFetcher fetcher,
                                       PollingCallback callback,
                                       long timeoutMs,
                                       long pollIntervalMs) {

        if (upid == null || upid.trim().isEmpty()) {
            if (actionKey != null) {
                ActionGuard.getInstance().release(actionKey);
            }
            return new TaskResult(upid, TaskResult.TaskState.ERROR,
                    "Invalid task UPID (empty or null)", null, 0, 0, nodeName);
        }

        if (fetcher == null) {
            if (actionKey != null) {
                ActionGuard.getInstance().release(actionKey);
            }
            return new TaskResult(upid, TaskResult.TaskState.ERROR,
                    "TaskStatusFetcher cannot be null", null, 0, 0, nodeName);
        }

        long startMs = clock.monotonicTimeMs();
        long limitMs = timeoutMs > 0 ? timeoutMs : defaultTimeoutMs;
        long deadlineMs = startMs + limitMs;
        long intervalMs = pollIntervalMs > 0 ? pollIntervalMs : defaultPollIntervalMs;

        try {
            while (true) {
                // 1. Check cooperative cancellation before network probe
                if (callback != null && callback.isCancelled()) {
                    return new TaskResult(upid, TaskResult.TaskState.UNKNOWN,
                            "Task polling cancelled by user", null,
                            startMs / 1000, clock.monotonicTimeMs() / 1000, nodeName);
                }

                // 2. Monotonic deadline verification before network probe
                long nowMs = clock.monotonicTimeMs();
                if (nowMs >= deadlineMs) {
                    long elapsedSec = Math.max(1, (nowMs - startMs) / 1000);
                    return new TaskResult(upid, TaskResult.TaskState.TIMEOUT,
                            "Task polling timed out after " + elapsedSec + "s without completion",
                            null, startMs / 1000, nowMs / 1000, nodeName);
                }

                long remainingBudgetMs = deadlineMs - nowMs;

                // 3. Poll current status bounded by remaining budget
                TaskResult current;
                try {
                    current = fetcher.poll(nodeName, upid, auth, remainingBudgetMs);
                } catch (ApiException e) {
                    if (clock.monotonicTimeMs() >= deadlineMs) {
                        long elapsedSec = Math.max(1, (clock.monotonicTimeMs() - startMs) / 1000);
                        return new TaskResult(upid, TaskResult.TaskState.TIMEOUT,
                                "Task polling timed out after " + elapsedSec + "s without completion",
                                null, startMs / 1000, clock.monotonicTimeMs() / 1000, nodeName);
                    }
                    if (callback != null && callback.isCancelled()) {
                        return new TaskResult(upid, TaskResult.TaskState.UNKNOWN,
                                "Task polling cancelled by user", null,
                                startMs / 1000, clock.monotonicTimeMs() / 1000, nodeName);
                    }
                    return new TaskResult(upid, TaskResult.TaskState.UNKNOWN,
                            "API error polling task: " + e.getUserMessage(), null,
                            startMs / 1000, clock.monotonicTimeMs() / 1000, nodeName);
                } catch (Exception e) {
                    if (clock.monotonicTimeMs() >= deadlineMs) {
                        long elapsedSec = Math.max(1, (clock.monotonicTimeMs() - startMs) / 1000);
                        return new TaskResult(upid, TaskResult.TaskState.TIMEOUT,
                                "Task polling timed out after " + elapsedSec + "s without completion",
                                null, startMs / 1000, clock.monotonicTimeMs() / 1000, nodeName);
                    }
                    if (callback != null && callback.isCancelled()) {
                        return new TaskResult(upid, TaskResult.TaskState.UNKNOWN,
                                "Task polling cancelled by user", null,
                                startMs / 1000, clock.monotonicTimeMs() / 1000, nodeName);
                    }
                    return new TaskResult(upid, TaskResult.TaskState.UNKNOWN,
                            "Network error polling task: " + e.getMessage(), null,
                            startMs / 1000, clock.monotonicTimeMs() / 1000, nodeName);
                }

                // POST-FETCH RECHECK: Strictly check cancellation & deadline before accepting result!
                // 3a. Recheck cooperative cancellation after network fetch
                if (callback != null && callback.isCancelled()) {
                    return new TaskResult(upid, TaskResult.TaskState.UNKNOWN,
                            "Task polling cancelled by user", null,
                            startMs / 1000, clock.monotonicTimeMs() / 1000, nodeName);
                }

                // 3b. Recheck monotonic deadline after network fetch (rejects late completion)
                long postFetchMs = clock.monotonicTimeMs();
                if (postFetchMs >= deadlineMs) {
                    long elapsedSec = Math.max(1, (postFetchMs - startMs) / 1000);
                    return new TaskResult(upid, TaskResult.TaskState.TIMEOUT,
                            "Task polling timed out after " + elapsedSec + "s without completion",
                            null, startMs / 1000, postFetchMs / 1000, nodeName);
                }

                if (current == null) {
                    return new TaskResult(upid, TaskResult.TaskState.UNKNOWN,
                            "Null task status returned from server", null,
                            startMs / 1000, clock.monotonicTimeMs() / 1000, nodeName);
                }

                // 4. Check for terminal states
                if (current.state == TaskResult.TaskState.OK) {
                    return current;
                }
                if (current.state == TaskResult.TaskState.ERROR) {
                    return current;
                }
                if (current.state == TaskResult.TaskState.TIMEOUT) {
                    return current;
                }
                if (current.state == TaskResult.TaskState.UNKNOWN) {
                    return current;
                }

                // 5. Task still RUNNING: dispatch intermediate progress
                if (callback != null) {
                    callback.onProgress(current);
                }

                // 6. Check cooperative cancellation again before sleep
                if (callback != null && callback.isCancelled()) {
                    return new TaskResult(upid, TaskResult.TaskState.UNKNOWN,
                            "Task polling cancelled by user", null,
                            startMs / 1000, clock.monotonicTimeMs() / 1000, nodeName);
                }

                // 7. Bounded monotonic sleep
                long remainingMs = deadlineMs - clock.monotonicTimeMs();
                if (remainingMs <= 0) {
                    long elapsedSec = Math.max(1, (clock.monotonicTimeMs() - startMs) / 1000);
                    return new TaskResult(upid, TaskResult.TaskState.TIMEOUT,
                            "Task polling timed out after " + elapsedSec + "s without completion",
                            null, startMs / 1000, clock.monotonicTimeMs() / 1000, nodeName);
                }

                long sleepDuration = Math.min(intervalMs, remainingMs);
                try {
                    clock.sleep(sleepDuration);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new TaskResult(upid, TaskResult.TaskState.UNKNOWN,
                            "Task polling interrupted", null,
                            startMs / 1000, clock.monotonicTimeMs() / 1000, nodeName);
                }
            }
        } finally {
            // Guard preservation guarantee: actionKey is NEVER released while RUNNING,
            // and ALWAYS released when terminal state, deadline, cancellation, or exception occurs.
            if (actionKey != null) {
                ActionGuard.getInstance().release(actionKey);
            }
        }
    }
}
