// SPDX-License-Identifier: GPL-3.0-or-later
package android.widget;

import android.content.Context;

/**
 * Faithful narrow JVM test double for android.widget.Button.
 */
public class Button extends TextView {
    public Button() {
        super();
    }

    public Button(Context context) {
        super(context);
    }

    public Button(Context context, Object attrs) {
        super(context, attrs);
    }

    public Button(Context context, Object attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
}
