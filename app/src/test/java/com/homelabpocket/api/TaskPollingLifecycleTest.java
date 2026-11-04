// SPDX-License-Identifier: GPL-3.0-or-later
package com.homelabpocket.api;

import com.homelabpocket.model.AuthCredentials;
import com.homelabpocket.model.TaskResult;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class TaskPollingLifecycleTest {

    private static final String UPID = "UPID:pve1:00012345:00ABCDEF:67280000:qmstart:100:root@pam:";
    private static final AuthCredentials MOCK_AUTH = AuthCredentials.apiToken("PVEAPIToken=test@pam!tok=uuid");

    static class VirtualClock implements TaskPoller.Clock {
        private long currentMs;

        VirtualClock(long startMs) {
            this.currentMs = startMs;
        }

        @Override
        public long monotonicTimeMs() {
            return currentMs;
        }

        @Override
        public void sleep(long millis) {
            currentMs += millis;
        }
    }

    @Before
    public void setUp() {
        ActionGuard.getInstance().clearAll();
    }

    @Test
    public void testTaskResultStates() {
        TaskResult running = new TaskResult(UPID, TaskResult.TaskState.RUNNING, null,
                "starting VM 100", 1700000000, 0, "pve1");

        assertFalse(running.isFinished());
        assertFalse(running.isSuccess());
        assertTrue(running.getSummary().contains("Running"));

        TaskResult success = new TaskResult(UPID, TaskResult.TaskState.OK, "OK",
                null, 1700000000, 1700000010, "pve1");

        assertTrue(success.isFinished());
        assertTrue(success.isSuccess());
        assertEquals("Completed successfully", success.getSummary());

        TaskResult failure = new TaskResult(UPID, TaskResult.TaskState.ERROR, "exit code 1: qemu error",
                null, 1700000000, 1700000005, "pve1");

        assertTrue(failure.isFinished());
        assertFalse(failure.isSuccess());
        assertTrue(failure.getSummary().contains("Failed: exit code 1"));

        TaskResult timeout = new TaskResult(UPID, TaskResult.TaskState.TIMEOUT, null,
                null, 1700000000, 0, "pve1");

        assertTrue(timeout.isFinished());
        assertFalse(timeout.isSuccess());
        assertTrue(timeout.getSummary().contains("Timed out"));
    }

    @Test
    public void testScriptedRunningToRunningToOk() {
        VirtualClock clock = new VirtualClock(1000L);
        TaskPoller poller = new TaskPoller(clock, 1000L, 30_000L);

        String actionKey = ActionGuard.buildGuestKey("srv1", "pve1", "qemu", 100, "start");
        assertTrue("Acquiring guard initially should succeed", ActionGuard.getInstance().tryAcquire(actionKey));

        AtomicInteger callCount = new AtomicInteger(0);
        List<TaskResult> progressList = new ArrayList<>();

        TaskPoller.TaskStatusFetcher fetcher = (node, upid, auth) -> {
            int count = callCount.incrementAndGet();
            // Verify that while polling is running, the ActionGuard remains held!
            assertTrue("ActionGuard must remain held during polling",
                    ActionGuard.getInstance().isRunning(actionKey));
            assertFalse("Second acquire attempt must be blocked while polling",
                    ActionGuard.getInstance().tryAcquire(actionKey));

            if (count == 1) {
                return new TaskResult(upid, TaskResult.TaskState.RUNNING, null, "preparing disk", 1000, 0, node);
            } else if (count == 2) {
                return new TaskResult(upid, TaskResult.TaskState.RUNNING, null, "starting kvm", 1000, 0, node);
            } else {
                return new TaskResult(upid, TaskResult.TaskState.OK, "OK", null, 1000, 3000, node);
            }
        };

        TaskResult finalResult = poller.pollUntilFinished(
                actionKey, "pve1", UPID, MOCK_AUTH, fetcher,
                new TaskPoller.PollingCallback() {
                    @Override
                    public void onProgress(TaskResult progress) {
                        progressList.add(progress);
                    }
                    @Override
                    public boolean isCancelled() {
                        return false;
                    }
                }
        );

        assertNotNull(finalResult);
        assertEquals("Task state must be OK", TaskResult.TaskState.OK, finalResult.state);
        assertEquals("exitStatus must be OK", "OK", finalResult.exitStatus);
        assertEquals("Should have called fetcher 3 times", 3, callCount.get());
        assertEquals("Should have dispatched 2 progress events", 2, progressList.size());
        assertEquals("preparing disk", progressList.get(0).progressText);
        assertEquals("starting kvm", progressList.get(1).progressText);

        // Guard must be released after completion
        assertFalse("ActionGuard must be released after completion",
                ActionGuard.getInstance().isRunning(actionKey));
        assertTrue("Subsequent action must be allowed after release",
                ActionGuard.getInstance().tryAcquire(actionKey));
    }

    @Test
    public void testScriptedRunningToError() {
        VirtualClock clock = new VirtualClock(2000L);
        TaskPoller poller = new TaskPoller(clock, 1000L, 30_000L);

        String actionKey = ActionGuard.buildGuestKey("srv1", "pve1", "qemu", 101, "stop");
        assertTrue(ActionGuard.getInstance().tryAcquire(actionKey));

        AtomicInteger callCount = new AtomicInteger(0);

        TaskPoller.TaskStatusFetcher fetcher = (node, upid, auth) -> {
            int count = callCount.incrementAndGet();
            if (count == 1) {
                return new TaskResult(upid, TaskResult.TaskState.RUNNING, null, "sending ACPI shutdown", 2000, 0, node);
            } else {
                return new TaskResult(upid, TaskResult.TaskState.ERROR, "exit code 1: guest did not respond", null, 2000, 3000, node);
            }
        };

        TaskResult finalResult = poller.pollUntilFinished(actionKey, "pve1", UPID, MOCK_AUTH, fetcher, null);

        assertNotNull(finalResult);
        assertEquals(TaskResult.TaskState.ERROR, finalResult.state);
        assertEquals("exit code 1: guest did not respond", finalResult.exitStatus);
        assertTrue(finalResult.getSummary().contains("Failed: exit code 1"));

        // Guard must be released even on error
        assertFalse(ActionGuard.getInstance().isRunning(actionKey));
    }

    @Test
    public void testScriptedNeverCompletesDeadlineTimesOut() {
        VirtualClock clock = new VirtualClock(5000L);
        // Timeout set to 5000ms with 1000ms polling interval
        TaskPoller poller = new TaskPoller(clock, 1000L, 5000L);

        String actionKey = ActionGuard.buildGuestKey("srv1", "pve1", "qemu", 102, "reboot");
        assertTrue(ActionGuard.getInstance().tryAcquire(actionKey));

        AtomicInteger callCount = new AtomicInteger(0);
        TaskPoller.TaskStatusFetcher fetcher = (node, upid, auth) -> {
            callCount.incrementAndGet();
            // Task never finishes
            return new TaskResult(upid, TaskResult.TaskState.RUNNING, null, "reboot in progress", 5000, 0, node);
        };

        TaskResult finalResult = poller.pollUntilFinished(
                actionKey, "pve1", UPID, MOCK_AUTH, fetcher, null, 5000L, 1000L);

        assertNotNull(finalResult);
        assertEquals("State must be TIMEOUT when deadline exceeded", TaskResult.TaskState.TIMEOUT, finalResult.state);
        assertTrue("Summary must explain timeout", finalResult.getSummary().contains("Timed out"));
        assertTrue("Exit status must describe timeout duration", finalResult.exitStatus.contains("timed out"));

        // Action guard must be released on timeout
        assertFalse("ActionGuard must be released on timeout", ActionGuard.getInstance().isRunning(actionKey));
    }

    @Test
    public void testScriptedUnknownStateAndExceptionHandling() {
        VirtualClock clock = new VirtualClock(10000L);
        TaskPoller poller = new TaskPoller(clock, 1000L, 30_000L);

        // Case A: Fetcher returns UNKNOWN state
        String actionKeyA = ActionGuard.buildGuestKey("srv1", "pve1", "lxc", 200, "start");
        assertTrue(ActionGuard.getInstance().tryAcquire(actionKeyA));

        TaskPoller.TaskStatusFetcher unknownFetcher = (node, upid, auth) ->
                new TaskResult(upid, TaskResult.TaskState.UNKNOWN, "indeterminate server state", null, 10000, 0, node);

        TaskResult resultA = poller.pollUntilFinished(actionKeyA, "pve1", UPID, MOCK_AUTH, unknownFetcher, null);
        assertEquals(TaskResult.TaskState.UNKNOWN, resultA.state);
        assertFalse(ActionGuard.getInstance().isRunning(actionKeyA));

        // Case B: Fetcher throws ApiException
        String actionKeyB = ActionGuard.buildGuestKey("srv1", "pve1", "lxc", 201, "start");
        assertTrue(ActionGuard.getInstance().tryAcquire(actionKeyB));

        TaskPoller.TaskStatusFetcher throwingFetcher = (node, upid, auth) -> {
            throw new ApiException(500, "Internal Server Error: Node offline");
        };

        TaskResult resultB = poller.pollUntilFinished(actionKeyB, "pve1", UPID, MOCK_AUTH, throwingFetcher, null);
        assertEquals(TaskResult.TaskState.UNKNOWN, resultB.state);
        assertTrue(resultB.exitStatus.contains("Node offline"));
        assertFalse(ActionGuard.getInstance().isRunning(actionKeyB));
    }

    @Test
    public void testScriptedCancellation() {
        VirtualClock clock = new VirtualClock(15000L);
        TaskPoller poller = new TaskPoller(clock, 1000L, 30_000L);

        String actionKey = ActionGuard.buildGuestKey("srv1", "pve1", "qemu", 300, "shutdown");
        assertTrue(ActionGuard.getInstance().tryAcquire(actionKey));

        AtomicInteger callCount = new AtomicInteger(0);
        AtomicBoolean cancelled = new AtomicBoolean(false);

        TaskPoller.TaskStatusFetcher fetcher = (node, upid, auth) -> {
            int count = callCount.incrementAndGet();
            if (count == 2) {
                cancelled.set(true);
            }
            return new TaskResult(upid, TaskResult.TaskState.RUNNING, null, "waiting for guest shutdown", 15000, 0, node);
        };

        TaskResult finalResult = poller.pollUntilFinished(
                actionKey, "pve1", UPID, MOCK_AUTH, fetcher,
                new TaskPoller.PollingCallback() {
                    @Override
                    public void onProgress(TaskResult progress) {}
                    @Override
                    public boolean isCancelled() {
                        return cancelled.get();
                    }
                }
        );

        assertNotNull(finalResult);
        assertEquals(TaskResult.TaskState.UNKNOWN, finalResult.state);
        assertTrue(finalResult.exitStatus.contains("cancelled"));
        assertFalse("Action guard must be released upon cancellation", ActionGuard.getInstance().isRunning(actionKey));
    }

    @Test
    public void testDeterministicLateOkRejectedAsTimeout() {
        VirtualClock clock = new VirtualClock(0L);
        TaskPoller poller = new TaskPoller(clock, 1L, 10L);

        String actionKey = "late-ok-test";
        ActionGuard.getInstance().tryAcquire(actionKey);

        TaskResult result = poller.pollUntilFinished(
                actionKey, "node", "UPID:late-ok", null,
                (n, u, a) -> {
                    clock.currentMs = 11L; // Clock advances past deadline=10 during fetch
                    return new TaskResult(u, TaskResult.TaskState.OK, "OK", null, 0, 0, n);
                },
                null, 10L, 1L);

        assertNotNull(result);
        assertEquals("Late network success after deadline must be rejected as TIMEOUT",
                TaskResult.TaskState.TIMEOUT, result.state);
        assertFalse("Guard must be released after late timeout", ActionGuard.getInstance().isRunning(actionKey));
    }

    @Test
    public void testDeterministicLateErrorRejectedAsTimeout() {
        VirtualClock clock = new VirtualClock(0L);
        TaskPoller poller = new TaskPoller(clock, 1L, 10L);

        String actionKey = "late-err-test";
        ActionGuard.getInstance().tryAcquire(actionKey);

        TaskResult result = poller.pollUntilFinished(
                actionKey, "node", "UPID:late-err", null,
                (n, u, a) -> {
                    clock.currentMs = 15L; // Clock advances past deadline=10 during fetch
                    return new TaskResult(u, TaskResult.TaskState.ERROR, "exit code 1", null, 0, 0, n);
                },
                null, 10L, 1L);

        assertNotNull(result);
        assertEquals("Late network error after deadline must be rejected as TIMEOUT",
                TaskResult.TaskState.TIMEOUT, result.state);
        assertFalse("Guard must be released after late timeout", ActionGuard.getInstance().isRunning(actionKey));
    }

    @Test
    public void testDeterministicLateCancellationDuringFetchRejectedAsCancelled() {
        VirtualClock clock = new VirtualClock(0L);
        TaskPoller poller = new TaskPoller(clock, 1L, 10L);

        String actionKey = "late-cancel-test";
        ActionGuard.getInstance().tryAcquire(actionKey);

        AtomicBoolean cancelled = new AtomicBoolean(false);
        TaskResult result = poller.pollUntilFinished(
                actionKey, "node", "UPID:late-cancel", null,
                (n, u, a) -> {
                    cancelled.set(true); // Cancellation occurs during fetch
                    clock.currentMs = 5L; // Still within deadline
                    return new TaskResult(u, TaskResult.TaskState.OK, "OK", null, 0, 0, n);
                },
                new TaskPoller.PollingCallback() {
                    @Override
                    public void onProgress(TaskResult progress) {}
                    @Override
                    public boolean isCancelled() {
                        return cancelled.get();
                    }
                },
                10L, 1L);

        assertNotNull(result);
        assertEquals("Post-fetch cancellation must override network success with UNKNOWN (cancelled)",
                TaskResult.TaskState.UNKNOWN, result.state);
        assertTrue("Message must indicate cancellation", result.exitStatus.contains("cancelled"));
        assertFalse("Guard must be released after cancellation", ActionGuard.getInstance().isRunning(actionKey));
    }

    @Test
    public void testRemainingBudgetPassedToFetcher() {
        VirtualClock clock = new VirtualClock(0L);
        TaskPoller poller = new TaskPoller(clock, 500L, 5000L);

        String actionKey = "budget-test";
        ActionGuard.getInstance().tryAcquire(actionKey);

        List<Long> budgetsReceived = new ArrayList<>();
        TaskResult result = poller.pollUntilFinished(
                actionKey, "node", "UPID:budget-test", null,
                (n, u, a, remainingBudgetMs) -> {
                    budgetsReceived.add(remainingBudgetMs);
                    clock.currentMs += 1000L;
                    if (budgetsReceived.size() >= 3) {
                        return new TaskResult(u, TaskResult.TaskState.OK, "OK", null, 0, 0, n);
                    }
                    return new TaskResult(u, TaskResult.TaskState.RUNNING, null, "working", 0, 0, n);
                },
                null, 5000L, 500L);

        assertEquals(TaskResult.TaskState.OK, result.state);
        assertEquals(3, budgetsReceived.size());
        assertTrue("Initial budget should be 5000ms", budgetsReceived.get(0) == 5000L);
        assertTrue("Second budget should decrease monotonically", budgetsReceived.get(1) < budgetsReceived.get(0));
        assertTrue("Third budget should decrease monotonically", budgetsReceived.get(2) < budgetsReceived.get(1));
        assertFalse("Guard must be released", ActionGuard.getInstance().isRunning(actionKey));
    }
}
