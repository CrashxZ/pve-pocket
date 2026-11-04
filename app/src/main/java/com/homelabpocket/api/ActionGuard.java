// SPDX-License-Identifier: GPL-3.0-or-later
// Homelab Pocket — Production Action Guard for in-flight operation locking
package com.homelabpocket.api;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Thread-safe production ActionGuard.
 *
 * Prevents double-submission and concurrent conflicting actions across:
 * - VM / Container list views (GuestsView)
 * - VM / Container detail activities (VmDetailActivity)
 * - Host node detail views (NodeDetailActivity)
 * - Background task polling lifecycles
 *
 * All state is centralized here to prevent race conditions across screens.
 */
public class ActionGuard {

    private static final ActionGuard INSTANCE = new ActionGuard();

    private final Set<String> inFlightActions = Collections.synchronizedSet(new HashSet<>());

    public static ActionGuard getInstance() {
        return INSTANCE;
    }

    /** Visible for testing / isolated instances */
    public ActionGuard() {}

    /**
     * Atomically attempts to acquire execution lock for the given target key.
     *
     * @param targetKey Unique target + action descriptor
     * @return true if lock acquired; false if an action is already in-flight
     */
    public boolean tryAcquire(String targetKey) {
        if (targetKey == null || targetKey.trim().isEmpty()) {
            return false;
        }
        return inFlightActions.add(targetKey);
    }

    /**
     * Convenience method for guest VM/LXC actions.
     */
    public boolean tryAcquireGuestAction(String serverId, String node, String guestType, int vmid, String action) {
        return tryAcquire(buildGuestKey(serverId, node, guestType, vmid, action));
    }

    /**
     * Releases execution lock for the given target key.
     */
    public void release(String targetKey) {
        if (targetKey != null) {
            inFlightActions.remove(targetKey);
        }
    }

    /**
     * Convenience method for releasing guest VM/LXC actions.
     */
    public void releaseGuestAction(String serverId, String node, String guestType, int vmid, String action) {
        release(buildGuestKey(serverId, node, guestType, vmid, action));
    }

    /**
     * Returns true if an action on targetKey is currently running.
     */
    public boolean isRunning(String targetKey) {
        if (targetKey == null) return false;
        return inFlightActions.contains(targetKey);
    }

    public boolean isGuestActionRunning(String serverId, String node, String guestType, int vmid, String action) {
        return isRunning(buildGuestKey(serverId, node, guestType, vmid, action));
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
    public void clear() {
        inFlightActions.clear();
    }

    public void clearAll() {
        clear();
    }

    /**
     * Returns the count of currently in-flight actions.
     */
    public int getActiveCount() {
        return inFlightActions.size();
    }
}
