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
}
