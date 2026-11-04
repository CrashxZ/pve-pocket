// SPDX-License-Identifier: GPL-3.0-or-later
// Homelab Pocket — Behavioral test suite for production ActionGuard
package com.homelabpocket.api;

import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Directly exercises the actual production ActionGuard used by GuestsView,
 * VmDetailActivity, and MainActivity.
 */
public class DoubleSubmitGuardTest {

    private ActionGuard guard;

    @Before
    public void setUp() {
        guard = new ActionGuard();
    }

    @Test
    public void testFirstActionAcquiresLockAndDuplicateIsRejected() {
        String key = ActionGuard.buildGuestKey("srv-1", "pve-node1", "qemu", 100, "start");

        // First action starts successfully
        boolean firstSubmission = guard.tryAcquire(key);
        assertTrue("First action must be accepted", firstSubmission);
        assertTrue("Guard must report action as running", guard.isRunning(key));

        // Rapid second click on same button/target is rejected
        boolean secondSubmission = guard.tryAcquire(key);
        assertFalse("Second action on identical target must be blocked", secondSubmission);

        // Different action or different target is allowed independently
        String otherGuestKey = ActionGuard.buildGuestKey("srv-1", "pve-node1", "qemu", 101, "start");
        assertTrue("Independent guest target must be accepted", guard.tryAcquire(otherGuestKey));

        String otherActionKey = ActionGuard.buildGuestKey("srv-1", "pve-node1", "qemu", 100, "reboot");
        assertTrue("Different action on same guest target must be accepted", guard.tryAcquire(otherActionKey));

        // Once first task completes, lock is released
        guard.release(key);
        assertFalse("Guard must no longer report action as running", guard.isRunning(key));

        // Target can now accept a new action
        boolean subsequentAction = guard.tryAcquire(key);
        assertTrue("Action after completion must be accepted", subsequentAction);
    }

    @Test
    public void testGuestConvenienceMethods() {
        String serverId = "srv-cluster";
        String node = "pve-alpha";
        String type = "LXC";
        int vmid = 205;
        String action = "shutdown";

        assertTrue(guard.tryAcquireGuestAction(serverId, node, type, vmid, action));
        assertTrue(guard.isGuestActionRunning(serverId, node, type, vmid, action));
        assertFalse(guard.tryAcquireGuestAction(serverId, node, type, vmid, action));

        guard.releaseGuestAction(serverId, node, type, vmid, action);
        assertFalse(guard.isGuestActionRunning(serverId, node, type, vmid, action));
        assertTrue(guard.tryAcquireGuestAction(serverId, node, type, vmid, action));
    }

    @Test
    public void testCanonicalKeyFormattingAndNullSafety() {
        assertFalse(guard.tryAcquire(null));
        assertFalse(guard.tryAcquire(""));
        assertFalse(guard.tryAcquire("   "));
        assertFalse(guard.isRunning(null));

        String key = ActionGuard.buildGuestKey(null, null, null, 100, null);
        assertNotNull(key);
        assertTrue(key.contains("100"));

        // Case-insensitivity check in canonical key
        String keyUpper = ActionGuard.buildGuestKey("SRV", "NODE", "QEMU", 100, "START");
        String keyLower = ActionGuard.buildGuestKey("srv", "node", "qemu", 100, "start");
        assertEquals("Canonical keys must normalize case", keyUpper, keyLower);
    }

    @Test
    public void testConcurrentRaceConditionOnlyAllowsOneWinner() throws Exception {
        final int THREAD_COUNT = 16;
        final String targetKey = ActionGuard.buildGuestKey("srv-prod", "node-1", "qemu", 500, "force-stop");
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(THREAD_COUNT);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    if (guard.tryAcquire(targetKey)) {
                        successCount.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    endGate.countDown();
                }
            });
        }

        // Release all threads simultaneously to hammer the lock
        startGate.countDown();
        boolean finished = endGate.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue("All threads should complete within timeout", finished);
        assertEquals("Exactly one thread must acquire the in-flight lock", 1, successCount.get());
        assertTrue(guard.isRunning(targetKey));

        // Release and verify state returns to idle
        guard.release(targetKey);
        assertFalse(guard.isRunning(targetKey));
    }

    @Test
    public void testClearAllReleasesAllActiveGuards() {
        guard.tryAcquire("key1");
        guard.tryAcquire("key2");
        guard.tryAcquire("key3");
        assertEquals(3, guard.getActiveCount());

        guard.clear();
        assertEquals(0, guard.getActiveCount());
        assertFalse(guard.isRunning("key1"));
        assertFalse(guard.isRunning("key2"));
        assertFalse(guard.isRunning("key3"));
    }
}
