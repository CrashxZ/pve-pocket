// SPDX-License-Identifier: GPL-3.0-or-later
package com.homelabpocket.model;

import com.homelabpocket.util.JsonUtil;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class MetricAggregationTest {

    @Test
    public void testCapacityWeightedCpuSum() {
        // Node 1: 8 cores at 42% CPU
        // Node 2: 4 cores at 15% CPU
        // Total cores: 12
        // Weighted CPU: (8 * 0.42 + 4 * 0.15) / 12 = (3.36 + 0.60) / 12 = 3.96 / 12 = 0.33 (33%)
        List<NodeStatus> nodes = new ArrayList<>();
        nodes.add(new NodeStatus("node-1", "srv1", NodeStatus.Status.ONLINE,
                0.42, 8, 10_000_000_000L, 16_000_000_000L,
                30_000_000_000L, 100_000_000_000L,
                1000, 2000, "8.1.4", 86400, System.currentTimeMillis()));

        nodes.add(new NodeStatus("node-2", "srv1", NodeStatus.Status.ONLINE,
                0.15, 4, 4_000_000_000L, 8_000_000_000L,
                10_000_000_000L, 50_000_000_000L,
                500, 1000, "8.1.4", 43200, System.currentTimeMillis()));

        int totalCores = 0;
        double weightedCpuSum = 0;
        for (NodeStatus n : nodes) {
            if (n.isOnline()) {
                int cores = n.cpuCount > 0 ? n.cpuCount : 1;
                totalCores += cores;
                double cpu = !Double.isNaN(n.cpuUsage) ? n.cpuUsage : 0.0;
                weightedCpuSum += (cpu * cores);
            }
        }

        double fleetCpuAvg = (weightedCpuSum / totalCores) * 100.0;
        assertEquals(12, totalCores);
        assertEquals(33.0, fleetCpuAvg, 0.001);
    }

    @Test
    public void testRamDeduplicationHostOnly() {
        // Host nodes have physical RAM:
        // Node 1: 10GB used / 16GB total
        // Node 2: 4GB used / 8GB total
        // Guests allocated:
        // Guest 1: 2GB used / 4GB max
        // Guest 2: 1GB used / 2GB max
        // Fleet RAM calculation MUST only sum host physical RAM (14GB / 24GB = 58.33%)
        // Adding guest memory would falsely inflate to 17GB / 30GB (double counting).
        long hostMemUsed = 10_000_000_000L + 4_000_000_000L;
        long hostMemTotal = 16_000_000_000L + 8_000_000_000L;

        long guestMemUsed = 2_000_000_000L + 1_000_000_000L;
        long guestMemMax = 4_000_000_000L + 2_000_000_000L;

        // Verify host total is strictly independent of guest allocations
        assertEquals(14_000_000_000L, hostMemUsed);
        assertEquals(24_000_000_000L, hostMemTotal);
        assertTrue(guestMemUsed < hostMemUsed);
        assertTrue(guestMemMax < hostMemTotal);

        double correctFleetRamPct = ((double) hostMemUsed / hostMemTotal) * 100.0;
        assertEquals(58.333, correctFleetRamPct, 0.001);
    }

    @Test
    public void testOfflineNodeHandling() {
        // Offline nodes must not contribute to CPU or memory sum
        NodeStatus online = new NodeStatus("pve1", "srv1", NodeStatus.Status.ONLINE,
                0.25, 4, 4_000_000_000L, 8_000_000_000L,
                10_000_000_000L, 50_000_000_000L,
                0, 0, "8.1.4", 1000, System.currentTimeMillis());

        NodeStatus offline = NodeStatus.offline("pve2", "srv1");

        List<NodeStatus> nodes = java.util.Arrays.asList(online, offline);

        int onlineCount = 0;
        int offlineCount = 0;
        int totalCores = 0;
        double weightedCpuSum = 0;

        for (NodeStatus n : nodes) {
            if (n.isOnline()) {
                onlineCount++;
                totalCores += n.cpuCount;
                weightedCpuSum += (n.cpuUsage * n.cpuCount);
            } else {
                offlineCount++;
            }
        }

        assertEquals(1, onlineCount);
        assertEquals(1, offlineCount);
        assertEquals(4, totalCores);
        assertEquals(25.0, (weightedCpuSum / totalCores) * 100.0, 0.001);
    }

    @Test
    public void testFormattingUtilities() {
        assertEquals("0 B", JsonUtil.formatBytes(0));
        assertEquals("512 B", JsonUtil.formatBytes(512));
        assertEquals("1.5 KB", JsonUtil.formatBytes(1536));
        assertEquals("2.0 MB", JsonUtil.formatBytes(2 * 1024 * 1024));
        assertEquals("4.0 GB", JsonUtil.formatBytes(4L * 1024 * 1024 * 1024));
        assertEquals("2.00 TB", JsonUtil.formatBytes(2L * 1024 * 1024 * 1024 * 1024));

        assertEquals("offline", JsonUtil.formatUptime(-1));
        assertEquals("0m", JsonUtil.formatUptime(0));
        assertEquals("15m", JsonUtil.formatUptime(900));
        assertEquals("2h 30m", JsonUtil.formatUptime(9000));
        assertEquals("1d 4h 12m", JsonUtil.formatUptime(86400 + 4 * 3600 + 12 * 60));
    }

    @Test
    public void testFleetCapacityAggregationPolicyWeightedCpuAndRam() {
        // Node 1: 8 cores at 42% CPU, 10GB/16GB RAM
        // Node 2: 4 cores at 15% CPU, 4GB/8GB RAM
        // Total cores: 12. Weighted CPU: 33.0%.
        // Total host RAM: 14GB / 24GB = 58.33%
        List<NodeStatus> nodes = new ArrayList<>();
        nodes.add(new NodeStatus("node-1", "srv1", NodeStatus.Status.ONLINE,
                0.42, 8, 10_000_000_000L, 16_000_000_000L,
                30_000_000_000L, 100_000_000_000L,
                1000, 2000, "8.1.4", 86400, System.currentTimeMillis()));

        nodes.add(new NodeStatus("node-2", "srv1", NodeStatus.Status.ONLINE,
                0.15, 4, 4_000_000_000L, 8_000_000_000L,
                10_000_000_000L, 50_000_000_000L,
                500, 1000, "8.1.4", 43200, System.currentTimeMillis()));

        List<GuestInfo> guests = new ArrayList<>();
        guests.add(new GuestInfo(100, "web-1", GuestInfo.GuestType.QEMU,
                GuestInfo.RunState.RUNNING, "node-1", "srv1",
                0.20, 2, 2_000_000_000L, 4_000_000_000L,
                10_000_000_000L, 20_000_000_000L, 0, 0, 1000, 2000, 5000, "", false, 1000L));
        guests.add(new GuestInfo(101, "db-1", GuestInfo.GuestType.LXC,
                GuestInfo.RunState.STOPPED, "node-2", "srv1",
                0.0, 1, 0L, 2_000_000_000L,
                5_000_000_000L, 10_000_000_000L, 0, 0, 0, 0, 0, "", false, 1000L));

        com.homelabpocket.ui.UiPresentationPolicy.FleetCapacitySummary summary =
                com.homelabpocket.ui.UiPresentationPolicy.calculateFleetCapacity(nodes, guests);

        assertEquals(2, summary.totalNodes);
        assertEquals(2, summary.onlineNodes);
        assertEquals(0, summary.offlineNodes);
        assertEquals(12, summary.totalCores);
        assertEquals(12, summary.reportedCores);

        assertTrue("CPU must be available", summary.isCpuAvailable);
        assertEquals(33.0, summary.cpuPercent, 0.05);
        assertEquals("33.0%", summary.cpuCenterText);
        assertEquals("12 vCPUs", summary.cpuCapacityText);
        assertTrue(summary.cpuContentDescription.contains("33.0 percent, 12 vCPUs"));

        assertTrue("RAM must be available", summary.isRamAvailable);
        assertEquals(58.333, summary.ramPercent, 0.05);
        assertEquals("58%", summary.ramCenterText);
        assertEquals(14_000_000_000L, summary.totalRamUsed);
        assertEquals(24_000_000_000L, summary.totalRamMax);
        assertTrue(summary.ramContentDescription.contains("58 percent"));

        // Guests
        assertEquals(1, summary.runningGuests);
        assertEquals(1, summary.stoppedGuests);
        assertEquals("GUESTS: 1 running \u2022 1 stopped", summary.guestSummaryText);
        assertEquals("HOSTS (2/2 online)", summary.hostsHeaderTitle);
    }

    @Test
    public void testMissingDataUnavailableNoFakeEmptyZeroPercentRing() {
        // Node is online but upstream reports NaN CPU usage and -1 for memory
        List<NodeStatus> nodes = new ArrayList<>();
        nodes.add(new NodeStatus("pdm-remote", "srv1", NodeStatus.Status.ONLINE,
                Double.NaN, 8, -1, -1,
                -1, -1, -1, -1, "8.1.4", 86400, System.currentTimeMillis()));

        com.homelabpocket.ui.UiPresentationPolicy.FleetCapacitySummary summary =
                com.homelabpocket.ui.UiPresentationPolicy.calculateFleetCapacity(nodes, null);

        // Unknown != 0: must report Unavailable, not 0.0%
        assertFalse("Missing CPU must not be reported as available", summary.isCpuAvailable);
        assertEquals("Unavailable", summary.cpuCenterText);
        assertNotEquals("0%", summary.cpuCenterText);
        assertNotEquals("0.0%", summary.cpuCenterText);
        assertEquals("8 vCPUs", summary.cpuCapacityText);

        assertFalse("Missing RAM must not be reported as available", summary.isRamAvailable);
        assertEquals("Unavailable", summary.ramCenterText);
        assertNotEquals("0%", summary.ramCenterText);
        assertEquals("Unavailable", summary.ramCapacityText);

        assertEquals("HOSTS (1/1 online)", summary.hostsHeaderTitle);
    }

    @Test
    public void testAllOfflineFleetHandling() {
        List<NodeStatus> nodes = new ArrayList<>();
        nodes.add(NodeStatus.offline("pve1", "srv1"));
        nodes.add(NodeStatus.offline("pve2", "srv1"));

        com.homelabpocket.ui.UiPresentationPolicy.FleetCapacitySummary summary =
                com.homelabpocket.ui.UiPresentationPolicy.calculateFleetCapacity(nodes, null);

        assertEquals(2, summary.totalNodes);
        assertEquals(0, summary.onlineNodes);
        assertEquals(2, summary.offlineNodes);

        assertFalse("CPU cannot be available when all hosts offline", summary.isCpuAvailable);
        assertEquals("Unavailable", summary.cpuCenterText);
        assertEquals("Unavailable", summary.cpuCapacityText);

        assertFalse("RAM cannot be available when all hosts offline", summary.isRamAvailable);
        assertEquals("Unavailable", summary.ramCenterText);
        assertEquals("Unavailable", summary.ramCapacityText);

        assertEquals("HOSTS (0/2 online)", summary.hostsHeaderTitle);
    }

    @Test
    public void testClampingAndZeroDenominators() {
        assertEquals(0.0, com.homelabpocket.ui.UiPresentationPolicy.clampPercent(Double.NaN), 0.001);
        assertEquals(100.0, com.homelabpocket.ui.UiPresentationPolicy.clampPercent(Double.POSITIVE_INFINITY), 0.001);
        assertEquals(0.0, com.homelabpocket.ui.UiPresentationPolicy.clampPercent(Double.NEGATIVE_INFINITY), 0.001);
        assertEquals(0.0, com.homelabpocket.ui.UiPresentationPolicy.clampPercent(-25.5), 0.001);
        assertEquals(100.0, com.homelabpocket.ui.UiPresentationPolicy.clampPercent(180.0), 0.001);
        assertEquals(42.5, com.homelabpocket.ui.UiPresentationPolicy.clampPercent(42.5), 0.001);

        // Empty node list
        com.homelabpocket.ui.UiPresentationPolicy.FleetCapacitySummary empty =
                com.homelabpocket.ui.UiPresentationPolicy.calculateFleetCapacity(null, null);
        assertEquals(0, empty.totalNodes);
        assertFalse(empty.isCpuAvailable);
        assertEquals("Unavailable", empty.cpuCenterText);
        assertEquals("HOSTS (0 online)", empty.hostsHeaderTitle);
    }

    @Test
    public void testProductionPolicyRegressionFiniteOverflowAndBoundedSaturation() {
        // Gap 3 regression: finite Double.MAX_VALUE CPU * cores must NOT overflow to fake 0%
        // but must saturate honestly at 100.0%
        List<NodeStatus> nodes = new ArrayList<>();
        nodes.add(new NodeStatus("node-overcommit", "srv1", NodeStatus.Status.ONLINE,
                Double.MAX_VALUE, 16, 20_000_000_000L, 16_000_000_000L,
                -1, -1, -1, -1, "8.1.4", 3600, System.currentTimeMillis()));

        com.homelabpocket.ui.UiPresentationPolicy.FleetCapacitySummary summary =
                com.homelabpocket.ui.UiPresentationPolicy.calculateFleetCapacity(nodes, null);

        assertTrue("Overflowing CPU must remain available as bounded saturation", summary.isCpuAvailable);
        assertEquals(100.0, summary.cpuPercent, 0.001);
        assertEquals("100.0%", summary.cpuCenterText);
        assertNotEquals("0.0%", summary.cpuCenterText);
        assertNotEquals("0%", summary.cpuCenterText);
        assertEquals("16 vCPUs", summary.cpuCapacityText);

        // Overcommitted RAM (used > max) must also saturate at 100%, not fake 0%
        assertTrue("Saturated RAM must be available", summary.isRamAvailable);
        assertEquals(100.0, summary.ramPercent, 0.001);
        assertEquals("100%", summary.ramCenterText);
        assertNotEquals("0%", summary.ramCenterText);
    }

    @Test
    public void testProductionPolicyRegressionZeroCoresAndZeroDenominator() {
        // Gap 3 regression: zero cores must NOT become fabricated 1
        // When a node reports 0 cores (e.g. unknown capacity), cores remain 0,
        // and CPU capacity is reported as Unavailable, never fake 0%
        List<NodeStatus> nodes = new ArrayList<>();
        nodes.add(new NodeStatus("node-zero-cores", "srv1", NodeStatus.Status.ONLINE,
                0.50, 0, 4_000_000_000L, 8_000_000_000L,
                -1, -1, -1, -1, "8.1.4", 3600, System.currentTimeMillis()));

        com.homelabpocket.ui.UiPresentationPolicy.FleetCapacitySummary summary =
                com.homelabpocket.ui.UiPresentationPolicy.calculateFleetCapacity(nodes, null);

        assertEquals(0, summary.totalCores);
        assertEquals(0, summary.reportedCores);
        assertFalse("Node with 0 cores must not report CPU available", summary.isCpuAvailable);
        assertEquals("Unavailable", summary.cpuCenterText);
        assertNotEquals("0%", summary.cpuCenterText);
        assertNotEquals("0.0%", summary.cpuCenterText);
        assertEquals("Unavailable", summary.cpuCapacityText);

        // RAM remains available since RAM was reported
        assertTrue("RAM must be available when valid memory reported", summary.isRamAvailable);
        assertEquals(50.0, summary.ramPercent, 0.001);
        assertEquals("50%", summary.ramCenterText);
    }

    @Test
    public void testProductionPolicyRegressionNaNAndInfiniteCpu() {
        // Node with POSITIVE_INFINITY CPU usage
        List<NodeStatus> infNodes = new ArrayList<>();
        infNodes.add(new NodeStatus("node-inf", "srv1", NodeStatus.Status.ONLINE,
                Double.POSITIVE_INFINITY, 8, 4_000_000_000L, 8_000_000_000L,
                -1, -1, -1, -1, "8.1.4", 3600, System.currentTimeMillis()));

        com.homelabpocket.ui.UiPresentationPolicy.FleetCapacitySummary infSummary =
                com.homelabpocket.ui.UiPresentationPolicy.calculateFleetCapacity(infNodes, null);

        assertTrue("Infinite CPU usage must saturate honestly at 100%", infSummary.isCpuAvailable);
        assertEquals(100.0, infSummary.cpuPercent, 0.001);
        assertEquals("100.0%", infSummary.cpuCenterText);
        assertNotEquals("0.0%", infSummary.cpuCenterText);

        // Node with NaN CPU usage and 0 cores
        List<NodeStatus> nanNodes = new ArrayList<>();
        nanNodes.add(new NodeStatus("node-nan", "srv1", NodeStatus.Status.ONLINE,
                Double.NaN, 0, -1, -1,
                -1, -1, -1, -1, "8.1.4", 3600, System.currentTimeMillis()));

        com.homelabpocket.ui.UiPresentationPolicy.FleetCapacitySummary nanSummary =
                com.homelabpocket.ui.UiPresentationPolicy.calculateFleetCapacity(nanNodes, null);

        assertFalse("NaN CPU with 0 cores must be Unavailable", nanSummary.isCpuAvailable);
        assertEquals("Unavailable", nanSummary.cpuCenterText);
        assertFalse("Missing RAM must be Unavailable", nanSummary.isRamAvailable);
        assertEquals("Unavailable", nanSummary.ramCenterText);
    }
}
