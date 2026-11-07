// SPDX-License-Identifier: GPL-3.0-or-later
package android.view;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;

/**
 * Faithful narrow JVM test double for android.view.ViewGroup.
 */
public abstract class ViewGroup extends View implements ViewParent {
    protected final List<View> children = new ArrayList<>();

    public ViewGroup() {
        super();
    }

    public ViewGroup(Context context) {
        super(context);
    }

    public ViewGroup(Context context, Object attrs) {
        super(context, attrs);
    }

    public ViewGroup(Context context, Object attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void addView(View child) {
        if (child != null) {
            children.add(child);
            child.setParent(this);
            if (child.getId() != -1) {
                registerChild(child.getId(), child);
            }
        }
    }

    public void addView(View child, int index) {
        if (child != null) {
            if (index < 0 || index > children.size()) {
                children.add(child);
            } else {
                children.add(index, child);
            }
            child.setParent(this);
            if (child.getId() != -1) {
                registerChild(child.getId(), child);
            }
        }
    }

    public void addView(View child, LayoutParams params) {
        if (child != null) {
            child.setLayoutParams(params);
            addView(child);
        }
    }

    public void removeView(View child) {
        if (child != null) {
            children.remove(child);
            child.setParent(null);
        }
    }

    public void removeAllViews() {
        for (View c : children) {
            c.setParent(null);
        }
        children.clear();
    }

    public int getChildCount() {
        return children.size();
    }

    public View getChildAt(int index) {
        if (index >= 0 && index < children.size()) {
            return children.get(index);
        }
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends View> T findViewById(int id) {
        T found = super.findViewById(id);
        if (found != null) {
            return found;
        }
        for (View child : children) {
            T childFound = child.findViewById(id);
            if (childFound != null) {
                return childFound;
            }
        }
        return null;
    }

    public static class LayoutParams {
        public static final int MATCH_PARENT = -1;
        public static final int WRAP_CONTENT = -2;

        public int width;
        public int height;

        public LayoutParams() {}

        public LayoutParams(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    public static class MarginLayoutParams extends LayoutParams {
        public int leftMargin, topMargin, rightMargin, bottomMargin;

        public MarginLayoutParams() {
            super();
        }

        public MarginLayoutParams(int width, int height) {
            super(width, height);
        }

        public void setMargins(int left, int top, int right, int bottom) {
            this.leftMargin = left;
            this.topMargin = top;
            this.rightMargin = right;
            this.bottomMargin = bottom;
        }
    }
}
