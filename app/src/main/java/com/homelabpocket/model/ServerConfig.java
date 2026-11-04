// SPDX-License-Identifier: GPL-3.0-or-later
// Homelab Pocket — Server connection configuration model
package com.homelabpocket.model;

/**
 * Represents a configured server connection (PVE node or PDM instance).
 * Credentials are NOT stored here — they are retrieved from CredentialStore.
 * This model holds only non-secret configuration.
 */
public class ServerConfig {

    public enum ServerType {
        PVE,  // Proxmox VE standalone node
        PDM   // Proxmox Datacenter Manager
    }

    /** Unique ID for this server config (UUID string) */
    public final String id;

    /** Display name set by user */
    public final String name;

    /** Hostname or IP address */
    public final String host;

    /** Port (8006 for PVE, 8443 for PDM) */
    public final int port;

    /** Server type */
    public final ServerType type;

    /**
     * SHA-256 fingerprint of server TLS certificate, hex-encoded.
     * Format: "AA:BB:CC:..." (colon-separated pairs).
     * Null = not yet pinned (first-connect flow required).
     * Empty string = intentionally cleared (will re-prompt).
     */
    public final String pinnedCertFingerprint;

    /** Whether to verify pinned fingerprint (should always be true in production) */
    public final boolean enforceCertPin;

    /**
     * Whether to explicitly skip TLS verification for this server (default false / OFF).
     * When true, this specific server bypasses certificate pinning, hostname validation,
     * and onboarding fingerprint approval. NEVER global.
     */
    public final boolean skipTlsVerify;

    public ServerConfig(String id, String name, String host, int port,
                        ServerType type, String pinnedCertFingerprint,
                        boolean enforceCertPin, boolean skipTlsVerify) {
        this.id = id;
        this.name = name;
        this.host = host;
        this.port = port;
        this.type = type;
        this.pinnedCertFingerprint = pinnedCertFingerprint;
        this.enforceCertPin = enforceCertPin;
        this.skipTlsVerify = skipTlsVerify;
    }

    public ServerConfig(String id, String name, String host, int port,
                        ServerType type, String pinnedCertFingerprint,
                        boolean enforceCertPin) {
        this(id, name, host, port, type, pinnedCertFingerprint, enforceCertPin, false);
    }

    public ServerConfig(String id, String name, String host, int port,
                        ServerType type, String pinnedCertFingerprint) {
        this(id, name, host, port, type, pinnedCertFingerprint, true, false);
    }

    public String getBaseUrl() {
        return "https://" + host + ":" + port;
    }

    public String getApiBaseUrl() {
        return getBaseUrl() + "/api2/json";
    }

    /**
     * Checks if this server configuration represents a legacy demo fixture entry.
     * Narrowly matches exact legacy demo identifiers (id "DEMO", host "demo", or exact legacy demo names)
     * without suppressing legitimate user servers containing "DEMO" in their name (e.g. "Demonstration Lab").
     */
    public boolean isLegacyDemo() {
        return isLegacyDemo(this);
    }

    public static boolean isLegacyDemo(ServerConfig server) {
        if (server == null) return false;
        if ("DEMO".equalsIgnoreCase(server.id)) return true;
        if ("demo".equalsIgnoreCase(server.host)) return true;
        if (server.name != null) {
            String trimmed = server.name.trim();
            if ("DEMO".equalsIgnoreCase(trimmed)
                    || "Demo Server".equalsIgnoreCase(trimmed)
                    || "⚠ DEMO MODE — Not real data".equalsIgnoreCase(trimmed)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return name + " (" + type + " @ " + host + ":" + port + ")";
    }
}
