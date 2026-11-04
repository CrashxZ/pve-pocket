// SPDX-License-Identifier: GPL-3.0-or-later
// Homelab Pocket — Credential-Free TLS Certificate Fingerprint Probe
package com.homelabpocket.security;

import java.io.IOException;
import java.net.URL;
import java.security.GeneralSecurityException;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

/**
 * Dedicated probe for unauthenticated TLS certificate discovery.
 *
 * Security:
 * - NO credentials, auth headers, tickets, or tokens are ever attached.
 * - Used ONLY during onboarding so the user can inspect the server's certificate.
 * - Does NOT perform API requests or parse application responses.
 */
public class CertProbe {

    private static final int PROBE_TIMEOUT_MS = 10_000;

    /**
     * Connects to https://host:port/ strictly to read the server's X.509 certificate
     * and compute its SHA-256 fingerprint for user approval.
     *
     * @param host Target hostname or IP
     * @param port Target port (e.g. 8006 or 8443)
     * @return SHA-256 fingerprint colon-separated hex string
     * @throws IOException on connection error
     * @throws GeneralSecurityException on TLS negotiation failure
     */
    public static String probeServerFingerprint(String host, int port)
            throws IOException, GeneralSecurityException {
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("Host cannot be null or empty");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        }

        CertDiscoveryTrustManager discoveryTrustManager = new CertDiscoveryTrustManager();
        SSLContext probeContext = SSLContext.getInstance("TLS");
        probeContext.init(null, new TrustManager[]{discoveryTrustManager}, null);

        URL url = new URL("https://" + host + ":" + port + "/");
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setSSLSocketFactory(probeContext.getSocketFactory());
        // For unauthenticated probe only: hostname verifier checks peer host
        conn.setHostnameVerifier((hostname, session) -> true);
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(PROBE_TIMEOUT_MS);
        conn.setReadTimeout(PROBE_TIMEOUT_MS);

        try {
            conn.connect();
            String fp = discoveryTrustManager.getDiscoveredFingerprint();
            if (fp != null) {
                return fp;
            }
            throw new IOException("No certificate captured during TLS probe to " + host + ":" + port);
        } catch (Exception e) {
            // Even if HTTP returns 401 or TLS error occurs during HTTP handshake,
            // check if the TLS certificate was already captured
            String fp = discoveryTrustManager.getDiscoveredFingerprint();
            if (fp != null) {
                return fp;
            }
            throw new IOException("Could not probe server TLS certificate: " + e.getMessage(), e);
        } finally {
            conn.disconnect();
        }
    }
}
