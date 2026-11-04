// SPDX-License-Identifier: GPL-3.0-or-later
// Homelab Pocket — Tests for Missing Pin Fail-Closed Transport and Offline Refusal
package com.homelabpocket.security;

import com.homelabpocket.api.ApiClient;
import com.homelabpocket.api.SessionManager;
import com.homelabpocket.model.AuthCredentials;
import com.homelabpocket.model.ServerConfig;

import org.junit.Before;
import org.junit.Test;

import java.security.GeneralSecurityException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import static org.junit.Assert.*;

public class MissingPinTransportTest {

    private byte[] certBytes;
    private X509Certificate testCert;
    private String validFingerprint;

    @Before
    public void setUp() throws Exception {
        certBytes = "TEST_CERTIFICATE_PAYLOAD_FOR_FAIL_CLOSED_CHECK".getBytes("UTF-8");
        testCert = new SecurityAndRedactionTest.TestX509Cert(certBytes);
        validFingerprint = PinnedTrustManager.computeFingerprint(testCert);
    }

    @Test
    public void testPinnedTrustManagerRejectsNullPinFailClosed() {
        PinnedTrustManager tm = new PinnedTrustManager(null);
        try {
            tm.checkServerTrusted(new X509Certificate[]{testCert}, "RSA");
            fail("PinnedTrustManager(null) must fail-closed and throw CertificateException");
        } catch (CertificateException expected) {
            assertTrue(expected.getMessage().contains("fail-closed") || expected.getMessage().contains("No pinned"));
        }
    }

    @Test
    public void testPinnedTrustManagerRejectsEmptyOrBlankPin() {
        try {
            new PinnedTrustManager("").checkServerTrusted(new X509Certificate[]{testCert}, "RSA");
            fail("Empty pin must be rejected");
        } catch (CertificateException expected) {
            // Success
        }

        try {
            new PinnedTrustManager("   ").checkServerTrusted(new X509Certificate[]{testCert}, "RSA");
            fail("Blank pin must be rejected");
        } catch (CertificateException expected) {
            // Success
        }
    }

    @Test
    public void testPinnedTrustManagerRejectsMalformedPin() {
        try {
            new PinnedTrustManager("NOT_A_VALID_HEX_SHA256").checkServerTrusted(new X509Certificate[]{testCert}, "RSA");
            fail("Malformed pin must be rejected");
        } catch (CertificateException expected) {
            assertTrue(expected.getMessage().contains("Malformed") || expected.getMessage().contains("fingerprint"));
        }
    }

    @Test
    public void testPinnedTrustManagerAcceptsMatchingFingerprint() throws Exception {
        PinnedTrustManager tm = new PinnedTrustManager(validFingerprint);
        tm.checkServerTrusted(new X509Certificate[]{testCert}, "RSA");
        assertEquals(validFingerprint, tm.getLastSeenFingerprint());
    }

    @Test
    public void testPinnedTrustManagerRejectsMismatchedFingerprint() {
        String otherFp = "11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00";
        PinnedTrustManager tm = new PinnedTrustManager(otherFp);
        try {
            tm.checkServerTrusted(new X509Certificate[]{testCert}, "RSA");
            fail("Mismatched fingerprint must throw CertificateException");
        } catch (CertificateException expected) {
            assertTrue(expected.getMessage().contains("mismatch"));
        }
    }

    @Test
    public void testApiClientRefusesInstantiationWhenPinIsMissing() {
        ServerConfig unpinnedConfig = new ServerConfig("s1", "Unpinned Host", "192.0.2.1", 8006,
                ServerConfig.ServerType.PVE, null);

        try {
            new ApiClient(unpinnedConfig);
            fail("ApiClient must refuse connection setup when pinnedCertFingerprint is null");
        } catch (GeneralSecurityException expected) {
            assertTrue(expected.getMessage().contains("no approved TLS certificate fingerprint pinned"));
        }
    }

    @Test
    public void testApiClientRefusesInstantiationWhenPinIsBlankOrMalformed() {
        ServerConfig blankConfig = new ServerConfig("s2", "Blank Host", "192.0.2.1", 8006,
                ServerConfig.ServerType.PVE, "   ");
        try {
            new ApiClient(blankConfig);
            fail("ApiClient must refuse blank pin");
        } catch (GeneralSecurityException expected) {
            // Success
        }

        ServerConfig malformedConfig = new ServerConfig("s3", "Malformed Host", "192.0.2.1", 8006,
                ServerConfig.ServerType.PVE, "xyz123");
        try {
            new ApiClient(malformedConfig);
            fail("ApiClient must refuse malformed pin");
        } catch (GeneralSecurityException expected) {
            // Success
        }
    }

    @Test
    public void testSessionManagerRefusesCredentialDispatchWhenPinIsMissing() throws Exception {
        SessionManager sessionManager = new SessionManager(null);
        ServerConfig offlineSavedDraft = new ServerConfig("s4", "Offline Draft", "192.0.2.20", 8006,
                ServerConfig.ServerType.PVE, null);

        try {
            sessionManager.getCredentials(offlineSavedDraft);
            fail("SessionManager must refuse credential dispatch for unpinned servers");
        } catch (GeneralSecurityException expected) {
            assertTrue(expected.getMessage().contains("Refusing credential dispatch"));
            assertTrue(expected.getMessage().contains("no approved pinned TLS certificate"));
        }
    }

    @Test
    public void testOfflineSaveThenReconnectRefusalLifecycle() {
        // Step 1: User saves server during network outage (pin is null)
        ServerConfig offlineConfig = new ServerConfig("s5", "Offline Saved PVE", "192.0.2.30", 8006,
                ServerConfig.ServerType.PVE, null);
        assertNull("Offline draft has no pinned fingerprint", offlineConfig.pinnedCertFingerprint);

        // Step 2: Attempting to create ApiClient must immediately throw
        try {
            new ApiClient(offlineConfig);
            fail("ApiClient must fail-closed on offline draft");
        } catch (GeneralSecurityException expected) {
            // Pass: network authentication refused
        }

        // Step 3: Once user verifies and pins the certificate, ApiClient is permitted
        ServerConfig verifiedConfig = new ServerConfig("s5", "Offline Saved PVE", "192.0.2.30", 8006,
                ServerConfig.ServerType.PVE, validFingerprint);
        try {
            ApiClient client = new ApiClient(verifiedConfig);
            assertNotNull("Verified config successfully configures ApiClient", client);
            assertEquals("192.0.2.30", client.getConfig().host);
        } catch (GeneralSecurityException e) {
            fail("Verified configuration must succeed: " + e.getMessage());
        }
    }
}
