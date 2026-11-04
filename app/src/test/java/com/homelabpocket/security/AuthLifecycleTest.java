// SPDX-License-Identifier: GPL-3.0-or-later
package com.homelabpocket.security;

import android.content.Context;
import android.content.SharedPreferences;

import com.homelabpocket.api.SessionManager;
import com.homelabpocket.model.AuthCredentials;
import com.homelabpocket.model.ServerConfig;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class AuthLifecycleTest {

    static class TestCredentialStore extends CredentialStore {
        private String apiToken;
        private String username;
        private String password;
        int networkAuthAttempts = 0;

        TestCredentialStore() {
            super();
        }

        void setToken(String token) {
            this.apiToken = token;
        }

        void setPassword(String u, String p) {
            this.username = u;
            this.password = p;
        }

        @Override
        public boolean hasApiToken(String serverId) {
            if (isSessionLocked()) return false;
            return apiToken != null;
        }

        @Override
        public String getApiToken(String serverId) throws GeneralSecurityException, java.io.IOException {
            if (isSessionLocked()) {
                throw new GeneralSecurityException("Session is locked: credential storage access blocked");
            }
            return apiToken;
        }

        @Override
        public boolean hasPassword(String serverId) {
            if (isSessionLocked()) return false;
            return password != null;
        }

        @Override
        public String getUsername(String serverId) throws GeneralSecurityException, java.io.IOException {
            if (isSessionLocked()) {
                throw new GeneralSecurityException("Session is locked: credential storage access blocked");
            }
            return username;
        }

        @Override
        public String getPassword(String serverId) throws GeneralSecurityException, java.io.IOException {
            if (isSessionLocked()) {
                throw new GeneralSecurityException("Session is locked: credential storage access blocked");
            }
            return password;
        }
    }

    private static final String VALID_FINGERPRINT = "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99";

    @Test
    public void testTicketExpirationLogic() {
        long now = System.currentTimeMillis();

        // Expiring in 2 hours -> NOT expired
        AuthCredentials validTicket = AuthCredentials.ticket("root@pam", "ticket123", "csrf123", now + 7200_000L);
        assertFalse(validTicket.isExpired());

        // Expiring in 30 seconds -> considered EXPIRED because of 60s safety buffer
        AuthCredentials expiringSoon = AuthCredentials.ticket("root@pam", "ticket123", "csrf123", now + 30_000L);
        assertTrue(expiringSoon.isExpired());

        // Expired 5 minutes ago -> EXPIRED
        AuthCredentials expiredTicket = AuthCredentials.ticket("root@pam", "ticket123", "csrf123", now - 300_000L);
        assertTrue(expiredTicket.isExpired());
    }

    @Test
    public void testApiTokenNeverExpires() {
        AuthCredentials apiToken = AuthCredentials.apiToken("PVEAPIToken=root@pam!app=12345678-abcd-1234-abcd-123456789abc");
        assertTrue(apiToken.isApiToken());
        assertFalse(apiToken.isExpired());
        assertEquals("PVEAPIToken=root@pam!app=12345678-abcd-1234-abcd-123456789abc", apiToken.getAuthorizationHeader());
        assertNull(apiToken.getTicketCookieValue());
    }

    @Test
    public void testTicketCookieFormatting() {
        AuthCredentials ticket = AuthCredentials.ticket("root@pam", "TICKET_PAYLOAD_HERE", "CSRF_TOKEN", System.currentTimeMillis() + 3600000);
        assertFalse(ticket.isApiToken());
        assertEquals("PVEAuthCookie=TICKET_PAYLOAD_HERE", ticket.getTicketCookieValue());
        assertNull(ticket.getAuthorizationHeader());
    }

    @Test
    public void testSessionLockBlocksTokenStorePath() throws Exception {
        TestCredentialStore store = new TestCredentialStore();
        store.setToken("PVEAPIToken=test@pam!tok=1111-2222-3333");
        SessionManager sessionManager = new SessionManager(store);

        ServerConfig server = new ServerConfig("srv1", "Production PVE", "192.0.2.10", 8006,
                ServerConfig.ServerType.PVE, VALID_FINGERPRINT);

        // Before lock: credentials available from store
        AuthCredentials creds = sessionManager.getCredentials(server);
        assertNotNull("Should retrieve stored API token before lock", creds);
        assertEquals("PVEAPIToken=test@pam!tok=1111-2222-3333", creds.getAuthorizationHeader());
        assertFalse(sessionManager.isLocked());

        // Explicit lock
        sessionManager.lock();
        assertTrue("Session must be in locked state", sessionManager.isLocked());

        // While locked: refuses to return credentials, refuses store access
        assertNull("getCredentials must return null when locked", sessionManager.getCredentials(server));
        assertFalse("hasCredentials must report false when locked", sessionManager.hasCredentials(server.id));

        // Unlock restores access
        sessionManager.unlock();
        assertFalse("Session should be unlocked", sessionManager.isLocked());
        AuthCredentials restored = sessionManager.getCredentials(server);
        assertNotNull("getCredentials should succeed after unlock", restored);
        assertEquals("PVEAPIToken=test@pam!tok=1111-2222-3333", restored.getAuthorizationHeader());
    }

    @Test
    public void testSessionLockBlocksPasswordStorePath() throws Exception {
        TestCredentialStore store = new TestCredentialStore();
        store.setPassword("root@pam", "secret123");
        SessionManager sessionManager = new SessionManager(store);

        ServerConfig server = new ServerConfig("srv2", "Lab Node", "192.0.2.20", 8006,
                ServerConfig.ServerType.PVE, VALID_FINGERPRINT);

        // Lock session before any authentication
        sessionManager.lock();
        assertTrue(sessionManager.isLocked());

        // Credential retrieval must return null immediately without attempting network ticket auth
        AuthCredentials creds = sessionManager.getCredentials(server);
        assertNull("Password auth must be blocked while session is locked", creds);
        assertEquals("No network auth should be triggered while locked", 0, store.networkAuthAttempts);
    }

    @Test
    public void testSessionLockCheckedBeforeCache() throws Exception {
        TestCredentialStore store = new TestCredentialStore();
        store.setToken("PVEAPIToken=cached@pam!tok=4444");
        SessionManager sessionManager = new SessionManager(store);

        ServerConfig server = new ServerConfig("srv3", "Cached Node", "192.0.2.30", 8006,
                ServerConfig.ServerType.PVE, VALID_FINGERPRINT);

        // Populate cache
        AuthCredentials initial = sessionManager.getCredentials(server);
        assertNotNull(initial);

        // Lock session: clears in-memory activeSessions cache AND blocks retrieval
        sessionManager.lock();
        assertNull("Even previously-cached credentials must not be returned while locked",
                sessionManager.getCredentials(server));
    }

    @Test
    public void testClearAllSessionsLocksFailClosed() throws Exception {
        TestCredentialStore store = new TestCredentialStore();
        store.setToken("PVEAPIToken=test@pve!fixture=not-a-secret");
        SessionManager sessionManager = new SessionManager(store);

        ServerConfig server = new ServerConfig("srv4", "Clear Test", "192.0.2.40", 8006,
                ServerConfig.ServerType.PVE, VALID_FINGERPRINT);

        // Initial fetch
        assertNotNull(sessionManager.getCredentials(server));

        // clearAllSessions must lock fail-closed
        sessionManager.clearAllSessions();
        assertTrue(sessionManager.isLocked());
        assertNull("clearAllSessions must prevent immediate credential re-fetch",
                sessionManager.getCredentials(server));
    }

    @Test
    public void testSessionLockPreservedAcrossProcessRecreation() throws Exception {
        TestCredentialStore store = new TestCredentialStore();
        store.setToken("PVEAPIToken=test@pve!persisted=token-value");
        ServerConfig server = new ServerConfig("srv5", "Recreation Test", "192.0.2.50", 8006,
                ServerConfig.ServerType.PVE, VALID_FINGERPRINT);

        // Process 1: Start app, fetch credentials, then explicitly lock session
        SessionManager sm1 = new SessionManager(store);
        assertNotNull(sm1.getCredentials(server));
        assertFalse(sm1.isLocked());

        sm1.lock();
        assertTrue(sm1.isLocked());
        assertNull(sm1.getCredentials(server));

        // Process 2: App process killed and restarted (new SessionManager created with same persistent store)
        SessionManager sm2 = new SessionManager(store);
        assertTrue("Session lock must be preserved across process recreation", sm2.isLocked());
        assertNull("Credentials must NOT be accessible after locked process restart", sm2.getCredentials(server));
        assertFalse("hasCredentials must report false while locked after restart", sm2.hasCredentials(server.id));

        // Unlock session explicitly
        sm2.unlock();
        assertFalse(sm2.isLocked());
        assertNotNull("Credentials should become accessible after unlock", sm2.getCredentials(server));

        // Process 3: Restart after explicit unlock stays unlocked
        SessionManager sm3 = new SessionManager(store);
        assertFalse("Unlocked state preserved after explicit unlock and restart", sm3.isLocked());
        assertNotNull(sm3.getCredentials(server));
    }

    @Test
    public void testEmptyFirstRunStartsUnlocked() {
        TestCredentialStore emptyStore = new TestCredentialStore();
        SessionManager sm = new SessionManager(emptyStore);
        assertFalse("Empty first-run onboarding must start unlocked", sm.isLocked());
    }

    private Context createTestContext(Map<String, Object> values) {
        SharedPreferences.Editor editor = (SharedPreferences.Editor) Proxy.newProxyInstance(
                AuthLifecycleTest.class.getClassLoader(),
                new Class[]{SharedPreferences.Editor.class},
                (p, m, x) -> {
                    if (m.getName().equals("putBoolean")) { values.put((String) x[0], x[1]); return p; }
                    if (m.getName().equals("putString")) { values.put((String) x[0], x[1]); return p; }
                    if (m.getName().equals("remove")) { values.remove((String) x[0]); return p; }
                    if (m.getName().equals("clear")) { values.clear(); return p; }
                    if (m.getName().equals("apply")) return null;
                    if (m.getName().equals("commit")) return true;
                    return p;
                });
        SharedPreferences prefs = (SharedPreferences) Proxy.newProxyInstance(
                AuthLifecycleTest.class.getClassLoader(),
                new Class[]{SharedPreferences.class},
                (p, m, x) -> {
                    if (m.getName().equals("edit")) return editor;
                    if (m.getName().equals("getBoolean")) return values.getOrDefault(x[0], x[1]);
                    if (m.getName().equals("getString")) return values.getOrDefault(x[0], x[1]);
                    if (m.getName().equals("contains")) return values.containsKey(x[0]);
                    return null;
                });
        return new Context() {
            @Override
            public SharedPreferences getSharedPreferences(String name, int mode) {
                return prefs;
            }
        };
    }

    @Test
    public void testFreshStoreAndSessionManagerSharesPersistedLock() throws Exception {
        Map<String, Object> persistedPrefs = new HashMap<>();
        Context context = createTestContext(persistedPrefs);

        // Instance 1: Store and SessionManager
        CredentialStore store1 = new CredentialStore(context);
        SessionManager sm1 = new SessionManager(store1);

        assertFalse("First run starts unlocked", store1.isSessionLocked());
        assertFalse("First run starts unlocked", sm1.isLocked());

        // Put simulated ciphertext in prefs
        persistedPrefs.put("srv1_api_token", "dummy-ciphertext");
        persistedPrefs.put("srv1_password", "dummy-ciphertext");

        // Explicitly lock session
        sm1.lock();
        assertTrue("sm1 must be locked", sm1.isLocked());
        assertTrue("store1 must report locked", store1.isSessionLocked());
        assertEquals(Boolean.TRUE, persistedPrefs.get("homelab_session_locked"));

        // Instance 2: Brand fresh CredentialStore and SessionManager sharing same SharedPreferences
        CredentialStore store2 = new CredentialStore(context);
        SessionManager sm2 = new SessionManager(store2);

        assertTrue("Fresh store2 must read locked state from persistent prefs", store2.isSessionLocked());
        assertTrue("Fresh sm2 must initialize in locked state from persistent store", sm2.isLocked());

        // Storage boundary enforcement: Direct getters on store2 must throw GeneralSecurityException
        try {
            store2.getApiToken("srv1");
            fail("store2.getApiToken must throw GeneralSecurityException while locked");
        } catch (GeneralSecurityException e) {
            assertTrue(e.getMessage().contains("Session is locked"));
        }

        try {
            store2.getPassword("srv1");
            fail("store2.getPassword must throw GeneralSecurityException while locked");
        } catch (GeneralSecurityException e) {
            assertTrue(e.getMessage().contains("Session is locked"));
        }

        try {
            store2.getUsername("srv1");
            fail("store2.getUsername must throw GeneralSecurityException while locked");
        } catch (GeneralSecurityException e) {
            assertTrue(e.getMessage().contains("Session is locked"));
        }

        assertFalse("hasPassword must return false while locked", store2.hasPassword("srv1"));
        assertFalse("hasApiToken must return false while locked", store2.hasApiToken("srv1"));

        ServerConfig server = new ServerConfig("srv1", "Server 1", "192.0.2.10", 8006,
                ServerConfig.ServerType.PVE, VALID_FINGERPRINT);
        assertNull("sm2.getCredentials must return null while locked", sm2.getCredentials(server));
        assertFalse("sm2.hasCredentials must return false while locked", sm2.hasCredentials("srv1"));
    }

    @Test
    public void testUnreadableLockPersistenceFailsClosed() throws Exception {
        Context brokenContext = new Context() {
            @Override
            public SharedPreferences getSharedPreferences(String name, int mode) {
                throw new RuntimeException("Corrupted shared preferences file on disk");
            }
        };

        CredentialStore brokenStore = new CredentialStore(brokenContext);
        assertTrue("Unreadable persistence must fail closed (return locked=true)", brokenStore.isSessionLocked());

        SessionManager brokenSm = new SessionManager(brokenStore);
        assertTrue("SessionManager with unreadable store must initialize in locked state", brokenSm.isLocked());

        try {
            brokenStore.getApiToken("srv1");
            fail("Direct getApiToken on broken store must throw GeneralSecurityException");
        } catch (GeneralSecurityException e) {
            assertTrue(e.getMessage().contains("Session is locked"));
        }
    }

    @Test
    public void testReonboardingPreservesLockForOtherSavedServers() throws Exception {
        Map<String, Object> persistedPrefs = new HashMap<>();
        Context context = createTestContext(persistedPrefs);

        CredentialStore store = new CredentialStore(context);
        SessionManager sm = new SessionManager(store);

        // Lock session
        sm.lock();
        assertTrue(sm.isLocked());
        assertTrue(store.isSessionLocked());

        // Re-onboarding adds/updates credentials for server srv_new
        // Verifies store.isSessionLocked() remains true, sm.isLocked() remains true
        assertTrue("Session must remain locked during re-onboarding", store.isSessionLocked());
        assertTrue("Session must remain locked during re-onboarding", sm.isLocked());

        // Fresh instance after re-onboarding is still locked
        CredentialStore freshStore = new CredentialStore(context);
        SessionManager freshSm = new SessionManager(freshStore);
        assertTrue("Fresh store must remain locked", freshStore.isSessionLocked());
        assertTrue("Fresh SessionManager must remain locked", freshSm.isLocked());
    }

    @Test
    public void testLockWriteFailureLatchesInProcessStoreAndBlocksGetters() throws Exception {
        int[] secretReads = new int[]{0};
        SharedPreferences prefs = (SharedPreferences) Proxy.newProxyInstance(
                AuthLifecycleTest.class.getClassLoader(),
                new Class[]{SharedPreferences.class},
                (p, m, x) -> {
                    if (m.getName().equals("edit")) {
                        throw new IllegalStateException("injected lock persistence write failure");
                    }
                    if (m.getName().equals("getBoolean")) return false;
                    if (m.getName().equals("contains")) return true;
                    if (m.getName().equals("getString")) {
                        secretReads[0]++;
                        return "ciphertext";
                    }
                    return null;
                });
        Context context = new Context() {
            @Override
            public SharedPreferences getSharedPreferences(String n, int m) {
                return prefs;
            }
        };

        CredentialStore store = new CredentialStore(context);
        SessionManager sm = new SessionManager(store);

        assertFalse("Starts unlocked", sm.isLocked());
        assertFalse("Store starts unlocked", store.isSessionLocked());

        // sm.lock() reports persistence failure (returns false) but latches in memory
        boolean persisted = sm.lock();
        assertFalse("sm.lock() must return false when storage write fails", persisted);
        assertTrue("sm must be locked in memory", sm.isLocked());
        assertTrue("store must latch session lock in memory", store.isSessionLocked());

        // Direct getters must be blocked before ciphertext access
        try {
            store.getApiToken("srv1");
            fail("store.getApiToken must throw GeneralSecurityException while locked");
        } catch (GeneralSecurityException expected) {
            assertTrue(expected.getMessage().contains("Session is locked"));
        }

        try {
            store.getPassword("srv1");
            fail("store.getPassword must throw GeneralSecurityException while locked");
        } catch (GeneralSecurityException expected) {
            assertTrue(expected.getMessage().contains("Session is locked"));
        }

        try {
            store.getUsername("srv1");
            fail("store.getUsername must throw GeneralSecurityException while locked");
        } catch (GeneralSecurityException expected) {
            assertTrue(expected.getMessage().contains("Session is locked"));
        }

        assertFalse("hasPassword must return false while locked", store.hasPassword("srv1"));
        assertFalse("hasApiToken must return false while locked", store.hasApiToken("srv1"));

        assertEquals("No secret reads must occur when getter is blocked", 0, secretReads[0]);

        ServerConfig server = new ServerConfig("srv1", "Test", "192.0.2.1", 8006,
                ServerConfig.ServerType.PVE, VALID_FINGERPRINT);
        assertNull("sm.getCredentials must return null when locked", sm.getCredentials(server));
    }

    @Test
    public void testFailedUnlockRemainsLocked() throws Exception {
        Map<String, Object> values = new HashMap<>();
        boolean[] allowWrites = new boolean[]{true};
        SharedPreferences.Editor editor = (SharedPreferences.Editor) Proxy.newProxyInstance(
                AuthLifecycleTest.class.getClassLoader(),
                new Class[]{SharedPreferences.Editor.class},
                (p, m, x) -> {
                    if (!allowWrites[0]) {
                        throw new IllegalStateException("injected write failure");
                    }
                    if (m.getName().equals("putBoolean")) { values.put((String) x[0], x[1]); return p; }
                    if (m.getName().equals("commit")) return true;
                    return p;
                });
        SharedPreferences prefs = (SharedPreferences) Proxy.newProxyInstance(
                AuthLifecycleTest.class.getClassLoader(),
                new Class[]{SharedPreferences.class},
                (p, m, x) -> {
                    if (m.getName().equals("edit")) {
                        if (!allowWrites[0]) throw new IllegalStateException("injected edit failure");
                        return editor;
                    }
                    if (m.getName().equals("getBoolean")) return values.getOrDefault(x[0], x[1]);
                    return null;
                });
        Context context = new Context() {
            @Override
            public SharedPreferences getSharedPreferences(String n, int m) {
                return prefs;
            }
        };

        CredentialStore store = new CredentialStore(context);
        SessionManager sm = new SessionManager(store);

        // Lock with working storage
        boolean lockedOk = sm.lock();
        assertTrue("sm.lock() must succeed when storage works", lockedOk);
        assertTrue(sm.isLocked());
        assertTrue(store.isSessionLocked());

        // Now break storage writes
        allowWrites[0] = false;

        // Attempt unlock
        boolean unlocked = sm.unlock();
        assertFalse("Failed unlock must return false", unlocked);
        assertTrue("sm must remain locked after failed unlock", sm.isLocked());
        assertTrue("store must remain locked after failed unlock", store.isSessionLocked());
    }

    @Test
    public void testCommitFalseReportsPersistenceFailure() throws Exception {
        SharedPreferences.Editor editor = (SharedPreferences.Editor) Proxy.newProxyInstance(
                AuthLifecycleTest.class.getClassLoader(),
                new Class[]{SharedPreferences.Editor.class},
                (p, m, x) -> {
                    if (m.getName().equals("putBoolean")) return p;
                    if (m.getName().equals("commit")) return false; // commit fails
                    return p;
                });
        SharedPreferences prefs = (SharedPreferences) Proxy.newProxyInstance(
                AuthLifecycleTest.class.getClassLoader(),
                new Class[]{SharedPreferences.class},
                (p, m, x) -> {
                    if (m.getName().equals("edit")) return editor;
                    if (m.getName().equals("getBoolean")) return false;
                    return null;
                });
        Context context = new Context() {
            @Override
            public SharedPreferences getSharedPreferences(String n, int m) {
                return prefs;
            }
        };

        CredentialStore store = new CredentialStore(context);
        SessionManager sm = new SessionManager(store);

        boolean persisted = sm.lock();
        assertFalse("commit=false must report persistence failure", persisted);
        assertTrue("sm must latch lock in memory", sm.isLocked());
        assertTrue("store must latch lock in memory", store.isSessionLocked());
    }

    @Test
    public void testSuccessfulLockSurvivesRecreation() throws Exception {
        Map<String, Object> values = new HashMap<>();
        Context context = createTestContext(values);

        CredentialStore store1 = new CredentialStore(context);
        SessionManager sm1 = new SessionManager(store1);

        boolean persisted = sm1.lock();
        assertTrue("sm.lock() must return true when commit succeeds", persisted);
        assertTrue(sm1.isLocked());
        assertTrue(store1.isSessionLocked());

        // Fresh instances (recreation)
        CredentialStore store2 = new CredentialStore(context);
        SessionManager sm2 = new SessionManager(store2);

        assertTrue("Fresh store must read locked state from storage", store2.isSessionLocked());
        assertTrue("Fresh SessionManager must initialize locked", sm2.isLocked());
    }
}
