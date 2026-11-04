// SPDX-License-Identifier: GPL-3.0-or-later
package com.homelabpocket.security;

import com.homelabpocket.model.AuthCredentials;
import com.homelabpocket.util.JsonUtil;

import org.junit.Test;

import java.math.BigInteger;
import java.security.Principal;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.Set;

import static org.junit.Assert.*;

public class SecurityAndRedactionTest {

    @Test
    public void testCredentialRedactionInLogs() {
        String logWithCookie = "Request sent with Cookie: PVEAuthCookie=PVE:root@pam:4B25E...; and Host: pve.local";
        String sanitized1 = JsonUtil.sanitizeForLog(logWithCookie);
        assertFalse(sanitized1.contains("4B25E"));
        assertTrue(sanitized1.contains("PVEAuthCookie=REDACTED"));

        String logWithToken = "Authorization: PVEAPIToken=root@pam!token1=11223344-5566-7788-99aa-bbccddeeff00";
        String sanitized2 = JsonUtil.sanitizeForLog(logWithToken);
        assertFalse(sanitized2.contains("11223344-5566"));
        assertTrue(sanitized2.contains("PVEAPIToken=REDACTED"));

        String logWithCsrf = "Headers: CSRFPreventionToken:4B25E123:SomeRandomCsrfToken\r\n";
        String sanitized3 = JsonUtil.sanitizeForLog(logWithCsrf);
        assertFalse(sanitized3.contains("SomeRandomCsrfToken"));
        assertTrue(sanitized3.contains("CSRFPreventionToken:REDACTED"));

        String logWithJsonTicket = "{\"data\":{\"ticket\":\"PVE:root@pam:ABCDEF123456\",\"CSRFPreventionToken\":\"XYZ\"}}";
        String sanitized4 = JsonUtil.sanitizeForLog(logWithJsonTicket);
        assertFalse(sanitized4.contains("ABCDEF123456"));
        assertTrue(sanitized4.contains("\"ticket\":\"REDACTED\""));

        String logWithJsonPassword = "{\"username\":\"admin\",\"password\":\"supersecret123\"}";
        String sanitized5 = JsonUtil.sanitizeForLog(logWithJsonPassword);
        assertFalse(sanitized5.contains("supersecret123"));
        assertTrue(sanitized5.contains("\"password\":\"REDACTED\""));
    }

    @Test
    public void testAuthCredentialsToStringDoesNotLeakSecrets() {
        AuthCredentials ticketAuth = AuthCredentials.ticket(
                "root@pam", "SUPER_SECRET_PVE_TICKET_VALUE", "CSRF_TOKEN_SECRET", System.currentTimeMillis() + 3600000);

        String strTicket = ticketAuth.toString();
        assertFalse("toString must not contain ticket", strTicket.contains("SUPER_SECRET_PVE_TICKET_VALUE"));
        assertFalse("toString must not contain CSRF token", strTicket.contains("CSRF_TOKEN_SECRET"));
        assertTrue(strTicket.contains("root@pam"));

        AuthCredentials tokenAuth = AuthCredentials.apiToken(
                "PVEAPIToken=root@pam!app=11223344-5566-7788-99aa-bbccddeeff00");
        String strToken = tokenAuth.toString();
        assertFalse("toString must not contain API token secret", strToken.contains("11223344-5566"));
    }

    @Test
    public void testPinnedTrustManagerSuccessAndRejection() throws Exception {
        byte[] certBytes = "MOCK_CERTIFICATE_PAYLOAD_FOR_TESTING".getBytes("UTF-8");
        X509Certificate mockCert = new TestX509Cert(certBytes);

        String validFingerprint = PinnedTrustManager.computeFingerprint(mockCert);
        assertNotNull(validFingerprint);
        assertTrue(validFingerprint.contains(":"));

        // Test matching fingerprint passes
        PinnedTrustManager matchingManager = new PinnedTrustManager(validFingerprint);
        matchingManager.checkServerTrusted(new X509Certificate[]{mockCert}, "RSA");
        assertEquals(validFingerprint, matchingManager.getLastSeenFingerprint());

        // Test normalized (lowercase, no colons) matches too
        PinnedTrustManager lowercaseManager = new PinnedTrustManager(validFingerprint.replace(":", "").toLowerCase());
        lowercaseManager.checkServerTrusted(new X509Certificate[]{mockCert}, "RSA");

        // Test changed fingerprint fails with CertificateException
        String changedFingerprint = "00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF";
        PinnedTrustManager rejectingManager = new PinnedTrustManager(changedFingerprint);
        try {
            rejectingManager.checkServerTrusted(new X509Certificate[]{mockCert}, "RSA");
            fail("Expected CertificateException on fingerprint mismatch");
        } catch (CertificateException e) {
            assertTrue(e.getMessage().contains("mismatch"));
            assertTrue(e.getMessage().contains("rejected"));
        }

        // Test empty cert chain fails
        try {
            matchingManager.checkServerTrusted(new X509Certificate[0], "RSA");
            fail("Expected CertificateException on empty chain");
        } catch (CertificateException e) {
            assertTrue(e.getMessage().contains("Empty"));
        }
    }

    // Minimal dummy X509Certificate for testing fingerprint hashing
    @SuppressWarnings("deprecation")
    public static class TestX509Cert extends X509Certificate {
        private final byte[] encoded;
        public TestX509Cert(byte[] encoded) { this.encoded = encoded; }
        @Override public byte[] getEncoded() { return encoded; }
        @Override public void checkValidity() {}
        @Override public void checkValidity(Date date) {}
        @Override public int getVersion() { return 3; }
        @Override public BigInteger getSerialNumber() { return BigInteger.ONE; }
        @Override public Principal getIssuerDN() { return null; }
        @Override public Principal getSubjectDN() { return null; }
        @Override public Date getNotBefore() { return new Date(); }
        @Override public Date getNotAfter() { return new Date(); }
        @Override public byte[] getTBSCertificate() { return new byte[0]; }
        @Override public byte[] getSignature() { return new byte[0]; }
        @Override public String getSigAlgName() { return "SHA256withRSA"; }
        @Override public String getSigAlgOID() { return "1.2.840.113549.1.1.11"; }
        @Override public byte[] getSigAlgParams() { return new byte[0]; }
        @Override public boolean[] getIssuerUniqueID() { return new boolean[0]; }
        @Override public boolean[] getSubjectUniqueID() { return new boolean[0]; }
        @Override public boolean[] getKeyUsage() { return new boolean[0]; }
        @Override public int getBasicConstraints() { return -1; }
        @Override public void verify(PublicKey key) {}
        @Override public void verify(PublicKey key, String sigProvider) {}
        @Override public String toString() { return "TestX509Cert"; }
        @Override public PublicKey getPublicKey() { return null; }
        @Override public boolean hasUnsupportedCriticalExtension() { return false; }
        @Override public Set<String> getCriticalExtensionOIDs() { return null; }
        @Override public Set<String> getNonCriticalExtensionOIDs() { return null; }
        @Override public byte[] getExtensionValue(String oid) { return new byte[0]; }
    }
}
