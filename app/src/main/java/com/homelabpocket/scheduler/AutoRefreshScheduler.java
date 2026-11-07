// SPDX-License-Identifier: GPL-3.0-or-later
// Homelab Pocket — Foreground lifecycle-aware auto-refresh state machine
package com.homelabpocket.scheduler;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Foreground lifecycle-aware auto-refresh state machine.
 *
 * Coordinates scheduled telemetry refreshes across fleet and detail screens:
 * - Cadence defaults to 10 seconds (configurable: Off, 5s, 10s, 30s, 60s).
 * - Schedule next refresh strictly AFTER prior completion + interval (no fixed-rate overlap).
 * - Single loading owner per screen; duplicate and rapid manual refresh coalesced safely.
 * - Pauses immediately when activity is not resumed, backgrounded, screen is off,
 *   device is locked, session is locked, or Settings tab is visible.
 * - Discards detached/stale midflight network callbacks on pause, destroy, or session lock.
 * - Defers auto-refresh during in-flight power operations to preserve UI cards, dialogs, and tracking.
 * - Bounded exponential backoff on errors: 30s on first error, 60s max on subsequent errors.
 * - Manual refresh retries safely during backoff, resetting backoff only upon success.
 * - Avoids duplicate startup onCreate/onResume fetches while guaranteeing prompt read on genuine lifecycle resume.
 */
public class AutoRefreshScheduler {

    public interface RefreshAction {
        void execute(RefreshCallback callback);
    }

    public interface RefreshCallback {
        /**
         * Returns true if this callback still owns the active fetch and the scheduler
         * is in a valid resumed state to accept UI data binding.
         */
        default boolean canAcceptResult() {
            return true;
        }

        void onSuccess();
        void onError(Throwable throwable, String userMessage);
    }

    public interface ActionGuardPredicate {
        boolean isActionInProgress();
    }

    public enum LifecycleState {
        INITIAL,
        RESUMED,
        PAUSED,
        DESTROYED
    }

    public enum ExecutionState {
        IDLE,
        FETCHING,
        SCHEDULED,
        DEFERRED,
        BACKOFF
    }

    // Timing constants
    public static final long BACKOFF_FIRST_MS = 30_000L;
    public static final long BACKOFF_MAX_MS = 60_000L;
    public static final long FETCH_TIMEOUT_MS = 60_000L;
    public static final long STARTUP_DEDUP_WINDOW_MS = 500L;
    public static final long ACTION_DEFERRAL_CHECK_MS = 2_000L;

    private final Clock clock;
    private final TaskScheduler taskScheduler;
    private final RefreshPreferenceStore preferenceStore;
    private final RefreshAction refreshAction;
    private final ActionGuardPredicate actionGuardPredicate;

    private LifecycleState lifecycleState = LifecycleState.INITIAL;
    private ExecutionState executionState = ExecutionState.IDLE;

    private boolean isScreenOff = false;
    private boolean isDeviceLocked = false;
    private boolean isSessionLocked = false;
    private boolean isPollingEnabledForScreen = true;

    private int consecutiveErrors = 0;
    private long lastSuccessfulUpdate = 0;
    private long lastFetchStartTime = 0;
    private long lastFetchCompletionTime = 0;
    private long nextScheduledExecutionTime = 0;

    private TaskScheduler.Cancellable pendingTask = null;
    private TaskScheduler.Cancellable fetchWatchdogTask = null;
    private long activeWatchdogFetchId = 0;
    private long activeFetchId = 0;
    private long activeTransportFetchId = 0;
    private long timedOutFetchId = 0;
    private boolean hasQueuedRefresh = false;

    private long currentGenerationToken = 0;
    private final AtomicLong tokenGenerator = new AtomicLong(0);
    private final AtomicLong fetchIdGenerator = new AtomicLong(0);

    private final RefreshPreferenceStore.OnPreferenceChangeListener prefListener =
            newInterval -> onPreferenceChanged(newInterval);

    public AutoRefreshScheduler(
            Clock clock,
            TaskScheduler taskScheduler,
            RefreshPreferenceStore preferenceStore,
            RefreshAction refreshAction,
            ActionGuardPredicate actionGuardPredicate) {

        this.clock = clock != null ? clock : new SystemClockImpl();
        this.taskScheduler = taskScheduler != null ? taskScheduler : new HandlerTaskScheduler();
        this.preferenceStore = preferenceStore;
        this.refreshAction = refreshAction;
        this.actionGuardPredicate = actionGuardPredicate != null ? actionGuardPredicate : () -> false;

        if (this.preferenceStore != null) {
            this.preferenceStore.addListener(prefListener);
        }
    }

    public AutoRefreshScheduler(
            RefreshPreferenceStore preferenceStore,
            RefreshAction refreshAction,
            ActionGuardPredicate actionGuardPredicate) {
        this(new SystemClockImpl(), new HandlerTaskScheduler(), preferenceStore, refreshAction, actionGuardPredicate);
    }

    // =========================================================================
    // Lifecycle Controls
    // =========================================================================

    /**
     * Resumes the scheduler when activity transitions to resumed foreground state.
     * Dispatches a single prompt fresh read if active, then schedules next cadence.
     */
    public synchronized void resume() {
        if (lifecycleState == LifecycleState.DESTROYED) {
            return;
        }

        boolean wasPaused = (lifecycleState == LifecycleState.PAUSED);
        boolean wasInitial = (lifecycleState == LifecycleState.INITIAL);
        lifecycleState = LifecycleState.RESUMED;

        if (!canPoll()) {
            return;
        }

        // Avoid duplicate fetch if already fetching or transport is active
        if (executionState == ExecutionState.FETCHING || activeFetchId != 0 || activeTransportFetchId != 0) {
            if (wasPaused) {
                // Was paused and now resumed: in-flight fetch belongs to pre-pause generation and
                // will be rejected on completion. Queue an immediate fresh read so the resumed screen
                // gets its promised fresh lifecycle read even if cadence is Off!
                hasQueuedRefresh = true;
            }
            if (activeFetchId != 0 && (fetchWatchdogTask == null || fetchWatchdogTask.isCancelled())) {
                long elapsed = clock.elapsedRealtime() - lastFetchStartTime;
                long remaining = Math.max(ACTION_DEFERRAL_CHECK_MS, FETCH_TIMEOUT_MS - elapsed);
                long fid = activeFetchId;
                activeWatchdogFetchId = fid;
                fetchWatchdogTask = taskScheduler.schedule(() -> onFetchWatchdogTimeout(fid), remaining);
            }
            return;
        }

        // If duplicate resume call while already resumed, do not bypass backoff or pending cadence
        if (!wasPaused && !wasInitial) {
            if (executionState == ExecutionState.BACKOFF || executionState == ExecutionState.SCHEDULED) {
                return;
            }
        }

        long now = clock.elapsedRealtime();

        // Genuine lifecycle resume from pause: prompt fresh read immediately
        if (wasPaused) {
            cancelPendingTimer();
            dispatchFetch(false);
            return;
        }

        // Startup duplicate dedup window (e.g. onCreate -> onResume duplicate calls during same initial startup)
        if (lastFetchStartTime > 0 && (now - lastFetchStartTime < STARTUP_DEDUP_WINDOW_MS)) {
            if (isAutoRefreshActive() && pendingTask == null && executionState == ExecutionState.IDLE) {
                long interval = getIntervalMs();
                long remaining = lastFetchCompletionTime > 0
                        ? Math.max(0, (lastFetchCompletionTime + interval) - now)
                        : interval;
                scheduleNextFromCompletion(remaining);
            }
            return;
        }

        // Cancel any pending timer and prompt fresh read immediately
        cancelPendingTimer();
        dispatchFetch(false);
    }

    /**
     * Pauses the scheduler when activity leaves resumed state (background, screen off, etc.).
     * Cancels any pending scheduled timers and increments generation token to reject midflight callbacks.
     */
    public synchronized void pause() {
        if (lifecycleState == LifecycleState.DESTROYED) {
            return;
        }

        lifecycleState = LifecycleState.PAUSED;
        cancelPendingTimer();
        cancelWatchdog();
        // Invalidate in-flight tokens so detached callbacks are rejected
        currentGenerationToken = tokenGenerator.incrementAndGet();

        if (executionState != ExecutionState.FETCHING && executionState != ExecutionState.BACKOFF) {
            executionState = ExecutionState.IDLE;
        }
    }

    /**
     * Permanently terminates this scheduler instance.
     */
    public synchronized void destroy() {
        lifecycleState = LifecycleState.DESTROYED;
        cancelPendingTimer();
        cancelWatchdog();
        currentGenerationToken = tokenGenerator.incrementAndGet();
        executionState = ExecutionState.IDLE;
        activeTransportFetchId = 0;

        if (preferenceStore != null) {
            preferenceStore.removeListener(prefListener);
        }
    }

    // =========================================================================
    // Environmental State Triggers
    // =========================================================================

    public synchronized void setScreenOff(boolean screenOff) {
        if (this.isScreenOff == screenOff) {
            return;
        }
        this.isScreenOff = screenOff;
        if (screenOff) {
            cancelPendingTimer();
            cancelWatchdog();
            currentGenerationToken = tokenGenerator.incrementAndGet();
            if (executionState != ExecutionState.FETCHING && executionState != ExecutionState.BACKOFF) {
                executionState = ExecutionState.IDLE;
            }
        } else {
            handleEnvironmentalResume();
        }
    }

    public synchronized void setDeviceLocked(boolean locked) {
        if (this.isDeviceLocked == locked) {
            return;
        }
        this.isDeviceLocked = locked;
        if (locked) {
            cancelPendingTimer();
            cancelWatchdog();
            currentGenerationToken = tokenGenerator.incrementAndGet();
            if (executionState != ExecutionState.FETCHING && executionState != ExecutionState.BACKOFF) {
                executionState = ExecutionState.IDLE;
            }
        } else {
            handleEnvironmentalResume();
        }
    }

    public synchronized void setSessionLocked(boolean locked) {
        if (this.isSessionLocked == locked) {
            return;
        }
        this.isSessionLocked = locked;
        if (locked) {
            cancelPendingTimer();
            cancelWatchdog();
            currentGenerationToken = tokenGenerator.incrementAndGet();
            if (executionState != ExecutionState.FETCHING && executionState != ExecutionState.BACKOFF) {
                executionState = ExecutionState.IDLE;
            }
        } else {
            handleEnvironmentalResume();
        }
    }

    /**
     * Enables or disables polling for the current active view (e.g. false when Settings tab is active).
     */
    public synchronized void setPollingEnabledForScreen(boolean enabled) {
        if (this.isPollingEnabledForScreen == enabled) {
            return;
        }
        this.isPollingEnabledForScreen = enabled;
        if (!enabled) {
            cancelPendingTimer();
            cancelWatchdog();
            // Invalidate mid-flight tokens so old results cannot be revalidated after tab roundtrip
            currentGenerationToken = tokenGenerator.incrementAndGet();
            if (executionState != ExecutionState.FETCHING && executionState != ExecutionState.BACKOFF) {
                executionState = ExecutionState.IDLE;
            }
        } else {
            handleEnvironmentalResume();
        }
    }

    private void handleEnvironmentalResume() {
        if (lifecycleState == LifecycleState.RESUMED && canPoll()) {
            if (executionState == ExecutionState.FETCHING || activeFetchId != 0 || activeTransportFetchId != 0) {
                hasQueuedRefresh = true;
                // Restore watchdog for in-flight fetch
                if (activeFetchId != 0 && (fetchWatchdogTask == null || fetchWatchdogTask.isCancelled())) {
                    long elapsed = clock.elapsedRealtime() - lastFetchStartTime;
                    long remaining = Math.max(ACTION_DEFERRAL_CHECK_MS, FETCH_TIMEOUT_MS - elapsed);
                    long fid = activeFetchId;
                    activeWatchdogFetchId = fid;
                    fetchWatchdogTask = taskScheduler.schedule(() -> onFetchWatchdogTimeout(fid), remaining);
                }
            } else if (executionState == ExecutionState.BACKOFF) {
                // Honor remaining error backoff on roundtrip
                long backoffMs = (consecutiveErrors <= 1) ? BACKOFF_FIRST_MS : BACKOFF_MAX_MS;
                long elapsed = clock.elapsedRealtime() - lastFetchCompletionTime;
                long remaining = backoffMs - elapsed;
                if (remaining > 0) {
                    scheduleNextFromCompletion(Math.max(getIntervalMs(), remaining));
                } else {
                    cancelPendingTimer();
                    dispatchFetch(false);
                }
            } else {
                cancelPendingTimer();
                dispatchFetch(false);
            }
        }
    }

    // =========================================================================
    // Manual Refresh & Dispatch
    // =========================================================================

    /**
     * Safely triggers manual refresh.
     * Rejects if not resumed, paused, screen off, device locked, session locked, or polling disabled.
     * If a fetch is currently in flight, coalesces safely (returns false) while queuing a fresh read
     * and invalidating pre-refresh data binding.
     * If idle, cancels pending timers, dispatches immediate fetch, and returns true.
     */
    public synchronized boolean triggerManualRefresh() {
        if (lifecycleState != LifecycleState.RESUMED || !canPoll()) {
            return false;
        }

        // If action is running, do not start conflicting refresh
        if (actionGuardPredicate != null && actionGuardPredicate.isActionInProgress()) {
            return false;
        }

        // Coalesce: if already fetching or transport is outstanding, let the active fetch complete without duplicate request
        if (executionState == ExecutionState.FETCHING || activeFetchId != 0 || activeTransportFetchId != 0) {
            return false;
        }

        cancelPendingTimer();
        dispatchFetch(true);
        return true;
    }

    /**
     * Requests an explicit post-operation fresh read (e.g. after VM/LXC power task completion).
     * If a fetch is in flight, queues the read to execute immediately upon completion and
     * invalidates the pre-operation fetch generation. Works even if auto-refresh setting is Off.
     */
    public synchronized void requestPostOperationRefresh() {
        if (lifecycleState != LifecycleState.RESUMED || !canPoll()) {
            return;
        }

        // Action completed: advance generation so pre-action fetch cannot bind stale data
        currentGenerationToken = tokenGenerator.incrementAndGet();

        if (executionState == ExecutionState.FETCHING || activeFetchId != 0 || activeTransportFetchId != 0) {
            hasQueuedRefresh = true;
            return;
        }

        cancelPendingTimer();
        dispatchFetch(true);
    }

    /**
     * Checks whether a callback token is still valid for UI data binding.
     */
    public synchronized boolean isValidCallbackToken(long token) {
        return token == currentGenerationToken &&
               activeFetchId != 0 &&
               activeFetchId != timedOutFetchId &&
               lifecycleState == LifecycleState.RESUMED &&
               canPoll() &&
               (actionGuardPredicate == null || !actionGuardPredicate.isActionInProgress());
    }

    /**
     * Checks whether a specific fetch callback is still valid for UI data binding.
     */
    public synchronized boolean isValidCallback(long fetchId, long generation) {
        return fetchId == activeFetchId &&
               fetchId != timedOutFetchId &&
               generation == currentGenerationToken &&
               lifecycleState == LifecycleState.RESUMED &&
               canPoll() &&
               (actionGuardPredicate == null || !actionGuardPredicate.isActionInProgress());
    }

    /**
     * Notifies the scheduler that an explicit operation-completion fetch has finished.
     * Resets backoff and schedules the next regular cadence from this completion.
     */
    public synchronized void onExplicitFetchComplete(boolean success) {
        if (lifecycleState != LifecycleState.RESUMED || !canPoll()) {
            return;
        }
        // If a real telemetry fetch is currently in flight, explicit completion must NOT
        // free or overwrite the active loading owner!
        if (executionState == ExecutionState.FETCHING || activeFetchId != 0 || activeTransportFetchId != 0) {
            return;
        }
        long now = clock.elapsedRealtime();
        lastFetchCompletionTime = now;
        if (success) {
            consecutiveErrors = 0;
            lastSuccessfulUpdate = clock.currentTimeMillis();
            scheduleNextFromCompletion(getIntervalMs());
        } else {
            // Keep previous errors/backoff
            long backoffMs = (consecutiveErrors == 0) ? BACKOFF_FIRST_MS : BACKOFF_MAX_MS;
            scheduleNextFromCompletion(Math.max(getIntervalMs(), backoffMs));
        }
    }

    private void dispatchFetch(boolean isManual) {
        if (activeTransportFetchId != 0 || (executionState == ExecutionState.FETCHING && activeFetchId != 0)) {
            return;
        }

        // Defer telemetry refresh if power operation is in-flight
        if (actionGuardPredicate != null && actionGuardPredicate.isActionInProgress()) {
            if (isManual) {
                hasQueuedRefresh = true;
            }
            executionState = ExecutionState.DEFERRED;
            scheduleNextFromCompletion(ACTION_DEFERRAL_CHECK_MS);
            return;
        }

        long fetchId = fetchIdGenerator.incrementAndGet();
        activeFetchId = fetchId;
        activeTransportFetchId = fetchId;
        long generation = currentGenerationToken;
        executionState = ExecutionState.FETCHING;
        lastFetchStartTime = clock.elapsedRealtime();

        cancelWatchdog();
        activeWatchdogFetchId = fetchId;
        fetchWatchdogTask = taskScheduler.schedule(() -> onFetchWatchdogTimeout(fetchId), FETCH_TIMEOUT_MS);

        if (refreshAction != null) {
            refreshAction.execute(new RefreshCallback() {
                @Override
                public boolean canAcceptResult() {
                    return isValidCallback(fetchId, generation);
                }

                @Override
                public void onSuccess() {
                    handleFetchResult(fetchId, generation, isManual, true, null, null);
                }

                @Override
                public void onError(Throwable throwable, String userMessage) {
                    handleFetchResult(fetchId, generation, isManual, false, throwable, userMessage);
                }
            });
        }
    }

    private synchronized void cancelWatchdog() {
        if (fetchWatchdogTask != null) {
            fetchWatchdogTask.cancel();
            fetchWatchdogTask = null;
        }
        activeWatchdogFetchId = 0;
    }

    private synchronized void onFetchWatchdogTimeout(long fetchId) {
        if (fetchId != activeFetchId || executionState != ExecutionState.FETCHING) {
            return;
        }
        fetchWatchdogTask = null;
        activeWatchdogFetchId = 0;
        timedOutFetchId = fetchId;
        activeFetchId = 0;
        // Invalidate generation token so timed-out callback can never accept results
        currentGenerationToken = tokenGenerator.incrementAndGet();
        consecutiveErrors++;
        executionState = ExecutionState.BACKOFF;

        if (lifecycleState == LifecycleState.RESUMED && canPoll()) {
            if (actionGuardPredicate != null && actionGuardPredicate.isActionInProgress()) {
                executionState = ExecutionState.DEFERRED;
                scheduleNextFromCompletion(ACTION_DEFERRAL_CHECK_MS);
            } else {
                long backoffMs = (consecutiveErrors == 1) ? BACKOFF_FIRST_MS : BACKOFF_MAX_MS;
                long delayMs = Math.max(getIntervalMs(), backoffMs);
                scheduleNextFromCompletion(delayMs);
            }
        } else {
            executionState = ExecutionState.IDLE;
        }
    }

    private synchronized void handleFetchResult(
            long fetchId,
            long generation,
            boolean isManual,
            boolean success,
            Throwable error,
            String userMessage) {

        // Late callback check: if this callback does not belong to the active transport fetch:
        if (fetchId != activeTransportFetchId) {
            // Late / stale callback from an older retired fetch!
            // It MUST NOT cancel the active fetch's watchdog!
            // It MUST NOT touch executionState or activeFetchId or activeTransportFetchId!
            // It MUST NOT dispatch another fetch!
            return;
        }

        // Active transport fetch has completed: release exactly its ownership
        activeTransportFetchId = 0;
        if (fetchId == activeFetchId) {
            activeFetchId = 0;
        }
        cancelWatchdog();
        boolean wasTimedOut = (fetchId == timedOutFetchId);
        timedOutFetchId = 0;

        boolean queued = this.hasQueuedRefresh;
        this.hasQueuedRefresh = false;

        // Detached / stale callback rejection check:
        boolean isStale = wasTimedOut ||
                          (generation != currentGenerationToken) ||
                          (lifecycleState != LifecycleState.RESUMED) ||
                          isScreenOff || isDeviceLocked || isSessionLocked || !isPollingEnabledForScreen ||
                          (actionGuardPredicate != null && actionGuardPredicate.isActionInProgress());

        if (isStale) {
            cancelPendingTimer();

            // If screen is resumed and can poll, dispatch safely according to current lifecycle/backoff
            if (lifecycleState == LifecycleState.RESUMED && canPoll()) {
                if (actionGuardPredicate != null && actionGuardPredicate.isActionInProgress()) {
                    if (queued) {
                        hasQueuedRefresh = true;
                    }
                    executionState = ExecutionState.DEFERRED;
                    scheduleNextFromCompletion(ACTION_DEFERRAL_CHECK_MS);
                } else if (queued || isAutoRefreshActive()) {
                    if (wasTimedOut) {
                        long backoffMs = (consecutiveErrors <= 1) ? BACKOFF_FIRST_MS : BACKOFF_MAX_MS;
                        long backoffExpiresAt = lastFetchStartTime + FETCH_TIMEOUT_MS + backoffMs;
                        long remainingBackoff = backoffExpiresAt - clock.elapsedRealtime();
                        if (remainingBackoff <= 0 || queued) {
                            dispatchFetch(queued);
                        } else {
                            executionState = ExecutionState.BACKOFF;
                            scheduleNextFromCompletion(remainingBackoff);
                        }
                    } else {
                        dispatchFetch(queued);
                    }
                } else {
                    executionState = ExecutionState.IDLE;
                }
            } else {
                if (executionState != ExecutionState.BACKOFF) {
                    executionState = ExecutionState.IDLE;
                }
            }
            return;
        }

        lastFetchCompletionTime = clock.elapsedRealtime();

        if (queued) {
            // Explicit post-operation refresh was queued: dispatch immediate fresh read!
            if (success) {
                consecutiveErrors = 0;
                lastSuccessfulUpdate = clock.currentTimeMillis();
            }
            dispatchFetch(true);
            return;
        }

        if (success) {
            consecutiveErrors = 0;
            lastSuccessfulUpdate = clock.currentTimeMillis();
            executionState = ExecutionState.IDLE;

            if (isAutoRefreshActive()) {
                scheduleNextFromCompletion(getIntervalMs());
            }
        } else {
            consecutiveErrors++;
            executionState = ExecutionState.BACKOFF;

            if (isAutoRefreshActive()) {
                long backoffMs = (consecutiveErrors == 1) ? BACKOFF_FIRST_MS : BACKOFF_MAX_MS;
                long delayMs = Math.max(getIntervalMs(), backoffMs);
                scheduleNextFromCompletion(delayMs);
            }
        }
    }

    private synchronized void scheduleNextFromCompletion(long delayMs) {
        cancelPendingTimer();

        if (!canPoll() || delayMs <= 0) {
            if (executionState != ExecutionState.BACKOFF && executionState != ExecutionState.FETCHING && executionState != ExecutionState.DEFERRED) {
                executionState = ExecutionState.IDLE;
            }
            return;
        }

        if (!isAutoRefreshActive() && executionState != ExecutionState.DEFERRED) {
            if (executionState != ExecutionState.BACKOFF && executionState != ExecutionState.FETCHING) {
                executionState = ExecutionState.IDLE;
            }
            return;
        }

        long now = clock.elapsedRealtime();
        nextScheduledExecutionTime = now + delayMs;

        if (executionState != ExecutionState.BACKOFF && executionState != ExecutionState.DEFERRED) {
            executionState = ExecutionState.SCHEDULED;
        }

        pendingTask = taskScheduler.schedule(() -> onScheduledTick(), delayMs);
    }

    private synchronized void onScheduledTick() {
        pendingTask = null;

        if (lifecycleState != LifecycleState.RESUMED || !canPoll()) {
            executionState = ExecutionState.IDLE;
            return;
        }

        // Defer telemetry refresh if power operation is in-flight
        if (actionGuardPredicate != null && actionGuardPredicate.isActionInProgress()) {
            executionState = ExecutionState.DEFERRED;
            scheduleNextFromCompletion(ACTION_DEFERRAL_CHECK_MS);
            return;
        }

        if (!isAutoRefreshActive() && executionState != ExecutionState.DEFERRED && !hasQueuedRefresh) {
            executionState = ExecutionState.IDLE;
            return;
        }

        // Never dispatch another request while original is outstanding
        if (activeTransportFetchId != 0) {
            executionState = ExecutionState.BACKOFF;
            long backoffMs = (consecutiveErrors <= 1) ? BACKOFF_FIRST_MS : BACKOFF_MAX_MS;
            scheduleNextFromCompletion(Math.max(getIntervalMs(), backoffMs));
            return;
        }

        boolean queued = hasQueuedRefresh;
        hasQueuedRefresh = false;
        dispatchFetch(queued);
    }

    private synchronized void onPreferenceChanged(int newInterval) {
        if (newInterval <= 0) {
            // Off: cancel pending timer
            cancelPendingTimer();
            if (executionState != ExecutionState.FETCHING && executionState != ExecutionState.BACKOFF) {
                executionState = ExecutionState.IDLE;
            }
        } else {
            // New interval set
            if (lifecycleState == LifecycleState.RESUMED && canPoll()) {
                if (executionState == ExecutionState.IDLE && pendingTask == null) {
                    dispatchFetch(false);
                } else if (executionState == ExecutionState.SCHEDULED && pendingTask != null) {
                    // Reschedule for new interval
                    cancelPendingTimer();
                    scheduleNextFromCompletion(newInterval * 1000L);
                } else if (executionState == ExecutionState.BACKOFF) {
                    // Re-enabling from BACKOFF: calculate remaining backoff
                    long backoffMs = (consecutiveErrors <= 1) ? BACKOFF_FIRST_MS : BACKOFF_MAX_MS;
                    long elapsed = clock.elapsedRealtime() - lastFetchCompletionTime;
                    long remaining = backoffMs - elapsed;
                    long delay = Math.max(newInterval * 1000L, Math.max(0, remaining));
                    cancelPendingTimer();
                    scheduleNextFromCompletion(delay);
                }
            }
        }
    }

    private void cancelPendingTimer() {
        if (pendingTask != null) {
            pendingTask.cancel();
            pendingTask = null;
        }
        nextScheduledExecutionTime = 0;
    }

    private boolean canPoll() {
        return !isScreenOff && !isDeviceLocked && !isSessionLocked && isPollingEnabledForScreen;
    }

    private boolean isAutoRefreshActive() {
        return preferenceStore == null || preferenceStore.isAutoRefreshEnabled();
    }

    private long getIntervalMs() {
        return preferenceStore != null ? preferenceStore.getRefreshIntervalMillis() : 10_000L;
    }

    // =========================================================================
    // Inspection / Seam Accessors (for tests & state monitoring)
    // =========================================================================

    public synchronized LifecycleState getLifecycleState() {
        return lifecycleState;
    }

    public synchronized ExecutionState getExecutionState() {
        return executionState;
    }

    public synchronized int getConsecutiveErrors() {
        return consecutiveErrors;
    }

    public synchronized long getLastSuccessfulUpdate() {
        return lastSuccessfulUpdate;
    }

    public synchronized long getLastFetchStartTime() {
        return lastFetchStartTime;
    }

    public synchronized long getLastFetchCompletionTime() {
        return lastFetchCompletionTime;
    }

    public synchronized long getNextScheduledExecutionTime() {
        return nextScheduledExecutionTime;
    }

    public synchronized boolean hasPendingTimer() {
        return pendingTask != null && !pendingTask.isCancelled();
    }

    public synchronized long getCurrentGenerationToken() {
        return currentGenerationToken;
    }

    public synchronized long getActiveFetchId() {
        return activeFetchId;
    }

    public synchronized long getActiveTransportFetchId() {
        return activeTransportFetchId;
    }

    public synchronized boolean hasQueuedRefresh() {
        return hasQueuedRefresh;
    }
}
