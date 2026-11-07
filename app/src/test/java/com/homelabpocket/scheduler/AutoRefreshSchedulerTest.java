// SPDX-License-Identifier: GPL-3.0-or-later
// Homelab Pocket — Behavioral test suite for foreground lifecycle-aware auto-refresh state machine
package com.homelabpocket.scheduler;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/**
 * Verification test suite for AutoRefreshScheduler lifecycle state machine.
 *
 * NOTE ON TEST EXECUTION ENVIRONMENT & HEADLESS SEAMS:
 * These tests execute in a standard JVM environment using injectable Clock and TaskScheduler seams.
 * They directly exercise the production AutoRefreshScheduler state machine logic used by
 * MainActivity, NodeDetailActivity, and VmDetailActivity without requiring the Android Looper
 * or an active device runtime.
 *
 * The headless seams allow precise time travel, synchronous verification of race conditions,
 * detached callback rejection, error backoff progression, power operation deferral, and
 * cancellation without asynchronous flakiness.
 */
public class AutoRefreshSchedulerTest {

    // =========================================================================
    // Test Doubles (Clock, TaskScheduler, RefreshAction, ActionGuardPredicate)
    // =========================================================================

    public static class TestClock implements Clock {
        private long currentMillis = 1_000_000L;
        private long elapsedRealtime = 50_000L;

        @Override
        public long currentTimeMillis() {
            return currentMillis;
        }

        @Override
        public long elapsedRealtime() {
            return elapsedRealtime;
        }

        public void advance(long ms) {
            currentMillis += ms;
            elapsedRealtime += ms;
        }

        public void set(long current, long elapsed) {
            this.currentMillis = current;
            this.elapsedRealtime = elapsed;
        }
    }

    public static class TestTaskScheduler implements TaskScheduler {
        static class ScheduledItem {
            final Runnable task;
            final long triggerElapsedRealtime;
            final AtomicBoolean cancelled = new AtomicBoolean(false);

            ScheduledItem(Runnable task, long triggerElapsedRealtime) {
                this.task = task;
                this.triggerElapsedRealtime = triggerElapsedRealtime;
            }
        }

        private final List<ScheduledItem> items = new ArrayList<>();
        private final TestClock clock;

        public TestTaskScheduler(TestClock clock) {
            this.clock = clock;
        }

        @Override
        public Cancellable schedule(Runnable task, long delayMillis) {
            ScheduledItem item = new ScheduledItem(task, clock.elapsedRealtime() + delayMillis);
            items.add(item);
            return new Cancellable() {
                @Override
                public void cancel() {
                    item.cancelled.set(true);
                }

                @Override
                public boolean isCancelled() {
                    return item.cancelled.get();
                }
            };
        }

        public int getActiveTaskCount() {
            int count = 0;
            for (ScheduledItem i : items) {
                if (!i.cancelled.get()) count++;
            }
            return count;
        }

        public Long getNextTriggerElapsed() {
            Long next = null;
            for (ScheduledItem i : items) {
                if (!i.cancelled.get()) {
                    if (next == null || i.triggerElapsedRealtime < next) {
                        next = i.triggerElapsedRealtime;
                    }
                }
            }
            return next;
        }

        /**
         * Advances time by ms and fires all due tasks in order.
         */
        public void advanceTime(long ms) {
            long targetTime = clock.elapsedRealtime() + ms;
            while (true) {
                ScheduledItem earliest = null;
                for (ScheduledItem i : items) {
                    if (!i.cancelled.get() && i.triggerElapsedRealtime <= targetTime) {
                        if (earliest == null || i.triggerElapsedRealtime < earliest.triggerElapsedRealtime) {
                            earliest = i;
                        }
                    }
                }
                if (earliest == null) {
                    break;
                }
                items.remove(earliest);
                clock.set(clock.currentTimeMillis() + (earliest.triggerElapsedRealtime - clock.elapsedRealtime()), earliest.triggerElapsedRealtime);
                earliest.task.run();
            }
            if (clock.elapsedRealtime() < targetTime) {
                clock.advance(targetTime - clock.elapsedRealtime());
            }
        }

        public boolean triggerNextDueTask() {
            ScheduledItem earliest = null;
            for (ScheduledItem i : items) {
                if (!i.cancelled.get()) {
                    if (earliest == null || i.triggerElapsedRealtime < earliest.triggerElapsedRealtime) {
                        earliest = i;
                    }
                }
            }
            if (earliest != null) {
                items.remove(earliest);
                if (earliest.triggerElapsedRealtime > clock.elapsedRealtime()) {
                    clock.advance(earliest.triggerElapsedRealtime - clock.elapsedRealtime());
                }
                earliest.task.run();
                return true;
            }
            return false;
        }

        public void clear() {
            items.clear();
        }
    }

    public static class TestRefreshAction implements AutoRefreshScheduler.RefreshAction {
        public enum Mode {
            MANUAL,             // Fetch stays in flight until explicitly completed
            IMMEDIATE_SUCCESS,  // Fetch completes immediately with success
            IMMEDIATE_ERROR     // Fetch completes immediately with error
        }

        private Mode mode = Mode.MANUAL;
        private int dispatchCount = 0;
        private final List<AutoRefreshScheduler.RefreshCallback> pendingCallbacks = new ArrayList<>();
        private String errorMessage = "Simulated Network Error";

        public void setMode(Mode mode) {
            this.mode = mode;
        }

        public void setErrorMessage(String msg) {
            this.errorMessage = msg;
        }

        public int getDispatchCount() {
            return dispatchCount;
        }

        public int getPendingCallbackCount() {
            return pendingCallbacks.size();
        }

        @Override
        public void execute(AutoRefreshScheduler.RefreshCallback callback) {
            dispatchCount++;
            if (mode == Mode.IMMEDIATE_SUCCESS) {
                callback.onSuccess();
            } else if (mode == Mode.IMMEDIATE_ERROR) {
                callback.onError(new Exception(errorMessage), errorMessage);
            } else {
                pendingCallbacks.add(callback);
            }
        }

        public boolean completeNextSuccess() {
            if (!pendingCallbacks.isEmpty()) {
                AutoRefreshScheduler.RefreshCallback cb = pendingCallbacks.remove(0);
                cb.onSuccess();
                return true;
            }
            return false;
        }

        public boolean completeNextError(String msg) {
            if (!pendingCallbacks.isEmpty()) {
                AutoRefreshScheduler.RefreshCallback cb = pendingCallbacks.remove(0);
                cb.onError(new Exception(msg), msg);
                return true;
            }
            return false;
        }

        public void completeAllSuccess() {
            while (!pendingCallbacks.isEmpty()) {
                completeNextSuccess();
            }
        }

        public void completeAllError(String msg) {
            while (!pendingCallbacks.isEmpty()) {
                completeNextError(msg);
            }
        }

        public AutoRefreshScheduler.RefreshCallback getLastCallback() {
            return pendingCallbacks.isEmpty() ? null : pendingCallbacks.get(pendingCallbacks.size() - 1);
        }
    }

    public static class TestActionGuardPredicate implements AutoRefreshScheduler.ActionGuardPredicate {
        public boolean inProgress = false;

        @Override
        public boolean isActionInProgress() {
            return inProgress;
        }
    }

    private TestClock clock;
    private TestTaskScheduler taskScheduler;
    private TestRefreshAction refreshAction;
    private TestActionGuardPredicate actionGuard;
    private RefreshPreferenceStore preferenceStore;
    private Map<String, Object> backingPrefs;
    private AutoRefreshScheduler scheduler;

    @Before
    public void setUp() {
        clock = new TestClock();
        taskScheduler = new TestTaskScheduler(clock);
        refreshAction = new TestRefreshAction();
        actionGuard = new TestActionGuardPredicate();

        backingPrefs = new HashMap<>();
        android.content.SharedPreferences.Editor editor = (android.content.SharedPreferences.Editor) Proxy.newProxyInstance(
                AutoRefreshSchedulerTest.class.getClassLoader(),
                new Class[]{android.content.SharedPreferences.Editor.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("putInt".equals(name)) {
                        backingPrefs.put((String) args[0], args[1]);
                        return proxy;
                    }
                    if ("commit".equals(name)) return true;
                    if ("apply".equals(name)) return null;
                    return proxy;
                }
        );

        android.content.SharedPreferences fakePrefs = (android.content.SharedPreferences) Proxy.newProxyInstance(
                AutoRefreshSchedulerTest.class.getClassLoader(),
                new Class[]{android.content.SharedPreferences.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("edit".equals(name)) return editor;
                    if ("getInt".equals(name)) {
                        Object val = backingPrefs.get(args[0]);
                        return val instanceof Integer ? (Integer) val : (Integer) args[1];
                    }
                    return null;
                }
        );

        preferenceStore = new RefreshPreferenceStore(fakePrefs);
        preferenceStore.setRefreshIntervalSeconds(RefreshPreferenceStore.INTERVAL_10S);

        scheduler = new AutoRefreshScheduler(clock, taskScheduler, preferenceStore, refreshAction, actionGuard);
    }

    // =========================================================================
    // 1. Completion Interval Scheduling (AFTER Completion + Interval)
    // =========================================================================

    @Test
    public void testCompletionIntervalSchedulingNotFixedRateOverlap() {
        // Resume activity -> immediate fresh read dispatched
        scheduler.resume();
        assertEquals("Prompt fresh read dispatched immediately on resume", 1, refreshAction.getDispatchCount());
        assertEquals(AutoRefreshScheduler.ExecutionState.FETCHING, scheduler.getExecutionState());

        // Simulated network I/O takes 2,500ms
        clock.advance(2500);

        // Fetch completes successfully
        refreshAction.completeNextSuccess();
        assertEquals(AutoRefreshScheduler.ExecutionState.SCHEDULED, scheduler.getExecutionState());
        assertTrue("Scheduler must have a pending scheduled timer", scheduler.hasPendingTimer());

        // Next execution must be at completionTime (52,500) + 10,000 = 62,500
        long completionElapsed = scheduler.getLastFetchCompletionTime();
        assertEquals("Completion time recorded accurately", 52_500L, completionElapsed);

        Long nextScheduled = taskScheduler.getNextTriggerElapsed();
        assertNotNull("Next trigger must be scheduled", nextScheduled);
        assertEquals("Next refresh must be scheduled strictly AFTER completion + interval (no fixed rate)",
                completionElapsed + 10_000L, (long) nextScheduled);

        // Advance 9,999ms: should NOT have fired yet
        taskScheduler.advanceTime(9999);
        assertEquals("Fetch must not fire before completion + interval has fully elapsed", 1, refreshAction.getDispatchCount());

        // Advance 1ms more: exactly at 10,000ms after completion -> second fetch fires
        taskScheduler.advanceTime(1);
        assertEquals("Second fetch dispatched after interval from prior completion", 2, refreshAction.getDispatchCount());
    }

    // =========================================================================
    // 2. Slow Fetch & Overlap Prevention / Manual Refresh Coalescing
    // =========================================================================

    @Test
    public void testSlowFetchPreventsDuplicateOrOverlappingRequests() {
        scheduler.resume();
        assertEquals(1, refreshAction.getDispatchCount());
        assertEquals(AutoRefreshScheduler.ExecutionState.FETCHING, scheduler.getExecutionState());

        // While fetch 1 is still in flight:
        // Try triggering manual refresh -> must be COALESCED (returns false, no new dispatch)
        boolean manualAccepted = scheduler.triggerManualRefresh();
        assertFalse("Manual refresh during in-flight fetch must coalesce safely", manualAccepted);
        assertEquals("Dispatch count must remain 1 during slow fetch", 1, refreshAction.getDispatchCount());

        // Resume call while still fetching must also be deduplicated
        scheduler.resume();
        assertEquals("Duplicate resume during in-flight fetch must be ignored", 1, refreshAction.getDispatchCount());

        // Advance simulated time past normal interval (e.g. 15,000ms)
        clock.advance(15_000);
        assertFalse("No cadence timer tasks should have fired overlapping requests", scheduler.hasPendingTimer());

        // Complete slow fetch 1
        refreshAction.completeNextSuccess();
        assertEquals(AutoRefreshScheduler.ExecutionState.SCHEDULED, scheduler.getExecutionState());
        assertTrue(scheduler.hasPendingTimer());

        // Now next fetch is scheduled 10s from completion
        taskScheduler.advanceTime(10_000);
        assertEquals("Second fetch dispatches only after prior completion + interval", 2, refreshAction.getDispatchCount());
    }

    @Test
    public void testManualRefreshWhenIdleDispatchesImmediatelyAndResetsSchedule() {
        scheduler.resume();
        refreshAction.completeNextSuccess();
        assertEquals(1, refreshAction.getDispatchCount());
        assertTrue(scheduler.hasPendingTimer());

        // Advance 4,000ms (timer pending for 6,000ms more)
        taskScheduler.advanceTime(4_000);
        assertEquals(1, refreshAction.getDispatchCount());

        // User taps manual Refresh
        boolean manualTriggered = scheduler.triggerManualRefresh();
        assertTrue("Manual refresh when idle must be accepted", manualTriggered);
        assertEquals("Manual refresh dispatches immediate fetch", 2, refreshAction.getDispatchCount());
        assertEquals(AutoRefreshScheduler.ExecutionState.FETCHING, scheduler.getExecutionState());

        // The previous pending timer was cancelled
        assertFalse("Previous cadence timer was cancelled upon manual dispatch", scheduler.hasPendingTimer());

        // Complete manual fetch
        clock.advance(500);
        refreshAction.completeNextSuccess();

        // Next cadence is now scheduled 10s from the MANUAL completion
        Long nextTrigger = taskScheduler.getNextTriggerElapsed();
        assertNotNull(nextTrigger);
        assertEquals("New cadence scheduled from manual completion",
                clock.elapsedRealtime() + 10_000L, (long) nextTrigger);
    }

    // =========================================================================
    // 3. Pause Midflight & Detached Callback Rejection
    // =========================================================================

    @Test
    public void testPauseMidflightRejectsDetachedCallbackAndCancelsPendingTimer() {
        scheduler.resume();
        assertEquals(1, refreshAction.getDispatchCount());
        assertEquals(AutoRefreshScheduler.ExecutionState.FETCHING, scheduler.getExecutionState());
        long tokenBeforePause = scheduler.getCurrentGenerationToken();

        // Activity leaves foreground (e.g. user pressed home or switched activity)
        scheduler.pause();
        assertEquals(AutoRefreshScheduler.LifecycleState.PAUSED, scheduler.getLifecycleState());
        assertNotEquals("Pause must increment generation token to invalidate in-flight callbacks",
                tokenBeforePause, scheduler.getCurrentGenerationToken());
        assertEquals("No timers should be pending after pause", 0, taskScheduler.getActiveTaskCount());

        // Slow network fetch finishes while activity is paused
        refreshAction.completeNextSuccess();

        // Detached callback must be rejected:
        assertEquals("Last successful update timestamp must NOT be updated by detached callback",
                0L, scheduler.getLastSuccessfulUpdate());
        assertEquals("No timer should be scheduled after detached completion",
                0, taskScheduler.getActiveTaskCount());
    }

    @Test
    public void testDestroyRejectsCallbacksAndTerminatesScheduler() {
        scheduler.resume();
        assertEquals(1, refreshAction.getDispatchCount());

        scheduler.destroy();
        assertEquals(AutoRefreshScheduler.LifecycleState.DESTROYED, scheduler.getLifecycleState());
        assertEquals(0, taskScheduler.getActiveTaskCount());

        // Midflight callback finishes
        refreshAction.completeNextSuccess();
        assertEquals(0L, scheduler.getLastSuccessfulUpdate());

        // Further actions rejected
        assertFalse(scheduler.triggerManualRefresh());
        scheduler.resume();
        assertEquals(1, refreshAction.getDispatchCount());
    }

    // =========================================================================
    // 4. Resume Single Prompt Fresh Read & Avoid Duplicate Startup
    // =========================================================================

    @Test
    public void testResumeTriggersSinglePromptFreshReadThenSchedules() {
        // Initially paused
        scheduler.pause();
        assertEquals(0, refreshAction.getDispatchCount());

        // Resumed
        scheduler.resume();
        assertEquals("Prompt fresh read dispatched immediately upon resume", 1, refreshAction.getDispatchCount());
        assertEquals(AutoRefreshScheduler.ExecutionState.FETCHING, scheduler.getExecutionState());

        // Complete fetch
        refreshAction.completeNextSuccess();
        assertTrue("Scheduler schedules regular interval after prompt fresh read", scheduler.hasPendingTimer());
    }

    @Test
    public void testAvoidDuplicateStartupOnCreateOnResumeFetch() {
        // Startup: onCreate initializes and onResume immediately follows
        scheduler.resume();
        assertEquals(1, refreshAction.getDispatchCount());

        // Rapid subsequent call within startup window (e.g. 50ms later)
        clock.advance(50);
        scheduler.resume();
        assertEquals("Rapid startup onResume must NOT launch duplicate request", 1, refreshAction.getDispatchCount());
    }

    // =========================================================================
    // 5. Environmental Triggers: Screen Off, Device Lock, Session Lock, Settings Tab
    // =========================================================================

    @Test
    public void testScreenOffPausesSchedulerAndScreenOnResumes() {
        scheduler.resume();
        refreshAction.completeNextSuccess();
        assertEquals(1, refreshAction.getDispatchCount());
        assertTrue(scheduler.hasPendingTimer());

        // Screen turns off
        scheduler.setScreenOff(true);
        assertEquals("Pending timer cancelled when screen off", 0, taskScheduler.getActiveTaskCount());

        // Timer tasks advance past normal schedule: no fetch while screen off
        taskScheduler.advanceTime(20_000);
        assertEquals("No polling while screen is off", 1, refreshAction.getDispatchCount());

        // Screen turns back on
        scheduler.setScreenOff(false);
        assertEquals("Prompt fresh read dispatched when screen turns back on", 2, refreshAction.getDispatchCount());
    }

    @Test
    public void testDeviceKeyguardLockHaultsPolling() {
        scheduler.resume();
        refreshAction.completeNextSuccess();
        assertTrue(scheduler.hasPendingTimer());

        // Device locked by keyguard
        scheduler.setDeviceLocked(true);
        assertEquals("Timers cancelled when device locked", 0, taskScheduler.getActiveTaskCount());

        // Unlock resumes polling
        scheduler.setDeviceLocked(false);
        assertEquals(2, refreshAction.getDispatchCount());
    }

    @Test
    public void testSessionLockHaltsPollingAndPreventsInfiniteAuthRetry() {
        scheduler.resume();
        assertEquals(1, refreshAction.getDispatchCount());

        // Session locks while fetch is in-flight
        scheduler.setSessionLocked(true);

        // Fetch returns error or token
        refreshAction.completeNextError("401 Unauthorized");
        assertEquals("Detached response rejected on session lock", 0, taskScheduler.getActiveTaskCount());

        // While session locked, manual refresh or scheduled refresh is completely blocked
        assertFalse("Manual refresh rejected while session locked", scheduler.triggerManualRefresh());
        taskScheduler.advanceTime(100_000);
        assertEquals("No polling or infinite auth retries while session locked", 1, refreshAction.getDispatchCount());
    }

    @Test
    public void testSettingsTabDisablesPollingOwner() {
        scheduler.resume();
        refreshAction.completeNextSuccess();
        assertEquals(1, refreshAction.getDispatchCount());
        assertTrue(scheduler.hasPendingTimer());

        // User switches to Settings tab (tab 3)
        scheduler.setPollingEnabledForScreen(false);
        assertEquals("Polling cancelled when on Settings tab", 0, taskScheduler.getActiveTaskCount());

        taskScheduler.advanceTime(60_000);
        assertEquals("No polling while Settings tab is active", 1, refreshAction.getDispatchCount());

        // User switches back to Overview / Hosts / Guests
        scheduler.setPollingEnabledForScreen(true);
        assertEquals("Prompt fresh read dispatched when returning from Settings tab", 2, refreshAction.getDispatchCount());
    }

    // =========================================================================
    // 6. Bounded Exponential Backoff on Errors (30s then 60s max)
    // =========================================================================

    @Test
    public void testBoundedExponentialBackoff30sThen60sMax() {
        scheduler.resume();
        assertEquals(1, refreshAction.getDispatchCount());

        // Error 1 occurs
        refreshAction.completeNextError("Network timeout");
        assertEquals("Error count is 1", 1, scheduler.getConsecutiveErrors());
        assertEquals(AutoRefreshScheduler.ExecutionState.BACKOFF, scheduler.getExecutionState());
        assertEquals("Last successful update remains 0 (stale)", 0L, scheduler.getLastSuccessfulUpdate());

        // Delay should be 30,000ms
        Long nextDelay1 = taskScheduler.getNextTriggerElapsed();
        assertNotNull(nextDelay1);
        assertEquals("First error backoff must be 30 seconds",
                clock.elapsedRealtime() + 30_000L, (long) nextDelay1);

        // Advance to 30s: retry 1 dispatches
        taskScheduler.advanceTime(30_000);
        assertEquals(2, refreshAction.getDispatchCount());

        // Error 2 occurs
        refreshAction.completeNextError("Connection refused");
        assertEquals("Error count is 2", 2, scheduler.getConsecutiveErrors());

        // Delay should be 60,000ms max
        Long nextDelay2 = taskScheduler.getNextTriggerElapsed();
        assertNotNull(nextDelay2);
        assertEquals("Second error backoff must be 60 seconds max",
                clock.elapsedRealtime() + 60_000L, (long) nextDelay2);

        // Advance to 60s: retry 2 dispatches
        taskScheduler.advanceTime(60_000);
        assertEquals(3, refreshAction.getDispatchCount());

        // Error 3 occurs -> bounded at 60s max
        refreshAction.completeNextError("Host unreachable");
        assertEquals(3, scheduler.getConsecutiveErrors());
        Long nextDelay3 = taskScheduler.getNextTriggerElapsed();
        assertNotNull(nextDelay3);
        assertEquals("Subsequent error backoff remains bounded at 60 seconds max",
                clock.elapsedRealtime() + 60_000L, (long) nextDelay3);
    }

    @Test
    public void testManualRefreshDuringBackoffCanRetryImmediatelyAndResetsOnlyOnSuccess() {
        scheduler.resume();
        refreshAction.completeNextError("Initial failure");
        assertEquals(1, scheduler.getConsecutiveErrors());
        assertEquals(AutoRefreshScheduler.ExecutionState.BACKOFF, scheduler.getExecutionState());

        // Manual refresh can retry immediately during backoff
        boolean retried = scheduler.triggerManualRefresh();
        assertTrue("Manual refresh can retry safely during backoff", retried);
        assertEquals(2, refreshAction.getDispatchCount());

        // If manual retry ALSO FAILS: backoff is NOT reset!
        refreshAction.completeNextError("Manual retry failed");
        assertEquals(2, scheduler.getConsecutiveErrors());
        assertEquals(AutoRefreshScheduler.ExecutionState.BACKOFF, scheduler.getExecutionState());
        Long nextSchedule = taskScheduler.getNextTriggerElapsed();
        assertNotNull(nextSchedule);
        assertEquals("Failed manual retry preserves backoff (now 60s max)",
                clock.elapsedRealtime() + 60_000L, (long) nextSchedule);

        // Now manual retry again, and this time it SUCCEEDS
        scheduler.triggerManualRefresh();
        assertEquals(3, refreshAction.getDispatchCount());
        refreshAction.completeNextSuccess();

        // Backoff is RESET after SUCCESS
        assertEquals("Backoff errors reset to 0 after success", 0, scheduler.getConsecutiveErrors());
        assertEquals(AutoRefreshScheduler.ExecutionState.SCHEDULED, scheduler.getExecutionState());
        Long resetSchedule = taskScheduler.getNextTriggerElapsed();
        assertNotNull(resetSchedule);
        assertEquals("Next cadence returns to normal 10s after success",
                clock.elapsedRealtime() + 10_000L, (long) resetSchedule);
    }

    // =========================================================================
    // 7. Action Deferral During In-Flight Power Operations
    // =========================================================================

    @Test
    public void testActionDeferralDuringPowerOperationsPreservesCardsAndDialogs() {
        scheduler.resume();
        refreshAction.completeNextSuccess();
        assertEquals(1, refreshAction.getDispatchCount());

        // Power operation starts (ActionGuard holds active lease)
        actionGuard.inProgress = true;

        // Advance time to when next refresh tick would normally fire (10s)
        taskScheduler.advanceTime(10_000);

        // Scheduled auto-refresh MUST BE DEFERRED: no fetch dispatched, no UI flicker
        assertEquals("Auto-refresh must be deferred while power operation is active", 1, refreshAction.getDispatchCount());
        assertEquals(AutoRefreshScheduler.ExecutionState.DEFERRED, scheduler.getExecutionState());

        // Power operation completes after another 3s, triggering explicit refresh
        clock.advance(3000);
        actionGuard.inProgress = false;
        scheduler.onExplicitFetchComplete(true);

        assertEquals("Consecutive errors 0 after explicit operation success", 0, scheduler.getConsecutiveErrors());

        // Next cadence resumes from explicit operation completion
        Long nextCadence = taskScheduler.getNextTriggerElapsed();
        assertNotNull(nextCadence);
        assertEquals("Cadence resumes after operation-completion refresh",
                clock.elapsedRealtime() + 10_000L, (long) nextCadence);
    }

    // =========================================================================
    // 8. Settings Changes: Off, 5s, 30s, 60s
    // =========================================================================

    @Test
    public void testChangingSettingsToOffCancelsPendingTimer() {
        scheduler.resume();
        refreshAction.completeNextSuccess();
        assertTrue(scheduler.hasPendingTimer());

        // User changes setting to Off (0)
        preferenceStore.setRefreshIntervalSeconds(RefreshPreferenceStore.INTERVAL_OFF);
        assertFalse("Pending timer cancelled when set to Off", scheduler.hasPendingTimer());
        assertEquals(AutoRefreshScheduler.ExecutionState.IDLE, scheduler.getExecutionState());

        // Advance time: no auto-refresh runs
        taskScheduler.advanceTime(100_000);
        assertEquals(1, refreshAction.getDispatchCount());

        // Turning back on (e.g. 5s) triggers fresh schedule
        preferenceStore.setRefreshIntervalSeconds(RefreshPreferenceStore.INTERVAL_5S);
        assertEquals("Turning back on triggers prompt read", 2, refreshAction.getDispatchCount());
    }

    @Test
    public void testChangingIntervalReschedulesPendingTimer() {
        scheduler.resume();
        refreshAction.completeNextSuccess();

        // Change from 10s to 30s
        preferenceStore.setRefreshIntervalSeconds(RefreshPreferenceStore.INTERVAL_30S);
        Long nextDelay = taskScheduler.getNextTriggerElapsed();
        assertNotNull(nextDelay);
        assertEquals("Pending timer rescheduled for 30 seconds",
                clock.elapsedRealtime() + 30_000L, (long) nextDelay);
    }

    // =========================================================================
    // 9. LastUpdated Timestamp Invariant
    // =========================================================================

    @Test
    public void testLastUpdatedReflectsSuccessfulDataOnlyNotReRenderOrError() {
        clock.set(100_000L, 50_000L);
        assertEquals(0L, scheduler.getLastSuccessfulUpdate());

        scheduler.resume();
        clock.advance(1000); // clock = 101,000
        refreshAction.completeNextSuccess();

        assertEquals("Last updated timestamp set to exact fetch success time",
                101_000L, scheduler.getLastSuccessfulUpdate());

        // Subsequent tab changes, pause, resume: must NOT touch timestamp!
        scheduler.pause();
        clock.advance(5000); // clock = 106,000
        assertEquals("Pause does not change last updated timestamp",
                101_000L, scheduler.getLastSuccessfulUpdate());

        // Next fetch fails: timestamp must NOT change!
        scheduler.resume();
        clock.advance(1000); // clock = 107,000
        refreshAction.completeNextError("500 Server Error");
        assertEquals("Error does not overwrite successful timestamp, keeping old data clearly stale",
                101_000L, scheduler.getLastSuccessfulUpdate());

        // Successful fetch updates timestamp
        taskScheduler.advanceTime(30_000);
        clock.advance(1000); // clock = 138,000
        refreshAction.completeNextSuccess();
        assertEquals("Fresh successful fetch updates timestamp",
                clock.currentTimeMillis(), scheduler.getLastSuccessfulUpdate());
    }

    // =========================================================================
    // 10. Controller Interim Review: Focused Lifecycle & Owner Tests
    // =========================================================================

    @Test
    public void testSlowFetchPauseResumeOldCompletionIssuesFreshFetchAndPollsWithoutOverlap() {
        // 1. Resume starts slow fetch 1 (token 1)
        scheduler.resume();
        assertEquals(1, refreshAction.getDispatchCount());
        assertEquals(AutoRefreshScheduler.ExecutionState.FETCHING, scheduler.getExecutionState());
        AutoRefreshScheduler.RefreshCallback slowCallback = refreshAction.getLastCallback();
        assertNotNull(slowCallback);
        assertTrue(slowCallback.canAcceptResult());

        // 2. Pause while fetch 1 is in-flight: token invalidated, timers cleared
        scheduler.pause();
        assertEquals(AutoRefreshScheduler.LifecycleState.PAUSED, scheduler.getLifecycleState());
        assertFalse("Paused state must reject old in-flight callback acceptance", slowCallback.canAcceptResult());
        assertEquals(0, taskScheduler.getActiveTaskCount());

        // 3. Resume while slow fetch 1 is still in-flight
        // Must NOT launch duplicate/overlapping fetch while background I/O is still active
        scheduler.resume();
        assertEquals(AutoRefreshScheduler.LifecycleState.RESUMED, scheduler.getLifecycleState());
        assertEquals("Resume while fetch in flight must not launch overlapping fetch", 1, refreshAction.getDispatchCount());

        // 4. Old fetch 1 completes
        refreshAction.completeNextSuccess();
        // Stale result discarded; scheduler immediately prompts exactly one fresh fetch (fetch 2)
        assertEquals("resumed screen must issue exactly one fresh fetch", 2, refreshAction.getDispatchCount());
        assertEquals(AutoRefreshScheduler.ExecutionState.FETCHING, scheduler.getExecutionState());
        AutoRefreshScheduler.RefreshCallback freshCallback = refreshAction.getLastCallback();
        assertNotNull(freshCallback);
        assertTrue(freshCallback.canAcceptResult());

        // 5. Fresh fetch 2 completes successfully -> schedules polling cadence without overlap
        clock.advance(500);
        refreshAction.completeNextSuccess();
        assertEquals(AutoRefreshScheduler.ExecutionState.SCHEDULED, scheduler.getExecutionState());
        assertTrue(scheduler.hasPendingTimer());

        // 6. Clock advance triggers subsequent poll
        taskScheduler.advanceTime(10_000);
        assertEquals("Continuous polling continues after fresh fetch completion", 3, refreshAction.getDispatchCount());
    }

    @Test
    public void testPauseAfterCompletedFetchThenResumePromptsFreshRead() {
        // Fetch 1 starts at t=0
        scheduler.resume();
        assertEquals(1, refreshAction.getDispatchCount());

        // Fetch 1 completes quickly at t=50
        clock.advance(50);
        refreshAction.completeNextSuccess();
        assertEquals(AutoRefreshScheduler.ExecutionState.SCHEDULED, scheduler.getExecutionState());
        assertTrue(scheduler.hasPendingTimer());

        // Pause at t=100
        clock.advance(50);
        scheduler.pause();
        assertEquals(AutoRefreshScheduler.LifecycleState.PAUSED, scheduler.getLifecycleState());
        assertEquals(0, taskScheduler.getActiveTaskCount());

        // Resume at t=200: genuine lifecycle resume from pause prompts a single fresh read immediately
        clock.advance(100);
        scheduler.resume();
        assertEquals(AutoRefreshScheduler.LifecycleState.RESUMED, scheduler.getLifecycleState());
        assertEquals("Genuine lifecycle resume prompts fresh read", 2, refreshAction.getDispatchCount());
        assertEquals(AutoRefreshScheduler.ExecutionState.FETCHING, scheduler.getExecutionState());

        // Completing fresh read schedules next regular cadence
        refreshAction.completeNextSuccess();
        assertEquals(AutoRefreshScheduler.ExecutionState.SCHEDULED, scheduler.getExecutionState());
        assertTrue(scheduler.hasPendingTimer());
    }

    @Test
    public void testStartupEnvironmentSettersSequencingPreventsDuplicateFetches() {
        // Standard startup owner sequence when keyguard/session are unlocked and screen is on:
        scheduler.setScreenOff(false);
        scheduler.setDeviceLocked(false);
        scheduler.setSessionLocked(false);
        scheduler.setPollingEnabledForScreen(true);
        assertEquals("Initial environment configuration before resume must not dispatch", 0, refreshAction.getDispatchCount());

        // Initial resume dispatches prompt fresh read
        scheduler.resume();
        assertEquals("Initial resume dispatches exactly one fetch", 1, refreshAction.getDispatchCount());
        assertEquals(AutoRefreshScheduler.ExecutionState.FETCHING, scheduler.getExecutionState());

        // Redundant calls with false / enabled while FETCHING must NOT dispatch duplicate fetches or overwrite owner
        scheduler.setScreenOff(false);
        scheduler.setDeviceLocked(false);
        scheduler.setSessionLocked(false);
        scheduler.setPollingEnabledForScreen(true);
        scheduler.resume();
        assertEquals("Redundant environment calls must not launch duplicates", 1, refreshAction.getDispatchCount());
    }

    @Test
    public void testManualRefreshRejectsWhenPausedScreenOffDeviceLockedSessionLocked() {
        scheduler.resume();
        refreshAction.completeNextSuccess();
        assertEquals(1, refreshAction.getDispatchCount());

        // 1. Rejected when PAUSED
        scheduler.pause();
        boolean manualInPause = scheduler.triggerManualRefresh();
        assertFalse("Manual refresh rejected when PAUSED", manualInPause);
        assertEquals(1, refreshAction.getDispatchCount());

        // Resume & reset
        clock.advance(1000);
        scheduler.resume();
        refreshAction.completeNextSuccess();
        assertEquals(2, refreshAction.getDispatchCount());

        // 2. Rejected when screen is OFF
        scheduler.setScreenOff(true);
        boolean manualInScreenOff = scheduler.triggerManualRefresh();
        assertFalse("Manual refresh rejected when screen is OFF", manualInScreenOff);
        assertEquals(2, refreshAction.getDispatchCount());

        // Screen on & reset
        scheduler.setScreenOff(false);
        refreshAction.completeNextSuccess();
        assertEquals(3, refreshAction.getDispatchCount());

        // 3. Rejected when device is LOCKED
        scheduler.setDeviceLocked(true);
        boolean manualInDeviceLock = scheduler.triggerManualRefresh();
        assertFalse("Manual refresh rejected when device is LOCKED", manualInDeviceLock);
        assertEquals(3, refreshAction.getDispatchCount());

        // Unlock & reset
        scheduler.setDeviceLocked(false);
        refreshAction.completeNextSuccess();
        assertEquals(4, refreshAction.getDispatchCount());

        // 4. Rejected when session is LOCKED
        scheduler.setSessionLocked(true);
        boolean manualInSessionLock = scheduler.triggerManualRefresh();
        assertFalse("Manual refresh rejected when session is LOCKED", manualInSessionLock);
        assertEquals(4, refreshAction.getDispatchCount());
    }

    @Test
    public void testCallbackRejectionAuditsActualFetchCompletionOwnerBeforeDataBinding() {
        scheduler.resume();
        assertEquals(1, refreshAction.getDispatchCount());
        AutoRefreshScheduler.RefreshCallback cb = refreshAction.getLastCallback();
        assertNotNull(cb);
        assertTrue("Callback is current and accepted during normal active fetch", cb.canAcceptResult());

        // If screen turns off midflight:
        scheduler.setScreenOff(true);
        assertFalse("Callback rejected when screen turned off", cb.canAcceptResult());
        scheduler.setScreenOff(false);

        // If device locks midflight:
        scheduler.setDeviceLocked(true);
        assertFalse("Callback rejected when device locked", cb.canAcceptResult());
        scheduler.setDeviceLocked(false);

        // If session locks midflight:
        scheduler.setSessionLocked(true);
        assertFalse("Callback rejected when session locked", cb.canAcceptResult());
        scheduler.setSessionLocked(false);

        // If power action starts midflight:
        actionGuard.inProgress = true;
        assertFalse("Callback rejected when action guard is active", cb.canAcceptResult());
        actionGuard.inProgress = false;

        // If destroyed:
        scheduler.destroy();
        assertFalse("Callback rejected when scheduler destroyed", cb.canAcceptResult());
    }

    // =========================================================================
    // 11. Controller Owner & Scheduler Integration Behavioral Tests
    // =========================================================================

    @Test
    public void testOwnerCallbackRejectionRetiresFetchAndAllowsSubsequentPolling() {
        scheduler.resume();
        assertEquals(1, refreshAction.getDispatchCount());
        AutoRefreshScheduler.RefreshCallback oldCallback = refreshAction.getLastCallback();

        // Pause then resume
        scheduler.pause();
        scheduler.resume();
        assertFalse("Old callback rejected after pause/resume", oldCallback.canAcceptResult());

        // Actual owner retires callback with onError
        refreshAction.completeNextError("Stale");

        // Fresh fetch dispatched immediately on resume
        assertEquals("Fresh fetch dispatched upon retiring old callback", 2, refreshAction.getDispatchCount());
        assertEquals(AutoRefreshScheduler.ExecutionState.FETCHING, scheduler.getExecutionState());

        // Fresh fetch completes successfully
        refreshAction.completeNextSuccess();
        assertEquals(AutoRefreshScheduler.ExecutionState.SCHEDULED, scheduler.getExecutionState());
        assertTrue(scheduler.hasPendingTimer());
    }

    @Test
    public void testSettingsRoundtripPreservesErrorBackoffCadence() {
        scheduler.resume();
        assertEquals(1, refreshAction.getDispatchCount());

        // Initial fetch fails -> enters BACKOFF with 30s delay
        refreshAction.completeNextError("Network error");
        assertEquals(AutoRefreshScheduler.ExecutionState.BACKOFF, scheduler.getExecutionState());
        assertEquals(1, scheduler.getConsecutiveErrors());
        long nextExecution = scheduler.getNextScheduledExecutionTime();
        assertTrue("Backoff scheduled for 30s", nextExecution - clock.elapsedRealtime() <= 30_000L);

        // User switches to Settings tab
        scheduler.setPollingEnabledForScreen(false);
        assertFalse(scheduler.hasPendingTimer());
        assertEquals(AutoRefreshScheduler.ExecutionState.BACKOFF, scheduler.getExecutionState());

        // Advance 5 seconds while on Settings tab
        clock.advance(5_000);

        // User returns to Overview tab: must retain BACKOFF and honor remaining 25s backoff
        scheduler.setPollingEnabledForScreen(true);
        assertEquals("Tab change must not immediately dispatch and bypass backoff", 1, refreshAction.getDispatchCount());
        assertEquals(AutoRefreshScheduler.ExecutionState.BACKOFF, scheduler.getExecutionState());
        assertTrue("Timer rescheduled for remaining backoff", scheduler.hasPendingTimer());

        long remaining = scheduler.getNextScheduledExecutionTime() - clock.elapsedRealtime();
        assertTrue("Remaining backoff is ~25s", remaining >= 24_000L && remaining <= 26_000L);

        // Advance remaining backoff: fires fresh fetch
        taskScheduler.advanceTime(remaining);
        assertEquals("Timer fires after remaining backoff", 2, refreshAction.getDispatchCount());
    }

    @Test
    public void testOffAndOnAfterErrorResumesPolling() {
        scheduler.resume();
        refreshAction.completeNextError("Fail 1");
        assertEquals(AutoRefreshScheduler.ExecutionState.BACKOFF, scheduler.getExecutionState());

        // Change to Off
        preferenceStore.setRefreshIntervalSeconds(RefreshPreferenceStore.INTERVAL_OFF);
        assertFalse(scheduler.hasPendingTimer());

        // Manual refresh while Off
        boolean manualStarted = scheduler.triggerManualRefresh();
        assertTrue(manualStarted);
        assertEquals(2, refreshAction.getDispatchCount());

        // Manual refresh fails
        refreshAction.completeNextError("Fail 2");
        assertEquals(AutoRefreshScheduler.ExecutionState.BACKOFF, scheduler.getExecutionState());
        assertFalse(scheduler.hasPendingTimer());

        // Turn back on to 10s: scheduler must recover and schedule backoff timer
        preferenceStore.setRefreshIntervalSeconds(RefreshPreferenceStore.INTERVAL_10S);
        assertTrue("Timer must be scheduled when re-enabled from BACKOFF", scheduler.hasPendingTimer());

        // Advance time: timer fires and dispatches
        taskScheduler.advanceTime(60_000);
        assertTrue("Polling resumes after re-enabling from error", refreshAction.getDispatchCount() >= 3);
    }

    @Test
    public void testActionRejectedResultCannotAdvanceSuccessfulDataTimestamp() {
        scheduler.resume();
        assertEquals(1, refreshAction.getDispatchCount());
        AutoRefreshScheduler.RefreshCallback cb = refreshAction.getLastCallback();

        // Action begins while fetch is in-flight
        actionGuard.inProgress = true;
        assertFalse(cb.canAcceptResult());

        // If onSuccess is called directly, scheduler must not record successful update
        cb.onSuccess();
        assertEquals("LastSuccessfulUpdate must remain 0 when action rejects binding", 0L, scheduler.getLastSuccessfulUpdate());
        assertEquals(AutoRefreshScheduler.ExecutionState.DEFERRED, scheduler.getExecutionState());
    }

    @Test
    public void testExplicitFetchCompleteCannotFreeUnrelatedActiveLoadingOwner() {
        scheduler.resume();
        assertEquals(1, refreshAction.getDispatchCount());
        assertEquals(AutoRefreshScheduler.ExecutionState.FETCHING, scheduler.getExecutionState());

        // Explicit completion called while fetch is actively in flight
        scheduler.onExplicitFetchComplete(true);

        // Active fetch owner must NOT be freed: manual refresh must still be coalesced/rejected
        boolean manualAccepted = scheduler.triggerManualRefresh();
        assertFalse("Manual refresh must be rejected while active fetch is in flight", manualAccepted);
        assertEquals(1, refreshAction.getDispatchCount());
        assertEquals(AutoRefreshScheduler.ExecutionState.FETCHING, scheduler.getExecutionState());
    }

    @Test
    public void testRepeatedResumeAfterErrorPreservesAutomaticBackoff() {
        scheduler.resume();
        refreshAction.completeNextError("Error");
        assertEquals(AutoRefreshScheduler.ExecutionState.BACKOFF, scheduler.getExecutionState());
        long scheduledAt = scheduler.getNextScheduledExecutionTime();

        // Advance 600ms
        clock.advance(600);

        // Duplicate resume call in same resumed lifecycle
        scheduler.resume();
        assertEquals("Duplicate resume must not dispatch new fetch during backoff", 1, refreshAction.getDispatchCount());
        assertEquals(scheduledAt, scheduler.getNextScheduledExecutionTime());
        assertEquals(AutoRefreshScheduler.ExecutionState.BACKOFF, scheduler.getExecutionState());
    }

    @Test
    public void testWatchdogTimeoutRecoversStrandedFetchingState() {
        scheduler.resume();
        assertEquals(1, refreshAction.getDispatchCount());
        assertEquals(AutoRefreshScheduler.ExecutionState.FETCHING, scheduler.getExecutionState());

        // Neither onSuccess nor onError is ever called on the callback (e.g. dropped/abandoned)
        // Advance clock past FETCH_TIMEOUT_MS (60s)
        taskScheduler.advanceTime(60_000);

        // Watchdog fires: scheduler moves to BACKOFF and schedules retry
        assertEquals(AutoRefreshScheduler.ExecutionState.BACKOFF, scheduler.getExecutionState());
        assertEquals(1, scheduler.getConsecutiveErrors());
        assertTrue(scheduler.hasPendingTimer());

        // Advance backoff delay (30s) without callback: must remain 1 fetch while transport outstanding
        taskScheduler.advanceTime(30_000);
        assertEquals("Must remain exactly 1 fetch while original transport outstanding", 1, refreshAction.getDispatchCount());

        // Original callback finally arrives (terminal): releases ownership and dispatches retry safely
        AutoRefreshScheduler.RefreshCallback cb = refreshAction.getLastCallback();
        cb.onError(new Exception("Stale"), "Stale");
        assertEquals("Retry dispatched once original callback arrives", 2, refreshAction.getDispatchCount());
    }

    @Test
    public void testWatchdogTimeoutInvalidatesOldCallbackAndLateOldCallbackCannotEvictRetryOwner() {
        scheduler.resume();
        assertEquals(1, refreshAction.getDispatchCount());
        AutoRefreshScheduler.RefreshCallback cb1 = refreshAction.getLastCallback();
        assertTrue(cb1.canAcceptResult());

        // Watchdog fires after 60s
        taskScheduler.advanceTime(60_000);
        assertFalse("Timed-out callback must not accept result", cb1.canAcceptResult());
        assertEquals(AutoRefreshScheduler.ExecutionState.BACKOFF, scheduler.getExecutionState());
        assertTrue(scheduler.hasPendingTimer());

        // Advance 30s backoff without callback: must remain exactly 1 fetch while transport outstanding
        taskScheduler.advanceTime(30_000);
        assertEquals("Must remain exactly 1 fetch while original transport outstanding", 1, refreshAction.getDispatchCount());

        // Old timed-out fetch 1 completes late with error: releases ownership and dispatches retry safely
        cb1.onError(new Exception("Stale"), "Stale");
        assertEquals("Retry dispatched upon original callback retirement", 2, refreshAction.getDispatchCount());
        AutoRefreshScheduler.RefreshCallback cb2 = refreshAction.getLastCallback();
        assertTrue("Active retry callback must accept result", cb2.canAcceptResult());
        assertEquals(AutoRefreshScheduler.ExecutionState.FETCHING, scheduler.getExecutionState());

        // Duplicate/late error on cb1 must NOT evict cb2 or dispatch 3rd fetch!
        cb1.onError(new Exception("Stale"), "Stale");
        assertEquals("Stale callback retirement must not dispatch 3rd fetch", 2, refreshAction.getDispatchCount());
        assertTrue("Retry owner must remain active and accepted", cb2.canAcceptResult());
        assertEquals(AutoRefreshScheduler.ExecutionState.FETCHING, scheduler.getExecutionState());

        // Now retry completes successfully
        cb2.onSuccess();
        assertEquals(AutoRefreshScheduler.ExecutionState.SCHEDULED, scheduler.getExecutionState());
        assertTrue(scheduler.getLastSuccessfulUpdate() > 0);
    }

    @Test
    public void testSettingsMidflightRoundtripRestoresWatchdog() {
        scheduler.resume();
        assertEquals(1, refreshAction.getDispatchCount());
        assertEquals(AutoRefreshScheduler.ExecutionState.FETCHING, scheduler.getExecutionState());

        // Switch to Settings tab while fetch is in-flight
        scheduler.setPollingEnabledForScreen(false);

        // Advance 5 seconds on Settings tab
        clock.advance(5_000);

        // Switch back to Overview tab
        scheduler.setPollingEnabledForScreen(true);
        assertEquals(AutoRefreshScheduler.ExecutionState.FETCHING, scheduler.getExecutionState());

        // Advance remaining watchdog timeout (55s)
        taskScheduler.advanceTime(55_000);
        assertEquals("Watchdog fires after remaining timeout", AutoRefreshScheduler.ExecutionState.BACKOFF, scheduler.getExecutionState());
        assertTrue("Backoff timer scheduled", scheduler.hasPendingTimer());

        // Advance 30s backoff without callback: must remain 1 fetch while transport outstanding
        taskScheduler.advanceTime(30_000);
        assertEquals("Must not dispatch second fetch while original outstanding", 1, refreshAction.getDispatchCount());

        // Original callback completes late
        AutoRefreshScheduler.RefreshCallback cb1 = refreshAction.getLastCallback();
        cb1.onError(new Exception("Stale"), "Stale");
        assertEquals("Retry dispatched after original completion", 2, refreshAction.getDispatchCount());
    }

    @Test
    public void testPowerCompletionFreshReadIsQueuedAndExecutedWithoutOverlap() {
        scheduler.resume();
        assertEquals(1, refreshAction.getDispatchCount());
        AutoRefreshScheduler.RefreshCallback cb1 = refreshAction.getLastCallback();
        assertTrue(cb1.canAcceptResult());

        // Action starts and finishes
        actionGuard.inProgress = true;
        assertFalse(cb1.canAcceptResult());
        actionGuard.inProgress = false;

        // Post-operation refresh requested while fetch 1 is still in-flight
        scheduler.requestPostOperationRefresh();
        assertFalse("Pre-action callback must not accept result after post-op request", cb1.canAcceptResult());
        assertTrue("Post-op refresh must be queued", scheduler.hasQueuedRefresh());
        assertEquals("Must not overlap with in-flight fetch", 1, refreshAction.getDispatchCount());

        // Fetch 1 completes
        cb1.onError(new Exception("Stale"), "Stale");

        // Queued refresh immediately dispatches without waiting for cadence
        assertEquals("Queued fresh read immediately dispatched upon prior completion", 2, refreshAction.getDispatchCount());
        assertEquals(AutoRefreshScheduler.ExecutionState.FETCHING, scheduler.getExecutionState());
    }

    @Test
    public void testPowerCompletionFreshReadExecutesEvenWhenRefreshOff() {
        preferenceStore.setRefreshIntervalSeconds(RefreshPreferenceStore.INTERVAL_OFF);
        scheduler.resume();
        assertEquals("Initial prompt read on resume even when Off", 1, refreshAction.getDispatchCount());
        AutoRefreshScheduler.RefreshCallback cb1 = refreshAction.getLastCallback();

        // Action starts and finishes
        actionGuard.inProgress = true;
        actionGuard.inProgress = false;

        // Post-operation refresh requested while fetch 1 is still in flight
        scheduler.requestPostOperationRefresh();
        assertTrue("Post-op refresh queued even when Off", scheduler.hasQueuedRefresh());
        assertEquals("Must not overlap with in-flight fetch", 1, refreshAction.getDispatchCount());

        // Fetch 1 completes
        cb1.onError(new Exception("Stale"), "Stale");

        // Dispatches queued fresh read even though auto-refresh preference is OFF
        assertEquals("Queued post-op read dispatches even when Off", 2, refreshAction.getDispatchCount());
        assertEquals(AutoRefreshScheduler.ExecutionState.FETCHING, scheduler.getExecutionState());
    }

    @Test
    public void testManualRefreshDuringFetchingCoalescesSafelyWithoutDuplicateDispatch() {
        scheduler.resume();
        assertEquals(1, refreshAction.getDispatchCount());
        AutoRefreshScheduler.RefreshCallback cb1 = refreshAction.getLastCallback();
        assertTrue(cb1.canAcceptResult());

        // User triggers manual refresh while fetch is in-flight: safely coalesced
        boolean accepted = scheduler.triggerManualRefresh();
        assertFalse("Manual refresh during FETCHING returns false (coalesced)", accepted);
        assertEquals("Must not dispatch duplicate request while in flight", 1, refreshAction.getDispatchCount());

        // In-flight fetch finishes successfully
        cb1.onSuccess();
        assertEquals(AutoRefreshScheduler.ExecutionState.SCHEDULED, scheduler.getExecutionState());
        assertTrue(scheduler.hasPendingTimer());
    }

    @Test
    public void testSettingsMidflightWithoutCompletionRecoversViaWatchdogAndAllowsManualRetry() {
        scheduler.resume();
        assertEquals(1, refreshAction.getDispatchCount());

        // Switch to Settings tab and return
        scheduler.setPollingEnabledForScreen(false);
        scheduler.setPollingEnabledForScreen(true);

        // Advance 180 seconds with no network completion: must remain exactly 1 call!
        AutoRefreshScheduler.RefreshCallback cb1 = refreshAction.getLastCallback();
        taskScheduler.advanceTime(180_000);
        assertEquals(AutoRefreshScheduler.ExecutionState.BACKOFF, scheduler.getExecutionState());
        assertTrue(scheduler.hasPendingTimer());
        assertEquals("Calls must remain exactly 1 after 180s without completion", 1, refreshAction.getDispatchCount());

        // While transport request is outstanding, manual refresh coalesces safely without duplicate request
        assertFalse("Manual refresh coalesces while original outstanding", scheduler.triggerManualRefresh());
        assertEquals(1, refreshAction.getDispatchCount());

        // When original callback completes, retry dispatches safely
        cb1.onError(new Exception("Stale"), "Stale");
        assertEquals("Retry dispatched once original callback arrives", 2, refreshAction.getDispatchCount());
    }

    @Test
    public void testWatchdogTimeoutAdvanced90sWithoutCallbackMustRemainExactlyOneCall() {
        scheduler.resume();
        assertEquals(1, refreshAction.getDispatchCount());
        AutoRefreshScheduler.RefreshCallback cb1 = refreshAction.getLastCallback();
        assertTrue(cb1.canAcceptResult());

        // Advance 60s to trigger watchdog
        taskScheduler.advanceTime(60_000);
        assertEquals(AutoRefreshScheduler.ExecutionState.BACKOFF, scheduler.getExecutionState());
        assertFalse("Timed-out callback must not accept result", cb1.canAcceptResult());
        assertTrue("Backoff timer pending", scheduler.hasPendingTimer());

        // Advance another 30s (total 90s from start) without callback: must remain exactly 1 call!
        taskScheduler.advanceTime(30_000);
        assertEquals("At 90s without callback, dispatch count must remain exactly 1", 1, refreshAction.getDispatchCount());
        assertEquals(AutoRefreshScheduler.ExecutionState.BACKOFF, scheduler.getExecutionState());

        // Late original callback completes: releases its ownership and dispatches retry safely
        cb1.onError(new Exception("Stale"), "Stale");
        assertEquals("Dispatches retry once original callback releases ownership", 2, refreshAction.getDispatchCount());
        AutoRefreshScheduler.RefreshCallback cb2 = refreshAction.getLastCallback();
        assertTrue("Retry callback accepts result", cb2.canAcceptResult());
        assertEquals(AutoRefreshScheduler.ExecutionState.FETCHING, scheduler.getExecutionState());
    }

    @Test
    public void testGuardAppliesInBackoffPausedSettingsManualResumeStates() {
        scheduler.resume();
        assertEquals(1, refreshAction.getDispatchCount());
        AutoRefreshScheduler.RefreshCallback cb1 = refreshAction.getLastCallback();

        // 1. Advance to watchdog timeout (60s) -> state becomes BACKOFF
        taskScheduler.advanceTime(60_000);
        assertEquals(AutoRefreshScheduler.ExecutionState.BACKOFF, scheduler.getExecutionState());
        assertEquals(1, scheduler.getActiveTransportFetchId());

        // 2. Manual refresh in BACKOFF while transport active is rejected
        assertFalse("Manual refresh blocked while transport active", scheduler.triggerManualRefresh());
        assertEquals(1, refreshAction.getDispatchCount());

        // 3. Pause while transport active
        scheduler.pause();
        assertEquals(AutoRefreshScheduler.LifecycleState.PAUSED, scheduler.getLifecycleState());
        assertEquals("Transport fetch ID preserved during pause", 1, scheduler.getActiveTransportFetchId());

        // 4. Resume while transport active
        scheduler.resume();
        assertEquals(AutoRefreshScheduler.LifecycleState.RESUMED, scheduler.getLifecycleState());
        assertEquals("No new dispatch on resume while transport active", 1, refreshAction.getDispatchCount());

        // 5. Settings tab cycle while transport active
        scheduler.setPollingEnabledForScreen(false);
        scheduler.setPollingEnabledForScreen(true);
        assertEquals("No new dispatch on settings cycle while transport active", 1, refreshAction.getDispatchCount());

        // Terminal callback arrives: releases transport ownership 1 and dispatches queued fresh read (fetch 2)
        cb1.onError(new Exception("Stale"), "Stale");
        assertEquals("Dispatches fresh read after transport released", 2, refreshAction.getDispatchCount());
        assertEquals("Transport ownership now belongs to fetch 2", 2, scheduler.getActiveTransportFetchId());
        AutoRefreshScheduler.RefreshCallback cb2 = refreshAction.getLastCallback();
        assertTrue("Fetch 2 accepts result", cb2.canAcceptResult());
        cb2.onSuccess();
        assertEquals("Transport ownership released after fetch 2 completes", 0, scheduler.getActiveTransportFetchId());
    }

    @Test
    public void testOffAndResumeWhileOldFetchIsOutstandingGuaranteesPromisedFreshResumeRead() {
        preferenceStore.setRefreshIntervalSeconds(RefreshPreferenceStore.INTERVAL_OFF);
        scheduler.resume();
        assertEquals("Prompt read on initial resume even when Off", 1, refreshAction.getDispatchCount());
        AutoRefreshScheduler.RefreshCallback cb1 = refreshAction.getLastCallback();
        assertTrue(cb1.canAcceptResult());

        // Pause while fetch 1 is in-flight
        scheduler.pause();
        assertFalse(cb1.canAcceptResult());

        // Resume while fetch 1 is still in flight (cadence is Off)
        scheduler.resume();
        assertTrue("Resume while in-flight fetch is outstanding queues fresh read", scheduler.hasQueuedRefresh());
        assertEquals("Must not launch overlapping fetch while old fetch is in-flight", 1, refreshAction.getDispatchCount());

        // Old fetch 1 completes late (rejected as stale)
        cb1.onError(new Exception("Stale"), "Stale");

        // Queued fresh read immediately dispatches without waiting for cadence, even though Off!
        assertEquals("Queued fresh read dispatched immediately upon retirement, even when Off", 2, refreshAction.getDispatchCount());
        assertEquals(AutoRefreshScheduler.ExecutionState.FETCHING, scheduler.getExecutionState());
    }

    @Test
    public void testOffResumeDeferredByActionGuardDispatchesPromisedReadOnGuardRelease() {
        // Reproduces exact frozen AdditionalProbe scenario
        scheduler.resume();
        assertEquals(1, refreshAction.getDispatchCount());
        refreshAction.getLastCallback().onSuccess();

        // Cadence set to OFF
        preferenceStore.setRefreshIntervalSeconds(RefreshPreferenceStore.INTERVAL_OFF);
        scheduler.pause();
        assertEquals(AutoRefreshScheduler.LifecycleState.PAUSED, scheduler.getLifecycleState());

        // Action guard becomes active (e.g. confirmation dialog open)
        actionGuard.inProgress = true;

        // Resume: screen is active, but action guard defers dispatch
        scheduler.resume();
        assertEquals(AutoRefreshScheduler.LifecycleState.RESUMED, scheduler.getLifecycleState());
        assertEquals(AutoRefreshScheduler.ExecutionState.DEFERRED, scheduler.getExecutionState());
        assertEquals("No second dispatch while action guard is active", 1, refreshAction.getDispatchCount());
        assertTrue("Deferral check timer must be pending even when Off", scheduler.hasPendingTimer());

        // Advance 180s while guard is active: timer repeatedly re-checks, no dispatch
        // Release action guard (dialog dismissed)
        actionGuard.inProgress = false;

        // Advance time for deferral timer check (ACTION_DEFERRAL_CHECK_MS = 2000ms)
        taskScheduler.advanceTime(2000);

        // Guard released: promised fresh read is safely dispatched!
        assertEquals("Promised resume fresh read dispatched upon guard release even when Off", 2, refreshAction.getDispatchCount());
        AutoRefreshScheduler.RefreshCallback cb2 = refreshAction.getLastCallback();
        assertTrue("Dispatched read can accept result", cb2.canAcceptResult());

        // Complete fetch 2 successfully
        cb2.onSuccess();
        assertEquals(AutoRefreshScheduler.ExecutionState.IDLE, scheduler.getExecutionState());

        // Advance 180s: must NOT poll in background when Off
        taskScheduler.advanceTime(180_000);
        assertEquals("Must remain at exactly 2 calls (no background polling when Off)", 2, refreshAction.getDispatchCount());
        assertFalse("No timer scheduled when Off", scheduler.hasPendingTimer());
    }

    @Test
    public void testRequestPostOperationRefreshHittingActiveGuardPreservesExplicitReadEvenWhenOff() {
        preferenceStore.setRefreshIntervalSeconds(RefreshPreferenceStore.INTERVAL_OFF);
        scheduler.resume();
        assertEquals(1, refreshAction.getDispatchCount());
        refreshAction.getLastCallback().onSuccess();
        assertEquals(AutoRefreshScheduler.ExecutionState.IDLE, scheduler.getExecutionState());

        // Another action becomes active
        actionGuard.inProgress = true;

        // Explicit post-operation refresh requested while guard is active
        scheduler.requestPostOperationRefresh();
        assertEquals(AutoRefreshScheduler.ExecutionState.DEFERRED, scheduler.getExecutionState());
        assertEquals(1, refreshAction.getDispatchCount());
        assertTrue("Deferral timer must be scheduled even when Off", scheduler.hasPendingTimer());

        // Advance 4s while guard is active: still 1 call
        taskScheduler.advanceTime(4000);
        assertEquals(1, refreshAction.getDispatchCount());

        // Guard released
        actionGuard.inProgress = false;
        taskScheduler.advanceTime(2000);

        // Explicit post-operation refresh is dispatched!
        assertEquals("Explicit post-operation refresh dispatched after guard cleared", 2, refreshAction.getDispatchCount());
        refreshAction.getLastCallback().onSuccess();
        assertEquals(AutoRefreshScheduler.ExecutionState.IDLE, scheduler.getExecutionState());

        // Verify no background polling
        taskScheduler.advanceTime(60_000);
        assertEquals(2, refreshAction.getDispatchCount());
        assertFalse(scheduler.hasPendingTimer());
    }

    @Test
    public void testRequestPostOperationRefreshWhileInFlightHittingActiveGuardPreservesExplicitReadEvenWhenOff() {
        preferenceStore.setRefreshIntervalSeconds(RefreshPreferenceStore.INTERVAL_OFF);
        scheduler.resume();
        assertEquals(1, refreshAction.getDispatchCount());
        AutoRefreshScheduler.RefreshCallback cb1 = refreshAction.getLastCallback();

        // While fetch 1 is in-flight, post-operation refresh is requested
        scheduler.requestPostOperationRefresh();
        assertTrue(scheduler.hasQueuedRefresh());

        // Another action guard becomes active before callback arrives
        actionGuard.inProgress = true;

        // Callback 1 completes (rejected as stale because generation token advanced)
        cb1.onError(new Exception("Stale"), "Stale");

        // Scheduler must defer and preserve queued explicit read
        assertEquals(AutoRefreshScheduler.ExecutionState.DEFERRED, scheduler.getExecutionState());
        assertTrue(scheduler.hasQueuedRefresh());
        assertTrue(scheduler.hasPendingTimer());
        assertEquals(1, refreshAction.getDispatchCount());

        // Release guard
        actionGuard.inProgress = false;
        taskScheduler.advanceTime(2000);

        // Dispatches explicit fresh read
        assertEquals(2, refreshAction.getDispatchCount());
        AutoRefreshScheduler.RefreshCallback cb2 = refreshAction.getLastCallback();
        assertTrue(cb2.canAcceptResult());
        cb2.onSuccess();
        assertEquals(AutoRefreshScheduler.ExecutionState.IDLE, scheduler.getExecutionState());

        // Verify no background polling when Off
        taskScheduler.advanceTime(60_000);
        assertEquals(2, refreshAction.getDispatchCount());
        assertFalse(scheduler.hasPendingTimer());
    }

    @Test
    public void testPauseHoldsDuringDeferredStateAndResumesWithoutOverlap() {
        preferenceStore.setRefreshIntervalSeconds(RefreshPreferenceStore.INTERVAL_OFF);
        scheduler.resume();
        refreshAction.getLastCallback().onSuccess();

        // Action active, resume defers
        scheduler.pause();
        actionGuard.inProgress = true;
        scheduler.resume();
        assertEquals(AutoRefreshScheduler.ExecutionState.DEFERRED, scheduler.getExecutionState());
        assertTrue(scheduler.hasPendingTimer());

        // Pause while deferred: pending timer cancelled, lifecycle pause holds
        scheduler.pause();
        assertEquals(AutoRefreshScheduler.LifecycleState.PAUSED, scheduler.getLifecycleState());
        assertFalse("Pending timer must be cancelled during pause", scheduler.hasPendingTimer());

        // Advance time while paused: no activity
        taskScheduler.advanceTime(10_000);
        assertEquals(1, refreshAction.getDispatchCount());

        // Guard released while paused
        actionGuard.inProgress = false;

        // Resume: prompt read dispatched cleanly without overlapping timers
        scheduler.resume();
        assertEquals(2, refreshAction.getDispatchCount());
        assertEquals(AutoRefreshScheduler.ExecutionState.FETCHING, scheduler.getExecutionState());
        refreshAction.getLastCallback().onSuccess();
        assertEquals(AutoRefreshScheduler.ExecutionState.IDLE, scheduler.getExecutionState());
    }
}
