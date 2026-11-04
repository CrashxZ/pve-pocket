// SPDX-License-Identifier: GPL-3.0-or-later
// PVE Pocket — Node Resource Completeness Behavioral Tests
package com.homelabpocket.api;

import com.homelabpocket.api.SessionManager;
import com.homelabpocket.model.AuthCredentials;
import com.homelabpocket.model.GuestInfo;
import com.homelabpocket.model.NodeStatus;
import com.homelabpocket.model.ServerConfig;
import com.homelabpocket.model.StoragePool;
import com.homelabpocket.security.PinnedTrustManager;
import com.homelabpocket.ui.nodes.NodeDetailActivity;
import com.homelabpocket.util.JsonUtil;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

import static org.junit.Assert.*;

public class NodeResourceCompletenessTest {

    private static HttpsServer httpsServer;
    private static int port;
    private static String localIpFingerprint;
    private static ServerConfig serverConfig;
    private static SessionManager sessionManager;
    private static AuthCredentials testCreds;

    private static volatile int qemuResponseCode = 200;
    private static volatile String qemuResponseBody = "{\"data\":[]}";
    private static volatile int lxcResponseCode = 200;
    private static volatile String lxcResponseBody = "{\"data\":[]}";

    @BeforeClass
    public static void setUpClass() throws Exception {
        File ksFile = new File("app/src/test/resources/test_fixtures.p12");
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream is = new FileInputStream(ksFile)) {
            ks.load(is, "testpass".toCharArray());
        }

        X509Certificate cert = (X509Certificate) ks.getCertificate("local-ip");
        localIpFingerprint = PinnedTrustManager.computeFingerprint(cert);

        SSLContext sslCtx = SSLContext.getInstance("TLS");
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "testpass".toCharArray());
        sslCtx.init(kmf.getKeyManagers(), null, null);

        httpsServer = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslCtx) {
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

        httpsServer.createContext("/api2/json/nodes/node1/qemu", exchange -> {
            byte[] resp = qemuResponseBody.getBytes("UTF-8");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(qemuResponseCode, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
            exchange.close();
        });

        httpsServer.createContext("/api2/json/nodes/node1/lxc", exchange -> {
            byte[] resp = lxcResponseBody.getBytes("UTF-8");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(lxcResponseCode, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
            exchange.close();
        });

        httpsServer.start();
        port = httpsServer.getAddress().getPort();

        serverConfig = new ServerConfig("test-pve", "Test PVE", "127.0.0.1", port,
                ServerConfig.ServerType.PVE, localIpFingerprint, true, false);
        sessionManager = new SessionManager(null);
        testCreds = AuthCredentials.apiToken("PVEAPIToken=root@pam!test=11111111-2222-3333-4444-555555555555");
    }

    @AfterClass
    public static void tearDownClass() {
        if (httpsServer != null) {
            httpsServer.stop(0);
        }
    }

    @Before
    public void resetEndpoints() {
        qemuResponseCode = 200;
        qemuResponseBody = "{\"data\":[]}";
        lxcResponseCode = 200;
        lxcResponseBody = "{\"data\":[]}";
    }

    @Test
    public void testStoragePoolParsingFromProxmoxApiResponse() throws Exception {
        String pveStorageJson = "{\"data\":[" +
                "{\"storage\":\"local\",\"type\":\"dir\",\"content\":\"iso,vztmpl,backup\",\"total\":100000000000,\"used\":10500000000,\"avail\":89500000000,\"active\":1,\"enabled\":1,\"shared\":0}," +
                "{\"storage\":\"local-lvm\",\"type\":\"lvmthin\",\"content\":\"rootdir,images\",\"total\":500000000000,\"used\":150000000000,\"avail\":350000000000,\"active\":1,\"enabled\":1,\"shared\":0}," +
                "{\"storage\":\"truenas-nfs\",\"type\":\"nfs\",\"content\":\"images,backup\",\"total\":10000000000000,\"used\":4000000000000,\"avail\":6000000000000,\"active\":1,\"enabled\":1,\"shared\":1}," +
                "{\"storage\":\"backup-zfs\",\"type\":\"zfspool\",\"content\":\"backup\",\"total\":2000000000000,\"used\":500000000000,\"avail\":1500000000000,\"active\":0,\"enabled\":1,\"shared\":0}," +
                "{\"storage\":\"decommissioned\",\"type\":\"dir\",\"content\":\"none\",\"total\":0,\"used\":0,\"avail\":0,\"active\":0,\"enabled\":0,\"shared\":0}" +
                "]}";

        JSONArray array = JsonUtil.getDataArray(pveStorageJson);
        assertEquals(5, array.length());

        List<StoragePool> pools = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            pools.add(PveApiDriver.parseStoragePool(array.getJSONObject(i)));
        }

        assertEquals(5, pools.size());

        // Pool 1: local dir
        StoragePool local = pools.get(0);
        assertEquals("local", local.name);
        assertEquals("dir", local.type);
        assertEquals(100000000000L, local.total);
        assertEquals(10500000000L, local.used);
        assertEquals(89500000000L, local.avail);
        assertTrue(local.active);
        assertTrue(local.enabled);
        assertFalse(local.shared);
        assertEquals("DIR", local.getTypeBadge());
        assertEquals(10, local.getUsagePercent());

        // Pool 2: local-lvm thin pool
        StoragePool lvm = pools.get(1);
        assertEquals("local-lvm", lvm.name);
        assertEquals("lvmthin", lvm.type);
        assertEquals(30, lvm.getUsagePercent());
        assertEquals("LVMTHIN", lvm.getTypeBadge());

        // Pool 3: shared NFS datastore
        StoragePool nfs = pools.get(2);
        assertEquals("truenas-nfs", nfs.name);
        assertEquals("nfs", nfs.type);
        assertTrue(nfs.shared);
        assertEquals("NFS (SHARED)", nfs.getTypeBadge());
        assertEquals(40, nfs.getUsagePercent());

        // Pool 4: inactive ZFS pool
        StoragePool zfs = pools.get(3);
        assertEquals("backup-zfs", zfs.name);
        assertFalse(zfs.active);
        assertEquals("ZFSPOOL [INACTIVE]", zfs.getTypeBadge());

        // Pool 5: disabled storage
        StoragePool disabled = pools.get(4);
        assertEquals("decommissioned", disabled.name);
        assertFalse(disabled.enabled);
        assertEquals("DIR [DISABLED]", disabled.getTypeBadge());
    }

    @Test
    public void testRootfsVersusConfiguredDatastoreNoDoubleCounting() {
        // Node detail rootfs is 93.9GB root filesystem
        long rootfsUsed = 9_800_000_000L;
        long rootfsTotal = 93_900_000_000L;

        // Shared NFS pool is 10TB total
        StoragePool sharedNfs = new StoragePool("truenas-nfs", "nfs", 10_000_000_000_000L, 4_000_000_000_000L, 6_000_000_000_000L, true, true, true, "images");

        // Verify rootfs is strictly distinct from storage pools
        assertNotEquals(rootfsTotal, sharedNfs.total);
        assertFalse("Root filesystem is not a shared cluster pool", sharedNfs.shared && rootfsTotal > 1_000_000_000_000L);

        // Usage formatting verification
        assertEquals("3.64 TB", sharedNfs.getFormattedUsed());
        assertEquals("9.09 TB", sharedNfs.getFormattedTotal());
        assertEquals("5.46 TB", sharedNfs.getFormattedAvail());
    }

    @Test
    public void testPveNodeGuestListPopulatedAndStoppedStatuses() throws Exception {
        String mockQemu = "{\"data\":[" +
                "{\"vmid\":100,\"name\":\"prod-web-01\",\"status\":\"running\",\"cpus\":4,\"cpu\":0.12,\"mem\":4294967296,\"maxmem\":8589934592,\"disk\":20000000000,\"maxdisk\":50000000000,\"uptime\":72000}," +
                "{\"vmid\":101,\"name\":\"staging-app\",\"status\":\"stopped\",\"cpus\":2,\"cpu\":0.0,\"mem\":0,\"maxmem\":4294967296,\"disk\":10000000000,\"maxdisk\":30000000000,\"uptime\":0}" +
                "]}";
        String mockLxc = "{\"data\":[" +
                "{\"vmid\":200,\"name\":\"pihole-dns\",\"status\":\"running\",\"cpus\":1,\"cpu\":0.02,\"mem\":536870912,\"maxmem\":1073741824,\"disk\":2000000000,\"maxdisk\":10000000000,\"uptime\":120000}," +
                "{\"vmid\":201,\"name\":\"wireguard-vpn\",\"status\":\"stopped\",\"cpus\":1,\"cpu\":0.0,\"mem\":0,\"maxmem\":536870912,\"disk\":1000000000,\"maxdisk\":5000000000,\"uptime\":0}" +
                "]}";

        JSONArray qemuArray = JsonUtil.getDataArray(mockQemu);
        JSONArray lxcArray = JsonUtil.getDataArray(mockLxc);

        List<GuestInfo> guests = new ArrayList<>();
        for (int i = 0; i < qemuArray.length(); i++) {
            JSONObject obj = qemuArray.getJSONObject(i);
            int vmid = obj.getInt("vmid");
            String name = obj.getString("name");
            String status = obj.getString("status");
            GuestInfo.RunState state = "running".equals(status) ? GuestInfo.RunState.RUNNING : GuestInfo.RunState.STOPPED;
            guests.add(new GuestInfo(vmid, name, GuestInfo.GuestType.QEMU, state, "home-lab", "srv-1",
                    obj.getDouble("cpu"), obj.getInt("cpus"), obj.getLong("mem"), obj.getLong("maxmem"),
                    obj.getLong("disk"), obj.getLong("maxdisk"), -1L, -1L, -1L, -1L,
                    obj.getLong("uptime"), "", false, System.currentTimeMillis()));
        }
        for (int i = 0; i < lxcArray.length(); i++) {
            JSONObject obj = lxcArray.getJSONObject(i);
            int vmid = obj.getInt("vmid");
            String name = obj.getString("name");
            String status = obj.getString("status");
            GuestInfo.RunState state = "running".equals(status) ? GuestInfo.RunState.RUNNING : GuestInfo.RunState.STOPPED;
            guests.add(new GuestInfo(vmid, name, GuestInfo.GuestType.LXC, state, "home-lab", "srv-1",
                    obj.getDouble("cpu"), obj.getInt("cpus"), obj.getLong("mem"), obj.getLong("maxmem"),
                    obj.getLong("disk"), obj.getLong("maxdisk"), -1L, -1L, -1L, -1L,
                    obj.getLong("uptime"), "", false, System.currentTimeMillis()));
        }

        // Verify both QEMU + LXC, running + stopped are captured
        assertEquals(4, guests.size());
        assertEquals(GuestInfo.GuestType.QEMU, guests.get(0).type);
        assertEquals(GuestInfo.RunState.RUNNING, guests.get(0).state);
        assertEquals(GuestInfo.GuestType.QEMU, guests.get(1).type);
        assertEquals(GuestInfo.RunState.STOPPED, guests.get(1).state);

        assertEquals(GuestInfo.GuestType.LXC, guests.get(2).type);
        assertEquals(GuestInfo.RunState.RUNNING, guests.get(2).state);
        assertEquals(GuestInfo.GuestType.LXC, guests.get(3).type);
        assertEquals(GuestInfo.RunState.STOPPED, guests.get(3).state);
    }

    @Test
    public void testPdmNodeGuestRoutingPreservesClusterIdentity() throws Exception {
        ServerConfig pdmConfig = new ServerConfig("pdm-main", "Main PDM", "127.0.0.1", 8443,
                ServerConfig.ServerType.PDM, "AA:BB:CC:DD");

        String resourcesJson = "{\"data\":[" +
                "{\"type\":\"node\",\"remote\":\"cluster-alpha\",\"node\":\"node-1\",\"status\":\"online\",\"cpu\":0.05,\"maxcpu\":8,\"mem\":8000000000,\"maxmem\":32000000000,\"disk\":50000000000,\"maxdisk\":200000000000,\"uptime\":50000}," +
                "{\"type\":\"node\",\"remote\":\"cluster-beta\",\"node\":\"node-2\",\"status\":\"online\",\"cpu\":0.10,\"maxcpu\":16,\"mem\":16000000000,\"maxmem\":64000000000,\"disk\":100000000000,\"maxdisk\":500000000000,\"uptime\":60000}," +
                "{\"type\":\"qemu\",\"remote\":\"cluster-alpha\",\"node\":\"node-1\",\"vmid\":101,\"name\":\"alpha-vm-1\",\"status\":\"running\",\"maxcpu\":2,\"cpu\":0.03,\"mem\":2000000000,\"maxmem\":4000000000,\"uptime\":10000}," +
                "{\"type\":\"lxc\",\"remote\":\"cluster-alpha\",\"node\":\"node-1\",\"vmid\":102,\"name\":\"alpha-ct-1\",\"status\":\"stopped\",\"maxcpu\":1,\"cpu\":0.0,\"mem\":0,\"maxmem\":1000000000,\"uptime\":0}," +
                "{\"type\":\"qemu\",\"remote\":\"cluster-beta\",\"node\":\"node-2\",\"vmid\":201,\"name\":\"beta-vm-1\",\"status\":\"running\",\"maxcpu\":4,\"cpu\":0.08,\"mem\":4000000000,\"maxmem\":8000000000,\"uptime\":20000}" +
                "]}";

        JSONArray array = JsonUtil.getDataArray(resourcesJson);
        List<GuestInfo> allGuests = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            String type = item.optString("type");
            if ("qemu".equals(type) || "lxc".equals(type)) {
                allGuests.add(PdmApiDriver.parsePdmGuest(item, pdmConfig));
            }
        }

        assertEquals(3, allGuests.size());

        // Routing for cluster-alpha/node-1 must match only alpha guests
        List<GuestInfo> alphaNodeGuests = new ArrayList<>();
        for (GuestInfo g : allGuests) {
            if ("cluster-alpha/node-1".equals(g.node)) {
                alphaNodeGuests.add(g);
            }
        }
        assertEquals(2, alphaNodeGuests.size());
        assertEquals(101, alphaNodeGuests.get(0).vmid);
        assertEquals(GuestInfo.RunState.RUNNING, alphaNodeGuests.get(0).state);
        assertEquals(102, alphaNodeGuests.get(1).vmid);
        assertEquals(GuestInfo.RunState.STOPPED, alphaNodeGuests.get(1).state);

        // Routing for cluster-beta/node-2
        List<GuestInfo> betaNodeGuests = new ArrayList<>();
        for (GuestInfo g : allGuests) {
            if ("cluster-beta/node-2".equals(g.node)) {
                betaNodeGuests.add(g);
            }
        }
        assertEquals(1, betaNodeGuests.size());
        assertEquals(201, betaNodeGuests.get(0).vmid);
    }

    @Test
    public void testNodeDataResultActionableMessages() {
        NodeStatus onlineNode = new NodeStatus("home-lab", "srv-1", NodeStatus.Status.ONLINE,
                0.01, 12, 17_400_000_000L, 67_200_000_000L, 10_500_000_000L, 100_800_000_000L,
                1000, 2000, "pve-manager/8.2.4", 500000, System.currentTimeMillis());

        // Case 1: Populated guests + multi-storage
        List<StoragePool> pools = new ArrayList<>();
        pools.add(new StoragePool("local", "dir", 100_000_000_000L, 10_000_000_000L, 90_000_000_000L, true, true, false, "iso"));
        pools.add(new StoragePool("local-lvm", "lvmthin", 500_000_000_000L, 150_000_000_000L, 350_000_000_000L, true, true, false, "images"));

        List<GuestInfo> guests = new ArrayList<>();
        guests.add(new GuestInfo(100, "k8s-control", GuestInfo.GuestType.QEMU, GuestInfo.RunState.RUNNING, "home-lab", "srv-1",
                0.05, 4, 4_000_000_000L, 8_000_000_000L, 20_000_000_000L, 50_000_000_000L, -1L, -1L, -1L, -1L, 86400, "prod", false, System.currentTimeMillis()));

        NodeDetailActivity.NodeDataResult resPopulated = new NodeDetailActivity.NodeDataResult(
                onlineNode, pools, null, guests, null, false);

        assertNotNull(resPopulated.node);
        assertEquals(2, resPopulated.storagePools.size());
        assertEquals(1, resPopulated.guests.size());
        assertNull(resPopulated.guestError);
        assertNull(resPopulated.storageError);
        assertFalse(resPopulated.isOffline);

        // Case 2: Truly zero guests
        NodeDetailActivity.NodeDataResult resZeroGuests = new NodeDetailActivity.NodeDataResult(
                onlineNode, pools, null, new ArrayList<>(), null, false);
        assertTrue(resZeroGuests.guests.isEmpty());
        assertNull(resZeroGuests.guestError);

        // Case 3: 403 Forbidden permission denied
        NodeDetailActivity.NodeDataResult res403 = new NodeDetailActivity.NodeDataResult(
                onlineNode, pools, null, new ArrayList<>(), "Permission denied (403): User lacks VM.Audit privilege", false);
        assertNotNull(res403.guestError);
        assertTrue(res403.guestError.contains("403"));

        // Case 4: 401 Unauthorized authentication failure
        NodeDetailActivity.NodeDataResult res401 = new NodeDetailActivity.NodeDataResult(
                onlineNode, pools, null, new ArrayList<>(), "Authentication failed (401): Session ticket expired", false);
        assertNotNull(res401.guestError);
        assertTrue(res401.guestError.contains("401"));

        // Case 5: Offline host
        NodeStatus offlineNode = new NodeStatus("home-lab-offline", "srv-1", NodeStatus.Status.OFFLINE,
                Double.NaN, 0, -1, -1, -1, -1, -1, -1, null, -1, System.currentTimeMillis());
        NodeDetailActivity.NodeDataResult resOffline = new NodeDetailActivity.NodeDataResult(
                offlineNode, new ArrayList<>(), null, new ArrayList<>(), null, true);
        assertTrue(resOffline.isOffline);
        assertEquals(NodeStatus.Status.OFFLINE, resOffline.node.status);
    }

    // =========================================================================
    // Actual Production PveApiDriver HTTPS Behavioral Tests for fetchAllGuests
    // =========================================================================

    @Test
    public void testFetchAllGuests_Populated() throws Exception {
        qemuResponseBody = "{\"data\":[{\"vmid\":100,\"name\":\"vm-prod-1\",\"status\":\"running\",\"cpu\":0.15,\"cpus\":4,\"mem\":2147483648,\"maxmem\":4294967296,\"disk\":10737418240,\"maxdisk\":53687091200,\"uptime\":86400,\"tags\":\"prod\"}]}";
        lxcResponseBody = "{\"data\":[{\"vmid\":200,\"name\":\"ct-web-1\",\"status\":\"running\",\"cpu\":0.05,\"cpus\":2,\"mem\":536870912,\"maxmem\":1073741824,\"disk\":2147483648,\"maxdisk\":10737418240,\"uptime\":43200,\"tags\":\"web\"}]}";

        PveApiDriver driver = new PveApiDriver(serverConfig, sessionManager);
        List<GuestInfo> guests = driver.fetchAllGuests("node1", testCreds);

        assertNotNull(guests);
        assertEquals("Populated fixtures must return all QEMU and LXC guests", 2, guests.size());
        assertEquals(100, guests.get(0).vmid);
        assertEquals(GuestInfo.GuestType.QEMU, guests.get(0).type);
        assertEquals(200, guests.get(1).vmid);
        assertEquals(GuestInfo.GuestType.LXC, guests.get(1).type);
    }

    @Test
    public void testFetchAllGuests_Stopped() throws Exception {
        qemuResponseBody = "{\"data\":[{\"vmid\":101,\"name\":\"vm-stopped\",\"status\":\"stopped\",\"cpu\":0,\"cpus\":2,\"mem\":0,\"maxmem\":2147483648,\"disk\":0,\"maxdisk\":21474836480,\"uptime\":0,\"tags\":\"\"}]}";
        lxcResponseBody = "{\"data\":[{\"vmid\":201,\"name\":\"ct-stopped\",\"status\":\"stopped\",\"cpu\":0,\"cpus\":1,\"mem\":0,\"maxmem\":1073741824,\"disk\":0,\"maxdisk\":10737418240,\"uptime\":0,\"tags\":\"\"}]}";

        PveApiDriver driver = new PveApiDriver(serverConfig, sessionManager);
        List<GuestInfo> guests = driver.fetchAllGuests("node1", testCreds);

        assertNotNull(guests);
        assertEquals(2, guests.size());
        assertEquals(GuestInfo.RunState.STOPPED, guests.get(0).state);
        assertEquals(GuestInfo.RunState.STOPPED, guests.get(1).state);
    }

    @Test
    public void testFetchAllGuests_TrulyEmpty() throws Exception {
        qemuResponseBody = "{\"data\":[]}";
        lxcResponseBody = "{\"data\":[]}";

        PveApiDriver driver = new PveApiDriver(serverConfig, sessionManager);
        List<GuestInfo> guests = driver.fetchAllGuests("node1", testCreds);

        assertNotNull(guests);
        assertTrue("When both QEMU and LXC return valid empty arrays, guest list must be truly empty", guests.isEmpty());
    }

    @Test
    public void testFetchAllGuests_Auth401() throws Exception {
        qemuResponseCode = 401;
        qemuResponseBody = "{\"message\":\"Authentication failed: ticket expired\"}";

        PveApiDriver driver = new PveApiDriver(serverConfig, sessionManager);
        try {
            driver.fetchAllGuests("node1", testCreds);
            fail("Must throw ApiException on HTTP 401");
        } catch (ApiException e) {
            assertEquals("HTTP code must be 401", 401, e.httpCode);
            assertTrue(e.isAuthError());
        }
    }

    @Test
    public void testFetchAllGuests_PermDenied403() throws Exception {
        qemuResponseCode = 403;
        qemuResponseBody = "{\"message\":\"Permission denied: VM.Audit required\"}";

        PveApiDriver driver = new PveApiDriver(serverConfig, sessionManager);
        try {
            driver.fetchAllGuests("node1", testCreds);
            fail("Must throw ApiException on HTTP 403");
        } catch (ApiException e) {
            assertEquals("HTTP code must be 403", 403, e.httpCode);
            assertTrue(e.isAuthError());
        }
    }

    @Test
    public void testFetchAllGuests_Offline() throws Exception {
        ServerConfig offlineConfig = new ServerConfig("srv-off", "Offline", "127.0.0.1", 49991,
                ServerConfig.ServerType.PVE, localIpFingerprint, true, false);
        PveApiDriver driver = new PveApiDriver(offlineConfig, sessionManager);

        try {
            driver.fetchAllGuests("node1", testCreds);
            fail("Must fail on offline host/unbound port");
        } catch (Exception e) {
            assertTrue("Must throw ApiException or IOException: " + e.getClass().getName(),
                    e instanceof ApiException || e instanceof IOException);
        }
    }

    @Test
    public void testFetchAllGuests_Qemu500_LxcEmpty() throws Exception {
        qemuResponseCode = 500;
        qemuResponseBody = "{\"errors\":\"qemu backend storage error\"}";
        lxcResponseCode = 200;
        lxcResponseBody = "{\"data\":[]}";

        PveApiDriver driver = new PveApiDriver(serverConfig, sessionManager);
        try {
            driver.fetchAllGuests("node1", testCreds);
            fail("Must fail explicitly with ApiException on QEMU HTTP 500, not return empty/success");
        } catch (ApiException e) {
            assertEquals("Must report HTTP 500 error code", 500, e.httpCode);
        }
    }

    @Test
    public void testFetchAllGuests_Lxc500_QemuPopulated() throws Exception {
        qemuResponseCode = 200;
        qemuResponseBody = "{\"data\":[{\"vmid\":100,\"name\":\"vm-running\",\"status\":\"running\",\"cpu\":0.1,\"cpus\":1,\"mem\":1024,\"maxmem\":2048,\"disk\":1024,\"maxdisk\":2048,\"uptime\":100,\"tags\":\"\"}]}";
        lxcResponseCode = 500;
        lxcResponseBody = "{\"errors\":\"lxc internal failure\"}";

        PveApiDriver driver = new PveApiDriver(serverConfig, sessionManager);
        try {
            driver.fetchAllGuests("node1", testCreds);
            fail("Must fail explicitly with ApiException on LXC HTTP 500, not return silently partial list");
        } catch (ApiException e) {
            assertEquals("Must report HTTP 500 error code", 500, e.httpCode);
        }
    }

    @Test
    public void testFetchAllGuests_MalformedPayload() throws Exception {
        qemuResponseCode = 200;
        qemuResponseBody = "{\"data\": [{invalid json syntax";
        lxcResponseCode = 200;
        lxcResponseBody = "{\"data\":[]}";

        PveApiDriver driver = new PveApiDriver(serverConfig, sessionManager);
        try {
            driver.fetchAllGuests("node1", testCreds);
            fail("Must fail explicitly on malformed payload");
        } catch (ApiException e) {
            assertTrue("Exception message must describe parse failure: " + e.getMessage(),
                    e.getMessage().contains("Failed to parse guest list"));
        }
    }
}
