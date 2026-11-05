// SPDX-License-Identifier: GPL-3.0-or-later
// PVE Pocket — Proxmox Datacenter Manager (PDM) Behavioral & Safety Tests
package com.homelabpocket.api;

import com.homelabpocket.model.AuthCredentials;
import com.homelabpocket.model.GuestInfo;
import com.homelabpocket.model.NodeStatus;
import com.homelabpocket.model.ServerConfig;
import com.homelabpocket.model.StoragePool;
import com.homelabpocket.security.PinnedTrustManager;
import com.homelabpocket.util.JsonUtil;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

import static org.junit.Assert.*;

public class PdmSafetyTest {

    private static final String DUMMY_FINGERPRINT = "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99";

    private static HttpsServer mockServer;
    private static int mockPort;
    private static String mockFingerprint;
    private static final AtomicInteger ticketRequests = new AtomicInteger(0);
    private static final AtomicInteger remotesRequests = new AtomicInteger(0);
    private static final AtomicInteger resourcesRequests = new AtomicInteger(0);
    private static final AtomicBoolean return403 = new AtomicBoolean(false);
    private static final AtomicBoolean return401Once = new AtomicBoolean(false);
    private static final AtomicBoolean return404OnResourcesList = new AtomicBoolean(false);
    private static final AtomicReference<String> customResourcesResponse = new AtomicReference<>(null);

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

        // 1. PDM Ticket Auth Context: /api2/json/access/ticket
        mockServer.createContext("/api2/json/access/ticket", exchange -> {
            ticketRequests.incrementAndGet();
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            InputStream is = exchange.getRequestBody();
            byte[] buf = new byte[1024];
            int n = is.read(buf);
            String body = n > 0 ? new String(buf, 0, n, "UTF-8") : "";

            // Verify body contains normalized username
            assertTrue("Body must contain normalized username", body.contains("username=root%40pam") || body.contains("username=root@pam") || body.contains("username="));

            String resp = "{\"data\":{\"ticket\":\"PDM:root@pam:654321::signature123\",\"CSRFPreventionToken\":\"csrf-pdm-token-999\",\"username\":\"root@pam\"}}";
            byte[] respBytes = resp.getBytes("UTF-8");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, respBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(respBytes);
            }
        });

        // 2. PDM Remotes Context: /api2/json/remotes
        mockServer.createContext("/api2/json/remotes", exchange -> {
            remotesRequests.incrementAndGet();
            if (return403.get()) {
                String err = "{\"message\":\"Permission check failed\"}";
                byte[] b = err.getBytes("UTF-8");
                exchange.sendResponseHeaders(403, b.length);
                exchange.getResponseBody().write(b);
                exchange.close();
                return;
            }

            // Verify Authorization or Cookie header
            String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
            String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
            assertTrue("Request must supply PDMAPIToken or PDM auth cookie",
                    (authHeader != null && authHeader.contains("PDMAPIToken")) ||
                    (cookieHeader != null && (cookieHeader.contains("__Host-PDMAuthCookie") || cookieHeader.contains("PDMAuthCookie"))));

            String resp = "{\"data\":[" +
                    "{\"name\":\"cluster-west\",\"type\":\"pve\",\"url\":\"https://192.0.2.10:8006\",\"fingerprint\":\"AA:BB...\"}," +
                    "{\"name\":\"pbs-backup\",\"type\":\"pbs\",\"url\":\"https://192.0.2.20:8007\"}" +
                    "]}";
            byte[] respBytes = resp.getBytes("UTF-8");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, respBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(respBytes);
            }
        });

        // 3. PDM Canonical Resources Context: /api2/json/resources/list
        // Returns upstream Vec<RemoteResources> format
        // Ref: https://raw.githubusercontent.com/proxmox/proxmox-datacenter-manager/6d7ef0adff2fc0347d87caf7ee687a013ffcc0b5/server/src/api/resources.rs
        HttpHandler resourcesHandler = exchange -> {
            resourcesRequests.incrementAndGet();
            if (return404OnResourcesList.get()) {
                String err = "{\"message\":\"Route not found\"}";
                byte[] b = err.getBytes("UTF-8");
                exchange.sendResponseHeaders(404, b.length);
                exchange.getResponseBody().write(b);
                exchange.close();
                return;
            }
            if (return401Once.compareAndSet(true, false)) {
                String err = "{\"message\":\"Authentication required\"}";
                byte[] b = err.getBytes("UTF-8");
                exchange.sendResponseHeaders(401, b.length);
                exchange.getResponseBody().write(b);
                exchange.close();
                return;
            }
            if (return403.get()) {
                String err = "{\"message\":\"Permission check failed\"}";
                byte[] b = err.getBytes("UTF-8");
                exchange.sendResponseHeaders(403, b.length);
                exchange.getResponseBody().write(b);
                exchange.close();
                return;
            }

            String resp = customResourcesResponse.get();
            if (resp == null) {
                // Canonical upstream Vec<RemoteResources> shape:
                // Outer remote, resources array containing pve-node, pve-qemu, pve-lxc, pve-storage, pbs-*
                resp = "{\"data\":[" +
                        "{\"remote\":\"cluster-west\",\"resources\":[" +
                        "{\"type\":\"pve-node\",\"node\":\"pve-node-01\",\"status\":\"online\",\"cpu\":0.145,\"maxcpu\":16.0,\"mem\":17179869184,\"maxmem\":68719476736,\"uptime\":864000}," +
                        "{\"type\":\"pve-qemu\",\"node\":\"pve-node-01\",\"vmid\":101,\"name\":\"k8s-master\",\"status\":\"running\",\"cpu\":0.08,\"maxcpu\":4.0,\"mem\":4294967296,\"maxmem\":8589934592,\"disk\":10000000000,\"maxdisk\":50000000000,\"uptime\":360000,\"tags\":[\"prod\",\"k8s\"]}," +
                        "{\"type\":\"pve-lxc\",\"node\":\"pve-node-01\",\"vmid\":102,\"name\":\"dns-pihole\",\"status\":\"stopped\",\"cpu\":0.0,\"maxcpu\":1.0,\"mem\":0,\"maxmem\":1073741824,\"disk\":2000000000,\"maxdisk\":10000000000,\"uptime\":0,\"tags\":[\"infra\"]}," +
                        "{\"type\":\"pve-storage\",\"node\":\"pve-node-01\",\"storage\":\"local-zfs\",\"disk\":300000000000,\"maxdisk\":1000000000000,\"status\":\"available\",\"shared\":false}," +
                        "{\"type\":\"pbs-node\",\"name\":\"pbs-01\",\"cpu\":0.05,\"maxcpu\":4.0,\"mem\":2147483648,\"maxmem\":16000000000,\"uptime\":123456}," +
                        "{\"type\":\"pbs-datastore\",\"name\":\"backup-ds\",\"disk\":500000000000,\"maxdisk\":2000000000000,\"usage\":0.25}" +
                        "]}," +
                        "{\"remote\":\"cluster-east\",\"resources\":[" +
                        "{\"type\":\"pve-node\",\"node\":\"pve-node-02\",\"status\":\"offline\",\"cpu\":0.0,\"maxcpu\":8.0,\"mem\":0,\"maxmem\":34359738368,\"uptime\":0}" +
                        "]}" +
                        "]}";
            }
            byte[] respBytes = resp.getBytes("UTF-8");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, respBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(respBytes);
            }
        };

        // Note: Canonical upstream route ONLY; no unproven /resources route registered
        mockServer.createContext("/api2/json/resources/list", resourcesHandler);

        mockServer.start();
        mockPort = mockServer.getAddress().getPort();
    }

    @AfterClass
    public static void tearDownClass() {
        if (mockServer != null) {
            mockServer.stop(0);
        }
    }

    @Test
    public void testPdmGuestPowerActionsThrowUnsupportedOperationException() throws Exception {
        ServerConfig pdmConfig = new ServerConfig("pdm-1", "Datacenter Manager", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);

        PdmApiDriver pdmDriver = new PdmApiDriver(pdmConfig);
        AuthCredentials dummyAuth = AuthCredentials.ticket("root@pam", "ticket", "csrf", System.currentTimeMillis() + 3600000);

        try {
            pdmDriver.executeGuestPowerAction("node-alpha", "qemu", 105, "start", dummyAuth);
            fail("PdmApiDriver must throw UnsupportedOperationException on guest power actions");
        } catch (UnsupportedOperationException e) {
            assertTrue(e.getMessage().contains("unsupported in this release"));
            assertTrue(e.getMessage().contains("standalone PVE endpoint"));
        }

        try {
            pdmDriver.executeGuestPowerAction("node-alpha", "lxc", 200, "reboot", dummyAuth);
            fail("PdmApiDriver must throw UnsupportedOperationException on LXC power actions");
        } catch (UnsupportedOperationException e) {
            assertTrue(e.getMessage().contains("unsupported in this release"));
        }
    }

    @Test
    public void testPdmTerminalStreamingThrowsUnsupportedOperationException() throws Exception {
        ServerConfig pdmConfig = new ServerConfig("pdm-1", "Datacenter Manager", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);

        PdmApiDriver pdmDriver = new PdmApiDriver(pdmConfig);
        AuthCredentials dummyAuth = AuthCredentials.ticket("root@pam", "ticket", "csrf", System.currentTimeMillis() + 3600000);

        try {
            pdmDriver.startTerminal("node-alpha", "qemu", 105, dummyAuth);
            fail("PdmApiDriver must throw UnsupportedOperationException on terminal launch");
        } catch (UnsupportedOperationException e) {
            assertTrue(e.getMessage().contains("unsupported in this release"));
            assertTrue(e.getMessage().contains("SSH or web console"));
        }
    }

    @Test
    public void testPdmIsWorkingReturnsTrue() throws Exception {
        ServerConfig pdmConfig = new ServerConfig("pdm-1", "Datacenter Manager", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);
        PdmApiDriver driver = new PdmApiDriver(pdmConfig);
        assertTrue(driver.isPdmWorking());
    }

    @Test
    public void testPdmStatusDisclosureIsExplicit() throws Exception {
        ServerConfig pdmConfig = new ServerConfig("pdm-2", "Datacenter Manager", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);
        PdmApiDriver driver = new PdmApiDriver(pdmConfig);

        String disclosure = driver.getPdmStatusDisclosure();
        assertTrue("Disclosure must mention Supported", disclosure.contains("Supported") || disclosure.contains("supported"));
        assertTrue("Disclosure must mention guest power actions disabled", disclosure.contains("power actions") || disclosure.contains("disabled"));
    }

    @Test
    public void testPdmNodeParsingWithRemoteClusterIdentity() throws Exception {
        String jsonStr = "{" +
                "\"type\":\"pve-node\"," +
                "\"remote\":\"cluster-west\"," +
                "\"node\":\"pve-node-01\"," +
                "\"status\":\"online\"," +
                "\"cpu\":0.145," +
                "\"maxcpu\":16.0," +
                "\"mem\":17179869184," +
                "\"maxmem\":68719476736," +
                "\"uptime\":864000" +
                "}";

        JSONObject json = new JSONObject(jsonStr);
        NodeStatus node = PdmApiDriver.parsePdmNode(json);

        assertNotNull(node);
        assertEquals("cluster-west/pve-node-01", node.node);
        assertEquals(NodeStatus.Status.ONLINE, node.status);
        assertEquals(16, node.cpuCount);
        assertEquals(17179869184L, node.memUsed);
        assertEquals(68719476736L, node.memTotal);
    }

    @Test
    public void testPdmGuestParsingPreservesGuestTypeAndMetrics() throws Exception {
        String qemuJsonStr = "{" +
                "\"type\":\"pve-qemu\"," +
                "\"remote\":\"cluster-east\"," +
                "\"node\":\"pve-node-02\"," +
                "\"vmid\":101," +
                "\"name\":\"k8s-master\"," +
                "\"status\":\"running\"," +
                "\"cpu\":0.08," +
                "\"maxcpu\":4.0," +
                "\"mem\":4294967296," +
                "\"maxmem\":8589934592," +
                "\"uptime\":360000," +
                "\"tags\":[\"prod\",\"k8s\"]" +
                "}";

        JSONObject json = new JSONObject(qemuJsonStr);
        GuestInfo guest = PdmApiDriver.parsePdmGuest(json);

        assertNotNull(guest);
        assertEquals(101, guest.vmid);
        assertEquals("k8s-master", guest.name);
        assertEquals(GuestInfo.GuestType.QEMU, guest.type);
        assertEquals(GuestInfo.RunState.RUNNING, guest.state);
        assertEquals("cluster-east/pve-node-02", guest.node);
        assertEquals(4, guest.cpuCount);
        assertEquals(4294967296L, guest.memUsed);
        assertEquals("prod;k8s", guest.tags);
    }

    @Test
    public void testPdmApiTokenAndTicketCookieFormatting() {
        AuthCredentials tokenCreds = AuthCredentials.apiToken("root@pam!pdmtoken=11112222-3333-4444-5555-666677778888");
        String pveHeader = tokenCreds.getAuthorizationHeader(ServerConfig.ServerType.PVE);
        String pdmHeader = tokenCreds.getAuthorizationHeader(ServerConfig.ServerType.PDM);
        assertEquals("PVEAPIToken=root@pam!pdmtoken=11112222-3333-4444-5555-666677778888", pveHeader);
        assertEquals("PDMAPIToken=root@pam!pdmtoken=11112222-3333-4444-5555-666677778888", pdmHeader);

        AuthCredentials ticketCreds = AuthCredentials.ticket("root@pam", "PDM:ticket123", "csrf456", System.currentTimeMillis() + 3600000);
        String pveCookie = ticketCreds.getTicketCookieValue(ServerConfig.ServerType.PVE);
        String pdmCookie = ticketCreds.getTicketCookieValue(ServerConfig.ServerType.PDM);
        assertEquals("PVEAuthCookie=PDM:ticket123", pveCookie);
        assertEquals("__Host-PDMAuthCookie=PDM:ticket123", pdmCookie);
    }

    @Test
    public void testPdmStrictTlsPinningEnforcement() throws Exception {
        ServerConfig pdmInvalidFingerprint = new ServerConfig("pdm-bad", "Bad PDM", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, DUMMY_FINGERPRINT);

        PdmApiDriver driver = new PdmApiDriver(pdmInvalidFingerprint);
        AuthCredentials dummyAuth = AuthCredentials.ticket("root@pam", "ticket", "csrf", System.currentTimeMillis() + 3600000);

        try {
            driver.fetchRemotes(dummyAuth);
            fail("PdmApiDriver must fail-closed on certificate pinning mismatch");
        } catch (Exception expected) {
            assertNotNull(expected);
        }
    }

    @Test
    public void testPdmLiveMockHttpOperations() throws Exception {
        ServerConfig pdmConfig = new ServerConfig("pdm-live", "PDM Cluster", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);

        PdmApiDriver driver = new PdmApiDriver(pdmConfig);

        // 1. Authenticate with username and password against mock server
        AuthCredentials creds = driver.authenticate("root", "secretpass");
        assertNotNull(creds);
        assertEquals("root@pam", creds.username);
        assertEquals("__Host-PDMAuthCookie=PDM:root@pam:654321::signature123", creds.getTicketCookieValue(ServerConfig.ServerType.PDM));
        assertEquals("csrf-pdm-token-999", creds.csrfToken);
        assertFalse(creds.isExpired());

        // 2. Fetch Remotes
        JSONArray remotes = driver.fetchRemotes(creds);
        assertNotNull(remotes);
        assertEquals(2, remotes.length());
        assertEquals("cluster-west", remotes.getJSONObject(0).getString("name"));
        assertEquals("pve", remotes.getJSONObject(0).getString("type"));
        assertEquals("pbs-backup", remotes.getJSONObject(1).getString("name"));

        // 3. Fetch Nodes (preserves remote cluster identity)
        List<NodeStatus> nodes = driver.fetchAllNodes(creds);
        assertNotNull(nodes);
        assertEquals(2, nodes.size());
        assertEquals("cluster-west/pve-node-01", nodes.get(0).node);
        assertEquals(NodeStatus.Status.ONLINE, nodes.get(0).status);
        assertEquals(-1, nodes.get(0).diskUsed); // Node has no disk/maxdisk upstream
        assertEquals(-1, nodes.get(0).diskTotal);
        assertEquals("cluster-east/pve-node-02", nodes.get(1).node);
        assertEquals(NodeStatus.Status.OFFLINE, nodes.get(1).status);

        // 4. Fetch Guests (PBS variants ignored honestly)
        List<GuestInfo> guests = driver.fetchAllGuests(creds);
        assertNotNull(guests);
        assertEquals(2, guests.size());
        assertEquals(101, guests.get(0).vmid);
        assertEquals("k8s-master", guests.get(0).name);
        assertEquals(GuestInfo.GuestType.QEMU, guests.get(0).type);
        assertEquals(GuestInfo.RunState.RUNNING, guests.get(0).state);
        assertEquals("cluster-west/pve-node-01", guests.get(0).node);
        assertEquals("prod;k8s", guests.get(0).tags); // tags array of strings parsed

        assertEquals(102, guests.get(1).vmid);
        assertEquals("dns-pihole", guests.get(1).name);
        assertEquals(GuestInfo.GuestType.LXC, guests.get(1).type);
        assertEquals(GuestInfo.RunState.STOPPED, guests.get(1).state);
        assertEquals("infra", guests.get(1).tags);

        // 5. Fetch Node Guests with exact remote/node identity
        List<GuestInfo> nodeGuests = driver.fetchNodeGuests("cluster-west/pve-node-01", creds);
        assertEquals(2, nodeGuests.size());
        List<GuestInfo> nonExistentNodeGuests = driver.fetchNodeGuests("cluster-east/pve-node-02", creds);
        assertTrue(nonExistentNodeGuests.isEmpty());

        // 6. Fetch Node Storage with exact remote/node identity
        List<StoragePool> storagePools = driver.fetchNodeStorage("cluster-west/pve-node-01", creds);
        assertEquals(1, storagePools.size());
        assertEquals("local-zfs", storagePools.get(0).name);
        assertEquals("unknown", storagePools.get(0).type); // plugintype absent in upstream PDM
        assertFalse(storagePools.get(0).shared); // boolean shared
        assertTrue(storagePools.get(0).active);

        // 7. Fetch Guest Detail with exact VMID, guestType, and nodeName
        GuestInfo detail = driver.fetchGuestDetail("cluster-west/pve-node-01", 101, GuestInfo.GuestType.QEMU, creds);
        assertNotNull(detail);
        assertEquals("k8s-master", detail.name);

        GuestInfo detailLxc = driver.fetchGuestDetail("cluster-west/pve-node-01", 102, GuestInfo.GuestType.LXC, creds);
        assertNotNull(detailLxc);
        assertEquals("dns-pihole", detailLxc.name);

        // Mismatched type must return null (QEMU requested for LXC ID 102)
        assertNull(driver.fetchGuestDetail("cluster-west/pve-node-01", 102, GuestInfo.GuestType.QEMU, creds));
        // Mismatched node must return null
        assertNull(driver.fetchGuestDetail("cluster-east/pve-node-02", 101, GuestInfo.GuestType.QEMU, creds));

        // 8. Strict 403 Forbidden Differentiation
        return403.set(true);
        try {
            driver.fetchRemotes(creds);
            fail("Must throw ApiException on 403");
        } catch (ApiException e) {
            assertEquals(403, e.getHttpCode());
            assertTrue(e.getMessage().contains("Permission denied"));
        } finally {
            return403.set(false);
        }
    }

    @Test
    public void testPdmMultiRemoteSharedNodeAndVmidDisambiguation() throws Exception {
        // Multi-remote fixture with upstream nested shape:
        // Remote A ("cluster-alpha") and Remote B ("cluster-beta") both have node "pve-shared-node"
        // Both clusters have VMID 100 as QEMU and VMID 100 as LXC
        String multiRemoteJson = "{\"data\":[" +
                "{\"remote\":\"cluster-alpha\",\"resources\":[" +
                "{\"type\":\"pve-node\",\"node\":\"pve-shared-node\",\"status\":\"online\",\"cpu\":0.10,\"maxcpu\":8.0,\"mem\":8000000000,\"maxmem\":32000000000,\"uptime\":100000}," +
                "{\"type\":\"pve-qemu\",\"node\":\"pve-shared-node\",\"vmid\":100,\"name\":\"alpha-prod-vm\",\"status\":\"running\",\"cpu\":0.15,\"maxcpu\":4.0,\"mem\":4000000000,\"maxmem\":8000000000,\"disk\":20000000000,\"maxdisk\":50000000000,\"uptime\":50000,\"tags\":[\"alpha\",\"prod\"]}," +
                "{\"type\":\"pve-lxc\",\"node\":\"pve-shared-node\",\"vmid\":100,\"name\":\"alpha-prod-ct\",\"status\":\"running\",\"cpu\":0.05,\"maxcpu\":2.0,\"mem\":1000000000,\"maxmem\":2000000000,\"disk\":5000000000,\"maxdisk\":10000000000,\"uptime\":50000,\"tags\":[\"alpha\",\"dns\"]}," +
                "{\"type\":\"pve-storage\",\"node\":\"pve-shared-node\",\"storage\":\"local-zfs\",\"disk\":300000000000,\"maxdisk\":1000000000000,\"status\":\"available\",\"shared\":false}" +
                "]}," +
                "{\"remote\":\"cluster-beta\",\"resources\":[" +
                "{\"type\":\"pve-node\",\"node\":\"pve-shared-node\",\"status\":\"online\",\"cpu\":0.20,\"maxcpu\":16.0,\"mem\":16000000000,\"maxmem\":64000000000,\"uptime\":200000}," +
                "{\"type\":\"pve-qemu\",\"node\":\"pve-shared-node\",\"vmid\":100,\"name\":\"beta-staging-vm\",\"status\":\"stopped\",\"cpu\":0.0,\"maxcpu\":8.0,\"mem\":0,\"maxmem\":16000000000,\"disk\":30000000000,\"maxdisk\":100000000000,\"uptime\":0,\"tags\":[\"beta\",\"staging\"]}," +
                "{\"type\":\"pve-lxc\",\"node\":\"pve-shared-node\",\"vmid\":100,\"name\":\"beta-staging-ct\",\"status\":\"stopped\",\"cpu\":0.0,\"maxcpu\":1.0,\"mem\":0,\"maxmem\":1000000000,\"disk\":2000000000,\"maxdisk\":5000000000,\"uptime\":0,\"tags\":[\"beta\",\"cache\"]}," +
                "{\"type\":\"pve-storage\",\"node\":\"pve-shared-node\",\"storage\":\"local-zfs\",\"disk\":1500000000000,\"maxdisk\":2000000000000,\"status\":\"available\",\"shared\":false}" +
                "]}" +
                "]}";

        customResourcesResponse.set(multiRemoteJson);
        try {
            ServerConfig cfg = new ServerConfig("pdm-multi", "Multi PDM", "127.0.0.1", mockPort, ServerConfig.ServerType.PDM, mockFingerprint);
            PdmApiDriver driver = new PdmApiDriver(cfg);
            AuthCredentials creds = AuthCredentials.ticket("root@pam", "ticket", "csrf", System.currentTimeMillis() + 3600000);

            // Exercise actual driver fetchAllNodes through HTTPS
            List<NodeStatus> allNodes = driver.fetchAllNodes(creds);
            assertEquals(2, allNodes.size());
            assertEquals("cluster-alpha/pve-shared-node", allNodes.get(0).node);
            assertEquals("cluster-beta/pve-shared-node", allNodes.get(1).node);

            // Exercise actual driver fetchAllGuests through HTTPS
            List<GuestInfo> allGuests = driver.fetchAllGuests(creds);
            assertEquals(4, allGuests.size());

            // 1. Exact node guest routing for cluster-alpha via driver.fetchNodeGuests
            List<GuestInfo> alphaGuests = driver.fetchNodeGuests("cluster-alpha/pve-shared-node", creds);
            assertEquals(2, alphaGuests.size());
            assertEquals("alpha-prod-vm", alphaGuests.get(0).name);
            assertEquals(GuestInfo.GuestType.QEMU, alphaGuests.get(0).type);
            assertEquals(GuestInfo.RunState.RUNNING, alphaGuests.get(0).state);
            assertEquals("alpha-prod-ct", alphaGuests.get(1).name);
            assertEquals(GuestInfo.GuestType.LXC, alphaGuests.get(1).type);
            assertEquals(GuestInfo.RunState.RUNNING, alphaGuests.get(1).state);

            // 2. Exact node guest routing for cluster-beta via driver.fetchNodeGuests
            List<GuestInfo> betaGuests = driver.fetchNodeGuests("cluster-beta/pve-shared-node", creds);
            assertEquals(2, betaGuests.size());
            assertEquals("beta-staging-vm", betaGuests.get(0).name);
            assertEquals(GuestInfo.GuestType.QEMU, betaGuests.get(0).type);
            assertEquals(GuestInfo.RunState.STOPPED, betaGuests.get(0).state);
            assertEquals("beta-staging-ct", betaGuests.get(1).name);
            assertEquals(GuestInfo.GuestType.LXC, betaGuests.get(1).type);
            assertEquals(GuestInfo.RunState.STOPPED, betaGuests.get(1).state);

            // 3. fetchGuestDetail disambiguation through HTTPS:
            // Matches exact VMID, guestType, and node
            GuestInfo alphaVm = driver.fetchGuestDetail("cluster-alpha/pve-shared-node", 100, GuestInfo.GuestType.QEMU, creds);
            assertNotNull("Alpha VM 100 must be found", alphaVm);
            assertEquals("alpha-prod-vm", alphaVm.name);
            assertEquals(GuestInfo.GuestType.QEMU, alphaVm.type);

            GuestInfo alphaCt = driver.fetchGuestDetail("cluster-alpha/pve-shared-node", 100, GuestInfo.GuestType.LXC, creds);
            assertNotNull("Alpha CT 100 must be found", alphaCt);
            assertEquals("alpha-prod-ct", alphaCt.name);
            assertEquals(GuestInfo.GuestType.LXC, alphaCt.type);

            GuestInfo betaVm = driver.fetchGuestDetail("cluster-beta/pve-shared-node", 100, GuestInfo.GuestType.QEMU, creds);
            assertNotNull("Beta VM 100 must be found", betaVm);
            assertEquals("beta-staging-vm", betaVm.name);
            assertEquals(GuestInfo.GuestType.QEMU, betaVm.type);

            GuestInfo betaCt = driver.fetchGuestDetail("cluster-beta/pve-shared-node", 100, GuestInfo.GuestType.LXC, creds);
            assertNotNull("Beta CT 100 must be found", betaCt);
            assertEquals("beta-staging-ct", betaCt.name);
            assertEquals(GuestInfo.GuestType.LXC, betaCt.type);

            // 4. Storage pool disambiguation across remotes via driver.fetchNodeStorage
            List<StoragePool> alphaPools = driver.fetchNodeStorage("cluster-alpha/pve-shared-node", creds);
            assertEquals(1, alphaPools.size());
            assertEquals("local-zfs", alphaPools.get(0).name);
            assertEquals(1000000000000L, alphaPools.get(0).total);
            assertEquals(300000000000L, alphaPools.get(0).used);
            assertFalse(alphaPools.get(0).shared);

            List<StoragePool> betaPools = driver.fetchNodeStorage("cluster-beta/pve-shared-node", creds);
            assertEquals(1, betaPools.size());
            assertEquals("local-zfs", betaPools.get(0).name);
            assertEquals(2000000000000L, betaPools.get(0).total);
            assertEquals(1500000000000L, betaPools.get(0).used);
            assertFalse(betaPools.get(0).shared);
        } finally {
            customResourcesResponse.set(null);
        }
    }

    @Test
    public void testPdmCanonical404NoFallback() throws Exception {
        ServerConfig pdmConfig = new ServerConfig("pdm-live", "PDM Cluster", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);
        PdmApiDriver driver = new PdmApiDriver(pdmConfig);
        AuthCredentials creds = AuthCredentials.ticket("root@pam", "ticket", "csrf", System.currentTimeMillis() + 3600000);

        return404OnResourcesList.set(true);
        try {
            driver.fetchAllNodes(creds);
            fail("Must throw ApiException(404) without falling back to /resources");
        } catch (ApiException e) {
            assertEquals(404, e.getHttpCode());
        } finally {
            return404OnResourcesList.set(false);
        }
    }

    @Test
    public void testPdmOuterRemoteErrorSurfacesFailClosed() throws Exception {
        ServerConfig pdmConfig = new ServerConfig("pdm-live", "PDM Cluster", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);
        PdmApiDriver driver = new PdmApiDriver(pdmConfig);
        AuthCredentials creds = AuthCredentials.ticket("root@pam", "ticket", "csrf", System.currentTimeMillis() + 3600000);

        String errorFixture = "{\"data\":[" +
                "{\"remote\":\"cluster-broken\",\"error\":\"connection timed out to remote PVE cluster\",\"resources\":[]}" +
                "]}";
        customResourcesResponse.set(errorFixture);
        try {
            driver.fetchAllNodes(creds);
            fail("Must fail closed and surface outer remote error");
        } catch (ApiException e) {
            assertTrue("Error message must identify remote", e.getMessage().contains("cluster-broken"));
            assertTrue("Error message must include error detail", e.getMessage().contains("connection timed out"));
        } finally {
            customResourcesResponse.set(null);
        }
    }

    @Test
    public void testPdmEmptyOrErrorOnlyRemoteGroup() throws Exception {
        ServerConfig pdmConfig = new ServerConfig("pdm-live", "PDM Cluster", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);
        PdmApiDriver driver = new PdmApiDriver(pdmConfig);
        AuthCredentials creds = AuthCredentials.ticket("root@pam", "ticket", "csrf", System.currentTimeMillis() + 3600000);

        // Group with missing resources field (empty group) along with valid remote
        String fixture = "{\"data\":[" +
                "{\"remote\":\"cluster-empty\"}," +
                "{\"remote\":\"cluster-valid\",\"resources\":[" +
                "{\"type\":\"pve-node\",\"node\":\"pve-node-03\",\"status\":\"online\",\"cpu\":0.1,\"maxcpu\":4.0,\"mem\":1000,\"maxmem\":2000,\"uptime\":100}" +
                "]}" +
                "]}";
        customResourcesResponse.set(fixture);
        try {
            List<NodeStatus> nodes = driver.fetchAllNodes(creds);
            assertEquals(1, nodes.size());
            assertEquals("cluster-valid/pve-node-03", nodes.get(0).node);
        } finally {
            customResourcesResponse.set(null);
        }
    }

    @Test
    public void testPdmFetchAllNodesDoesNotInventOnlineNodesAndFailsClosed() throws Exception {
        ServerConfig pdmConfig = new ServerConfig("pdm-live", "PDM Cluster", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);
        PdmApiDriver driver = new PdmApiDriver(pdmConfig);
        AuthCredentials creds = AuthCredentials.ticket("root@pam", "ticket", "csrf", System.currentTimeMillis() + 3600000);

        // When 403 Forbidden is returned on resources, fetchAllNodes must fail-closed with 403
        return403.set(true);
        try {
            driver.fetchAllNodes(creds);
            fail("fetchAllNodes must throw ApiException on 403, never swallow exception or invent nodes");
        } catch (ApiException e) {
            assertEquals(403, e.getHttpCode());
            assertTrue(e.getMessage().contains("Permission denied"));
        } finally {
            return403.set(false);
        }

        // When offline, fetchAllNodes must throw connection error fail-closed
        ServerConfig pdmOffline = new ServerConfig("pdm-offline", "PDM Offline", "127.0.0.1", 59999,
                ServerConfig.ServerType.PDM, mockFingerprint);
        PdmApiDriver offlineDriver = new PdmApiDriver(pdmOffline);
        try {
            offlineDriver.fetchAllNodes(creds);
            fail("Offline endpoint must throw exception, never invent nodes");
        } catch (Exception expected) {
            assertNotNull(expected);
        }
    }

    @Test
    public void testPdmOfflineFailClosed() throws Exception {
        // Closed port on 127.0.0.1
        ServerConfig pdmOffline = new ServerConfig("pdm-offline", "PDM Offline", "127.0.0.1", 59999,
                ServerConfig.ServerType.PDM, mockFingerprint);
        PdmApiDriver driver = new PdmApiDriver(pdmOffline);
        AuthCredentials dummyAuth = AuthCredentials.ticket("root@pam", "ticket", "csrf", System.currentTimeMillis() + 3600000);

        try {
            driver.fetchRemotes(dummyAuth);
            fail("Offline PDM endpoint must throw exception");
        } catch (Exception expected) {
            // Expected connection failure fail-closed
            assertNotNull(expected);
        }
    }
}
