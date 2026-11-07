// SPDX-License-Identifier: GPL-3.0-or-later
package android.view;

import android.content.Context;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.homelabpocket.R;

/**
 * JVM test double for android.view.LayoutInflater allowing view inflating
 * without Android runtime stub exceptions.
 */
public class LayoutInflater {
    private final Context context;

    protected LayoutInflater(Context context) {
        this.context = context;
    }

    public static LayoutInflater from(Context context) {
        return new LayoutInflater(context);
    }

    public View inflate(int resource, ViewGroup root) {
        return inflate(resource, root, root != null);
    }

    public View inflate(int resource, ViewGroup root, boolean attachToRoot) {
        View card = new View(context);
        card.registerChild(R.id.btn_favorite, new ImageButton(context));
        card.registerChild(R.id.tv_guest_name, new TextView(context));
        card.registerChild(R.id.tv_guest_vmid_node, new TextView(context));
        card.registerChild(R.id.tv_guest_type, new TextView(context));
        card.registerChild(R.id.tv_guest_status, new TextView(context));
        card.registerChild(R.id.layout_guest_metrics, new LinearLayout(context));
        card.registerChild(R.id.tv_guest_cpu, new TextView(context));
        card.registerChild(R.id.tv_guest_ram, new TextView(context));
        card.registerChild(R.id.progress_guest_cpu, new ProgressBar(context));
        card.registerChild(R.id.btn_guest_start, new Button(context));
        card.registerChild(R.id.btn_guest_shutdown, new Button(context));
        card.registerChild(R.id.btn_guest_reboot, new Button(context));
        card.registerChild(R.id.btn_guest_force_stop, new Button(context));
        card.registerChild(R.id.btn_guest_console, new Button(context));
        if (root != null && attachToRoot) {
            root.addView(card);
        }
        return card;
    }
}
