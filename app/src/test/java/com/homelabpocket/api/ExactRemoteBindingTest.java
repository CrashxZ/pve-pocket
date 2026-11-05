package com.homelabpocket.api;

import org.junit.Test;
import static org.junit.Assert.*;

public class ExactRemoteBindingTest {

    @Test
    public void caseDistinctRemoteMustBeRejected() throws Exception {
        boolean rejected = false;
        try {
            PdmApiDriver.verifyRemoteUpidBinding("cluster-west", "pve:CLUSTER-WEST!UPID:pve-node-01:00001234:00005678:67B4A9D1:qmstart:101:root@pam:");
        } catch (ApiException expected) {
            rejected = true;
        }
        assertTrue("Opaque remote identity mismatch must fail closed, not compare case-insensitively", rejected);
    }

    @Test
    public void malformedWhitespaceInRemoteUpidPrefixMustBeRejected() throws Exception {
        // Space in remoteType " pve"
        boolean rej1 = false;
        try {
            PdmApiDriver.verifyRemoteUpidBinding("cluster-west", " pve:cluster-west!UPID:node:1:2:3:qmstart:101:root@pam:");
        } catch (ApiException expected) {
            rej1 = true;
        }
        assertTrue("Whitespace in remoteType must fail closed without normalization", rej1);

        // Leading space in remote " cluster-west"
        boolean rej2 = false;
        try {
            PdmApiDriver.verifyRemoteUpidBinding("cluster-west", "pve: cluster-west!UPID:node:1:2:3:qmstart:101:root@pam:");
        } catch (ApiException expected) {
            rej2 = true;
        }
        assertTrue("Leading whitespace in remote parameter must fail closed without normalization", rej2);

        // Trailing space in remote "cluster-west "
        boolean rej3 = false;
        try {
            PdmApiDriver.verifyRemoteUpidBinding("cluster-west", "pve:cluster-west !UPID:node:1:2:3:qmstart:101:root@pam:");
        } catch (ApiException expected) {
            rej3 = true;
        }
        assertTrue("Trailing whitespace before bang separator must fail closed without normalization", rej3);

        // extractRemoteFromUpid preserves exact substring without trimming
        String extracted = PdmApiDriver.extractRemoteFromUpid("pve: cluster-west !UPID:node:1:2:3:qmstart:101:root@pam:");
        assertEquals("extractRemoteFromUpid must preserve exact opaque tokens without trimming", " cluster-west ", extracted);
    }

    @Test
    public void pollTaskExplicitExpiredBudgetFailsClosed() throws Exception {
        com.homelabpocket.model.ServerConfig cfg = new com.homelabpocket.model.ServerConfig("pdm-t", "PDM", "127.0.0.1", 1, com.homelabpocket.model.ServerConfig.ServerType.PDM, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        PdmApiDriver driver = new PdmApiDriver(cfg);
        com.homelabpocket.model.AuthCredentials creds = com.homelabpocket.model.AuthCredentials.apiToken("token");

        boolean rejectedZero = false;
        try {
            driver.pollTask("cluster-west", "pve:cluster-west!UPID:node:1:2:3:qmstart:101:root@pam:", creds, 0);
        } catch (ApiException expected) {
            rejectedZero = true;
            assertTrue("Explicit budget <= 0 error disclosure", expected.getMessage().contains("Operation budget exhausted before task polling"));
        }
        assertTrue("Explicit budget = 0 must fail closed immediately", rejectedZero);

        boolean rejectedNegative = false;
        try {
            driver.pollTask("cluster-west", "pve:cluster-west!UPID:node:1:2:3:qmstart:101:root@pam:", creds, -50L);
        } catch (ApiException expected) {
            rejectedNegative = true;
            assertTrue("Explicit budget < 0 error disclosure", expected.getMessage().contains("Operation budget exhausted before task polling"));
        }
        assertTrue("Explicit budget < 0 must fail closed immediately", rejectedNegative);
    }

    @Test
    public void actionGuardAbaPreventionAndQueuedVsActiveCancellation() {
        ActionGuard guard = new ActionGuard();
        String key = "guest[srv,node,qemu,100]";

        // 1. ABA Prevention
        ActionGuard.Lease lease1 = guard.acquireLease(key);
        assertNotNull("First lease acquisition must succeed", lease1);
        assertTrue(guard.isRunning(key));

        assertTrue("Lease1 release must succeed", lease1.release());
        assertFalse(guard.isRunning(key));

        // Rapid new acquisition
        ActionGuard.Lease lease2 = guard.acquireLease(key);
        assertNotNull("Second lease acquisition must succeed", lease2);
        assertTrue(guard.isRunning(key));

        // Delayed release of stale lease1 (e.g. from prior task callback)
        assertFalse("Stale lease1 release must return false", lease1.release());
        assertTrue("Stale lease1 release must NOT remove newer lease2", guard.isRunning(key));

        // Release lease2
        assertTrue("Lease2 release must succeed", lease2.release());
        assertFalse(guard.isRunning(key));

        // 2. Queued Cancellation: must release immediately
        ActionGuard.Lease queuedLease = guard.acquireLease(key);
        assertNotNull(queuedLease);
        ActionGuard.TaskLifecycleOwner queuedOwner = new ActionGuard.TaskLifecycleOwner(queuedLease);
        assertFalse(queuedOwner.isStarted());

        queuedOwner.requestCancel();
        assertTrue("Queued cancellation must be marked cancelled", queuedOwner.isCancelled());
        assertTrue("Queued cancellation must release guard immediately to prevent leak", queuedLease.isReleased());
        assertFalse("Guard must be idle after queued cancellation", guard.isRunning(key));

        // 3. Active Cancellation: must NOT release immediately while worker runs
        ActionGuard.Lease activeLease = guard.acquireLease(key);
        assertNotNull(activeLease);
        ActionGuard.TaskLifecycleOwner activeOwner = new ActionGuard.TaskLifecycleOwner(activeLease);

        activeOwner.markStarted();
        assertTrue(activeOwner.isStarted());

        activeOwner.requestCancel();
        assertTrue("Active cancellation must be marked cancelled", activeOwner.isCancelled());
        assertFalse("Active cancellation must NOT release guard early while worker is alive", activeLease.isReleased());
        assertTrue("Guard must remain held during active cancellation", guard.isRunning(key));

        // Worker terminates and cleans up
        activeOwner.release();
        assertTrue("Guard must release after worker completes", activeLease.isReleased());
        assertFalse("Guard must be idle after worker release", guard.isRunning(key));
    }

    @Test
    public void whitespaceInPowerActionParametersMustBeRejectedWithoutNormalization() throws Exception {
        com.homelabpocket.model.ServerConfig cfg = new com.homelabpocket.model.ServerConfig("pdm-t", "PDM", "127.0.0.1", 1, com.homelabpocket.model.ServerConfig.ServerType.PDM, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        PdmApiDriver driver = new PdmApiDriver(cfg);
        com.homelabpocket.model.AuthCredentials creds = com.homelabpocket.model.AuthCredentials.apiToken("token");

        boolean rejRemote = false;
        try {
            driver.executeGuestPowerAction(" cluster-west", "pve-node-01", 100, com.homelabpocket.model.GuestInfo.GuestType.QEMU, "start", creds, 1000L);
        } catch (ApiException expected) {
            rejRemote = true;
            assertTrue("Rejection must mention invalid remote", expected.getMessage().contains("Invalid remote identifier"));
        }
        assertTrue("Leading whitespace in remote must be rejected fail-closed without trimming", rejRemote);

        boolean rejNode = false;
        try {
            driver.executeGuestPowerAction("cluster-west", "pve-node-01 ", 100, com.homelabpocket.model.GuestInfo.GuestType.QEMU, "start", creds, 1000L);
        } catch (ApiException expected) {
            rejNode = true;
            assertTrue("Rejection must mention invalid node", expected.getMessage().contains("Invalid node identifier"));
        }
        assertTrue("Trailing whitespace in node must be rejected fail-closed without trimming", rejNode);
    }

    @Test
    public void pveExplicitExpiredBudgetsFailClosed() throws Exception {
        com.homelabpocket.model.ServerConfig cfg = new com.homelabpocket.model.ServerConfig("pve-t", "PVE", "127.0.0.1", 8006, com.homelabpocket.model.ServerConfig.ServerType.PVE, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        PveApiDriver driver = new PveApiDriver(cfg);
        com.homelabpocket.model.AuthCredentials creds = com.homelabpocket.model.AuthCredentials.apiToken("token");

        boolean rejPollZero = false;
        try {
            driver.pollTask("node1", "UPID:node1:1:2:3:qmstart:100:root@pam:", creds, 0);
        } catch (ApiException expected) {
            rejPollZero = true;
            assertTrue(expected.getMessage().contains("Operation budget exhausted before task polling"));
        }
        assertTrue("PVE pollTask with explicit budget=0 must fail closed", rejPollZero);

        boolean rejStartZero = false;
        try {
            driver.startGuest("node1", 100, com.homelabpocket.model.GuestInfo.GuestType.QEMU, creds, 0);
        } catch (ApiException expected) {
            rejStartZero = true;
            assertTrue(expected.getMessage().contains("Request deadline already expired before dispatch"));
        }
        assertTrue("PVE startGuest with explicit budget=0 must fail closed", rejStartZero);
    }

    @Test
    public void targetValidityStrictIdentityAndStatusChecks() {
        com.homelabpocket.model.GuestInfo runningQemu = new com.homelabpocket.model.GuestInfo(
                100, "vm100", com.homelabpocket.model.GuestInfo.GuestType.QEMU,
                com.homelabpocket.model.GuestInfo.RunState.RUNNING, "node1", "srv1",
                0.1, 2, 1024, 2048, 1024, 2048, 0, 0, 100, "", false, System.currentTimeMillis());

        // Exact match passes
        assertTrue(com.homelabpocket.ui.UiPresentationPolicy.isGuestTargetValid(runningQemu, "node1", 100, com.homelabpocket.model.GuestInfo.GuestType.QEMU));

        // Different node fails (guest moved)
        assertFalse(com.homelabpocket.ui.UiPresentationPolicy.isGuestTargetValid(runningQemu, "node2", 100, com.homelabpocket.model.GuestInfo.GuestType.QEMU));

        // Case-different node fails (case sensitive)
        assertFalse(com.homelabpocket.ui.UiPresentationPolicy.isGuestTargetValid(runningQemu, "NODE1", 100, com.homelabpocket.model.GuestInfo.GuestType.QEMU));

        // Different type fails (LXC vs QEMU collision protection)
        assertFalse(com.homelabpocket.ui.UiPresentationPolicy.isGuestTargetValid(runningQemu, "node1", 100, com.homelabpocket.model.GuestInfo.GuestType.LXC));

        // Null type fails
        assertFalse(com.homelabpocket.ui.UiPresentationPolicy.isGuestTargetValid(runningQemu, "node1", 100, null));

        // Offline / unknown state fails
        com.homelabpocket.model.GuestInfo unknownGuest = new com.homelabpocket.model.GuestInfo(
                100, "vm100", com.homelabpocket.model.GuestInfo.GuestType.QEMU,
                com.homelabpocket.model.GuestInfo.RunState.UNKNOWN, "node1", "srv1",
                0, 2, 0, 2048, 0, 2048, 0, 0, 0, "", false, System.currentTimeMillis());
        assertFalse(com.homelabpocket.ui.UiPresentationPolicy.isGuestTargetValid(unknownGuest, "node1", 100, com.homelabpocket.model.GuestInfo.GuestType.QEMU));
    }
}
