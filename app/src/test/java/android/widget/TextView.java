// SPDX-License-Identifier: GPL-3.0-or-later
package android.widget;

import android.content.Context;
import android.view.View;

/**
 * Faithful narrow JVM test double for android.widget.TextView.
 */
public class TextView extends View {
    private CharSequence text = "";
    private int textColor;
    private float textSize;

    public TextView() {
        super();
    }

    public TextView(Context context) {
        super(context);
    }

    public TextView(Context context, Object attrs) {
        super(context, attrs);
    }

    public TextView(Context context, Object attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setText(CharSequence text) {
        this.text = text != null ? text : "";
    }

    public void setTypeface(android.graphics.Typeface tf) {}

    public void setTypeface(android.graphics.Typeface tf, int style) {}

    public void setText(int resId) {
        this.text = String.valueOf(resId);
    }

    public CharSequence getText() {
        return text;
    }

    public void setTextColor(int color) {
        this.textColor = color;
    }

    public int getTextColor() {
        return textColor;
    }

    public void setTextSize(float size) {
        this.textSize = size;
    }

    public void setTextSize(int unit, float size) {
        this.textSize = size;
    }

    public float getTextSize() {
        return textSize;
    }
}
