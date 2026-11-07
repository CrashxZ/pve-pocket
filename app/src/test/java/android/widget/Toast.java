// SPDX-License-Identifier: GPL-3.0-or-later
package android.widget;

import android.content.Context;

/**
 * JVM test double for android.widget.Toast allowing real callers to execute
 * without stub exceptions and capturing displayed notifications for test assertions.
 */
public class Toast {
    public static final int LENGTH_SHORT = 0;
    public static final int LENGTH_LONG = 1;

    private static Toast lastToast;
    private static String lastShownText;
    private static int lastShownResId = -1;
    private static int showCount = 0;

    private final Context context;
    private String text;
    private int resId = -1;
    private int duration;

    public Toast(Context context) {
        this.context = context;
    }

    public static Toast makeText(Context context, CharSequence text, int duration) {
        Toast toast = new Toast(context);
        toast.text = (text != null) ? text.toString() : null;
        toast.duration = duration;
        lastToast = toast;
        return toast;
    }

    public static Toast makeText(Context context, int resId, int duration) {
        Toast toast = new Toast(context);
        toast.resId = resId;
        toast.text = String.valueOf(resId);
        toast.duration = duration;
        lastToast = toast;
        return toast;
    }

    public void show() {
        lastToast = this;
        lastShownText = this.text;
        lastShownResId = this.resId;
        showCount++;
    }

    public void cancel() {}

    public void setText(CharSequence s) {
        this.text = (s != null) ? s.toString() : null;
    }

    public void setText(int resId) {
        this.resId = resId;
        this.text = String.valueOf(resId);
    }

    public void setDuration(int duration) {
        this.duration = duration;
    }

    public int getDuration() {
        return duration;
    }

    public String getText() {
        return text;
    }

    public int getResId() {
        return resId;
    }

    public static Toast getLastToast() {
        return lastToast;
    }

    public static String getLastShownText() {
        return lastShownText;
    }

    public static int getLastShownResId() {
        return lastShownResId;
    }

    public static int getShowCount() {
        return showCount;
    }

    public static void reset() {
        lastToast = null;
        lastShownText = null;
        lastShownResId = -1;
        showCount = 0;
    }
}
