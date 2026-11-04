// SPDX-License-Identifier: GPL-3.0-or-later
package com.homelabpocket.api;

import com.homelabpocket.model.ServerConfig;

/**
 * Production routing decision engine for terminal and web console fallbacks.
 * Enforces honesty guarantees:
 * 1. Do NOT invent guest/node network addresses or routes.
 * 2. Disable selected-target SSH whenever actual target is unverified.
 * 3. Distinguish PVE API node vs cluster peer node, PDM appliance vs remote node,
 *    and host machine vs guest VM/LXC container.
 * 4. Explicitly label browser URL as the configured management endpoint,
 *    not the selected guest console.
 */
public class TerminalRoutingDecision {

    public final boolean isSshAllowed;
    public final String sshCommand;
    public final String sshTargetHost;
    public final String denialReason;
    public final String webManagementUrl;
    public final String webConsoleLabel;
    public final boolean isManagementEndpointOnly;

    public TerminalRoutingDecision(
            boolean isSshAllowed,
            String sshCommand,
            String sshTargetHost,
            String denialReason,
            String webManagementUrl,
            String webConsoleLabel,
            boolean isManagementEndpointOnly) {
        this.isSshAllowed = isSshAllowed;
        this.sshCommand = sshCommand;
        this.sshTargetHost = sshTargetHost;
        this.denialReason = denialReason;
        this.webManagementUrl = webManagementUrl;
        this.webConsoleLabel = webConsoleLabel;
        this.isManagementEndpointOnly = isManagementEndpointOnly;
    }

    public static boolean isUnknownOrBlank(String str) {
        if (str == null) return true;
        String trimmed = str.trim();
        return trimmed.isEmpty() ||
                "unknown".equalsIgnoreCase(trimmed) ||
                "null".equalsIgnoreCase(trimmed) ||
                "none".equalsIgnoreCase(trimmed) ||
                "undefined".equalsIgnoreCase(trimmed) ||
                "<unknown>".equalsIgnoreCase(trimmed) ||
                "?".equals(trimmed);
    }

    public static TerminalRoutingDecision evaluate(ServerConfig server, String targetType, String targetNode, int vmid) {
        // Case 1: Absent server config
        if (server == null) {
            return new TerminalRoutingDecision(
                    false,
                    null,
                    null,
                    "SSH disabled: Server configuration not found. Direct SSH target is unverified.",
                    null,
                    "Web Console Unavailable (No Server Config)",
                    false
            );
        }

        String host = (server.host != null) ? server.host.trim() : "";
        if (isUnknownOrBlank(host)) {
            return new TerminalRoutingDecision(
                    false,
                    null,
                    null,
                    "SSH disabled: Server host is not configured or unknown. Direct SSH target is unverified.",
                    null,
                    "Web Console Unavailable (No Host Configured)",
                    false
            );
        }

        int port = server.port > 0 ? server.port : (server.type == ServerConfig.ServerType.PDM ? 8443 : 8006);
        String managementUrl = formatWebUrl(host, port);

        // Case 2: PDM Server (Proxmox Datacenter Manager)
        if (server.type == ServerConfig.ServerType.PDM) {
            String nodeDisplay = !isUnknownOrBlank(targetNode) ? targetNode.trim() : "selected node";
            String reason = "Direct SSH to remote PDM node '" + nodeDisplay + "' is disabled. Homelab Pocket does not invent network routes. Connecting to " + host + " would open a shell on the PDM management appliance rather than the selected target node.";
            return new TerminalRoutingDecision(
                    false,
                    null,
                    null,
                    reason,
                    managementUrl,
                    "Open PDM Management Endpoint (" + host + ":" + port + ")",
                    true
            );
        }

        // Case 3: Guest Container (LXC) or VM (QEMU) with VMID
        boolean isGuest = (vmid > 0) || "LXC".equalsIgnoreCase(targetType) || "QEMU".equalsIgnoreCase(targetType);
        if (isGuest) {
            String gType = (targetType != null && !targetType.trim().isEmpty()) ? targetType.trim().toUpperCase() : "GUEST";
            String idStr = (vmid > 0) ? String.valueOf(vmid) : "unknown";
            String reason = "Direct SSH to " + gType + " [" + idStr + "] is disabled. Homelab Pocket does not invent guest network addresses. Connecting to " + host + " would open a shell on the Proxmox host rather than inside container/VM " + idStr + ".";
            return new TerminalRoutingDecision(
                    false,
                    null,
                    null,
                    reason,
                    managementUrl,
                    "Open Management Endpoint Web UI (" + host + ":" + port + ") [Not Guest Console]",
                    true
            );
        }

        // Case 4: Target node unspecified, blank, or unknown — Fail closed for SSH; permit management-only web endpoint
        if (isUnknownOrBlank(targetNode)) {
            String reason = "Direct SSH is disabled: Target node is unspecified or unknown. Homelab Pocket does not route SSH commands to unspecified targets.";
            return new TerminalRoutingDecision(
                    false,
                    null,
                    null,
                    reason,
                    managementUrl,
                    "Open PVE Management Endpoint (" + host + ":" + port + ") [Target Node Unspecified]",
                    true
            );
        }

        // Case 5: Node shell — Check if selected node matches configured API endpoint host
        if (!targetNode.trim().equalsIgnoreCase(host)) {
            // Selected node does NOT match configured API node host (e.g. cluster peer node)
            String reason = "Direct SSH to cluster peer node '" + targetNode.trim() + "' is disabled. The configured API endpoint is '" + host + "'. Homelab Pocket does not invent peer node IP addresses or network routes.";
            return new TerminalRoutingDecision(
                    false,
                    null,
                    null,
                    reason,
                    managementUrl,
                    "Open PVE API Node Web UI (" + host + ":" + port + ") [Not " + targetNode.trim() + "]",
                    true
            );
        }

        // Case 6: Verified API Node Host
        String sshCmd = formatSshCommand(host);
        return new TerminalRoutingDecision(
                true,
                sshCmd,
                host,
                null,
                managementUrl,
                "Open Proxmox Host Web Console (" + host + ":" + port + ")",
                false
        );
    }

    public static String formatSshCommand(String host) {
        if (isUnknownOrBlank(host)) {
            return null;
        }
        return "ssh root@" + host.trim();
    }

    public static String formatWebUrl(String host, int port) {
        if (isUnknownOrBlank(host)) {
            return null;
        }
        String cleanHost = host.trim();
        int cleanPort = port > 0 ? port : 8006;
        if (cleanHost.contains(":") && !cleanHost.startsWith("[")) {
            cleanHost = "[" + cleanHost + "]";
        }
        return "https://" + cleanHost + ":" + cleanPort + "/";
    }
}
