// SPDX-License-Identifier: GPL-3.0-or-later
// Homelab Pocket — Session manager (in-memory auth tokens + refresh logic)
package com.homelabpocket.api;

import com.homelabpocket.model.AuthCredentials;
import com.homelabpocket.model.ServerConfig;
import com.homelabpocket.security.CredentialStore;
import com.homelabpocket.security.PinnedTrustManager;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages per-server authentication sessions (in-memory tickets).
 *
 * Security:
 * - Refuses credential dispatch if server has no approved pinned TLS certificate (fail-closed).
 * - Tickets are short-lived and stored only in memory.
 * - Password used only to obtain ticket and never persisted in memory or logs.
 */
public class SessionManager {

    /**
     * Interface for persisting session lock state across process restarts.
     */
    public interface LockStateStore {
        boolean isSessionLocked();
        boolean setSessionLocked(boolean locked);
    }

    private final Map<String, AuthCredentials> activeSessions = new HashMap<>();
    private final CredentialStore credentialStore;
    private final LockStateStore lockStateStore;
    private volatile boolean locked = false;

    public SessionManager(CredentialStore credentialStore) {
        this(credentialStore, credentialStore instanceof LockStateStore ? (LockStateStore) credentialStore : null);
    }

    public SessionManager(CredentialStore credentialStore, LockStateStore lockStateStore) {
        this.credentialStore = credentialStore;
        this.lockStateStore = lockStateStore;
        if (lockStateStore != null) {
            this.locked = lockStateStore.isSessionLocked();
        } else {
            this.locked = false;
        }
    }

    /**
     * Returns true if the session is currently in explicit locked state.
     */
    public synchronized boolean isLocked() {
        return locked;
    }

    /**
     * Locks the session: clears all in-memory tickets and enters explicit locked state.
     * While locked, getCredentials() refuses to access cached sessions, stored credentials,
     * or network authentication, returning null.
     *
     * @return true if durable storage lock persistence succeeded, false if persistence failed
     *         (note: in-process lock is latched regardless).
     */
    public synchronized boolean lock() {
        this.locked = true;
        activeSessions.clear();
        if (lockStateStore != null) {
            return lockStateStore.setSessionLocked(true);
        }
        return true;
    }

    /**
     * Unlocks the session, allowing credential access to resume.
     * Enforces that durable lock clearing must succeed before unlocking;
     * failed storage writes remain locked.
     *
     * @return true if unlock succeeded and persisted, false if persistence failed (session remains locked).
     */
    public synchronized boolean unlock() {
        if (lockStateStore != null) {
            boolean persisted = lockStateStore.setSessionLocked(false);
            if (!persisted) {
                this.locked = true;
                return false;
            }
        }
        this.locked = false;
        return true;
    }

    /**
     * Clears all in-memory sessions and locks the session.
     * Enforces fail-closed security: credential retrieval is blocked until explicitly unlocked.
     */
    public synchronized boolean clearAllSessions() {
        return lock();
    }

    /**
     * Clears only in-memory cached tickets without changing lock state.
     */
    public synchronized void clearCacheOnly() {
        activeSessions.clear();
    }

    /**
     * Gets valid credentials for a server, refreshing if needed.
     * Enforces fail-closed security:
     * - Returns null immediately if the session is locked.
     * - Throws GeneralSecurityException if server has no approved pinned TLS cert.
     *
     * @return valid AuthCredentials, or null if session is locked or no credentials stored
     */
    public synchronized AuthCredentials getCredentials(ServerConfig config)
            throws GeneralSecurityException, IOException, ApiException {
        return getCredentials(config, 0);
    }

    public synchronized AuthCredentials getCredentials(ServerConfig config, long remainingBudgetMs)
            throws GeneralSecurityException, IOException, ApiException {

        // Session lock check FIRST — before in-memory cache, credential store, or network
        if (locked) {
            return null;
        }

        if (config == null) {
            throw new IllegalArgumentException("ServerConfig cannot be null");
        }

        if (remainingBudgetMs < 0) {
            throw new ApiException(0, "Request deadline already expired before authentication (" + remainingBudgetMs + "ms remaining)");
        }

        if (config.isLegacyDemo()) {
            return null;
        }

        // Fail-closed security check: Never dispatch credentials to an unpinned server UNLESS opted in to skip TLS verification
        if (!config.skipTlsVerify) {
            if (config.pinnedCertFingerprint == null || config.pinnedCertFingerprint.trim().isEmpty() ||
                    !PinnedTrustManager.isValidFingerprint(config.pinnedCertFingerprint)) {
                throw new GeneralSecurityException("Refusing credential dispatch: Server '" + config.name +
                        "' has no approved pinned TLS certificate. All credential-bearing connections remain blocked until the certificate is verified and pinned.");
            }
        }

        // Check in-memory session
        AuthCredentials cached = activeSessions.get(config.id);
        if (cached != null && !cached.isExpired()) {
            return cached;
        }

        // Try API token first (no expiry management needed)
        if (credentialStore.hasApiToken(config.id)) {
            String token = credentialStore.getApiToken(config.id);
            if (token != null) {
                AuthCredentials creds = AuthCredentials.apiToken(token);
                activeSessions.put(config.id, creds);
                return creds;
            }
        }

        // Try username/password -> ticket
        if (credentialStore.hasPassword(config.id)) {
            String username = credentialStore.getUsername(config.id);
            String password = credentialStore.getPassword(config.id);
            if (username != null && password != null) {
                AuthCredentials creds;
                if (config.type == ServerConfig.ServerType.PDM) {
                    PdmApiDriver pdm = new PdmApiDriver(config, this);
                    creds = (remainingBudgetMs > 0)
                            ? pdm.authenticate(username, password, remainingBudgetMs)
                            : pdm.authenticate(username, password);
                } else {
                    PveApiDriver pve = new PveApiDriver(config, this);
                    creds = (remainingBudgetMs > 0)
                            ? pve.authenticate(username, password, remainingBudgetMs)
                            : pve.authenticate(username, password);
                }
                activeSessions.put(config.id, creds);
                return creds;
            }
        }

        // No credentials available
        return null;
    }

    /**
     * Forces re-authentication for a server (called after 401 responses).
     */
    public synchronized void invalidateSession(String serverId) {
        activeSessions.remove(serverId);
    }

    /**
     * Pre-populates or updates the in-memory active session for a server.
     */
    public synchronized void putActiveSession(String serverId, AuthCredentials creds) {
        if (creds != null) {
            activeSessions.put(serverId, creds);
        } else {
            activeSessions.remove(serverId);
        }
    }

    /**
     * Returns true if there is an active, unexpired in-memory session for a server.
     */
    public synchronized boolean hasActiveSession(String serverId) {
        AuthCredentials cached = activeSessions.get(serverId);
        return cached != null && !cached.isExpired();
    }

    /**
     * Refreshes credentials for a server by invalidating existing session
     * and obtaining fresh credentials from the store or network.
     *
     * @return refreshed AuthCredentials, or null if locked or no credentials
     */
    public synchronized AuthCredentials refreshSession(ServerConfig config)
            throws GeneralSecurityException, IOException, ApiException {
        return refreshSession(config, 0);
    }

    /**
     * Refreshes credentials for a server bounded by an absolute remaining budget limit.
     */
    public synchronized AuthCredentials refreshSession(ServerConfig config, long remainingBudgetMs)
            throws GeneralSecurityException, IOException, ApiException {
        if (config == null || locked) return null;
        invalidateSession(config.id);
        return getCredentials(config, remainingBudgetMs);
    }

    /**
     * Checks if a server has any credentials stored. Returns false if session is locked.
     */
    public synchronized boolean hasCredentials(String serverId) {
        if (locked) {
            return false;
        }
        return credentialStore.hasPassword(serverId) || credentialStore.hasApiToken(serverId);
    }
}
