// SPDX-License-Identifier: GPL-3.0-or-later
// Homelab Pocket — Node (Proxmox host) model
package com.homelabpocket.model;

/**
 * Represents a Proxmox host node with its resource metrics.
 * Metrics may be null/NaN if the node is offline or unreachable.
 */
public class NodeStatus {

    /** Node name as reported by API */
    public final String node;

    /** Server config ID this node belongs to */
    public final String serverId;

    public enum Status {
        ONLINE,
        OFFLINE,
        UNKNOWN
    }

    public final Status status;

    /** CPU usage 0.0–1.0, NaN if unknown */
    public final double cpuUsage;

    /** Total CPU cores/threads */
    public final int cpuCount;

    /** Used RAM in bytes, -1 if unknown */
    public final long memUsed;

    /** Total RAM in bytes, -1 if unknown */
    public final long memTotal;

    /** Used disk (root fs) in bytes, -1 if unknown */
    public final long diskUsed;

    /** Total disk (root fs) in bytes, -1 if unknown */
    public final long diskTotal;

    /** Network in bytes/sec (1-min avg), -1 if unknown */
    public final long netIn;
    public final long netOut;

    /** PVE version string, null if unknown */
    public final String pveVersion;

    /** Uptime in seconds, -1 if unknown */
    public final long uptime;

    /** Timestamp when this data was fetched (System.currentTimeMillis) */
    public final long fetchedAt;

    public NodeStatus(String node, String serverId, Status status,
                      double cpuUsage, int cpuCount,
                      long memUsed, long memTotal,
                      long diskUsed, long diskTotal,
                      long netIn, long netOut,
                      String pveVersion, long uptime,
                      long fetchedAt) {
        this.node = node;
        this.serverId = serverId;
        this.status = status;
        this.cpuUsage = cpuUsage;
        this.cpuCount = cpuCount;
        this.memUsed = memUsed;
        this.memTotal = memTotal;
        this.diskUsed = diskUsed;
        this.diskTotal = diskTotal;
        this.netIn = netIn;
        this.netOut = netOut;
        this.pveVersion = pveVersion;
        this.uptime = uptime;
        this.fetchedAt = fetchedAt;
    }

    /** Creates an offline placeholder for a known node */
    public static NodeStatus offline(String node, String serverId) {
        return new NodeStatus(node, serverId, Status.OFFLINE,
                Double.NaN, 0, -1, -1, -1, -1, -1, -1, null, -1,
                System.currentTimeMillis());
    }

    public boolean isOnline() {
        return status == Status.ONLINE;
    }

    public double getCpuUsagePercent() {
        return Double.isNaN(cpuUsage) ? 0.0 : cpuUsage * 100.0;
    }

    public double getMemUsagePercent() {
        if (memTotal <= 0) return 0.0;
        return (double) memUsed / memTotal * 100.0;
    }

    public double getDiskUsagePercent() {
        if (diskTotal <= 0) return 0.0;
        return (double) diskUsed / diskTotal * 100.0;
    }

    /**
     * Returns the base hostname of the node.
     * For PDM multi-cluster composite names (e.g. "remote/node"), returns "node".
     * If no remote prefix exists, returns the full node identifier.
     */
    public String getDisplayName() {
        if (node == null) return "";
        int slash = node.indexOf('/');
        return (slash >= 0 && slash < node.length() - 1) ? node.substring(slash + 1) : node;
    }

    /**
     * Returns the remote cluster name if prefixed (e.g. "remote" from "remote/node"),
     * or empty string if standalone.
     */
    public String getRemoteName() {
        if (node == null) return "";
        int slash = node.indexOf('/');
        return (slash > 0) ? node.substring(0, slash) : "";
    }

    /**
     * Returns true if this node identifier contains a separate remote cluster prefix.
     */
    public boolean hasSeparateRemote() {
        return node != null && node.contains("/") && !getRemoteName().isEmpty();
    }

    /**
     * Returns true if rootfs/disk telemetry is actively reported by upstream.
     * PDM reports -1 for disk and maxdisk, in which case this returns false.
     */
    public boolean isDiskReported() {
        return diskTotal > 0 && diskUsed >= 0;
    }

    /**
     * Returns true if network throughput telemetry is actively reported by upstream.
     * PDM reports -1 for netIn and netOut, in which case this returns false.
     */
    public boolean isNetReported() {
        return netIn >= 0 && netOut >= 0;
    }
}
