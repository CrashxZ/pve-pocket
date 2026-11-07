// SPDX-License-Identifier: GPL-3.0-or-later
package android.app;

import android.content.Context;

/**
 * JVM test double for android.app.ProgressDialog.
 */
public class ProgressDialog extends AlertDialog {
    public ProgressDialog(Context context) {
        super(context);
    }

    public ProgressDialog(Context context, int themeResId) {
        super(context, themeResId);
    }

    public static ProgressDialog show(Context context, CharSequence title, CharSequence message) {
        ProgressDialog d = new ProgressDialog(context);
        d.setTitle(title);
        d.setMessage(message);
        d.show();
        return d;
    }
}
