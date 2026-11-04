// SPDX-License-Identifier: GPL-3.0-or-later
// Homelab Pocket — AddServerActivity Transactional Credential Replacement & Injected Failure Tests
package com.homelabpocket.security;

import android.content.Context;
import android.content.SharedPreferences;

import com.homelabpocket.api.ServerConfigStore;
import com.homelabpocket.api.SessionManager;
import com.homelabpocket.model.AuthCredentials;
import com.homelabpocket.model.ServerConfig;
import com.homelabpocket.ui.settings.AddServerActivity;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public class AddServerActivityTransactionalTest {

    private Map<String, Object> serverPrefsMap;
    private Map<String, Object> credPrefsMap;
    private StorageBoundaryController serverBoundary;
    private StorageBoundaryController credBoundary;
    private ServerConfigStore configStore;
    private CredentialStore credStore;
    private SessionManager sessionManager;

    private static final String SERVER_ID = "srv-trans-01";
    private static final String FP_ORIG = "01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF";
    private static final String FP_NEW = "FE:DC:BA:98:76:54:32:10:FE:DC:BA:98:76:54:32:10:FE:DC:BA:98:76:54:32:10:FE:DC:BA:98:76:54:32:10";

    public static class StorageBoundaryController {
        public volatile boolean commitReturnsFalse = false;
        public volatile boolean commitThrowsException = false;
    }

    @Before
    public void setUp() {
        serverPrefsMap = new HashMap<>();
        credPrefsMap = new HashMap<>();
        serverBoundary = new StorageBoundaryController();
        credBoundary = new StorageBoundaryController();

        Context serverContext = createTestContext(serverPrefsMap, serverBoundary);
        Context credContext = createTestContext(credPrefsMap, credBoundary);

        configStore = new ServerConfigStore(serverContext);
        credStore = new CredentialStore(credContext);

        // Standard reversible test crypto provider
        credStore.setCryptoProvider(new CredentialStore.CryptoProvider() {
            @Override
            public String encrypt(String plaintext) {
                return "TEST_ENC:" + java.util.Base64.getEncoder().encodeToString(plaintext.getBytes());
            }

            @Override
            public String decrypt(String encoded) throws GeneralSecurityException {
                if (!encoded.startsWith("TEST_ENC:")) {
                    throw new GeneralSecurityException("Bad test crypto prefix");
                }
                byte[] raw = java.util.Base64.getDecoder().decode(encoded.substring("TEST_ENC:".length()));
                return new String(raw);
            }
        });

        sessionManager = new SessionManager(credStore);
    }

    private static Context createTestContext(Map<String, Object> values, StorageBoundaryController boundary) {
        return new Context() {
            @Override
            public SharedPreferences getSharedPreferences(String name, int mode) {
                return (SharedPreferences) Proxy.newProxyInstance(
                        AddServerActivityTransactionalTest.class.getClassLoader(),
                        new Class[]{SharedPreferences.class},
                        (p, m, x) -> {
                            String mName = m.getName();
                            if (mName.equals("edit")) {
                                Map<String, Object> pendingPuts = new HashMap<>();
                                Set<String> pendingRemoves = new HashSet<>();
                                boolean[] pendingClear = new boolean[]{false};

                                return (SharedPreferences.Editor) Proxy.newProxyInstance(
                                        AddServerActivityTransactionalTest.class.getClassLoader(),
                                        new Class[]{SharedPreferences.Editor.class},
                                        (eProxy, eMethod, eArgs) -> {
                                            String eName = eMethod.getName();
                                            if (eName.equals("putBoolean")) {
                                                pendingPuts.put((String) eArgs[0], eArgs[1]);
                                                pendingRemoves.remove((String) eArgs[0]);
                                                return eProxy;
                                            }
                                            if (eName.equals("putString")) {
                                                pendingPuts.put((String) eArgs[0], eArgs[1]);
                                                pendingRemoves.remove((String) eArgs[0]);
                                                return eProxy;
                                            }
                                            if (eName.equals("remove")) {
                                                pendingRemoves.add((String) eArgs[0]);
                                                pendingPuts.remove((String) eArgs[0]);
                                                return eProxy;
                                            }
                                            if (eName.equals("clear")) {
                                                pendingClear[0] = true;
                                                pendingPuts.clear();
                                                pendingRemoves.clear();
                                                return eProxy;
                                            }
                                            if (eName.equals("commit")) {
                                                if (boundary != null) {
                                                    if (boundary.commitThrowsException) {
                                                        throw new RuntimeException("Injected disk write I/O error (EIO) during commit");
                                                    }
                                                    if (boundary.commitReturnsFalse) {
                                                        // Android commitToMemory occurs before disk success is known.
                                                        if (pendingClear[0]) values.clear();
                                                        for (String rem : pendingRemoves) values.remove(rem);
                                                        values.putAll(pendingPuts);
                                                        return false;
                                                    }
                                                }
                                                // Real SharedPreferences: apply changes atomically only upon successful commit
                                                if (pendingClear[0]) {
                                                    values.clear();
                                                }
                                                for (String rem : pendingRemoves) {
                                                    values.remove(rem);
                                                }
                                                values.putAll(pendingPuts);
                                                return true;
                                            }
                                            if (eName.equals("apply")) {
                                                if (boundary != null && boundary.commitThrowsException) {
                                                    throw new RuntimeException("Injected disk write I/O error during SharedPreferences.apply");
                                                }
                                                if (pendingClear[0]) {
                                                    values.clear();
                                                }
                                                for (String rem : pendingRemoves) {
                                                    values.remove(rem);
                                                }
                                                values.putAll(pendingPuts);
                                                return null;
                                            }
                                            return eProxy;
                                        });
                            }
                            if (mName.equals("getBoolean")) return values.getOrDefault(x[0], x[1]);
                            if (mName.equals("getString")) return values.getOrDefault(x[0], x[1]);
                            if (mName.equals("contains")) return values.containsKey(x[0]);
                            return null;
                        });
            }
        };
    }

    @Test
    public void testPersistentCryptoProviderEncryptionFailure_PreservesOriginalCiphertextAndConfig_InvalidatesSession() throws Exception {
        // Arrange: Populate original configuration and credentials
        ServerConfig origConfig = new ServerConfig(SERVER_ID, "Original Node", "192.0.2.10", 8006,
                ServerConfig.ServerType.PVE, FP_ORIG, true, false);
        configStore.addOrUpdate(origConfig);
        credStore.storePassword(SERVER_ID, "root@pam", "supersecret123");

        // Take snapshot of raw ciphertext bytes before attempt
        Map<String, Object> beforeCiphertext = new HashMap<>(credPrefsMap);

        // Warm active in-memory session
        sessionManager.putActiveSession(SERVER_ID, AuthCredentials.ticket("root@pam", "old-ticket", "old-csrf", System.currentTimeMillis() + 3600_000L));
        assertTrue("Warm session must be cached", sessionManager.hasActiveSession(SERVER_ID));

        // Inject persistent encryption failure in real CryptoProvider while decrypt still works
        credStore.setCryptoProvider(new CredentialStore.CryptoProvider() {
            @Override
            public String encrypt(String plaintext) throws GeneralSecurityException {
                throw new GeneralSecurityException("persistent actual provider failure");
            }

            @Override
            public String decrypt(String encoded) throws GeneralSecurityException {
                if (!encoded.startsWith("TEST_ENC:")) {
                    throw new GeneralSecurityException("Bad test crypto prefix");
                }
                byte[] raw = java.util.Base64.getDecoder().decode(encoded.substring("TEST_ENC:".length()));
                return new String(raw);
            }
        });

        // Act: Attempt replacement
        try {
            AddServerActivity.saveServerAndCredentialsTransactional(
                    configStore, credStore, sessionManager, origConfig,
                    SERVER_ID, "Replaced Node", "192.0.2.99", 8006,
                    ServerConfig.ServerType.PVE, false,
                    "newuser@pve", "brandnewpassword", null,
                    FP_NEW, false
            );
            fail("Expected GeneralSecurityException from persistent encryption failure");
        } catch (GeneralSecurityException e) {
            assertTrue(e.getMessage().contains("persistent actual provider failure"));
        }

        // Assert:
        // 1. Original ciphertext is preserved untouched (no destructive clear/re-encrypt)
        assertEquals("Original stored ciphertext must be preserved byte-for-byte", beforeCiphertext, credPrefsMap);

        // 2. Original server config is retained untouched
        ServerConfig currentConfig = configStore.findById(SERVER_ID);
        assertNotNull(currentConfig);
        assertEquals("192.0.2.10", currentConfig.host);
        assertEquals("Original Node", currentConfig.name);
        assertEquals(FP_ORIG, currentConfig.pinnedCertFingerprint);

        // 3. Stale session must be safely invalidated
        assertFalse("Stale cached session must be invalidated safely on encryption failure",
                sessionManager.hasActiveSession(SERVER_ID));
    }

    @Test
    public void testProductionStorageBoundary_CredentialStoreCommitReturnsFalse_PreservesOriginalState_InvalidatesSession() throws Exception {
        ServerConfig origConfig = new ServerConfig(SERVER_ID, "Prod PVE", "198.51.100.1", 8006,
                ServerConfig.ServerType.PVE, FP_ORIG, true, false);
        configStore.addOrUpdate(origConfig);
        credStore.storePassword(SERVER_ID, "admin@pam", "oldpass123");
        Map<String, Object> beforeCiphertext = new HashMap<>(credPrefsMap);

        sessionManager.putActiveSession(SERVER_ID, AuthCredentials.ticket("admin@pam", "old-ticket", "old-csrf", System.currentTimeMillis() + 3600_000L));
        assertTrue(sessionManager.hasActiveSession(SERVER_ID));

        // Actual storage boundary failure: commit returns false
        credBoundary.commitReturnsFalse = true;

        try {
            AddServerActivity.saveServerAndCredentialsTransactional(
                    configStore, credStore, sessionManager, origConfig,
                    SERVER_ID, "Failed Update", "198.51.100.200", 8006,
                    ServerConfig.ServerType.PVE, false,
                    "newadmin@pam", "newpass456", null,
                    FP_NEW, false
            );
            fail("Expected IOException from credential store commit=false");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("Failed to commit raw credentials"));
        }

        // Config retained
        ServerConfig currentConfig = configStore.findById(SERVER_ID);
        assertEquals("198.51.100.1", currentConfig.host);
        assertEquals("Prod PVE", currentConfig.name);

        // Credentials retained
        assertEquals("Original ciphertext must remain unchanged", beforeCiphertext, credPrefsMap);

        // Session safely invalidated
        assertFalse("Session must be invalidated on credential persist failure", sessionManager.hasActiveSession(SERVER_ID));
    }

    @Test
    public void testProductionStorageBoundary_CredentialStoreCommitThrows_PreservesOriginalState_InvalidatesSession() throws Exception {
        ServerConfig origConfig = new ServerConfig(SERVER_ID, "Prod PVE", "198.51.100.1", 8006,
                ServerConfig.ServerType.PVE, FP_ORIG, true, false);
        configStore.addOrUpdate(origConfig);
        credStore.storePassword(SERVER_ID, "admin@pam", "oldpass123");
        Map<String, Object> beforeCiphertext = new HashMap<>(credPrefsMap);

        sessionManager.putActiveSession(SERVER_ID, AuthCredentials.ticket("admin@pam", "old-ticket", "old-csrf", System.currentTimeMillis() + 3600_000L));
        assertTrue(sessionManager.hasActiveSession(SERVER_ID));

        // Actual storage boundary failure: commit throws RuntimeException
        credBoundary.commitThrowsException = true;

        try {
            AddServerActivity.saveServerAndCredentialsTransactional(
                    configStore, credStore, sessionManager, origConfig,
                    SERVER_ID, "Failed Update", "198.51.100.200", 8006,
                    ServerConfig.ServerType.PVE, false,
                    "newadmin@pam", "newpass456", null,
                    FP_NEW, false
            );
            fail("Expected RuntimeException from credential store commit throwing");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("Injected disk write I/O error"));
        }

        // Config retained
        ServerConfig currentConfig = configStore.findById(SERVER_ID);
        assertEquals("198.51.100.1", currentConfig.host);

        // Credentials retained
        assertEquals("Original ciphertext must remain unchanged", beforeCiphertext, credPrefsMap);

        // Session safely invalidated
        assertFalse("Session must be invalidated on credential persist throwing", sessionManager.hasActiveSession(SERVER_ID));
    }

    @Test
    public void testProductionStorageBoundary_ConfigStoreCommitReturnsFalse_PreservesOriginalState_InvalidatesSession() throws Exception {
        ServerConfig origConfig = new ServerConfig(SERVER_ID, "Cluster Node", "192.168.1.50", 8006,
                ServerConfig.ServerType.PVE, FP_ORIG, true, false);
        configStore.addOrUpdate(origConfig);
        credStore.storePassword(SERVER_ID, "root@pam", "clustersecret");
        Map<String, Object> beforeCiphertext = new HashMap<>(credPrefsMap);

        sessionManager.putActiveSession(SERVER_ID, AuthCredentials.ticket("root@pam", "old-ticket", "old-csrf", System.currentTimeMillis() + 3600_000L));
        assertTrue(sessionManager.hasActiveSession(SERVER_ID));

        // Actual storage boundary failure: configStore commit returns false
        serverBoundary.commitReturnsFalse = true;

        try {
            AddServerActivity.saveServerAndCredentialsTransactional(
                    configStore, credStore, sessionManager, origConfig,
                    SERVER_ID, "Changed Name", "192.168.1.99", 8006,
                    ServerConfig.ServerType.PVE, false,
                    "changed@pam", "newsecret", null,
                    FP_NEW, false
            );
            fail("Expected IOException from configStore commit=false");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("Failed to commit server configuration"));
        }

        // Config retained
        assertEquals("192.168.1.50", configStore.findById(SERVER_ID).host);
        assertEquals("Cluster Node", configStore.findById(SERVER_ID).name);

        // Credentials restored to exact original raw ciphertext
        assertEquals("Original ciphertext must be restored without destructive re-encryption", beforeCiphertext, credPrefsMap);

        // Session safely invalidated
        assertFalse("Session must be invalidated on config persist failure", sessionManager.hasActiveSession(SERVER_ID));
    }

    @Test
    public void testProductionStorageBoundary_ConfigStoreCommitThrows_PreservesOriginalState_InvalidatesSession() throws Exception {
        ServerConfig origConfig = new ServerConfig(SERVER_ID, "Cluster Node", "192.168.1.50", 8006,
                ServerConfig.ServerType.PVE, FP_ORIG, true, false);
        configStore.addOrUpdate(origConfig);
        credStore.storePassword(SERVER_ID, "root@pam", "clustersecret");
        Map<String, Object> beforeCiphertext = new HashMap<>(credPrefsMap);

        sessionManager.putActiveSession(SERVER_ID, AuthCredentials.ticket("root@pam", "old-ticket", "old-csrf", System.currentTimeMillis() + 3600_000L));
        assertTrue(sessionManager.hasActiveSession(SERVER_ID));

        // Actual storage boundary failure: configStore commit throws
        serverBoundary.commitThrowsException = true;

        try {
            AddServerActivity.saveServerAndCredentialsTransactional(
                    configStore, credStore, sessionManager, origConfig,
                    SERVER_ID, "Changed Name", "192.168.1.99", 8006,
                    ServerConfig.ServerType.PVE, false,
                    "changed@pam", "newsecret", null,
                    FP_NEW, false
            );
            fail("Expected RuntimeException from configStore commit throwing");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("Injected disk write I/O error"));
        }

        // Config retained
        assertEquals("192.168.1.50", configStore.findById(SERVER_ID).host);

        // Credentials restored
        assertEquals("Original ciphertext must be restored", beforeCiphertext, credPrefsMap);

        // Session safely invalidated
        assertFalse("Session must be invalidated on config persist throwing", sessionManager.hasActiveSession(SERVER_ID));
    }

    @Test
    public void testSnapshotReadOrDecryptionFailure_InvalidatesSessionSafely() throws Exception {
        ServerConfig origConfig = new ServerConfig(SERVER_ID, "Locked Node", "192.0.2.77", 8006,
                ServerConfig.ServerType.PVE, FP_ORIG, true, false);
        configStore.addOrUpdate(origConfig);
        credStore.storePassword(SERVER_ID, "root@pam", "secret123");

        // Warm active in-memory session
        sessionManager.putActiveSession(SERVER_ID, AuthCredentials.ticket("root@pam", "ticket", "csrf", System.currentTimeMillis() + 3600_000L));
        assertTrue(sessionManager.hasActiveSession(SERVER_ID));

        // Lock credential store so reading/decrypting stored credentials fails
        credStore.setSessionLocked(true);

        try {
            // Attempting edit with username change but blank password requires reading old password
            AddServerActivity.saveServerAndCredentialsTransactional(
                    configStore, credStore, sessionManager, origConfig,
                    SERVER_ID, "New Name", "192.0.2.77", 8006,
                    ServerConfig.ServerType.PVE, false,
                    "newuser@pam", "", null, // blank password triggers getPassword()
                    FP_ORIG, false
            );
            fail("Expected GeneralSecurityException due to session locked during password read");
        } catch (GeneralSecurityException e) {
            assertTrue(e.getMessage().contains("Session is locked"));
        } finally {
            credStore.setSessionLocked(false);
        }

        // Session must be safely invalidated even when failure occurs during snapshot-read / decryption
        assertFalse("Cached session must be invalidated on snapshot-read / decryption failure",
                sessionManager.hasActiveSession(SERVER_ID));
    }

    @Test
    public void testSwitchingPasswordToToken_Successful_ReplacesCredentialsTransactionally() throws Exception {
        ServerConfig origConfig = new ServerConfig(SERVER_ID, "Node-01", "10.0.0.1", 8006,
                ServerConfig.ServerType.PVE, FP_ORIG, true, false);
        configStore.addOrUpdate(origConfig);
        credStore.storePassword(SERVER_ID, "root@pam", "mypassword");

        assertTrue(credStore.hasPassword(SERVER_ID));
        assertFalse(credStore.hasApiToken(SERVER_ID));

        String tokenVal = "PVEAPIToken=root@pam!token=12345678-abcd-1234-abcd-123456789abc";

        AddServerActivity.saveServerAndCredentialsTransactional(
                configStore, credStore, sessionManager, origConfig,
                SERVER_ID, "Node-01-Token", "10.0.0.1", 8006,
                ServerConfig.ServerType.PVE, true,
                null, null, tokenVal,
                FP_ORIG, false
        );

        // Token must now be stored
        assertTrue("Must have API token stored", credStore.hasApiToken(SERVER_ID));
        assertEquals(tokenVal, credStore.getApiToken(SERVER_ID));

        // Old password must be cleanly removed without destructive premature wipe
        assertFalse("Previous password must be cleared after successful token store", credStore.hasPassword(SERVER_ID));

        // Config updated
        assertEquals("Node-01-Token", configStore.findById(SERVER_ID).name);
    }

    @Test
    public void testSwitchingPasswordToToken_Failure_PreservesOriginalPassword() throws Exception {
        ServerConfig origConfig = new ServerConfig(SERVER_ID, "Node-01", "10.0.0.1", 8006,
                ServerConfig.ServerType.PVE, FP_ORIG, true, false);
        configStore.addOrUpdate(origConfig);
        credStore.storePassword(SERVER_ID, "root@pam", "must_not_be_wiped");
        Map<String, Object> beforeCiphertext = new HashMap<>(credPrefsMap);

        credStore.setCryptoProvider(new CredentialStore.CryptoProvider() {
            @Override
            public String encrypt(String p) throws GeneralSecurityException {
                throw new GeneralSecurityException("Failed to encrypt token");
            }

            @Override
            public String decrypt(String encoded) throws GeneralSecurityException {
                byte[] raw = java.util.Base64.getDecoder().decode(encoded.substring("TEST_ENC:".length()));
                return new String(raw);
            }
        });

        try {
            AddServerActivity.saveServerAndCredentialsTransactional(
                    configStore, credStore, sessionManager, origConfig,
                    SERVER_ID, "New Name", "10.0.0.2", 8006,
                    ServerConfig.ServerType.PVE, true,
                    null, null, "PVEAPIToken=new!tok=abc",
                    FP_NEW, false
            );
            fail("Expected exception");
        } catch (GeneralSecurityException ignored) {}

        // Original password must NOT have been destroyed
        assertEquals("Original ciphertext must remain completely intact", beforeCiphertext, credPrefsMap);
        assertTrue("Original password must remain intact", credStore.hasPassword(SERVER_ID));
        assertEquals("must_not_be_wiped", credStore.getPassword(SERVER_ID));
        assertFalse("Token must not be stored", credStore.hasApiToken(SERVER_ID));

        // Config must be unchanged
        assertEquals("10.0.0.1", configStore.findById(SERVER_ID).host);
    }

    @Test
    public void testNewServerCreation_FailureDuringSave_LeavesNoOrphanConfigOrCredentials() throws Exception {
        String newServerId = "srv-brand-new-uuid";

        // Config store commit fails
        serverBoundary.commitReturnsFalse = true;

        try {
            AddServerActivity.saveServerAndCredentialsTransactional(
                    configStore, credStore, sessionManager, null,
                    newServerId, "Brand New", "192.0.2.77", 8006,
                    ServerConfig.ServerType.PVE, false,
                    "root@pam", "newpassword", null,
                    FP_NEW, false
            );
            fail("Expected IOException");
        } catch (IOException ignored) {}

        // Assert: No orphan config or credentials remain
        assertNull("Brand new server must not exist in config store after failure", configStore.findById(newServerId));
        assertFalse("Brand new server credentials must not exist in cred store after failure", credStore.hasPassword(newServerId));
    }
}
