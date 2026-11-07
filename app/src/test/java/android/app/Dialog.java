// SPDX-License-Identifier: GPL-3.0-or-later
package android.app;

import android.content.Context;
import android.content.DialogInterface;
import android.view.View;

/**
 * JVM test double for android.app.Dialog.
 */
public class Dialog implements DialogInterface {
    protected final Context context;
    private boolean showing = false;
    protected CharSequence title;
    private View contentView;

    public Dialog(Context context) {
        this.context = context;
    }

    public Dialog(Context context, int themeResId) {
        this.context = context;
    }

    @Override
    public void cancel() {
        dismiss();
    }

    @Override
    public void dismiss() {
        showing = false;
    }

    public void show() {
        showing = true;
    }

    public boolean isShowing() {
        return showing;
    }

    public void setTitle(CharSequence title) {
        this.title = title;
    }

    public void setTitle(int titleId) {
        this.title = String.valueOf(titleId);
    }

    public CharSequence getTitle() {
        return title;
    }

    public void setContentView(View view) {
        this.contentView = view;
    }

    public void setContentView(int layoutResID) {}

    public Context getContext() {
        return context;
    }

    @SuppressWarnings("unchecked")
    public <T extends View> T findViewById(int id) {
        if (contentView != null) {
            return contentView.findViewById(id);
        }
        return null;
    }

    public void setOnDismissListener(DialogInterface.OnDismissListener listener) {}
    public void setOnCancelListener(DialogInterface.OnCancelListener listener) {}
}
