// SPDX-License-Identifier: GPL-3.0-or-later
// Homelab Pocket — Auth Refresh, PVE Credentials & Per-Server Skip TLS Regression Tests
package com.homelabpocket.security;

import android.content.Context;
import android.content.SharedPreferences;
import com.homelabpocket.api.ApiClient;
import com.homelabpocket.api.ApiException;
import com.homelabpocket.api.PveApiDriver;
import com.homelabpocket.api.ServerConfigStore;
import com.homelabpocket.api.SessionManager;
import com.homelabpocket.model.AuthCredentials;
import com.homelabpocket.model.NodeStatus;
import com.homelabpocket.model.ServerConfig;
import com.homelabpocket.model.TaskResult;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

import static org.junit.Assert.*;

public class AuthRefreshAndTlsOptionTest {

    private static HttpsServer httpsServer;
    private static int serverPort;
    private static String localIpFingerprint;
    private static KeyStore fixtureKeystore;

    private static final AtomicInteger ticketCallCount = new AtomicInteger(0);
    private static final AtomicInteger nodesCallCount = new AtomicInteger(0);
    private static final AtomicInteger taskCallCount = new AtomicInteger(0);
    private static volatile boolean simulateExpiredTicketOnServer = false;
    private static volatile long ticketDelayMs = 0;
    private static volatile long taskDelayMs = 0;

    @BeforeClass
    public static void setUpClass() throws Exception {
        File ksFile = new File("app/src/test/resources/test_fixtures.p12");
        fixtureKeystore = KeyStore.getInstance("PKCS12");
        try (InputStream is = new FileInputStream(ksFile)) {
            fixtureKeystore.load(is, "testpass".toCharArray());
        }

        X509Certificate localIpCert = (X509Certificate) fixtureKeystore.getCertificate("local-ip");
        localIpFingerprint = PinnedTrustManager.computeFingerprint(localIpCert);

        SSLContext sslCtx = SSLContext.getInstance("TLS");
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(fixtureKeystore, "testpass".toCharArray());
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

        // 1. Endpoint: /access/ticket (PVE Ticket authentication)
        httpsServer.createContext("/api2/json/access/ticket", exchange -> {
            if (ticketDelayMs > 0) {
                try { Thread.sleep(ticketDelayMs); } catch (InterruptedException ignored) {}
            }
            InputStream is = exchange.getRequestBody();
            byte[] buf = new byte[1024];
            int n = is.read(buf);
            String body = n > 0 ? new String(buf, 0, n, "UTF-8") : "";
            // Verify username parameter: Proxmox requires realm (@pam or other realm)
            // If body has raw "username=root&" without realm, server rejects with 401
            if (body.contains("username=root&") || body.endsWith("username=root")) {
                byte[] err = "{\"message\":\"Authentication failed: username missing realm (@pam)\"}".getBytes("UTF-8");
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(401, err.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(err); }
                exchange.close();
                return;
            }

            int count = ticketCallCount.incrementAndGet();
            byte[] resp = ("{\"data\":{\"ticket\":\"PVE:root@pam:TICKET_VERSION_" + count +
                    "::SIGNATURE\",\"CSRFPreventionToken\":\"CSRF_" + count + "\",\"username\":\"root@pam\"}}").getBytes("UTF-8");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
            exchange.close();
        });

        // 2. Endpoint: /nodes with auth verification
        httpsServer.createContext("/api2/json/nodes", exchange -> {
            nodesCallCount.incrementAndGet();

            String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
            String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");

            // API Token verification: MUST have PVEAPIToken= prefix
            if (authHeader != null) {
                if (authHeader.startsWith("PVEAPIToken=")) {
                    byte[] resp = "{\"data\":[{\"node\":\"pve-node1\",\"status\":\"online\",\"cpu\":0.12,\"maxcpu\":8,\"mem\":1000,\"maxmem\":2000}]}".getBytes("UTF-8");
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, resp.length);
                    try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
                    exchange.close();
                    return;
                } else {
                    // Invalid token format without PVEAPIToken prefix -> 401
                    byte[] err = "{\"message\":\"Authentication failed: invalid token header\"}".getBytes("UTF-8");
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(401, err.length);
                    try (OutputStream os = exchange.getResponseBody()) { os.write(err); }
                    exchange.close();
                    return;
                }
            }

            // Ticket verification: Cookie must have PVEAuthCookie=
            if (cookieHeader != null && cookieHeader.contains("PVEAuthCookie=")) {
                if (simulateExpiredTicketOnServer && cookieHeader.contains("TICKET_VERSION_1")) {
                    // Expired ticket -> HTTP 401
                    byte[] err = "{\"message\":\"Authentication failed or token expired. Please re-authenticate.\"}".getBytes("UTF-8");
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(401, err.length);
                    try (OutputStream os = exchange.getResponseBody()) { os.write(err); }
                    exchange.close();
                    return;
                }

                // Valid ticket
                byte[] resp = "{\"data\":[{\"node\":\"pve-node1\",\"status\":\"online\",\"cpu\":0.12,\"maxcpu\":8,\"mem\":1000,\"maxmem\":2000}]}".getBytes("UTF-8");
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, resp.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
                exchange.close();
                return;
            }

            // No auth
            byte[] err = "{\"message\":\"No credentials\"}".getBytes("UTF-8");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(401, err.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(err); }
            exchange.close();
        });

        // 3. Endpoint: /forbidden (HTTP 403 test)
        httpsServer.createContext("/api2/json/forbidden", exchange -> {
            byte[] err = "{\"message\":\"Permission check failed\"}".getBytes("UTF-8");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(403, err.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(err); }
            exchange.close();
        });

        // 4. Endpoint: /redirect302 (HTTP 302 test)
        httpsServer.createContext("/api2/json/redirect302", exchange -> {
            exchange.getResponseHeaders().set("Location", "https://127.0.0.1:" + serverPort + "/api2/json/evil");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        // 5. Endpoint: /slow (delay test for deadline)
        httpsServer.createContext("/api2/json/slow", exchange -> {
            try { Thread.sleep(250); } catch (InterruptedException ignored) {}
            byte[] resp = "{\"data\":\"slow\"}".getBytes("UTF-8");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
            exchange.close();
        });

        // 6. Endpoint: /nodes/pve-node1/tasks/UPID:test/status (with delay and 401 on expired ticket)
        com.sun.net.httpserver.HttpHandler taskStatusHandler = exchange -> {
            taskCallCount.incrementAndGet();
            if (taskDelayMs > 0) {
                try { Thread.sleep(taskDelayMs); } catch (InterruptedException ignored) {}
            }
            String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
            if (simulateExpiredTicketOnServer && cookieHeader != null && cookieHeader.contains("TICKET_VERSION_1")) {
                byte[] err = "{\"message\":\"Authentication failed or token expired. Please re-authenticate.\"}".getBytes("UTF-8");
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(401, err.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(err); }
                exchange.close();
                return;
            }

            byte[] resp = "{\"data\":{\"status\":\"stopped\",\"exitstatus\":\"OK\",\"starttime\":1000,\"endtime\":1010}}".getBytes("UTF-8");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
            exchange.close();
        };
        httpsServer.createContext("/api2/json/nodes/pve-node1/tasks/UPID:test/status", taskStatusHandler);
        httpsServer.createContext("/api2/json/nodes/pve-node1/tasks/UPID%3Atest/status", taskStatusHandler);

        httpsServer.start();
        serverPort = httpsServer.getAddress().getPort();
    }

    @AfterClass
    public static void tearDownClass() {
        if (httpsServer != null) {
            httpsServer.stop(0);
        }
    }

    static class MockCredentialStore extends CredentialStore {
        private String apiToken;
        private String username;
        private String password;

        void setToken(String token) { this.apiToken = token; }
        void setPassword(String u, String p) { this.username = u; this.password = p; }

        @Override public boolean hasApiToken(String serverId) { return apiToken != null; }
        @Override public String getApiToken(String serverId) { return apiToken; }
        @Override public boolean hasPassword(String serverId) { return password != null; }
        @Override public String getUsername(String serverId) { return username; }
        @Override public String getPassword(String serverId) { return password; }
    }

    @Test
    public void testRawApiTokenNormalizedWithPveApiTokenPrefix() {
        // When user pastes token without prefix, header MUST still contain "PVEAPIToken="
        AuthCredentials creds = AuthCredentials.apiToken("root@pam!mytoken=12345678-1234-abcd-ef01-0123456789ab");
        assertEquals("PVEAPIToken=root@pam!mytoken=12345678-1234-abcd-ef01-0123456789ab",
                creds.getAuthorizationHeader());

        // When user pastes token with prefix, it is preserved without duplicating
        AuthCredentials credsWithPrefix = AuthCredentials.apiToken("PVEAPIToken=root@pam!mytoken=12345678-1234-abcd-ef01-0123456789ab");
        assertEquals("PVEAPIToken=root@pam!mytoken=12345678-1234-abcd-ef01-0123456789ab",
                credsWithPrefix.getAuthorizationHeader());

        // When user accidentally pastes "Authorization: PVEAPIToken=...", prefix is stripped cleanly
        AuthCredentials credsWithAuth = AuthCredentials.apiToken("Authorization: PVEAPIToken=root@pam!mytoken=12345678-1234-abcd-ef01-0123456789ab");
        assertEquals("PVEAPIToken=root@pam!mytoken=12345678-1234-abcd-ef01-0123456789ab",
                credsWithAuth.getAuthorizationHeader());
    }

    @Test
    public void testRawApiTokenSucceedsOverLiveHttps() throws Exception {
        ServerConfig cfg = new ServerConfig("srv-token", "Token Test", "127.0.0.1", serverPort,
                ServerConfig.ServerType.PVE, localIpFingerprint);
        ApiClient client = new ApiClient(cfg);

        // User enters raw token without PVEAPIToken=
        AuthCredentials rawToken = AuthCredentials.apiToken("root@pam!mytoken=12345678-1234-abcd-ef01-0123456789ab");
        String resp = client.get("/nodes", rawToken);
        assertNotNull(resp);
        assertTrue("Live request with raw token must succeed", resp.contains("pve-node1"));
    }

    @Test
    public void testTicketCookieFormattingPreservesOrAddsPveAuthCookiePrefix() {
        AuthCredentials ticket = AuthCredentials.ticket("root@pam", "TICKET_STRING", "CSRF", System.currentTimeMillis() + 3600000);
        assertEquals("PVEAuthCookie=TICKET_STRING", ticket.getTicketCookieValue());

        AuthCredentials ticketAlreadyPrefixed = AuthCredentials.ticket("root@pam", "PVEAuthCookie=TICKET_STRING", "CSRF", System.currentTimeMillis() + 3600000);
        assertEquals("PVEAuthCookie=TICKET_STRING", ticketAlreadyPrefixed.getTicketCookieValue());
    }

    @Test
    public void testTicketExpiryOnServerRefreshesAutomatically() throws Exception {
        MockCredentialStore store = new MockCredentialStore();
        store.setPassword("root@pam", "mypassword");
        SessionManager sessionManager = new SessionManager(store);

        ServerConfig cfg = new ServerConfig("srv-refresh", "Refresh Test", "127.0.0.1", serverPort,
                ServerConfig.ServerType.PVE, localIpFingerprint);

        PveApiDriver pve = new PveApiDriver(cfg, sessionManager);

        // Initial fetch: gets Ticket Version 1
        ticketCallCount.set(0);
        nodesCallCount.set(0);
        simulateExpiredTicketOnServer = false;

        AuthCredentials creds1 = sessionManager.getCredentials(cfg);
        assertNotNull(creds1);
        assertEquals(1, ticketCallCount.get());

        List<NodeStatus> nodes1 = pve.fetchAllNodes(creds1);
        assertEquals(1, nodes1.size());
        assertEquals(1, nodesCallCount.get());

        // Now simulate server expiring ticket 1 (server returns 401 to Ticket 1)
        simulateExpiredTicketOnServer = true;

        // Fetching nodes should detect 401, invalidate ticket 1, fetch ticket 2, and retry successfully
        List<NodeStatus> nodes2 = pve.fetchAllNodes(creds1);
        assertEquals(1, nodes2.size());
        assertEquals("Should have re-authenticated to get Ticket 2", 2, ticketCallCount.get());
        assertEquals("Should have made 2 calls (initial 401 + retry 200)", 3, nodesCallCount.get());

        // Subsequent getCredentials returns the new ticket
        AuthCredentials creds2 = sessionManager.getCredentials(cfg);
        assertNotNull(creds2);
        assertTrue(creds2.secretValue.contains("TICKET_VERSION_2"));
    }

    @Test
    public void test403ForbiddenDoesNotTriggerReauthLoop() throws Exception {
        MockCredentialStore store = new MockCredentialStore();
        store.setPassword("root@pam", "mypassword");
        SessionManager sessionManager = new SessionManager(store);

        ServerConfig cfg = new ServerConfig("srv-forbidden", "Forbidden Test", "127.0.0.1", serverPort,
                ServerConfig.ServerType.PVE, localIpFingerprint);

        PveApiDriver pve = new PveApiDriver(cfg, sessionManager);
        AuthCredentials creds = sessionManager.getCredentials(cfg);
        int ticketsBefore = ticketCallCount.get();

        try {
            pve.getApiClient().get("/forbidden", creds);
            fail("Expected 403 ApiException");
        } catch (ApiException e) {
            assertEquals(403, e.getHttpCode());
            assertTrue(e.getMessage().contains("Permission denied"));
        }

        // Must NOT have called /access/ticket to re-authenticate on 403
        assertEquals("403 must not trigger ticket re-authentication", ticketsBefore, ticketCallCount.get());
        // Session should remain cached and NOT invalidated
        assertEquals(creds, sessionManager.getCredentials(cfg));
    }

    private static Context createTestContext(Map<String, Object> values) {
        SharedPreferences.Editor editor = (SharedPreferences.Editor) Proxy.newProxyInstance(
                AuthRefreshAndTlsOptionTest.class.getClassLoader(),
                new Class[]{SharedPreferences.Editor.class},
                (p, m, x) -> {
                    if (m.getName().equals("putBoolean")) { values.put((String) x[0], x[1]); return p; }
                    if (m.getName().equals("putString")) { values.put((String) x[0], x[1]); return p; }
                    if (m.getName().equals("remove")) { values.remove((String) x[0]); return p; }
                    if (m.getName().equals("clear")) { values.clear(); return p; }
                    if (m.getName().equals("apply")) return null;
                    if (m.getName().equals("commit")) return true;
                    return p;
                });
        SharedPreferences prefs = (SharedPreferences) Proxy.newProxyInstance(
                AuthRefreshAndTlsOptionTest.class.getClassLoader(),
                new Class[]{SharedPreferences.class},
                (p, m, x) -> {
                    if (m.getName().equals("edit")) return editor;
                    if (m.getName().equals("getBoolean")) return values.getOrDefault(x[0], x[1]);
                    if (m.getName().equals("getString")) return values.getOrDefault(x[0], x[1]);
                    if (m.getName().equals("contains")) return values.containsKey(x[0]);
                    return null;
                });
        return new Context() {
            @Override
            public SharedPreferences getSharedPreferences(String name, int mode) {
                return prefs;
            }
        };
    }

    @Test
    public void testAutoAppendsPamRealmForUsernameWithoutRealm() throws Exception {
        ServerConfig cfg = new ServerConfig("srv-user", "User Test", "127.0.0.1", serverPort,
                ServerConfig.ServerType.PVE, localIpFingerprint);
        ApiClient client = new ApiClient(cfg);

        // "root" without @pam must be normalized to root@pam and succeed
        AuthCredentials ticket = client.authenticate("root", "testpassword");
        assertNotNull(ticket);
        assertEquals("root@pam", ticket.username);

        // "root@pam" with realm remains root@pam
        AuthCredentials ticket2 = client.authenticate("root@pam", "testpassword");
        assertNotNull(ticket2);
        assertEquals("root@pam", ticket2.username);
    }

    @Test
    public void testSkipTlsVerifyDefaultsOffAndPersistsAcrossStore() throws Exception {
        // Default constructor sets skipTlsVerify = false
        ServerConfig defaultCfg = new ServerConfig("srv-def", "Default Srv", "192.0.2.1", 8006,
                ServerConfig.ServerType.PVE, localIpFingerprint);
        assertFalse("skipTlsVerify must default to OFF (false)", defaultCfg.skipTlsVerify);

        // Opted-in server sets skipTlsVerify = true
        ServerConfig optInCfg = new ServerConfig("srv-opt", "Opted In", "192.0.2.2", 8006,
                ServerConfig.ServerType.PVE, null, false, true);
        assertTrue("skipTlsVerify must be true when specified", optInCfg.skipTlsVerify);

        // Persistence test via ServerConfigStore
        Map<String, Object> prefsMap = new HashMap<>();
        Context ctx = createTestContext(prefsMap);
        ServerConfigStore store = new ServerConfigStore(ctx);

        store.addOrUpdate(defaultCfg);
        store.addOrUpdate(optInCfg);

        ServerConfig loadedDef = store.findById("srv-def");
        assertNotNull(loadedDef);
        assertFalse("Loaded default server must retain skipTlsVerify=false", loadedDef.skipTlsVerify);

        ServerConfig loadedOpt = store.findById("srv-opt");
        assertNotNull(loadedOpt);
        assertTrue("Loaded opted-in server must retain skipTlsVerify=true", loadedOpt.skipTlsVerify);
    }

    @Test
    public void testOptedInServerBypassesPinAndHostnameValidationOnLiveHttps() throws Exception {
        // Opted-in server with null cert pin and arbitrary host name
        ServerConfig optInCfg = new ServerConfig("srv-optin-live", "Opt In Live", "127.0.0.1", serverPort,
                ServerConfig.ServerType.PVE, null, false, true);

        // Client creation MUST NOT throw GeneralSecurityException even with null pin
        ApiClient client = new ApiClient(optInCfg);
        AuthCredentials token = AuthCredentials.apiToken("root@pam!token=12345678-abcd-1234-abcd-123456789abc");

        // Live HTTPS call must succeed without certificate pin or hostname validation error
        String response = client.get("/nodes", token);
        assertNotNull(response);
        assertTrue(response.contains("pve-node1"));
    }

    @Test
    public void testDefaultServerFailsClosedWithoutPinEvenWhenAnotherServerOptsIn() throws Exception {
        // 1. One server opts in to skip TLS
        ServerConfig optInCfg = new ServerConfig("srv-optin", "Opt In", "127.0.0.1", serverPort,
                ServerConfig.ServerType.PVE, null, false, true);
        ApiClient optInClient = new ApiClient(optInCfg);
        assertNotNull(optInClient);

        // 2. Another server does NOT opt in (default OFF) and lacks pinned cert
        ServerConfig strictCfg = new ServerConfig("srv-strict", "Strict Srv", "127.0.0.1", serverPort,
                ServerConfig.ServerType.PVE, null, true, false);

        try {
            new ApiClient(strictCfg);
            fail("Default server without pinned cert must throw GeneralSecurityException");
        } catch (GeneralSecurityException e) {
            assertTrue("Must mention missing approved TLS certificate fingerprint",
                    e.getMessage().contains("no approved TLS certificate fingerprint pinned"));
        }
    }

    @Test
    public void testOptedInServerStillBlocksHttpRedirectsFailClosed() throws Exception {
        ServerConfig optInCfg = new ServerConfig("srv-optin-redir", "Opt In Redir", "127.0.0.1", serverPort,
                ServerConfig.ServerType.PVE, null, false, true);
        ApiClient client = new ApiClient(optInCfg);
        AuthCredentials token = AuthCredentials.apiToken("root@pam!token=12345678-abcd-1234-abcd-123456789abc");

        try {
            client.get("/redirect302", token);
            fail("Redirects must be blocked fail-closed even for opted-in servers");
        } catch (ApiException e) {
            assertEquals(302, e.getHttpCode());
            assertTrue("Must report redirect blocked", e.getMessage().contains("redirect (302"));
        }
    }

    @Test
    public void testOptedInServerStillEnforcesMonotonicDeadlines() throws Exception {
        ServerConfig optInCfg = new ServerConfig("srv-optin-deadline", "Opt In Deadline", "127.0.0.1", serverPort,
                ServerConfig.ServerType.PVE, null, false, true);
        ApiClient client = new ApiClient(optInCfg);
        AuthCredentials token = AuthCredentials.apiToken("root@pam!token=12345678-abcd-1234-abcd-123456789abc");

        try {
            // Send bounded request with 50ms budget against 250ms slow endpoint
            client.postBounded("/slow", "", token, 50L);
            fail("Deadline must be enforced even for opted-in servers");
        } catch (ApiException e) {
            assertTrue("Must report deadline exceeded: " + e.getMessage(),
                    e.getMessage().contains("deadline exceeded") || e.getMessage().contains("expired"));
        }
    }

    @Test
    public void testSavedDemoServerEntriesRefuseNetworkConnectionsFailClosed() throws Exception {
        ServerConfig demoId = new ServerConfig("DEMO", "Demo PVE", "192.0.2.1", 8006,
                ServerConfig.ServerType.PVE, null, false, true);
        ServerConfig demoHost = new ServerConfig("srv-demo-host", "Demo Srv", "demo", 8006,
                ServerConfig.ServerType.PVE, null, false, true);
        ServerConfig demoExactName = new ServerConfig("srv-legacy-name", "Demo Server", "192.0.2.1", 8006,
                ServerConfig.ServerType.PVE, null, false, true);
        ServerConfig demoBannerName = new ServerConfig("srv-banner-name", "⚠ DEMO MODE — Not real data", "192.0.2.1", 8006,
                ServerConfig.ServerType.PVE, null, false, true);

        assertTrue("id DEMO must be recognized as legacy demo", demoId.isLegacyDemo());
        assertTrue("host demo must be recognized as legacy demo", demoHost.isLegacyDemo());
        assertTrue("exact name Demo Server must be recognized as legacy demo", demoExactName.isLegacyDemo());
        assertTrue("banner name must be recognized as legacy demo", demoBannerName.isLegacyDemo());

        for (ServerConfig srv : new ServerConfig[]{demoId, demoHost, demoExactName, demoBannerName}) {
            try {
                new ApiClient(srv);
                fail("Legacy demo entry must refuse ApiClient connection: " + srv);
            } catch (GeneralSecurityException e) {
                assertTrue(e.getMessage().contains("Demo server entries cannot make network connections"));
            }
        }

        MockCredentialStore store = new MockCredentialStore();
        store.setToken("PVEAPIToken=dummy");
        SessionManager sm = new SessionManager(store);
        for (ServerConfig srv : new ServerConfig[]{demoId, demoHost, demoExactName, demoBannerName}) {
            assertNull("SessionManager must return null credentials for legacy demo: " + srv, sm.getCredentials(srv));
        }
    }

    @Test
    public void testRealServerNamesWithDemoSubstringAreNotClassifiedAsDemo() throws Exception {
        // Legitimate user server configs containing substring "DEMO"
        ServerConfig demonstrationLab = new ServerConfig("real-server-uuid", "Demonstration Lab",
                "127.0.0.1", serverPort, ServerConfig.ServerType.PVE, null, false, true);
        ServerConfig demolition = new ServerConfig("real-server-uuid-2", "Demolition Cluster",
                "127.0.0.1", serverPort, ServerConfig.ServerType.PVE, null, false, true);
        ServerConfig academy = new ServerConfig("real-server-uuid-3", "Academy Demonstration Environment",
                "127.0.0.1", serverPort, ServerConfig.ServerType.PVE, null, false, true);
        ServerConfig pandemonium = new ServerConfig("real-server-uuid-4", "Pandemonium PVE",
                "127.0.0.1", serverPort, ServerConfig.ServerType.PVE, null, false, true);

        for (ServerConfig real : new ServerConfig[]{demonstrationLab, demolition, academy, pandemonium}) {
            assertFalse("Real server name must NOT be classified as legacy demo: " + real.name, real.isLegacyDemo());
            // ApiClient must NOT reject legitimate server as a demo server
            ApiClient client = new ApiClient(real);
            assertNotNull(client);
        }

        MockCredentialStore store = new MockCredentialStore();
        store.setToken("PVEAPIToken=root@pam!token=12345678-abcd-1234-abcd-123456789abc");
        SessionManager sm = new SessionManager(store);
        AuthCredentials creds = sm.getCredentials(demonstrationLab);
        assertNotNull("SessionManager must return valid credentials for legitimate Demonstration Lab", creds);
    }

    @Test
    public void testAuthRefreshSharedAbsoluteDeadlineEnforcedAcrossFirstRequestRefreshAndRetry() throws Exception {
        simulateExpiredTicketOnServer = true;
        ticketDelayMs = 100;
        taskDelayMs = 100;
        ticketCallCount.set(1);
        taskCallCount.set(0);

        ServerConfig cfg = new ServerConfig("srv-deadline-test", "Shared Deadline Test",
                "127.0.0.1", serverPort, ServerConfig.ServerType.PVE, null, false, true);

        MockCredentialStore store = new MockCredentialStore();
        store.setPassword("root", "secretpassword");
        SessionManager sm = new SessionManager(store);

        // Pre-populate stale ticket (version 1)
        AuthCredentials staleTicket = AuthCredentials.ticket("root@pam", "PVE:root@pam:TICKET_VERSION_1::SIG", "CSRF_1", System.currentTimeMillis() + 7200000L);
        PveApiDriver pve = new PveApiDriver(cfg, sm);

        // Case 1: Sufficient budget (550ms) -> First request (100ms) + Ticket Refresh (100ms) + Retry (100ms) = ~300ms <= 550ms -> SUCCESS
        long t0 = System.currentTimeMillis();
        TaskResult result = pve.pollTask("pve-node1", "UPID:test", staleTicket, 550L);
        long elapsed = System.currentTimeMillis() - t0;

        assertNotNull("Task result must not be null", result);
        assertTrue("Task must report success", result.isSuccess());
        assertEquals("OK", result.exitStatus);
        assertTrue("Must have called task endpoint at least twice (stale + refreshed)", taskCallCount.get() >= 2);
        assertTrue("Must have refreshed ticket via /access/ticket", ticketCallCount.get() >= 1);
        assertTrue("Total elapsed must be bounded: " + elapsed + "ms", elapsed < 550);

        // Case 2: Insufficient budget for refresh (150ms).
        // First request takes 100ms, leaving ~50ms for refresh. Refresh requires 100ms.
        // Must fail with deadline exceeded within ~200ms without hanging for 15s.
        simulateExpiredTicketOnServer = true;
        sm.invalidateSession(cfg.id);
        taskCallCount.set(0);
        ticketCallCount.set(1);

        long t1 = System.currentTimeMillis();
        try {
            pve.pollTask("pve-node1", "UPID:test", staleTicket, 150L);
            fail("Must fail when budget expires during ticket refresh");
        } catch (ApiException e) {
            long elapsedFail = System.currentTimeMillis() - t1;
            assertTrue("Total elapsed must be tightly bounded (" + elapsedFail + "ms): " + e.getMessage(), elapsedFail < 300);
            assertTrue("Must report deadline/expired: " + e.getMessage(),
                    e.getMessage().toLowerCase().contains("deadline") || e.getMessage().toLowerCase().contains("expired"));
        }

        // Case 3: Insufficient budget for retry (230ms).
        // First request (100ms) + Refresh (100ms) = 200ms. Remaining for retry = ~30ms < 100ms.
        // Must fail with deadline exceeded within ~280ms without hanging for second full budget.
        simulateExpiredTicketOnServer = true;
        sm.invalidateSession(cfg.id);
        taskCallCount.set(0);
        ticketCallCount.set(1);

        long t2 = System.currentTimeMillis();
        try {
            pve.pollTask("pve-node1", "UPID:test", staleTicket, 230L);
            fail("Must fail when budget expires during post-refresh retry");
        } catch (ApiException e) {
            long elapsedFail = System.currentTimeMillis() - t2;
            assertTrue("Total elapsed must be tightly bounded (" + elapsedFail + "ms): " + e.getMessage(), elapsedFail < 350);
            assertTrue("Must report deadline/expired: " + e.getMessage(),
                    e.getMessage().toLowerCase().contains("deadline") || e.getMessage().toLowerCase().contains("expired"));
        } finally {
            ticketDelayMs = 0;
            taskDelayMs = 0;
            simulateExpiredTicketOnServer = false;
        }
    }

    @Test
    public void testPdmSessionRefreshPropagatesRemainingBudgetOnLoopbackServer() throws Exception {
        ServerConfig pdmCfg = new ServerConfig(
                "srv-pdm-deadline", "PDM Deadline Test", "127.0.0.1", serverPort,
                ServerConfig.ServerType.PDM, localIpFingerprint, true, false);

        MockCredentialStore store = new MockCredentialStore();
        store.setPassword("root@pam", "secretpassword");
        SessionManager sm = new SessionManager(store);

        ticketDelayMs = 250;
        ticketCallCount.set(0);

        try {
            // Case 1: Remaining budget (80ms) is less than ticket response delay (250ms).
            // PDM refresh MUST propagate remaining budget and fail promptly with deadline exceeded,
            // rather than defaulting to 15s and succeeding or hanging.
            long t0 = System.currentTimeMillis();
            try {
                sm.refreshSession(pdmCfg, 80L);
                fail("PDM refresh must fail when remaining budget (80ms) expires during delayed ticket request");
            } catch (ApiException e) {
                long elapsed = System.currentTimeMillis() - t0;
                assertTrue("Elapsed time must be tightly bounded (" + elapsed + "ms < 250ms): " + e.getMessage(), elapsed < 250);
                assertTrue("Exception message must indicate deadline/expired: " + e.getMessage(),
                        e.getMessage().toLowerCase().contains("deadline") || e.getMessage().toLowerCase().contains("expired"));
            }

            // Case 2: Sufficient remaining budget (600ms) allows ticket response to complete (~250ms).
            long t1 = System.currentTimeMillis();
            AuthCredentials creds = sm.refreshSession(pdmCfg, 600L);
            long elapsedSuccess = System.currentTimeMillis() - t1;

            assertNotNull("Refreshed credentials must not be null with sufficient budget", creds);
            assertTrue("Should have taken at least delay time (" + elapsedSuccess + "ms >= 200ms)", elapsedSuccess >= 200);
            assertTrue("Elapsed must be within remaining budget (" + elapsedSuccess + "ms < 600ms)", elapsedSuccess < 600);
            assertNotNull("Must return ticket string", creds.getTicketCookieValue());

            // Case 3: Negative remaining budget (already expired, -25ms) must fail immediately without dispatch
            try {
                sm.refreshSession(pdmCfg, -25L);
                fail("PDM refresh must fail immediately when remaining budget is negative");
            } catch (ApiException e) {
                assertTrue("Exception message must indicate deadline/expired: " + e.getMessage(),
                        e.getMessage().toLowerCase().contains("deadline") || e.getMessage().toLowerCase().contains("expired"));
            }

        } finally {
            ticketDelayMs = 0;
        }
    }

    @Test
    public void testTlsVerificationStatus_SkipTlsOverridesFingerprint() {
        ServerConfig cfgWithOldFp = new ServerConfig(
                "srv-insecure", "InsecureServer", "127.0.0.1", serverPort,
                ServerConfig.ServerType.PVE,
                "01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF",
                false, true);

        TlsVerificationStatus status = TlsVerificationStatus.evaluate(cfgWithOldFp);
        assertEquals("When skipTlsVerify is true, status must be DISABLED_INSECURE even if old fingerprint is retained",
                TlsVerificationStatus.StatusLevel.DISABLED_INSECURE, status.level);
        assertTrue("Status must be flagged as a warning", status.isWarning);
        assertFalse("Status must not be marked secure", status.isSecure);
        assertTrue("Display text must prominently state verification is disabled: " + status.displayText,
                status.displayText.contains("TLS VERIFICATION DISABLED (Insecure)"));
        assertFalse("Display text must NEVER claim Pinned TLS when skip is active",
                status.displayText.contains("Pinned TLS"));
    }

    @Test
    public void testTlsVerificationStatus_PinnedVerifiedWhenSkipFalse() {
        ServerConfig cfgPinned = new ServerConfig(
                "srv-pinned", "PinnedServer", "127.0.0.1", serverPort,
                ServerConfig.ServerType.PVE,
                "01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF",
                true, false);

        TlsVerificationStatus status = TlsVerificationStatus.evaluate(cfgPinned);
        assertEquals(TlsVerificationStatus.StatusLevel.PINNED_VERIFIED, status.level);
        assertFalse("Pinned verified must not be flagged as warning", status.isWarning);
        assertTrue("Pinned verified must be marked secure", status.isSecure);
        assertTrue("Display text must show pinned fingerprint: " + status.displayText,
                status.displayText.startsWith("Pinned TLS: 01:23:45:67:89:AB"));
    }

    @Test
    public void testTlsVerificationStatus_PendingWhenNoFingerprint() {
        ServerConfig cfgPending = new ServerConfig(
                "srv-pending", "PendingServer", "127.0.0.1", serverPort,
                ServerConfig.ServerType.PVE,
                null, false, false);

        TlsVerificationStatus status = TlsVerificationStatus.evaluate(cfgPending);
        assertEquals(TlsVerificationStatus.StatusLevel.PENDING_VERIFICATION, status.level);
        assertTrue(status.isWarning);
        assertFalse(status.isSecure);
        assertEquals("TLS Fingerprint: Pending verification", status.displayText);
    }

    @Test
    public void testTlsConfirmationDecision_DefaultOffAndExplicitConfirmationRequired() {
        // Default OFF: user didn't request skip
        assertFalse(TlsConfirmationDecision.resolveCheckboxState(false, false));
        assertFalse(TlsConfirmationDecision.resolveCheckboxState(false, true));

        // User requested skip but cancelled warning dialog -> must remain OFF
        assertFalse("Toggling skip without explicit confirmation must resolve to FALSE",
                TlsConfirmationDecision.resolveCheckboxState(true, false));

        // User requested skip and confirmed warning -> resolves to TRUE
        assertTrue("Toggling skip WITH explicit confirmation resolves to TRUE",
                TlsConfirmationDecision.resolveCheckboxState(true, true));
    }
}
