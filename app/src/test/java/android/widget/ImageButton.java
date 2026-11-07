// SPDX-License-Identifier: GPL-3.0-or-later
package android.widget;

import android.content.Context;
import android.view.View;

/**
 * Faithful narrow JVM test double for android.widget.ImageButton.
 */
public class ImageButton extends View {
    public ImageButton() {
        super();
    }

    public ImageButton(Context context) {
        super(context);
    }

    public ImageButton(Context context, Object attrs) {
        super(context, attrs);
    }

    public ImageButton(Context context, Object attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setColorFilter(int color) {}
}
