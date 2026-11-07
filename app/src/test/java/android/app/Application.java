// SPDX-License-Identifier: GPL-3.0-or-later
package android.app;

import android.content.Context;
import android.content.SharedPreferences;

public class Application extends Context {
    public Application() {}

    public void onCreate() {}

    @Override
    public SharedPreferences getSharedPreferences(String name, int mode) {
        return null;
    }
}
