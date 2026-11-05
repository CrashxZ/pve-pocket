// SPDX-License-Identifier: GPL-3.0-or-later
// PVE Pocket — Proxmox Datacenter Manager (PDM) Upstream Auth & Cookie Followup Regression Tests
package com.homelabpocket.api;

import com.homelabpocket.model.AuthCredentials;
import com.homelabpocket.model.ServerConfig;
import com.homelabpocket.security.PinnedTrustManager;
import com.homelabpocket.util.JsonUtil;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;

import org.json.JSONArray;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

import static org.junit.Assert.*;

public class PdmUpstreamAuthRegressionTest {

    private static HttpsServer mockServer;
    private static int mockPort;
    private static String mockFingerprint;

    // Control flags for server responses
    private static final AtomicBoolean returnHttpOnlyPdm = new AtomicBoolean(true);
    private static final AtomicBoolean returnTfaChallenge = new AtomicBoolean(false);
    private static final AtomicBoolean returnPveTfaChallenge = new AtomicBoolean(false);
    private static final AtomicBoolean returnMissingCookie = new AtomicBoolean(false);
    private static final AtomicBoolean returnMalformedJson = new AtomicBoolean(false);
    private static final AtomicBoolean return401 = new AtomicBoolean(false);
    private static final AtomicBoolean return403 = new AtomicBoolean(false);
    private static final AtomicReference<String> customSetCookieHeader = new AtomicReference<>(null);

    // Captured headers from followup request
    private static final AtomicReference<String> lastFollowupCookieHeader = new AtomicReference<>(null);
    private static final AtomicReference<String> lastFollowupCsrfHeader = new AtomicReference<>(null);
    private static final AtomicInteger sinkRequestCount = new AtomicInteger(0);

    // Dynamic error reflection & redirect injection flags
    private static final AtomicReference<String> returnReflected400Login = new AtomicReference<>(null);
    private static final AtomicReference<String> returnReflected302Login = new AtomicReference<>(null);
    private static final AtomicReference<String> returnReflected400Followup = new AtomicReference<>(null);
    private static final AtomicReference<String> returnReflected302Followup = new AtomicReference<>(null);

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

        // 1. Upstream Auth Context: /api2/json/access/ticket
        mockServer.createContext("/api2/json/access/ticket", exchange -> {
            if (returnReflected400Login.get() != null) {
                String err = "{\"message\":\"password rejected: " + returnReflected400Login.get() + "\"}";
                byte[] b = err.getBytes("UTF-8");
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(400, b.length);
                exchange.getResponseBody().write(b);
                exchange.close();
                return;
            }
            if (returnReflected302Login.get() != null) {
                exchange.getResponseHeaders().set("Location", returnReflected302Login.get());
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }
            if (return401.get()) {
                String err = "{\"message\":\"Authentication failed: invalid credentials\"}";
                byte[] b = err.getBytes("UTF-8");
                exchange.sendResponseHeaders(401, b.length);
                exchange.getResponseBody().write(b);
                exchange.close();
                return;
            }
            if (return403.get()) {
                String err = "{\"message\":\"Permission denied\"}";
                byte[] b = err.getBytes("UTF-8");
                exchange.sendResponseHeaders(403, b.length);
                exchange.getResponseBody().write(b);
                exchange.close();
                return;
            }
            if (returnMalformedJson.get()) {
                // Synthetic secret reflected in malformed response body
                String err = "{\"reflected_secret\":\"my_secret_password_12345\", syntax_error: bad_json";
                byte[] b = err.getBytes("UTF-8");
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, b.length);
                exchange.getResponseBody().write(b);
                exchange.close();
                return;
            }

            if (returnTfaChallenge.get()) {
                // PDM upstream TFA challenge shape (proxmox-auth-api handle_ticket_creation AuthResult::Partial)
                // csrfprevention_token is "invalid", ticket_info is None, ticket contains !tfa! challenge
                String tfaResp = "{\"data\":{\"CSRFPreventionToken\":\"invalid\",\"ticket\":\"PDM:!tfa!{\\\"userid\\\":\\\"root@pam\\\",\\\"challenge\\\":{\\\"totp\\\":true}}\",\"username\":\"root@pam\"}}";
                byte[] b = tfaResp.getBytes("UTF-8");
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, b.length);
                exchange.getResponseBody().write(b);
                exchange.close();
                return;
            }

            if (returnPveTfaChallenge.get()) {
                // PVE standard TFA challenge shape: NeedTFA: 1
                String tfaResp = "{\"data\":{\"NeedTFA\":1,\"ticket\":\"PVE:!tfa!partial_challenge\",\"username\":\"root@pam\"}}";
                byte[] b = tfaResp.getBytes("UTF-8");
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, b.length);
                exchange.getResponseBody().write(b);
                exchange.close();
                return;
            }

            if (customSetCookieHeader.get() != null) {
                String respBody = "{\"data\":{\"CSRFPreventionToken\":\"csrf-upstream-pdm-token-999\",\"ticket-info\":\"PDM:root@pam:67C65000::ticketinfo\",\"username\":\"root@pam\"}}";
                exchange.getResponseHeaders().add("Set-Cookie", customSetCookieHeader.get());
                byte[] respBytes = respBody.getBytes("UTF-8");
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, respBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(respBytes);
                }
                return;
            }

            if (returnHttpOnlyPdm.get()) {
                // Upstream PDM HttpOnly cookie login response (proxmox_auth_api::api::create_ticket_http_only):
                // Response body contains CSRFPreventionToken, ticket-info, username.
                // Sensitive ticket is omitted from JSON body and returned strictly in Set-Cookie: __Host-PDMAuthCookie=...
                String respBody = "{\"data\":{\"CSRFPreventionToken\":\"csrf-upstream-pdm-token-999\",\"ticket-info\":\"PDM:root@pam:67C65000::ticketinfo\",\"username\":\"root@pam\"}}";
                if (!returnMissingCookie.get()) {
                    exchange.getResponseHeaders().add("Set-Cookie",
                            "__Host-PDMAuthCookie=PDM:root@pam:67C65000::validUpstreamSignature123; Secure; SameSite=Lax; HttpOnly; Path=/;");
                }
                byte[] respBytes = respBody.getBytes("UTF-8");
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, respBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(respBytes);
                }
                return;
            }

            // Standard PVE response (ticket in data.ticket)
            String pveBody = "{\"data\":{\"CSRFPreventionToken\":\"csrf-pve-token-888\",\"ticket\":\"PVE:root@pam:67C65000::validPveSignature456\",\"username\":\"root@pam\"}}";
            exchange.getResponseHeaders().add("Set-Cookie", "PVEAuthCookie=PVE:root@pam:67C65000::validPveSignature456; Path=/; Secure; HttpOnly;");
            byte[] respBytes = pveBody.getBytes("UTF-8");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, respBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(respBytes);
            }
        });

        // 2. Followup context: /api2/json/remotes (used by PDM)
        // Rejects requests with missing or invalid ticket cookie / CSRF token
        mockServer.createContext("/api2/json/remotes", exchange -> {
            if (returnReflected400Followup.get() != null) {
                String err = "{\"errors\":\"reflected followup error: " + returnReflected400Followup.get() + "\"}";
                byte[] b = err.getBytes("UTF-8");
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(400, b.length);
                exchange.getResponseBody().write(b);
                exchange.close();
                return;
            }
            if (returnReflected302Followup.get() != null) {
                exchange.getResponseHeaders().set("Location", returnReflected302Followup.get());
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }

            String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
            String csrfHeader = exchange.getRequestHeaders().getFirst("CSRFPreventionToken");
            lastFollowupCookieHeader.set(cookieHeader);
            lastFollowupCsrfHeader.set(csrfHeader);

            boolean hasValidCookie = (cookieHeader != null &&
                    (cookieHeader.contains("__Host-PDMAuthCookie=PDM:root@pam:67C65000::validUpstreamSignature123") ||
                     cookieHeader.contains("PDMAuthCookie=PDM:root@pam:67C65000::validUpstreamSignature123")));
            boolean hasValidCsrf = ("csrf-upstream-pdm-token-999".equals(csrfHeader));

            if (!hasValidCookie || !hasValidCsrf) {
                String err = "{\"message\":\"Authentication failed: missing or invalid credentials\"}";
                byte[] b = err.getBytes("UTF-8");
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(401, b.length);
                exchange.getResponseBody().write(b);
                exchange.close();
                return;
            }

            String resp = "{\"data\":[{\"name\":\"remote-pve-1\",\"type\":\"pve\",\"url\":\"https://192.0.2.10:8006\"}]}";
            byte[] b = resp.getBytes("UTF-8");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, b.length);
            exchange.getResponseBody().write(b);
            exchange.close();
        });

        // 3. Followup context: /api2/json/nodes (used by PVE)
        // Rejects requests with missing or invalid ticket cookie / CSRF token
        mockServer.createContext("/api2/json/nodes", exchange -> {
            String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
            String csrfHeader = exchange.getRequestHeaders().getFirst("CSRFPreventionToken");
            lastFollowupCookieHeader.set(cookieHeader);
            lastFollowupCsrfHeader.set(csrfHeader);

            boolean hasValidCookie = (cookieHeader != null &&
                    cookieHeader.contains("PVEAuthCookie=PVE:root@pam:67C65000::validPveSignature456"));
            boolean hasValidCsrf = ("csrf-pve-token-888".equals(csrfHeader));

            if (!hasValidCookie || !hasValidCsrf) {
                String err = "{\"message\":\"Authentication failed: missing or invalid credentials\"}";
                byte[] b = err.getBytes("UTF-8");
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(401, b.length);
                exchange.getResponseBody().write(b);
                exchange.close();
                return;
            }

            String resp = "{\"data\":[{\"node\":\"pve-node-1\",\"status\":\"online\"}]}";
            byte[] b = resp.getBytes("UTF-8");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, b.length);
            exchange.getResponseBody().write(b);
            exchange.close();
        });

        // 4. Redirect source & sink contexts to verify redirect refusal & credential isolation
        mockServer.createContext("/api2/json/redirect_source", exchange -> {
            exchange.getResponseHeaders().set("Location", "https://127.0.0.1:" + mockPort + "/api2/json/redirect_sink");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        mockServer.createContext("/api2/json/redirect_sink", exchange -> {
            sinkRequestCount.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
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

    private void resetServerFlags() {
        returnHttpOnlyPdm.set(true);
        returnTfaChallenge.set(false);
        returnPveTfaChallenge.set(false);
        returnMissingCookie.set(false);
        returnMalformedJson.set(false);
        return401.set(false);
        return403.set(false);
        customSetCookieHeader.set(null);
        lastFollowupCookieHeader.set(null);
        lastFollowupCsrfHeader.set(null);
        sinkRequestCount.set(0);
        returnReflected400Login.set(null);
        returnReflected302Login.set(null);
        returnReflected400Followup.set(null);
        returnReflected302Followup.set(null);
    }

    @Test
    public void testPdmUpstreamPasswordLoginHttpOnlyCookieAndFollowup() throws Exception {
        resetServerFlags();
        ServerConfig pdmConfig = new ServerConfig("pdm-upstream", "PDM Real Upstream", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);
        PdmApiDriver driver = new PdmApiDriver(pdmConfig);

        // 1. Password login against upstream PDM mock returning HttpOnly cookie + ticket-info
        AuthCredentials creds = driver.authenticate("root", "secretpassword");
        assertNotNull("AuthCredentials must not be null", creds);
        assertEquals("root@pam", creds.username);
        assertEquals("csrf-upstream-pdm-token-999", creds.csrfToken);
        assertFalse(creds.isExpired());
        assertFalse(creds.isApiToken());

        // 2. Cookie followup request to PDM endpoint (/api2/json/remotes)
        JSONArray remotes = driver.fetchRemotes(creds);
        assertNotNull(remotes);
        assertEquals(1, remotes.length());

        // Verify cookie received on server contains upstream PDM cookie name
        String cookieReceived = lastFollowupCookieHeader.get();
        assertNotNull("Cookie header must be sent on followup", cookieReceived);
        assertTrue("Followup cookie header must contain __Host-PDMAuthCookie",
                cookieReceived.contains("__Host-PDMAuthCookie="));
        assertTrue("Cookie must contain upstream ticket payload",
                cookieReceived.contains("PDM:root@pam:67C65000::validUpstreamSignature123"));
        assertFalse("PDM followup must NOT contain PVE cookie", cookieReceived.contains("PVEAuthCookie"));
    }

    @Test
    public void testPvePasswordLoginAndFollowupDistinctFromPdm() throws Exception {
        resetServerFlags();
        returnHttpOnlyPdm.set(false); // Emit PVE response

        ServerConfig pveConfig = new ServerConfig("pve-server", "PVE Server", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PVE, mockFingerprint);
        PveApiDriver driver = new PveApiDriver(pveConfig);

        AuthCredentials creds = driver.authenticate("root", "secretpassword");
        assertNotNull(creds);
        assertEquals("root@pam", creds.username);
        assertEquals("csrf-pve-token-888", creds.csrfToken);
        assertFalse(creds.isExpired());

        // Followup request to PVE endpoint
        driver.fetchAllNodes(creds);
        String cookieReceived = lastFollowupCookieHeader.get();
        assertNotNull("Cookie header must be sent on PVE followup", cookieReceived);
        assertTrue("Cookie must contain PVEAuthCookie", cookieReceived.contains("PVEAuthCookie="));
        assertTrue("Cookie must contain PVE ticket payload", cookieReceived.contains("PVE:root@pam:67C65000::validPveSignature456"));
        assertFalse("PVE followup must NOT contain PDM cookie", cookieReceived.contains("PDMAuthCookie"));
    }

    @Test
    public void testPdmTfaChallengeFailsClosed() throws Exception {
        resetServerFlags();
        returnTfaChallenge.set(true);

        ServerConfig pdmConfig = new ServerConfig("pdm-tfa", "PDM TFA", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);
        PdmApiDriver driver = new PdmApiDriver(pdmConfig);

        try {
            driver.authenticate("root", "password");
            fail("Two-factor authentication challenge must fail-closed");
        } catch (ApiException e) {
            assertTrue("Exception message must mention TFA/2FA",
                    e.getMessage().toLowerCase().contains("two-factor") ||
                    e.getMessage().toLowerCase().contains("tfa") ||
                    e.getMessage().toLowerCase().contains("second login factor"));
        }
    }

    @Test
    public void testPveTfaChallengeFailsClosed() throws Exception {
        resetServerFlags();
        returnPveTfaChallenge.set(true);

        ServerConfig pveConfig = new ServerConfig("pve-tfa", "PVE TFA", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PVE, mockFingerprint);
        PveApiDriver driver = new PveApiDriver(pveConfig);

        try {
            driver.authenticate("root", "password");
            fail("PVE NeedTFA challenge must fail-closed");
        } catch (ApiException e) {
            assertTrue("Exception message must mention TFA/2FA",
                    e.getMessage().toLowerCase().contains("two-factor") ||
                    e.getMessage().toLowerCase().contains("tfa"));
        }
    }

    @Test
    public void testPdmMalformedMissingTicketAndCookieFailsClosed() throws Exception {
        resetServerFlags();
        returnMissingCookie.set(true); // Body has ticket-info, but Set-Cookie header is absent

        ServerConfig pdmConfig = new ServerConfig("pdm-nocookie", "PDM No Cookie", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);
        PdmApiDriver driver = new PdmApiDriver(pdmConfig);

        try {
            driver.authenticate("root", "password");
            fail("Missing ticket and cookie must fail-closed");
        } catch (ApiException e) {
            assertTrue("Exception must explain missing ticket/cookie",
                    e.getMessage().contains("Failed to parse ticket response"));
        }
    }

    @Test
    public void testPdmMalformedJsonFailsClosedWithoutSecretLeakage() throws Exception {
        resetServerFlags();
        returnMalformedJson.set(true);

        ServerConfig pdmConfig = new ServerConfig("pdm-malformed", "PDM Malformed", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);
        PdmApiDriver driver = new PdmApiDriver(pdmConfig);

        try {
            driver.authenticate("root", "my_secret_password_12345");
            fail("Malformed JSON must throw ApiException");
        } catch (ApiException e) {
            assertFalse("Exception must not leak synthetic secret reflected in malformed response",
                    e.getMessage().contains("my_secret_password_12345"));
            assertTrue(e.getMessage().contains("Failed to parse ticket response"));
        }
    }

    @Test
    public void testPdmAuth401Failure() throws Exception {
        resetServerFlags();
        return401.set(true);

        ServerConfig pdmConfig = new ServerConfig("pdm-401", "PDM 401", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);
        PdmApiDriver driver = new PdmApiDriver(pdmConfig);

        try {
            driver.authenticate("root", "wrongpassword");
            fail("401 must throw ApiException");
        } catch (ApiException e) {
            assertEquals(401, e.getHttpCode());
            assertTrue(e.getMessage().contains("Authentication failed"));
        }
    }

    @Test
    public void testPdmAuth403Failure() throws Exception {
        resetServerFlags();
        return403.set(true);

        ServerConfig pdmConfig = new ServerConfig("pdm-403", "PDM 403", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);
        PdmApiDriver driver = new PdmApiDriver(pdmConfig);

        try {
            driver.authenticate("root", "nopermpassword");
            fail("403 must throw ApiException");
        } catch (ApiException e) {
            assertEquals(403, e.getHttpCode());
            assertTrue(e.getMessage().contains("Permission denied"));
        }
    }

    @Test
    public void testPdmDeadlineExceededFailsClosed() throws Exception {
        resetServerFlags();
        ServerConfig pdmConfig = new ServerConfig("pdm-deadline", "PDM Deadline", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);
        PdmApiDriver driver = new PdmApiDriver(pdmConfig);

        try {
            // Expired budget (0ms)
            driver.authenticate("root", "password", 0L);
            fail("Expired budget must throw ApiException immediately");
        } catch (ApiException e) {
            assertTrue(e.getMessage().contains("deadline"));
        }
    }

    @Test
    public void testPdmTicketExpiryBehavior() {
        long now = System.currentTimeMillis();
        // Valid for 2 hours -> NOT expired
        AuthCredentials validPdm = AuthCredentials.ticket("root@pam", "PDM:ticket123", "csrf999", now + 7200_000L);
        assertFalse("Ticket with 2h remaining must not be expired", validPdm.isExpired());
        assertEquals("__Host-PDMAuthCookie=PDM:ticket123", validPdm.getTicketCookieValue(ServerConfig.ServerType.PDM));

        // 30 seconds remaining -> considered expired due to 60s safety renewal buffer
        AuthCredentials expiringSoon = AuthCredentials.ticket("root@pam", "PDM:ticket123", "csrf999", now + 30_000L);
        assertTrue("Ticket expiring in 30s must be marked expired by safety buffer", expiringSoon.isExpired());

        // Past expiry -> expired
        AuthCredentials expiredPdm = AuthCredentials.ticket("root@pam", "PDM:ticket123", "csrf999", now - 15_000L);
        assertTrue("Past-expiry ticket must be marked expired", expiredPdm.isExpired());
    }

    @Test
    public void testPdmCredentialRedaction() {
        String logLine = "Cookie: __Host-PDMAuthCookie=PDM:user@pdm:12345::sig; PDMAuthCookie=PDM:user@pdm:12345::sig; PDMAUTHCOOKIE=PDM:user@pdm:12345::sig; PDMAPIToken=root@pam!token=11223344-5566-7788-99aa-bbccddeeff00; \"ticket-info\":\"PDM:user@pdm:12345::ticketinfo\"";
        String redacted = JsonUtil.sanitizeForLog(logLine);
        assertNotNull(redacted);
        assertFalse("Must redact __Host-PDMAuthCookie value", redacted.contains("PDM:user@pdm:12345::sig"));
        assertFalse("Must redact token uuid", redacted.contains("11223344-5566"));
        assertFalse("Must redact ticket-info value", redacted.contains("PDM:user@pdm:12345::ticketinfo"));
        assertTrue(redacted.contains("__Host-PDMAuthCookie=REDACTED"));
        assertTrue(redacted.contains("PDMAuthCookie=REDACTED"));
        assertTrue(redacted.contains("PDMAUTHCOOKIE=REDACTED"));
        assertTrue(redacted.contains("PDMAPIToken=REDACTED"));
        assertTrue(redacted.contains("\"ticket-info\":\"REDACTED\""));
    }

    // -------------------------------------------------------------
    // Durable actual HTTPS tests for boundary failure paths & isolation
    // -------------------------------------------------------------

    @Test
    public void testHttpsPdmRejectsPveOnlyCookie() throws Exception {
        resetServerFlags();
        customSetCookieHeader.set("PVEAuthCookie=PVE:root@pam:123::sig; Secure; Path=/");
        ServerConfig config = new ServerConfig("pdm-boundary", "PDM Boundary", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);
        PdmApiDriver driver = new PdmApiDriver(config);

        try {
            driver.authenticate("root", "password");
            fail("PDM must reject PVE-only cookie");
        } catch (ApiException e) {
            assertTrue(e.getMessage().contains("Failed to parse ticket response"));
        }
    }

    @Test
    public void testHttpsPveRejectsPdmOnlyCookie() throws Exception {
        resetServerFlags();
        customSetCookieHeader.set("__Host-PDMAuthCookie=PDM:root@pam:123::sig; Secure; Path=/");
        ServerConfig config = new ServerConfig("pve-boundary", "PVE Boundary", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PVE, mockFingerprint);
        PveApiDriver driver = new PveApiDriver(config);

        try {
            driver.authenticate("root", "password");
            fail("PVE must reject PDM-only cookie");
        } catch (ApiException e) {
            assertTrue(e.getMessage().contains("Failed to parse ticket response"));
        }
    }

    @Test
    public void testHttpsArbitraryAttributeIsNotCookie() throws Exception {
        resetServerFlags();
        customSetCookieHeader.set("unrelated=x; __Host-PDMAuthCookie=PDM:root@pam:123::sig; Secure; Path=/");
        ServerConfig config = new ServerConfig("pdm-attr", "PDM Attr", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);
        PdmApiDriver driver = new PdmApiDriver(config);

        try {
            driver.authenticate("root", "password");
            fail("Set-Cookie attribute must not be parsed as cookie");
        } catch (ApiException e) {
            assertTrue(e.getMessage().contains("Failed to parse ticket response"));
        }
    }

    @Test
    public void testHttpsCookieNamesAreCaseSensitive() throws Exception {
        resetServerFlags();
        customSetCookieHeader.set("__host-pdmauthcookie=PDM:root@pam:123::sig; Secure; Path=/");
        ServerConfig config = new ServerConfig("pdm-case", "PDM Case", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);
        PdmApiDriver driver = new PdmApiDriver(config);

        try {
            driver.authenticate("root", "password");
            fail("Wrong-case cookie name must fail closed");
        } catch (ApiException e) {
            assertTrue(e.getMessage().contains("Failed to parse ticket response"));
        }
    }

    @Test
    public void testHttpsPartialTfaCookieFailsClosed() throws Exception {
        resetServerFlags();
        customSetCookieHeader.set("__Host-PDMAuthCookie=PDM:!tfa!partial_challenge; Secure; Path=/");
        ServerConfig config = new ServerConfig("pdm-tfacookie", "PDM TFA Cookie", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);
        PdmApiDriver driver = new PdmApiDriver(config);

        try {
            driver.authenticate("root", "password");
            fail("Cookie-sourced TFA partial challenge must fail closed");
        } catch (ApiException e) {
            assertTrue(e.getMessage().toLowerCase().contains("two-factor") ||
                    e.getMessage().toLowerCase().contains("tfa"));
        }
    }

    @Test
    public void testHttpsEncodedDelimiterPreservesWireBytes() throws Exception {
        resetServerFlags();
        customSetCookieHeader.set("__Host-PDMAuthCookie=PDM:root@pam:123::sig%3BInjected%3Dvalue; Secure; Path=/");
        ServerConfig config = new ServerConfig("pdm-delim", "PDM Delim", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);
        PdmApiDriver driver = new PdmApiDriver(config);

        AuthCredentials creds = driver.authenticate("root", "password");
        assertNotNull(creds);
        assertFalse("Percent-encoded delimiter must not decode into semicolon",
                creds.getRawTicketValue().contains(";"));
        assertEquals("PDM:root@pam:123::sig%3BInjected%3Dvalue", creds.getRawTicketValue());
    }

    @Test
    public void testSeparateEndpointsNoAmbientCookiePersistence() throws Exception {
        resetServerFlags();
        assertNull("Global CookieHandler must NOT be installed", java.net.CookieHandler.getDefault());

        ServerConfig pdmConfig = new ServerConfig("pdm-ep1", "PDM Endpoint 1", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);
        PdmApiDriver driver = new PdmApiDriver(pdmConfig);
        AuthCredentials creds = driver.authenticate("root", "secretpassword");
        assertNotNull(creds);

        // A second client configured without authentication making a request must send zero cookies
        ServerConfig unauthConfig = new ServerConfig("pdm-ep2", "PDM Endpoint 2", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);
        ApiClient unauthClient = new ApiClient(unauthConfig);

        try {
            unauthClient.get("/remotes", null);
            fail("Unauthenticated request must not inherit ambient cookies from previous login");
        } catch (ApiException e) {
            assertEquals(401, e.getHttpCode());
        }
        assertNull("Global CookieHandler must remain null", java.net.CookieHandler.getDefault());
    }

    @Test
    public void testFollowupRejectsMissingOrWrongTicket() throws Exception {
        resetServerFlags();
        ServerConfig pdmConfig = new ServerConfig("pdm-test", "PDM Test", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);
        PdmApiDriver driver = new PdmApiDriver(pdmConfig);

        // Followup with wrong ticket
        AuthCredentials wrongTicketAuth = AuthCredentials.ticket("root@pam", "PDM:wrong_ticket_val",
                "csrf-upstream-pdm-token-999", System.currentTimeMillis() + 3600000);
        try {
            driver.fetchRemotes(wrongTicketAuth);
            fail("Followup with invalid ticket must be rejected by mock server with 401");
        } catch (ApiException e) {
            assertEquals(401, e.getHttpCode());
        }

        // Followup with wrong CSRF
        AuthCredentials wrongCsrfAuth = AuthCredentials.ticket("root@pam",
                "PDM:root@pam:67C65000::validUpstreamSignature123", "wrong_csrf",
                System.currentTimeMillis() + 3600000);
        try {
            driver.fetchRemotes(wrongCsrfAuth);
            fail("Followup with invalid CSRF must be rejected by mock server with 401");
        } catch (ApiException e) {
            assertEquals(401, e.getHttpCode());
        }
    }

    @Test
    public void testRedirectSinkRefusalAndNoCredentialArrival() throws Exception {
        resetServerFlags();
        ServerConfig config = new ServerConfig("pdm-redir", "PDM Redir", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);
        ApiClient client = new ApiClient(config);
        AuthCredentials auth = AuthCredentials.ticket("root@pam", "PDM:valid", "csrf", System.currentTimeMillis() + 3600000);

        try {
            client.get("/redirect_source", auth);
            fail("Redirect must be refused on credential-bearing request");
        } catch (ApiException e) {
            assertEquals(302, e.getHttpCode());
            assertTrue(e.getMessage().contains("redirect"));
        }

        // Verify that redirect sink never received credentials or requests
        assertEquals("Redirect sink must not receive forwarded credentials or requests",
                0, sinkRequestCount.get());
    }

    @Test
    public void testLoginHttp400DoesNotLeakReflectedSecret() throws Exception {
        resetServerFlags();
        String secret = "SYNTHETIC_LOGIN_PW_987654321";
        returnReflected400Login.set(secret);

        ServerConfig pdmConfig = new ServerConfig("pdm-sec", "PDM Sec", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);
        ApiClient client = new ApiClient(pdmConfig);

        try {
            client.authenticate("root@pam", secret);
            fail("Expected ApiException on 400 error");
        } catch (ApiException e) {
            assertEquals(400, e.getHttpCode());
            assertFalse("UI exception exposed response-reflected credential", e.getMessage().contains(secret));
            assertTrue("Expected safe fixed status description", e.getMessage().contains("HTTP 400: Bad Request"));
        }
    }

    @Test
    public void testLoginHttp302RedirectDoesNotLeakLocationSecretAndRefusesSink() throws Exception {
        resetServerFlags();
        String secret = "SYNTHETIC_LOGIN_REDIR_SECRET_12345";
        String sinkUrl = "https://127.0.0.1:" + mockPort + "/api2/json/redirect_sink?credential=" + secret;
        returnReflected302Login.set(sinkUrl);

        ServerConfig pdmConfig = new ServerConfig("pdm-sec", "PDM Sec", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);
        ApiClient client = new ApiClient(pdmConfig);

        try {
            client.authenticate("root@pam", "password");
            fail("Expected ApiException on 302 redirect");
        } catch (ApiException e) {
            assertEquals(302, e.getHttpCode());
            assertFalse("UI exception exposed response-reflected credential", e.getMessage().contains(secret));
            assertTrue("Expected redirect refusal message", e.getMessage().contains("redirect") && e.getMessage().contains("blocked"));
            assertEquals("Redirect sink must not receive forwarded requests", 0, sinkRequestCount.get());
        }
    }

    @Test
    public void testAuthenticatedTicketFollowupHttp400DoesNotLeakSecret() throws Exception {
        resetServerFlags();
        String secret = "SYNTHETIC_TICKET_SECRET_554433";
        returnReflected400Followup.set(secret);

        ServerConfig pdmConfig = new ServerConfig("pdm-sec", "PDM Sec", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);
        ApiClient client = new ApiClient(pdmConfig);
        AuthCredentials auth = AuthCredentials.ticket("root@pam", "PDM:valid_ticket", "csrf-upstream-pdm-token-999", System.currentTimeMillis() + 3600000);

        try {
            client.get("/remotes", auth);
            fail("Expected ApiException on 400 error");
        } catch (ApiException e) {
            assertEquals(400, e.getHttpCode());
            assertFalse("UI exception exposed response-reflected credential", e.getMessage().contains(secret));
            assertTrue("Expected safe fixed status description", e.getMessage().contains("HTTP 400: Bad Request"));
        }
    }

    @Test
    public void testAuthenticatedTicketFollowupHttp302RedirectDoesNotLeakLocationAndRefusesSink() throws Exception {
        resetServerFlags();
        String secret = "SYNTHETIC_TICKET_REDIR_667788";
        String sinkUrl = "https://127.0.0.1:" + mockPort + "/api2/json/redirect_sink?ticket=" + secret;
        returnReflected302Followup.set(sinkUrl);

        ServerConfig pdmConfig = new ServerConfig("pdm-sec", "PDM Sec", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);
        ApiClient client = new ApiClient(pdmConfig);
        AuthCredentials auth = AuthCredentials.ticket("root@pam", "PDM:valid_ticket", "csrf-upstream-pdm-token-999", System.currentTimeMillis() + 3600000);

        try {
            client.get("/remotes", auth);
            fail("Expected ApiException on 302 redirect");
        } catch (ApiException e) {
            assertEquals(302, e.getHttpCode());
            assertFalse("UI exception exposed response-reflected credential", e.getMessage().contains(secret));
            assertTrue("Expected redirect refusal message", e.getMessage().contains("redirect") && e.getMessage().contains("blocked"));
            assertEquals("Redirect sink must not receive forwarded requests", 0, sinkRequestCount.get());
        }
    }

    @Test
    public void testAuthenticatedTokenFollowupHttp400DoesNotLeakSecret() throws Exception {
        resetServerFlags();
        String secret = "SYNTHETIC_TOKEN_SECRET_998877";
        returnReflected400Followup.set(secret);

        ServerConfig pdmConfig = new ServerConfig("pdm-sec", "PDM Sec", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);
        ApiClient client = new ApiClient(pdmConfig);
        AuthCredentials auth = AuthCredentials.apiToken("PDMAPIToken=root@pam!token=" + secret);

        try {
            client.get("/remotes", auth);
            fail("Expected ApiException on 400 error");
        } catch (ApiException e) {
            assertEquals(400, e.getHttpCode());
            assertFalse("UI exception exposed response-reflected credential", e.getMessage().contains(secret));
            assertTrue("Expected safe fixed status description", e.getMessage().contains("HTTP 400: Bad Request"));
        }
    }

    @Test
    public void testAuthenticatedTokenFollowupHttp302RedirectDoesNotLeakLocationAndRefusesSink() throws Exception {
        resetServerFlags();
        String secret = "SYNTHETIC_TOKEN_REDIR_112233";
        String sinkUrl = "https://127.0.0.1:" + mockPort + "/api2/json/redirect_sink?token=" + secret;
        returnReflected302Followup.set(sinkUrl);

        ServerConfig pdmConfig = new ServerConfig("pdm-sec", "PDM Sec", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint);
        ApiClient client = new ApiClient(pdmConfig);
        AuthCredentials auth = AuthCredentials.apiToken("PDMAPIToken=root@pam!token=" + secret);

        try {
            client.get("/remotes", auth);
            fail("Expected ApiException on 302 redirect");
        } catch (ApiException e) {
            assertEquals(302, e.getHttpCode());
            assertFalse("UI exception exposed response-reflected credential", e.getMessage().contains(secret));
            assertTrue("Expected redirect refusal message", e.getMessage().contains("redirect") && e.getMessage().contains("blocked"));
            assertEquals("Redirect sink must not receive forwarded requests", 0, sinkRequestCount.get());
        }
    }

    @Test
    public void testPveApiDriverApiToken401PropagatesDeadlineAwareSessionInvalidation() throws Exception {
        ServerConfig cfg = new ServerConfig("test-pve", "PVE Test", "127.0.0.1", 8006,
                ServerConfig.ServerType.PVE, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        com.homelabpocket.security.CredentialStore store = new com.homelabpocket.security.CredentialStore(
                new android.content.Context() {
                    public android.content.SharedPreferences getSharedPreferences(String n, int m) {
                        return null;
                    }
                });
        SessionManager sm = new SessionManager(store);
        PveApiDriver driver = new PveApiDriver(cfg, sm);

        Class<?> actionClass = Class.forName("com.homelabpocket.api.PveApiDriver$RequestActionWithDeadline");
        java.util.concurrent.locks.ReentrantLock lock = sm.getSessionLockForTesting();
        java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);

        Object fake = java.lang.reflect.Proxy.newProxyInstance(actionClass.getClassLoader(), new Class<?>[]{actionClass}, (o, m, a) -> {
            Thread holder = new Thread(() -> {
                lock.lock();
                try {
                    held.countDown();
                    Thread.sleep(300);
                } catch (Exception ignored) {
                } finally {
                    lock.unlock();
                }
            });
            holder.start();
            held.await();
            throw new ApiException(401, "synthetic token 401");
        });

        java.lang.reflect.Method call = PveApiDriver.class.getDeclaredMethod(
                "executeWithAuthRetryWithDeadline", AuthCredentials.class, long.class, actionClass);
        call.setAccessible(true);

        long start = System.nanoTime();
        long deadlineNanos = start + 50_000_000L; // 50ms deadline
        try {
            call.invoke(driver, AuthCredentials.apiToken("PVEAPIToken=root@pam!token=synthetic"), deadlineNanos, fake);
            fail("Expected ApiException on token 401 with lock contention");
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            assertTrue("Expected ApiException cause", cause instanceof ApiException);
            ApiException apiEx = (ApiException) cause;
            assertTrue("Expected lock acquisition timeout or deadline exception, got: " + apiEx.getMessage(),
                    apiEx.getMessage().contains("SessionManager lock acquisition timed out") ||
                    apiEx.getMessage().contains("deadline") ||
                    apiEx.getMessage().contains("expired"));
        }

        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertTrue("Elapsed time (" + elapsedMs + "ms) must be bounded by deadline (~50ms) and not block for 300ms",
                elapsedMs < 200L);
    }
}
