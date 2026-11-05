// SPDX-License-Identifier: GPL-3.0-or-later
// PVE Pocket — Auth credentials (in-memory, never persisted as plaintext)
package com.homelabpocket.model;

/**
 * Holds authentication credentials in memory only.
 * This object is NOT serialized, NOT put in bundles or intents,
 * NOT logged, and NOT stored in SharedPreferences.
 *
 * Credentials come from CredentialStore (Keystore-backed encrypted storage)
 * and should be cleared from memory after use.
 *
 * Supports both standalone Proxmox VE (PVEAPIToken / PVEAuthCookie)
 * and Proxmox Datacenter Manager (PDMAPIToken / PDMAUTHCOOKIE).
 */
public class AuthCredentials {

    public enum AuthType {
        TICKET,     // Username/password → short-lived ticket (preferred)
        API_TOKEN   // API token (PVEAPIToken=... or PDMAPIToken=...)
    }

    public final AuthType authType;

    /** For TICKET auth: username (e.g. "root@pam") */
    public final String username;

    /**
     * For TICKET auth: the actual ticket cookie value (from /access/ticket response).
     * For API_TOKEN auth: the raw or prefixed token string.
     * NEVER log or bundle this value.
     */
    public final String secretValue;

    /**
     * For TICKET auth: CSRF prevention token (required for POST/PUT/DELETE).
     * Null for API_TOKEN auth.
     */
    public final String csrfToken;

    /** When this credential expires (epoch ms). -1 if no expiry (API tokens). */
    public final long expiresAtMs;

    private AuthCredentials(AuthType authType, String username,
                            String secretValue, String csrfToken,
                            long expiresAtMs) {
        this.authType = authType;
        this.username = username;
        this.secretValue = secretValue;
        this.csrfToken = csrfToken;
        this.expiresAtMs = expiresAtMs;
    }

    public static AuthCredentials ticket(String username, String ticket,
                                          String csrfToken, long expiresAtMs) {
        return new AuthCredentials(AuthType.TICKET, username, ticket, csrfToken, expiresAtMs);
    }

    public static AuthCredentials apiToken(String tokenString) {
        return new AuthCredentials(AuthType.API_TOKEN, null, tokenString, null, -1);
    }

    public boolean isExpired() {
        if (expiresAtMs < 0) return false;
        // Consider expired 60 seconds before actual expiry for safety margin
        return System.currentTimeMillis() > (expiresAtMs - 60_000L);
    }

    public boolean isApiToken() {
        return authType == AuthType.API_TOKEN;
    }

    /**
     * Returns the HTTP Authorization header value for API-token auth for PVE (default).
     */
    public String getAuthorizationHeader() {
        return getAuthorizationHeader(ServerConfig.ServerType.PVE);
    }

    /**
     * Returns the HTTP Authorization header value for API-token auth.
     * Automatically applies "PVEAPIToken=" or "PDMAPIToken=" prefix depending on server type.
     */
    public String getAuthorizationHeader(ServerConfig.ServerType type) {
        if (authType == AuthType.API_TOKEN) {
            if (secretValue == null) return null;
            String val = secretValue.trim();
            if (val.toLowerCase().startsWith("authorization:")) {
                val = val.substring(14).trim();
            }
            if (val.toLowerCase().startsWith("bearer ")) {
                val = val.substring(7).trim();
            }
            if ((val.startsWith("\"") && val.endsWith("\"")) || (val.startsWith("'") && val.endsWith("'"))) {
                val = val.substring(1, val.length() - 1).trim();
            }

            // Strip existing prefix if switching or normalizing
            String rawToken = val;
            if (rawToken.startsWith("PVEAPIToken=")) {
                rawToken = rawToken.substring(12).trim();
            } else if (rawToken.startsWith("PDMAPIToken=")) {
                rawToken = rawToken.substring(12).trim();
            } else if (rawToken.startsWith("PDMAPIToken ")) {
                rawToken = rawToken.substring(12).trim();
            } else if (rawToken.startsWith("PVEAPIToken ")) {
                rawToken = rawToken.substring(12).trim();
            }

            if (type == ServerConfig.ServerType.PDM) {
                return "PDMAPIToken=" + rawToken;
            } else {
                return "PVEAPIToken=" + rawToken;
            }
        }
        return null;
    }

    /**
     * Extracts and returns the raw ticket string without any cookie prefix.
     */
    public String getRawTicketValue() {
        if (secretValue == null) return null;
        String val = secretValue.trim();
        if (val.toLowerCase().startsWith("cookie:")) {
            val = val.substring(7).trim();
        }
        if (val.startsWith("__Host-PDMAuthCookie=")) {
            return val.substring(21).trim();
        } else if (val.startsWith("__Host-PVEAuthCookie=")) {
            return val.substring(21).trim();
        } else if (val.startsWith("PDMAuthCookie=")) {
            return val.substring(14).trim();
        } else if (val.startsWith("PDMAUTHCOOKIE=")) {
            return val.substring(14).trim();
        } else if (val.startsWith("PVEAuthCookie=")) {
            return val.substring(14).trim();
        } else if (val.startsWith("PDMCookie=")) {
            return val.substring(10).trim();
        }
        return val;
    }

    /** Returns the cookie value for ticket-based auth for PVE (default) */
    public String getTicketCookieValue() {
        return getTicketCookieValue(ServerConfig.ServerType.PVE);
    }

    /**
     * Returns the cookie value for ticket-based auth.
     * Applies exact upstream cookie prefix: "__Host-PDMAuthCookie=" (or "PDMAuthCookie=") for PDM,
     * "PVEAuthCookie=" for PVE.
     */
    public String getTicketCookieValue(ServerConfig.ServerType type) {
        if (authType == AuthType.TICKET) {
            String rawTicket = getRawTicketValue();
            if (rawTicket == null) return null;
            if (type == ServerConfig.ServerType.PDM) {
                if (secretValue != null && secretValue.trim().startsWith("PDMAuthCookie=")) {
                    return "PDMAuthCookie=" + rawTicket;
                }
                return "__Host-PDMAuthCookie=" + rawTicket;
            } else {
                return "PVEAuthCookie=" + rawTicket;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        // NEVER expose the secret in toString
        return "AuthCredentials{type=" + authType + ", user=" + username
                + ", expired=" + isExpired() + "}";
    }
}
