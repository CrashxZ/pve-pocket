// SPDX-License-Identifier: GPL-3.0-or-later
// Homelab Pocket — Application entry point and service container
package com.homelabpocket;

import android.app.Application;

import com.homelabpocket.api.ActionGuard;
import com.homelabpocket.api.FavoritesStore;
import com.homelabpocket.api.ServerConfigStore;
import com.homelabpocket.api.SessionManager;
import com.homelabpocket.security.CredentialStore;

/**
 * Application class. Provides access to stores and session manager.
 * No plaintext credentials or secrets are held statically.
 */
public class HomelabApp extends Application {

    private static HomelabApp instance;

    private ServerConfigStore serverConfigStore;
    private CredentialStore credentialStore;
    private SessionManager sessionManager;
    private FavoritesStore favoritesStore;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        this.serverConfigStore = new ServerConfigStore(this);
        this.credentialStore = new CredentialStore(this);
        this.sessionManager = new SessionManager(this.credentialStore);
        this.favoritesStore = new FavoritesStore(this);
    }

    public static HomelabApp getInstance() {
        return instance;
    }

    public ServerConfigStore getServerConfigStore() {
        return serverConfigStore;
    }

    public CredentialStore getCredentialStore() {
        return credentialStore;
    }

    public SessionManager getSessionManager() {
        return sessionManager;
    }

    public FavoritesStore getFavoritesStore() {
        return favoritesStore;
    }

    public ActionGuard getActionGuard() {
        return ActionGuard.getInstance();
    }
}
