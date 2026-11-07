// SPDX-License-Identifier: GPL-3.0-or-later
package android.content.res;

import android.util.DisplayMetrics;

/**
 * JVM test double for android.content.res.Resources.
 */
public class Resources {
    private final DisplayMetrics displayMetrics = new DisplayMetrics();

    public Resources() {}

    public int getColor(int id) {
        return 0xFF000000;
    }

    public int getColor(int id, Object theme) {
        return 0xFF000000;
    }

    public String getString(int id) {
        return String.valueOf(id);
    }

    public DisplayMetrics getDisplayMetrics() {
        return displayMetrics;
    }
}
