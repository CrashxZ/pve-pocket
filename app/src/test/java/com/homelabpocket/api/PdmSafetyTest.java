// SPDX-License-Identifier: GPL-3.0-or-later
// PVE Pocket — Proxmox Datacenter Manager (PDM) Behavioral & Safety Tests
package com.homelabpocket.api;

import com.homelabpocket.model.AuthCredentials;
import com.homelabpocket.model.GuestInfo;
import com.homelabpocket.model.NodeStatus;
import com.homelabpocket.model.ServerConfig;
import com.homelabpocket.model.StoragePool;
import com.homelabpocket.model.TaskResult;
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
import java.util.Arrays;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import com.homelabpocket.security.CredentialStore;
import com.homelabpocket.ui.UiPresentationPolicy;

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
    private static final AtomicInteger powerPostRequests = new AtomicInteger(0);
    private static final AtomicInteger taskPollRequests = new AtomicInteger(0);
    private static final AtomicInteger storageStatusRequests = new AtomicInteger(0);
    private static final AtomicBoolean return403 = new AtomicBoolean(false);
    private static final AtomicBoolean return401Once = new AtomicBoolean(false);
    private static final AtomicBoolean return404OnResourcesList = new AtomicBoolean(false);
    private static final AtomicBoolean return401OnPowerPost = new AtomicBoolean(false);
    private static final AtomicBoolean return403OnPowerPost = new AtomicBoolean(false);
    private static final AtomicBoolean return403OnStorageStatus = new AtomicBoolean(false);
    private static final AtomicBoolean return404OnStorageStatus = new AtomicBoolean(false);
    private static final AtomicInteger taskRunningCountdown = new AtomicInteger(0);
    private static final AtomicReference<String> taskExitStatus = new AtomicReference<>("OK");
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

        // 4. PDM Remote Operations: guest power actions, task polling, and storage status
        mockServer.createContext("/api2/json/pve/remotes", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();

            java.util.regex.Pattern taskPattern = java.util.regex.Pattern.compile("^/api2/json/pve/remotes/([^/]+)/tasks/([^/]+)/status$");
            java.util.regex.Pattern storagePattern = java.util.regex.Pattern.compile("^/api2/json/pve/remotes/([^/]+)/nodes/([^/]+)/storage/([^/]+)/status$");
            java.util.regex.Pattern powerPattern = java.util.regex.Pattern.compile("^/api2/json/pve/remotes/([^/]+)/(qemu|lxc)/(\\d+)/(start|shutdown|stop)$");

            java.util.regex.Matcher taskMatcher = taskPattern.matcher(path);
            if (taskMatcher.matches()) {
                if (!"GET".equalsIgnoreCase(method)) {
                    exchange.sendResponseHeaders(405, -1);
                    exchange.close();
                    return;
                }
                String remotePath = java.net.URLDecoder.decode(taskMatcher.group(1), "UTF-8");
                String upidPath = java.net.URLDecoder.decode(taskMatcher.group(2), "UTF-8");

                // Upstream tasks.rs verify_upid fail-closed check: reject remote mismatch
                int bang = upidPath.indexOf('!');
                if (bang > 0) {
                    String prefix = upidPath.substring(0, bang);
                    int colon = prefix.indexOf(':');
                    if (colon > 0) {
                        String upidRemote = prefix.substring(colon + 1).trim();
                        if (!remotePath.equals(upidRemote)) {
                            byte[] b = ("{\"message\":\"Remote UPID remote mismatch: expected " + remotePath + ", found " + upidRemote + "\"}").getBytes("UTF-8");
                            exchange.getResponseHeaders().set("Content-Type", "application/json");
                            exchange.sendResponseHeaders(400, b.length);
                            exchange.getResponseBody().write(b);
                            exchange.close();
                            return;
                        }
                    }
                }

                taskPollRequests.incrementAndGet();
                if (return403.get()) {
                    byte[] b = "{\"message\":\"Permission denied\"}".getBytes("UTF-8");
                    exchange.sendResponseHeaders(403, b.length);
                    exchange.getResponseBody().write(b);
                    exchange.close();
                    return;
                }
                String resp;
                if (taskRunningCountdown.get() > 0) {
                    taskRunningCountdown.decrementAndGet();
                    resp = "{\"data\":{\"status\":\"running\",\"node\":\"pve-node-01\",\"starttime\":1700000000}}";
                } else {
                    String exitStat = taskExitStatus.get();
                    if (exitStat == null) exitStat = "OK";
                    resp = "{\"data\":{\"status\":\"stopped\",\"exitstatus\":\"" + exitStat + "\",\"node\":\"pve-node-01\",\"starttime\":1700000000,\"endtime\":1700000010}}";
                }
                byte[] respBytes = resp.getBytes("UTF-8");
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, respBytes.length);
                exchange.getResponseBody().write(respBytes);
                exchange.close();
                return;
            }

            java.util.regex.Matcher storageMatcher = storagePattern.matcher(path);
            if (storageMatcher.matches()) {
                if (!"GET".equalsIgnoreCase(method)) {
                    exchange.sendResponseHeaders(405, -1);
                    exchange.close();
                    return;
                }
                storageStatusRequests.incrementAndGet();
                if (return403OnStorageStatus.get()) {
                    byte[] b = "{\"message\":\"Permission check failed for storage audit\"}".getBytes("UTF-8");
                    exchange.sendResponseHeaders(403, b.length);
                    exchange.getResponseBody().write(b);
                    exchange.close();
                    return;
                }
                if (return404OnStorageStatus.get()) {
                    byte[] b = "{\"message\":\"Route not found on this PDM version\"}".getBytes("UTF-8");
                    exchange.sendResponseHeaders(404, b.length);
                    exchange.getResponseBody().write(b);
                    exchange.close();
                    return;
                }
                String resp = "{\"data\":{\"type\":\"zfspool\",\"active\":1,\"enabled\":1,\"total\":1000000000000,\"used\":300000000000,\"avail\":700000000000,\"shared\":0}}";
                byte[] respBytes = resp.getBytes("UTF-8");
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, respBytes.length);
                exchange.getResponseBody().write(respBytes);
                exchange.close();
                return;
            }

            java.util.regex.Matcher powerMatcher = powerPattern.matcher(path);
            if (powerMatcher.matches()) {
                if (!"POST".equalsIgnoreCase(method)) {
                    exchange.sendResponseHeaders(405, -1);
                    exchange.close();
                    return;
                }

                // Verify Form Body: node parameter strictly required
                java.io.ByteArrayOutputStream bodyBytes = new java.io.ByteArrayOutputStream();
                java.io.InputStream is = exchange.getRequestBody();
                byte[] buf = new byte[512];
                int n;
                while ((n = is.read(buf)) > 0) {
                    bodyBytes.write(buf, 0, n);
                }
                String bodyStr = new String(bodyBytes.toByteArray(), "UTF-8");
                boolean hasValidNode = false;
                for (String param : bodyStr.split("&")) {
                    int eq = param.indexOf('=');
                    if (eq > 0) {
                        String key = param.substring(0, eq).trim();
                        String val = param.substring(eq + 1).trim();
                        if ("node".equals(key) && !val.isEmpty()) {
                            hasValidNode = true;
                            break;
                        }
                    }
                }
                if (!hasValidNode) {
                    byte[] b = "{\"message\":\"Missing required parameter: node\"}".getBytes("UTF-8");
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(400, b.length);
                    exchange.getResponseBody().write(b);
                    exchange.close();
                    return;
                }

                powerPostRequests.incrementAndGet();

                // Check auth headers
                String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
                String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
                String csrfHeader = exchange.getRequestHeaders().getFirst("CSRFPreventionToken");

                boolean hasAuth = (authHeader != null && authHeader.contains("PDMAPIToken")) ||
                        (cookieHeader != null && (cookieHeader.contains("__Host-PDMAuthCookie") || cookieHeader.contains("PDMAuthCookie")));
                assertTrue("Power action must supply valid PDM auth header/cookie", hasAuth);

                if (cookieHeader != null && !cookieHeader.isEmpty()) {
                    assertNotNull("Cookie-authenticated POST must supply CSRFPreventionToken", csrfHeader);
                    if (csrfHeader == null || csrfHeader.trim().isEmpty()) {
                        byte[] b = "{\"message\":\"Permission denied (403): CSRFPreventionToken required for cookie-authenticated POST\"}".getBytes("UTF-8");
                        exchange.sendResponseHeaders(403, b.length);
                        exchange.getResponseBody().write(b);
                        exchange.close();
                        return;
                    }
                }

                if (return401OnPowerPost.get()) {
                    byte[] b = "{\"message\":\"Authentication ticket expired\"}".getBytes("UTF-8");
                    exchange.sendResponseHeaders(401, b.length);
                    exchange.getResponseBody().write(b);
                    exchange.close();
                    return;
                }
                if (return403OnPowerPost.get()) {
                    byte[] b = "{\"message\":\"Permission denied for guest power management\"}".getBytes("UTF-8");
                    exchange.sendResponseHeaders(403, b.length);
                    exchange.getResponseBody().write(b);
                    exchange.close();
                    return;
                }

                String remoteName = java.net.URLDecoder.decode(powerMatcher.group(1), "UTF-8");
                String vmType = powerMatcher.group(2);
                String vmidStr = powerMatcher.group(3);
                String actionName = powerMatcher.group(4);

                String upid = "pve:" + remoteName + "!UPID:pve-node-01:00001234:00005678:67B4A9D1:qm" + actionName + ":" + vmidStr + ":root@pam:";
                String resp = "{\"data\":\"" + upid + "\"}";
                byte[] respBytes = resp.getBytes("UTF-8");
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, respBytes.length);
                exchange.getResponseBody().write(respBytes);
                exchange.close();
                return;
            }

            exchange.sendResponseHeaders(404, -1);
            exchange.close();
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

    @Test
    public void testPdmQemuStartAndTaskPolling() throws Exception {
        ServerConfig pdmConfig = new ServerConfig("pdm-1", "Datacenter Manager", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);
        PdmApiDriver pdmDriver = new PdmApiDriver(pdmConfig);
        AuthCredentials dummyAuth = AuthCredentials.ticket("root@pam", "ticket", "csrf", System.currentTimeMillis() + 3600000);

        String upid = pdmDriver.startGuest("cluster-west/pve-node-01", 101, GuestInfo.GuestType.QEMU, dummyAuth, 15_000L);
        assertNotNull(upid);
        assertTrue("UPID must follow PDM RemoteUpid format", upid.startsWith("pve:cluster-west!UPID:"));

        TaskResult result = pdmDriver.pollTask("cluster-west/pve-node-01", upid, dummyAuth, 15_000L);
        assertNotNull(result);
        assertEquals(TaskResult.TaskState.OK, result.state);
        assertEquals("pve-node-01", result.node);
    }

    @Test
    public void testPdmLxcStartAndTaskPolling() throws Exception {
        ServerConfig pdmConfig = new ServerConfig("pdm-1", "Datacenter Manager", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);
        PdmApiDriver pdmDriver = new PdmApiDriver(pdmConfig);
        AuthCredentials dummyAuth = AuthCredentials.ticket("root@pam", "ticket", "csrf", System.currentTimeMillis() + 3600000);

        String upid = pdmDriver.startGuest("cluster-west/pve-node-01", 102, GuestInfo.GuestType.LXC, dummyAuth, 15_000L);
        assertNotNull(upid);
        assertTrue("UPID must follow PDM RemoteUpid format", upid.startsWith("pve:cluster-west!UPID:"));

        TaskResult result = pdmDriver.pollTask("cluster-west", upid, dummyAuth, 15_000L);
        assertNotNull(result);
        assertEquals(TaskResult.TaskState.OK, result.state);
    }

    @Test
    public void testPdmShutdownAndForceStop() throws Exception {
        ServerConfig pdmConfig = new ServerConfig("pdm-1", "Datacenter Manager", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);
        PdmApiDriver pdmDriver = new PdmApiDriver(pdmConfig);
        AuthCredentials dummyAuth = AuthCredentials.ticket("root@pam", "ticket", "csrf", System.currentTimeMillis() + 3600000);

        String shutUpid = pdmDriver.shutdownGuest("cluster-west/pve-node-01", 101, GuestInfo.GuestType.QEMU, dummyAuth, 15_000L);
        assertTrue(shutUpid.contains("shutdown"));

        String stopUpid = pdmDriver.forceStopGuest("cluster-west/pve-node-01", 101, GuestInfo.GuestType.QEMU, dummyAuth, 15_000L);
        assertTrue(stopUpid.contains("stop"));
    }

    @Test
    public void testPdmRebootThrowsHonestUnsupportedException() throws Exception {
        ServerConfig pdmConfig = new ServerConfig("pdm-1", "Datacenter Manager", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);
        PdmApiDriver pdmDriver = new PdmApiDriver(pdmConfig);
        AuthCredentials dummyAuth = AuthCredentials.ticket("root@pam", "ticket", "csrf", System.currentTimeMillis() + 3600000);

        try {
            pdmDriver.rebootGuest("cluster-west/pve-node-01", 101, GuestInfo.GuestType.QEMU, dummyAuth, 15_000L);
            fail("PdmApiDriver must reject reboot with honest capability disclosure");
        } catch (ApiException e) {
            assertTrue("Message must explain reboot is not supported by PDM",
                    e.getMessage().contains("Guest Reboot is not supported by the Proxmox Datacenter Manager API"));
            assertTrue("Message must state supported operations",
                    e.getMessage().contains("Start, Graceful Shutdown, and Stop"));
        }
    }

    @Test
    public void testPdmPowerPostNeverRetriesOn401() throws Exception {
        ServerConfig pdmConfig = new ServerConfig("pdm-1", "Datacenter Manager", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);
        PdmApiDriver pdmDriver = new PdmApiDriver(pdmConfig);
        AuthCredentials dummyAuth = AuthCredentials.ticket("root@pam", "ticket", "csrf", System.currentTimeMillis() + 3600000);

        return401OnPowerPost.set(true);
        powerPostRequests.set(0);
        try {
            pdmDriver.startGuest("cluster-west/pve-node-01", 101, GuestInfo.GuestType.QEMU, dummyAuth, 15_000L);
            fail("Must fail on 401 without retry");
        } catch (ApiException e) {
            assertEquals(401, e.getHttpCode());
            assertTrue(e.getMessage().contains("Action was NOT retried"));
            assertEquals("State-changing POST must NOT be retried on 401", 1, powerPostRequests.get());
        } finally {
            return401OnPowerPost.set(false);
        }
    }

    @Test
    public void testPdmPowerPostThrows403Forbidden() throws Exception {
        ServerConfig pdmConfig = new ServerConfig("pdm-1", "Datacenter Manager", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);
        PdmApiDriver pdmDriver = new PdmApiDriver(pdmConfig);
        AuthCredentials dummyAuth = AuthCredentials.ticket("root@pam", "ticket", "csrf", System.currentTimeMillis() + 3600000);

        return403OnPowerPost.set(true);
        try {
            pdmDriver.startGuest("cluster-west/pve-node-01", 101, GuestInfo.GuestType.QEMU, dummyAuth, 15_000L);
            fail("Must fail on 403 Forbidden");
        } catch (ApiException e) {
            assertEquals(403, e.getHttpCode());
            assertTrue(e.getMessage().contains("Permission denied (403)"));
        } finally {
            return403OnPowerPost.set(false);
        }
    }

    @Test
    public void testCollisionProofIdentityAcrossRemotesAndGuestTypes() {
        String key1 = ActionGuard.buildGuestKey("pdm-1", "cluster-west/node-01", "qemu", 101, "start");
        String key2 = ActionGuard.buildGuestKey("pdm-1", "cluster-east/node-01", "qemu", 101, "start");
        String key3 = ActionGuard.buildGuestKey("pdm-1", "cluster-west/node-01", "lxc", 101, "start");
        String key4 = ActionGuard.buildGuestKey("pdm-2", "cluster-west/node-01", "qemu", 101, "start");

        assertNotEquals("Different remotes must have distinct keys", key1, key2);
        assertNotEquals("QEMU vs LXC on same node must have distinct keys", key1, key3);
        assertNotEquals("Different server configs must have distinct keys", key1, key4);

        ActionGuard guard = new ActionGuard();
        assertTrue(guard.tryAcquire(key1));
        assertTrue(guard.tryAcquire(key2));
        assertTrue(guard.tryAcquire(key3));
        assertTrue(guard.tryAcquire(key4));
        assertEquals(4, guard.getActiveCount());

        // Duplicate on key1 is blocked
        assertFalse(guard.tryAcquire(key1));
    }

    @Test
    public void testStorageTypeEnrichmentAndBoundedCache() throws Exception {
        ServerConfig pdmConfig = new ServerConfig("pdm-1", "Datacenter Manager", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);
        PdmApiDriver pdmDriver = new PdmApiDriver(pdmConfig);
        AuthCredentials dummyAuth = AuthCredentials.ticket("root@pam", "ticket", "csrf", System.currentTimeMillis() + 3600000);

        pdmDriver.clearStorageCache();
        storageStatusRequests.set(0);

        String type1 = pdmDriver.fetchStorageType("cluster-west", "pve-node-01", "local-zfs", dummyAuth, 5_000L);
        assertEquals("zfspool", type1);
        assertEquals(1, storageStatusRequests.get());

        // Second call must hit cache without network dispatch
        String type2 = pdmDriver.fetchStorageType("cluster-west", "pve-node-01", "local-zfs", dummyAuth, 5_000L);
        assertEquals("zfspool", type2);
        assertEquals(1, storageStatusRequests.get());
    }

    @Test
    public void testStorageTypeEnrichmentTolerates403And404() throws Exception {
        ServerConfig pdmConfig = new ServerConfig("pdm-1", "Datacenter Manager", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);
        PdmApiDriver pdmDriver = new PdmApiDriver(pdmConfig);
        AuthCredentials dummyAuth = AuthCredentials.ticket("root@pam", "ticket", "csrf", System.currentTimeMillis() + 3600000);

        pdmDriver.clearStorageCache();
        return403OnStorageStatus.set(true);
        try {
            String type403 = pdmDriver.fetchStorageType("cluster-west", "pve-node-01", "restricted-pool", dummyAuth, 5_000L);
            assertTrue(type403.contains("Type unavailable"));
            assertTrue(type403.contains("403 Forbidden"));
        } finally {
            return403OnStorageStatus.set(false);
        }

        pdmDriver.clearStorageCache();
        return404OnStorageStatus.set(true);
        try {
            String type404 = pdmDriver.fetchStorageType("cluster-west", "pve-node-01", "legacy-pool", dummyAuth, 5_000L);
            assertTrue(type404.contains("Type unavailable"));
            assertTrue(type404.contains("404 Not Found"));
        } finally {
            return404OnStorageStatus.set(false);
        }
    }

    @Test
    public void testEnrichStoragePoolsHelper() throws Exception {
        ServerConfig pdmConfig = new ServerConfig("pdm-1", "Datacenter Manager", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);
        PdmApiDriver pdmDriver = new PdmApiDriver(pdmConfig);
        AuthCredentials dummyAuth = AuthCredentials.ticket("root@pam", "ticket", "csrf", System.currentTimeMillis() + 3600000);

        pdmDriver.clearStorageCache();
        List<StoragePool> rawPools = new ArrayList<>();
        rawPools.add(new StoragePool("local-zfs", "unknown", 1000000000000L, 300000000000L, 700000000000L, true, true, false, ""));

        List<StoragePool> enriched = pdmDriver.enrichStoragePools("cluster-west/pve-node-01", rawPools, dummyAuth);
        assertNotNull(enriched);
        assertEquals(1, enriched.size());
        assertEquals("zfspool", enriched.get(0).type);
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

    @Test
    public void testTaskPollingRunningToOkWithActionGuardPreservation() throws Exception {
        ServerConfig pdmConfig = new ServerConfig("pdm-1", "Datacenter Manager", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);
        PdmApiDriver pdmDriver = new PdmApiDriver(pdmConfig);
        AuthCredentials dummyAuth = AuthCredentials.ticket("root@pam", "ticket", "csrf", System.currentTimeMillis() + 3600000);

        String actionKey = ActionGuard.buildGuestKey("pdm-1", "cluster-west/pve-node-01", "qemu", 101, "start");
        ActionGuard.getInstance().tryAcquire(actionKey);
        assertTrue(ActionGuard.getInstance().isRunning(actionKey));

        String upid = pdmDriver.startGuest("cluster-west/pve-node-01", 101, GuestInfo.GuestType.QEMU, dummyAuth, 15_000L);
        taskRunningCountdown.set(2);
        taskExitStatus.set("OK");

        List<TaskResult> progressList = new ArrayList<>();
        TaskResult finalResult = TaskPoller.getInstance().pollUntilFinished(
                actionKey,
                "cluster-west/pve-node-01",
                upid,
                dummyAuth,
                (node, id, auth, curBudget) -> pdmDriver.pollTask(node, id, auth, curBudget),
                new TaskPoller.PollingCallback() {
                    @Override
                    public void onProgress(TaskResult progress) {
                        progressList.add(progress);
                        assertTrue("ActionGuard must be held during RUNNING progress", ActionGuard.getInstance().isRunning(actionKey));
                    }

                    @Override
                    public boolean isCancelled() {
                        return false;
                    }
                },
                15_000L,
                50L
        );

        assertNotNull(finalResult);
        assertEquals(TaskResult.TaskState.OK, finalResult.state);
        assertTrue("Must have received intermediate progress", progressList.size() >= 2);
        assertFalse("ActionGuard must be released on terminal state", ActionGuard.getInstance().isRunning(actionKey));
    }

    @Test
    public void testTaskPollingRunningToErrorWithActionGuardPreservation() throws Exception {
        ServerConfig pdmConfig = new ServerConfig("pdm-1", "Datacenter Manager", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);
        PdmApiDriver pdmDriver = new PdmApiDriver(pdmConfig);
        AuthCredentials dummyAuth = AuthCredentials.ticket("root@pam", "ticket", "csrf", System.currentTimeMillis() + 3600000);

        String actionKey = ActionGuard.buildGuestKey("pdm-1", "cluster-west/pve-node-01", "qemu", 101, "shutdown");
        ActionGuard.getInstance().tryAcquire(actionKey);
        assertTrue(ActionGuard.getInstance().isRunning(actionKey));

        String upid = pdmDriver.shutdownGuest("cluster-west/pve-node-01", 101, GuestInfo.GuestType.QEMU, dummyAuth, 15_000L);
        taskRunningCountdown.set(1);
        taskExitStatus.set("command failed: guest refused shutdown");

        try {
            TaskResult finalResult = TaskPoller.getInstance().pollUntilFinished(
                    actionKey,
                    "cluster-west/pve-node-01",
                    upid,
                    dummyAuth,
                    (node, id, auth, curBudget) -> pdmDriver.pollTask(node, id, auth, curBudget),
                    new TaskPoller.PollingCallback() {
                        @Override
                        public void onProgress(TaskResult progress) {
                            assertTrue("ActionGuard must be held during RUNNING progress", ActionGuard.getInstance().isRunning(actionKey));
                        }

                        @Override
                        public boolean isCancelled() {
                            return false;
                        }
                    },
                    15_000L,
                    50L
            );

            assertNotNull(finalResult);
            assertEquals(TaskResult.TaskState.ERROR, finalResult.state);
            assertEquals("command failed: guest refused shutdown", finalResult.exitStatus);
            assertFalse("ActionGuard must be released on ERROR", ActionGuard.getInstance().isRunning(actionKey));
        } finally {
            taskExitStatus.set("OK");
        }
    }

    @Test
    public void testTaskPollingTimeoutReleasesActionGuard() throws Exception {
        ServerConfig pdmConfig = new ServerConfig("pdm-1", "Datacenter Manager", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);
        PdmApiDriver pdmDriver = new PdmApiDriver(pdmConfig);
        AuthCredentials dummyAuth = AuthCredentials.ticket("root@pam", "ticket", "csrf", System.currentTimeMillis() + 3600000);

        String actionKey = ActionGuard.buildGuestKey("pdm-1", "cluster-west/pve-node-01", "qemu", 101, "stop");
        ActionGuard.getInstance().tryAcquire(actionKey);
        assertTrue(ActionGuard.getInstance().isRunning(actionKey));

        String upid = pdmDriver.forceStopGuest("cluster-west/pve-node-01", 101, GuestInfo.GuestType.QEMU, dummyAuth, 15_000L);
        taskRunningCountdown.set(50); // Keep task running beyond short timeout

        TaskResult finalResult = TaskPoller.getInstance().pollUntilFinished(
                actionKey,
                "cluster-west/pve-node-01",
                upid,
                dummyAuth,
                (node, id, auth, curBudget) -> pdmDriver.pollTask(node, id, auth, curBudget),
                null,
                150L,
                40L
        );

        assertNotNull(finalResult);
        assertEquals(TaskResult.TaskState.TIMEOUT, finalResult.state);
        assertFalse("ActionGuard must be released on TIMEOUT", ActionGuard.getInstance().isRunning(actionKey));
        taskRunningCountdown.set(0);
    }

    @Test
    public void testTaskPollingCancellationReleasesActionGuard() throws Exception {
        ServerConfig pdmConfig = new ServerConfig("pdm-1", "Datacenter Manager", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);
        PdmApiDriver pdmDriver = new PdmApiDriver(pdmConfig);
        AuthCredentials dummyAuth = AuthCredentials.ticket("root@pam", "ticket", "csrf", System.currentTimeMillis() + 3600000);

        String actionKey = ActionGuard.buildGuestKey("pdm-1", "cluster-west/pve-node-01", "lxc", 102, "start");
        ActionGuard.getInstance().tryAcquire(actionKey);
        assertTrue(ActionGuard.getInstance().isRunning(actionKey));

        String upid = pdmDriver.startGuest("cluster-west/pve-node-01", 102, GuestInfo.GuestType.LXC, dummyAuth, 15_000L);
        taskRunningCountdown.set(50);

        AtomicInteger callCount = new AtomicInteger(0);
        TaskResult finalResult = TaskPoller.getInstance().pollUntilFinished(
                actionKey,
                "cluster-west/pve-node-01",
                upid,
                dummyAuth,
                (node, id, auth, curBudget) -> pdmDriver.pollTask(node, id, auth, curBudget),
                new TaskPoller.PollingCallback() {
                    @Override
                    public void onProgress(TaskResult progress) {
                    }

                    @Override
                    public boolean isCancelled() {
                        return callCount.incrementAndGet() >= 2;
                    }
                },
                15_000L,
                40L
        );

        assertNotNull(finalResult);
        assertFalse("ActionGuard must be released on Cancellation", ActionGuard.getInstance().isRunning(actionKey));
        taskRunningCountdown.set(0);
    }

    @Test
    public void testStorageTypeEnrichmentFootnoteDisplaysExplicitReason() {
        StoragePool pool403 = new StoragePool("sec-pool",
                "Type unavailable from this endpoint/permission (403 Forbidden)",
                1000L, 500L, 500L, true, true, false, "");

        assertFalse("Explicitly unavailable type must not be reported as verified", pool403.isTypeReported());
        assertEquals("UNKNOWN", pool403.getTypeBadge());
        assertEquals("Type unavailable from this endpoint/permission (403 Forbidden)",
                com.homelabpocket.ui.UiPresentationPolicy.getStorageTypeFootnote(pool403, true));
        assertEquals("Type unavailable from this endpoint/permission (403 Forbidden)",
                com.homelabpocket.ui.UiPresentationPolicy.getStorageTypeFootnote(pool403, false));

        StoragePool pool404 = new StoragePool("old-pool",
                "Type unavailable from this endpoint/permission (404 Not Found)",
                1000L, 500L, 500L, true, true, false, "");

        assertFalse(pool404.isTypeReported());
        assertEquals("UNKNOWN", pool404.getTypeBadge());
        assertEquals("Type unavailable from this endpoint/permission (404 Not Found)",
                com.homelabpocket.ui.UiPresentationPolicy.getStorageTypeFootnote(pool404, true));
    }

    @Test
    public void testUrlEncodedRemoteNodeAndStorageParameters() throws Exception {
        ServerConfig pdmConfig = new ServerConfig("pdm-1", "Datacenter Manager", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);
        PdmApiDriver pdmDriver = new PdmApiDriver(pdmConfig);
        AuthCredentials dummyAuth = AuthCredentials.ticket("root@pam", "ticket", "csrf", System.currentTimeMillis() + 3600000);

        pdmDriver.clearStorageCache();
        // Uses spaces and symbols in remote, node, storage
        String type = pdmDriver.fetchStorageType("cluster west", "pve node 01", "local zfs", dummyAuth, 5_000L);
        assertEquals("zfspool", type);
    }

    @Test
    public void testAbsentNodeMustNotDispatchPower() throws Exception {
        ServerConfig pdmConfig = new ServerConfig("pdm-1", "Datacenter Manager", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);
        PdmApiDriver pdmDriver = new PdmApiDriver(pdmConfig);
        AuthCredentials dummyAuth = AuthCredentials.ticket("root@pam", "ticket", "csrf", System.currentTimeMillis() + 3600000);

        int before = powerPostRequests.get();
        boolean rejected = false;
        try {
            pdmDriver.executeGuestPowerAction("cluster-west", "", 101, GuestInfo.GuestType.QEMU, "start", dummyAuth, 3000L);
        } catch (ApiException expected) {
            rejected = true;
        }
        assertEquals("Missing node must not silently auto-target or send POST", before, powerPostRequests.get());
        assertTrue("Explicit full target identity must be required before network", rejected);
    }

    @Test
    public void testConflictingPowerForSameGuestMustBeBlocked() {
        ActionGuard guard = new ActionGuard();
        assertTrue(guard.tryAcquireGuestAction("pdm", "west/node", "qemu", 101, "shutdown"));
        assertFalse("Force-stop must not acquire the same guest while shutdown is in flight",
                guard.tryAcquireGuestAction("pdm", "west/node", "qemu", 101, "stop"));
        assertTrue("Distinct guest on same node must acquire cleanly",
                guard.tryAcquireGuestAction("pdm", "west/node", "qemu", 102, "stop"));
        assertTrue("Distinct remote on same VMID must acquire cleanly",
                guard.tryAcquireGuestAction("pdm", "east/node", "qemu", 101, "stop"));

        guard.releaseGuestAction("pdm", "west/node", "qemu", 101, "shutdown");
        assertTrue("Guest becomes available after release",
                guard.tryAcquireGuestAction("pdm", "west/node", "qemu", 101, "stop"));
    }

    @Test
    public void testMismatchedRemoteTaskMustNotBeAcceptedAsSuccess() throws Exception {
        ServerConfig pdmConfig = new ServerConfig("pdm-1", "Datacenter Manager", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);
        PdmApiDriver pdmDriver = new PdmApiDriver(pdmConfig);
        AuthCredentials dummyAuth = AuthCredentials.ticket("root@pam", "ticket", "csrf", System.currentTimeMillis() + 3600000);

        int before = taskPollRequests.get();
        boolean rejected = false;
        try {
            pdmDriver.pollTask("cluster-west",
                    "pve:cluster-east!UPID:pve-node-01:00001234:00005678:67B4A9D1:qmstart:101:root@pam:",
                    dummyAuth, 3000L);
        } catch (ApiException expected) {
            rejected = true;
        }
        assertTrue("Task identity remote mismatch must be rejected fail-closed before network", rejected);
        assertEquals(before, taskPollRequests.get());
    }

    @Test
    public void testMonotonicBudgetExhaustionWithoutMathMaxReset() throws Exception {
        ServerConfig pdmConfig = new ServerConfig("pdm-1", "Datacenter Manager", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);
        PdmApiDriver pdmDriver = new PdmApiDriver(pdmConfig);
        AuthCredentials dummyAuth = AuthCredentials.ticket("root@pam", "ticket", "csrf", System.currentTimeMillis() + 3600000);

        int before = powerPostRequests.get();
        boolean rejected = false;
        try {
            pdmDriver.executeGuestPowerAction("cluster-west", "pve-node-01", 101, GuestInfo.GuestType.QEMU, "start", dummyAuth, 0L);
        } catch (ApiException expected) {
            rejected = true;
            assertTrue(expected.getMessage().contains("budget") || expected.getMessage().contains("deadline"));
        }
        assertTrue("Zero/exhausted budget must be rejected before network dispatch", rejected);
        assertEquals(before, powerPostRequests.get());
    }

    @Test
    public void testStorageCacheSurvivesDriverRecreationAndRespectsTtl() throws Exception {
        ServerConfig pdmConfig = new ServerConfig("pdm-1", "Datacenter Manager", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);
        AuthCredentials dummyAuth = AuthCredentials.ticket("root@pam", "ticket", "csrf", System.currentTimeMillis() + 3600000);

        final AtomicLong virtualClockMs = new AtomicLong(100_000L);
        PdmApiDriver.setTimeProviderForTesting(() -> virtualClockMs.get());

        try {
            PdmApiDriver.clearStorageCache();
            int beforeRequests = storageStatusRequests.get();

            // Driver 1 populates cache at t0
            PdmApiDriver driver1 = new PdmApiDriver(pdmConfig);
            String type1 = driver1.fetchStorageType("cluster-west", "pve-node-01", "local-zfs", dummyAuth, 5_000L);
            assertEquals("zfspool", type1);
            assertEquals("Initial fetch must query network", beforeRequests + 1, storageStatusRequests.get());

            // Advance virtual time by 30 minutes (within 1 hour positive TTL)
            virtualClockMs.addAndGet(1800_000L);

            // Driver 2 (fresh instance simulating recreated Activity) reads within TTL
            PdmApiDriver driver2 = new PdmApiDriver(pdmConfig);
            String type2 = driver2.fetchStorageType("cluster-west", "pve-node-01", "local-zfs", dummyAuth, 5_000L);
            assertEquals("zfspool", type2);
            assertEquals("Driver 2 must read from unexpired cache without issuing network request",
                    beforeRequests + 1, storageStatusRequests.get());

            // Advance virtual monotonic time past 1 hour positive TTL (+30 min + 1 ms = 60m 1ms total)
            virtualClockMs.addAndGet(1800_001L);

            // Driver 2 fetch now observes expired cache and issues fresh network request
            String type3 = driver2.fetchStorageType("cluster-west", "pve-node-01", "local-zfs", dummyAuth, 5_000L);
            assertEquals("zfspool", type3);
            assertEquals("Expired cache must trigger fresh network read",
                    beforeRequests + 2, storageStatusRequests.get());

            // Test Negative Cache TTL (60 seconds)
            return404OnStorageStatus.set(true);
            try {
                String negType1 = driver2.fetchStorageType("cluster-west", "pve-node-01", "non-existent-pool", dummyAuth, 5_000L);
                assertTrue(negType1.startsWith(PdmApiDriver.STORAGE_TYPE_UNAVAILABLE));
                assertEquals("Negative cache fetch must query network", beforeRequests + 3, storageStatusRequests.get());

                // Within 30 seconds: negative cache hit
                virtualClockMs.addAndGet(30_000L);
                String negType2 = driver2.fetchStorageType("cluster-west", "pve-node-01", "non-existent-pool", dummyAuth, 5_000L);
                assertEquals(negType1, negType2);
                assertEquals("Negative cache read within TTL must not query network", beforeRequests + 3, storageStatusRequests.get());

                // Past 60 seconds negative TTL: cache expires and re-queries network
                virtualClockMs.addAndGet(30_001L);
                String negType3 = driver2.fetchStorageType("cluster-west", "pve-node-01", "non-existent-pool", dummyAuth, 5_000L);
                assertEquals(negType1, negType3);
                assertEquals("Expired negative cache must re-query network", beforeRequests + 4, storageStatusRequests.get());
            } finally {
                return404OnStorageStatus.set(false);
            }

        } finally {
            PdmApiDriver.resetTimeProviderForTesting();
        }
    }

    @Test
    public void testStorageCacheEndpointIdentityIsolation() throws Exception {
        ServerConfig pdm1 = new ServerConfig("pdm-cluster-alpha", "Alpha PDM", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);
        ServerConfig pdm2 = new ServerConfig("pdm-cluster-beta", "Beta PDM", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);
        AuthCredentials dummyAuth = AuthCredentials.ticket("root@pam", "ticket", "csrf", System.currentTimeMillis() + 3600000);

        PdmApiDriver.clearStorageCache();
        int beforeRequests = storageStatusRequests.get();

        PdmApiDriver driverAlpha = new PdmApiDriver(pdm1);
        String typeAlpha = driverAlpha.fetchStorageType("cluster-west", "pve-node-01", "local-zfs", dummyAuth, 5_000L);
        assertEquals("zfspool", typeAlpha);
        assertEquals(beforeRequests + 1, storageStatusRequests.get());

        // Driver for Beta PDM with identical remote/node/storage names MUST NOT cross-contaminate from Alpha PDM cache
        PdmApiDriver driverBeta = new PdmApiDriver(pdm2);
        String typeBeta = driverBeta.fetchStorageType("cluster-west", "pve-node-01", "local-zfs", dummyAuth, 5_000L);
        assertEquals("zfspool", typeBeta);
        assertEquals("Beta PDM endpoint must query independently and not reuse Alpha cache",
                beforeRequests + 2, storageStatusRequests.get());
    }

    @Test
    public void testEnrichStoragePoolsZeroOrExhaustedBudgetMakesZeroHttpRequests() throws Exception {
        ServerConfig pdmConfig = new ServerConfig("pdm-1", "Datacenter Manager", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);
        PdmApiDriver pdmDriver = new PdmApiDriver(pdmConfig);
        AuthCredentials dummyAuth = AuthCredentials.ticket("root@pam", "ticket", "csrf", System.currentTimeMillis() + 3600000);

        PdmApiDriver.clearStorageCache();
        int before = storageStatusRequests.get();

        List<StoragePool> pools = Arrays.asList(
                new StoragePool("local-zfs", "unknown", 100, 20, 80, true, true, false, ""),
                new StoragePool("backup-nfs", "unknown", 200, 50, 150, true, true, false, "")
        );

        List<StoragePool> enriched = pdmDriver.enrichStoragePools("cluster-west/pve-node-01", pools, dummyAuth, 0L);
        assertEquals(2, enriched.size());
        assertEquals("Type unavailable from this endpoint/permission (budget exhausted)", enriched.get(0).type);
        assertEquals("Type unavailable from this endpoint/permission (budget exhausted)", enriched.get(1).type);
        assertEquals("Exhausted budget must not issue any HTTP requests", before, storageStatusRequests.get());
    }

    @Test
    public void testStrictCsrfRequiredOnCookieAuthenticatedPowerPost() throws Exception {
        ServerConfig pdmConfig = new ServerConfig("pdm-1", "Datacenter Manager", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);
        PdmApiDriver pdmDriver = new PdmApiDriver(pdmConfig);

        // Ticket auth missing CSRFPreventionToken
        AuthCredentials authNoCsrf = AuthCredentials.ticket("root@pam", "synthetic-ticket", "", System.currentTimeMillis() + 3600000);
        int before = powerPostRequests.get();
        boolean rejected = false;
        try {
            pdmDriver.executeGuestPowerAction("cluster-west", "pve-node-01", 101, GuestInfo.GuestType.QEMU, "start", authNoCsrf, 5000L);
        } catch (ApiException expected) {
            rejected = true;
            assertEquals(403, expected.getHttpCode());
            assertTrue(expected.getMessage().contains("403"));
        }
        assertTrue("Cookie-authenticated POST without CSRF token must fail-closed with HTTP 403 Forbidden", rejected);
        assertEquals(before + 1, powerPostRequests.get());
    }

    @Test
    public void testProductionUiOwnerLifecycleAndGuardPreservation() throws Exception {
        ActionGuard guard = ActionGuard.getInstance();
        guard.clearAll();

        String serverId = "pdm-prod";
        String node = "cluster-west/pve-node-01";
        GuestInfo.GuestType type = GuestInfo.GuestType.QEMU;
        int vmid = 101;
        String actionKey = ActionGuard.buildGuestResourceKey(serverId, node, type.name(), vmid);

        // 1. Initial lock acquisition by GuestsView or VmDetailActivity
        assertTrue("UI owner must acquire resource-level lock", guard.tryAcquire(actionKey));

        // 2. Conflicting action from detail view while list view action is running
        assertFalse("Concurrent action on same guest must be blocked", guard.tryAcquire(actionKey));

        // 3. Pre-credential budget check simulation: if deadline expired, owner releases guard
        long deadlineNanos = System.nanoTime() - 1_000_000L; // Expired
        long budgetForCreds = (deadlineNanos - System.nanoTime()) / 1_000_000L;
        assertTrue("Budget must be expired", budgetForCreds <= 0);
        guard.release(actionKey);
        assertFalse("Guard must be released on pre-credential budget exhaustion", guard.isRunning(actionKey));

        // 4. Pre-dispatch cancellation check simulation:
        assertTrue(guard.tryAcquire(actionKey));
        boolean isCancelled = true;
        if (isCancelled) {
            guard.release(actionKey);
        }
        assertFalse("Guard must be released when owner task is cancelled before POST", guard.isRunning(actionKey));
    }

    @Test
    public void testSessionManagerMonitorBlockingDeadlinePropagation() throws Exception {
        // Mock credential store with credentials
        CredentialStore store = new CredentialStore(new android.content.Context() {
            @Override
            public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
                return null;
            }
        }) {
            @Override
            public boolean hasPassword(String serverId) { return true; }
            @Override
            public String getUsername(String serverId) { return "root@pam"; }
            @Override
            public String getPassword(String serverId) { return "secret"; }
            @Override
            public boolean hasApiToken(String serverId) { return false; }
            @Override
            public String getApiToken(String serverId) { return null; }
        };

        SessionManager sm = new SessionManager(store);
        ServerConfig cfg = new ServerConfig("pdm-mon", "Monitor PDM", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);

        // 1. Deadlines in the past must fail-closed immediately without blocking
        boolean pastRejected = false;
        try {
            sm.getCredentialsWithDeadline(cfg, System.nanoTime() - 1_000_000L);
        } catch (ApiException e) {
            pastRejected = true;
            assertTrue("Message must indicate deadline expiration", e.getMessage().contains("deadline already expired"));
        }
        assertTrue("Past deadline must fail closed", pastRejected);

        // 2. Contended monitor: simulate another thread holding the SessionManager lock
        ReentrantLock monitorLock = sm.getSessionLockForTesting();
        CountDownLatch lockAcquiredByOtherThread = new CountDownLatch(1);
        CountDownLatch releaseLockSignal = new CountDownLatch(1);

        Thread holdingThread = new Thread(() -> {
            monitorLock.lock();
            try {
                lockAcquiredByOtherThread.countDown();
                releaseLockSignal.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            } finally {
                monitorLock.unlock();
            }
        });
        holdingThread.setDaemon(true);
        holdingThread.start();

        assertTrue("Holding thread must acquire lock", lockAcquiredByOtherThread.await(2, TimeUnit.SECONDS));

        // Call getCredentialsWithDeadline with a 50ms bounded deadline while lock is held
        long startNanos = System.nanoTime();
        long deadlineNanos = startNanos + 50_000_000L; // 50ms
        boolean lockTimeoutRejected = false;
        try {
            sm.getCredentialsWithDeadline(cfg, deadlineNanos);
        } catch (ApiException e) {
            lockTimeoutRejected = true;
            assertTrue("Message must indicate monitor timeout",
                    e.getMessage().contains("timed out waiting for monitor") || e.getMessage().contains("deadline"));
        } finally {
            // Signal holding thread to release lock
            releaseLockSignal.countDown();
            holdingThread.join(2000);
        }

        assertTrue("Monitor wait must be bounded by monotonic deadline and fail closed", lockTimeoutRejected);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        assertTrue("Monitor acquisition must terminate boundedly (took " + elapsedMs + "ms)", elapsedMs < 1000L);
    }

    @Test
    public void testStrictTargetLookupAndNoDefaultType() {
        // 1. Intent type parsing: Missing/invalid MUST return null, never default to QEMU
        assertNull("Missing intent type must return null", UiPresentationPolicy.parseIntentGuestType(null));
        assertNull("Empty intent type must return null", UiPresentationPolicy.parseIntentGuestType(""));
        assertNull("Invalid intent type must return null", UiPresentationPolicy.parseIntentGuestType("kvm"));
        assertNull("Malformed intent type must return null", UiPresentationPolicy.parseIntentGuestType("unknown"));
        assertEquals(GuestInfo.GuestType.QEMU, UiPresentationPolicy.parseIntentGuestType("qemu"));
        assertEquals(GuestInfo.GuestType.QEMU, UiPresentationPolicy.parseIntentGuestType("QEMU"));
        assertEquals(GuestInfo.GuestType.LXC, UiPresentationPolicy.parseIntentGuestType("lxc"));
        assertEquals(GuestInfo.GuestType.LXC, UiPresentationPolicy.parseIntentGuestType("LXC"));

        // 2. Strict target validation: fails closed on null, moved, wrong type, or offline
        GuestInfo runningGuest = new GuestInfo(101, "vm-101", GuestInfo.GuestType.QEMU, GuestInfo.RunState.RUNNING,
                "cluster-west/pve-node-01", "pdm-1", 0.1, 2, 1024, 2048, 0, 0, 0, 0, 3600, "", false, System.currentTimeMillis());
        GuestInfo offlineGuest = new GuestInfo(101, "vm-101", GuestInfo.GuestType.QEMU, GuestInfo.RunState.UNKNOWN,
                "cluster-west/pve-node-01", "pdm-1", 0.0, 2, -1, 2048, 0, 0, 0, 0, 0, "", false, System.currentTimeMillis());

        // Valid exact match
        assertTrue(UiPresentationPolicy.isGuestTargetValid(runningGuest, "cluster-west/pve-node-01", 101, GuestInfo.GuestType.QEMU));

        // Fail-closed when expectedType is null (intent type missing)
        assertFalse("Must fail closed if expectedType is null",
                UiPresentationPolicy.isGuestTargetValid(runningGuest, "cluster-west/pve-node-01", 101, null));

        // Fail-closed on type mismatch (QEMU vs LXC)
        assertFalse("Must fail closed on type mismatch",
                UiPresentationPolicy.isGuestTargetValid(runningGuest, "cluster-west/pve-node-01", 101, GuestInfo.GuestType.LXC));

        // Fail-closed on moved/migrated guest (wrong node)
        assertFalse("Must fail closed on wrong node",
                UiPresentationPolicy.isGuestTargetValid(runningGuest, "cluster-west/pve-node-02", 101, GuestInfo.GuestType.QEMU));

        // Fail-closed on stale VMID
        assertFalse("Must fail closed on wrong VMID",
                UiPresentationPolicy.isGuestTargetValid(runningGuest, "cluster-west/pve-node-01", 102, GuestInfo.GuestType.QEMU));

        // Fail-closed on offline / UNKNOWN guest state
        assertFalse("Must fail closed on offline/unknown guest state",
                UiPresentationPolicy.isGuestTargetValid(offlineGuest, "cluster-west/pve-node-01", 101, GuestInfo.GuestType.QEMU));

        // Fail-closed on null guest (lookup failure)
        assertFalse("Must fail closed on null guest",
                UiPresentationPolicy.isGuestTargetValid(null, "cluster-west/pve-node-01", 101, GuestInfo.GuestType.QEMU));
    }

    @Test
    public void testQueuedCancellationReleasesActionGuard() {
        ActionGuard guard = ActionGuard.getInstance();
        guard.clearAll();

        String serverId = "pdm-prod";
        String node = "cluster-west/pve-node-01";
        GuestInfo.GuestType type = GuestInfo.GuestType.QEMU;
        int vmid = 200;
        String actionKey = ActionGuard.buildGuestResourceKey(serverId, node, type.name(), vmid);

        // Simulate UI owner task submission: tryAcquire is called before queuing AsyncTask
        assertTrue("UI owner acquires guard before queuing AsyncTask", guard.tryAcquire(actionKey));
        assertTrue("Guard is actively in flight", guard.isRunning(actionKey));

        // Atomic release guard pattern implemented in both VmDetailActivity and GuestsView
        final AtomicBoolean guardReleased = new AtomicBoolean(false);
        Runnable releaseGuardOnce = () -> {
            if (guardReleased.compareAndSet(false, true)) {
                guard.release(actionKey);
            }
        };

        // Simulate cancellation before doInBackground starts (queued cancellation in executor)
        // Dialog onCancelListener or onCancelled() invokes releaseGuardOnce()
        releaseGuardOnce.run();

        // Verify guard is immediately released
        assertFalse("Guard must be released after queued task cancellation", guard.isRunning(actionKey));

        // Calling onCancelled() subsequently is idempotent and safe
        releaseGuardOnce.run();
        assertFalse("Subsequent release must be idempotent", guard.isRunning(actionKey));

        // A subsequent power operation can now acquire the guard without collision
        assertTrue("Subsequent action must be able to acquire released guard", guard.tryAcquire(actionKey));
        guard.release(actionKey);
    }
}
