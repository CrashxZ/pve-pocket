// SPDX-License-Identifier: GPL-3.0-or-later
package android.content;

/**
 * JVM test stub for android.content.Context to enable standalone SharedPreferences
 * testing of CredentialStore and SessionManager lifecycle without Android runtime.
 */
public abstract class Context {
    public static final int MODE_PRIVATE = 0;

    public Context() {}

    public Context getApplicationContext() {
        return this;
    }

    public abstract SharedPreferences getSharedPreferences(String name, int mode);
}
