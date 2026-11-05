// SPDX-License-Identifier: GPL-3.0-or-later
// PVE Pocket — UI Polish Presentation & Formatting Policy
package com.homelabpocket.ui;

import com.homelabpocket.model.GuestInfo;
import com.homelabpocket.model.NodeStatus;
import com.homelabpocket.model.ServerConfig;
import com.homelabpocket.model.StoragePool;
import com.homelabpocket.util.JsonUtil;

/**
 * Central presentation policy for PVE Pocket UI formatting.
 *
 * Ensures consistent behavior across views (NodesView, DashboardView, GuestsView,
 * NodeDetailActivity, VmDetailActivity) and provides a single, testable source of truth.
 */
public final class UiPresentationPolicy {

    public static final String NOT_REPORTED = "Not reported";
    public static final String NOT_REPORTED_BY_PDM = "Not reported by PDM";
    public static final String PDM_MONITORING_ONLY = "PDM MONITORING ONLY";
    public static final String PDM_MONITORING_BADGE = "PDM MONITORING";
    public static final String PDM_MONITORING_DETAIL =
            "Power lifecycle controls and direct terminal shell are disabled on Proxmox Datacenter Manager (PDM) endpoints. Please use the upstream Proxmox VE web console or PDM web interface for direct guest management.";
    public static final String STORAGE_TYPE_NOT_REPORTED = "Type not reported";
    public static final String STORAGE_TYPE_NOT_REPORTED_PDM = "Type not reported by PDM";

    private UiPresentationPolicy() {}

    /**
     * Helper to return appropriate missing metric attribution.
     * Uses "Not reported by PDM" for confirmed PDM backends, generic "Not reported" otherwise.
     */
    public static String getNotReportedLabel(boolean isPdm) {
        return isPdm ? NOT_REPORTED_BY_PDM : NOT_REPORTED;
    }

    /**
     * Presentation policy for Node Detail Root FS display.
     * When diskTotal <= 0 or diskUsed < 0, reports "Root FS (/): Not reported by PDM" (for PDM)
     * or "Root FS (/): Not reported" (for PVE/unknown).
     * Never derives synthetic metrics from storage pools and never reports 0%.
     */
    public static String formatNodeRootFs(NodeStatus node, boolean isPdm) {
        if (node == null || !node.isDiskReported()) {
            return "Root FS (/): " + getNotReportedLabel(isPdm);
        }
        int pct = (int) ((node.diskUsed * 100L) / node.diskTotal);
        return "Root FS (/): " + JsonUtil.formatBytes(node.diskUsed) + " / " +
                JsonUtil.formatBytes(node.diskTotal) + " (" + pct + "%)";
    }

    public static String formatNodeRootFs(NodeStatus node, ServerConfig server) {
        return formatNodeRootFs(node, isPdm(server));
    }

    public static String formatNodeRootFs(NodeStatus node) {
        return formatNodeRootFs(node, false);
    }

    /**
     * Determines whether Root FS progress bar should be displayed.
     * True only if upstream reports valid disk capacity; false (hidden) on PDM remote hosts.
     */
    public static boolean isRootFsBarVisible(NodeStatus node) {
        return node != null && node.isDiskReported();
    }

    /**
     * Presentation policy for host node card disk summary (NodesView and DashboardView).
     */
    public static String formatHostDisk(NodeStatus node, boolean isPdm) {
        if (node == null || !node.isDiskReported()) {
            return "Disk: " + getNotReportedLabel(isPdm);
        }
        return "Disk: " + JsonUtil.formatBytes(node.diskUsed) + " / " + JsonUtil.formatBytes(node.diskTotal);
    }

    public static String formatHostDisk(NodeStatus node, ServerConfig server) {
        return formatHostDisk(node, isPdm(server));
    }

    public static String formatHostDisk(NodeStatus node) {
        return formatHostDisk(node, false);
    }

    /**
     * Presentation policy for host node card network throughput (NodesView and DashboardView).
     */
    public static String formatHostNet(NodeStatus node, boolean isPdm) {
        if (node == null || !node.isNetReported()) {
            return "Net: " + getNotReportedLabel(isPdm);
        }
        return "Net: \u2193" + JsonUtil.formatRate(node.netIn) + " \u2191" + JsonUtil.formatRate(node.netOut);
    }

    public static String formatHostNet(NodeStatus node, ServerConfig server) {
        return formatHostNet(node, isPdm(server));
    }

    public static String formatHostNet(NodeStatus node) {
        return formatHostNet(node, false);
    }

    /**
     * Formats node metadata subtitle (version, uptime, and separate remote if applicable).
     * Preserves remote cluster identity regardless of online or offline status.
     */
    public static String formatNodeMeta(NodeStatus node) {
        if (node == null) return "";
        if (!node.isOnline()) {
            if (node.hasSeparateRemote()) {
                return "Unreachable / Intentionally powered down \u2022 [Remote: " + node.getRemoteName() + "]";
            }
            return "Unreachable / Intentionally powered down";
        }
        String uptime = JsonUtil.formatUptime(node.uptime);
        String ver = node.pveVersion != null ? node.pveVersion : "PVE";
        if (node.hasSeparateRemote()) {
            return ver + " \u2022 Up " + uptime + " \u2022 [Remote: " + node.getRemoteName() + "]";
        }
        return ver + " \u2022 Up " + uptime;
    }

    /**
     * Extracts basename from a node identifier (e.g. "home-lab" from "home-lab/home-lab").
     */
    public static String getNodeDisplayName(String node) {
        if (node == null) return "";
        int slash = node.indexOf('/');
        return (slash >= 0 && slash < node.length() - 1) ? node.substring(slash + 1) : node;
    }

    /**
     * Extracts remote cluster name from a node identifier (e.g. "cluster-1" from "cluster-1/node-a").
     */
    public static String getNodeRemoteName(String node) {
        if (node == null) return "";
        int slash = node.indexOf('/');
        return (slash > 0) ? node.substring(0, slash) : "";
    }

    /**
     * Returns true if the node identifier contains a remote prefix.
     */
    public static boolean hasSeparateRemote(String node) {
        return node != null && node.contains("/") && !getNodeRemoteName(node).isEmpty();
    }

    /**
     * Formats guest node subtitle, separating remote cluster from node basename.
     */
    public static String formatGuestHostLabel(GuestInfo guest) {
        if (guest == null) return "";
        if (guest.hasSeparateRemote()) {
            return "[" + guest.vmid + "] \u2022 Host: " + guest.getNodeDisplayName() + " [Remote: " + guest.getNodeRemoteName() + "]";
        }
        return "[" + guest.vmid + "] \u2022 Host: " + (guest.node != null ? guest.node : "unknown");
    }

    /**
     * Formats VM/LXC detail header subtitle.
     */
    public static String formatVmDetailSubtitle(String guestType, String node) {
        String typeLabel = guestType != null ? guestType : "Guest";
        if (node != null && node.contains("/")) {
            int slash = node.indexOf('/');
            String remote = node.substring(0, slash);
            String baseNode = node.substring(slash + 1);
            return typeLabel + " on " + baseNode + " [Remote: " + remote + "]";
        }
        return typeLabel + " on " + (node != null ? node : "unknown");
    }

    /**
     * Formats guest disk display in VmDetailActivity.
     */
    public static String formatGuestDisk(GuestInfo guest, boolean isPdm) {
        if (guest == null || guest.diskTotal <= 0) {
            return "Disk: " + getNotReportedLabel(isPdm);
        }
        if (guest.diskUsed >= 0) {
            return "Disk: " + JsonUtil.formatBytes(guest.diskUsed) + " / " + JsonUtil.formatBytes(guest.diskTotal);
        }
        return "Disk: - / " + JsonUtil.formatBytes(guest.diskTotal);
    }

    public static String formatGuestDisk(GuestInfo guest, ServerConfig server) {
        return formatGuestDisk(guest, isPdm(server));
    }

    public static String formatGuestDisk(GuestInfo guest) {
        return formatGuestDisk(guest, false);
    }

    /**
     * Returns true if server is Proxmox Datacenter Manager.
     */
    public static boolean isPdm(ServerConfig server) {
        return server != null && server.type == ServerConfig.ServerType.PDM;
    }

    /**
     * Determines whether guest lifecycle power controls (Start, Shutdown, Reboot, Stop)
     * are supported and should be rendered in UI.
     * In v0.1.3-alpha, guest power is supported for both PVE and PDM endpoints.
     */
    public static boolean isGuestPowerAllowed(ServerConfig server) {
        return true;
    }

    /**
     * Determines whether host lifecycle power controls (Host Node Reboot, Shutdown)
     * are supported and should be rendered in UI.
     * Host power operations are strictly disabled on PDM endpoints (guest power only).
     */
    public static boolean isHostPowerAllowed(ServerConfig server) {
        return !isPdm(server);
    }

    /**
     * Determines whether host shell button is supported and should be rendered in UI.
     * Host direct shell streaming is disabled on PDM endpoints.
     */
    public static boolean isHostShellAllowed(ServerConfig server) {
        return !isPdm(server);
    }

    /**
     * Determines whether guest console/shell button is supported and should be rendered.
     * Guest terminal streaming is disabled on PDM endpoints fail-closed.
     */
    public static boolean isGuestConsoleAllowed(ServerConfig server) {
        return !isPdm(server);
    }

    /**
     * Encapsulates the expected binding state of guest power and terminal controls
     * in VmDetailActivity and GuestsView.
     * Provides testable view-binding verification in headless unit tests.
     */
    public static final class GuestDetailBindingState {
        public final boolean isPowerCardVisible;
        public final boolean isStartButtonVisible;
        public final boolean isShutdownButtonVisible;
        public final boolean isRebootButtonVisible;
        public final boolean isForceStopButtonVisible;
        public final boolean isTerminalCardVisible;
        public final boolean isTerminalButtonVisible;
        public final boolean isPdmNoticeVisible;

        public GuestDetailBindingState(
                boolean isPowerCardVisible,
                boolean isStartButtonVisible,
                boolean isShutdownButtonVisible,
                boolean isRebootButtonVisible,
                boolean isForceStopButtonVisible,
                boolean isTerminalCardVisible,
                boolean isTerminalButtonVisible,
                boolean isPdmNoticeVisible) {
            this.isPowerCardVisible = isPowerCardVisible;
            this.isStartButtonVisible = isStartButtonVisible;
            this.isShutdownButtonVisible = isShutdownButtonVisible;
            this.isRebootButtonVisible = isRebootButtonVisible;
            this.isForceStopButtonVisible = isForceStopButtonVisible;
            this.isTerminalCardVisible = isTerminalCardVisible;
            this.isTerminalButtonVisible = isTerminalButtonVisible;
            this.isPdmNoticeVisible = isPdmNoticeVisible;
        }
    }

    /**
     * Evaluates the view-binding state for guest power lifecycle controls in VmDetailActivity.
     */
    public static GuestDetailBindingState evaluateGuestDetailBinding(GuestInfo guest, ServerConfig server) {
        if (guest == null) return null;
        boolean isPdm = isPdm(server);
        boolean isRunning = (guest.state == GuestInfo.RunState.RUNNING);
        boolean isStopped = (guest.state == GuestInfo.RunState.STOPPED);
        boolean isUnknown = (guest.state == GuestInfo.RunState.UNKNOWN || (!isRunning && !isStopped));

        boolean isPowerCardVisible = !isUnknown;
        boolean isStartButtonVisible = isStopped;
        boolean isShutdownButtonVisible = isRunning;
        boolean isRebootButtonVisible = isRunning;
        boolean isForceStopButtonVisible = isRunning;

        // Terminal streaming is disabled on PDM fail-closed and disabled for unknown
        boolean isTerminalCardVisible = !isPdm && !isUnknown;
        boolean isTerminalButtonVisible = !isPdm && !isUnknown;
        boolean isPdmNoticeVisible = false;

        return new GuestDetailBindingState(
                isPowerCardVisible,
                isStartButtonVisible,
                isShutdownButtonVisible,
                isRebootButtonVisible,
                isForceStopButtonVisible,
                isTerminalCardVisible,
                isTerminalButtonVisible,
                isPdmNoticeVisible);
    }

    /**
     * Encapsulates the expected binding state of a guest item card in GuestsView.
     */
    public static final class GuestCardBindingState {
        public final boolean isMetricsVisible;
        public final boolean isStartButtonVisible;
        public final boolean isStartButtonEnabled;
        public final boolean isShutdownButtonVisible;
        public final boolean isShutdownButtonEnabled;
        public final boolean isRebootButtonVisible;
        public final boolean isRebootButtonEnabled;
        public final boolean isForceStopButtonVisible;
        public final boolean isForceStopButtonEnabled;
        public final boolean isConsoleVisible;
        public final String statusText;

        public GuestCardBindingState(
                boolean isMetricsVisible,
                boolean isStartButtonVisible,
                boolean isStartButtonEnabled,
                boolean isShutdownButtonVisible,
                boolean isShutdownButtonEnabled,
                boolean isRebootButtonVisible,
                boolean isRebootButtonEnabled,
                boolean isForceStopButtonVisible,
                boolean isForceStopButtonEnabled,
                boolean isConsoleVisible,
                String statusText) {
            this.isMetricsVisible = isMetricsVisible;
            this.isStartButtonVisible = isStartButtonVisible;
            this.isStartButtonEnabled = isStartButtonEnabled;
            this.isShutdownButtonVisible = isShutdownButtonVisible;
            this.isShutdownButtonEnabled = isShutdownButtonEnabled;
            this.isRebootButtonVisible = isRebootButtonVisible;
            this.isRebootButtonEnabled = isRebootButtonEnabled;
            this.isForceStopButtonVisible = isForceStopButtonVisible;
            this.isForceStopButtonEnabled = isForceStopButtonEnabled;
            this.isConsoleVisible = isConsoleVisible;
            this.statusText = statusText;
        }
    }

    /**
     * Evaluates the view-binding state for guest power lifecycle controls in GuestsView.
     */
    public static GuestCardBindingState evaluateGuestCardBinding(GuestInfo guest, ServerConfig server) {
        if (guest == null) return null;
        boolean isPdm = isPdm(server);
        boolean isRunning = (guest.state == GuestInfo.RunState.RUNNING);
        boolean isStopped = (guest.state == GuestInfo.RunState.STOPPED);
        boolean isUnknown = (guest.state == GuestInfo.RunState.UNKNOWN || (!isRunning && !isStopped));

        boolean isMetricsVisible = isRunning;
        boolean isStartVisible = isStopped;
        boolean isStartEnabled = isStopped;
        boolean isShutdownVisible = isRunning;
        boolean isShutdownEnabled = isRunning;
        boolean isRebootVisible = isRunning;
        boolean isRebootEnabled = isRunning;
        boolean isForceStopVisible = isRunning;
        boolean isForceStopEnabled = isRunning;
        boolean isConsoleVisible = !isPdm && !isUnknown;
        String statusText = isRunning ? "RUNNING" : isStopped ? "STOPPED" : "UNKNOWN";

        return new GuestCardBindingState(
                isMetricsVisible,
                isStartVisible, isStartEnabled,
                isShutdownVisible, isShutdownEnabled,
                isRebootVisible, isRebootEnabled,
                isForceStopVisible, isForceStopEnabled,
                isConsoleVisible,
                statusText);
    }

    /**
     * Parses guest type from Android Intent extras fail-closed.
     * Missing, empty, or unparseable values return null rather than defaulting to QEMU.
     */
    public static GuestInfo.GuestType parseIntentGuestType(String typeStr) {
        if ("LXC".equalsIgnoreCase(typeStr)) {
            return GuestInfo.GuestType.LXC;
        } else if ("QEMU".equalsIgnoreCase(typeStr)) {
            return GuestInfo.GuestType.QEMU;
        }
        return null;
    }

    /**
     * Verifies that the resolved guest matches exact required target parameters.
     * Prevents dispatching operations to stale, moved, or offline guests.
     */
    public static boolean isGuestTargetValid(GuestInfo guest, String expectedNode, int expectedVmid, GuestInfo.GuestType expectedType) {
        if (guest == null) return false;
        if (expectedVmid <= 0 || guest.vmid != expectedVmid) return false;
        if (expectedNode == null || expectedNode.isEmpty() || !expectedNode.equals(guest.node)) return false;
        if (expectedType == null || guest.type != expectedType) return false;
        if (guest.state == null || guest.state == GuestInfo.RunState.UNKNOWN) return false;
        return true;
    }

    /**
     * Storage pool driver type explanatory note.
     * When storage type is explicitly unavailable from endpoint or permission, surfaces the exact reason;
     * returns "Type not reported by PDM" when driver type is unknown on PDM;
     * "Type not reported" when unknown on PVE / generic; null when reported.
     */
    public static String getStorageTypeFootnote(StoragePool pool, boolean isPdm) {
        if (pool == null) {
            return null;
        }
        if (pool.type != null && pool.type.startsWith("Type unavailable")) {
            return pool.type;
        }
        if (pool.isTypeReported()) {
            return null;
        }
        return isPdm ? STORAGE_TYPE_NOT_REPORTED_PDM : STORAGE_TYPE_NOT_REPORTED;
    }

    public static String getStorageTypeFootnote(StoragePool pool, ServerConfig server) {
        return getStorageTypeFootnote(pool, isPdm(server));
    }

    public static String getStorageTypeFootnote(StoragePool pool) {
        return getStorageTypeFootnote(pool, false);
    }

    /**
     * Encapsulates the expected binding state of a host node card in NodesView or DashboardView.
     * Allows fully verifying the view-binding model logic in unit tests without requiring
     * an Android UI view hierarchy runtime.
     */
    public static final class NodeCardBindingState {
        public final String displayName;
        public final String statusText;
        public final String metaText;
        public final boolean isOnline;
        public final boolean isOfflineMsgVisible;
        public final boolean isMetricsLayoutVisible;
        public final boolean isPdmBadgeVisible;
        public final boolean isShellButtonVisible;
        public final String diskText;
        public final String netText;
        public final String intentNode;
        public final String intentServerId;

        public NodeCardBindingState(
                String displayName, String statusText, String metaText,
                boolean isOnline, boolean isOfflineMsgVisible, boolean isMetricsLayoutVisible,
                boolean isPdmBadgeVisible, boolean isShellButtonVisible,
                String diskText, String netText,
                String intentNode, String intentServerId) {
            this.displayName = displayName;
            this.statusText = statusText;
            this.metaText = metaText;
            this.isOnline = isOnline;
            this.isOfflineMsgVisible = isOfflineMsgVisible;
            this.isMetricsLayoutVisible = isMetricsLayoutVisible;
            this.isPdmBadgeVisible = isPdmBadgeVisible;
            this.isShellButtonVisible = isShellButtonVisible;
            this.diskText = diskText;
            this.netText = netText;
            this.intentNode = intentNode;
            this.intentServerId = intentServerId;
        }
    }

    /**
     * Evaluates the complete presentation and binding state for a host node card.
     * This logic is identical to that executed in NodesView.bindNode and DashboardView.bindNode,
     * ensuring single-source-of-truth consistency.
     */
    public static NodeCardBindingState evaluateNodeBinding(NodeStatus node, ServerConfig server) {
        if (node == null) {
            return null;
        }
        boolean isPdm = isPdm(server);
        boolean online = node.isOnline();

        String displayName = node.getDisplayName();
        String metaText = formatNodeMeta(node);
        String statusText = online ? "ONLINE" : "OFFLINE";
        boolean isOfflineMsgVisible = !online;
        boolean isMetricsLayoutVisible = online;

        // PDM Shell and badge visibility evaluated regardless of online/offline
        boolean isPdmBadgeVisible = isPdm;
        boolean isShellButtonVisible = !isPdm && online;

        String diskText = online ? formatHostDisk(node, isPdm) : null;
        String netText = online ? formatHostNet(node, isPdm) : null;

        // Intent always preserves full internal node identity
        String intentNode = node.node;
        String intentServerId = node.serverId;

        return new NodeCardBindingState(
                displayName, statusText, metaText,
                online, isOfflineMsgVisible, isMetricsLayoutVisible,
                isPdmBadgeVisible, isShellButtonVisible,
                diskText, netText,
                intentNode, intentServerId);
    }

    /**
     * Clamps percentage value to [0.0, 100.0] and safely handles NaN/Infinity.
     * Positive infinity/overflow saturates honestly to 100.0%, negative/negative infinity to 0.0%,
     * and NaN to 0.0%.
     */
    public static double clampPercent(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        if (value <= 0.0) return 0.0;
        if (value >= 100.0 || Double.isInfinite(value)) {
            return 100.0;
        }
        return value;
    }

    /**
     * Formats compact metrics string for host rows on Overview.
     */
    public static String formatHostRowMetrics(NodeStatus node) {
        if (node == null || !node.isOnline()) {
            return "Unreachable / Intentionally powered down";
        }
        int cpuPct = (int) Math.round(!Double.isNaN(node.cpuUsage) ? node.cpuUsage * 100.0 : 0.0);
        String ramUsed = node.memUsed >= 0 ? JsonUtil.formatBytes(node.memUsed) : "—";
        String ramTotal = node.memTotal > 0 ? JsonUtil.formatBytes(node.memTotal) : "—";
        return "CPU: " + cpuPct + "% \u2022 RAM: " + ramUsed + " / " + ramTotal;
    }

    /**
     * Formats accessibility content description for compact host rows.
     */
    public static String formatHostRowContentDescription(NodeStatus node) {
        if (node == null) return "";
        StringBuilder sb = new StringBuilder("Host ");
        sb.append(node.getDisplayName());
        if (node.hasSeparateRemote()) {
            sb.append(", Remote ").append(node.getRemoteName());
        }
        if (node.isOnline()) {
            int cpuPct = (int) Math.round(!Double.isNaN(node.cpuUsage) ? node.cpuUsage * 100.0 : 0.0);
            sb.append(", Online, CPU ").append(cpuPct).append(" percent");
            if (node.memUsed >= 0 && node.memTotal > 0) {
                sb.append(", RAM ").append(JsonUtil.formatBytes(node.memUsed))
                  .append(" of ").append(JsonUtil.formatBytes(node.memTotal));
            }
        } else {
            sb.append(", Offline, Unreachable or intentionally powered down");
        }
        return sb.toString();
    }

    /**
     * Summary model representing aggregated fleet capacity metrics.
     */
    public static final class FleetCapacitySummary {
        public final int totalNodes;
        public final int onlineNodes;
        public final int offlineNodes;
        public final int totalCores;
        public final int reportedCores;
        public final double cpuPercent;
        public final boolean isCpuAvailable;
        public final String cpuCenterText;
        public final String cpuCapacityText;
        public final String cpuContentDescription;

        public final long totalRamUsed;
        public final long totalRamMax;
        public final double ramPercent;
        public final boolean isRamAvailable;
        public final String ramCenterText;
        public final String ramCapacityText;
        public final String ramContentDescription;

        public final int runningGuests;
        public final int stoppedGuests;
        public final String guestSummaryText;
        public final String guestContentDescription;

        public final String hostsHeaderTitle;

        public FleetCapacitySummary(
                int totalNodes, int onlineNodes, int offlineNodes,
                int totalCores, int reportedCores,
                double cpuPercent, boolean isCpuAvailable,
                String cpuCenterText, String cpuCapacityText, String cpuContentDescription,
                long totalRamUsed, long totalRamMax,
                double ramPercent, boolean isRamAvailable,
                String ramCenterText, String ramCapacityText, String ramContentDescription,
                int runningGuests, int stoppedGuests,
                String guestSummaryText, String guestContentDescription,
                String hostsHeaderTitle) {
            this.totalNodes = totalNodes;
            this.onlineNodes = onlineNodes;
            this.offlineNodes = offlineNodes;
            this.totalCores = totalCores;
            this.reportedCores = reportedCores;
            this.cpuPercent = cpuPercent;
            this.isCpuAvailable = isCpuAvailable;
            this.cpuCenterText = cpuCenterText;
            this.cpuCapacityText = cpuCapacityText;
            this.cpuContentDescription = cpuContentDescription;
            this.totalRamUsed = totalRamUsed;
            this.totalRamMax = totalRamMax;
            this.ramPercent = ramPercent;
            this.isRamAvailable = isRamAvailable;
            this.ramCenterText = ramCenterText;
            this.ramCapacityText = ramCapacityText;
            this.ramContentDescription = ramContentDescription;
            this.runningGuests = runningGuests;
            this.stoppedGuests = stoppedGuests;
            this.guestSummaryText = guestSummaryText;
            this.guestContentDescription = guestContentDescription;
            this.hostsHeaderTitle = hostsHeaderTitle;
        }
    }

    /**
     * Encapsulates the expected binding state of a compact host row in DashboardView.
     */
    public static final class CompactHostRowBindingState {
        public final String displayName;
        public final String remoteName;
        public final boolean hasRemote;
        public final String statusText;
        public final boolean isOnline;
        public final String metricsText;
        public final String contentDescription;
        public final boolean isPdmBadgeVisible;
        public final boolean isShellButtonVisible;
        public final String intentNode;
        public final String intentServerId;

        public CompactHostRowBindingState(
                String displayName, String remoteName, boolean hasRemote,
                String statusText, boolean isOnline,
                String metricsText, String contentDescription,
                boolean isPdmBadgeVisible, boolean isShellButtonVisible,
                String intentNode, String intentServerId) {
            this.displayName = displayName;
            this.remoteName = remoteName;
            this.hasRemote = hasRemote;
            this.statusText = statusText;
            this.isOnline = isOnline;
            this.metricsText = metricsText;
            this.contentDescription = contentDescription;
            this.isPdmBadgeVisible = isPdmBadgeVisible;
            this.isShellButtonVisible = isShellButtonVisible;
            this.intentNode = intentNode;
            this.intentServerId = intentServerId;
        }
    }

    /**
     * Evaluates the binding state for a compact host row on Overview.
     */
    public static CompactHostRowBindingState evaluateCompactHostRowBinding(NodeStatus node, ServerConfig server) {
        if (node == null) return null;
        boolean isPdm = isPdm(server);
        boolean online = node.isOnline();

        String displayName = node.getDisplayName();
        String remoteName = node.getRemoteName();
        boolean hasRemote = node.hasSeparateRemote();
        String statusText = online ? "ONLINE" : "OFFLINE";
        String metricsText = formatHostRowMetrics(node);
        String contentDesc = formatHostRowContentDescription(node);

        boolean isPdmBadgeVisible = isPdm;
        boolean isShellButtonVisible = !isPdm && online;

        String intentNode = node.node;
        String intentServerId = node.serverId;

        return new CompactHostRowBindingState(
                displayName, remoteName, hasRemote,
                statusText, online,
                metricsText, contentDesc,
                isPdmBadgeVisible, isShellButtonVisible,
                intentNode, intentServerId);
    }

    /**
     * Aggregates fleet capacity metrics safely with capacity-weighted CPU, host-only RAM
     * (strictly preventing double-counting with guest allocations), and safe handling of
     * NaN, infinity, and zero denominators.
     */
    public static FleetCapacitySummary calculateFleetCapacity(
            java.util.List<NodeStatus> nodes, java.util.List<GuestInfo> guests) {
        int totalNodes = nodes != null ? nodes.size() : 0;
        int onlineNodes = 0;
        int offlineNodes = 0;
        int totalCores = 0;
        int reportedCores = 0;
        double weightedCpuSum = 0.0;
        long totalRamUsed = 0L;
        long totalRamMax = 0L;

        if (nodes != null) {
            for (NodeStatus n : nodes) {
                if (n.isOnline()) {
                    onlineNodes++;
                    // Zero cores is NOT fabricated into 1. Only actual positive cores count towards capacity.
                    if (n.cpuCount > 0) {
                        totalCores += n.cpuCount;
                        if (!Double.isNaN(n.cpuUsage) && n.cpuUsage >= 0.0) {
                            reportedCores += n.cpuCount;
                            if (Double.isInfinite(n.cpuUsage) || n.cpuUsage > 100.0) {
                                weightedCpuSum = Double.POSITIVE_INFINITY;
                            } else {
                                double nodeWeighted = n.cpuUsage * n.cpuCount;
                                if (Double.isInfinite(nodeWeighted) || (Double.MAX_VALUE - weightedCpuSum) < nodeWeighted) {
                                    weightedCpuSum = Double.POSITIVE_INFINITY;
                                } else {
                                    weightedCpuSum += nodeWeighted;
                                }
                            }
                        }
                    }
                    if (n.memUsed >= 0 && n.memTotal > 0) {
                        totalRamUsed += n.memUsed;
                        totalRamMax += n.memTotal;
                    }
                } else {
                    offlineNodes++;
                }
            }
        }

        boolean isCpuAvailable = onlineNodes > 0 && reportedCores > 0 && totalCores > 0;
        double cpuPct = 0.0;
        String cpuCenterText;
        String cpuCapacityText;
        String cpuContentDesc;

        if (isCpuAvailable) {
            if (Double.isInfinite(weightedCpuSum)) {
                // Honest bounded saturation on positive overflow / infinity
                cpuPct = 100.0;
            } else {
                cpuPct = clampPercent((weightedCpuSum / reportedCores) * 100.0);
            }

            if (Double.isNaN(cpuPct)) {
                isCpuAvailable = false;
                cpuCenterText = "Unavailable";
                cpuCapacityText = totalCores > 0 ? (totalCores + " vCPUs") : "Unavailable";
                cpuContentDesc = "Fleet CPU usage: Unavailable";
            } else {
                cpuCenterText = String.format(java.util.Locale.US, "%.1f%%", cpuPct);
                cpuCapacityText = totalCores + " vCPUs";
                cpuContentDesc = String.format(java.util.Locale.US,
                        "Fleet CPU usage: %.1f percent, %d vCPUs", cpuPct, totalCores);
            }
        } else {
            cpuCenterText = "Unavailable";
            cpuCapacityText = totalCores > 0 ? (totalCores + " vCPUs") : "Unavailable";
            cpuContentDesc = "Fleet CPU usage: Unavailable";
        }

        boolean isRamAvailable = onlineNodes > 0 && totalRamMax > 0;
        double ramPct = 0.0;
        String ramCenterText;
        String ramCapacityText;
        String ramContentDesc;

        if (isRamAvailable) {
            if (totalRamUsed > totalRamMax) {
                // Saturated RAM (overcommitted or swap overflow)
                ramPct = 100.0;
            } else {
                ramPct = clampPercent(((double) totalRamUsed / totalRamMax) * 100.0);
            }

            if (Double.isNaN(ramPct)) {
                isRamAvailable = false;
                ramCenterText = "Unavailable";
                ramCapacityText = "Unavailable";
                ramContentDesc = "Fleet RAM usage: Unavailable";
            } else {
                ramCenterText = String.format(java.util.Locale.US, "%.0f%%", ramPct);
                ramCapacityText = JsonUtil.formatBytes(totalRamUsed) + " / " + JsonUtil.formatBytes(totalRamMax);
                ramContentDesc = String.format(java.util.Locale.US,
                        "Fleet RAM usage: %.0f percent, %s used of %s total",
                        ramPct, JsonUtil.formatBytes(totalRamUsed), JsonUtil.formatBytes(totalRamMax));
            }
        } else {
            ramCenterText = "Unavailable";
            ramCapacityText = "Unavailable";
            ramContentDesc = "Fleet RAM usage: Unavailable";
        }

        int runningGuests = 0;
        int stoppedGuests = 0;
        if (guests != null) {
            for (GuestInfo g : guests) {
                if (g.state == GuestInfo.RunState.RUNNING) {
                    runningGuests++;
                } else if (g.state == GuestInfo.RunState.STOPPED) {
                    stoppedGuests++;
                }
            }
        }

        String guestSummaryText = "GUESTS: " + runningGuests + " running \u2022 " + stoppedGuests + " stopped";
        String guestContentDesc = "Fleet Guests: " + runningGuests + " running, " + stoppedGuests + " stopped";

        String hostsHeaderTitle;
        if (totalNodes > 0) {
            hostsHeaderTitle = "HOSTS (" + onlineNodes + "/" + totalNodes + " online)";
        } else {
            hostsHeaderTitle = "HOSTS (0 online)";
        }

        return new FleetCapacitySummary(
                totalNodes, onlineNodes, offlineNodes,
                totalCores, reportedCores,
                cpuPct, isCpuAvailable, cpuCenterText, cpuCapacityText, cpuContentDesc,
                totalRamUsed, totalRamMax,
                ramPct, isRamAvailable, ramCenterText, ramCapacityText, ramContentDesc,
                runningGuests, stoppedGuests,
                guestSummaryText, guestContentDesc,
                hostsHeaderTitle);
    }
}
