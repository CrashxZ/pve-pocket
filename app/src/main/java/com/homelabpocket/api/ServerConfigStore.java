// SPDX-License-Identifier: GPL-3.0-or-later
// Homelab Pocket — Server configuration persistence
package com.homelabpocket.api;

import android.content.Context;
import android.content.SharedPreferences;

import com.homelabpocket.model.ServerConfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Persists non-secret server configuration (host, port, name, type, cert fingerprint).
 * Credentials are NOT stored here — use CredentialStore for those.
 *
 * Uses SharedPreferences with JSON encoding.
 * The pinned cert fingerprint is not a secret (it's a public cert hash), so
 * it's acceptable to store it in plain SharedPreferences.
 */
public class ServerConfigStore {

    private static final String PREFS_NAME = "homelab_servers";
    private static final String KEY_SERVERS = "servers_json";

    private final Context context;

    public ServerConfigStore(Context context) {
        this.context = context.getApplicationContext();
    }

    public List<ServerConfig> loadAll() {
        List<ServerConfig> configs = new ArrayList<>();
        String json = getPrefs().getString(KEY_SERVERS, null);
        if (json == null) return configs;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                ServerConfig cfg = fromJson(arr.getJSONObject(i));
                if (cfg != null) configs.add(cfg);
            }
        } catch (JSONException ignored) {}
        return configs;
    }

    public void saveAll(List<ServerConfig> configs) throws IOException {
        JSONArray arr = new JSONArray();
        for (ServerConfig cfg : configs) {
            arr.put(toJson(cfg));
        }
        if (!getPrefs().edit().putString(KEY_SERVERS, arr.toString()).commit()) {
            throw new IOException("Failed to commit server configuration to persistent storage");
        }
    }

    public void addOrUpdate(ServerConfig config) throws IOException {
        List<ServerConfig> all = loadAll();
        boolean found = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(config.id)) {
                all.set(i, config);
                found = true;
                break;
            }
        }
        if (!found) all.add(config);
        saveAll(all);
    }

    public void remove(String serverId) throws IOException {
        List<ServerConfig> all = loadAll();
        all.removeIf(cfg -> cfg.id.equals(serverId));
        saveAll(all);
    }

    public ServerConfig findById(String serverId) {
        for (ServerConfig cfg : loadAll()) {
            if (cfg.id.equals(serverId)) return cfg;
        }
        return null;
    }

    public String getConfigFingerprint() {
        return getPrefs().getString(KEY_SERVERS, "");
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    private JSONObject toJson(ServerConfig cfg) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("id", cfg.id);
            obj.put("name", cfg.name);
            obj.put("host", cfg.host);
            obj.put("port", cfg.port);
            obj.put("type", cfg.type.name());
            if (cfg.pinnedCertFingerprint != null) {
                obj.put("fingerprint", cfg.pinnedCertFingerprint);
            }
            obj.put("enforcePin", cfg.enforceCertPin);
            obj.put("skipTlsVerify", cfg.skipTlsVerify);
        } catch (JSONException ignored) {}
        return obj;
    }

    private ServerConfig fromJson(JSONObject obj) {
        try {
            String id = obj.getString("id");
            String name = obj.getString("name");
            String host = obj.getString("host");
            int port = obj.getInt("port");
            ServerConfig.ServerType type = ServerConfig.ServerType.valueOf(
                    obj.optString("type", "PVE"));
            String fingerprint = obj.optString("fingerprint", null);
            if (fingerprint != null && fingerprint.isEmpty()) fingerprint = null;
            boolean enforcePin = obj.optBoolean("enforcePin", true);
            boolean skipTlsVerify = obj.optBoolean("skipTlsVerify", false);
            return new ServerConfig(id, name, host, port, type, fingerprint, enforcePin, skipTlsVerify);
        } catch (JSONException e) {
            return null;
        }
    }

    private SharedPreferences getPrefs() {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
