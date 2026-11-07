// SPDX-License-Identifier: GPL-3.0-or-later
// Homelab Pocket — PDM Partial Fleet GREEN Regression & Integration Matrix Test
package com.homelabpocket.api;

import com.homelabpocket.model.AuthCredentials;
import com.homelabpocket.model.GuestInfo;
import com.homelabpocket.model.NodeStatus;
import com.homelabpocket.model.ServerConfig;
import com.homelabpocket.model.StoragePool;
import com.homelabpocket.security.PinnedTrustManager;
import com.homelabpocket.ui.UiPresentationPolicy;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Concrete behavioral test matrix validating the authorized partial-fleet repair.
 * Exercises the actual production PDM HTTP driver, JSON parser, cache-merging
 * policy, and presentation helpers under synthetic loopback HTTPS fixtures.
 */
public class PdmPartialFleetGreenRegressionTest {

    private static HttpsServer mockServer;
    private static int mockPort;
    private static String mockFingerprint;

    private static final AtomicReference<String> resourcesPayload = new AtomicReference<>();
    private static final AtomicInteger httpStatusOverride = new AtomicInteger(200);
    private static final AtomicInteger postActionCount = new AtomicInteger(0);

    @BeforeClass
    public static void setUpClass() throws Exception {
        File ksFile = new File("app/src/test/resources/test_fixtures.p12");
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream is = new FileInputStream(ksFile)) {
            ks.load(is, "testpass".toCharArray());
        }

        X509Certificate cert = (X509Certificate) ks.getCertificate("local-ip");
        mockFingerprint = PinnedTrustManager.computeFingerprint(cert);

        SSLContext sslCtx = SSLContext.getInstance("TLS");
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "testpass".toCharArray());
        sslCtx.init(kmf.getKeyManagers(), null, null);

        mockServer = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockServer.setHttpsConfigurator(new HttpsConfigurator(sslCtx) {
            @Override
            public void configure(HttpsParameters params) {
                try {
                    SSLEngine engine = sslCtx.createSSLEngine();
                    params.setNeedClientAuth(false);
                    params.setCipherSuites(engine.getEnabledCipherSuites());
                    params.setProtocols(engine.getEnabledProtocols());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });

        // 1. Auth ticket endpoint
        mockServer.createContext("/api2/json/access/ticket", exchange -> {
            String json = "{\"data\":{\"ticket\":\"TEST_TICKET\",\"CSRFPreventionToken\":\"TEST_CSRF\"}}";
            byte[] bytes = json.getBytes("UTF-8");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        // 2. Canonical resources endpoint: /api2/json/resources/list
        mockServer.createContext("/api2/json/resources/list", exchange -> {
            int code = httpStatusOverride.get();
            if (code != 200) {
                String err = "{\"message\":\"HTTP error " + code + "\"}";
                byte[] bytes = err.getBytes("UTF-8");
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(code, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
                return;
            }

            String body = resourcesPayload.get();
            if (body == null) {
                body = "{\"data\":[]}";
            }
            byte[] bytes = body.getBytes("UTF-8");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        // 3. Power action routes: e.g. /api2/json/pve/remotes/...
        mockServer.createContext("/api2/json/pve/remotes/", exchange -> {
            postActionCount.incrementAndGet();
            String resp = "{\"data\":\"pve:cluster-west!UPID:pve1:00001:00001:start:101:root@pam:\"}";
            byte[] bytes = resp.getBytes("UTF-8");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        mockServer.start();
        mockPort = mockServer.getAddress().getPort();
    }

    @AfterClass
    public static void tearDownClass() {
        if (mockServer != null) {
            mockServer.stop(0);
        }
    }

    @Before
    public void setUp() {
        resourcesPayload.set(null);
        httpStatusOverride.set(200);
        postActionCount.set(0);
    }

    private ServerConfig createConfig(String id) {
        return new ServerConfig(id, "PDM Cluster", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint, true, false);
    }

    private AuthCredentials createCreds() {
        return AuthCredentials.ticket("root@pam", "ticket", "csrf", System.currentTimeMillis() + 3600000);
    }

    // -------------------------------------------------------------------------
    // 1 & 2: Ahealthy + Bembeddederror in BOTH Orders
    // -------------------------------------------------------------------------

    @Test
    public void testPartialFleetBothOrdersAHealthyBErrorAndBErrorAHealthy() throws Exception {
        ServerConfig config = createConfig("pdm-partial");
        PdmApiDriver driver = new PdmApiDriver(config);
        AuthCredentials creds = createCreds();

        String groupA = "{\"remote\":\"home-lab\",\"resources\":[" +
                "{\"type\":\"pve-node\",\"node\":\"pve-node-01\",\"status\":\"online\",\"cpu\":0.15,\"maxcpu\":8.0,\"mem\":8000000000,\"maxmem\":16000000000,\"uptime\":3600}," +
                "{\"type\":\"pve-qemu\",\"node\":\"pve-node-01\",\"vmid\":101,\"name\":\"vm-healthy\",\"status\":\"running\",\"cpu\":0.05,\"maxcpu\":2.0,\"mem\":2000000000,\"maxmem\":4000000000,\"uptime\":1800}," +
                "{\"type\":\"pve-storage\",\"node\":\"pve-node-01\",\"storage\":\"local-zfs\",\"disk\":100000000000,\"maxdisk\":500000000000,\"status\":\"available\",\"shared\":false}" +
                "]}";
        String groupB = "{\"remote\":\"home-lab-2\",\"error\":\"Remote home-lab-2 connection failed\",\"resources\":[]}";

        // Order 1: A then B
        resourcesPayload.set("{\"data\":[" + groupA + "," + groupB + "]}");
        PdmApiDriver.PdmFleetResult res1 = driver.fetchFleetResources(creds);

        assertTrue("Must be partial failure", res1.isPartialFailure);
        assertFalse("Must not be all failed", res1.isAllFailed);
        assertEquals(1, res1.successfulRemotes.size());
        assertTrue(res1.successfulRemotes.contains("home-lab"));
        assertEquals(1, res1.failedRemotes.size());
        assertTrue(res1.failedRemotes.contains("home-lab-2"));
        assertEquals("Remote home-lab-2 connection failed", res1.remoteErrors.get("home-lab-2"));
        assertEquals(1, res1.nodes.size());
        assertEquals("home-lab/pve-node-01", res1.nodes.get(0).node);
        assertEquals(1, res1.guests.size());
        assertEquals(101, res1.guests.get(0).vmid);
        assertEquals(1, res1.storagePools.size());

        // Order 2: B then A (reverse order must yield identical scoped result)
        resourcesPayload.set("{\"data\":[" + groupB + "," + groupA + "]}");
        PdmApiDriver.PdmFleetResult res2 = driver.fetchFleetResources(creds);

        assertTrue("Must be partial failure in reverse order", res2.isPartialFailure);
        assertFalse("Must not be all failed in reverse order", res2.isAllFailed);
        assertEquals(1, res2.successfulRemotes.size());
        assertTrue(res2.successfulRemotes.contains("home-lab"));
        assertEquals(1, res2.failedRemotes.size());
        assertTrue(res2.failedRemotes.contains("home-lab-2"));
        assertEquals("Remote home-lab-2 connection failed", res2.remoteErrors.get("home-lab-2"));
        assertEquals(1, res2.nodes.size());
        assertEquals("home-lab/pve-node-01", res2.nodes.get(0).node);
        assertEquals(1, res2.guests.size());
        assertEquals(101, res2.guests.get(0).vmid);
        assertEquals(1, res2.storagePools.size());
    }

    // -------------------------------------------------------------------------
    // 3: Cold Start Without Cache Preserves Typed Unknown Placeholder
    // -------------------------------------------------------------------------

    @Test
    public void testColdStartWithoutCachePreservesTypedStatus() throws Exception {
        ServerConfig config = createConfig("pdm-cold");
        PdmApiDriver driver = new PdmApiDriver(config);
        AuthCredentials creds = createCreds();

        resourcesPayload.set("{\"data\":[" +
                "{\"remote\":\"home-lab\",\"resources\":[" +
                "{\"type\":\"pve-node\",\"node\":\"pve-node-01\",\"status\":\"online\",\"cpu\":0.15,\"maxcpu\":8.0,\"mem\":8000000000,\"maxmem\":16000000000,\"uptime\":3600}" +
                "]}," +
                "{\"remote\":\"home-lab-2\",\"error\":\"Remote home-lab-2 connection failed\",\"resources\":[]}" +
                "]}");

        PdmApiDriver.PdmFleetResult res = driver.fetchFleetResources(creds);

        List<ServerConfig> servers = Collections.singletonList(config);
        UiPresentationPolicy.FleetCacheMergeResult mergeResult = UiPresentationPolicy.mergeFleetData(
                null, null, null, servers,
                res.nodes, res.guests, res.firstError,
                Collections.singleton(config.id), Collections.singleton(config.id), Collections.emptySet(),
                Collections.singleton(config.id + "/home-lab"),
                Collections.singleton(config.id + "/home-lab-2"),
                res.remoteErrors);

        assertEquals(2, mergeResult.nodes.size());
        NodeStatus liveNode = mergeResult.nodes.get(0);
        assertEquals("home-lab/pve-node-01", liveNode.node);
        assertTrue(liveNode.isOnline());

        NodeStatus placeholderNode = mergeResult.nodes.get(1);
        assertEquals("home-lab-2", placeholderNode.node);
        assertEquals(NodeStatus.Status.UNKNOWN, placeholderNode.status);
        assertFalse(placeholderNode.isOnline());

        // Overview / Dashboard view binding check
        UiPresentationPolicy.FleetCapacitySummary summary =
                UiPresentationPolicy.calculateFleetCapacity(mergeResult.nodes, mergeResult.guests);

        // Placeholder is an unknown remote with unproven host count; proven host count is 1
        assertEquals(1, summary.totalNodes);
        assertEquals(1, summary.onlineNodes);
        assertEquals(0, summary.offlineNodes);
        assertEquals(1, summary.unknownRemotes);
        assertTrue(summary.isPartialCoverage);
        assertEquals("8 vCPUs (partial coverage)", summary.cpuCapacityText);
        assertTrue(summary.ramCapacityText.contains("(partial coverage)"));
        assertEquals("HOSTS (1 online \u2022 1 remote unknown)", summary.hostsHeaderTitle);
        assertEquals("GUESTS: 0 running \u2022 0 stopped (partial coverage)", summary.guestSummaryText);
    }

    // -------------------------------------------------------------------------
    // 4: Cached Retained Failed Remote Scope Retained as Stale UNKNOWN
    // -------------------------------------------------------------------------

    @Test
    public void testCachedRetainedFailedRemoteScopeMarkedStale() throws Exception {
        ServerConfig config = createConfig("pdm-warm");
        List<ServerConfig> servers = Collections.singletonList(config);
        String prov = UiPresentationPolicy.computeServerProvenance(config);
        Map<String, String> cachedProvenances = Collections.singletonMap(config.id, prov);

        // Previous warm cache before failure
        NodeStatus cachedA = new NodeStatus("home-lab/pve-node-01", config.id, NodeStatus.Status.ONLINE,
                0.15, 8, 8000000000L, 16000000000L, 0, 0, 0, 0, "8.1.4", 3600, 1000L);
        NodeStatus cachedB = new NodeStatus("home-lab-2/pve-node-02", config.id, NodeStatus.Status.ONLINE,
                0.10, 4, 4000000000L, 8000000000L, 0, 0, 0, 0, "8.1.4", 3600, 1000L);
        GuestInfo cachedGuestB = new GuestInfo(201, "vm-db-2", GuestInfo.GuestType.QEMU,
                GuestInfo.RunState.RUNNING, "home-lab-2/pve-node-02", config.id,
                0.05, 2, 2000000000L, 4000000000L, 0, 0, 0, 0, 1800, "", false, 1000L);

        List<NodeStatus> cachedNodes = Arrays.asList(cachedA, cachedB);
        List<GuestInfo> cachedGuests = Collections.singletonList(cachedGuestB);

        // Fresh fetch where Remote B fails with error
        NodeStatus freshA = new NodeStatus("home-lab/pve-node-01", config.id, NodeStatus.Status.ONLINE,
                0.20, 8, 9000000000L, 16000000000L, 0, 0, 0, 0, "8.1.4", 7200, 2000L);

        UiPresentationPolicy.FleetCacheMergeResult mergeResult = UiPresentationPolicy.mergeFleetData(
                cachedNodes, cachedGuests, cachedProvenances, servers,
                Collections.singletonList(freshA), Collections.emptyList(), "Remote 'home-lab-2' reported error",
                Collections.singleton(config.id), Collections.singleton(config.id), Collections.emptySet(),
                Collections.singleton(config.id + "/home-lab"),
                Collections.singleton(config.id + "/home-lab-2"),
                Collections.singletonMap("home-lab-2", "Remote home-lab-2 connection failed"));

        assertEquals(2, mergeResult.nodes.size());

        NodeStatus mergedA = null;
        NodeStatus mergedB = null;
        for (NodeStatus n : mergeResult.nodes) {
            if ("home-lab/pve-node-01".equals(n.node)) mergedA = n;
            if ("home-lab-2/pve-node-02".equals(n.node)) mergedB = n;
        }
        assertNotNull("Merged nodes must contain healthy home-lab", mergedA);
        assertNotNull("Merged nodes must contain retained home-lab-2", mergedB);

        // Sibling A updated with live stats
        assertEquals("home-lab/pve-node-01", mergedA.node);
        assertEquals(0.20, mergedA.cpuUsage, 0.001);
        assertTrue(mergedA.isOnline());

        // Failed Remote B retained but status strictly converted to UNKNOWN (stale)
        assertEquals("home-lab-2/pve-node-02", mergedB.node);
        assertEquals(NodeStatus.Status.UNKNOWN, mergedB.status);
        assertFalse(mergedB.isOnline());

        // Metrics formatted for host row on Overview must explicitly disclose stale
        String rowMetrics = UiPresentationPolicy.formatHostRowMetrics(mergedB);
        assertTrue("Row metrics must be labeled stale", rowMetrics.contains("(stale)"));

        // Retained guest on failed remote converted to UNKNOWN, not STOPPED
        assertEquals(1, mergeResult.guests.size());
        GuestInfo mergedGuestB = mergeResult.guests.get(0);
        assertEquals(GuestInfo.RunState.UNKNOWN, mergedGuestB.state);

        UiPresentationPolicy.FleetCapacitySummary summary =
                UiPresentationPolicy.calculateFleetCapacity(mergeResult.nodes, mergeResult.guests);

        assertEquals(1, summary.unknownGuests);
        assertEquals(0, summary.runningGuests);
        assertEquals(0, summary.stoppedGuests);
        assertEquals("GUESTS: 0 running \u2022 0 stopped \u2022 1 unknown", summary.guestSummaryText);

        // B's 4 cores must NOT count towards supposedly live fleet capacity
        assertEquals(8, summary.totalCores);
        assertEquals("8 vCPUs (partial coverage)", summary.cpuCapacityText);
    }

    // -------------------------------------------------------------------------
    // 5: Duplicates Across Remotes Never Mix
    // -------------------------------------------------------------------------

    @Test
    public void testDuplicatesAcrossRemotesNeverMix() throws Exception {
        ServerConfig config = createConfig("pdm-dup");
        PdmApiDriver driver = new PdmApiDriver(config);
        AuthCredentials creds = createCreds();

        // Both remote A and remote B have a node named "pve-node-01" and VMID 100
        resourcesPayload.set("{\"data\":[" +
                "{\"remote\":\"cluster-east\",\"resources\":[" +
                "{\"type\":\"pve-node\",\"node\":\"pve-node-01\",\"status\":\"online\",\"cpu\":0.10,\"maxcpu\":4.0,\"mem\":4000000000,\"maxmem\":8000000000,\"uptime\":1000}," +
                "{\"type\":\"pve-qemu\",\"node\":\"pve-node-01\",\"vmid\":100,\"name\":\"vm-east\",\"status\":\"running\",\"cpu\":0.05,\"maxcpu\":1.0,\"mem\":1000000000,\"maxmem\":2000000000,\"uptime\":500}" +
                "]}," +
                "{\"remote\":\"cluster-west\",\"resources\":[" +
                "{\"type\":\"pve-node\",\"node\":\"pve-node-01\",\"status\":\"online\",\"cpu\":0.20,\"maxcpu\":8.0,\"mem\":8000000000,\"maxmem\":16000000000,\"uptime\":2000}," +
                "{\"type\":\"pve-qemu\",\"node\":\"pve-node-01\",\"vmid\":100,\"name\":\"vm-west\",\"status\":\"running\",\"cpu\":0.08,\"maxcpu\":2.0,\"mem\":2000000000,\"maxmem\":4000000000,\"uptime\":600}" +
                "]}" +
                "]}");

        PdmApiDriver.PdmFleetResult res = driver.fetchFleetResources(creds);
        assertEquals(2, res.nodes.size());
        assertEquals("cluster-east/pve-node-01", res.nodes.get(0).node);
        assertEquals("cluster-west/pve-node-01", res.nodes.get(1).node);

        assertEquals(2, res.guests.size());
        assertEquals("cluster-east/pve-node-01", res.guests.get(0).node);
        assertEquals("vm-east", res.guests.get(0).name);
        assertEquals("cluster-west/pve-node-01", res.guests.get(1).node);
        assertEquals("vm-west", res.guests.get(1).name);

        UiPresentationPolicy.FleetCacheMergeResult mergeResult = UiPresentationPolicy.mergeFleetData(
                null, null, null, Collections.singletonList(config),
                res.nodes, res.guests, null,
                Collections.singleton(config.id), Collections.singleton(config.id), Collections.emptySet(),
                res.successfulRemotes, Collections.emptySet(), Collections.emptyMap());

        assertEquals(2, mergeResult.nodes.size());
        assertEquals(2, mergeResult.guests.size());
    }

    // -------------------------------------------------------------------------
    // 6: Failed Remote Embedded Resources Ignored
    // -------------------------------------------------------------------------

    @Test
    public void testFailedRemoteEmbeddedResourcesIgnored() throws Exception {
        ServerConfig config = createConfig("pdm-embedded");
        PdmApiDriver driver = new PdmApiDriver(config);
        AuthCredentials creds = createCreds();

        // Remote B has error, but also has embedded resources returned
        resourcesPayload.set("{\"data\":[" +
                "{\"remote\":\"home-lab\",\"resources\":[" +
                "{\"type\":\"pve-node\",\"node\":\"pve-node-01\",\"status\":\"online\",\"cpu\":0.15,\"maxcpu\":8.0,\"mem\":8000000000,\"maxmem\":16000000000,\"uptime\":3600}" +
                "]}," +
                "{\"remote\":\"home-lab-2\",\"error\":\"Connection timeout\",\"resources\":[" +
                "{\"type\":\"pve-node\",\"node\":\"pve-node-02\",\"status\":\"online\",\"cpu\":0.50,\"maxcpu\":4.0,\"mem\":4000000000,\"maxmem\":8000000000,\"uptime\":3600}," +
                "{\"type\":\"pve-qemu\",\"node\":\"pve-node-02\",\"vmid\":999,\"name\":\"vm-ghost\",\"status\":\"running\",\"cpu\":0.10,\"maxcpu\":1.0,\"mem\":1000000000,\"maxmem\":2000000000,\"uptime\":100}" +
                "]}" +
                "]}");

        PdmApiDriver.PdmFleetResult res = driver.fetchFleetResources(creds);

        // Failed remote embedded resources must strictly NOT be treated as fresh
        assertEquals(1, res.nodes.size());
        assertEquals("home-lab/pve-node-01", res.nodes.get(0).node);
        assertEquals(0, res.guests.size());
        assertTrue(res.failedRemotes.contains("home-lab-2"));
    }

    // -------------------------------------------------------------------------
    // 7: All Failed PDM Remotes Surfaces Typed Unknown Status (No Fabricated 0)
    // -------------------------------------------------------------------------

    @Test
    public void testAllFailedPdmRemotesTypedUnknownStatusNoFakeZeroOrOffline() throws Exception {
        ServerConfig config = createConfig("pdm-all-fail");
        PdmApiDriver driver = new PdmApiDriver(config);
        AuthCredentials creds = createCreds();

        resourcesPayload.set("{\"data\":[" +
                "{\"remote\":\"remote-alpha\",\"error\":\"Remote alpha offline\",\"resources\":[]}," +
                "{\"remote\":\"remote-beta\",\"error\":\"Remote beta offline\",\"resources\":[]}" +
                "]}");

        PdmApiDriver.PdmFleetResult res = driver.fetchFleetResources(creds);
        assertTrue(res.isAllFailed);
        assertFalse(res.isPartialFailure);
        assertEquals(2, res.failedRemotes.size());

        List<ServerConfig> servers = Collections.singletonList(config);
        Set<String> failedRemoteKeys = new java.util.LinkedHashSet<>();
        failedRemoteKeys.add(config.id + "/remote-alpha");
        failedRemoteKeys.add(config.id + "/remote-beta");

        UiPresentationPolicy.FleetCacheMergeResult mergeResult = UiPresentationPolicy.mergeFleetData(
                null, null, null, servers,
                res.nodes, res.guests, res.firstError,
                Collections.emptySet(), Collections.emptySet(), Collections.emptySet(),
                Collections.emptySet(), failedRemoteKeys, res.remoteErrors);

        assertEquals(2, mergeResult.nodes.size());
        for (NodeStatus n : mergeResult.nodes) {
            assertEquals(NodeStatus.Status.UNKNOWN, n.status);
            assertFalse(n.isOnline());
        }

        UiPresentationPolicy.FleetCapacitySummary summary =
                UiPresentationPolicy.calculateFleetCapacity(mergeResult.nodes, mergeResult.guests);

        // Remote placeholders represent unproven hosts from unknown remote scopes
        assertEquals(0, summary.totalNodes);
        assertEquals(0, summary.onlineNodes);
        assertEquals(0, summary.offlineNodes);
        assertEquals(2, summary.unknownRemotes);
        assertFalse("CPU cannot be available when all hosts unknown/offline", summary.isCpuAvailable);
        assertEquals("Unavailable", summary.cpuCenterText);
        assertEquals("Unavailable", summary.cpuCapacityText);
        assertFalse("RAM cannot be available when all hosts unknown/offline", summary.isRamAvailable);
        assertEquals("Unavailable", summary.ramCenterText);
        assertEquals("Unavailable", summary.ramCapacityText);
        assertEquals("HOSTS (0 online \u2022 2 remotes unknown)", summary.hostsHeaderTitle);
        assertEquals("GUESTS: Unavailable", summary.guestSummaryText);
    }

    // -------------------------------------------------------------------------
    // 8: Whole PDM HTTP Error Fails Closed and Preserves Labelled Cache
    // -------------------------------------------------------------------------

    @Test
    public void testWholePdmHttpFailurePreservesLabelledCache() throws Exception {
        ServerConfig config = createConfig("pdm-http-err");
        PdmApiDriver driver = new PdmApiDriver(config);
        AuthCredentials creds = createCreds();

        // 1. Warm cache
        NodeStatus cachedA = new NodeStatus("home-lab/pve-node-01", config.id, NodeStatus.Status.ONLINE,
                0.15, 8, 8000000000L, 16000000000L, 0, 0, 0, 0, "8.1.4", 3600, 1000L);
        List<NodeStatus> cachedNodes = Collections.singletonList(cachedA);
        Map<String, String> cachedProvenances = Collections.singletonMap(
                config.id, UiPresentationPolicy.computeServerProvenance(config));

        // 2. HTTP 500 on whole PDM endpoint
        httpStatusOverride.set(500);
        try {
            driver.fetchFleetResources(creds);
            fail("Whole PDM HTTP 500 must throw ApiException");
        } catch (ApiException e) {
            assertEquals(500, e.getHttpCode());
        }

        // Cache merge on global failure preserves previously cached data
        UiPresentationPolicy.FleetCacheMergeResult mergeResult = UiPresentationPolicy.mergeFleetData(
                cachedNodes, null, cachedProvenances, Collections.singletonList(config),
                Collections.emptyList(), Collections.emptyList(), "HTTP 500 Internal Server Error",
                Collections.emptySet(), Collections.emptySet(), Collections.emptySet());

        assertEquals(1, mergeResult.nodes.size());
        assertEquals("home-lab/pve-node-01", mergeResult.nodes.get(0).node);

        UiPresentationPolicy.ErrorBannerState banner =
                UiPresentationPolicy.evaluateErrorBanner("HTTP 500", true, 1000L);
        assertTrue(banner.isVisible);
        assertTrue("Banner must indicate stale cached data", banner.bannerText.contains("stale / showing cached data"));
    }

    // -------------------------------------------------------------------------
    // 9: Successful Empty Clears Vanished Data
    // -------------------------------------------------------------------------

    @Test
    public void testSuccessfulEmptyClearsVanishedData() throws Exception {
        ServerConfig config = createConfig("pdm-empty");
        PdmApiDriver driver = new PdmApiDriver(config);
        AuthCredentials creds = createCreds();

        NodeStatus cachedA = new NodeStatus("home-lab/pve-node-01", config.id, NodeStatus.Status.ONLINE,
                0.15, 8, 8000000000L, 16000000000L, 0, 0, 0, 0, "8.1.4", 3600, 1000L);
        List<NodeStatus> cachedNodes = Collections.singletonList(cachedA);
        Map<String, String> cachedProvenances = Collections.singletonMap(
                config.id, UiPresentationPolicy.computeServerProvenance(config));

        resourcesPayload.set("{\"data\":[]}");
        PdmApiDriver.PdmFleetResult res = driver.fetchFleetResources(creds);

        assertFalse(res.isAllFailed);
        assertFalse(res.isPartialFailure);
        assertEquals(0, res.nodes.size());

        UiPresentationPolicy.FleetCacheMergeResult mergeResult = UiPresentationPolicy.mergeFleetData(
                cachedNodes, null, cachedProvenances, Collections.singletonList(config),
                res.nodes, res.guests, null,
                Collections.singleton(config.id), Collections.singleton(config.id), Collections.emptySet());

        // Successful empty result must authoritatively clear previously cached data
        assertEquals(0, mergeResult.nodes.size());
    }

    // -------------------------------------------------------------------------
    // 10: Recover Remote B Becomes Fresh
    // -------------------------------------------------------------------------

    @Test
    public void testRecoverRemoteBBecomesFresh() throws Exception {
        ServerConfig config = createConfig("pdm-recover");
        List<ServerConfig> servers = Collections.singletonList(config);
        String prov = UiPresentationPolicy.computeServerProvenance(config);
        Map<String, String> cachedProvenances = Collections.singletonMap(config.id, prov);

        // Previous cache where B was retained as stale UNKNOWN
        NodeStatus staleB = new NodeStatus("home-lab-2/pve-node-02", config.id, NodeStatus.Status.UNKNOWN,
                Double.NaN, 4, -1, 8000000000L, -1, -1, -1, -1, null, -1, 1000L);
        List<NodeStatus> cachedNodes = Collections.singletonList(staleB);

        // Fresh fetch where Remote B recovers
        NodeStatus freshB = new NodeStatus("home-lab-2/pve-node-02", config.id, NodeStatus.Status.ONLINE,
                0.12, 4, 3000000000L, 8000000000L, 0, 0, 0, 0, "8.1.4", 100, 2000L);

        UiPresentationPolicy.FleetCacheMergeResult mergeResult = UiPresentationPolicy.mergeFleetData(
                cachedNodes, null, cachedProvenances, servers,
                Collections.singletonList(freshB), Collections.emptyList(), null,
                Collections.singleton(config.id), Collections.singleton(config.id), Collections.emptySet(),
                Collections.singleton(config.id + "/home-lab-2"), Collections.emptySet(), Collections.emptyMap());

        assertEquals(1, mergeResult.nodes.size());
        NodeStatus node = mergeResult.nodes.get(0);
        assertEquals("home-lab-2/pve-node-02", node.node);
        assertEquals(NodeStatus.Status.ONLINE, node.status);
        assertTrue(node.isOnline());
    }

    // -------------------------------------------------------------------------
    // 11: Removal of Remote B Purged From Cache
    // -------------------------------------------------------------------------

    @Test
    public void testRemovalRemoteBPurgedFromCache() throws Exception {
        ServerConfig config = createConfig("pdm-removal");
        List<ServerConfig> servers = Collections.singletonList(config);
        String prov = UiPresentationPolicy.computeServerProvenance(config);
        Map<String, String> cachedProvenances = Collections.singletonMap(config.id, prov);

        NodeStatus cachedA = new NodeStatus("home-lab/pve-node-01", config.id, NodeStatus.Status.ONLINE,
                0.15, 8, 8000000000L, 16000000000L, 0, 0, 0, 0, "8.1.4", 3600, 1000L);
        NodeStatus cachedB = new NodeStatus("home-lab-2/pve-node-02", config.id, NodeStatus.Status.ONLINE,
                0.10, 4, 4000000000L, 8000000000L, 0, 0, 0, 0, "8.1.4", 3600, 1000L);
        List<NodeStatus> cachedNodes = Arrays.asList(cachedA, cachedB);

        // Remote B was unlinked from PDM: only Remote A returns
        NodeStatus freshA = new NodeStatus("home-lab/pve-node-01", config.id, NodeStatus.Status.ONLINE,
                0.15, 8, 8000000000L, 16000000000L, 0, 0, 0, 0, "8.1.4", 3600, 2000L);

        UiPresentationPolicy.FleetCacheMergeResult mergeResult = UiPresentationPolicy.mergeFleetData(
                cachedNodes, null, cachedProvenances, servers,
                Collections.singletonList(freshA), Collections.emptyList(), null,
                Collections.singleton(config.id), Collections.singleton(config.id), Collections.emptySet(),
                Collections.singleton(config.id + "/home-lab"), Collections.emptySet(), Collections.emptyMap());

        assertEquals(1, mergeResult.nodes.size());
        assertEquals("home-lab/pve-node-01", mergeResult.nodes.get(0).node);
    }

    // -------------------------------------------------------------------------
    // 12: Endpoint Replacement Invalidates Cache
    // -------------------------------------------------------------------------

    @Test
    public void testEndpointReplacementInvalidatesCache() throws Exception {
        ServerConfig oldConfig = createConfig("pdm-rep");
        String oldProv = UiPresentationPolicy.computeServerProvenance(oldConfig);
        Map<String, String> cachedProvenances = Collections.singletonMap(oldConfig.id, oldProv);

        NodeStatus cachedNode = new NodeStatus("home-lab/pve-node-01", oldConfig.id, NodeStatus.Status.ONLINE,
                0.15, 8, 8000000000L, 16000000000L, 0, 0, 0, 0, "8.1.4", 3600, 1000L);
        List<NodeStatus> cachedNodes = Collections.singletonList(cachedNode);

        // Configuration changed (new host endpoint under same ID)
        ServerConfig newConfig = new ServerConfig(oldConfig.id, "Replaced PDM", "192.0.2.50", 8443,
                ServerConfig.ServerType.PDM, "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99",
                true, false);

        // New fetch fails with connection error
        UiPresentationPolicy.FleetCacheMergeResult mergeResult = UiPresentationPolicy.mergeFleetData(
                cachedNodes, null, cachedProvenances, Collections.singletonList(newConfig),
                Collections.emptyList(), Collections.emptyList(), "Failed to connect to 192.0.2.50",
                Collections.emptySet(), Collections.emptySet(), Collections.emptySet());

        // Cache must be invalidated because endpoint provenance changed
        assertEquals(0, mergeResult.nodes.size());
    }

    // -------------------------------------------------------------------------
    // 13: Detail & Storage Healthy Target Succeeds While Sibling Error Throws
    // -------------------------------------------------------------------------

    @Test
    public void testDetailAndStorageHealthyTargetSucceedsWhileFailedTargetThrows() throws Exception {
        ServerConfig config = createConfig("pdm-detail-storage");
        PdmApiDriver driver = new PdmApiDriver(config);
        AuthCredentials creds = createCreds();

        resourcesPayload.set("{\"data\":[" +
                "{\"remote\":\"home-lab\",\"resources\":[" +
                "{\"type\":\"pve-node\",\"node\":\"pve-node-01\",\"status\":\"online\",\"cpu\":0.15,\"maxcpu\":8.0,\"mem\":8000000000,\"maxmem\":16000000000,\"uptime\":3600}," +
                "{\"type\":\"pve-qemu\",\"node\":\"pve-node-01\",\"vmid\":101,\"name\":\"vm-healthy\",\"status\":\"running\",\"cpu\":0.05,\"maxcpu\":2.0,\"mem\":2000000000,\"maxmem\":4000000000,\"uptime\":1800}," +
                "{\"type\":\"pve-storage\",\"node\":\"pve-node-01\",\"storage\":\"local-zfs\",\"disk\":100000000000,\"maxdisk\":500000000000,\"status\":\"available\",\"shared\":false}" +
                "]}," +
                "{\"remote\":\"home-lab-2\",\"error\":\"Remote home-lab-2 connection failed\",\"resources\":[]}" +
                "]}");

        // Healthy Remote A queries: unrelated offline sibling Remote B does NOT poison healthy queries
        List<StoragePool> pools = driver.fetchNodeStorage("home-lab/pve-node-01", creds);
        assertEquals(1, pools.size());
        assertEquals("local-zfs", pools.get(0).name);

        GuestInfo detail = driver.fetchGuestDetail("home-lab/pve-node-01", 101, GuestInfo.GuestType.QEMU, creds);
        assertNotNull(detail);
        assertEquals("vm-healthy", detail.name);

        // Errored Remote B queries: selected remote error MUST fail closed
        try {
            driver.fetchNodeStorage("home-lab-2/pve-node-02", creds);
            fail("Querying storage on errored remote B must throw ApiException");
        } catch (ApiException e) {
            assertTrue(e.getMessage().contains("home-lab-2"));
            assertTrue(e.getMessage().contains("Remote home-lab-2 connection failed"));
        }

        try {
            driver.fetchGuestDetail("home-lab-2/pve-node-02", 201, GuestInfo.GuestType.QEMU, creds);
            fail("Querying guest detail on errored remote B must throw ApiException");
        } catch (ApiException e) {
            assertTrue(e.getMessage().contains("home-lab-2"));
            assertTrue(e.getMessage().contains("Remote home-lab-2 connection failed"));
        }
    }

    // -------------------------------------------------------------------------
    // 14: Selected Failed Remote Action Preflight Rejects with Zero POST
    // -------------------------------------------------------------------------

    @Test
    public void testSelectedFailedRemoteActionPreflightRejectsZeroPost() throws Exception {
        ServerConfig config = createConfig("pdm-preflight");
        PdmApiDriver driver = new PdmApiDriver(config);
        AuthCredentials creds = createCreds();

        resourcesPayload.set("{\"data\":[" +
                "{\"remote\":\"home-lab\",\"resources\":[" +
                "{\"type\":\"pve-node\",\"node\":\"pve-node-01\",\"status\":\"online\",\"cpu\":0.15,\"maxcpu\":8.0,\"mem\":8000000000,\"maxmem\":16000000000,\"uptime\":3600}" +
                "]}," +
                "{\"remote\":\"home-lab-2\",\"error\":\"Remote home-lab-2 connection failed\",\"resources\":[]}" +
                "]}");

        // Target guest on errored remote B
        String targetNode = "home-lab-2/pve-node-02";
        int targetVmid = 201;

        // Action preflight / target verification must reject before ANY POST is dispatched
        try {
            driver.fetchGuestDetailWithDeadline(targetNode, targetVmid, GuestInfo.GuestType.QEMU, creds,
                    System.nanoTime() + 15_000_000_000L);
            fail("Preflight on errored remote B must fail closed and reject");
        } catch (ApiException expected) {
            assertTrue(expected.getMessage().contains("home-lab-2"));
        }

        // Verify zero POST requests were dispatched to upstream
        assertEquals("Preflight rejection must dispatch ZERO POST requests", 0, postActionCount.get());
    }

    // -------------------------------------------------------------------------
    // 15: Scoped Remote Inventory Preserves Unchanged Configured Second Endpoint Failed Globally (BLOCKER 1)
    // -------------------------------------------------------------------------

    @Test
    public void testScopedRemoteInventoryPreservesUnchangedConfiguredSecondEndpointFailedGlobally() throws Exception {
        ServerConfig a = createConfig("pdm-one");
        ServerConfig other = createConfig("pdm-two");
        PdmApiDriver driver = new PdmApiDriver(a);
        AuthCredentials creds = createCreds();

        resourcesPayload.set("{\"data\":[" +
                "{\"remote\":\"A\",\"resources\":[{\"type\":\"pve-node\",\"node\":\"node\",\"status\":\"online\",\"cpu\":0.1,\"maxcpu\":8,\"mem\":4,\"maxmem\":8}]}," +
                "{\"remote\":\"B\",\"error\":\"unreachable\",\"resources\":[]}" +
                "]}");

        PdmApiDriver.PdmFleetResult r = driver.fetchFleetResources(creds);
        assertEquals(1, r.nodes.size());
        assertEquals(Collections.singleton("B"), r.failedRemotes);

        NodeStatus old = new NodeStatus("C/node", other.id, NodeStatus.Status.ONLINE, 0.2, 4, 4, 8, 0, 0, 0, 0, null, 1, 1000L);
        Map<String, String> prov = new LinkedHashMap<>();
        prov.put(other.id, UiPresentationPolicy.computeServerProvenance(other));

        UiPresentationPolicy.FleetCacheMergeResult merged = UiPresentationPolicy.mergeFleetData(
                Collections.singletonList(old), null, prov, Arrays.asList(a, other),
                r.nodes, r.guests, r.firstError,
                Collections.singleton(a.id), Collections.singleton(a.id), Collections.emptySet(),
                Collections.singleton(a.id + "/A"), Collections.singleton(a.id + "/B"), r.remoteErrors);

        boolean kept = false;
        NodeStatus retainedNode = null;
        for (NodeStatus n : merged.nodes) {
            if (other.id.equals(n.serverId) && "C/node".equals(n.node)) {
                kept = true;
                retainedNode = n;
            }
        }

        assertTrue("Unchanged configured second endpoint failing globally must retain its cache", kept);
        assertNotNull(retainedNode);
        assertEquals("Retained node on failed whole endpoint must be converted to UNKNOWN", NodeStatus.Status.UNKNOWN, retainedNode.status);
        assertFalse("Retained node on failed whole endpoint must NOT be online", retainedNode.isOnline());
        assertEquals(1000L, retainedNode.fetchedAt);

        UiPresentationPolicy.FleetCapacitySummary summary = UiPresentationPolicy.calculateFleetCapacity(merged.nodes, merged.guests);
        assertTrue(summary.isPartialCoverage);
        assertEquals("GUESTS: 0 running \u2022 0 stopped (partial coverage)", summary.guestSummaryText);
    }

    // -------------------------------------------------------------------------
    // 16: Successful Empty PDM Endpoint Clears Only Its Own Data During Sibling Endpoint Error
    // -------------------------------------------------------------------------

    @Test
    public void testSuccessfulEmptyPdmEndpointClearsOnlyItsOwnDataDuringSiblingEndpointError() throws Exception {
        ServerConfig pdmOne = createConfig("pdm-empty-one");
        ServerConfig pdmTwo = createConfig("pdm-error-two");

        NodeStatus cachedOne = new NodeStatus("rem-1/node-1", pdmOne.id, NodeStatus.Status.ONLINE,
                0.1, 4, 2, 4, 0, 0, 0, 0, "8.1", 100, 1000L);
        NodeStatus cachedTwo = new NodeStatus("rem-2/node-2", pdmTwo.id, NodeStatus.Status.ONLINE,
                0.2, 8, 4, 8, 0, 0, 0, 0, "8.1", 100, 1000L);

        Map<String, String> prov = new LinkedHashMap<>();
        prov.put(pdmOne.id, UiPresentationPolicy.computeServerProvenance(pdmOne));
        prov.put(pdmTwo.id, UiPresentationPolicy.computeServerProvenance(pdmTwo));

        // pdmOne succeeded with empty data; pdmTwo failed
        UiPresentationPolicy.FleetCacheMergeResult merged = UiPresentationPolicy.mergeFleetData(
                Arrays.asList(cachedOne, cachedTwo), null, prov, Arrays.asList(pdmOne, pdmTwo),
                Collections.emptyList(), Collections.emptyList(), "pdm-two connection error",
                Collections.singleton(pdmOne.id), Collections.singleton(pdmOne.id), Collections.emptySet(),
                Collections.emptySet(), Collections.emptySet(), Collections.emptyMap());

        // pdmOne was successfully empty -> authoritatively cleared
        boolean hasOne = false;
        boolean hasTwo = false;
        for (NodeStatus n : merged.nodes) {
            if (pdmOne.id.equals(n.serverId)) hasOne = true;
            if (pdmTwo.id.equals(n.serverId)) hasTwo = true;
        }

        assertFalse("Successful empty endpoint must clear its own data", hasOne);
        assertTrue("Failed endpoint must preserve its data as UNKNOWN/stale", hasTwo);
    }

    // -------------------------------------------------------------------------
    // 17: Per-Scope Freshness and Honest Guest / Host Summaries (BLOCKER 2 & 3)
    // -------------------------------------------------------------------------

    @Test
    public void testPerScopeFreshnessAndHonestSummaries() throws Exception {
        // Cold all-failed
        NodeStatus placeholderA = NodeStatus.remotePlaceholder("rem-a", "srv-1");
        NodeStatus placeholderB = NodeStatus.remotePlaceholder("rem-b", "srv-1");
        assertEquals(0L, placeholderA.fetchedAt);
        assertTrue(placeholderA.isRemotePlaceholder);

        UiPresentationPolicy.FleetCapacitySummary allFailedSummary =
                UiPresentationPolicy.calculateFleetCapacity(Arrays.asList(placeholderA, placeholderB), Collections.emptyList());

        assertEquals("GUESTS: Unavailable", allFailedSummary.guestSummaryText);
        assertEquals("HOSTS (0 online \u2022 2 remotes unknown)", allFailedSummary.hostsHeaderTitle);
        assertEquals(0, allFailedSummary.totalNodes);
        assertEquals(2, allFailedSummary.unknownRemotes);

        // Cold partial (live A + placeholder B)
        NodeStatus liveA = new NodeStatus("rem-a/node-1", "srv-1", NodeStatus.Status.ONLINE,
                0.1, 4, 2, 4, 0, 0, 0, 0, "8.1", 100, 2000L);
        UiPresentationPolicy.FleetCapacitySummary partialSummary =
                UiPresentationPolicy.calculateFleetCapacity(Arrays.asList(liveA, placeholderB), Collections.emptyList());

        assertEquals("GUESTS: 0 running \u2022 0 stopped (partial coverage)", partialSummary.guestSummaryText);
        assertEquals("HOSTS (1 online \u2022 1 remote unknown)", partialSummary.hostsHeaderTitle);
        assertEquals(1, partialSummary.totalNodes);
        assertEquals(1, partialSummary.unknownRemotes);
        assertTrue(partialSummary.isPartialCoverage);

        // Banner evaluation with fresh sibling data
        UiPresentationPolicy.ErrorBannerState banner =
                UiPresentationPolicy.evaluateErrorBanner("Remote B failed", true, 2000L, true);
        assertTrue(banner.isVisible);
        assertTrue("Banner must indicate cached data for unreachable remotes when fresh data present",
                banner.bannerText.contains("showing cached data for unreachable remotes"));
    }

    // -------------------------------------------------------------------------
    // 18: All Failed Embedded Response Authoritative Removal and No Duplicate Placeholders
    // -------------------------------------------------------------------------

    @Test
    public void testAllFailedEmbeddedResponseAuthoritativeRemovalAndNoDuplicatePlaceholders() throws Exception {
        ServerConfig config = createConfig("pdm-all-fail-purge");
        PdmApiDriver driver = new PdmApiDriver(config);
        AuthCredentials creds = createCreds();

        // Warm cache: home-lab, home-lab-2, and home-lab-old (which will be removed)
        NodeStatus cachedA = new NodeStatus("home-lab/pve-node-01", config.id, NodeStatus.Status.ONLINE,
                0.20, 8, 4000000000L, 8000000000L, 0, 0, 0, 0, "8.1.4", 3600, 1000L);
        NodeStatus cachedB = new NodeStatus("home-lab-2/pve-node-02", config.id, NodeStatus.Status.ONLINE,
                0.10, 4, 2000000000L, 4000000000L, 0, 0, 0, 0, "8.1.4", 3600, 1000L);
        NodeStatus cachedOld = new NodeStatus("home-lab-old/pve-node-03", config.id, NodeStatus.Status.ONLINE,
                0.15, 4, 2000000000L, 4000000000L, 0, 0, 0, 0, "8.1.4", 3600, 1000L);

        GuestInfo cachedGuestA = new GuestInfo(101, "vm-a", GuestInfo.GuestType.QEMU, GuestInfo.RunState.RUNNING,
                "home-lab/pve-node-01", config.id, 0.05, 2, 1000000000L, 2000000000L,
                0, 0, 0, 0, 0, 0, 100, "", false, 1000L);
        GuestInfo cachedGuestOld = new GuestInfo(103, "vm-old", GuestInfo.GuestType.QEMU, GuestInfo.RunState.RUNNING,
                "home-lab-old/pve-node-03", config.id, 0.05, 2, 1000000000L, 2000000000L,
                0, 0, 0, 0, 0, 0, 100, "", false, 1000L);

        List<NodeStatus> cachedNodes = Arrays.asList(cachedA, cachedB, cachedOld);
        List<GuestInfo> cachedGuests = Arrays.asList(cachedGuestA, cachedGuestOld);
        Map<String, String> cachedProvenances = Collections.singletonMap(
                config.id, UiPresentationPolicy.computeServerProvenance(config));

        // Actual HTTPS loopback response from PDM: home-lab and home-lab-2 fail, home-lab-old was removed from PDM
        resourcesPayload.set("{\"data\":[" +
                "{\"remote\":\"home-lab\",\"error\":\"Remote home-lab connection timeout\",\"resources\":[]}," +
                "{\"remote\":\"home-lab-2\",\"error\":\"Remote home-lab-2 connection failed\",\"resources\":[]}" +
                "]}");

        PdmApiDriver.PdmFleetResult res = driver.fetchFleetResources(creds);
        assertTrue("All embedded remotes failed", res.isAllFailed);
        assertFalse("Not partial since all failed", res.isPartialFailure);
        assertEquals(2, res.failedRemotes.size());
        assertTrue(res.failedRemotes.contains("home-lab"));
        assertTrue(res.failedRemotes.contains("home-lab-2"));

        // Production merge with successful endpoint inventory marked (MainActivity allfailed branch fix)
        Set<String> failedRemoteKeys = new LinkedHashSet<>();
        failedRemoteKeys.add(config.id + "/home-lab");
        failedRemoteKeys.add(config.id + "/home-lab-2");

        UiPresentationPolicy.FleetCacheMergeResult mergeResult = UiPresentationPolicy.mergeFleetData(
                cachedNodes, cachedGuests, cachedProvenances, Collections.singletonList(config),
                res.nodes, res.guests, res.firstError,
                Collections.singleton(config.id), Collections.singleton(config.id), Collections.emptySet(),
                Collections.emptySet(), failedRemoteKeys, res.remoteErrors);

        // 1. Authoritative purge: removed remote home-lab-old MUST be purged even though remaining remotes failed
        boolean hasOld = false;
        boolean hasA = false;
        boolean hasB = false;
        for (NodeStatus n : mergeResult.nodes) {
            if ("home-lab-old/pve-node-03".equals(n.node)) hasOld = true;
            if ("home-lab/pve-node-01".equals(n.node)) hasA = true;
            if ("home-lab-2/pve-node-02".equals(n.node)) hasB = true;
        }
        assertFalse("Removed remote MUST be authoritatively purged even during allfailed", hasOld);
        assertTrue("Existing failed remote A node must be retained", hasA);
        assertTrue("Existing failed remote B node must be retained", hasB);

        // 2. No duplicate invented host placeholders for existing retained nodes
        assertEquals("Merged nodes must contain EXACTLY the 2 retained nodes and NO duplicate placeholders",
                2, mergeResult.nodes.size());

        for (NodeStatus n : mergeResult.nodes) {
            assertEquals(NodeStatus.Status.UNKNOWN, n.status);
            assertFalse(n.isOnline());
            assertEquals("Retained node must preserve original fetchedAt", 1000L, n.fetchedAt);
            assertFalse("Retained node must not be flagged as cold placeholder", n.isRemotePlaceholder);
        }

        // 3. Guests: removed guest purged, failed guest retained as UNKNOWN
        assertEquals("Merged guests must contain EXACTLY the 1 retained guest", 1, mergeResult.guests.size());
        GuestInfo guest = mergeResult.guests.get(0);
        assertEquals(101, guest.vmid);
        assertEquals(GuestInfo.RunState.UNKNOWN, guest.state);
        assertEquals(1000L, guest.fetchedAt);
    }

    // -------------------------------------------------------------------------
    // 19: Whole Endpoint Failure Preserves Cache Without Adding Duplicate Placeholders
    // -------------------------------------------------------------------------

    @Test
    public void testWholeEndpointFailurePreservesCacheWithoutAddingDuplicatePlaceholders() throws Exception {
        ServerConfig config = createConfig("pdm-whole-fail");
        String prov = UiPresentationPolicy.computeServerProvenance(config);
        Map<String, String> cachedProvenances = Collections.singletonMap(config.id, prov);

        NodeStatus cachedA = new NodeStatus("home-lab/pve-node-01", config.id, NodeStatus.Status.ONLINE,
                0.20, 8, 4000000000L, 8000000000L, 0, 0, 0, 0, "8.1.4", 3600, 1000L);
        NodeStatus cachedB = new NodeStatus("home-lab-2/pve-node-02", config.id, NodeStatus.Status.ONLINE,
                0.10, 4, 2000000000L, 4000000000L, 0, 0, 0, 0, "8.1.4", 3600, 1000L);

        List<NodeStatus> cachedNodes = Arrays.asList(cachedA, cachedB);

        // Whole endpoint fails: successfulNodeServers is EMPTY, but failedRemoteKeys is passed
        Set<String> failedRemoteKeys = new LinkedHashSet<>();
        failedRemoteKeys.add(config.id + "/home-lab");
        failedRemoteKeys.add(config.id + "/home-lab-2");

        UiPresentationPolicy.FleetCacheMergeResult mergeResult = UiPresentationPolicy.mergeFleetData(
                cachedNodes, null, cachedProvenances, Collections.singletonList(config),
                Collections.emptyList(), Collections.emptyList(), "HTTP 500 Connection Refused",
                Collections.emptySet(), Collections.emptySet(), Collections.emptySet(),
                Collections.emptySet(), failedRemoteKeys, Collections.emptyMap());

        // Merged nodes must contain EXACTLY 2 nodes (the retained cached nodes), NO duplicate cold placeholders
        assertEquals("Whole endpoint failure must retain cached nodes without adding duplicate placeholders",
                2, mergeResult.nodes.size());

        for (NodeStatus n : mergeResult.nodes) {
            assertEquals(NodeStatus.Status.UNKNOWN, n.status);
            assertEquals(1000L, n.fetchedAt);
            assertFalse(n.isRemotePlaceholder);
        }
    }

    // -------------------------------------------------------------------------
    // 20: Scope Freshness Conveys Fresh A vs Failed B Stale and Partial Counts
    // -------------------------------------------------------------------------

    @Test
    public void testScopeFreshnessConveysFreshAVsFailedBStaleAndPartialCounts() throws Exception {
        NodeStatus freshA = new NodeStatus("home-lab/pve-node-01", "srv-1", NodeStatus.Status.ONLINE,
                0.25, 8, 4000000000L, 8000000000L, 0, 0, 0, 0, "8.1.4", 3600, 5000L);
        NodeStatus staleB = new NodeStatus("home-lab-2/pve-node-02", "srv-1", NodeStatus.Status.UNKNOWN,
                0.10, 4, 2000000000L, 4000000000L, 0, 0, 0, 0, "8.1.4", 3600, 1000L);
        NodeStatus placeholderC = NodeStatus.remotePlaceholder("home-lab-3", "srv-1");

        GuestInfo freshGuestA = new GuestInfo(101, "vm-live", GuestInfo.GuestType.QEMU, GuestInfo.RunState.RUNNING,
                "home-lab/pve-node-01", "srv-1", 0.05, 2, 1000000000L, 2000000000L,
                0, 0, 0, 0, 0, 0, 100, "", false, 5000L);
        GuestInfo staleGuestB = new GuestInfo(201, "vm-stale", GuestInfo.GuestType.QEMU, GuestInfo.RunState.UNKNOWN,
                "home-lab-2/pve-node-02", "srv-1", 0.05, 2, 1000000000L, 2000000000L,
                0, 0, 0, 0, 0, 0, 100, "", false, 1000L);

        List<NodeStatus> nodes = Arrays.asList(freshA, staleB, placeholderC);
        List<GuestInfo> guests = Arrays.asList(freshGuestA, staleGuestB);

        UiPresentationPolicy.FleetCapacitySummary summary =
                UiPresentationPolicy.calculateFleetCapacity(nodes, guests);

        // 1. Host capacity & counting:
        assertEquals(2, summary.totalNodes); // placeholder C is not a proven host
        assertEquals(1, summary.onlineNodes);
        assertEquals(1, summary.offlineNodes);
        assertEquals(1, summary.unknownRemotes);
        assertEquals("HOSTS (1 online \u2022 1 remote unknown)", summary.hostsHeaderTitle);

        // 2. CPU & RAM: only fresh A contributes to live aggregate
        assertEquals(8, summary.totalCores); // stale B (4) and placeholder C do not count
        assertEquals("8 vCPUs (partial coverage)", summary.cpuCapacityText);
        assertEquals("GUESTS: 1 running \u2022 0 stopped \u2022 1 unknown", summary.guestSummaryText);

        // 3. Compact host row metrics formatting
        String metricsA = UiPresentationPolicy.formatHostRowMetrics(freshA);
        assertTrue("Live metrics must show CPU percent", metricsA.contains("CPU: 25%"));
        assertFalse("Live metrics must NOT be labeled stale", metricsA.contains("(stale)"));

        String metricsB = UiPresentationPolicy.formatHostRowMetrics(staleB);
        assertTrue("Cached UNKNOWN metrics must be labeled stale", metricsB.contains("(stale)"));

        String metricsC = UiPresentationPolicy.formatHostRowMetrics(placeholderC);
        assertEquals("Cold placeholder metrics must be Unreachable / Unknown", "Unreachable / Unknown", metricsC);

        // 4. Banner formatting: partial coverage banner conveys fresh A vs failed B
        UiPresentationPolicy.ErrorBannerState banner =
                UiPresentationPolicy.evaluateErrorBanner("Remote home-lab-2 connection failed", true, 5000L, true);
        assertTrue(banner.isVisible);
        assertTrue("Banner must state cached data for unreachable remotes",
                banner.bannerText.contains("showing cached data for unreachable remotes"));
        assertFalse("Banner must NOT claim entire fleet is stale when fresh sibling data exists",
                banner.bannerText.contains("stale / showing cached data"));
    }

    // -------------------------------------------------------------------------
    // 21: Empty + Fresh PVE Results With Unrelated PDM Partial Failure
    // -------------------------------------------------------------------------

    @Test
    public void testEmptyAndFreshPveResultsWithUnrelatedPdmPartialFailure() throws Exception {
        ServerConfig pveServer = new ServerConfig("pve-a", "PVE Host", "192.0.2.1", 8006,
                ServerConfig.ServerType.PVE, "pin-pve", true, false);
        ServerConfig pdmServer = new ServerConfig("pdm-b", "PDM Datacenter", "192.0.2.2", 8443,
                ServerConfig.ServerType.PDM, "pin-pdm", true, false);

        GuestInfo vanishedPveGuest = new GuestInfo(101, "vanished-vm", GuestInfo.GuestType.QEMU,
                GuestInfo.RunState.RUNNING, "node-1", pveServer.id,
                0.1, 2, 1000, 2000, 0, 0, 0, 0, 0, 0, 100, "", false, 1000L);
        GuestInfo survivingPveGuest = new GuestInfo(102, "surviving-vm", GuestInfo.GuestType.QEMU,
                GuestInfo.RunState.RUNNING, "node-2", pveServer.id,
                0.2, 4, 2000, 4000, 0, 0, 0, 0, 0, 0, 200, "", false, 1000L);

        GuestInfo healthyPdmGuest = new GuestInfo(201, "healthy-pdm-vm", GuestInfo.GuestType.QEMU,
                GuestInfo.RunState.RUNNING, "Healthy/node-h", pdmServer.id,
                0.15, 2, 1500, 3000, 0, 0, 0, 0, 0, 0, 300, "", false, 1000L);
        GuestInfo failedPdmGuest = new GuestInfo(202, "failed-pdm-vm", GuestInfo.GuestType.QEMU,
                GuestInfo.RunState.RUNNING, "Failed/node-f", pdmServer.id,
                0.10, 2, 1000, 2000, 0, 0, 0, 0, 0, 0, 400, "", false, 1000L);

        List<GuestInfo> cachedGuests = Arrays.asList(vanishedPveGuest, survivingPveGuest, healthyPdmGuest, failedPdmGuest);

        Map<String, String> cachedProvenances = new LinkedHashMap<>();
        cachedProvenances.put(pveServer.id, UiPresentationPolicy.computeServerProvenance(pveServer));
        cachedProvenances.put(pdmServer.id, UiPresentationPolicy.computeServerProvenance(pdmServer));

        // Fresh fetch simulates:
        // - PVE node-1: empty guest result (vanished-vm deleted)
        // - PVE node-2: fresh surviving-vm (updated metrics)
        // - PDM Healthy: fresh healthy-pdm-vm
        // - PDM Failed: partial failure (error reported for remote "Failed")
        GuestInfo freshSurvivingPveGuest = new GuestInfo(102, "surviving-vm", GuestInfo.GuestType.QEMU,
                GuestInfo.RunState.RUNNING, "node-2", pveServer.id,
                0.25, 4, 2500, 4000, 0, 0, 0, 0, 0, 0, 250, "", false, 2000L);
        GuestInfo freshHealthyPdmGuest = new GuestInfo(201, "healthy-pdm-vm", GuestInfo.GuestType.QEMU,
                GuestInfo.RunState.RUNNING, "Healthy/node-h", pdmServer.id,
                0.18, 2, 1800, 3000, 0, 0, 0, 0, 0, 0, 350, "", false, 2000L);

        List<GuestInfo> freshGuests = Arrays.asList(freshSurvivingPveGuest, freshHealthyPdmGuest);

        Set<String> successfulNodeServers = new LinkedHashSet<>(Arrays.asList(pveServer.id, pdmServer.id));
        Set<String> successfulGuestServers = Collections.singleton(pdmServer.id); // PDM sets server-level, PVE does NOT!
        Set<String> successfulGuestNodeKeys = new LinkedHashSet<>(Arrays.asList(
                pveServer.id + "/node-1", // node-1 guest fetch succeeded authoritatively with EMPTY list!
                pveServer.id + "/node-2"  // node-2 guest fetch succeeded with freshSurvivingPveGuest
        ));
        Set<String> successfulRemoteKeys = Collections.singleton(pdmServer.id + "/Healthy");
        Set<String> failedRemoteKeys = Collections.singleton(pdmServer.id + "/Failed");

        UiPresentationPolicy.FleetCacheMergeResult result = UiPresentationPolicy.mergeFleetData(
                Collections.emptyList(), cachedGuests, cachedProvenances,
                Arrays.asList(pveServer, pdmServer),
                Collections.emptyList(), freshGuests, "PDM remote 'Failed' connection failed",
                successfulNodeServers, successfulGuestServers, successfulGuestNodeKeys,
                successfulRemoteKeys, failedRemoteKeys, Collections.emptyMap());

        // Assertions:
        // 1. Authoritative empty result on PVE node-1 MUST purge vanished-vm (Guest 101)
        for (GuestInfo g : result.guests) {
            assertNotEquals("Authoritative successful empty PVE result must purge vanished guest", 101, g.vmid);
        }

        // 2. Fresh PVE guest on node-2 must be retained and updated
        GuestInfo resPve102 = null;
        GuestInfo resPdm201 = null;
        GuestInfo resPdm202 = null;
        for (GuestInfo g : result.guests) {
            if (g.vmid == 102) resPve102 = g;
            if (g.vmid == 201) resPdm201 = g;
            if (g.vmid == 202) resPdm202 = g;
        }

        assertNotNull("Surviving fresh PVE guest must be present", resPve102);
        assertEquals(GuestInfo.RunState.RUNNING, resPve102.state);
        assertEquals(2000L, resPve102.fetchedAt);

        assertNotNull("Healthy PDM guest must be present and fresh", resPdm201);
        assertEquals(GuestInfo.RunState.RUNNING, resPdm201.state);
        assertEquals(2000L, resPdm201.fetchedAt);

        assertNotNull("Failed PDM guest must be retained from cache as UNKNOWN", resPdm202);
        assertEquals(GuestInfo.RunState.UNKNOWN, resPdm202.state);
        assertEquals("Original fetchedAt must be preserved for stale guest", 1000L, resPdm202.fetchedAt);

        assertEquals("Total merged guests count must be exactly 3", 3, result.guests.size());
    }

    // -------------------------------------------------------------------------
    // 22: PVE Success Node vs Failed Node Within Same PVE Server
    // -------------------------------------------------------------------------

    @Test
    public void testPveSuccessNodeVsFailedNodeWithinSamePveServer() throws Exception {
        ServerConfig pveServer = new ServerConfig("pve-cluster", "Cluster", "192.0.2.10", 8006,
                ServerConfig.ServerType.PVE, "pin-cluster", true, false);

        GuestInfo vanishedAlpha = new GuestInfo(101, "alpha-vm", GuestInfo.GuestType.QEMU,
                GuestInfo.RunState.RUNNING, "node-alpha", pveServer.id,
                0.10, 2, 1000, 2000, 0, 0, 0, 0, 0, 0, 100, "", false, 1000L);
        GuestInfo cachedBeta = new GuestInfo(201, "beta-vm", GuestInfo.GuestType.QEMU,
                GuestInfo.RunState.RUNNING, "node-beta", pveServer.id,
                0.20, 4, 2000, 4000, 0, 0, 0, 0, 0, 0, 200, "", false, 1000L);

        List<GuestInfo> cachedGuests = Arrays.asList(vanishedAlpha, cachedBeta);
        Map<String, String> cachedProvenances = Collections.singletonMap(
                pveServer.id, UiPresentationPolicy.computeServerProvenance(pveServer));

        // Node alpha succeeds authoritatively with 0 guests (vanishedAlpha was deleted).
        // Node beta telemetry fails with an exception (node-beta is NOT in successfulGuestNodeKeys).
        Set<String> successfulNodeServers = Collections.singleton(pveServer.id);
        Set<String> successfulGuestServers = Collections.emptySet(); // PVE sets node keys, not server
        Set<String> successfulGuestNodeKeys = Collections.singleton(pveServer.id + "/node-alpha");

        UiPresentationPolicy.FleetCacheMergeResult result = UiPresentationPolicy.mergeFleetData(
                Collections.emptyList(), cachedGuests, cachedProvenances,
                Collections.singletonList(pveServer),
                Collections.emptyList(), Collections.emptyList(), "Node beta telemetry error",
                successfulNodeServers, successfulGuestServers, successfulGuestNodeKeys,
                Collections.emptySet(), Collections.emptySet(), Collections.emptyMap());

        // 1. Authoritative empty result for node-alpha MUST clear vanishedAlpha
        for (GuestInfo g : result.guests) {
            assertNotEquals("Successful node-alpha must authoritatively clear vanished guest", 101, g.vmid);
        }

        // 2. Failed node-beta MUST retain cached guest as UNKNOWN with original fetchedAt
        assertEquals("Failed node-beta must retain its cached guest", 1, result.guests.size());
        GuestInfo retainedBeta = result.guests.get(0);
        assertEquals(201, retainedBeta.vmid);
        assertEquals("node-beta", retainedBeta.node);
        assertEquals(GuestInfo.RunState.UNKNOWN, retainedBeta.state);
        assertEquals(1000L, retainedBeta.fetchedAt);
    }

    // -------------------------------------------------------------------------
    // 23: Selected PDM Remote Failure Disables Power Controls & Rejects Confirmation
    // -------------------------------------------------------------------------

    @Test
    public void testSelectedPdmRemoteFailureDisablesPowerControlsAndRejectsConfirmationWhileHealthySiblingWorks() throws Exception {
        ServerConfig config = createConfig("pdm-detail-power");
        PdmApiDriver driver = new PdmApiDriver(config);
        AuthCredentials creds = createCreds();

        resourcesPayload.set("{\"data\":[" +
                "{\"remote\":\"home-lab\",\"resources\":[" +
                "{\"type\":\"pve-node\",\"node\":\"pve-node-01\",\"status\":\"online\",\"cpu\":0.15,\"maxcpu\":8.0,\"mem\":8000000000,\"maxmem\":16000000000,\"uptime\":3600}," +
                "{\"type\":\"pve-qemu\",\"node\":\"pve-node-01\",\"vmid\":101,\"name\":\"vm-healthy\",\"status\":\"running\",\"cpu\":0.05,\"maxcpu\":2.0,\"mem\":2000000000,\"maxmem\":4000000000,\"uptime\":1800}," +
                "{\"type\":\"pve-storage\",\"node\":\"pve-node-01\",\"storage\":\"local-zfs\",\"disk\":100000000000,\"maxdisk\":500000000000,\"status\":\"available\",\"shared\":false}" +
                "]}," +
                "{\"remote\":\"home-lab-2\",\"error\":\"Remote home-lab-2 connection failed\",\"resources\":[]}" +
                "]}");

        // 1. Production driver scoping:
        // Healthy remote "home-lab" detail & storage queries succeed; failed sibling "home-lab-2" fails closed with ApiException.
        GuestInfo healthyDetail = driver.fetchGuestDetail("home-lab/pve-node-01", 101, GuestInfo.GuestType.QEMU, creds);
        assertNotNull("Healthy remote A guest detail must succeed", healthyDetail);
        assertEquals(101, healthyDetail.vmid);
        assertEquals(GuestInfo.RunState.RUNNING, healthyDetail.state);

        List<StoragePool> healthyStorage = driver.fetchNodeStorage("home-lab/pve-node-01", creds);
        assertNotNull("Healthy remote A storage pools must succeed", healthyStorage);
        assertFalse("Healthy storage pools must not be empty", healthyStorage.isEmpty());

        try {
            driver.fetchGuestDetail("home-lab-2/pve-node-02", 201, GuestInfo.GuestType.QEMU, creds);
            fail("Selected failed remote B guest detail must fail closed with ApiException");
        } catch (ApiException e) {
            assertTrue("Exception must identify failed remote", e.getMessage().contains("home-lab-2"));
        }

        try {
            driver.fetchNodeStorage("home-lab-2/pve-node-02", creds);
            fail("Selected failed remote B storage must fail closed with ApiException");
        } catch (ApiException e) {
            assertTrue("Exception must identify failed remote", e.getMessage().contains("home-lab-2"));
        }

        // 2. Production helper & view-binding state evaluation:
        // Evaluates evaluateGuestMetricsBinding for healthy vs failed remote targets
        GuestInfo cachedRunningB = new GuestInfo(201, "vm-failed-remote", GuestInfo.GuestType.QEMU,
                GuestInfo.RunState.RUNNING, "home-lab-2/pve-node-02", config.id,
                0.40, 4, 4000000000L, 8000000000L, 0, 0, 0, 0, 0, 0, 7200, "", false, 1000L);

        // Before failure (fresh read):
        UiPresentationPolicy.GuestMetricsBindingState freshStateB =
                UiPresentationPolicy.evaluateGuestMetricsBinding(cachedRunningB, false, config, null);
        assertFalse("Fresh binding must not be stale", freshStateB.isStale);
        assertTrue("Fresh binding has data", freshStateB.hasData);
        assertEquals("RUNNING", freshStateB.statusText);
        assertTrue("Power card visible for fresh running guest", freshStateB.isPowerCardVisible);
        assertTrue("Power controls enabled for fresh running guest", freshStateB.isPowerControlsEnabled);
        assertTrue("Action confirmation allowed for fresh running guest", freshStateB.isActionConfirmationAllowed);
        assertFalse("CPU text must not contain stale indicator", freshStateB.cpuText.contains("stale"));

        // After selected remote failure (stale cached read):
        UiPresentationPolicy.GuestMetricsBindingState staleStateB =
                UiPresentationPolicy.evaluateGuestMetricsBinding(cachedRunningB, true, config, "Remote home-lab-2 connection failed");
        assertTrue("Stale binding must be marked stale", staleStateB.isStale);
        assertTrue("Stale binding retains cached data", staleStateB.hasData);
        assertEquals("UNKNOWN", staleStateB.statusText);
        assertTrue("Stale CPU clearly marked (stale)", staleStateB.cpuText.contains("(stale)"));
        assertTrue("Stale RAM clearly marked (stale)", staleStateB.ramText.contains("(stale)"));
        assertTrue("Stale Disk clearly marked (stale)", staleStateB.diskText.contains("(stale)"));
        assertTrue("Stale Uptime explains telemetry failure and cached data",
                staleStateB.uptimeText.contains("Telemetry refresh failed") && staleStateB.uptimeText.contains("cached data"));
        assertFalse("Power card must be strictly HIDDEN for stale guest", staleStateB.isPowerCardVisible);
        assertFalse("Power controls must be strictly DISABLED for stale guest", staleStateB.isPowerControlsEnabled);
        assertFalse("Terminal must be strictly HIDDEN for stale guest", staleStateB.isTerminalVisible);
        assertFalse("Action confirmation must be strictly FORBIDDEN for stale guest", staleStateB.isActionConfirmationAllowed);

        // 3. Stale guest target validation & confirmation rejection:
        GuestInfo staleGuestInfoB = new GuestInfo(
                cachedRunningB.vmid, cachedRunningB.name, cachedRunningB.type,
                GuestInfo.RunState.UNKNOWN,
                cachedRunningB.node, cachedRunningB.serverId,
                cachedRunningB.cpuUsage, cachedRunningB.cpuCount,
                cachedRunningB.memUsed, cachedRunningB.memMax,
                cachedRunningB.diskUsed, cachedRunningB.diskTotal,
                cachedRunningB.diskRead, cachedRunningB.diskWrite,
                cachedRunningB.netIn, cachedRunningB.netOut,
                cachedRunningB.uptime, cachedRunningB.tags,
                cachedRunningB.favorited, cachedRunningB.fetchedAt);

        assertEquals("Cached metrics and original fetchedAt must be preserved", 1000L, staleGuestInfoB.fetchedAt);
        assertFalse("Stale UNKNOWN target must FAIL target validation",
                UiPresentationPolicy.isGuestTargetValid(staleGuestInfoB, "home-lab-2/pve-node-02", 201, GuestInfo.GuestType.QEMU));

        // 4. Seam verification: Both caller bindings audited:
        // Caller 1: VM detail view binding (evaluateGuestDetailBinding)
        UiPresentationPolicy.GuestDetailBindingState detailBinding =
                UiPresentationPolicy.evaluateGuestDetailBinding(staleGuestInfoB, config);
        assertFalse("Detail power card must be hidden for stale target", detailBinding.isPowerCardVisible);
        assertFalse("Detail start button must be hidden for stale target", detailBinding.isStartButtonVisible);
        assertFalse("Detail shutdown button must be hidden for stale target", detailBinding.isShutdownButtonVisible);

        // Caller 2: Guests card view binding (evaluateGuestCardBinding)
        UiPresentationPolicy.GuestCardBindingState cardBinding =
                UiPresentationPolicy.evaluateGuestCardBinding(staleGuestInfoB, config);
        assertFalse("Card metrics must be hidden for stale target", cardBinding.isMetricsVisible);
        assertFalse("Card start button must be enabled for stale target", cardBinding.isStartButtonEnabled);
        assertFalse("Card shutdown button must be enabled for stale target", cardBinding.isShutdownButtonEnabled);
        assertFalse("Card reboot button must be enabled for stale target", cardBinding.isRebootButtonEnabled);
        assertFalse("Card force stop button must be enabled for stale target", cardBinding.isForceStopButtonEnabled);

        // 5. Successful revalidation restores full controls
        GuestInfo revalidatedB = new GuestInfo(201, "vm-failed-remote", GuestInfo.GuestType.QEMU,
                GuestInfo.RunState.RUNNING, "home-lab-2/pve-node-02", config.id,
                0.35, 4, 3500000000L, 8000000000L, 0, 0, 0, 0, 0, 0, 7300, "", false, 5000L);
        UiPresentationPolicy.GuestMetricsBindingState reboundState =
                UiPresentationPolicy.evaluateGuestMetricsBinding(revalidatedB, false, config, null);
        assertFalse("Rebound state must not be stale", reboundState.isStale);
        assertTrue("Rebound state enables power controls", reboundState.isPowerControlsEnabled);
        assertTrue("Rebound state allows action confirmation", reboundState.isActionConfirmationAllowed);
        assertEquals("RUNNING", reboundState.statusText);
    }
}
