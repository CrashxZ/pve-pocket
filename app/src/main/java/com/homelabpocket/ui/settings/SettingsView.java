// SPDX-License-Identifier: GPL-3.0-or-later
// Homelab Pocket — Settings & Connection Management View
package com.homelabpocket.ui.settings;

import android.app.AlertDialog;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.homelabpocket.HomelabApp;
import com.homelabpocket.R;
import com.homelabpocket.model.ServerConfig;
import com.homelabpocket.security.TlsVerificationStatus;
import com.homelabpocket.ui.MainActivity;

import java.util.List;

public class SettingsView {

    private final MainActivity activity;
    private final View root;

    private final Button btnAddServer;
    private final LinearLayout layoutServerList;
    private final Button btnLockSession;

    public SettingsView(MainActivity activity) {
        this.activity = activity;
        this.root = LayoutInflater.from(activity).inflate(R.layout.fragment_settings, null);

        btnAddServer = root.findViewById(R.id.btn_add_server);
        layoutServerList = root.findViewById(R.id.layout_server_list);
        btnLockSession = root.findViewById(R.id.btn_lock_session);

        setupListeners();
    }

    public View getView() {
        return root;
    }

    private void setupListeners() {
        btnAddServer.setOnClickListener(v -> {
            Intent intent = new Intent(activity, AddServerActivity.class);
            activity.startActivityForResult(intent, MainActivity.REQ_ADD_SERVER);
        });

        btnLockSession.setOnClickListener(v -> {
            if (HomelabApp.getInstance().getSessionManager().isLocked()) {
                promptUnlock();
            } else {
                boolean persisted = HomelabApp.getInstance().getSessionManager().lock();
                render();
                activity.refreshData();
                if (persisted) {
                    Toast.makeText(activity, "Session locked: in-memory auth tickets cleared and credential access blocked.", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(activity, "Session locked in memory, but storage persistence failed.", Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    public void promptUnlock() {
        android.app.KeyguardManager km = (android.app.KeyguardManager) activity.getSystemService(android.content.Context.KEYGUARD_SERVICE);
        if (km != null && km.isDeviceSecure()) {
            Intent intent = km.createConfirmDeviceCredentialIntent(
                    "Unlock PVE Pocket",
                    "Confirm your device PIN, pattern or biometrics to restore session access.");
            if (intent != null) {
                activity.startActivityForResult(intent, MainActivity.REQ_CONFIRM_CREDENTIAL);
                return;
            }
        }

        // Fail-closed security: Device authentication is required to unlock stored credentials.
        // If no lock screen is configured, provide actionable navigation to Android Settings.
        // Session remains strictly locked.
        new AlertDialog.Builder(activity)
                .setTitle("Device Security Required")
                .setMessage("Session unlock requires Android device lock screen security (PIN, pattern, password, or biometrics) to protect stored credentials.\n\n"
                        + "No secure lock screen is currently configured on this device.\n\n"
                        + "Please configure device security in Android Settings to unlock this session.")
                .setPositiveButton("Security Settings", (dialog, which) -> {
                    try {
                        activity.startActivity(new Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS));
                    } catch (Exception e) {
                        try {
                            activity.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
                        } catch (Exception ignored) {
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    public void render() {
        boolean locked = HomelabApp.getInstance().getSessionManager().isLocked();
        if (locked) {
            btnLockSession.setText("Unlock Session 🔓");
            btnLockSession.setBackgroundResource(R.drawable.bg_btn_phosphor);
            btnLockSession.setTextColor(activity.getResources().getColor(R.color.matrix_obsidian));
        } else {
            btnLockSession.setText("Lock Session 🔒");
            btnLockSession.setBackgroundResource(R.drawable.bg_btn_outline);
            btnLockSession.setTextColor(activity.getResources().getColor(R.color.matrix_text_primary));
        }

        layoutServerList.removeAllViews();

        List<ServerConfig> servers = HomelabApp.getInstance().getServerConfigStore().loadAll();
        if (servers.isEmpty()) {
            TextView emptyText = new TextView(activity);
            emptyText.setText("No servers configured.\nTap + Add Server to add your Proxmox VE or PDM instance.");
            emptyText.setTextSize(12);
            emptyText.setTypeface(android.graphics.Typeface.MONOSPACE);
            emptyText.setTextColor(activity.getResources().getColor(R.color.matrix_text_muted));
            emptyText.setPadding(10, 10, 10, 10);
            layoutServerList.addView(emptyText);
            return;
        }

        for (ServerConfig server : servers) {
            LinearLayout cardLayout = new LinearLayout(activity);
            cardLayout.setOrientation(LinearLayout.VERTICAL);
            cardLayout.setBackgroundResource(R.drawable.bg_card_matrix);
            cardLayout.setPadding(24, 24, 24, 24);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, 16);
            cardLayout.setLayoutParams(lp);

            // Title + Driver tag
            LinearLayout header = new LinearLayout(activity);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(android.view.Gravity.CENTER_VERTICAL);

            TextView tvName = new TextView(activity);
            tvName.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
            tvName.setText(server.name);
            tvName.setTextSize(15);
            tvName.setTypeface(null, android.graphics.Typeface.BOLD);
            tvName.setTextColor(activity.getResources().getColor(R.color.matrix_text_primary));
            header.addView(tvName);

            TextView tvType = new TextView(activity);
            tvType.setText(server.type.name());
            tvType.setTextSize(11);
            tvType.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
            tvType.setTextColor(activity.getResources().getColor(server.type == ServerConfig.ServerType.PDM
                    ? R.color.matrix_cyan : R.color.matrix_phosphor));
            header.addView(tvType);

            cardLayout.addView(header);

            // Host + Port
            TextView tvEndpoint = new TextView(activity);
            tvEndpoint.setText("https://" + server.host + ":" + server.port);
            tvEndpoint.setTextSize(12);
            tvEndpoint.setTypeface(android.graphics.Typeface.MONOSPACE);
            tvEndpoint.setTextColor(activity.getResources().getColor(R.color.matrix_text_secondary));
            tvEndpoint.setPadding(0, 6, 0, 6);
            cardLayout.addView(tvEndpoint);

            // Fingerprint status
            TlsVerificationStatus tlsStatus = TlsVerificationStatus.evaluate(server);
            TextView tvFingerprint = new TextView(activity);
            tvFingerprint.setText(tlsStatus.displayText);
            if (tlsStatus.level == TlsVerificationStatus.StatusLevel.DISABLED_INSECURE) {
                tvFingerprint.setTextColor(activity.getResources().getColor(R.color.matrix_danger));
            } else if (tlsStatus.level == TlsVerificationStatus.StatusLevel.PINNED_VERIFIED) {
                tvFingerprint.setTextColor(activity.getResources().getColor(R.color.matrix_phosphor));
            } else {
                tvFingerprint.setTextColor(activity.getResources().getColor(R.color.matrix_amber));
            }
            tvFingerprint.setTextSize(11);
            tvFingerprint.setTypeface(android.graphics.Typeface.MONOSPACE);
            cardLayout.addView(tvFingerprint);

            // Action row
            LinearLayout actionRow = new LinearLayout(activity);
            actionRow.setOrientation(LinearLayout.HORIZONTAL);
            actionRow.setGravity(android.view.Gravity.END);
            actionRow.setPadding(0, 16, 0, 0);

            Button btnEdit = new Button(activity);
            btnEdit.setText("Edit");
            btnEdit.setTextColor(activity.getResources().getColor(R.color.matrix_text_primary));
            btnEdit.setBackgroundResource(R.drawable.bg_btn_outline);
            btnEdit.setTextSize(11);
            btnEdit.setTypeface(android.graphics.Typeface.MONOSPACE);
            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    activity.getResources().getDimensionPixelSize(R.dimen.touch_target_min));
            btnLp.setMargins(0, 0, 12, 0);
            btnEdit.setLayoutParams(btnLp);
            btnEdit.setOnClickListener(v -> {
                if (HomelabApp.getInstance().getSessionManager().isLocked() ||
                        HomelabApp.getInstance().getCredentialStore().isSessionLocked()) {
                    promptUnlock();
                    return;
                }
                Intent intent = new Intent(activity, AddServerActivity.class);
                intent.putExtra(AddServerActivity.EXTRA_SERVER_ID, server.id);
                activity.startActivityForResult(intent, MainActivity.REQ_ADD_SERVER);
            });
            actionRow.addView(btnEdit);

            Button btnDelete = new Button(activity);
            btnDelete.setText("Remove");
            btnDelete.setTextColor(activity.getResources().getColor(R.color.matrix_danger));
            btnDelete.setBackgroundResource(R.drawable.bg_btn_outline);
            btnDelete.setTextSize(11);
            btnDelete.setTypeface(android.graphics.Typeface.MONOSPACE);
            btnDelete.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    activity.getResources().getDimensionPixelSize(R.dimen.touch_target_min)));
            btnDelete.setOnClickListener(v -> confirmDeleteServer(server));
            actionRow.addView(btnDelete);

            cardLayout.addView(actionRow);
            layoutServerList.addView(cardLayout);
        }
    }

    private void confirmDeleteServer(ServerConfig server) {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.delete_server)
                .setMessage(activity.getString(R.string.delete_server_confirm, server.name))
                .setPositiveButton("Remove", (dialog, which) -> {
                    try {
                        HomelabApp.getInstance().getServerConfigStore().remove(server.id);
                        HomelabApp.getInstance().getCredentialStore().clearCredentials(server.id);
                    } catch (Exception e) {
                        Toast.makeText(activity, "Failed to remove server from storage: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                    HomelabApp.getInstance().getSessionManager().invalidateSession(server.id);
                    render();
                    activity.refreshData();
                    Toast.makeText(activity, "Server removed.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
