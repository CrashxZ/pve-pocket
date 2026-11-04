// SPDX-License-Identifier: GPL-3.0-or-later
// Homelab Pocket — TLS certificate pinning (per-server SHA-256 fingerprint)
package com.homelabpocket.security;

import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.X509TrustManager;

/**
 * Per-server certificate fingerprint pinning TrustManager.
 *
 * Security model:
 * - On first connect to a new server, the user is shown the cert fingerprint and
 *   must explicitly approve it before it is stored (TOFU - Trust On First Use).
 * - On subsequent connects, the pinned fingerprint is compared against the server cert.
 * - If they differ, or if NO approved fingerprint is configured, the connection is
 *   FAIL-CLOSED and REJECTED.
 * - There is NO global trust-all override and NO hostname bypass.
 * - ApiClient and credential transport MUST NEVER use a null or blank pin.
 */
public class PinnedTrustManager implements X509TrustManager {

    /** Expected SHA-256 fingerprint in hex (with or without colons) */
    private final String expectedFingerprint;

    private String lastSeenFingerprint = null;

    /**
     * Creates a PinnedTrustManager with the specified SHA-256 fingerprint.
     * @param expectedFingerprint The pinned SHA-256 fingerprint.
     *                            MUST be a valid SHA-256 hex string (64 hex characters).
     *                            If null, blank, or malformed, checkServerTrusted will fail-closed.
     */
    public PinnedTrustManager(String expectedFingerprint) {
        this.expectedFingerprint = expectedFingerprint;
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType)
            throws CertificateException {
        // Not used for client mode
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType)
            throws CertificateException {
        if (chain == null || chain.length == 0) {
            throw new CertificateException("Empty certificate chain presented by server");
        }

        if (expectedFingerprint == null || expectedFingerprint.trim().isEmpty()) {
            throw new CertificateException("No pinned certificate fingerprint configured; refusing unapproved server certificate (fail-closed).");
        }

        String normalizedExpected = normalize(expectedFingerprint);
        if (!isValidNormalizedFingerprint(normalizedExpected)) {
            throw new CertificateException("Malformed pinned certificate fingerprint: " + expectedFingerprint);
        }

        X509Certificate serverCert = chain[0];
        String actualFingerprint = computeFingerprint(serverCert);
        String normalizedActual = normalize(actualFingerprint);

        if (!normalizedActual.equalsIgnoreCase(normalizedExpected)) {
            throw new CertificateException(
                "Certificate fingerprint mismatch!\n" +
                "Expected: " + expectedFingerprint + "\n" +
                "Got:      " + actualFingerprint + "\n" +
                "Connection rejected. The server certificate may have changed. " +
                "Please verify with the server administrator before re-pinning."
            );
        }

        lastSeenFingerprint = actualFingerprint;
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
    }

    /** Call after a successful connection to get the actual cert fingerprint */
    public String getLastSeenFingerprint() {
        return lastSeenFingerprint;
    }

    /**
     * Computes SHA-256 fingerprint of an X.509 certificate.
     * @return colon-separated hex string
     */
    public static String computeFingerprint(X509Certificate cert) throws CertificateException {
        if (cert == null) {
            throw new CertificateException("Cannot compute fingerprint for null certificate");
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(cert.getEncoded());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < digest.length; i++) {
                if (i > 0) sb.append(':');
                sb.append(String.format("%02X", digest[i] & 0xFF));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new CertificateException("Failed to compute fingerprint: " + e.getMessage(), e);
        }
    }

    public static boolean matches(String actual, String expected) {
        if (actual == null || expected == null) return false;
        String nActual = normalize(actual);
        String nExpected = normalize(expected);
        if (!isValidNormalizedFingerprint(nActual) || !isValidNormalizedFingerprint(nExpected)) {
            return false;
        }
        return nActual.equalsIgnoreCase(nExpected);
    }

    public static boolean isValidFingerprint(String fp) {
        if (fp == null) return false;
        String n = normalize(fp);
        return isValidNormalizedFingerprint(n);
    }

    private static boolean isValidNormalizedFingerprint(String n) {
        return n != null && n.matches("^[0-9A-Fa-f]{64}$");
    }

    public static String normalize(String fp) {
        if (fp == null) return "";
        return fp.replace(":", "").trim().toUpperCase();
    }
}
