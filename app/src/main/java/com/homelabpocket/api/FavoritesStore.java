// SPDX-License-Identifier: GPL-3.0-or-later
// Homelab Pocket — Favorites storage
package com.homelabpocket.api;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/**
 * Persists user favorites (starred VM/LXC guests) by their unique key.
 * Key format: serverId/node/type/vmid (from GuestInfo.getKey())
 */
public class FavoritesStore {

    private static final String PREFS_NAME = "homelab_favorites";
    private static final String KEY_FAVS = "favorited_guests";

    private final Context context;

    public FavoritesStore(Context context) {
        this.context = context.getApplicationContext();
    }

    public Set<String> loadFavorites() {
        return new HashSet<>(getPrefs().getStringSet(KEY_FAVS, new HashSet<>()));
    }

    public boolean isFavorite(String guestKey) {
        return loadFavorites().contains(guestKey);
    }

    public void setFavorite(String guestKey, boolean favorite) {
        Set<String> favs = loadFavorites();
        if (favorite) {
            favs.add(guestKey);
        } else {
            favs.remove(guestKey);
        }
        getPrefs().edit().putStringSet(KEY_FAVS, favs).apply();
    }

    public void toggleFavorite(String guestKey) {
        setFavorite(guestKey, !isFavorite(guestKey));
    }

    private SharedPreferences getPrefs() {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
