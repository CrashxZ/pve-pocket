// SPDX-License-Identifier: GPL-3.0-or-later
// Homelab Pocket — Android Keystore-backed credential storage
package com.homelabpocket.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.HashSet;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Credential storage backed by Android Keystore (AES-256-GCM encryption).
 *
 * Security properties:
 * - Encryption key stored in Android Keystore (hardware-backed on supported devices)
 * - Credentials encrypted before writing to SharedPreferences
 * - No plaintext passwords, tokens, or tickets in prefs, logs, or APK assets
 * - Credentials are keyed by serverId and credential type
 * - Full wipe on logout
 *
 * Stored credential types (all encrypted):
 * - For username/password: "password" (used to obtain short-lived tickets)
 * - For API tokens: "api_token" (the full PVEAPIToken=... string)
 * - Pinned cert fingerprints: stored separately in server config prefs
 *
 * Note: Short-lived tickets (PVEAuthCookie, CSRFPreventionToken) are kept in
 * memory only during an active session and NOT persisted to disk.
 */
public class CredentialStore implements com.homelabpocket.api.SessionManager.LockStateStore {

    private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
    private static final String KEY_ALIAS = "homelab_pocket_creds_v1";
    private static final String PREFS_NAME = "homelab_creds_encrypted";
    private static final String PREF_KEY_SESSION_LOCKED = "homelab_session_locked";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;

    private final Context context;
    private final java.util.Map<String, String> inMemoryPrefs = new java.util.HashMap<>();
    private volatile boolean memorySessionLocked = false;

    public CredentialStore() {
        this.context = null;
    }

    public CredentialStore(Context context) {
        this.context = context != null ? context.getApplicationContext() : null;
        if (this.context != null) {
            try {
                this.memorySessionLocked = getPrefs().getBoolean(PREF_KEY_SESSION_LOCKED, false);
            } catch (Exception e) {
                // Fail closed on unreadable lock persistence; do not silently default unlocked after error
                this.memorySessionLocked = true;
            }
        }
    }

    @Override
    public boolean isSessionLocked() {
        if (memorySessionLocked) {
            return true;
        }
        if (context != null) {
            try {
                return getPrefs().getBoolean(PREF_KEY_SESSION_LOCKED, false);
            } catch (Exception e) {
                // Fail closed on unreadable lock persistence; do not silently default unlocked after error
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean setSessionLocked(boolean locked) {
        if (locked) {
            // Immediately latch in-process protection at storage boundary
            this.memorySessionLocked = true;
            if (context != null) {
                try {
                    SharedPreferences.Editor editor = getPrefs().edit();
                    if (editor == null) {
                        return false;
                    }
                    return editor.putBoolean(PREF_KEY_SESSION_LOCKED, true).commit();
                } catch (Exception e) {
                    return false;
                }
            }
            return true;
        } else {
            // Unlocking: must confirm durable persistence before clearing in-process lock
            if (context != null) {
                try {
                    SharedPreferences.Editor editor = getPrefs().edit();
                    if (editor == null) {
                        return false;
                    }
                    boolean committed = editor.putBoolean(PREF_KEY_SESSION_LOCKED, false).commit();
                    if (!committed) {
                        return false;
                    }
                } catch (Exception e) {
                    return false;
                }
            }
            this.memorySessionLocked = false;
            return true;
        }
    }

    /**
     * Stores a password for username/password auth.
     * Password is encrypted with AES-256-GCM before storage.
     * @param serverId server config ID
     * @param username e.g. "root@pam"
     * @param password plaintext password — will be encrypted, then cleared from this call
     */
    public void storePassword(String serverId, String username, String password)
            throws GeneralSecurityException, IOException {
        String encrypted = prepareEncryptedPassword(username, password);
        if (!getPrefs().edit().putString(prefKey(serverId, "password"), encrypted).commit()) {
            throw new IOException("Failed to commit password to credential storage");
        }
    }

    /**
     * Stores an API token string.
     * @param serverId server config ID
     * @param tokenString full "PVEAPIToken=user@realm!name=UUID" string
     */
    public void storeApiToken(String serverId, String tokenString)
            throws GeneralSecurityException, IOException {
        String encrypted = prepareEncryptedApiToken(tokenString);
        if (!getPrefs().edit().putString(prefKey(serverId, "api_token"), encrypted).commit()) {
            throw new IOException("Failed to commit API token to credential storage");
        }
    }

    /**
     * Prepares (encrypts) password credential JSON payload in memory without modifying persistent storage.
     */
    public String prepareEncryptedPassword(String username, String password)
            throws GeneralSecurityException, IOException {
        JSONObject cred = new JSONObject();
        try {
            cred.put("type", "password");
            cred.put("username", username);
            cred.put("password", password);
        } catch (JSONException e) {
            throw new IOException("JSON error: " + e.getMessage());
        }
        return encrypt(cred.toString());
    }

    /**
     * Prepares (encrypts) API token credential JSON payload in memory without modifying persistent storage.
     */
    public String prepareEncryptedApiToken(String tokenString)
            throws GeneralSecurityException, IOException {
        JSONObject cred = new JSONObject();
        try {
            cred.put("type", "api_token");
            cred.put("token", tokenString);
        } catch (JSONException e) {
            throw new IOException("JSON error: " + e.getMessage());
        }
        return encrypt(cred.toString());
    }

    /**
     * Retrieves the raw encrypted ciphertext stored for a server without attempting to decrypt it.
     */
    public String getRawCiphertext(String serverId, String type) {
        return getPrefs().getString(prefKey(serverId, type), null);
    }

    /**
     * Atomically commits prepared raw ciphertexts (or removes them if null) in a single editor commit.
     */
    public void commitRawCredentials(String serverId, String passwordCipher, String tokenCipher)
            throws IOException {
        SharedPreferences.Editor editor = getPrefs().edit();
        if (passwordCipher != null) {
            editor.putString(prefKey(serverId, "password"), passwordCipher);
        } else {
            editor.remove(prefKey(serverId, "password"));
        }
        if (tokenCipher != null) {
            editor.putString(prefKey(serverId, "api_token"), tokenCipher);
        } else {
            editor.remove(prefKey(serverId, "api_token"));
        }
        if (!editor.commit()) {
            throw new IOException("Failed to commit raw credentials to persistent storage");
        }
    }

    /**
     * Retrieves stored password for a server.
     * @return plaintext password, or null if not stored
     */
    public String getPassword(String serverId) throws GeneralSecurityException, IOException {
        if (isSessionLocked()) {
            throw new GeneralSecurityException("Session is locked: credential storage access blocked");
        }
        String encrypted = getPrefs().getString(prefKey(serverId, "password"), null);
        if (encrypted == null) return null;
        try {
            JSONObject cred = new JSONObject(decrypt(encrypted));
            return cred.optString("password", null);
        } catch (JSONException e) {
            return null;
        }
    }

    /**
     * Retrieves stored username for a server.
     */
    public String getUsername(String serverId) throws GeneralSecurityException, IOException {
        if (isSessionLocked()) {
            throw new GeneralSecurityException("Session is locked: credential storage access blocked");
        }
        String encrypted = getPrefs().getString(prefKey(serverId, "password"), null);
        if (encrypted == null) return null;
        try {
            JSONObject cred = new JSONObject(decrypt(encrypted));
            return cred.optString("username", null);
        } catch (JSONException e) {
            return null;
        }
    }

    /**
     * Retrieves stored API token for a server.
     */
    public String getApiToken(String serverId) throws GeneralSecurityException, IOException {
        if (isSessionLocked()) {
            throw new GeneralSecurityException("Session is locked: credential storage access blocked");
        }
        String encrypted = getPrefs().getString(prefKey(serverId, "api_token"), null);
        if (encrypted == null) return null;
        try {
            JSONObject cred = new JSONObject(decrypt(encrypted));
            return cred.optString("token", null);
        } catch (JSONException e) {
            return null;
        }
    }

    /**
     * Returns true if the server has username/password credentials stored.
     */
    public boolean hasPassword(String serverId) {
        if (isSessionLocked()) {
            return false;
        }
        return getPrefs().contains(prefKey(serverId, "password"));
    }

    /**
     * Returns true if the server has API token credentials stored.
     */
    public boolean hasApiToken(String serverId) {
        if (isSessionLocked()) {
            return false;
        }
        return getPrefs().contains(prefKey(serverId, "api_token"));
    }

    /**
     * Removes all credentials for a server (called on delete/logout).
     */
    public boolean clearCredentials(String serverId) {
        SharedPreferences.Editor editor = getPrefs().edit();
        editor.remove(prefKey(serverId, "password"));
        editor.remove(prefKey(serverId, "api_token"));
        return editor.commit();
    }

    /**
     * Clears only stored password for a server.
     */
    public boolean clearPassword(String serverId) {
        return getPrefs().edit().remove(prefKey(serverId, "password")).commit();
    }

    /**
     * Clears only stored API token for a server.
     */
    public boolean clearApiToken(String serverId) {
        return getPrefs().edit().remove(prefKey(serverId, "api_token")).commit();
    }

    /**
     * Clears ALL stored credentials (factory reset / full logout).
     */
    public boolean clearAll() {
        return getPrefs().edit().clear().commit();
    }

    // ---- Encryption implementation ----

    public interface CryptoProvider {
        String encrypt(String plaintext) throws GeneralSecurityException, IOException;
        String decrypt(String encoded) throws GeneralSecurityException, IOException;
    }

    private volatile CryptoProvider cryptoProvider = null;

    public void setCryptoProvider(CryptoProvider provider) {
        this.cryptoProvider = provider;
    }

    private SecretKey getOrCreateKey() throws GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
        try {
            keyStore.load(null);
        } catch (IOException e) {
            throw new GeneralSecurityException("Keystore load failed", e);
        }

        if (keyStore.containsAlias(KEY_ALIAS)) {
            KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry)
                    keyStore.getEntry(KEY_ALIAS, null);
            return entry.getSecretKey();
        }

        // Generate new key
        KeyGenerator keyGen = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER);
        keyGen.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build());
        return keyGen.generateKey();
    }

    protected String encrypt(String plaintext) throws GeneralSecurityException, IOException {
        if (cryptoProvider != null) {
            return cryptoProvider.encrypt(plaintext);
        }
        SecretKey key = getOrCreateKey();
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(plaintext.getBytes("UTF-8"));

        // Encode as "base64(iv):base64(ciphertext)"
        return Base64.encodeToString(iv, Base64.NO_WRAP) + ":"
                + Base64.encodeToString(encrypted, Base64.NO_WRAP);
    }

    protected String decrypt(String encoded) throws GeneralSecurityException, IOException {
        if (cryptoProvider != null) {
            return cryptoProvider.decrypt(encoded);
        }
        String[] parts = encoded.split(":", 2);
        if (parts.length != 2) throw new GeneralSecurityException("Invalid credential format");

        byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
        byte[] ciphertext = Base64.decode(parts[1], Base64.NO_WRAP);

        SecretKey key = getOrCreateKey();
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        byte[] plaintext = cipher.doFinal(ciphertext);
        return new String(plaintext, "UTF-8");
    }

    private SharedPreferences inMemorySharedPrefs = null;

    private synchronized SharedPreferences getInMemoryPrefs() {
        if (inMemorySharedPrefs == null) {
            inMemorySharedPrefs = (SharedPreferences) java.lang.reflect.Proxy.newProxyInstance(
                    CredentialStore.class.getClassLoader(),
                    new Class[]{SharedPreferences.class},
                    (proxy, method, args) -> {
                        String mName = method.getName();
                        if (mName.equals("getString")) {
                            String k = (String) args[0];
                            String def = (String) args[1];
                            return inMemoryPrefs.getOrDefault(k, def);
                        }
                        if (mName.equals("getBoolean")) {
                            String k = (String) args[0];
                            Boolean def = (Boolean) args[1];
                            String v = inMemoryPrefs.get(k);
                            return v != null ? Boolean.parseBoolean(v) : def;
                        }
                        if (mName.equals("contains")) {
                            return inMemoryPrefs.containsKey((String) args[0]);
                        }
                        if (mName.equals("edit")) {
                            return (SharedPreferences.Editor) java.lang.reflect.Proxy.newProxyInstance(
                                    CredentialStore.class.getClassLoader(),
                                    new Class[]{SharedPreferences.Editor.class},
                                    (eProxy, eMethod, eArgs) -> {
                                        String emName = eMethod.getName();
                                        if (emName.equals("putString")) {
                                            inMemoryPrefs.put((String) eArgs[0], (String) eArgs[1]);
                                            return eProxy;
                                        }
                                        if (emName.equals("putBoolean")) {
                                            inMemoryPrefs.put((String) eArgs[0], String.valueOf(eArgs[1]));
                                            return eProxy;
                                        }
                                        if (emName.equals("remove")) {
                                            inMemoryPrefs.remove((String) eArgs[0]);
                                            return eProxy;
                                        }
                                        if (emName.equals("clear")) {
                                            inMemoryPrefs.clear();
                                            return eProxy;
                                        }
                                        if (emName.equals("apply")) return null;
                                        if (emName.equals("commit")) return true;
                                        return eProxy;
                                    });
                        }
                        return null;
                    });
        }
        return inMemorySharedPrefs;
    }

    private SharedPreferences getPrefs() {
        if (context == null) {
            return getInMemoryPrefs();
        }
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static String prefKey(String serverId, String type) {
        return serverId + "_" + type;
    }
}
