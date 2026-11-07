// SPDX-License-Identifier: GPL-3.0-or-later
// PVE Pocket — UI Polish Presentation & Formatting Policy
package com.homelabpocket.ui;

import com.homelabpocket.model.GuestInfo;
import com.homelabpocket.model.NodeStatus;
import com.homelabpocket.model.ServerConfig;
import com.homelabpocket.model.StoragePool;
import com.homelabpocket.model.TaskResult;
import com.homelabpocket.util.JsonUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
            String label = (node.status == NodeStatus.Status.UNKNOWN)
                    ? "Unreachable / Unknown"
                    : "Unreachable / Intentionally powered down";
            if (node.hasSeparateRemote()) {
                return label + " \u2022 [Remote: " + node.getRemoteName() + "]";
            }
            return label;
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
     * Encapsulates the expected presentation and view-binding state for guest telemetry metrics
     * and power control authorization in VmDetailActivity.
     */
    public static final class GuestMetricsBindingState {
        public final boolean isStale;
        public final boolean hasData;
        public final String statusText;
        public final String cpuText;
        public final int cpuPercent;
        public final String ramText;
        public final int ramPercent;
        public final String diskText;
        public final String uptimeText;
        public final boolean isPowerCardVisible;
        public final boolean isPowerControlsEnabled;
        public final boolean isTerminalVisible;
        public final boolean isActionConfirmationAllowed;

        public GuestMetricsBindingState(
                boolean isStale,
                boolean hasData,
                String statusText,
                String cpuText,
                int cpuPercent,
                String ramText,
                int ramPercent,
                String diskText,
                String uptimeText,
                boolean isPowerCardVisible,
                boolean isPowerControlsEnabled,
                boolean isTerminalVisible,
                boolean isActionConfirmationAllowed) {
            this.isStale = isStale;
            this.hasData = hasData;
            this.statusText = statusText;
            this.cpuText = cpuText;
            this.cpuPercent = cpuPercent;
            this.ramText = ramText;
            this.ramPercent = ramPercent;
            this.diskText = diskText;
            this.uptimeText = uptimeText;
            this.isPowerCardVisible = isPowerCardVisible;
            this.isPowerControlsEnabled = isPowerControlsEnabled;
            this.isTerminalVisible = isTerminalVisible;
            this.isActionConfirmationAllowed = isActionConfirmationAllowed;
        }
    }

    /**
     * Evaluates the view-binding state for guest telemetry metrics and power control availability
     * in VmDetailActivity.
     * Retains cached CPU, RAM, Disk, and Uptime metrics clearly marked stale on telemetry failure,
     * while strictly disabling power controls and preventing action confirmation until fresh revalidation.
     */
    public static GuestMetricsBindingState evaluateGuestMetricsBinding(
            GuestInfo guest, boolean isStale, ServerConfig server, String errorDetail) {
        if (guest == null) {
            return new GuestMetricsBindingState(
                    isStale,
                    false,
                    "OFFLINE / UNKNOWN",
                    "CPU: - (telemetry unavailable)",
                    0,
                    "RAM: - (telemetry unavailable)",
                    0,
                    "Disk: - (telemetry unavailable)",
                    "State: Target unreachable, offline, or unknown",
                    false,
                    false,
                    false,
                    false
            );
        }

        boolean isPdm = isPdm(server);
        int cpuPct = (int) Math.round(!Double.isNaN(guest.cpuUsage) ? guest.cpuUsage * 100 : 0);
        long ramPct = guest.memTotal > 0 ? (guest.memUsed * 100 / guest.memTotal) : 0;

        if (isStale) {
            String cpuText = "CPU: " + cpuPct + "% (stale)";
            String ramText = "RAM: " + JsonUtil.formatBytes(guest.memUsed) + " / " + JsonUtil.formatBytes(guest.memTotal) + " (stale)";
            String diskText = formatGuestDisk(guest, isPdm) + " (stale)";
            String uptimeText = (errorDetail != null && !errorDetail.isEmpty())
                    ? "State: Telemetry refresh failed (" + errorDetail + " - cached data)"
                    : "State: Telemetry refresh failed (cached data)";

            return new GuestMetricsBindingState(
                    true,
                    true,
                    "UNKNOWN",
                    cpuText,
                    cpuPct,
                    ramText,
                    (int) ramPct,
                    diskText,
                    uptimeText,
                    false, // Power card hidden
                    false, // Power controls disabled
                    false, // Terminal hidden
                    false  // Action confirmation strictly forbidden
            );
        }

        boolean isRunning = (guest.state == GuestInfo.RunState.RUNNING);
        boolean isStopped = (guest.state == GuestInfo.RunState.STOPPED);

        if (isRunning) {
            String cpuText = "CPU: " + cpuPct + "%";
            String ramText = "RAM: " + JsonUtil.formatBytes(guest.memUsed) + " / " + JsonUtil.formatBytes(guest.memTotal);
            String diskText = formatGuestDisk(guest, isPdm);
            String uptimeText = "Uptime: " + JsonUtil.formatUptime(guest.uptime);
            boolean isTerminal = !isPdm;

            return new GuestMetricsBindingState(
                    false,
                    true,
                    "RUNNING",
                    cpuText,
                    cpuPct,
                    ramText,
                    (int) ramPct,
                    diskText,
                    uptimeText,
                    true,
                    true,
                    isTerminal,
                    true
            );
        } else if (isStopped) {
            String cpuText = "CPU: -";
            String ramText = "RAM: - / " + JsonUtil.formatBytes(guest.memTotal);
            String diskText = formatGuestDisk(guest, isPdm);
            String uptimeText = "State: Powered down";

            return new GuestMetricsBindingState(
                    false,
                    true,
                    "STOPPED",
                    cpuText,
                    0,
                    ramText,
                    0,
                    diskText,
                    uptimeText,
                    true,
                    true,
                    false,
                    true
            );
        } else {
            String cpuText = "CPU: -";
            String ramText = "RAM: - / " + JsonUtil.formatBytes(guest.memTotal);
            String diskText = formatGuestDisk(guest, isPdm);
            String uptimeText = "State: Target unreachable, offline, or unknown";

            return new GuestMetricsBindingState(
                    false,
                    true,
                    "UNKNOWN",
                    cpuText,
                    0,
                    ramText,
                    0,
                    diskText,
                    uptimeText,
                    false,
                    false,
                    false,
                    false
            );
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
     * Encapsulates the expected binding state of hardware telemetry metrics
     * (CPU, RAM, Root FS, Uptime, Release) in NodeDetailActivity.
     * Provides testable view-binding verification in headless unit tests.
     */
    public static final class NodeMetricsBindingState {
        public final NodeStatus node;
        public final boolean isStale;
        public final boolean hasData;
        public final String cpuText;
        public final int cpuPercent;
        public final String ramText;
        public final int ramPercent;
        public final String diskText;
        public final boolean isDiskBarVisible;
        public final int diskPercent;
        public final String uptimeText;
        public final String releaseText;

        public NodeMetricsBindingState(
                NodeStatus node,
                boolean isStale,
                boolean hasData,
                String cpuText,
                int cpuPercent,
                String ramText,
                int ramPercent,
                String diskText,
                boolean isDiskBarVisible,
                int diskPercent,
                String uptimeText,
                String releaseText) {
            this.node = node;
            this.isStale = isStale;
            this.hasData = hasData;
            this.cpuText = cpuText;
            this.cpuPercent = cpuPercent;
            this.ramText = ramText;
            this.ramPercent = ramPercent;
            this.diskText = diskText;
            this.isDiskBarVisible = isDiskBarVisible;
            this.diskPercent = diskPercent;
            this.uptimeText = uptimeText;
            this.releaseText = releaseText;
        }

        public NodeMetricsBindingState(
                boolean isStale,
                boolean hasData,
                String cpuText,
                int cpuPercent,
                String ramText,
                int ramPercent,
                String diskText,
                boolean isDiskBarVisible,
                int diskPercent,
                String uptimeText,
                String releaseText) {
            this(null, isStale, hasData, cpuText, cpuPercent, ramText, ramPercent, diskText, isDiskBarVisible, diskPercent, uptimeText, releaseText);
        }
    }

    /**
     * Evaluates the view-binding state for hardware telemetry metrics in NodeDetailActivity.
     * Clearly marks retained CPU/RAM/uptime as stale on failed status and clears only on successful bind.
     */
    public static NodeMetricsBindingState evaluateNodeMetricsBinding(NodeStatus node, boolean isStale, ServerConfig server) {
        if (node == null || !node.isOnline() || node.status == NodeStatus.Status.UNKNOWN || node.status == NodeStatus.Status.OFFLINE) {
            boolean isOffline = (node != null && node.status == NodeStatus.Status.OFFLINE);
            String reason = isOffline ? "host offline" : "telemetry unavailable";
            return new NodeMetricsBindingState(
                    node,
                    isStale,
                    false,
                    "CPU: - (" + reason + ")",
                    0,
                    "RAM: - (" + reason + ")",
                    0,
                    "Root FS (/): - (" + reason + ")",
                    false,
                    0,
                    "Uptime: - (" + reason + ")",
                    "Release: " + (node != null && node.pveVersion != null ? node.pveVersion : "-")
            );
        }

        int cpuPct = (int) Math.round(!Double.isNaN(node.cpuUsage) ? node.cpuUsage * 100 : 0);
        String cpuText = "CPU: " + cpuPct + "% (" + node.cpuCount + " cores)" + (isStale ? " (stale)" : "");

        long ramPct = node.memTotal > 0 ? (node.memUsed * 100 / node.memTotal) : 0;
        String ramText = "RAM: " + JsonUtil.formatBytes(node.memUsed) + " / " + JsonUtil.formatBytes(node.memTotal) + (isStale ? " (stale)" : "");

        boolean isPdm = isPdm(server);
        String diskText = formatNodeRootFs(node, isPdm) + (isStale ? " (stale)" : "");
        boolean showBar = isRootFsBarVisible(node);
        int diskPct = (showBar && node.diskTotal > 0) ? (int) ((node.diskUsed * 100L) / node.diskTotal) : 0;

        String uptimeText = "Uptime: " + JsonUtil.formatUptime(node.uptime) + (isStale ? " (stale - showing cached data)" : "");
        String releaseText = "Release: " + (node.pveVersion != null ? node.pveVersion : "Proxmox VE");

        return new NodeMetricsBindingState(
                node,
                isStale,
                true,
                cpuText,
                cpuPct,
                ramText,
                (int) ramPct,
                diskText,
                showBar,
                diskPct,
                uptimeText,
                releaseText
        );
    }

    /**
     * Encapsulates the expected binding state of storage pools in NodeDetailActivity.
     * Provides testable view-binding verification and cache provenance in headless unit tests.
     */
    public static final class NodeStorageBindingState {
        public final boolean isLoading;
        public final List<StoragePool> cachedPools;
        public final String errorMessage;
        public final String emptyMessage;
        public final List<StoragePool> displayedPools;

        public NodeStorageBindingState(boolean isLoading, List<StoragePool> cachedPools,
                                       String errorMessage, String emptyMessage,
                                       List<StoragePool> displayedPools) {
            this.isLoading = isLoading;
            this.cachedPools = cachedPools != null ? Collections.unmodifiableList(cachedPools) : Collections.emptyList();
            this.errorMessage = errorMessage;
            this.emptyMessage = emptyMessage;
            this.displayedPools = displayedPools != null ? Collections.unmodifiableList(displayedPools) : Collections.emptyList();
        }
    }

    /**
     * Evaluates storage pool view-binding state in NodeDetailActivity.
     * On successful fetch (error == null), updates the cache authoritative even if empty.
     * On error, preserves existing cached pools (displaying cached data indicator) and clears loading.
     */
    public static NodeStorageBindingState evaluateNodeStorageBinding(
            List<StoragePool> pools, String error, List<StoragePool> currentCache) {
        List<StoragePool> newCache;
        if (error == null) {
            newCache = (pools != null) ? new ArrayList<>(pools) : new ArrayList<>();
        } else {
            newCache = (pools != null && !pools.isEmpty()) ? new ArrayList<>(pools) : (currentCache != null ? new ArrayList<>(currentCache) : new ArrayList<>());
        }

        String errorMsg = null;
        if (error != null) {
            errorMsg = "Storage Pools: " + error + (newCache.isEmpty() ? "" : " (showing cached data)");
        }

        List<StoragePool> displayPools = (error == null) ? newCache : (!newCache.isEmpty() ? newCache : (pools != null ? pools : Collections.emptyList()));
        String emptyMsg = null;
        if (displayPools.isEmpty() && error == null) {
            emptyMsg = "No storage pools configured on this host.";
        }

        return new NodeStorageBindingState(false, newCache, errorMsg, emptyMsg, displayPools);
    }

    /**
     * Encapsulates the expected binding state of guest instances in NodeDetailActivity.
     */
    public static final class NodeGuestsBindingState {
        public final boolean isLoading;
        public final List<GuestInfo> cachedGuests;
        public final boolean isOffline;
        public final String offlineMessage;
        public final String errorMessage;
        public final String emptyMessage;
        public final List<GuestInfo> displayedGuests;

        public NodeGuestsBindingState(boolean isLoading, List<GuestInfo> cachedGuests,
                                      boolean isOffline, String offlineMessage,
                                      String errorMessage, String emptyMessage,
                                      List<GuestInfo> displayedGuests) {
            this.isLoading = isLoading;
            this.cachedGuests = cachedGuests != null ? Collections.unmodifiableList(cachedGuests) : Collections.emptyList();
            this.isOffline = isOffline;
            this.offlineMessage = offlineMessage;
            this.errorMessage = errorMessage;
            this.emptyMessage = emptyMessage;
            this.displayedGuests = displayedGuests != null ? Collections.unmodifiableList(displayedGuests) : Collections.emptyList();
        }
    }

    /**
     * Evaluates guest instances view-binding state in NodeDetailActivity.
     */
    public static NodeGuestsBindingState evaluateNodeGuestsBinding(
            List<GuestInfo> guests, String error, boolean isOffline, List<GuestInfo> currentCache) {
        List<GuestInfo> newCache;
        if (error == null && !isOffline) {
            newCache = (guests != null) ? new ArrayList<>(guests) : new ArrayList<>();
        } else {
            newCache = (guests != null && !guests.isEmpty()) ? new ArrayList<>(guests) : (currentCache != null ? new ArrayList<>(currentCache) : new ArrayList<>());
        }

        String offlineMsg = null;
        if (isOffline) {
            offlineMsg = "Host is offline or unreachable. Hosted guest instances cannot be inspected.";
        }

        String errorMsg = null;
        if (!isOffline && error != null) {
            errorMsg = error.contains("403") || error.toLowerCase().contains("permission")
                    ? "Guests: Insufficient permissions to inspect guest instances."
                    : "Guests: " + error + (!newCache.isEmpty() ? " (showing cached data)" : "");
        }

        List<GuestInfo> displayGuests = isOffline ? Collections.emptyList()
                : (error == null ? newCache : (!newCache.isEmpty() ? newCache : (guests != null ? guests : Collections.emptyList())));

        String emptyMsg = null;
        if (!isOffline && error == null && displayGuests.isEmpty()) {
            emptyMsg = "No guest instances hosted on this host.";
        }

        return new NodeGuestsBindingState(false, newCache, isOffline, offlineMsg, errorMsg, emptyMsg, displayGuests);
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
        String statusText;
        if (online) {
            statusText = "ONLINE";
        } else if (node.status == NodeStatus.Status.UNKNOWN) {
            statusText = "UNKNOWN";
        } else {
            statusText = "OFFLINE";
        }
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
        if (node == null) {
            return "Unreachable / Unknown";
        }
        if (node.isOnline()) {
            int cpuPct = (int) Math.round(!Double.isNaN(node.cpuUsage) ? node.cpuUsage * 100.0 : 0.0);
            String ramUsed = node.memUsed >= 0 ? JsonUtil.formatBytes(node.memUsed) : "—";
            String ramTotal = node.memTotal > 0 ? JsonUtil.formatBytes(node.memTotal) : "—";
            return "CPU: " + cpuPct + "% \u2022 RAM: " + ramUsed + " / " + ramTotal;
        }
        if (node.status == NodeStatus.Status.UNKNOWN) {
            if (node.memTotal > 0 || node.cpuCount > 0) {
                int cpuPct = (int) Math.round(!Double.isNaN(node.cpuUsage) ? node.cpuUsage * 100.0 : 0.0);
                String ramUsed = node.memUsed >= 0 ? JsonUtil.formatBytes(node.memUsed) : "—";
                String ramTotal = node.memTotal > 0 ? JsonUtil.formatBytes(node.memTotal) : "—";
                return "CPU: " + cpuPct + "% \u2022 RAM: " + ramUsed + " / " + ramTotal + " (stale)";
            }
            return "Unreachable / Unknown";
        }
        return "Unreachable / Intentionally powered down";
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
        public final int unknownRemotes;
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
        public final int unknownGuests;
        public final String guestSummaryText;
        public final String guestContentDescription;

        public final String hostsHeaderTitle;
        public final boolean isPartialCoverage;

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
            this(totalNodes, onlineNodes, offlineNodes,
                    totalCores, reportedCores,
                    cpuPercent, isCpuAvailable,
                    cpuCenterText, cpuCapacityText, cpuContentDescription,
                    totalRamUsed, totalRamMax,
                    ramPercent, isRamAvailable,
                    ramCenterText, ramCapacityText, ramContentDescription,
                    runningGuests, stoppedGuests, 0,
                    guestSummaryText, guestContentDescription,
                    hostsHeaderTitle,
                    (offlineNodes > 0 && onlineNodes > 0));
        }

        public FleetCapacitySummary(
                int totalNodes, int onlineNodes, int offlineNodes,
                int totalCores, int reportedCores,
                double cpuPercent, boolean isCpuAvailable,
                String cpuCenterText, String cpuCapacityText, String cpuContentDescription,
                long totalRamUsed, long totalRamMax,
                double ramPercent, boolean isRamAvailable,
                String ramCenterText, String ramCapacityText, String ramContentDescription,
                int runningGuests, int stoppedGuests, int unknownGuests,
                String guestSummaryText, String guestContentDescription,
                String hostsHeaderTitle) {
            this(totalNodes, onlineNodes, offlineNodes,
                    totalCores, reportedCores,
                    cpuPercent, isCpuAvailable,
                    cpuCenterText, cpuCapacityText, cpuContentDescription,
                    totalRamUsed, totalRamMax,
                    ramPercent, isRamAvailable,
                    ramCenterText, ramCapacityText, ramContentDescription,
                    runningGuests, stoppedGuests, unknownGuests,
                    guestSummaryText, guestContentDescription,
                    hostsHeaderTitle,
                    (offlineNodes > 0 && onlineNodes > 0));
        }

        public FleetCapacitySummary(
                int totalNodes, int onlineNodes, int offlineNodes,
                int totalCores, int reportedCores,
                double cpuPercent, boolean isCpuAvailable,
                String cpuCenterText, String cpuCapacityText, String cpuContentDescription,
                long totalRamUsed, long totalRamMax,
                double ramPercent, boolean isRamAvailable,
                String ramCenterText, String ramCapacityText, String ramContentDescription,
                int runningGuests, int stoppedGuests, int unknownGuests,
                String guestSummaryText, String guestContentDescription,
                String hostsHeaderTitle,
                boolean isPartialCoverage) {
            this(totalNodes, onlineNodes, offlineNodes, 0,
                    totalCores, reportedCores,
                    cpuPercent, isCpuAvailable,
                    cpuCenterText, cpuCapacityText, cpuContentDescription,
                    totalRamUsed, totalRamMax,
                    ramPercent, isRamAvailable,
                    ramCenterText, ramCapacityText, ramContentDescription,
                    runningGuests, stoppedGuests, unknownGuests,
                    guestSummaryText, guestContentDescription,
                    hostsHeaderTitle,
                    isPartialCoverage);
        }

        public FleetCapacitySummary(
                int totalNodes, int onlineNodes, int offlineNodes, int unknownRemotes,
                int totalCores, int reportedCores,
                double cpuPercent, boolean isCpuAvailable,
                String cpuCenterText, String cpuCapacityText, String cpuContentDescription,
                long totalRamUsed, long totalRamMax,
                double ramPercent, boolean isRamAvailable,
                String ramCenterText, String ramCapacityText, String ramContentDescription,
                int runningGuests, int stoppedGuests, int unknownGuests,
                String guestSummaryText, String guestContentDescription,
                String hostsHeaderTitle,
                boolean isPartialCoverage) {
            this.totalNodes = totalNodes;
            this.onlineNodes = onlineNodes;
            this.offlineNodes = offlineNodes;
            this.unknownRemotes = unknownRemotes;
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
            this.unknownGuests = unknownGuests;
            this.guestSummaryText = guestSummaryText;
            this.guestContentDescription = guestContentDescription;
            this.hostsHeaderTitle = hostsHeaderTitle;
            this.isPartialCoverage = isPartialCoverage;
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
        String statusText;
        if (online) {
            statusText = "ONLINE";
        } else if (node.status == NodeStatus.Status.UNKNOWN) {
            statusText = "UNKNOWN";
        } else {
            statusText = "OFFLINE";
        }
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
        int provenTotalNodes = 0;
        int onlineNodes = 0;
        int offlineNodes = 0;
        int unknownRemotes = 0;
        int totalCores = 0;
        int reportedCores = 0;
        double weightedCpuSum = 0.0;
        long totalRamUsed = 0L;
        long totalRamMax = 0L;

        if (nodes != null) {
            for (NodeStatus n : nodes) {
                if (n.isRemotePlaceholder) {
                    unknownRemotes++;
                    continue;
                }
                provenTotalNodes++;
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

        boolean isPartialCoverage = (offlineNodes > 0 && onlineNodes > 0) || (unknownRemotes > 0 && onlineNodes > 0);
        String coverageSuffix = isPartialCoverage ? " (partial coverage)" : "";

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
                cpuCapacityText = totalCores > 0 ? (totalCores + " vCPUs" + coverageSuffix) : "Unavailable";
                cpuContentDesc = "Fleet CPU usage: Unavailable";
            } else {
                cpuCenterText = String.format(java.util.Locale.US, "%.1f%%", cpuPct);
                cpuCapacityText = totalCores + " vCPUs" + coverageSuffix;
                cpuContentDesc = String.format(java.util.Locale.US,
                        "Fleet CPU usage: %.1f percent, %d vCPUs%s", cpuPct, totalCores, coverageSuffix);
            }
        } else {
            cpuCenterText = "Unavailable";
            cpuCapacityText = (totalCores > 0 && onlineNodes > 0) ? (totalCores + " vCPUs" + coverageSuffix) : "Unavailable";
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
                ramCapacityText = JsonUtil.formatBytes(totalRamUsed) + " / " + JsonUtil.formatBytes(totalRamMax) + coverageSuffix;
                ramContentDesc = String.format(java.util.Locale.US,
                        "Fleet RAM usage: %.0f percent, %s used of %s total%s",
                        ramPct, JsonUtil.formatBytes(totalRamUsed), JsonUtil.formatBytes(totalRamMax), coverageSuffix);
            }
        } else {
            ramCenterText = "Unavailable";
            ramCapacityText = "Unavailable";
            ramContentDesc = "Fleet RAM usage: Unavailable";
        }

        int runningGuests = 0;
        int stoppedGuests = 0;
        int unknownGuests = 0;
        if (guests != null) {
            for (GuestInfo g : guests) {
                if (g.state == GuestInfo.RunState.RUNNING) {
                    runningGuests++;
                } else if (g.state == GuestInfo.RunState.STOPPED) {
                    stoppedGuests++;
                } else {
                    unknownGuests++;
                }
            }
        }

        String guestSummaryText;
        String guestContentDesc;
        if (onlineNodes == 0 && (offlineNodes > 0 || unknownRemotes > 0)) {
            guestSummaryText = "GUESTS: Unavailable";
            guestContentDesc = "Fleet Guests: Unavailable";
        } else if (isPartialCoverage) {
            if (unknownGuests > 0) {
                guestSummaryText = "GUESTS: " + runningGuests + " running \u2022 " + stoppedGuests + " stopped \u2022 " + unknownGuests + " unknown";
                guestContentDesc = "Fleet Guests: " + runningGuests + " running, " + stoppedGuests + " stopped, " + unknownGuests + " unknown";
            } else {
                guestSummaryText = "GUESTS: " + runningGuests + " running \u2022 " + stoppedGuests + " stopped (partial coverage)";
                guestContentDesc = "Fleet Guests: " + runningGuests + " running, " + stoppedGuests + " stopped, partial coverage";
            }
        } else {
            if (unknownGuests > 0) {
                guestSummaryText = "GUESTS: " + runningGuests + " running \u2022 " + stoppedGuests + " stopped \u2022 " + unknownGuests + " unknown";
                guestContentDesc = "Fleet Guests: " + runningGuests + " running, " + stoppedGuests + " stopped, " + unknownGuests + " unknown";
            } else {
                guestSummaryText = "GUESTS: " + runningGuests + " running \u2022 " + stoppedGuests + " stopped";
                guestContentDesc = "Fleet Guests: " + runningGuests + " running, " + stoppedGuests + " stopped";
            }
        }

        String hostsHeaderTitle;
        if (unknownRemotes > 0) {
            String remoteUnit = unknownRemotes == 1 ? "remote" : "remotes";
            if (provenTotalNodes > 0) {
                hostsHeaderTitle = "HOSTS (" + onlineNodes + " online \u2022 " + unknownRemotes + " " + remoteUnit + " unknown)";
            } else {
                hostsHeaderTitle = "HOSTS (0 online \u2022 " + unknownRemotes + " " + remoteUnit + " unknown)";
            }
        } else if (provenTotalNodes > 0) {
            hostsHeaderTitle = "HOSTS (" + onlineNodes + "/" + provenTotalNodes + " online)";
        } else {
            hostsHeaderTitle = "HOSTS (0 online)";
        }

        return new FleetCapacitySummary(
                provenTotalNodes, onlineNodes, offlineNodes, unknownRemotes,
                totalCores, reportedCores,
                cpuPct, isCpuAvailable, cpuCenterText, cpuCapacityText, cpuContentDesc,
                totalRamUsed, totalRamMax,
                ramPct, isRamAvailable, ramCenterText, ramCapacityText, ramContentDesc,
                runningGuests, stoppedGuests, unknownGuests,
                guestSummaryText, guestContentDesc,
                hostsHeaderTitle,
                isPartialCoverage);
    }

    // =========================================================================
    // Error Banner Presentation Helper (NodesView, GuestsView, DashboardView)
    // =========================================================================

    public static final class ErrorBannerState {
        public final boolean isVisible;
        public final boolean isStale;
        public final String bannerText;
        public final long lastSuccessfulUpdate;

        public ErrorBannerState(boolean isVisible, boolean isStale, String bannerText, long lastSuccessfulUpdate) {
            this.isVisible = isVisible;
            this.isStale = isStale;
            this.bannerText = bannerText;
            this.lastSuccessfulUpdate = lastSuccessfulUpdate;
        }
    }

    /**
     * Formats error banner text for fleet views.
     * When an error occurs and previously fetched data is retained on screen,
     * clearly labels the banner 'stale / showing cached data' so users understand
     * the displayed information may be stale (in partial failures, some data may be stale).
     */
    public static String formatErrorBanner(String error, boolean hasRetainedData) {
        return formatErrorBanner(error, hasRetainedData, false);
    }

    public static String formatErrorBanner(String error, boolean hasRetainedData, boolean hasFreshData) {
        if (error == null || error.trim().isEmpty()) {
            return "";
        }
        if (hasRetainedData) {
            if (hasFreshData) {
                return error + " (showing cached data for unreachable remotes)";
            }
            return error + " (stale / showing cached data)";
        }
        return error;
    }

    public static String formatErrorBanner(String error, boolean hasRetainedData, long lastSuccessfulUpdate) {
        return formatErrorBanner(error, hasRetainedData, false);
    }

    /**
     * Evaluates error banner presentation state for views (NodesView, GuestsView, DashboardView).
     * On error with retained data, produces a visible banner labeled 'stale / showing cached data'
     * or 'showing cached data for unreachable remotes' when fresh sibling data is present.
     * On successful refresh (error null or empty), produces an invisible banner state, cleanly clearing
     * the stale indicator.
     */
    public static ErrorBannerState evaluateErrorBanner(String error, boolean hasRetainedData, long lastSuccessfulUpdate) {
        return evaluateErrorBanner(error, hasRetainedData, lastSuccessfulUpdate, false);
    }

    public static ErrorBannerState evaluateErrorBanner(String error, boolean hasRetainedData, long lastSuccessfulUpdate, boolean hasFreshData) {
        if (error == null || error.trim().isEmpty()) {
            return new ErrorBannerState(false, false, "", lastSuccessfulUpdate);
        }
        String text = formatErrorBanner(error, hasRetainedData, hasFreshData);
        return new ErrorBannerState(true, hasRetainedData, text, lastSuccessfulUpdate);
    }

    public static ErrorBannerState evaluateErrorBanner(String error, boolean hasRetainedData) {
        return evaluateErrorBanner(error, hasRetainedData, 0L, false);
    }

    // =========================================================================
    // Fleet Data Cache & Provenance Policy (MainActivity)
    // =========================================================================

    /**
     * Computes the exact endpoint provenance signature for a configured server.
     * Binds identity, endpoint host/port, backend type, pinned cert fingerprint,
     * and verification flags so that any modification (including same-id endpoint replacement)
     * produces a distinct provenance signature.
     */
    public static String computeServerProvenance(ServerConfig config) {
        if (config == null) return "";
        return config.id + "|" + (config.name != null ? config.name : "") + "|" +
                config.host + ":" + config.port + "|" + config.type + "|" +
                (config.pinnedCertFingerprint != null ? config.pinnedCertFingerprint : "") + "|" +
                config.enforceCertPin + "|" + config.skipTlsVerify;
    }

    public static final class FleetCacheMergeResult {
        public final List<NodeStatus> nodes;
        public final List<GuestInfo> guests;
        public final Map<String, String> provenances;
        public final boolean hasFreshData;

        public FleetCacheMergeResult(List<NodeStatus> nodes, List<GuestInfo> guests, Map<String, String> provenances) {
            this(nodes, guests, provenances, false);
        }

        public FleetCacheMergeResult(List<NodeStatus> nodes, List<GuestInfo> guests, Map<String, String> provenances, boolean hasFreshData) {
            this.nodes = nodes != null ? Collections.unmodifiableList(new ArrayList<>(nodes)) : Collections.emptyList();
            this.guests = guests != null ? Collections.unmodifiableList(new ArrayList<>(guests)) : Collections.emptyList();
            this.provenances = provenances != null ? Collections.unmodifiableMap(new LinkedHashMap<>(provenances)) : Collections.emptyMap();
            this.hasFreshData = hasFreshData;
        }
    }

    /**
     * Filters previously cached nodes and guests to exact currently configured endpoint provenance.
     * Invalidate cached data from servers that have been deleted or replaced (including same-id replacement).
     */
    public static FleetCacheMergeResult filterCacheToConfiguredProvenance(
            List<NodeStatus> cachedNodes,
            List<GuestInfo> cachedGuests,
            Map<String, String> cachedProvenances,
            List<ServerConfig> configuredServers) {

        Map<String, String> configuredProvenances = new LinkedHashMap<>();
        if (configuredServers != null) {
            for (ServerConfig s : configuredServers) {
                if (s != null && s.id != null) {
                    configuredProvenances.put(s.id, computeServerProvenance(s));
                }
            }
        }

        List<NodeStatus> filteredNodes = new ArrayList<>();
        if (cachedNodes != null) {
            for (NodeStatus n : cachedNodes) {
                String serverId = n.serverId != null ? n.serverId : "";
                String currentProv = configuredProvenances.get(serverId);
                String cachedProv = cachedProvenances != null ? cachedProvenances.get(serverId) : null;
                if (currentProv != null && currentProv.equals(cachedProv)) {
                    filteredNodes.add(n);
                }
            }
        }

        List<GuestInfo> filteredGuests = new ArrayList<>();
        if (cachedGuests != null) {
            for (GuestInfo g : cachedGuests) {
                String serverId = g.serverId != null ? g.serverId : "";
                String currentProv = configuredProvenances.get(serverId);
                String cachedProv = cachedProvenances != null ? cachedProvenances.get(serverId) : null;
                if (currentProv != null && currentProv.equals(cachedProv)) {
                    filteredGuests.add(g);
                }
            }
        }

        Map<String, String> validProvenances = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : configuredProvenances.entrySet()) {
            String serverId = entry.getKey();
            if (cachedProvenances != null && entry.getValue().equals(cachedProvenances.get(serverId))) {
                validProvenances.put(serverId, entry.getValue());
            }
        }

        return new FleetCacheMergeResult(filteredNodes, filteredGuests, validProvenances);
    }

    /**
     * Merges fresh fetch results into the fleet cache while preserving cached data ONLY from
     * configured endpoints that failed and retain valid unchanged provenance.
     *
     * Rules:
     * 1. Authoritative Success (error == null): replaces cache with fresh results and authoritative empty sets.
     * 2. Deleted Server: any cached nodes/guests for servers absent from configuredServers are discarded.
     * 3. Same-ID Endpoint Replacement: if configured endpoint provenance differs from cached provenance,
     *    old cached data is invalidated and discarded.
     * 4. Failed Configured Server: if server remains configured with matching provenance and fetch failed,
     *    its previously cached data is preserved.
     * 5. Remote-scoped tracking: for PDM endpoints sharing a server config, healthy remotes supersede cache,
     *    failed remotes retain cached nodes/guests marked UNKNOWN / stale (no fake zero, no live aggregate contribution),
     *    and removed remotes are purged.
     */
    public static FleetCacheMergeResult mergeFleetData(
            List<NodeStatus> cachedNodes,
            List<GuestInfo> cachedGuests,
            Map<String, String> cachedProvenances,
            List<ServerConfig> configuredServers,
            List<NodeStatus> freshNodes,
            List<GuestInfo> freshGuests,
            String error,
            Set<String> successfulNodeServers,
            Set<String> successfulGuestServers,
            Set<String> successfulGuestNodeKeys) {
        return mergeFleetData(
                cachedNodes, cachedGuests, cachedProvenances, configuredServers,
                freshNodes, freshGuests, error,
                successfulNodeServers, successfulGuestServers, successfulGuestNodeKeys,
                Collections.emptySet(), Collections.emptySet(), Collections.emptyMap());
    }

    public static FleetCacheMergeResult mergeFleetData(
            List<NodeStatus> cachedNodes,
            List<GuestInfo> cachedGuests,
            Map<String, String> cachedProvenances,
            List<ServerConfig> configuredServers,
            List<NodeStatus> freshNodes,
            List<GuestInfo> freshGuests,
            String error,
            Set<String> successfulNodeServers,
            Set<String> successfulGuestServers,
            Set<String> successfulGuestNodeKeys,
            Set<String> successfulRemoteKeys,
            Set<String> failedRemoteKeys,
            Map<String, String> failedRemoteErrors) {

        Map<String, String> configuredProvenances = new LinkedHashMap<>();
        Map<String, ServerConfig> configuredServersMap = new LinkedHashMap<>();
        if (configuredServers != null) {
            for (ServerConfig s : configuredServers) {
                if (s != null && s.id != null) {
                    configuredProvenances.put(s.id, computeServerProvenance(s));
                    configuredServersMap.put(s.id, s);
                }
            }
        }

        // Complete success: all configured servers must be in successfulNodeServers,
        // no remote errors, and error is null
        boolean allServersNodeSucceeded = successfulNodeServers != null && successfulNodeServers.containsAll(configuredProvenances.keySet());
        if (error == null && (failedRemoteKeys == null || failedRemoteKeys.isEmpty()) && allServersNodeSucceeded) {
            List<NodeStatus> nodes = freshNodes != null ? new ArrayList<>(freshNodes) : new ArrayList<>();
            List<GuestInfo> guests = freshGuests != null ? new ArrayList<>(freshGuests) : new ArrayList<>();
            Map<String, String> provenances = new LinkedHashMap<>(configuredProvenances);
            return new FleetCacheMergeResult(nodes, guests, provenances, true);
        }

        Map<String, NodeStatus> mergedNodes = new LinkedHashMap<>();
        Set<String> retainedFailedRemoteKeys = new LinkedHashSet<>();

        if (cachedNodes != null) {
            for (NodeStatus oldNode : cachedNodes) {
                String serverId = oldNode.serverId != null ? oldNode.serverId : "";
                String currentProv = configuredProvenances.get(serverId);
                String cachedProv = cachedProvenances != null ? cachedProvenances.get(serverId) : null;

                boolean provenanceValid = currentProv != null && currentProv.equals(cachedProv);
                if (!provenanceValid) {
                    continue;
                }

                boolean serverNodeSucceeded = successfulNodeServers != null && successfulNodeServers.contains(serverId);
                if (!serverNodeSucceeded) {
                    // Whole server/endpoint failure: preserve cached node with explicit stale / UNKNOWN
                    // Preserves original fetchedAt, does NOT claim live online status or fresh totals
                    NodeStatus staleNode = new NodeStatus(
                            oldNode.node, oldNode.serverId, NodeStatus.Status.UNKNOWN,
                            oldNode.cpuUsage, oldNode.cpuCount,
                            oldNode.memUsed, oldNode.memTotal,
                            oldNode.diskUsed, oldNode.diskTotal,
                            oldNode.netIn, oldNode.netOut,
                            oldNode.pveVersion, oldNode.uptime,
                            oldNode.fetchedAt, oldNode.isRemotePlaceholder);
                    mergedNodes.put(serverId + "/" + (oldNode.node != null ? oldNode.node : ""), staleNode);
                    String remoteName = oldNode.getRemoteName();
                    if (remoteName.isEmpty() && oldNode.node != null) {
                        remoteName = oldNode.node;
                    }
                    if (!remoteName.isEmpty()) {
                        retainedFailedRemoteKeys.add(serverId + "/" + remoteName);
                    }
                    continue;
                }

                // Endpoint succeeded: remote inventory tracking is strictly scoped to this server
                ServerConfig sCfg = configuredServersMap.get(serverId);
                boolean isPdmServer = isPdm(sCfg);

                if (isPdmServer) {
                    String remoteName = oldNode.getRemoteName();
                    if (remoteName.isEmpty() && oldNode.node != null) {
                        remoteName = oldNode.node;
                    }
                    String remoteKey = serverId + "/" + remoteName;

                    if (failedRemoteKeys != null && failedRemoteKeys.contains(remoteKey)) {
                        NodeStatus staleNode = new NodeStatus(
                                oldNode.node, oldNode.serverId, NodeStatus.Status.UNKNOWN,
                                oldNode.cpuUsage, oldNode.cpuCount,
                                oldNode.memUsed, oldNode.memTotal,
                                oldNode.diskUsed, oldNode.diskTotal,
                                oldNode.netIn, oldNode.netOut,
                                oldNode.pveVersion, oldNode.uptime,
                                oldNode.fetchedAt, oldNode.isRemotePlaceholder);
                        mergedNodes.put(serverId + "/" + oldNode.node, staleNode);
                        retainedFailedRemoteKeys.add(remoteKey);
                    } else if (successfulRemoteKeys != null && successfulRemoteKeys.contains(remoteKey)) {
                        // Succeeded remote: freshNodes will supersede cache
                    } else {
                        // Removed remote or successful empty endpoint: authoritative purge
                    }
                } else {
                    // PVE server succeeded: freshNodes will supersede all cached nodes for this server
                }
            }
        }

        if (freshNodes != null) {
            for (NodeStatus newNode : freshNodes) {
                String serverId = newNode.serverId != null ? newNode.serverId : "";
                if (configuredProvenances.containsKey(serverId)) {
                    mergedNodes.put(serverId + "/" + (newNode.node != null ? newNode.node : ""), newNode);
                }
            }
        }

        if (failedRemoteKeys != null) {
            for (String failedKey : failedRemoteKeys) {
                if (!retainedFailedRemoteKeys.contains(failedKey)) {
                    int slash = failedKey.indexOf('/');
                    String serverId = slash > 0 ? failedKey.substring(0, slash) : failedKey;
                    String remote = slash >= 0 ? failedKey.substring(slash + 1) : failedKey;

                    if (configuredProvenances.containsKey(serverId)) {
                        NodeStatus placeholder = NodeStatus.remotePlaceholder(remote, serverId);
                        mergedNodes.put(serverId + "/" + remote, placeholder);
                    }
                }
            }
        }

        Map<String, GuestInfo> mergedGuests = new LinkedHashMap<>();
        if (cachedGuests != null) {
            for (GuestInfo oldGuest : cachedGuests) {
                String serverId = oldGuest.serverId != null ? oldGuest.serverId : "";
                String currentProv = configuredProvenances.get(serverId);
                String cachedProv = cachedProvenances != null ? cachedProvenances.get(serverId) : null;

                boolean provenanceValid = currentProv != null && currentProv.equals(cachedProv);
                if (!provenanceValid) {
                    continue;
                }

                String nodeKey = serverId + "/" + (oldGuest.node != null ? oldGuest.node : "");
                ServerConfig sCfg = configuredServersMap.get(serverId);
                boolean isPdmServer = isPdm(sCfg);

                if (isPdmServer) {
                    boolean serverGuestSucceeded = successfulGuestServers != null && successfulGuestServers.contains(serverId);
                    if (!serverGuestSucceeded) {
                        // Whole server/endpoint failure: preserve cached guest as UNKNOWN
                        GuestInfo staleGuest = new GuestInfo(
                                oldGuest.vmid, oldGuest.name, oldGuest.type,
                                GuestInfo.RunState.UNKNOWN,
                                oldGuest.node, oldGuest.serverId,
                                oldGuest.cpuUsage, oldGuest.cpuCount,
                                oldGuest.memUsed, oldGuest.memMax,
                                oldGuest.diskUsed, oldGuest.diskTotal,
                                oldGuest.diskRead, oldGuest.diskWrite,
                                oldGuest.netIn, oldGuest.netOut,
                                oldGuest.uptime, oldGuest.tags,
                                oldGuest.favorited, oldGuest.fetchedAt);
                        mergedGuests.put(nodeKey + "/" + oldGuest.vmid, staleGuest);
                        continue;
                    }

                    String remoteName = com.homelabpocket.api.PdmApiDriver.extractRemote(oldGuest.node);
                    String remoteKey = serverId + "/" + remoteName;

                    if (failedRemoteKeys != null && failedRemoteKeys.contains(remoteKey)) {
                        GuestInfo staleGuest = new GuestInfo(
                                oldGuest.vmid, oldGuest.name, oldGuest.type,
                                GuestInfo.RunState.UNKNOWN,
                                oldGuest.node, oldGuest.serverId,
                                oldGuest.cpuUsage, oldGuest.cpuCount,
                                oldGuest.memUsed, oldGuest.memMax,
                                oldGuest.diskUsed, oldGuest.diskTotal,
                                oldGuest.diskRead, oldGuest.diskWrite,
                                oldGuest.netIn, oldGuest.netOut,
                                oldGuest.uptime, oldGuest.tags,
                                oldGuest.favorited, oldGuest.fetchedAt);
                        mergedGuests.put(nodeKey + "/" + oldGuest.vmid, staleGuest);
                    } else if (successfulRemoteKeys != null && successfulRemoteKeys.contains(remoteKey)) {
                        // Succeeded remote: freshGuests supersedes cache
                    } else {
                        // Removed remote or empty PDM endpoint: authoritative purge
                    }
                } else {
                    // PVE server: per-node guest authority
                    boolean nodeGuestSucceeded = successfulGuestNodeKeys != null && successfulGuestNodeKeys.contains(nodeKey);
                    if (!nodeGuestSucceeded) {
                        GuestInfo staleGuest = new GuestInfo(
                                oldGuest.vmid, oldGuest.name, oldGuest.type,
                                GuestInfo.RunState.UNKNOWN,
                                oldGuest.node, oldGuest.serverId,
                                oldGuest.cpuUsage, oldGuest.cpuCount,
                                oldGuest.memUsed, oldGuest.memMax,
                                oldGuest.diskUsed, oldGuest.diskTotal,
                                oldGuest.diskRead, oldGuest.diskWrite,
                                oldGuest.netIn, oldGuest.netOut,
                                oldGuest.uptime, oldGuest.tags,
                                oldGuest.favorited, oldGuest.fetchedAt);
                        mergedGuests.put(nodeKey + "/" + oldGuest.vmid, staleGuest);
                    }
                }
            }
        }

        if (freshGuests != null) {
            for (GuestInfo newGuest : freshGuests) {
                String serverId = newGuest.serverId != null ? newGuest.serverId : "";
                if (configuredProvenances.containsKey(serverId)) {
                    String nodeKey = serverId + "/" + (newGuest.node != null ? newGuest.node : "");
                    mergedGuests.put(nodeKey + "/" + newGuest.vmid, newGuest);
                }
            }
        }

        Map<String, String> newProvenances = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : configuredProvenances.entrySet()) {
            String serverId = entry.getKey();
            String currentProv = entry.getValue();
            boolean nodeSucceeded = successfulNodeServers != null && successfulNodeServers.contains(serverId);
            boolean guestSucceeded = successfulGuestServers != null && successfulGuestServers.contains(serverId);
            boolean guestNodeSucceeded = false;
            if (successfulGuestNodeKeys != null) {
                for (String k : successfulGuestNodeKeys) {
                    if (k.startsWith(serverId + "/")) {
                        guestNodeSucceeded = true;
                        break;
                    }
                }
            }
            if (nodeSucceeded || guestSucceeded || guestNodeSucceeded) {
                newProvenances.put(serverId, currentProv);
            } else if (cachedProvenances != null && currentProv.equals(cachedProvenances.get(serverId))) {
                newProvenances.put(serverId, currentProv);
            }
        }

        boolean hasFreshData = (freshNodes != null && !freshNodes.isEmpty()) || (freshGuests != null && !freshGuests.isEmpty());
        return new FleetCacheMergeResult(
                new ArrayList<>(mergedNodes.values()),
                new ArrayList<>(mergedGuests.values()),
                newProvenances,
                hasFreshData);
    }

    public static class TaskResultPresentation {
        public enum DialogType {
            TOAST_SUCCESS,
            ALERT_WARNINGS,
            ALERT_ERROR,
            ALERT_TIMEOUT,
            ALERT_UNKNOWN,
            ALERT_OPERATION_ERROR
        }

        public final DialogType type;
        public final String title;
        public final String message;
        public final boolean isSuccess;

        public TaskResultPresentation(DialogType type, String title, String message, boolean isSuccess) {
            this.type = type;
            this.title = title;
            this.message = message;
            this.isSuccess = isSuccess;
        }
    }

    public static TaskResultPresentation evaluateTaskResultPresentation(
            TaskResult taskResult, String action, String targetInfo, String errorMessage) {
        if (taskResult != null) {
            switch (taskResult.state) {
                case OK:
                    return new TaskResultPresentation(
                            TaskResultPresentation.DialogType.TOAST_SUCCESS,
                            "Success",
                            "Success: " + action + " completed for " + targetInfo,
                            true);
                case WARNINGS:
                    return new TaskResultPresentation(
                            TaskResultPresentation.DialogType.ALERT_WARNINGS,
                            "Task Completed with Warnings",
                            "Operation '" + action + "' completed on Proxmox with upstream warnings.\n\n"
                                    + "Target: " + targetInfo + "\n\n"
                                    + "Details: " + (taskResult.exitStatus != null ? taskResult.exitStatus : "Completed with warnings"),
                            true);
                case ERROR:
                    return new TaskResultPresentation(
                            TaskResultPresentation.DialogType.ALERT_ERROR,
                            "Task Failed",
                            "Operation '" + action + "' failed on Proxmox.\n\n"
                                    + "Target: " + targetInfo + "\n\n"
                                    + "Details: " + (taskResult.exitStatus != null ? taskResult.exitStatus : "Unknown error"),
                            false);
                case TIMEOUT:
                    return new TaskResultPresentation(
                            TaskResultPresentation.DialogType.ALERT_TIMEOUT,
                            "Task Timed Out",
                            "Operation '" + action + "' did not complete within the 60s timeout.\n\n"
                                    + "Target: " + targetInfo + "\n\n"
                                    + "Details: " + taskResult.getSummary(),
                            false);
                case UNKNOWN:
                default:
                    return new TaskResultPresentation(
                            TaskResultPresentation.DialogType.ALERT_UNKNOWN,
                            "Task Status Unknown",
                            "Operation '" + action + "' returned indeterminate status.\n\n"
                                    + "Target: " + targetInfo + "\n\n"
                                    + "Details: " + taskResult.getSummary(),
                            false);
            }
        } else if (errorMessage != null) {
            return new TaskResultPresentation(
                    TaskResultPresentation.DialogType.ALERT_OPERATION_ERROR,
                    "Operation Error",
                    "Failed to execute '" + action + "' on " + targetInfo + ":\n\n" + errorMessage,
                    false);
        } else {
            return new TaskResultPresentation(
                    TaskResultPresentation.DialogType.ALERT_UNKNOWN,
                    "Task Status Unknown",
                    "Operation '" + action + "' ended with no result.",
                    false);
        }
    }

    /**
     * Encapsulates the caller state transition when an asynchronous power action completes.
     * Guarantees truthful acknowledgement isolation: subsequent telemetry refresh failure
     * (e.g. background polling error or network timeout) CANNOT overwrite, mutate, or downgrade
     * the user-facing task acknowledgment dialog or toast.
     */
    public static class ActionCallerTransitionState {
        public final TaskResultPresentation presentation;
        public final boolean postRefreshTriggered;
        public final boolean isActionRunning;
        public final String dialogTitle;
        public final String dialogMessage;
        public final boolean isSuccess;

        public ActionCallerTransitionState(TaskResultPresentation presentation, boolean postRefreshTriggered,
                                           boolean isActionRunning) {
            this.presentation = presentation;
            this.postRefreshTriggered = postRefreshTriggered;
            this.isActionRunning = isActionRunning;
            this.dialogTitle = presentation != null ? presentation.title : null;
            this.dialogMessage = presentation != null ? presentation.message : null;
            this.isSuccess = presentation != null && presentation.isSuccess;
        }

        /**
         * Verifies truthful acknowledgement isolation:
         * Guarantees that subsequent telemetry errors or stale cache refreshes
         * do not alter or corrupt the acknowledged action result.
         */
        public boolean isAcknowledgedSuccessPreserved(GuestMetricsBindingState subsequentTelemetryState) {
            if (!this.isSuccess || this.presentation == null) {
                return false;
            }
            boolean isAckType = (presentation.type == TaskResultPresentation.DialogType.TOAST_SUCCESS
                    || presentation.type == TaskResultPresentation.DialogType.ALERT_WARNINGS);
            if (!isAckType) {
                return false;
            }
            if (!this.presentation.isSuccess || this.presentation.type == TaskResultPresentation.DialogType.ALERT_ERROR
                    || this.presentation.type == TaskResultPresentation.DialogType.ALERT_TIMEOUT
                    || this.presentation.type == TaskResultPresentation.DialogType.ALERT_UNKNOWN) {
                return false;
            }
            if (dialogTitle != null && (dialogTitle.toLowerCase(java.util.Locale.ROOT).contains("failed")
                    || dialogTitle.toLowerCase(java.util.Locale.ROOT).contains("error"))) {
                return false;
            }
            if (subsequentTelemetryState != null) {
                // If telemetry refreshed with an error or stale state, power controls must be disabled,
                // but this caller transition must still retain isSuccess = true and its acknowledged dialog/toast.
                if (subsequentTelemetryState.isStale || !subsequentTelemetryState.hasData) {
                    return this.isSuccess && this.presentation.isSuccess && !subsequentTelemetryState.isPowerControlsEnabled;
                }
            }
            return this.isSuccess && this.presentation.isSuccess;
        }
    }

    /**
     * Interface for triggering post-operation telemetry refresh without coupling to specific Activity.
     */
    public interface PostRefreshTrigger {
        void triggerRefresh();
    }

    /**
     * Shared production caller transition seam wired to BOTH GuestsView and VmDetailActivity.
     * Evaluates task presentation, triggers post-operation refresh, and transitions owner state.
     */
    public static ActionCallerTransitionState performActionCompletionTransition(
            TaskResult taskResult, String action, String targetInfo, String errorMessage,
            PostRefreshTrigger refreshTrigger) {
        TaskResultPresentation pres = evaluateTaskResultPresentation(taskResult, action, targetInfo, errorMessage);
        boolean refreshTriggered = false;
        if (refreshTrigger != null) {
            refreshTrigger.triggerRefresh();
            refreshTriggered = true;
        }
        return new ActionCallerTransitionState(pres, refreshTriggered, false);
    }
}
