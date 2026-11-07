// SPDX-License-Identifier: GPL-3.0-or-later
package android.widget;

import android.content.Context;
import android.view.ViewGroup;

/**
 * Faithful narrow JVM test double for android.widget.ScrollView.
 */
public class ScrollView extends ViewGroup {
    private boolean fillViewport = false;
    private int scrollY = 0;

    public ScrollView() {
        super();
    }

    public ScrollView(Context context) {
        super(context);
    }

    public ScrollView(Context context, Object attrs) {
        super(context, attrs);
    }

    public ScrollView(Context context, Object attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setFillViewport(boolean fillViewport) {
        this.fillViewport = fillViewport;
    }

    public boolean isFillViewport() {
        return fillViewport;
    }

    public int getScrollY() {
        return scrollY;
    }

    public void setScrollY(int scrollY) {
        this.scrollY = scrollY;
    }
}
