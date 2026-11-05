// SPDX-License-Identifier: GPL-3.0-or-later
// Homelab Pocket — Production Action Guard for in-flight operation locking
package com.homelabpocket.api;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe production ActionGuard with ownership-aware lease tracking.
 *
 * Prevents double-submission and concurrent conflicting actions across:
 * - VM / Container list views (GuestsView)
 * - VM / Container detail activities (VmDetailActivity)
 * - Host node detail views (NodeDetailActivity)
 * - Background task polling lifecycles
 *
 * Guarantees:
 * - ABA Prevention: A lease release only clears the exact lease instance acquired;
 *   releasing a stale or completed lease never releases a newer acquisition.
 * - Shared Lifecycle Management: TaskLifecycleOwner distinguishes queued cancellation
 *   (which releases immediately to prevent leaked guards) from active cancellation
 *   (which preserves guard protection until background work actually finishes).
 */
public class ActionGuard {

    private static final ActionGuard INSTANCE = new ActionGuard();

    /**
     * Unique ownership lease representing an in-flight operation reservation.
     */
    public static class Lease {
        private final ActionGuard guard;
        private final String key;
        private final long id;
        private final AtomicBoolean released = new AtomicBoolean(false);

        Lease(ActionGuard guard, String key, long id) {
            this.guard = guard != null ? guard : ActionGuard.getInstance();
            this.key = key;
            this.id = id;
        }

        public ActionGuard getGuard() {
            return guard;
        }

        public String getKey() {
            return key;
        }

        public long getId() {
            return id;
        }

        public boolean isReleased() {
            return released.get();
        }

        /**
         * Idempotently releases this lease. Returns true if this lease released the lock,
         * or false if already released or superseded.
         */
        public boolean release() {
            if (released.compareAndSet(false, true)) {
                return guard.releaseLease(key, this);
            }
            return false;
        }
    }

    /**
     * Shared idempotent lifecycle owner covering task execution, queued/active cancellation,
     * and callback release without ABA vulnerability.
     */
    public static class TaskLifecycleOwner {
        private final Lease lease;
        private final AtomicBoolean isStarted = new AtomicBoolean(false);
        private final AtomicBoolean isFinished = new AtomicBoolean(false);
        private final AtomicBoolean isCancelled = new AtomicBoolean(false);

        public TaskLifecycleOwner(Lease lease) {
            this.lease = lease;
        }

        public Lease getLease() {
            return lease;
        }

        public String getKey() {
            return lease != null ? lease.getKey() : null;
        }

        public boolean markStarted() {
            return isStarted.compareAndSet(false, true);
        }

        public boolean isStarted() {
            return isStarted.get();
        }

        public boolean isCancelled() {
            return isCancelled.get();
        }

        /**
         * Invoked when cancellation is requested (e.g. user dismissed dialog or activity destroyed).
         *
         * If the task is still QUEUED (not yet started doInBackground):
         * releases the lease immediately so that the resource lock is not orphaned.
         *
         * If the task is ACTIVE (already in doInBackground / network / polling):
         * marks cancelled = true, but DOES NOT release the lease early!
         * The active background owner remains protected until its background work completes.
         */
        public void requestCancel() {
            isCancelled.set(true);
            if (!isStarted.get()) {
                release();
            }
        }

        /**
         * Idempotently releases the guard lease when background execution completes,
         * whether normally, via exception, or upon background loop exit.
         */
        public void release() {
            isFinished.set(true);
            if (lease != null) {
                lease.release();
            }
        }
    }

    private final Set<String> inFlightActions = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, Lease> activeLeases = new ConcurrentHashMap<>();
    private final AtomicLong leaseSequence = new AtomicLong(0);

    public static ActionGuard getInstance() {
        return INSTANCE;
    }

    /** Visible for testing / isolated instances */
    public ActionGuard() {}

    /**
     * Encodes a tuple component safely using length prefixing to prevent delimiter collision.
     * Preserves exact opaque identity without lowercasing.
     */
    private static String encodeComponent(String s) {
        if (s == null) {
            return "-1:";
        }
        return s.length() + ":" + s;
    }

    /**
     * Builds resource-level canonical key for guest VM/LXC power lifecycle operations.
     * Prevents conflicting actions (e.g. shutdown vs force-stop) from running concurrently
     * on the same guest resource across list and detail views.
     *
     * Collision-proof length-prefixed tuple preserves opaque server and node identifiers
     * without lowercasing or boundary collision (e.g. server "a:b"/node "c" vs server "a"/node "b:c").
     */
    public static String buildGuestResourceKey(String serverId, String node, String guestType, int vmid) {
        String safeServer = serverId != null ? serverId : "";
        String safeNode = node != null ? node : "";
        String safeType = guestType != null ? guestType.toLowerCase(java.util.Locale.ROOT) : "";
        return "guest[" +
                encodeComponent(safeServer) + "," +
                encodeComponent(safeNode) + "," +
                encodeComponent(safeType) + "," +
                vmid + "]";
    }

    /**
     * Atomically attempts to acquire execution lock for the given target key.
     *
     * @param targetKey Unique target descriptor
     * @return true if lock acquired; false if an action is already in-flight
     */
    public synchronized boolean tryAcquire(String targetKey) {
        if (targetKey == null || targetKey.trim().isEmpty()) {
            return false;
        }
        if (activeLeases.containsKey(targetKey)) {
            return false;
        }
        long id = leaseSequence.incrementAndGet();
        Lease lease = new Lease(this, targetKey, id);
        activeLeases.put(targetKey, lease);
        inFlightActions.add(targetKey);
        return true;
    }

    /**
     * Atomically attempts to acquire an ownership lease for the given target key.
     *
     * @param targetKey Unique target descriptor
     * @return Lease if acquired; null if an action is already in-flight
     */
    public synchronized Lease acquireLease(String targetKey) {
        if (targetKey == null || targetKey.trim().isEmpty()) {
            return null;
        }
        if (activeLeases.containsKey(targetKey)) {
            return null;
        }
        long id = leaseSequence.incrementAndGet();
        Lease lease = new Lease(this, targetKey, id);
        activeLeases.put(targetKey, lease);
        inFlightActions.add(targetKey);
        return lease;
    }

    /**
     * Convenience method for guest VM/LXC actions.
     * Acquires a collision-safe resource-level lock for the guest, preventing concurrent
     * conflicting actions (such as shutdown and force-stop) on the same guest.
     */
    public boolean tryAcquireGuestAction(String serverId, String node, String guestType, int vmid, String action) {
        return tryAcquire(buildGuestResourceKey(serverId, node, guestType, vmid));
    }

    /**
     * Releases execution lock for the given target key unconditionally.
     */
    public synchronized void release(String targetKey) {
        if (targetKey != null) {
            activeLeases.remove(targetKey);
            inFlightActions.remove(targetKey);
        }
    }

    /**
     * Ownership-aware release: only releases if the active lease matches the provided lease,
     * preventing ABA problems where a delayed release clears a subsequent newer acquisition.
     */
    public synchronized boolean releaseLease(String targetKey, Lease lease) {
        if (targetKey == null || lease == null) {
            return false;
        }
        Lease current = activeLeases.get(targetKey);
        if (current != null && current.getId() == lease.getId()) {
            activeLeases.remove(targetKey);
            inFlightActions.remove(targetKey);
            return true;
        }
        return false;
    }

    /**
     * Convenience method for releasing guest VM/LXC resource-level lock.
     */
    public void releaseGuestAction(String serverId, String node, String guestType, int vmid, String action) {
        release(buildGuestResourceKey(serverId, node, guestType, vmid));
    }

    /**
     * Returns true if an action on targetKey is currently running.
     */
    public synchronized boolean isRunning(String targetKey) {
        if (targetKey == null) return false;
        return activeLeases.containsKey(targetKey) || inFlightActions.contains(targetKey);
    }

    public boolean isGuestActionRunning(String serverId, String node, String guestType, int vmid, String action) {
        return isRunning(buildGuestResourceKey(serverId, node, guestType, vmid));
    }

    public boolean isGuestActionRunning(String serverId, String node, String guestType, int vmid) {
        return isRunning(buildGuestResourceKey(serverId, node, guestType, vmid));
    }

    /**
     * Builds standard canonical key for guest actions.
     */
    public static String buildGuestKey(String serverId, String node, String guestType, int vmid, String action) {
        return (serverId != null ? serverId.toLowerCase() : "default") + ":" +
               (node != null ? node.toLowerCase() : "unknown") + ":" +
               (guestType != null ? guestType.toLowerCase() : "guest") + ":" +
               vmid + ":" +
               (action != null ? action.toLowerCase() : "action");
    }

    /**
     * Clears all active locks (e.g. on logout or teardown).
     */
    public synchronized void clear() {
        activeLeases.clear();
        inFlightActions.clear();
    }

    public void clearAll() {
        clear();
    }

    /**
     * Returns the count of currently in-flight actions.
     */
    public synchronized int getActiveCount() {
        return activeLeases.size();
    }
}
