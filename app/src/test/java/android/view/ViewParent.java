// SPDX-License-Identifier: GPL-3.0-or-later
package android.view;

/**
 * JVM test double for android.view.ViewParent.
 */
public interface ViewParent {
    ViewParent getParent();
    void requestLayout();
}
