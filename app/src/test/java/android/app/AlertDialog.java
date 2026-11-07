// SPDX-License-Identifier: GPL-3.0-or-later
package android.app;

import android.content.Context;
import android.content.DialogInterface;
import android.view.View;

/**
 * JVM test double for android.app.AlertDialog capturing displayed dialogs
 * for test verification without throwing stub exceptions.
 */
public class AlertDialog extends Dialog {
    private static AlertDialog lastShownDialog;
    private static int showCount = 0;

    private CharSequence message;
    private CharSequence positiveText;
    private DialogInterface.OnClickListener positiveListener;
    private CharSequence negativeText;
    private DialogInterface.OnClickListener negativeListener;

    public AlertDialog(Context context) {
        super(context);
    }

    public AlertDialog(Context context, int themeResId) {
        super(context, themeResId);
    }

    public void setMessage(CharSequence message) {
        this.message = message;
    }

    public CharSequence getMessage() {
        return message;
    }

    public CharSequence getPositiveText() {
        return positiveText;
    }

    public DialogInterface.OnClickListener getPositiveListener() {
        return positiveListener;
    }

    public CharSequence getNegativeText() {
        return negativeText;
    }

    public DialogInterface.OnClickListener getNegativeListener() {
        return negativeListener;
    }

    @Override
    public void show() {
        super.show();
        lastShownDialog = this;
        showCount++;
    }

    public static AlertDialog getLastShownDialog() {
        return lastShownDialog;
    }

    public static int getShowCount() {
        return showCount;
    }

    public static void reset() {
        lastShownDialog = null;
        showCount = 0;
    }

    public static class Builder {
        private final Context context;
        private CharSequence title;
        private CharSequence message;
        private CharSequence positiveText;
        private DialogInterface.OnClickListener positiveListener;
        private CharSequence negativeText;
        private DialogInterface.OnClickListener negativeListener;
        private View customView;

        public Builder(Context context) {
            this.context = context;
        }

        public Builder setTitle(CharSequence title) {
            this.title = title;
            return this;
        }

        public Builder setTitle(int titleId) {
            this.title = String.valueOf(titleId);
            return this;
        }

        public Builder setMessage(CharSequence message) {
            this.message = message;
            return this;
        }

        public Builder setMessage(int messageId) {
            this.message = String.valueOf(messageId);
            return this;
        }

        public Builder setPositiveButton(CharSequence text, DialogInterface.OnClickListener listener) {
            this.positiveText = text;
            this.positiveListener = listener;
            return this;
        }

        public Builder setPositiveButton(int textId, DialogInterface.OnClickListener listener) {
            return setPositiveButton(String.valueOf(textId), listener);
        }

        public Builder setNegativeButton(CharSequence text, DialogInterface.OnClickListener listener) {
            this.negativeText = text;
            this.negativeListener = listener;
            return this;
        }

        public Builder setNegativeButton(int textId, DialogInterface.OnClickListener listener) {
            return setNegativeButton(String.valueOf(textId), listener);
        }

        public Builder setNeutralButton(CharSequence text, DialogInterface.OnClickListener listener) {
            return this;
        }

        public Builder setNeutralButton(int textId, DialogInterface.OnClickListener listener) {
            return this;
        }

        public Builder setView(View view) {
            this.customView = view;
            return this;
        }

        public AlertDialog create() {
            AlertDialog dialog = new AlertDialog(context);
            dialog.setTitle(title);
            dialog.setMessage(message);
            dialog.positiveText = positiveText;
            dialog.positiveListener = positiveListener;
            dialog.negativeText = negativeText;
            dialog.negativeListener = negativeListener;
            if (customView != null) {
                dialog.setContentView(customView);
            }
            return dialog;
        }

        public AlertDialog show() {
            AlertDialog dialog = create();
            dialog.show();
            return dialog;
        }
    }
}
