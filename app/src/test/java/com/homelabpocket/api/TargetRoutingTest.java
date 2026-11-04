// SPDX-License-Identifier: GPL-3.0-or-later
package com.homelabpocket.api;

import com.homelabpocket.model.GuestInfo;
import com.homelabpocket.model.ServerConfig;

import org.junit.Test;

import static org.junit.Assert.*;

public class TargetRoutingTest {

    @Test
    public void testGuestUniqueKey() {
        GuestInfo vm1 = new GuestInfo(100, "db-vm", GuestInfo.GuestType.QEMU,
                GuestInfo.RunState.RUNNING, "pve-1", "srv-alpha",
                0.1, 2, 1024, 2048, 0, 0, 0, 0, 100, "", false, 0);

        GuestInfo vm2 = new GuestInfo(100, "web-vm", GuestInfo.GuestType.QEMU,
                GuestInfo.RunState.RUNNING, "pve-2", "srv-alpha",
                0.1, 2, 1024, 2048, 0, 0, 0, 0, 100, "", false, 0);

        GuestInfo ct1 = new GuestInfo(100, "ct-100", GuestInfo.GuestType.LXC,
                GuestInfo.RunState.RUNNING, "pve-1", "srv-alpha",
                0.1, 2, 1024, 2048, 0, 0, 0, 0, 100, "", false, 0);

        assertEquals("srv-alpha/pve-1/qemu/100", vm1.getKey());
        assertEquals("srv-alpha/pve-2/qemu/100", vm2.getKey());
        assertEquals("srv-alpha/pve-1/lxc/100", ct1.getKey());

        // Keys are distinct even if VMID matches
        assertNotEquals(vm1.getKey(), vm2.getKey());
        assertNotEquals(vm1.getKey(), ct1.getKey());
    }

    @Test
    public void testTermproxySupportDistinction() {
        GuestInfo qemuGuest = new GuestInfo(100, "ubuntu-vm", GuestInfo.GuestType.QEMU,
                GuestInfo.RunState.RUNNING, "pve-1", "srv-1",
                0.1, 2, 1024, 2048, 0, 0, 0, 0, 100, "", false, 0);

        GuestInfo lxcGuest = new GuestInfo(200, "nginx-ct", GuestInfo.GuestType.LXC,
                GuestInfo.RunState.RUNNING, "pve-1", "srv-1",
                0.1, 2, 1024, 2048, 0, 0, 0, 0, 100, "", false, 0);

        // LXC supports PVE termproxy directly
        assertTrue(lxcGuest.supportsTermproxy());

        // QEMU does NOT support termproxy in standard Proxmox (requires graphical noVNC)
        assertFalse(qemuGuest.supportsTermproxy());
    }

    @Test
    public void testEndpointPathGeneration() {
        String node = "pve-alpha";
        int vmid = 105;

        // QEMU power endpoints
        String qemuStart = "/nodes/" + node + "/qemu/" + vmid + "/status/start";
        String qemuStop = "/nodes/" + node + "/qemu/" + vmid + "/status/stop";
        String qemuShutdown = "/nodes/" + node + "/qemu/" + vmid + "/status/shutdown";
        String qemuReboot = "/nodes/" + node + "/qemu/" + vmid + "/status/reboot";

        assertEquals("/nodes/pve-alpha/qemu/105/status/start", qemuStart);
        assertEquals("/nodes/pve-alpha/qemu/105/status/stop", qemuStop);
        assertEquals("/nodes/pve-alpha/qemu/105/status/shutdown", qemuShutdown);
        assertEquals("/nodes/pve-alpha/qemu/105/status/reboot", qemuReboot);

        // LXC power endpoints
        String lxcStart = "/nodes/" + node + "/lxc/" + vmid + "/status/start";
        String lxcTermproxy = "/nodes/" + node + "/lxc/" + vmid + "/termproxy";

        assertEquals("/nodes/pve-alpha/lxc/105/status/start", lxcStart);
        assertEquals("/nodes/pve-alpha/lxc/105/termproxy", lxcTermproxy);

        // Node termproxy endpoint
        String nodeTermproxy = "/nodes/" + node + "/termproxy";
        assertEquals("/nodes/pve-alpha/termproxy", nodeTermproxy);
    }

    @Test
    public void testRouting_PveApiNodeMatchesSelectedNode() {
        ServerConfig server = new ServerConfig("srv-1", "PVE-01", "pve-01.lab.example.com", 8006,
                ServerConfig.ServerType.PVE, "01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF",
                true, false);

        TerminalRoutingDecision decision = TerminalRoutingDecision.evaluate(server, "NODE", "pve-01.lab.example.com", -1);
        assertTrue("SSH should be allowed when target matches verified API node host", decision.isSshAllowed);
        assertEquals("ssh root@pve-01.lab.example.com", decision.sshCommand);
        assertEquals("pve-01.lab.example.com", decision.sshTargetHost);
        assertNull(decision.denialReason);
        assertEquals("https://pve-01.lab.example.com:8006/", decision.webManagementUrl);
        assertTrue(decision.webConsoleLabel.contains("Open Proxmox Host Web Console"));
        assertFalse(decision.isManagementEndpointOnly);
    }

    @Test
    public void testRouting_PveClusterPeerNode_SelectedNodeNotApiNode() {
        ServerConfig server = new ServerConfig("srv-1", "PVE-Cluster", "pve-api.lab.example.com", 8006,
                ServerConfig.ServerType.PVE, "01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF",
                true, false);

        TerminalRoutingDecision decision = TerminalRoutingDecision.evaluate(server, "NODE", "pve-peer-02", -1);
        assertFalse("SSH must be disabled for cluster peer node to prevent misrouting to API node", decision.isSshAllowed);
        assertNull(decision.sshCommand);
        assertNotNull(decision.denialReason);
        assertTrue(decision.denialReason.contains("cluster peer node 'pve-peer-02' is disabled"));
        assertTrue(decision.denialReason.contains("configured API endpoint is 'pve-api.lab.example.com'"));
        assertEquals("https://pve-api.lab.example.com:8006/", decision.webManagementUrl);
        assertTrue(decision.webConsoleLabel.contains("[Not pve-peer-02]"));
        assertTrue(decision.isManagementEndpointOnly);
    }

    @Test
    public void testRouting_PdmRemoteNode_SelectedNodeDisabled() {
        ServerConfig server = new ServerConfig("srv-pdm", "PDM-Prod", "198.51.100.240", 8443,
                ServerConfig.ServerType.PDM, "01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF",
                true, false);

        TerminalRoutingDecision decision = TerminalRoutingDecision.evaluate(server, "NODE", "remote1/pve-node1", -1);
        assertFalse("SSH must be disabled for PDM remote node to prevent SSH on PDM appliance", decision.isSshAllowed);
        assertNull(decision.sshCommand);
        assertNotNull(decision.denialReason);
        assertTrue(decision.denialReason.contains("PDM management appliance rather than the selected target node"));
        assertEquals("https://198.51.100.240:8443/", decision.webManagementUrl);
        assertTrue(decision.webConsoleLabel.contains("PDM Management Endpoint"));
        assertTrue(decision.isManagementEndpointOnly);
    }

    @Test
    public void testRouting_LxcContainer_SelectedVmidDisabled() {
        ServerConfig server = new ServerConfig("srv-1", "PVE-01", "192.0.2.100", 8006,
                ServerConfig.ServerType.PVE, "01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF",
                true, false);

        TerminalRoutingDecision decision = TerminalRoutingDecision.evaluate(server, "LXC", "pve-01", 105);
        assertFalse("SSH must be disabled for LXC container without verified guest network configuration", decision.isSshAllowed);
        assertNull(decision.sshCommand);
        assertNotNull(decision.denialReason);
        assertTrue(decision.denialReason.contains("Direct SSH to LXC [105] is disabled"));
        assertTrue(decision.denialReason.contains("would open a shell on the Proxmox host rather than inside container/VM 105"));
        assertEquals("https://192.0.2.100:8006/", decision.webManagementUrl);
        assertTrue(decision.webConsoleLabel.contains("[Not Guest Console]"));
        assertTrue(decision.isManagementEndpointOnly);
    }

    @Test
    public void testRouting_AbsentServerConfig_Disabled() {
        TerminalRoutingDecision decision = TerminalRoutingDecision.evaluate(null, "NODE", "unknown", -1);
        assertFalse("SSH must be disabled when server configuration is absent", decision.isSshAllowed);
        assertNull(decision.sshCommand);
        assertNotNull(decision.denialReason);
        assertTrue(decision.denialReason.contains("Server configuration not found"));
        assertNull(decision.webManagementUrl);
        assertFalse(decision.isManagementEndpointOnly);
    }

    @Test
    public void testRouting_IPv6AddressFormatting() {
        ServerConfig server = new ServerConfig("srv-ipv6", "PVE-IPv6", "2001:db8::1", 8006,
                ServerConfig.ServerType.PVE, "01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF",
                true, false);

        TerminalRoutingDecision decision = TerminalRoutingDecision.evaluate(server, "NODE", "2001:db8::1", -1);
        assertTrue(decision.isSshAllowed);
        assertEquals("ssh root@2001:db8::1", decision.sshCommand);
        assertEquals("https://[2001:db8::1]:8006/", decision.webManagementUrl);

        // IPv6 peer node
        TerminalRoutingDecision peerDecision = TerminalRoutingDecision.evaluate(server, "NODE", "peer-node", -1);
        assertFalse(peerDecision.isSshAllowed);
        assertEquals("https://[2001:db8::1]:8006/", peerDecision.webManagementUrl);
    }

    @Test
    public void testRouting_NullOrEmptyTargetNode_FailsClosedForSsh_LabelsManagementEndpointOnly() {
        ServerConfig server = new ServerConfig("srv-pve", "Lab PVE", "pve.example.com", 8006,
                ServerConfig.ServerType.PVE, "01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF",
                true, false);

        // Null targetNode
        TerminalRoutingDecision decisionNull = TerminalRoutingDecision.evaluate(server, "NODE", null, -1);
        assertFalse("SSH must fail closed when targetNode is null", decisionNull.isSshAllowed);
        assertNull(decisionNull.sshCommand);
        assertNull(decisionNull.sshTargetHost);
        assertNotNull(decisionNull.denialReason);
        assertTrue(decisionNull.denialReason.contains("Target node is unspecified"));
        assertEquals("https://pve.example.com:8006/", decisionNull.webManagementUrl);
        assertTrue(decisionNull.webConsoleLabel.contains("[Target Node Unspecified]"));
        assertTrue(decisionNull.isManagementEndpointOnly);

        // Empty string targetNode
        TerminalRoutingDecision decisionEmpty = TerminalRoutingDecision.evaluate(server, "NODE", "", -1);
        assertFalse("SSH must fail closed when targetNode is empty string", decisionEmpty.isSshAllowed);
        assertNull(decisionEmpty.sshCommand);
        assertTrue(decisionEmpty.isManagementEndpointOnly);

        // Whitespace-only targetNode
        TerminalRoutingDecision decisionBlank = TerminalRoutingDecision.evaluate(server, "NODE", "   ", -1);
        assertFalse("SSH must fail closed when targetNode is whitespace only", decisionBlank.isSshAllowed);
        assertNull(decisionBlank.sshCommand);
        assertTrue(decisionBlank.isManagementEndpointOnly);

        // "unknown" targetNode
        TerminalRoutingDecision decisionUnknown = TerminalRoutingDecision.evaluate(server, "NODE", "unknown", -1);
        assertFalse("SSH must fail closed when targetNode is 'unknown'", decisionUnknown.isSshAllowed);
        assertNull(decisionUnknown.sshCommand);
        assertTrue(decisionUnknown.isManagementEndpointOnly);
        assertTrue(decisionUnknown.webConsoleLabel.contains("[Target Node Unspecified]"));
        assertFalse(decisionUnknown.webConsoleLabel.contains("[Not unknown]"));

        // "none" targetNode
        TerminalRoutingDecision decisionNone = TerminalRoutingDecision.evaluate(server, "NODE", "none", -1);
        assertFalse("SSH must fail closed when targetNode is 'none'", decisionNone.isSshAllowed);
        assertNull(decisionNone.sshCommand);
        assertTrue(decisionNone.isManagementEndpointOnly);

        // "undefined" targetNode
        TerminalRoutingDecision decisionUndefined = TerminalRoutingDecision.evaluate(server, "NODE", "undefined", -1);
        assertFalse("SSH must fail closed when targetNode is 'undefined'", decisionUndefined.isSshAllowed);
        assertNull(decisionUndefined.sshCommand);
        assertTrue(decisionUndefined.isManagementEndpointOnly);

        // "?" targetNode
        TerminalRoutingDecision decisionQuestion = TerminalRoutingDecision.evaluate(server, "NODE", "?", -1);
        assertFalse("SSH must fail closed when targetNode is '?'", decisionQuestion.isSshAllowed);
        assertNull(decisionQuestion.sshCommand);
        assertTrue(decisionQuestion.isManagementEndpointOnly);
    }

    @Test
    public void testRouting_BlankOrNullServerHost_FailsClosedWithoutLocalhostFallback() {
        ServerConfig serverBlank = new ServerConfig("srv-blank", "Blank Host PVE", "", 8006,
                ServerConfig.ServerType.PVE, "01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF",
                true, false);

        TerminalRoutingDecision decisionBlank = TerminalRoutingDecision.evaluate(serverBlank, "NODE", "pve-01", -1);
        assertFalse("SSH must fail closed when server host is blank", decisionBlank.isSshAllowed);
        assertNull(decisionBlank.sshCommand);
        assertNull(decisionBlank.sshTargetHost);
        assertNull("Blank server host must NOT fall back to localhost web console URL", decisionBlank.webManagementUrl);
        assertNotNull(decisionBlank.denialReason);
        assertTrue(decisionBlank.denialReason.contains("Server host is not configured"));
        assertEquals("Web Console Unavailable (No Host Configured)", decisionBlank.webConsoleLabel);
        assertFalse(decisionBlank.isManagementEndpointOnly);

        // Null host on ServerConfig
        ServerConfig serverNull = new ServerConfig("srv-null", "Null Host PVE", null, 8006,
                ServerConfig.ServerType.PVE, "01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF",
                true, false);

        TerminalRoutingDecision decisionNull = TerminalRoutingDecision.evaluate(serverNull, "NODE", "pve-01", -1);
        assertFalse("SSH must fail closed when server host is null", decisionNull.isSshAllowed);
        assertNull(decisionNull.sshCommand);
        assertNull(decisionNull.webManagementUrl);

        // "unknown" host on ServerConfig
        ServerConfig serverUnknown = new ServerConfig("srv-unk", "Unknown Host PVE", "unknown", 8006,
                ServerConfig.ServerType.PVE, "01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF",
                true, false);
        TerminalRoutingDecision decisionUnk = TerminalRoutingDecision.evaluate(serverUnknown, "NODE", "unknown", -1);
        assertFalse("SSH must fail closed when server host is 'unknown'", decisionUnk.isSshAllowed);
        assertNull(decisionUnk.sshCommand);
        assertNull(decisionUnk.webManagementUrl);
        assertEquals("Web Console Unavailable (No Host Configured)", decisionUnk.webConsoleLabel);
    }

    @Test
    public void testRouting_FormatHelpers_FailClosedOnBlankHost() {
        assertNull(TerminalRoutingDecision.formatSshCommand(null));
        assertNull(TerminalRoutingDecision.formatSshCommand(""));
        assertNull(TerminalRoutingDecision.formatSshCommand("   "));
        assertNull(TerminalRoutingDecision.formatSshCommand("unknown"));
        assertNull(TerminalRoutingDecision.formatSshCommand("null"));
        assertNull(TerminalRoutingDecision.formatSshCommand("none"));
        assertNull(TerminalRoutingDecision.formatWebUrl(null, 8006));
        assertNull(TerminalRoutingDecision.formatWebUrl("", 8006));
        assertNull(TerminalRoutingDecision.formatWebUrl("   ", 8006));
        assertNull(TerminalRoutingDecision.formatWebUrl("unknown", 8006));
        assertNull(TerminalRoutingDecision.formatWebUrl("null", 8006));
        assertNull(TerminalRoutingDecision.formatWebUrl("none", 8006));
    }
}
