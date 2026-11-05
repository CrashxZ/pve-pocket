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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

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
    private final ReentrantLock sessionLock = new ReentrantLock();
    private volatile boolean locked = false;

    ReentrantLock getSessionLockForTesting() {
        return sessionLock;
    }

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
    public boolean isLocked() {
        sessionLock.lock();
        try {
            return locked;
        } finally {
            sessionLock.unlock();
        }
    }

    /**
     * Locks the session: clears all in-memory tickets and enters explicit locked state.
     * While locked, getCredentials() refuses to access cached sessions, stored credentials,
     * or network authentication, returning null.
     *
     * @return true if durable storage lock persistence succeeded, false if persistence failed
     *         (note: in-process lock is latched regardless).
     */
    public boolean lock() {
        sessionLock.lock();
        try {
            this.locked = true;
            activeSessions.clear();
            if (lockStateStore != null) {
                return lockStateStore.setSessionLocked(true);
            }
            return true;
        } finally {
            sessionLock.unlock();
        }
    }

    /**
     * Unlocks the session, allowing credential access to resume.
     * Enforces that durable lock clearing must succeed before unlocking;
     * failed storage writes remain locked.
     *
     * @return true if unlock succeeded and persisted, false if persistence failed (session remains locked).
     */
    public boolean unlock() {
        sessionLock.lock();
        try {
            if (lockStateStore != null) {
                boolean persisted = lockStateStore.setSessionLocked(false);
                if (!persisted) {
                    this.locked = true;
                    return false;
                }
            }
            this.locked = false;
            return true;
        } finally {
            sessionLock.unlock();
        }
    }

    /**
     * Clears all in-memory sessions and locks the session.
     * Enforces fail-closed security: credential retrieval is blocked until explicitly unlocked.
     */
    public boolean clearAllSessions() {
        return lock();
    }

    /**
     * Clears only in-memory cached tickets without changing lock state.
     */
    public void clearCacheOnly() {
        sessionLock.lock();
        try {
            activeSessions.clear();
        } finally {
            sessionLock.unlock();
        }
    }

    /**
     * Gets valid credentials for a server, refreshing if needed.
     * Enforces fail-closed security:
     * - Returns null immediately if the session is locked.
     * - Throws GeneralSecurityException if server has no approved pinned TLS cert.
     *
     * @return valid AuthCredentials, or null if session is locked or no credentials stored
     */
    public AuthCredentials getCredentials(ServerConfig config)
            throws GeneralSecurityException, IOException, ApiException {
        return getCredentials(config, 0);
    }

    public AuthCredentials getCredentials(ServerConfig config, long remainingBudgetMs)
            throws GeneralSecurityException, IOException, ApiException {
        if (remainingBudgetMs < 0) {
            throw new ApiException(0, "Request deadline already expired before authentication (" + remainingBudgetMs + "ms remaining)");
        }
        long deadlineNanos = (remainingBudgetMs > 0) ? (System.nanoTime() + remainingBudgetMs * 1_000_000L) : 0;
        return getCredentialsWithDeadline(config, deadlineNanos);
    }

    /**
     * Propagates an absolute monotonic deadline through monitor acquisition, storage access,
     * setup, and authentication dispatch. Prevents unbounded wait on the SessionManager monitor.
     */
    public AuthCredentials getCredentialsWithDeadline(ServerConfig config, long deadlineNanos)
            throws GeneralSecurityException, IOException, ApiException {

        if (deadlineNanos > 0) {
            long waitNanos = deadlineNanos - System.nanoTime();
            if (waitNanos <= 0) {
                throw new ApiException(0, "Request deadline already expired before acquiring SessionManager lock (" + (waitNanos / 1_000_000L) + "ms remaining)");
            }
            try {
                boolean acquired = sessionLock.tryLock(waitNanos, TimeUnit.NANOSECONDS);
                if (!acquired) {
                    throw new ApiException(0, "SessionManager lock acquisition timed out waiting for monitor");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiException(0, "SessionManager lock acquisition interrupted: " + e.getMessage());
            }
        } else {
            sessionLock.lock();
        }

        try {
            return getCredentialsWithDeadlineLocked(config, deadlineNanos);
        } finally {
            sessionLock.unlock();
        }
    }

    private AuthCredentials getCredentialsWithDeadlineLocked(ServerConfig config, long deadlineNanos)
            throws GeneralSecurityException, IOException, ApiException {

        // Session lock check FIRST — before in-memory cache, credential store, or network
        if (locked) {
            return null;
        }

        if (config == null) {
            throw new IllegalArgumentException("ServerConfig cannot be null");
        }

        if (deadlineNanos > 0 && System.nanoTime() >= deadlineNanos) {
            throw new ApiException(0, "Request deadline expired after acquiring SessionManager lock");
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

        if (deadlineNanos > 0 && System.nanoTime() >= deadlineNanos) {
            throw new ApiException(0, "Request deadline expired before credential storage lookup");
        }

        // Try API token first
        // Note: Android SharedPreferences is blocking and non-interruptible;
        // we enforce monotonic deadlines before storage queries and perform late-rejection
        // immediately upon return so expired credentials are never published or cached.
        if (credentialStore.hasApiToken(config.id)) {
            if (deadlineNanos > 0 && System.nanoTime() >= deadlineNanos) {
                throw new ApiException(0, "Request deadline expired during API token lookup");
            }
            String token = credentialStore.getApiToken(config.id);
            if (deadlineNanos > 0 && System.nanoTime() >= deadlineNanos) {
                throw new ApiException(0, "Request deadline expired during API token retrieval");
            }
            if (token != null) {
                AuthCredentials creds = AuthCredentials.apiToken(token);
                activeSessions.put(config.id, creds);
                return creds;
            }
        }

        if (deadlineNanos > 0 && System.nanoTime() >= deadlineNanos) {
            throw new ApiException(0, "Request deadline expired during credential storage lookup");
        }

        // Try username/password -> ticket
        if (credentialStore.hasPassword(config.id)) {
            if (deadlineNanos > 0 && System.nanoTime() >= deadlineNanos) {
                throw new ApiException(0, "Request deadline expired during password storage check");
            }
            String username = credentialStore.getUsername(config.id);
            if (deadlineNanos > 0 && System.nanoTime() >= deadlineNanos) {
                throw new ApiException(0, "Request deadline expired during username retrieval");
            }
            String password = credentialStore.getPassword(config.id);
            if (deadlineNanos > 0 && System.nanoTime() >= deadlineNanos) {
                throw new ApiException(0, "Request deadline expired during password retrieval");
            }

            if (username != null && password != null) {
                if (deadlineNanos > 0 && System.nanoTime() >= deadlineNanos) {
                    throw new ApiException(0, "Request deadline expired before driver setup and auth dispatch");
                }

                AuthCredentials creds;
                if (config.type == ServerConfig.ServerType.PDM) {
                    PdmApiDriver pdm = new PdmApiDriver(config, this);
                    if (deadlineNanos > 0 && System.nanoTime() >= deadlineNanos) {
                        throw new ApiException(0, "Request deadline expired before PDM auth dispatch");
                    }
                    creds = pdm.authenticateWithDeadline(username, password, deadlineNanos);
                } else {
                    PveApiDriver pve = new PveApiDriver(config, this);
                    if (deadlineNanos > 0 && System.nanoTime() >= deadlineNanos) {
                        throw new ApiException(0, "Request deadline expired before PVE auth dispatch");
                    }
                    creds = pve.authenticateWithDeadline(username, password, deadlineNanos);
                }

                // Post-dispatch check: never publish or return late token/auth results
                if (deadlineNanos > 0 && System.nanoTime() >= deadlineNanos) {
                    throw new ApiException(0, "Request deadline expired after authentication dispatch");
                }
                if (creds != null) {
                    activeSessions.put(config.id, creds);
                    return creds;
                }
            }
        }

        // No credentials available
        return null;
    }

    /**
     * Forces re-authentication for a server (called after 401 responses).
     */
    public void invalidateSession(String serverId) {
        sessionLock.lock();
        try {
            activeSessions.remove(serverId);
        } finally {
            sessionLock.unlock();
        }
    }

    /**
     * Pre-populates or updates the in-memory active session for a server.
     */
    public void putActiveSession(String serverId, AuthCredentials creds) {
        sessionLock.lock();
        try {
            if (creds != null) {
                activeSessions.put(serverId, creds);
            } else {
                activeSessions.remove(serverId);
            }
        } finally {
            sessionLock.unlock();
        }
    }

    /**
     * Returns true if there is an active, unexpired in-memory session for a server.
     */
    public boolean hasActiveSession(String serverId) {
        sessionLock.lock();
        try {
            AuthCredentials cached = activeSessions.get(serverId);
            return cached != null && !cached.isExpired();
        } finally {
            sessionLock.unlock();
        }
    }

    /**
     * Refreshes credentials for a server by invalidating existing session
     * and obtaining fresh credentials from the store or network.
     *
     * @return refreshed AuthCredentials, or null if locked or no credentials
     */
    public AuthCredentials refreshSession(ServerConfig config)
            throws GeneralSecurityException, IOException, ApiException {
        return refreshSession(config, 0);
    }

    /**
     * Refreshes credentials for a server bounded by an absolute remaining budget limit.
     */
    public AuthCredentials refreshSession(ServerConfig config, long remainingBudgetMs)
            throws GeneralSecurityException, IOException, ApiException {
        if (config == null) return null;
        if (remainingBudgetMs < 0) {
            throw new ApiException(0, "Request deadline already expired before session refresh (" + remainingBudgetMs + "ms remaining)");
        }
        long deadlineNanos = (remainingBudgetMs > 0) ? (System.nanoTime() + remainingBudgetMs * 1_000_000L) : 0;
        return refreshSessionWithDeadline(config, deadlineNanos);
    }

    public void invalidateSessionWithDeadline(String serverId, long deadlineNanos) throws ApiException {
        if (deadlineNanos > 0) {
            long waitNanos = deadlineNanos - System.nanoTime();
            if (waitNanos <= 0) {
                throw new ApiException(0, "Request deadline expired before acquiring lock for session invalidation");
            }
            try {
                if (!sessionLock.tryLock(waitNanos, TimeUnit.NANOSECONDS)) {
                    throw new ApiException(0, "SessionManager lock acquisition timed out during session invalidation");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiException(0, "Interrupted acquiring lock for session invalidation: " + e.getMessage());
            }
        } else {
            sessionLock.lock();
        }
        try {
            activeSessions.remove(serverId);
        } finally {
            sessionLock.unlock();
        }
    }

    public AuthCredentials refreshSessionWithDeadline(ServerConfig config, long deadlineNanos)
            throws GeneralSecurityException, IOException, ApiException {
        if (config == null) return null;
        if (deadlineNanos > 0) {
            long waitNanos = deadlineNanos - System.nanoTime();
            if (waitNanos <= 0) {
                throw new ApiException(0, "Request deadline expired before refreshing session");
            }
            try {
                if (!sessionLock.tryLock(waitNanos, TimeUnit.NANOSECONDS)) {
                    throw new ApiException(0, "SessionManager lock acquisition timed out waiting for monitor");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiException(0, "SessionManager lock acquisition interrupted: " + e.getMessage());
            }
        } else {
            sessionLock.lock();
        }

        try {
            if (locked) {
                return null;
            }
            if (deadlineNanos > 0 && System.nanoTime() >= deadlineNanos) {
                throw new ApiException(0, "Request deadline expired after acquiring SessionManager lock");
            }
            activeSessions.remove(config.id);
            return getCredentialsWithDeadlineLocked(config, deadlineNanos);
        } finally {
            sessionLock.unlock();
        }
    }

    /**
     * Checks if a server has any credentials stored. Returns false if session is locked.
     */
    public boolean hasCredentials(String serverId) {
        sessionLock.lock();
        try {
            if (locked) {
                return false;
            }
            return credentialStore.hasPassword(serverId) || credentialStore.hasApiToken(serverId);
        } finally {
            sessionLock.unlock();
        }
    }
}
