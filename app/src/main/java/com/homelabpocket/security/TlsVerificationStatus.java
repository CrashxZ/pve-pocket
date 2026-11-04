// SPDX-License-Identifier: GPL-3.0-or-later
package com.homelabpocket.security;

import com.homelabpocket.model.ServerConfig;

/**
 * Production status and verification decision engine for server TLS configuration.
 *
 * Guarantees:
 * 1. If skipTlsVerify is true, ALWAYS reports DISABLED_INSECURE with prominent warning,
 *    even if an old or existing pinned fingerprint is present.
 * 2. If skipTlsVerify is false and a valid pinned fingerprint exists, reports PINNED_VERIFIED.
 * 3. If skipTlsVerify is false and no fingerprint exists, reports PENDING_VERIFICATION.
 */
public class TlsVerificationStatus {

    public enum StatusLevel {
        DISABLED_INSECURE,
        PINNED_VERIFIED,
        PENDING_VERIFICATION
    }

    public final StatusLevel level;
    public final String displayText;
    public final boolean isWarning;
    public final boolean isSecure;

    public TlsVerificationStatus(StatusLevel level, String displayText, boolean isWarning, boolean isSecure) {
        this.level = level;
        this.displayText = displayText;
        this.isWarning = isWarning;
        this.isSecure = isSecure;
    }

    public static TlsVerificationStatus evaluate(ServerConfig server) {
        if (server == null) {
            return new TlsVerificationStatus(
                    StatusLevel.PENDING_VERIFICATION,
                    "TLS Fingerprint: Pending verification",
                    true,
                    false
            );
        }

        // Rule 1: skipTlsVerify ALWAYS overrides any present fingerprint
        if (server.skipTlsVerify) {
            return new TlsVerificationStatus(
                    StatusLevel.DISABLED_INSECURE,
                    "⚠️ TLS VERIFICATION DISABLED (Insecure)",
                    true,
                    false
            );
        }

        // Rule 2: Pinned fingerprint verified
        if (server.pinnedCertFingerprint != null && !server.pinnedCertFingerprint.trim().isEmpty()) {
            String shortFp = server.pinnedCertFingerprint.trim();
            if (shortFp.length() > 17) {
                shortFp = shortFp.substring(0, 17) + "…";
            }
            return new TlsVerificationStatus(
                    StatusLevel.PINNED_VERIFIED,
                    "Pinned TLS: " + shortFp,
                    false,
                    true
            );
        }

        // Rule 3: Pending verification
        return new TlsVerificationStatus(
                StatusLevel.PENDING_VERIFICATION,
                "TLS Fingerprint: Pending verification",
                true,
                false
        );
    }
}
