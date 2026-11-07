// SPDX-License-Identifier: GPL-3.0-or-later
package android.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;

/**
 * Faithful headless JVM test stub for android.app.Activity allowing lifecycle
 * and view-binding execution without throwing stub exceptions.
 */
public class Activity extends Context {

    private boolean finishing = false;
    private boolean destroyed = false;
    private Intent intent = new Intent();

    public Activity() {}

    public Intent getIntent() {
        return intent;
    }

    public void setIntent(Intent intent) {
        this.intent = intent;
    }

    public void setContentView(int layoutResID) {}

    public void setContentView(View view) {}

    private java.util.Map<Integer, View> viewMap = new java.util.HashMap<>();

    public void registerView(int id, View view) {
        if (viewMap == null) {
            viewMap = new java.util.HashMap<>();
        }
        viewMap.put(id, view);
    }

    @SuppressWarnings("unchecked")
    public <T extends View> T findViewById(int id) {
        if (viewMap != null && viewMap.containsKey(id)) {
            return (T) viewMap.get(id);
        }
        return null;
    }

    public void finish() {
        this.finishing = true;
    }

    public boolean isFinishing() {
        return finishing;
    }

    public boolean isDestroyed() {
        return destroyed;
    }

    public void runOnUiThread(Runnable action) {
        if (action != null) {
            action.run();
        }
    }

    protected void onCreate(Bundle savedInstanceState) {}

    protected void onResume() {}

    protected void onPause() {}

    protected void onDestroy() {
        this.destroyed = true;
    }

    @Override
    public SharedPreferences getSharedPreferences(String name, int mode) {
        return null;
    }
}
