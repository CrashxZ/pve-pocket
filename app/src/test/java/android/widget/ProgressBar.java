// SPDX-License-Identifier: GPL-3.0-or-later
package android.widget;

import android.content.Context;
import android.view.View;

/**
 * Faithful narrow JVM test double for android.widget.ProgressBar.
 */
public class ProgressBar extends View {
    private int progress = 0;
    private int max = 100;

    public ProgressBar() {
        super();
    }

    public ProgressBar(Context context) {
        super(context);
    }

    public ProgressBar(Context context, Object attrs) {
        super(context);
    }

    public ProgressBar(Context context, android.util.AttributeSet attrs) {
        super(context);
    }

    public ProgressBar(Context context, Object attrs, int defStyleAttr) {
        super(context);
    }

    public ProgressBar(Context context, android.util.AttributeSet attrs, int defStyleAttr) {
        super(context);
    }

    public void setProgress(int progress) {
        this.progress = progress;
    }

    public int getProgress() {
        return progress;
    }

    public void setMax(int max) {
        this.max = max;
    }

    public int getMax() {
        return max;
    }
}
