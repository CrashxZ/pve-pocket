// SPDX-License-Identifier: GPL-3.0-or-later
// Homelab Pocket — Credential-free TLS Certificate Discovery TrustManager
package com.homelabpocket.security;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.X509TrustManager;

/**
 * TrustManager used STRICTLY and EXCLUSIVELY for credential-free first-connect
 * TLS certificate discovery during onboarding.
 *
 * This manager NEVER handles credentials, tokens, cookies, or API calls.
 * It exists solely to capture the certificate presented by the endpoint
 * so the user can inspect and explicitly approve its SHA-256 fingerprint.
 */
public class CertDiscoveryTrustManager implements X509TrustManager {

    private String discoveredFingerprint = null;
    private X509Certificate discoveredCertificate = null;

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) {
        // Not used for client mode
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType)
            throws CertificateException {
        if (chain == null || chain.length == 0) {
            throw new CertificateException("Empty certificate chain presented during discovery");
        }
        this.discoveredCertificate = chain[0];
        this.discoveredFingerprint = PinnedTrustManager.computeFingerprint(chain[0]);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
    }

    /** Returns the SHA-256 fingerprint discovered during unauthenticated probe */
    public String getDiscoveredFingerprint() {
        return discoveredFingerprint;
    }

    public X509Certificate getDiscoveredCertificate() {
        return discoveredCertificate;
    }
}
