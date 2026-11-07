// SPDX-License-Identifier: GPL-3.0-or-later
package android.widget;

import android.content.Context;
import android.view.ViewGroup;

/**
 * Faithful narrow JVM test double for android.widget.LinearLayout.
 */
public class LinearLayout extends ViewGroup {
    public static final int HORIZONTAL = 0;
    public static final int VERTICAL = 1;

    private int orientation = HORIZONTAL;

    public LinearLayout() {
        super();
    }

    public LinearLayout(Context context) {
        super(context);
    }

    public LinearLayout(Context context, Object attrs) {
        super(context, attrs);
    }

    public LinearLayout(Context context, Object attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setOrientation(int orientation) {
        this.orientation = orientation;
    }

    public int getOrientation() {
        return orientation;
    }

    public static class LayoutParams extends ViewGroup.MarginLayoutParams {
        public float weight;

        public LayoutParams() {
            super();
        }

        public LayoutParams(int width, int height) {
            super(width, height);
        }

        public LayoutParams(int width, int height, float weight) {
            super(width, height);
            this.weight = weight;
        }
    }
}
