// SPDX-License-Identifier: GPL-3.0-or-later
package android.content;

/**
 * JVM test stub for android.content.Context to enable standalone SharedPreferences
 * testing of CredentialStore and SessionManager lifecycle without Android runtime.
 */
public abstract class Context {
    public static final int MODE_PRIVATE = 0;
    public static final String KEYGUARD_SERVICE = "keyguard";
    public static final String POWER_SERVICE = "power";

    public Context() {}

    public Context getApplicationContext() {
        return this;
    }

    public abstract SharedPreferences getSharedPreferences(String name, int mode);

    public Object getSystemService(String name) {
        return null;
    }

    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        return null;
    }

    public void unregisterReceiver(BroadcastReceiver receiver) {}

    public android.content.res.Resources getResources() {
        return new android.content.res.Resources();
    }

    public String getString(int resId) {
        return String.valueOf(resId);
    }
}
