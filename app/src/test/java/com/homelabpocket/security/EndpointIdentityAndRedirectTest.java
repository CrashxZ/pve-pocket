// SPDX-License-Identifier: GPL-3.0-or-later
// Homelab Pocket — Genuine Endpoint Identity & Transport Redirect Security Tests
package com.homelabpocket.security;

import com.homelabpocket.api.ApiClient;
import com.homelabpocket.api.ApiException;
import com.homelabpocket.model.AuthCredentials;
import com.homelabpocket.model.ServerConfig;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.Principal;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSessionContext;

import static org.junit.Assert.*;

public class EndpointIdentityAndRedirectTest {

    private static KeyStore fixtureKeystore;
    private static X509Certificate localIpCert;
    private static String localIpFingerprint;

    private static X509Certificate dnsHostCert;
    private static String dnsHostFingerprint;

    private static X509Certificate wildcardCert;
    private static String wildcardFingerprint;

    private static X509Certificate wrongSanCert;
    private static String wrongSanFingerprint;

    private static X509Certificate noSanCert;
    private static String noSanFingerprint;

    private static X509Certificate sanCnConflictCert;
    private static String sanCnConflictFingerprint;

    private static X509Certificate wrongDerCert;

    // Local in-process HTTPS fixture server for transport redirect verification
    private static HttpsServer httpsServer;
    private static int serverPort;
    private static final AtomicInteger redirectHitCount = new AtomicInteger(0);

    @BeforeClass
    public static void setUpClass() throws Exception {
        // Load fixture keystore
        File ksFile = new File("app/src/test/resources/test_fixtures.p12");
        fixtureKeystore = KeyStore.getInstance("PKCS12");
        try (InputStream is = new FileInputStream(ksFile)) {
            fixtureKeystore.load(is, "testpass".toCharArray());
        }

        localIpCert = (X509Certificate) fixtureKeystore.getCertificate("local-ip");
        localIpFingerprint = PinnedTrustManager.computeFingerprint(localIpCert);

        dnsHostCert = (X509Certificate) fixtureKeystore.getCertificate("dns-host");
        dnsHostFingerprint = PinnedTrustManager.computeFingerprint(dnsHostCert);

        wildcardCert = (X509Certificate) fixtureKeystore.getCertificate("wildcard-host");
        wildcardFingerprint = PinnedTrustManager.computeFingerprint(wildcardCert);

        wrongSanCert = (X509Certificate) fixtureKeystore.getCertificate("wrong-san");
        wrongSanFingerprint = PinnedTrustManager.computeFingerprint(wrongSanCert);

        noSanCert = (X509Certificate) fixtureKeystore.getCertificate("no-san");
        noSanFingerprint = PinnedTrustManager.computeFingerprint(noSanCert);

        sanCnConflictCert = (X509Certificate) fixtureKeystore.getCertificate("san-cn-conflict");
        sanCnConflictFingerprint = PinnedTrustManager.computeFingerprint(sanCnConflictCert);

        // Load preserved review probe wrong.der
        File wrongDerFile = new File("reviews/20261104-0500/wrong.der");
        if (wrongDerFile.exists()) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            try (InputStream is = new ByteArrayInputStream(Files.readAllBytes(wrongDerFile.toPath()))) {
                wrongDerCert = (X509Certificate) cf.generateCertificate(is);
            }
        }

        // Initialize in-process HTTPS test server with local-ip certificate
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

        // Endpoint: 302 Redirect
        httpsServer.createContext("/api2/json/redirect-302", exchange -> {
            redirectHitCount.incrementAndGet();
            exchange.getResponseHeaders().set("Location", "https://evil.attacker.example/intercept-token");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        // Endpoint: 301 Redirect
        httpsServer.createContext("/api2/json/redirect-301", exchange -> {
            exchange.getResponseHeaders().set("Location", "https://evil.attacker.example/intercept-token");
            exchange.sendResponseHeaders(301, -1);
            exchange.close();
        });

        // Endpoint: 307 Redirect
        httpsServer.createContext("/api2/json/redirect-307", exchange -> {
            exchange.getResponseHeaders().set("Location", "https://evil.attacker.example/intercept-token");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });

        // Endpoint: 308 Redirect
        httpsServer.createContext("/api2/json/redirect-308", exchange -> {
            exchange.getResponseHeaders().set("Location", "https://evil.attacker.example/intercept-token");
            exchange.sendResponseHeaders(308, -1);
            exchange.close();
        });

        // Endpoint: 200 OK
        httpsServer.createContext("/api2/json/version", exchange -> {
            byte[] resp = "{\"data\":{\"version\":\"8.2.4\",\"release\":\"pve\"}}".getBytes("UTF-8");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
            exchange.close();
        });

        // Endpoint: Slow task response for bounded polling timeout test
        httpsServer.createContext("/api2/json/slow-task", exchange -> {
            try {
                Thread.sleep(400);
            } catch (InterruptedException ignored) {}
            byte[] resp = "{\"data\":{\"status\":\"running\"}}".getBytes("UTF-8");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
            exchange.close();
        });

        // Endpoint: Dripping response (1 byte every 100ms) to test total request deadline enforcement
        httpsServer.createContext("/api2/json/drip", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 0); // chunked encoding
                try (OutputStream os = exchange.getResponseBody()) {
                    for (int i = 0; i < 15; i++) {
                        os.write(' ');
                        os.flush();
                        Thread.sleep(100);
                    }
                    os.write("{\"data\":{}}".getBytes("UTF-8"));
                }
            } catch (Exception ignored) {}
            finally {
                exchange.close();
            }
        });

        // Endpoint: Silent body (200 OK headers immediately, then blocks without body)
        httpsServer.createContext("/api2/json/silent-body", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 0);
                Thread.sleep(3000);
            } catch (Exception ignored) {}
            finally {
                exchange.close();
            }
        });

        // Endpoint: Silent headers (server stalls before sending headers)
        httpsServer.createContext("/api2/json/silent-headers", exchange -> {
            try {
                Thread.sleep(3000);
                byte[] resp = "{}".getBytes("UTF-8");
                exchange.sendResponseHeaders(200, resp.length);
                exchange.getResponseBody().write(resp);
            } catch (Exception ignored) {}
            finally {
                exchange.close();
            }
        });

        // Endpoint: Oversized response (> 2MB)
        httpsServer.createContext("/api2/json/oversized", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 0);
                byte[] chunk = new byte[64 * 1024];
                java.util.Arrays.fill(chunk, (byte) 'a');
                try (OutputStream os = exchange.getResponseBody()) {
                    for (int i = 0; i < 48; i++) {
                        os.write(chunk);
                        os.flush();
                    }
                }
            } catch (Exception ignored) {}
            finally {
                exchange.close();
            }
        });

        httpsServer.start();
        serverPort = httpsServer.getAddress().getPort();
    }

    @AfterClass
    public static void tearDownClass() {
        if (httpsServer != null) {
            httpsServer.stop(0);
        }
    }

    // =========================================================================
    // Part 1: Real X509 Certificate Positive Identity Checks (SAN & CN)
    // =========================================================================

    @Test
    public void testRealCertIpSanMatch() {
        // Target is IP address 127.0.0.1; certificate has SAN:ip:127.0.0.1
        EndpointHostnameVerifier verifier = new EndpointHostnameVerifier("127.0.0.1", localIpFingerprint);
        SSLSession session = new MockSession("127.0.0.1", new Certificate[]{localIpCert});

        assertTrue("Valid IP SAN match must succeed", verifier.verify("127.0.0.1", session));
    }

    @Test
    public void testRealCertDnsSanMatch() {
        // Target is domain pve.example.com; certificate has SAN:dns:pve.example.com
        EndpointHostnameVerifier verifier = new EndpointHostnameVerifier("pve.example.com", dnsHostFingerprint);
        SSLSession session = new MockSession("pve.example.com", new Certificate[]{dnsHostCert});

        assertTrue("Valid DNS SAN match must succeed", verifier.verify("pve.example.com", session));
    }

    @Test
    public void testRealCertWildcardSanMatch() {
        // Target is sub.lab.example.com; certificate has SAN:dns:*.lab.example.com
        EndpointHostnameVerifier verifier = new EndpointHostnameVerifier("sub.lab.example.com", wildcardFingerprint);
        SSLSession session = new MockSession("sub.lab.example.com", new Certificate[]{wildcardCert});

        assertTrue("Single-label wildcard match must succeed", verifier.verify("sub.lab.example.com", session));
    }

    @Test
    public void testRealCertNoSanCnFallbackMatch() {
        // Certificate has NO SAN extension; fallback to CN=legacy.example.com
        EndpointHostnameVerifier verifier = new EndpointHostnameVerifier("legacy.example.com", noSanFingerprint);
        SSLSession session = new MockSession("legacy.example.com", new Certificate[]{noSanCert});

        assertTrue("CN fallback match when SAN absent must succeed", verifier.verify("legacy.example.com", session));
    }

    // =========================================================================
    // Part 2: Real X509 Certificate Negative Identity Checks (Bypass Prevention)
    // =========================================================================

    @Test
    public void testRealCertWrongSanRedProbeRejection() throws Exception {
        // Probe scenario: target is 192.0.2.100; certificate has SAN:dns:wrong.example.
        // Even if caller supplies matching fingerprint, RFC certificate identity check MUST reject it!
        X509Certificate probeCert = (wrongDerCert != null) ? wrongDerCert : wrongSanCert;
        String probeFingerprint = PinnedTrustManager.computeFingerprint(probeCert);

        EndpointHostnameVerifier verifier = new EndpointHostnameVerifier("192.0.2.100", probeFingerprint);
        SSLSession session = new MockSession("192.0.2.100", new Certificate[]{probeCert});

        assertFalse("Wrong SAN (dns:wrong.example) for IP target (192.0.2.100) must be strictly REJECTED",
                verifier.verify("192.0.2.100", session));
    }

    @Test
    public void testSanPrecedenceOverridesMatchingCn() {
        // RFC 6125 SAN precedence: Certificate has CN=192.0.2.100, but has SAN:dns:other.example.com.
        // Because SAN is present, CN MUST be ignored. For IP target 192.0.2.100, it must FAIL!
        EndpointHostnameVerifier verifier = new EndpointHostnameVerifier("192.0.2.100", sanCnConflictFingerprint);
        SSLSession session = new MockSession("192.0.2.100", new Certificate[]{sanCnConflictCert});

        assertFalse("SAN precedence must ignore CN when SAN is present without matching IP",
                verifier.verify("192.0.2.100", session));
    }

    @Test
    public void testRealCertWildcardRejectsMultiLevel() {
        // Target deep.sub.lab.example.com has 2 labels before .lab.example.com.
        // RFC 6125 prohibits wildcard matching across multiple domain levels.
        EndpointHostnameVerifier verifier = new EndpointHostnameVerifier("deep.sub.lab.example.com", wildcardFingerprint);
        SSLSession session = new MockSession("deep.sub.lab.example.com", new Certificate[]{wildcardCert});

        assertFalse("Wildcard *.lab.example.com must not match multi-level deep.sub.lab.example.com",
                verifier.verify("deep.sub.lab.example.com", session));
    }

    @Test
    public void testRealCertWildcardRejectsEmptyLabel() {
        // Target lab.example.com has no label before .lab.example.com.
        EndpointHostnameVerifier verifier = new EndpointHostnameVerifier("lab.example.com", wildcardFingerprint);
        SSLSession session = new MockSession("lab.example.com", new Certificate[]{wildcardCert});

        assertFalse("Wildcard *.lab.example.com must not match apex lab.example.com",
                verifier.verify("lab.example.com", session));
    }

    @Test
    public void testRealCertWrongDnsSanRejection() {
        // Target other.example.com; cert has SAN:dns:pve.example.com
        EndpointHostnameVerifier verifier = new EndpointHostnameVerifier("other.example.com", dnsHostFingerprint);
        SSLSession session = new MockSession("other.example.com", new Certificate[]{dnsHostCert});

        assertFalse("Mismatched DNS SAN must be rejected", verifier.verify("other.example.com", session));
    }

    @Test
    public void testEndpointHostnameVerifierRejectsMismatchedCallerHostname() {
        // verifier expects pve.example.com, but verify() is called with attacker.example.com
        EndpointHostnameVerifier verifier = new EndpointHostnameVerifier("pve.example.com", dnsHostFingerprint);
        SSLSession session = new MockSession("pve.example.com", new Certificate[]{dnsHostCert});

        assertFalse("Caller hostname disagreement must be rejected",
                verifier.verify("attacker.example.com", session));
    }

    @Test
    public void testEndpointHostnameVerifierRejectsMismatchedPeerHost() {
        // verifier expects pve.example.com, but session peerHost is attacker.example.com
        EndpointHostnameVerifier verifier = new EndpointHostnameVerifier("pve.example.com", dnsHostFingerprint);
        SSLSession session = new MockSession("attacker.example.com", new Certificate[]{dnsHostCert});

        assertFalse("Session peerHost disagreement must be rejected",
                verifier.verify("pve.example.com", session));
    }

    @Test
    public void testEndpointHostnameVerifierRejectsMismatchedCertificatePin() {
        // Host identity matches, but certificate fingerprint is completely wrong
        String wrongFp = "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99";
        EndpointHostnameVerifier verifier = new EndpointHostnameVerifier("127.0.0.1", wrongFp);
        SSLSession session = new MockSession("127.0.0.1", new Certificate[]{localIpCert});

        assertFalse("Mismatched certificate fingerprint must be rejected even when host matches",
                verifier.verify("127.0.0.1", session));
    }

    @Test
    public void testEndpointHostnameVerifierRejectsNullOrEmptyInputs() {
        EndpointHostnameVerifier verifier = new EndpointHostnameVerifier("127.0.0.1", localIpFingerprint);
        SSLSession session = new MockSession("127.0.0.1", new Certificate[]{localIpCert});

        assertFalse("Null hostname must return false", verifier.verify(null, session));
        assertFalse("Null session must return false", verifier.verify("127.0.0.1", null));

        try {
            new EndpointHostnameVerifier(null, localIpFingerprint);
            fail("Null host must throw IllegalArgumentException");
        } catch (IllegalArgumentException expected) {}

        try {
            new EndpointHostnameVerifier("127.0.0.1", "");
            fail("Empty fingerprint must throw IllegalArgumentException");
        } catch (IllegalArgumentException expected) {}
    }

    // =========================================================================
    // Part 3: Actual Transport Redirect Security Tests (Live HTTPS Transport)
    // =========================================================================

    @Test
    public void testActualTransportRedirect302IsBlockedWithoutCredentialForwarding() throws Exception {
        ServerConfig cfg = new ServerConfig("test-srv", "Local Test", "127.0.0.1", serverPort,
                ServerConfig.ServerType.PVE, localIpFingerprint);
        ApiClient client = new ApiClient(cfg);
        AuthCredentials creds = AuthCredentials.apiToken("PVEAPIToken=root@pam!token=SECRET_TOKEN_VALUE");

        int initialHits = redirectHitCount.get();
        try {
            client.get("/redirect-302", creds);
            fail("ApiClient must throw ApiException on HTTP 302 redirect");
        } catch (ApiException e) {
            assertEquals("HTTP status must be 302", 302, e.getHttpCode());
            assertTrue("Error message must disclose redirect blocked",
                    e.getMessage().contains("redirect") && e.getMessage().contains("blocked"));
        }

        // Verify only 1 request was made to the server (redirect was NOT followed)
        assertEquals("Server must have received exactly one request without following redirect",
                initialHits + 1, redirectHitCount.get());
    }

    @Test
    public void testActualTransportRedirect301IsBlocked() throws Exception {
        ServerConfig cfg = new ServerConfig("test-srv", "Local Test", "127.0.0.1", serverPort,
                ServerConfig.ServerType.PVE, localIpFingerprint);
        ApiClient client = new ApiClient(cfg);
        AuthCredentials creds = AuthCredentials.apiToken("PVEAPIToken=root@pam!token=SECRET_TOKEN_VALUE");

        try {
            client.get("/redirect-301", creds);
            fail("ApiClient must throw ApiException on HTTP 301 redirect");
        } catch (ApiException e) {
            assertEquals(301, e.getHttpCode());
            assertTrue(e.getMessage().contains("redirect") && e.getMessage().contains("blocked"));
        }
    }

    @Test
    public void testActualTransportRedirect307And308AreBlocked() throws Exception {
        ServerConfig cfg = new ServerConfig("test-srv", "Local Test", "127.0.0.1", serverPort,
                ServerConfig.ServerType.PVE, localIpFingerprint);
        ApiClient client = new ApiClient(cfg);
        AuthCredentials creds = AuthCredentials.apiToken("PVEAPIToken=root@pam!token=SECRET_TOKEN_VALUE");

        try {
            client.get("/redirect-307", creds);
            fail("Must block HTTP 307 redirect");
        } catch (ApiException e) {
            assertEquals(307, e.getHttpCode());
        }

        try {
            client.get("/redirect-308", creds);
            fail("Must block HTTP 308 redirect");
        } catch (ApiException e) {
            assertEquals(308, e.getHttpCode());
        }
    }

    @Test
    public void testActualTransportPostRedirectIsBlocked() throws Exception {
        ServerConfig cfg = new ServerConfig("test-srv", "Local Test", "127.0.0.1", serverPort,
                ServerConfig.ServerType.PVE, localIpFingerprint);
        ApiClient client = new ApiClient(cfg);
        AuthCredentials creds = AuthCredentials.apiToken("PVEAPIToken=root@pam!token=SECRET_TOKEN_VALUE");

        try {
            client.post("/redirect-302", "vmid=100&action=start", creds);
            fail("ApiClient POST must throw ApiException on HTTP 302 redirect");
        } catch (ApiException e) {
            assertEquals(302, e.getHttpCode());
            assertTrue(e.getMessage().contains("redirect") && e.getMessage().contains("blocked"));
        }
    }

    @Test
    public void testActualTransportSuccessfulHttpsRequest() throws Exception {
        ServerConfig cfg = new ServerConfig("test-srv", "Local Test", "127.0.0.1", serverPort,
                ServerConfig.ServerType.PVE, localIpFingerprint);
        ApiClient client = new ApiClient(cfg);
        AuthCredentials creds = AuthCredentials.apiToken("PVEAPIToken=root@pam!token=VALID");

        String resp = client.get("/version", creds);
        assertNotNull(resp);
        assertTrue("Response must contain version", resp.contains("\"version\":\"8.2.4\""));
    }

    @Test
    public void testActualTransportBoundedGetSuccess() throws Exception {
        ServerConfig cfg = new ServerConfig("test-srv", "Local Test", "127.0.0.1", serverPort,
                ServerConfig.ServerType.PVE, localIpFingerprint);
        ApiClient client = new ApiClient(cfg);
        AuthCredentials creds = AuthCredentials.apiToken("PVEAPIToken=root@pam!token=VALID");

        // Bounded get with ample budget (5000ms) against fast endpoint
        String resp = client.getBounded("/version", creds, 5000L);
        assertNotNull(resp);
        assertTrue(resp.contains("8.2.4"));
    }

    @Test
    public void testActualTransportBoundedTimeoutExceeded() throws Exception {
        ServerConfig cfg = new ServerConfig("test-srv", "Local Test", "127.0.0.1", serverPort,
                ServerConfig.ServerType.PVE, localIpFingerprint);
        ApiClient client = new ApiClient(cfg);
        AuthCredentials creds = AuthCredentials.apiToken("PVEAPIToken=root@pam!token=VALID");

        // Bounded get with tight budget (100ms) against slow endpoint (400ms delay)
        try {
            client.getBounded("/slow-task", creds, 100L);
            fail("Expected ApiException due to network socket timeout exceeding remaining budget");
        } catch (ApiException e) {
            assertTrue("Exception message should indicate network timeout: " + e.getMessage(),
                    e.getMessage().contains("timeout") || e.getMessage().contains("timed out") || e.getMessage().contains("deadline"));
        }
    }

    @Test
    public void testActualTransportDripBudgetTimeout() throws Exception {
        ServerConfig cfg = new ServerConfig("test-srv", "Local Test", "127.0.0.1", serverPort,
                ServerConfig.ServerType.PVE, localIpFingerprint);
        ApiClient client = new ApiClient(cfg);
        AuthCredentials creds = AuthCredentials.apiToken("PVEAPIToken=root@pam!token=VALID");

        long start = System.currentTimeMillis();
        try {
            client.getBounded("/drip", creds, 300L);
            fail("Expected ApiException due to total request deadline exceeded on dripping body");
        } catch (ApiException e) {
            long elapsed = System.currentTimeMillis() - start;
            assertTrue("Exception message should indicate total deadline: " + e.getMessage(),
                    e.getMessage().contains("deadline") || e.getMessage().contains("timed out") || e.getMessage().contains("timeout"));
            assertTrue("Total elapsed time (" + elapsed + "ms) must be bounded by deadline budget (< 900ms)",
                    elapsed < 900);
        }
    }

    @Test
    public void testActualTransportSilentBodyTimeout() throws Exception {
        ServerConfig cfg = new ServerConfig("test-srv", "Local Test", "127.0.0.1", serverPort,
                ServerConfig.ServerType.PVE, localIpFingerprint);
        ApiClient client = new ApiClient(cfg);
        AuthCredentials creds = AuthCredentials.apiToken("PVEAPIToken=root@pam!token=VALID");

        long start = System.currentTimeMillis();
        try {
            client.getBounded("/silent-body", creds, 300L);
            fail("Expected ApiException due to total request deadline exceeded on silent body");
        } catch (ApiException e) {
            long elapsed = System.currentTimeMillis() - start;
            assertTrue("Total elapsed time (" + elapsed + "ms) must be bounded (< 900ms)", elapsed < 900);
        }
    }

    @Test
    public void testActualTransportSilentHeadersTimeout() throws Exception {
        ServerConfig cfg = new ServerConfig("test-srv", "Local Test", "127.0.0.1", serverPort,
                ServerConfig.ServerType.PVE, localIpFingerprint);
        ApiClient client = new ApiClient(cfg);
        AuthCredentials creds = AuthCredentials.apiToken("PVEAPIToken=root@pam!token=VALID");

        long start = System.currentTimeMillis();
        try {
            client.getBounded("/silent-headers", creds, 300L);
            fail("Expected ApiException due to total request deadline exceeded on silent headers");
        } catch (ApiException e) {
            long elapsed = System.currentTimeMillis() - start;
            assertTrue("Total elapsed time (" + elapsed + "ms) must be bounded (< 900ms)", elapsed < 900);
        }
    }

    @Test
    public void testActualTransportExpiredBudgetFailsImmediately() throws Exception {
        ServerConfig cfg = new ServerConfig("test-srv", "Local Test", "127.0.0.1", serverPort,
                ServerConfig.ServerType.PVE, localIpFingerprint);
        ApiClient client = new ApiClient(cfg);
        AuthCredentials creds = AuthCredentials.apiToken("PVEAPIToken=root@pam!token=VALID");

        try {
            client.getBounded("/version", creds, 0L);
            fail("Expected ApiException for non-positive budget");
        } catch (ApiException e) {
            assertTrue(e.getMessage().contains("expired before dispatch"));
        }

        try {
            client.getBounded("/version", creds, -100L);
            fail("Expected ApiException for negative budget");
        } catch (ApiException e) {
            assertTrue(e.getMessage().contains("expired before dispatch"));
        }
    }

    @Test
    public void testActualTransportOversizedResponseBlocked() throws Exception {
        ServerConfig cfg = new ServerConfig("test-srv", "Local Test", "127.0.0.1", serverPort,
                ServerConfig.ServerType.PVE, localIpFingerprint);
        ApiClient client = new ApiClient(cfg);
        AuthCredentials creds = AuthCredentials.apiToken("PVEAPIToken=root@pam!token=VALID");

        try {
            client.getBounded("/oversized", creds, 5000L);
            fail("Expected ApiException for oversized response (> 2MB)");
        } catch (ApiException e) {
            assertTrue("Exception message should indicate size limit: " + e.getMessage(),
                    e.getMessage().contains("exceeded maximum allowed size"));
        }
    }

    // =========================================================================
    // Mock SSLSession for unit testing EndpointHostnameVerifier
    // =========================================================================
    private static class MockSession implements SSLSession {
        private final String peerHost;
        private final Certificate[] certs;

        MockSession(String peerHost, Certificate[] certs) {
            this.peerHost = peerHost;
            this.certs = certs;
        }

        @Override public byte[] getId() { return new byte[0]; }
        @Override public SSLSessionContext getSessionContext() { return null; }
        @Override public long getCreationTime() { return 0; }
        @Override public long getLastAccessedTime() { return 0; }
        @Override public void invalidate() {}
        @Override public boolean isValid() { return true; }
        @Override public void putValue(String s, Object o) {}
        @Override public Object getValue(String s) { return null; }
        @Override public void removeValue(String s) {}
        @Override public String[] getValueNames() { return new String[0]; }
        @Override public Certificate[] getPeerCertificates() throws SSLPeerUnverifiedException {
            if (certs == null || certs.length == 0) throw new SSLPeerUnverifiedException("No peer cert");
            return certs;
        }
        @Override public Certificate[] getLocalCertificates() { return new Certificate[0]; }
        @Override @SuppressWarnings({"deprecation", "removal"})
        public javax.security.cert.X509Certificate[] getPeerCertificateChain() { return new javax.security.cert.X509Certificate[0]; }
        @Override public Principal getPeerPrincipal() { return null; }
        @Override public Principal getLocalPrincipal() { return null; }
        @Override public String getCipherSuite() { return "TLS_AES_256_GCM_SHA384"; }
        @Override public String getProtocol() { return "TLSv1.3"; }
        @Override public String getPeerHost() { return peerHost; }
        @Override public int getPeerPort() { return 8006; }
        @Override public int getPacketBufferSize() { return 16384; }
        @Override public int getApplicationBufferSize() { return 16384; }
    }
}
