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
 * - Ownership-aware: supports ActionGuard.TaskLifecycleOwner and ActionGuard.Lease to prevent ABA releases.
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
        return pollUntilFinishedInternal(actionKey, null, null, nodeName, upid, auth, fetcher, callback, timeoutMs, pollIntervalMs);
    }

    public TaskResult pollUntilFinished(ActionGuard.TaskLifecycleOwner lifecycleOwner,
                                       String nodeName,
                                       String upid,
                                       AuthCredentials auth,
                                       BoundedTaskStatusFetcher fetcher,
                                       PollingCallback callback,
                                       long timeoutMs,
                                       long pollIntervalMs) {
        String actionKey = (lifecycleOwner != null) ? lifecycleOwner.getKey() : null;
        ActionGuard.Lease lease = (lifecycleOwner != null) ? lifecycleOwner.getLease() : null;
        return pollUntilFinishedInternal(actionKey, lease, lifecycleOwner, nodeName, upid, auth, fetcher, callback, timeoutMs, pollIntervalMs);
    }

    public TaskResult pollUntilFinished(ActionGuard.Lease lease,
                                       String nodeName,
                                       String upid,
                                       AuthCredentials auth,
                                       BoundedTaskStatusFetcher fetcher,
                                       PollingCallback callback,
                                       long timeoutMs,
                                       long pollIntervalMs) {
        String actionKey = (lease != null) ? lease.getKey() : null;
        return pollUntilFinishedInternal(actionKey, lease, null, nodeName, upid, auth, fetcher, callback, timeoutMs, pollIntervalMs);
    }

    private TaskResult pollUntilFinishedInternal(String actionKey,
                                                ActionGuard.Lease lease,
                                                ActionGuard.TaskLifecycleOwner lifecycleOwner,
                                                String nodeName,
                                                String upid,
                                                AuthCredentials auth,
                                                BoundedTaskStatusFetcher fetcher,
                                                PollingCallback callback,
                                                long timeoutMs,
                                                long pollIntervalMs) {

        Runnable releaseGuard = () -> {
            if (lifecycleOwner != null) {
                lifecycleOwner.release();
            } else if (lease != null) {
                lease.release();
            } else if (actionKey != null) {
                ActionGuard.getInstance().release(actionKey);
            }
        };

        if (upid == null || upid.trim().isEmpty()) {
            releaseGuard.run();
            return new TaskResult(upid, TaskResult.TaskState.ERROR,
                    "Invalid task UPID (empty or null)", null, 0, 0, nodeName);
        }

        if (fetcher == null) {
            releaseGuard.run();
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
                    long elapsedSec = (nowMs - startMs) / 1000;
                    return new TaskResult(upid, TaskResult.TaskState.TIMEOUT,
                            "Task polling timed out after " + elapsedSec + "s without completion",
                            null, startMs / 1000, nowMs / 1000, nodeName);
                }

                long remainingMs = deadlineMs - nowMs;

                // 3. Execute bounded network poll
                TaskResult current;
                try {
                    current = fetcher.poll(nodeName, upid, auth, remainingMs);
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

                // 4. Handle Terminal States
                if (current.isFinished()) {
                    return current;
                }

                // 5. Still RUNNING: Dispatch progress notification
                if (callback != null) {
                    callback.onProgress(current);
                }

                // 6. Cooperative sleep bounded by remaining interval and deadline
                nowMs = clock.monotonicTimeMs();
                remainingMs = deadlineMs - nowMs;
                if (remainingMs <= 0) {
                    long elapsedSec = (nowMs - startMs) / 1000;
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
            // Guard preservation guarantee: actionKey/lease is NEVER released while RUNNING,
            // and ALWAYS released when terminal state, deadline, cancellation, or exception occurs.
            releaseGuard.run();
        }
    }
}
