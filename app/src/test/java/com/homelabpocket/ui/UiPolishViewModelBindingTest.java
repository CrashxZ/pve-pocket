// SPDX-License-Identifier: GPL-3.0-or-later
// PVE Pocket — UI Polish Behavioral & ViewModel Binding Tests
package com.homelabpocket.ui;

import com.homelabpocket.api.ActionGuard;
import com.homelabpocket.api.TerminalRoutingDecision;
import com.homelabpocket.model.GuestInfo;
import com.homelabpocket.model.NodeStatus;
import com.homelabpocket.model.ServerConfig;
import com.homelabpocket.model.StoragePool;
import com.homelabpocket.ui.widget.RadialGaugeView;
import com.homelabpocket.util.JsonUtil;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Verification test suite for PVE Pocket UI Polish policies and view-binding models.
 *
 * NOTE ON TEST EXECUTION ENVIRONMENT:
 * These tests execute in a standard JVM environment against the compile-time android.jar platform.
 * Because the headless compile-time android.jar contains stub methods (throwing RuntimeException: Stub!),
 * tests verify the production-used presentation policies, view-binding state machines, and data models
 * that drive NodesView, DashboardView, GuestsView, NodeDetailActivity, and VmDetailActivity.
 * Actual Android View inflation and rendering requires an active Android runtime / device.
 */
public class UiPolishViewModelBindingTest {

    // =========================================================================
    // 1. Node Basename and Separate Remote Label Presentation Policy
    // =========================================================================

    @Test
    public void testNodeBasenameAndRemoteSeparation() {
        // PDM remote composite node name (Online)
        NodeStatus pdmNode = new NodeStatus(
                "home-lab/home-lab", "srv-1", NodeStatus.Status.ONLINE,
                0.03, 12, 19_300_000_000L, 62_600_000_000L,
                -1, -1, -1, -1, "Proxmox VE", 96000, 1000L);

        assertEquals("home-lab", pdmNode.getDisplayName());
        assertEquals("home-lab", pdmNode.getRemoteName());
        assertTrue("Composite node must report separate remote", pdmNode.hasSeparateRemote());
        assertEquals("Full internal identity must be preserved", "home-lab/home-lab", pdmNode.node);

        // Verify UiPresentationPolicy matches NodeStatus
        assertEquals("home-lab", UiPresentationPolicy.getNodeDisplayName(pdmNode.node));
        assertEquals("home-lab", UiPresentationPolicy.getNodeRemoteName(pdmNode.node));
        assertTrue(UiPresentationPolicy.hasSeparateRemote(pdmNode.node));
        assertTrue(UiPresentationPolicy.formatNodeMeta(pdmNode).contains("[Remote: home-lab]"));

        // PDM remote composite node name (Offline)
        NodeStatus pdmOfflineNode = new NodeStatus(
                "cluster-a/node-1", "srv-1", NodeStatus.Status.OFFLINE,
                0.0, 1, 0, 1,
                -1, -1, -1, -1, "PVE", 0, 0L);

        assertEquals("node-1", pdmOfflineNode.getDisplayName());
        assertEquals("cluster-a", pdmOfflineNode.getRemoteName());
        assertTrue(pdmOfflineNode.hasSeparateRemote());
        assertEquals("cluster-a/node-1", pdmOfflineNode.node);

        String offlineMeta = UiPresentationPolicy.formatNodeMeta(pdmOfflineNode);
        assertTrue("Offline composite node MUST retain remote identity in meta label",
                offlineMeta.contains("cluster-a"));
        assertTrue("Offline meta must display unreachable notice",
                offlineMeta.contains("Unreachable / Intentionally powered down"));
        assertTrue("Offline meta must format separate remote badge",
                offlineMeta.contains("[Remote: cluster-a]"));

        // Different remote and node
        NodeStatus clusterNode = new NodeStatus(
                "datacenter-east/pve-compute-04", "srv-1", NodeStatus.Status.ONLINE,
                0.10, 24, 32_000_000_000L, 128_000_000_000L,
                -1, -1, -1, -1, "Proxmox VE", 120000, 1000L);

        assertEquals("pve-compute-04", clusterNode.getDisplayName());
        assertEquals("datacenter-east", clusterNode.getRemoteName());
        assertTrue(clusterNode.hasSeparateRemote());
        assertEquals("datacenter-east/pve-compute-04", clusterNode.node);

        // Standalone PVE node without remote prefix (Online)
        NodeStatus standaloneNode = new NodeStatus(
                "pve-primary", "srv-2", NodeStatus.Status.ONLINE,
                0.05, 8, 8_000_000_000L, 32_000_000_000L,
                50_000_000_000L, 100_000_000_000L, 1000, 2000, "Proxmox VE", 50000, 1000L);

        assertEquals("pve-primary", standaloneNode.getDisplayName());
        assertEquals("", standaloneNode.getRemoteName());
        assertFalse(standaloneNode.hasSeparateRemote());
        assertEquals("pve-primary", standaloneNode.node);
        assertFalse(UiPresentationPolicy.formatNodeMeta(standaloneNode).contains("[Remote:"));

        // Standalone PVE node without remote prefix (Offline)
        NodeStatus standaloneOffline = new NodeStatus(
                "pve-standby", "srv-2", NodeStatus.Status.OFFLINE,
                0.0, 4, 0, 16_000_000_000L,
                0, 50_000_000_000L, 0, 0, "Proxmox VE", 0, 0L);

        assertEquals("pve-standby", standaloneOffline.getDisplayName());
        assertEquals("", standaloneOffline.getRemoteName());
        assertFalse(standaloneOffline.hasSeparateRemote());
        assertEquals("Unreachable / Intentionally powered down",
                UiPresentationPolicy.formatNodeMeta(standaloneOffline));
    }

    @Test
    public void testGuestInfoBasenameAndRemoteSeparation() {
        // Guest running on composite PDM remote node
        GuestInfo guestOnPdm = new GuestInfo(
                101, "prod-db", GuestInfo.GuestType.QEMU, GuestInfo.RunState.RUNNING,
                "remote-cluster/node-02", "srv-1",
                0.15, 4, 4_000_000_000L, 8_000_000_000L,
                0, 0, 100, 200, 86400, "prod", false, 1000L);

        assertEquals("node-02", guestOnPdm.getNodeDisplayName());
        assertEquals("remote-cluster", guestOnPdm.getNodeRemoteName());
        assertTrue(guestOnPdm.hasSeparateRemote());
        assertEquals("Full node identity must remain untouched", "remote-cluster/node-02", guestOnPdm.node);

        String guestHostLabel = UiPresentationPolicy.formatGuestHostLabel(guestOnPdm);
        assertTrue("Must include VMID", guestHostLabel.contains("[101]"));
        assertTrue("Must display base host", guestHostLabel.contains("Host: node-02"));
        assertTrue("Must display remote cluster label", guestHostLabel.contains("[Remote: remote-cluster]"));

        // Standalone PVE guest
        GuestInfo guestStandalone = new GuestInfo(
                202, "test-ct", GuestInfo.GuestType.LXC, GuestInfo.RunState.RUNNING,
                "pve-box", "srv-2",
                0.02, 2, 512_000_000L, 1_024_000_000L,
                0, 0, 50, 50, 43200, "", false, 1000L);

        assertEquals("pve-box", guestStandalone.getNodeDisplayName());
        assertEquals("", guestStandalone.getNodeRemoteName());
        assertFalse(guestStandalone.hasSeparateRemote());
        String standaloneLabel = UiPresentationPolicy.formatGuestHostLabel(guestStandalone);
        assertTrue(standaloneLabel.contains("[202] • Host: pve-box"));
        assertFalse(standaloneLabel.contains("[Remote:"));
    }

    @Test
    public void testNodeCollisionSafetyAcrossDistinctRemotesOnlineAndOffline() {
        // Multi-cluster setup: identical basenames "node-1" across distinct remotes
        // Online nodes
        NodeStatus onlineA = new NodeStatus(
                "cluster-alpha/node-1", "pdm-srv", NodeStatus.Status.ONLINE,
                0.02, 16, 16_000_000_000L, 64_000_000_000L,
                -1, -1, -1, -1, "Proxmox VE", 3600, 1000L);

        NodeStatus onlineB = new NodeStatus(
                "cluster-beta/node-1", "pdm-srv", NodeStatus.Status.ONLINE,
                0.08, 16, 24_000_000_000L, 64_000_000_000L,
                -1, -1, -1, -1, "Proxmox VE", 7200, 1000L);

        // Display basenames are identical
        assertEquals("node-1", onlineA.getDisplayName());
        assertEquals("node-1", onlineB.getDisplayName());

        // Remotes are distinct
        assertEquals("cluster-alpha", onlineA.getRemoteName());
        assertEquals("cluster-beta", onlineB.getRemoteName());

        // Subtitles clearly distinguish the upstream remotes
        assertTrue(UiPresentationPolicy.formatNodeMeta(onlineA).contains("[Remote: cluster-alpha]"));
        assertTrue(UiPresentationPolicy.formatNodeMeta(onlineB).contains("[Remote: cluster-beta]"));
        assertNotEquals(UiPresentationPolicy.formatNodeMeta(onlineA), UiPresentationPolicy.formatNodeMeta(onlineB));

        // Full internal node keys are distinct, preventing intent collision and routing ambiguity
        assertNotEquals(onlineA.node, onlineB.node);
        assertEquals("cluster-alpha/node-1", onlineA.node);
        assertEquals("cluster-beta/node-1", onlineB.node);

        // Offline nodes with colliding basenames
        NodeStatus offlineA = new NodeStatus(
                "cluster-alpha/node-1", "pdm-srv", NodeStatus.Status.OFFLINE,
                0.0, 1, 0, 1,
                -1, -1, -1, -1, "PVE", 0, 0L);

        NodeStatus offlineB = new NodeStatus(
                "cluster-beta/node-1", "pdm-srv", NodeStatus.Status.OFFLINE,
                0.0, 1, 0, 1,
                -1, -1, -1, -1, "PVE", 0, 0L);

        // Basenames are identical
        assertEquals("node-1", offlineA.getDisplayName());
        assertEquals("node-1", offlineB.getDisplayName());

        // Offline subtitles MUST NOT be generic identical strings; they must distinguish clusters
        String metaA = UiPresentationPolicy.formatNodeMeta(offlineA);
        String metaB = UiPresentationPolicy.formatNodeMeta(offlineB);
        assertTrue("offlineA meta must identify cluster-alpha", metaA.contains("cluster-alpha"));
        assertTrue("offlineB meta must identify cluster-beta", metaB.contains("cluster-beta"));
        assertNotEquals("Offline nodes from distinct remotes MUST NOT produce identical meta labels",
                metaA, metaB);

        // Full composite keys preserved
        assertEquals("cluster-alpha/node-1", offlineA.node);
        assertEquals("cluster-beta/node-1", offlineB.node);
    }

    // =========================================================================
    // 2. Missing Metrics Presentation Policy (Honest Attribution)
    // =========================================================================

    @Test
    public void testMissingRootFsAttributionPdmVsPve() {
        ServerConfig pdmServer = new ServerConfig(
                "pdm-1", "Datacenter Manager", "pdm.local", 8443,
                ServerConfig.ServerType.PDM, "AA:BB");
        ServerConfig pveServer = new ServerConfig(
                "pve-1", "Main PVE", "pve.local", 8006,
                ServerConfig.ServerType.PVE, "CC:DD");

        // Node with missing rootfs (-1)
        NodeStatus missingRootFsNode = new NodeStatus(
                "home-lab/node-1", "srv-1", NodeStatus.Status.ONLINE,
                0.01, 12, 19_300_000_000L, 62_600_000_000L,
                -1, -1, -1, -1, "Proxmox VE", 96000, 1000L);

        assertFalse(missingRootFsNode.isDiskReported());

        // Confirmed PDM backend: Must explicitly attribute "Not reported by PDM"
        String rootFsPdmExplicit = UiPresentationPolicy.formatNodeRootFs(missingRootFsNode, true);
        String rootFsPdmServer = UiPresentationPolicy.formatNodeRootFs(missingRootFsNode, pdmServer);
        assertEquals("Root FS (/): Not reported by PDM", rootFsPdmExplicit);
        assertEquals("Root FS (/): Not reported by PDM", rootFsPdmServer);
        assertFalse(UiPresentationPolicy.isRootFsBarVisible(missingRootFsNode));

        // PVE backend or unknown backend: Must use generic "Not reported", NEVER falsely attributing to PDM
        String rootFsPveExplicit = UiPresentationPolicy.formatNodeRootFs(missingRootFsNode, false);
        String rootFsPveServer = UiPresentationPolicy.formatNodeRootFs(missingRootFsNode, pveServer);
        String rootFsUnknownDefault = UiPresentationPolicy.formatNodeRootFs(missingRootFsNode);
        assertEquals("Root FS (/): Not reported", rootFsPveExplicit);
        assertEquals("Root FS (/): Not reported", rootFsPveServer);
        assertEquals("Root FS (/): Not reported", rootFsUnknownDefault);

        // Valid populated rootfs on standalone PVE node
        NodeStatus pveNode = new NodeStatus(
                "pve-node", "srv-2", NodeStatus.Status.ONLINE,
                0.15, 8, 8_000_000_000L, 32_000_000_000L,
                25_000_000_000L, 100_000_000_000L, 500, 1000, "Proxmox VE", 40000, 1000L);

        assertTrue(pveNode.isDiskReported());
        String pveDisplay = UiPresentationPolicy.formatNodeRootFs(pveNode, pveServer);
        assertTrue(UiPresentationPolicy.isRootFsBarVisible(pveNode));
        assertTrue(pveDisplay.contains("Root FS (/)"));
        assertTrue(pveDisplay.contains("25%"));
        assertFalse(pveDisplay.contains("Not reported"));
    }

    @Test
    public void testMissingHostDiskAndNetworkAttribution() {
        ServerConfig pdmServer = new ServerConfig(
                "pdm-1", "Datacenter Manager", "pdm.local", 8443,
                ServerConfig.ServerType.PDM, "AA:BB");
        ServerConfig pveServer = new ServerConfig(
                "pve-1", "Main PVE", "pve.local", 8006,
                ServerConfig.ServerType.PVE, "CC:DD");

        NodeStatus missingMetricsNode = new NodeStatus(
                "home-lab/home-lab", "srv-1", NodeStatus.Status.ONLINE,
                0.01, 12, 19_300_000_000L, 62_600_000_000L,
                -1, -1, -1, -1, "Proxmox VE", 96000, 1000L);

        // Confirmed PDM
        assertEquals("Disk: Not reported by PDM", UiPresentationPolicy.formatHostDisk(missingMetricsNode, true));
        assertEquals("Disk: Not reported by PDM", UiPresentationPolicy.formatHostDisk(missingMetricsNode, pdmServer));
        assertEquals("Net: Not reported by PDM", UiPresentationPolicy.formatHostNet(missingMetricsNode, true));
        assertEquals("Net: Not reported by PDM", UiPresentationPolicy.formatHostNet(missingMetricsNode, pdmServer));

        // PVE / Unknown backend
        assertEquals("Disk: Not reported", UiPresentationPolicy.formatHostDisk(missingMetricsNode, false));
        assertEquals("Disk: Not reported", UiPresentationPolicy.formatHostDisk(missingMetricsNode, pveServer));
        assertEquals("Disk: Not reported", UiPresentationPolicy.formatHostDisk(missingMetricsNode));
        assertEquals("Net: Not reported", UiPresentationPolicy.formatHostNet(missingMetricsNode, false));
        assertEquals("Net: Not reported", UiPresentationPolicy.formatHostNet(missingMetricsNode, pveServer));
        assertEquals("Net: Not reported", UiPresentationPolicy.formatHostNet(missingMetricsNode));

        // Populated host node
        NodeStatus pveNode = new NodeStatus(
                "pve-1", "srv-2", NodeStatus.Status.ONLINE,
                0.05, 4, 4_000_000_000L, 16_000_000_000L,
                20_000_000_000L, 50_000_000_000L, 1048576, 524288, "Proxmox VE", 50000, 1000L);

        String pveDisk = UiPresentationPolicy.formatHostDisk(pveNode, pveServer);
        String pveNet = UiPresentationPolicy.formatHostNet(pveNode, pveServer);

        assertTrue(pveDisk.startsWith("Disk: "));
        assertFalse(pveDisk.contains("Not reported"));
        assertTrue(pveNet.startsWith("Net: "));
        assertFalse(pveNet.contains("Not reported"));
    }

    @Test
    public void testGuestDiskAttributionPdmVsPve() {
        ServerConfig pdmServer = new ServerConfig(
                "pdm-1", "Datacenter Manager", "pdm.local", 8443,
                ServerConfig.ServerType.PDM, "AA:BB");
        ServerConfig pveServer = new ServerConfig(
                "pve-1", "Main PVE", "pve.local", 8006,
                ServerConfig.ServerType.PVE, "CC:DD");

        GuestInfo guestNoDisk = new GuestInfo(
                100, "web", GuestInfo.GuestType.QEMU, GuestInfo.RunState.RUNNING,
                "node", "srv", 0.1, 2, 1000, 2000, -1, -1, 0, 0, 0, 0, 100, "", false, 100L);

        // Confirmed PDM
        assertEquals("Disk: Not reported by PDM", UiPresentationPolicy.formatGuestDisk(guestNoDisk, true));
        assertEquals("Disk: Not reported by PDM", UiPresentationPolicy.formatGuestDisk(guestNoDisk, pdmServer));

        // PVE / Unknown backend
        assertEquals("Disk: Not reported", UiPresentationPolicy.formatGuestDisk(guestNoDisk, false));
        assertEquals("Disk: Not reported", UiPresentationPolicy.formatGuestDisk(guestNoDisk, pveServer));
        assertEquals("Disk: Not reported", UiPresentationPolicy.formatGuestDisk(guestNoDisk));

        // Guest with populated disk metrics
        GuestInfo guestWithDisk = new GuestInfo(
                101, "db", GuestInfo.GuestType.QEMU, GuestInfo.RunState.RUNNING,
                "node", "srv", 0.1, 2, 1000, 2000, 10_000_000_000L, 50_000_000_000L, 0, 0, 0, 0, 100, "", false, 100L);

        String disk = UiPresentationPolicy.formatGuestDisk(guestWithDisk, pveServer);
        assertTrue(disk.startsWith("Disk: "));
        assertFalse(disk.contains("Not reported"));
    }

    // =========================================================================
    // 3. Consistent PDM Monitoring Notice & Unsupported Actions Filtering Policy
    // =========================================================================

    @Test
    public void testPdmPresentationPolicyHidesUnsupportedControls() {
        ServerConfig pdmServer = new ServerConfig(
                "pdm-1", "Datacenter Manager", "pdm.local", 8443,
                ServerConfig.ServerType.PDM, "AA:BB");
        ServerConfig pveServer = new ServerConfig(
                "pve-1", "Main PVE", "pve.local", 8006,
                ServerConfig.ServerType.PVE, "CC:DD");

        // PDM: power controls and direct shells MUST be disabled/hidden
        assertTrue(UiPresentationPolicy.isPdm(pdmServer));
        assertFalse("Guest power actions must be hidden on PDM", UiPresentationPolicy.isGuestPowerAllowed(pdmServer));
        assertFalse("Host shell must be hidden on PDM", UiPresentationPolicy.isHostShellAllowed(pdmServer));
        assertFalse("Guest console must be hidden on PDM", UiPresentationPolicy.isGuestConsoleAllowed(pdmServer));

        // PDM notices are explicit and non-empty
        assertEquals("PDM MONITORING ONLY", UiPresentationPolicy.PDM_MONITORING_ONLY);
        assertEquals("PDM MONITORING", UiPresentationPolicy.PDM_MONITORING_BADGE);
        assertTrue(UiPresentationPolicy.PDM_MONITORING_DETAIL.contains("Proxmox Datacenter Manager"));
        assertTrue(UiPresentationPolicy.PDM_MONITORING_DETAIL.contains("web console"));

        // PVE: power controls and direct shells MUST be allowed
        assertFalse(UiPresentationPolicy.isPdm(pveServer));
        assertTrue("Guest power actions allowed on PVE", UiPresentationPolicy.isGuestPowerAllowed(pveServer));
        assertTrue("Host shell allowed on PVE", UiPresentationPolicy.isHostShellAllowed(pveServer));
        assertTrue("Guest console allowed on PVE", UiPresentationPolicy.isGuestConsoleAllowed(pveServer));
    }

    // =========================================================================
    // 4. Storage Pool Driver Type Presentation Policy (Not Health)
    // =========================================================================

    @Test
    public void testStoragePoolUnknownTypeExplanationAttribution() {
        ServerConfig pdmServer = new ServerConfig(
                "pdm-1", "Datacenter Manager", "pdm.local", 8443,
                ServerConfig.ServerType.PDM, "AA:BB");
        ServerConfig pveServer = new ServerConfig(
                "pve-1", "Main PVE", "pve.local", 8006,
                ServerConfig.ServerType.PVE, "CC:DD");

        StoragePool unknownPool = new StoragePool(
                "data2-isos", "unknown",
                97_900_000_000L, 81_400_000_000L, 16_500_000_000L,
                true, true, false, "iso");

        assertFalse("Type must be reported as unverified/unreported", unknownPool.isTypeReported());
        assertEquals("UNKNOWN", unknownPool.getTypeBadge());

        // Confirmed PDM: "Type not reported by PDM"
        assertEquals("Type not reported by PDM", UiPresentationPolicy.getStorageTypeFootnote(unknownPool, true));
        assertEquals("Type not reported by PDM", UiPresentationPolicy.getStorageTypeFootnote(unknownPool, pdmServer));

        // PVE / Unknown backend: "Type not reported"
        assertEquals("Type not reported", UiPresentationPolicy.getStorageTypeFootnote(unknownPool, false));
        assertEquals("Type not reported", UiPresentationPolicy.getStorageTypeFootnote(unknownPool, pveServer));
        assertEquals("Type not reported", UiPresentationPolicy.getStorageTypeFootnote(unknownPool));

        // Pool with explicit known driver type
        StoragePool zfsPool = new StoragePool(
                "local-zfs", "zfspool",
                500_000_000_000L, 200_000_000_000L, 300_000_000_000L,
                true, true, false, "rootdir,images");

        assertTrue(zfsPool.isTypeReported());
        assertEquals("ZFSPOOL", zfsPool.getTypeBadge());
        assertNull("Known storage driver type must not have footnote", UiPresentationPolicy.getStorageTypeFootnote(zfsPool, pdmServer));
        assertNull(UiPresentationPolicy.getStorageTypeFootnote(zfsPool, pveServer));
    }

    // =========================================================================
    // 5. Host Node Card View-Binding State Machine Integration
    // =========================================================================

    @Test
    public void testHostNodeCardBindingIntegrationOnlineAndOffline() {
        ServerConfig pdmServer = new ServerConfig(
                "pdm-1", "PDM Main", "pdm.local", 8443,
                ServerConfig.ServerType.PDM, "AA:BB");
        ServerConfig pveServer = new ServerConfig(
                "pve-1", "PVE Cluster", "pve.local", 8006,
                ServerConfig.ServerType.PVE, "CC:DD");

        // --- Scenario 1: Online PDM Node ---
        NodeStatus onlinePdm = new NodeStatus(
                "remote-a/node-1", "pdm-1", NodeStatus.Status.ONLINE,
                0.12, 8, 8_000_000_000L, 32_000_000_000L,
                -1, -1, -1, -1, "Proxmox VE", 10000, 1000L);

        UiPresentationPolicy.NodeCardBindingState stateOnlinePdm =
                UiPresentationPolicy.evaluateNodeBinding(onlinePdm, pdmServer);

        assertEquals("node-1", stateOnlinePdm.displayName);
        assertEquals("ONLINE", stateOnlinePdm.statusText);
        assertTrue(stateOnlinePdm.isOnline);
        assertFalse(stateOnlinePdm.isOfflineMsgVisible);
        assertTrue(stateOnlinePdm.isMetricsLayoutVisible);
        assertTrue("Online PDM card MUST show PDM monitoring badge", stateOnlinePdm.isPdmBadgeVisible);
        assertFalse("Online PDM card MUST hide Shell button", stateOnlinePdm.isShellButtonVisible);
        assertEquals("Disk: Not reported by PDM", stateOnlinePdm.diskText);
        assertEquals("Net: Not reported by PDM", stateOnlinePdm.netText);
        assertTrue(stateOnlinePdm.metaText.contains("[Remote: remote-a]"));
        assertEquals("Full composite key must be bound for routing", "remote-a/node-1", stateOnlinePdm.intentNode);

        // --- Scenario 2: Offline PDM Node (Regression check for acceptance defect) ---
        NodeStatus offlinePdm = new NodeStatus(
                "remote-a/node-1", "pdm-1", NodeStatus.Status.OFFLINE,
                0.0, 1, 0, 1,
                -1, -1, -1, -1, "PVE", 0, 0L);

        UiPresentationPolicy.NodeCardBindingState stateOfflinePdm =
                UiPresentationPolicy.evaluateNodeBinding(offlinePdm, pdmServer);

        assertEquals("node-1", stateOfflinePdm.displayName);
        assertEquals("OFFLINE", stateOfflinePdm.statusText);
        assertFalse(stateOfflinePdm.isOnline);
        assertTrue("Offline PDM card must show offline message", stateOfflinePdm.isOfflineMsgVisible);
        assertFalse("Offline PDM card must hide metrics layout", stateOfflinePdm.isMetricsLayoutVisible);
        assertTrue("Offline PDM card MUST display PDM monitoring badge regardless of online/offline status",
                stateOfflinePdm.isPdmBadgeVisible);
        assertFalse("Offline PDM card MUST hide Shell button regardless of online/offline status",
                stateOfflinePdm.isShellButtonVisible);
        assertTrue("Offline PDM card meta must retain remote cluster identity",
                stateOfflinePdm.metaText.contains("remote-a"));
        assertEquals("Full composite key must be preserved on offline PDM card",
                "remote-a/node-1", stateOfflinePdm.intentNode);

        // --- Scenario 3: Online PVE Node ---
        NodeStatus onlinePve = new NodeStatus(
                "pve-primary", "pve-1", NodeStatus.Status.ONLINE,
                0.20, 16, 16_000_000_000L, 64_000_000_000L,
                50_000_000_000L, 200_000_000_000L, 5000, 10000, "Proxmox VE", 25000, 1000L);

        UiPresentationPolicy.NodeCardBindingState stateOnlinePve =
                UiPresentationPolicy.evaluateNodeBinding(onlinePve, pveServer);

        assertEquals("pve-primary", stateOnlinePve.displayName);
        assertEquals("ONLINE", stateOnlinePve.statusText);
        assertTrue(stateOnlinePve.isOnline);
        assertFalse(stateOnlinePve.isOfflineMsgVisible);
        assertTrue(stateOnlinePve.isMetricsLayoutVisible);
        assertFalse("Online PVE card must NOT show PDM badge", stateOnlinePve.isPdmBadgeVisible);
        assertTrue("Online PVE card MUST show Shell button", stateOnlinePve.isShellButtonVisible);
        assertTrue(stateOnlinePve.diskText.startsWith("Disk: "));
        assertFalse(stateOnlinePve.diskText.contains("Not reported"));
        assertEquals("pve-primary", stateOnlinePve.intentNode);

        // --- Scenario 4: Offline PVE Node ---
        NodeStatus offlinePve = new NodeStatus(
                "pve-primary", "pve-1", NodeStatus.Status.OFFLINE,
                0.0, 16, 0, 64_000_000_000L,
                0, 200_000_000_000L, 0, 0, "Proxmox VE", 0, 0L);

        UiPresentationPolicy.NodeCardBindingState stateOfflinePve =
                UiPresentationPolicy.evaluateNodeBinding(offlinePve, pveServer);

        assertEquals("pve-primary", stateOfflinePve.displayName);
        assertEquals("OFFLINE", stateOfflinePve.statusText);
        assertFalse(stateOfflinePve.isOnline);
        assertTrue(stateOfflinePve.isOfflineMsgVisible);
        assertFalse(stateOfflinePve.isMetricsLayoutVisible);
        assertFalse("Offline PVE card must NOT show PDM badge", stateOfflinePve.isPdmBadgeVisible);
        assertFalse("Offline PVE card MUST hide Shell button (cannot shell into offline host)",
                stateOfflinePve.isShellButtonVisible);
        assertEquals("pve-primary", stateOfflinePve.intentNode);
    }

    // =========================================================================
    // 6. WCAG 2.1 Contrast Ratios Verification Test
    // =========================================================================

    @Test
    public void testPaletteMeetsWcagContrastRequirements() {
        // Dark theme background surfaces
        String bgObsidian = "#0A0F0D";
        String bgSurface = "#121A16";
        String bgElevated = "#17221D";

        // Text & Accent colors from colors.xml
        String textPrimary = "#E1EFE6";
        String textSecondary = "#A0BAAF";
        String textMuted = "#738E82";       // Also textColorHint for placeholders
        String colorPhosphor = "#00FF66";
        String colorCyan = "#00E5FF";
        String colorAmber = "#FFB300";

        // Primary text must meet WCAG AAA (>= 7.0:1) on all dark surfaces
        assertTrue(computeContrast(textPrimary, bgObsidian) >= 7.0);
        assertTrue(computeContrast(textPrimary, bgSurface) >= 7.0);
        assertTrue(computeContrast(textPrimary, bgElevated) >= 7.0);

        // Secondary text must meet WCAG AAA (>= 7.0:1) on all dark surfaces
        double secVsObs = computeContrast(textSecondary, bgObsidian);
        double secVsSurf = computeContrast(textSecondary, bgSurface);
        double secVsElev = computeContrast(textSecondary, bgElevated);
        assertTrue("Secondary text must achieve >= 7.0:1", secVsObs >= 7.0);
        assertTrue("Secondary text must achieve >= 7.0:1", secVsSurf >= 7.0);
        assertTrue("Secondary text must achieve >= 7.0:1", secVsElev >= 7.0);

        // Muted / Hint / Placeholder text must meet WCAG AA (>= 4.5:1) on all surfaces
        double mutedVsObs = computeContrast(textMuted, bgObsidian);
        double mutedVsSurf = computeContrast(textMuted, bgSurface);
        double mutedVsElev = computeContrast(textMuted, bgElevated);
        assertTrue("Muted text must achieve >= 4.5:1 on obsidian (actual: " + mutedVsObs + ")", mutedVsObs >= 4.5);
        assertTrue("Muted text must achieve >= 4.5:1 on surface (actual: " + mutedVsSurf + ")", mutedVsSurf >= 4.5);
        assertTrue("Muted text must achieve >= 4.5:1 on elevated (actual: " + mutedVsElev + ")", mutedVsElev >= 4.5);

        // Status accent colors
        assertTrue("Phosphor accent must exceed 7.0:1", computeContrast(colorPhosphor, bgObsidian) >= 7.0);
        assertTrue("Cyan accent must exceed 7.0:1", computeContrast(colorCyan, bgObsidian) >= 7.0);
        assertTrue("Amber accent must exceed 7.0:1", computeContrast(colorAmber, bgObsidian) >= 7.0);
    }

    private static double computeLuminance(int r, int g, int b) {
        double rNorm = r / 255.0;
        double gNorm = g / 255.0;
        double bNorm = b / 255.0;

        double rL = (rNorm <= 0.03928) ? rNorm / 12.92 : Math.pow((rNorm + 0.055) / 1.055, 2.4);
        double gL = (gNorm <= 0.03928) ? gNorm / 12.92 : Math.pow((gNorm + 0.055) / 1.055, 2.4);
        double bL = (bNorm <= 0.03928) ? bNorm / 12.92 : Math.pow((bNorm + 0.055) / 1.055, 2.4);

        return 0.2126 * rL + 0.7152 * gL + 0.0722 * bL;
    }

    private static double computeContrast(String hex1, String hex2) {
        int r1 = Integer.parseInt(hex1.substring(1, 3), 16);
        int g1 = Integer.parseInt(hex1.substring(3, 5), 16);
        int b1 = Integer.parseInt(hex1.substring(5, 7), 16);

        int r2 = Integer.parseInt(hex2.substring(1, 3), 16);
        int g2 = Integer.parseInt(hex2.substring(3, 5), 16);
        int b2 = Integer.parseInt(hex2.substring(5, 7), 16);

        double l1 = computeLuminance(r1, g1, b1);
        double l2 = computeLuminance(r2, g2, b2);

        double lighter = Math.max(l1, l2);
        double darker = Math.min(l1, l2);

        return (lighter + 0.05) / (darker + 0.05);
    }

    // =========================================================================
    // 7. Backend ActionGuard and Routing Safety
    // =========================================================================

    @Test
    public void testActionGuardLockingAndReleaseIntegrity() {
        ActionGuard guard = new ActionGuard();

        String key = "srv-1:node-1:vm:101:stop";
        assertTrue("First acquire must succeed", guard.tryAcquire(key));
        assertFalse("Second concurrent acquire must be blocked", guard.tryAcquire(key));

        guard.release(key);
        assertTrue("Acquire after release must succeed", guard.tryAcquire(key));
        guard.release(key);
    }

    @Test
    public void testTerminalRoutingRefusesPdmRemoteWithoutGuessing() {
        ServerConfig pdmServer = new ServerConfig(
                "srv-pdm", "My PDM", "pdm.example.com", 8443,
                ServerConfig.ServerType.PDM, null);

        TerminalRoutingDecision decision = TerminalRoutingDecision.evaluate(
                pdmServer, "home-lab/home-lab", "LXC", 101);

        assertFalse("Direct SSH must be refused for PDM remotes", decision.isSshAllowed);
        assertNotNull("Must provide honest reason", decision.denialReason);
        assertTrue(decision.denialReason.contains("PDM"));
    }

    // =========================================================================
    // 13. Overview Radial Gauges, Unified Hosts Section & Accessibility
    // =========================================================================

    @Test
    public void testRadialGaugeCalculationsAndClamping() {
        // Partly open 270-degree ring sweep calculations
        assertEquals(0.0f, RadialGaugeView.calculateSweepAngle(0.0, true), 0.001f);
        assertEquals(135.0f, RadialGaugeView.calculateSweepAngle(50.0, true), 0.001f);
        assertEquals(270.0f, RadialGaugeView.calculateSweepAngle(100.0, true), 0.001f);
        assertEquals(89.1f, RadialGaugeView.calculateSweepAngle(33.0, true), 0.001f);

        // When unavailable: sweep must be strictly 0 (no fake empty 0% ring)
        assertEquals(0.0f, RadialGaugeView.calculateSweepAngle(33.0, false), 0.001f);
        assertEquals(0.0f, RadialGaugeView.calculateSweepAngle(100.0, false), 0.001f);

        // Clamping edge cases
        assertEquals(0.0, RadialGaugeView.clampPercent(Double.NaN), 0.001);
        assertEquals(100.0, RadialGaugeView.clampPercent(Double.POSITIVE_INFINITY), 0.001);
        assertEquals(0.0, RadialGaugeView.clampPercent(Double.NEGATIVE_INFINITY), 0.001);
        assertEquals(0.0, RadialGaugeView.clampPercent(-10.0), 0.001);
        assertEquals(100.0, RadialGaugeView.clampPercent(110.0), 0.001);
        assertEquals(75.5, RadialGaugeView.clampPercent(75.5), 0.001);
    }

    @Test
    public void testRadialGaugeDisplayTextResolution() {
        // Gap 1: Unavailable metrics or "Unavailable" string render short center dash ("—"),
        // leaving full status to external subtitle and TalkBack without ring crowding.
        assertEquals("33%", RadialGaugeView.resolveDisplayText("33%", true));
        assertEquals("58.3%", RadialGaugeView.resolveDisplayText("58.3%", true));
        assertEquals("—", RadialGaugeView.resolveDisplayText("Unavailable", true));
        assertEquals("—", RadialGaugeView.resolveDisplayText("UNAVAILABLE", true));
        assertEquals("—", RadialGaugeView.resolveDisplayText("Unavailable", false));
        assertEquals("—", RadialGaugeView.resolveDisplayText("33%", false));
        assertEquals("—", RadialGaugeView.resolveDisplayText(null, true));
        assertEquals("—", RadialGaugeView.resolveDisplayText("", true));
    }

    @Test
    public void testRadialGaugeAccessibilityFontScalingAndResponsiveGeometryPolicy() {
        // Gap 1: Meaningfully honors user font scaling (scaledDensity / fontScale)
        // rather than ignoring it or collapsing immediately with autoshrink.
        float density = 2.0f; // xhdpi
        float innerDiameterPx = 164.0f; // 82dp inner diameter for 104dp gauge
        float baseSp = 16.0f;

        // 1. Standard text (fontScale = 1.0, scaledDensity = 2.0)
        float normalSize = RadialGaugeView.calculateEffectiveTextSize(
                baseSp, density, 2.0f, innerDiameterPx, "33%");
        assertEquals(32.0f, normalSize, 0.001f); // 16sp * 2.0 = 32px

        // 2. Large accessibility text (fontScale = 1.5, scaledDensity = 3.0)
        // Must meaningfully enlarge by 50% (to 48px) without being crushed back to 32px!
        float largeSize = RadialGaugeView.calculateEffectiveTextSize(
                baseSp, density, 3.0f, innerDiameterPx, "33%");
        assertEquals(48.0f, largeSize, 0.001f);
        assertTrue("Accessibility 150% text MUST enlarge over 100%", largeSize > normalSize);

        // 3. Largest accessibility text (fontScale = 2.0, scaledDensity = 4.0)
        // Must meaningfully enlarge by 100% (to 64px) for 3-char percent ("33%")
        float largestSize = RadialGaugeView.calculateEffectiveTextSize(
                baseSp, density, 4.0f, innerDiameterPx, "33%");
        assertEquals(64.0f, largestSize, 0.001f);
        assertTrue("Accessibility 200% text MUST enlarge over 150%", largestSize > largeSize);

        // 4. Short center dash ("—") under fontScale 2.0:
        // Fits comfortably at the full 200% enlarged size (64px)
        float dashSize = RadialGaugeView.calculateEffectiveTextSize(
                baseSp, density, 4.0f, innerDiameterPx, "—");
        assertEquals(64.0f, dashSize, 0.001f);

        // 5. 4-character string ("100%") under 200% fontScale:
        // Scales down gracefully to fit within inner ring margin (~58px),
        // staying well above 32px and well above the 18px (9*density) floor!
        float hundredSize = RadialGaugeView.calculateEffectiveTextSize(
                baseSp, density, 4.0f, innerDiameterPx, "100%");
        assertTrue("100% at 200% font scale must fit within inner ring", hundredSize <= 64.0f);
        assertTrue("100% at 200% font scale must stay significantly larger than 100% font", hundredSize > normalSize);
        assertTrue("100% must stay well above readable floor", hundredSize >= (RadialGaugeView.MIN_TEXT_SP * density));

        // 6. Readable fallback floor under extreme constraint
        float constrainedSize = RadialGaugeView.calculateEffectiveTextSize(
                baseSp, density, 4.0f, 40.0f, "99999%");
        assertEquals(RadialGaugeView.MIN_TEXT_SP * density, constrainedSize, 0.001f);
    }

    @Test
    public void testCompactHostRowBindingOnlineAndOfflinePdmAndPve() {
        ServerConfig pdmServer = new ServerConfig(
                "srv-pdm", "My PDM", "pdm.example.com", 8443,
                ServerConfig.ServerType.PDM, null);

        ServerConfig pveServer = new ServerConfig(
                "srv-pve", "My PVE", "pve.example.com", 8006,
                ServerConfig.ServerType.PVE, null);

        // 1. PDM Online Host:
        // Composite identity home-lab/home-lab -> basename home-lab, remote home-lab.
        // PDM monitoring badge visible, Shell button hidden fail-closed.
        NodeStatus pdmOnline = new NodeStatus(
                "home-lab/home-lab", "srv-pdm", NodeStatus.Status.ONLINE,
                0.33, 8, 10_000_000_000L, 16_000_000_000L,
                -1, -1, -1, -1, "8.1.4", 86400, 1000L);

        UiPresentationPolicy.CompactHostRowBindingState pdmOnlineBinding =
                UiPresentationPolicy.evaluateCompactHostRowBinding(pdmOnline, pdmServer);

        assertEquals("home-lab", pdmOnlineBinding.displayName);
        assertEquals("home-lab", pdmOnlineBinding.remoteName);
        assertTrue(pdmOnlineBinding.hasRemote);
        assertEquals("ONLINE", pdmOnlineBinding.statusText);
        assertTrue(pdmOnlineBinding.isOnline);
        assertEquals("home-lab/home-lab", pdmOnlineBinding.intentNode);
        assertEquals("srv-pdm", pdmOnlineBinding.intentServerId);
        assertTrue("PDM badge must be visible", pdmOnlineBinding.isPdmBadgeVisible);
        assertFalse("Shell button must be hidden on PDM", pdmOnlineBinding.isShellButtonVisible);
        assertTrue(pdmOnlineBinding.metricsText.contains("CPU: 33%"));
        assertTrue(pdmOnlineBinding.metricsText.contains("RAM:"));

        // 2. PDM Offline Host:
        // Offline hosts remain visible! Remote cluster identity cluster-a preserved.
        NodeStatus pdmOffline = new NodeStatus(
                "cluster-a/node-standby", "srv-pdm", NodeStatus.Status.OFFLINE,
                0.0, 4, -1, -1,
                -1, -1, -1, -1, null, -1, 1000L);

        UiPresentationPolicy.CompactHostRowBindingState pdmOfflineBinding =
                UiPresentationPolicy.evaluateCompactHostRowBinding(pdmOffline, pdmServer);

        assertEquals("node-standby", pdmOfflineBinding.displayName);
        assertEquals("cluster-a", pdmOfflineBinding.remoteName);
        assertTrue(pdmOfflineBinding.hasRemote);
        assertEquals("OFFLINE", pdmOfflineBinding.statusText);
        assertFalse(pdmOfflineBinding.isOnline);
        assertEquals("cluster-a/node-standby", pdmOfflineBinding.intentNode);
        assertTrue(pdmOfflineBinding.isPdmBadgeVisible);
        assertFalse(pdmOfflineBinding.isShellButtonVisible);
        assertTrue(pdmOfflineBinding.metricsText.contains("Unreachable / Intentionally powered down"));

        // 3. PVE Online Standalone Host:
        // No remote prefix. Shell button visible, PDM badge hidden.
        NodeStatus pveOnline = new NodeStatus(
                "pve-node1", "srv-pve", NodeStatus.Status.ONLINE,
                0.15, 4, 4_000_000_000L, 8_000_000_000L,
                20_000_000_000L, 100_000_000_000L, 1000, 2000, "8.1.4", 43200, 1000L);

        UiPresentationPolicy.CompactHostRowBindingState pveOnlineBinding =
                UiPresentationPolicy.evaluateCompactHostRowBinding(pveOnline, pveServer);

        assertEquals("pve-node1", pveOnlineBinding.displayName);
        assertEquals("", pveOnlineBinding.remoteName);
        assertFalse(pveOnlineBinding.hasRemote);
        assertEquals("ONLINE", pveOnlineBinding.statusText);
        assertTrue(pveOnlineBinding.isOnline);
        assertFalse("PDM badge hidden on PVE", pveOnlineBinding.isPdmBadgeVisible);
        assertTrue("Shell button visible on online PVE", pveOnlineBinding.isShellButtonVisible);
        assertTrue(pveOnlineBinding.metricsText.contains("CPU: 15%"));

        // 4. PVE Offline Host:
        // Offline host remains visible. Shell button hidden.
        NodeStatus pveOffline = NodeStatus.offline("pve-node2", "srv-pve");
        UiPresentationPolicy.CompactHostRowBindingState pveOfflineBinding =
                UiPresentationPolicy.evaluateCompactHostRowBinding(pveOffline, pveServer);

        assertEquals("pve-node2", pveOfflineBinding.displayName);
        assertEquals("OFFLINE", pveOfflineBinding.statusText);
        assertFalse(pveOfflineBinding.isOnline);
        assertFalse(pveOfflineBinding.isPdmBadgeVisible);
        assertFalse("Shell button hidden on offline PVE", pveOfflineBinding.isShellButtonVisible);
        assertTrue(pveOfflineBinding.metricsText.contains("Unreachable / Intentionally powered down"));
    }

    @Test
    public void testCompactHostRowAccessibilityAnnouncements() {
        NodeStatus onlineComposite = new NodeStatus(
                "remote-prod/pve-compute-1", "srv-1", NodeStatus.Status.ONLINE,
                0.22, 16, 8_000_000_000L, 32_000_000_000L,
                -1, -1, -1, -1, "8.1.4", 90000, 1000L);

        String desc = UiPresentationPolicy.formatHostRowContentDescription(onlineComposite);
        assertTrue(desc.contains("Host pve-compute-1"));
        assertTrue(desc.contains("Remote remote-prod"));
        assertTrue(desc.contains("Online"));
        assertTrue(desc.contains("CPU 22 percent"));
        assertTrue(desc.contains("RAM"));

        NodeStatus offlineComposite = new NodeStatus(
                "remote-dr/pve-standby-1", "srv-1", NodeStatus.Status.OFFLINE,
                0.0, 8, -1, -1,
                -1, -1, -1, -1, null, -1, 1000L);

        String offDesc = UiPresentationPolicy.formatHostRowContentDescription(offlineComposite);
        assertTrue(offDesc.contains("Host pve-standby-1"));
        assertTrue(offDesc.contains("Remote remote-dr"));
        assertTrue(offDesc.contains("Offline"));
        assertTrue(offDesc.contains("Unreachable or intentionally powered down"));
    }

    @Test
    public void testCompactHostRowXmlStructuralIntegrity() throws Exception {
        // Gap 2 regression: verify item_host_row.xml has tv_node_remote on a separate line
        // from tv_node_name to prevent crushing or erasing the hostname under long remotes or 200% font.
        java.io.File layoutFile = new java.io.File("app/src/main/res/layout/item_host_row.xml");
        assertTrue("item_host_row.xml must exist", layoutFile.exists());

        byte[] bytes = java.nio.file.Files.readAllBytes(layoutFile.toPath());
        String xml = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);

        assertTrue("tv_node_name must exist", xml.contains("android:id=\"@+id/tv_node_name\""));
        assertTrue("tv_node_remote must exist", xml.contains("android:id=\"@+id/tv_node_remote\""));
        assertTrue("tv_node_status must exist", xml.contains("android:id=\"@+id/tv_node_status\""));
        assertTrue("tv_node_metrics must exist", xml.contains("android:id=\"@+id/tv_node_metrics\""));
        assertTrue("tv_node_pdm_badge must exist", xml.contains("android:id=\"@+id/tv_node_pdm_badge\""));
        assertTrue("btn_node_shell must exist", xml.contains("android:id=\"@+id/btn_node_shell\""));

        // Verify tv_node_remote is NOT placed inside the header row next to tv_node_name
        int headerStart = xml.indexOf("<!-- Header: Basename + Status Chip");
        int headerEnd = xml.indexOf("</LinearLayout>", headerStart);
        String headerContent = xml.substring(headerStart, headerEnd);

        assertTrue("Header must contain tv_node_name", headerContent.contains("@+id/tv_node_name"));
        assertTrue("Header must contain tv_node_status", headerContent.contains("@+id/tv_node_status"));
        assertFalse("Header MUST NOT contain tv_node_remote to prevent crushing hostname",
                headerContent.contains("@+id/tv_node_remote"));

        // Verify tv_node_remote is on a separate line with match_parent width
        int remoteIndex = xml.indexOf("@+id/tv_node_remote");
        assertTrue("tv_node_remote must follow header", remoteIndex > headerEnd);
        assertTrue("tv_node_remote must have match_parent for clean wrapping",
                xml.contains("android:id=\"@+id/tv_node_remote\"\n        android:layout_width=\"match_parent\""));
    }
}
