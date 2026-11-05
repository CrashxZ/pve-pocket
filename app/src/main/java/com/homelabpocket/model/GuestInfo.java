// SPDX-License-Identifier: GPL-3.0-or-later
// Homelab Pocket — VM/LXC guest model
package com.homelabpocket.model;

/**
 * Represents a VM (QEMU) or LXC container with its current status.
 *
 * IMPORTANT: for resource display, do NOT add guest memory to host memory totals.
 * Guest RAM is allocated FROM the host — displaying both would double-count.
 * The UI shows fleet-level host RAM totals, and per-guest RAM as guest allocation.
 */
public class GuestInfo {

    public enum GuestType {
        QEMU,  // KVM/QEMU virtual machine
        LXC    // LXC container
    }

    public enum RunState {
        RUNNING,
        STOPPED,
        PAUSED,
        SUSPENDED,
        UNKNOWN
    }

    /** VM/LXC ID */
    public final int vmid;

    /** Guest name */
    public final String name;

    /** Type of guest */
    public final GuestType type;

    /** Current run state */
    public final RunState state;

    /** Node this guest runs on */
    public final String node;

    /** Server config ID */
    public final String serverId;

    /** CPU usage 0.0–1.0, NaN if stopped */
    public final double cpuUsage;

    /** Number of vCPUs configured */
    public final int cpuCount;

    /** RAM used by guest in bytes, -1 if stopped */
    public final long memUsed;

    /** RAM configured for guest in bytes */
    public final long memMax;

    /** RAM alias for memMax */
    public final long memTotal;

    /** Disk used by guest in bytes, -1 if unavailable */
    public final long diskUsed;

    /** Disk configured for guest in bytes, -1 if unavailable */
    public final long diskTotal;

    /** Disk read bytes/sec */
    public final long diskRead;

    /** Disk write bytes/sec */
    public final long diskWrite;

    /** Network in bytes/sec */
    public final long netIn;
    public final long netOut;

    /** Uptime in seconds, 0 if stopped */
    public final long uptime;

    /** Tags assigned to guest (comma-separated string from API) */
    public final String tags;

    /** Whether this guest is favorited by user */
    public boolean favorited;

    /** Timestamp of last fetch */
    public final long fetchedAt;

    public GuestInfo(int vmid, String name, GuestType type, RunState state,
                     String node, String serverId,
                     double cpuUsage, int cpuCount,
                     long memUsed, long memMax,
                     long diskUsed, long diskTotal,
                     long diskRead, long diskWrite,
                     long netIn, long netOut,
                     long uptime, String tags,
                     boolean favorited, long fetchedAt) {
        this.vmid = vmid;
        this.name = name != null ? name : "vm-" + vmid;
        this.type = type;
        this.state = state;
        this.node = node;
        this.serverId = serverId;
        this.cpuUsage = cpuUsage;
        this.cpuCount = cpuCount;
        this.memUsed = memUsed;
        this.memMax = memMax;
        this.memTotal = memMax;
        this.diskUsed = diskUsed;
        this.diskTotal = diskTotal;
        this.diskRead = diskRead;
        this.diskWrite = diskWrite;
        this.netIn = netIn;
        this.netOut = netOut;
        this.uptime = uptime;
        this.tags = tags != null ? tags : "";
        this.favorited = favorited;
        this.fetchedAt = fetchedAt;
    }

    public GuestInfo(int vmid, String name, GuestType type, RunState state,
                     String node, String serverId,
                     double cpuUsage, int cpuCount,
                     long memUsed, long memMax,
                     long diskRead, long diskWrite,
                     long netIn, long netOut,
                     long uptime, String tags,
                     boolean favorited, long fetchedAt) {
        this(vmid, name, type, state, node, serverId, cpuUsage, cpuCount,
             memUsed, memMax, -1, -1, diskRead, diskWrite, netIn, netOut,
             uptime, tags, favorited, fetchedAt);
    }

    public boolean isRunning() {
        return state == RunState.RUNNING;
    }

    public String getTypeLabel() {
        return type == GuestType.QEMU ? "VM" : "CT";
    }

    /** Unique key for identifying this guest across the fleet */
    public String getKey() {
        return serverId + "/" + node + "/" + type.name().toLowerCase() + "/" + vmid;
    }

    public double getCpuPercent() {
        return Double.isNaN(cpuUsage) ? 0.0 : cpuUsage * 100.0;
    }

    public double getMemPercent() {
        if (memMax <= 0) return 0.0;
        return (double) memUsed / memMax * 100.0;
    }

    /**
     * Whether this guest supports interactive terminal via Proxmox termproxy API.
     * LXC containers: YES (uses /nodes/{node}/lxc/{vmid}/termproxy)
     * QEMU VMs: NO — termproxy is not available for QEMU guests in standard PVE.
     *   QEMU guests use noVNC graphical console (not suitable for phone keyboard).
     *   The UI will explain this honestly rather than offering a fake shell button.
     */
    public boolean supportsTermproxy() {
        return type == GuestType.LXC;
    }

    /**
     * Returns the base host node name if prefixed with remote cluster (e.g. "node" from "remote/node").
     */
    public String getNodeDisplayName() {
        if (node == null) return "";
        int slash = node.indexOf('/');
        return (slash >= 0 && slash < node.length() - 1) ? node.substring(slash + 1) : node;
    }

    /**
     * Returns the remote cluster name if prefixed (e.g. "remote" from "remote/node").
     */
    public String getNodeRemoteName() {
        if (node == null) return "";
        int slash = node.indexOf('/');
        return (slash > 0) ? node.substring(0, slash) : "";
    }

    /**
     * Returns true if this guest's host node contains a remote cluster prefix.
     */
    public boolean hasSeparateRemote() {
        return node != null && node.contains("/") && !getNodeRemoteName().isEmpty();
    }
}
