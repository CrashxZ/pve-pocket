// SPDX-License-Identifier: GPL-3.0-or-later
package android.view;

import android.content.Context;
import android.content.res.Resources;
import java.util.HashMap;
import java.util.Map;

/**
 * Faithful narrow JVM test double for android.view.View.
 */
public class View {
    public static final int VISIBLE = 0;
    public static final int INVISIBLE = 4;
    public static final int GONE = 8;

    protected Context context;
    private int visibility = VISIBLE;
    private boolean enabled = true;
    private int id = -1;
    private Object tag;
    private OnClickListener clickListener;
    private ViewGroup parent;
    private ViewGroup.LayoutParams layoutParams;
    private int paddingLeft, paddingTop, paddingRight, paddingBottom;
    private int backgroundResId;
    private final Map<Integer, View> childrenById = new HashMap<>();

    public interface OnClickListener {
        void onClick(View v);
    }

    public View() {}

    public View(Context context) {
        this.context = context;
    }

    public View(Context context, Object attrs) {
        this.context = context;
    }

    public View(Context context, Object attrs, int defStyleAttr) {
        this.context = context;
    }

    public Context getContext() {
        return context;
    }

    public Resources getResources() {
        return context != null ? context.getResources() : new Resources();
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getVisibility() {
        return visibility;
    }

    public void setVisibility(int visibility) {
        this.visibility = visibility;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setOnClickListener(OnClickListener l) {
        this.clickListener = l;
    }

    public boolean performClick() {
        if (clickListener != null) {
            clickListener.onClick(this);
            return true;
        }
        return false;
    }

    public ViewParent getParent() {
        return parent;
    }

    public void requestLayout() {}

    void setParent(ViewGroup parent) {
        this.parent = parent;
    }

    public Object getTag() {
        return tag;
    }

    public void setTag(Object tag) {
        this.tag = tag;
    }

    public void setBackgroundResource(int resid) {
        this.backgroundResId = resid;
    }

    public int getBackgroundResource() {
        return backgroundResId;
    }

    public void setPadding(int left, int top, int right, int bottom) {
        this.paddingLeft = left;
        this.paddingTop = top;
        this.paddingRight = right;
        this.paddingBottom = bottom;
    }

    public int getPaddingLeft() { return paddingLeft; }
    public int getPaddingTop() { return paddingTop; }
    public int getPaddingRight() { return paddingRight; }
    public int getPaddingBottom() { return paddingBottom; }

    public ViewGroup.LayoutParams getLayoutParams() {
        return layoutParams;
    }

    public void setLayoutParams(ViewGroup.LayoutParams params) {
        this.layoutParams = params;
    }

    @SuppressWarnings("unchecked")
    public <T extends View> T findViewById(int id) {
        if (this.id == id) {
            return (T) this;
        }
        return (T) childrenById.get(id);
    }

    public void registerChild(int id, View child) {
        childrenById.put(id, child);
    }
}
