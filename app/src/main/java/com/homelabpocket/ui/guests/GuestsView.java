// SPDX-License-Identifier: GPL-3.0-or-later
// Homelab Pocket — Guest VMs & Containers Operations View
package com.homelabpocket.ui.guests;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.homelabpocket.HomelabApp;
import com.homelabpocket.R;
import com.homelabpocket.api.ApiException;
import com.homelabpocket.api.FavoritesStore;
import com.homelabpocket.api.PveApiDriver;
import com.homelabpocket.model.AuthCredentials;
import com.homelabpocket.model.GuestInfo;
import com.homelabpocket.model.ServerConfig;
import com.homelabpocket.model.TaskResult;
import com.homelabpocket.ui.MainActivity;
import com.homelabpocket.ui.terminal.TerminalActivity;
import com.homelabpocket.ui.vms.VmDetailActivity;
import com.homelabpocket.util.JsonUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class GuestsView {

    private final MainActivity activity;
    private final View root;

    private final Button btnRefresh;
    private final EditText etSearch;
    private final Button btnFilterAll;
    private final Button btnFilterRunning;
    private final Button btnFilterStopped;
    private final Button btnFilterFavs;
    private final TextView tvError;
    private final LinearLayout layoutGuests;
    private final LinearLayout layoutEmptyGuests;

    private int activeFilter = 0; // 0=All, 1=Running, 2=Stopped, 3=Favs
    private String currentSearchQuery = "";
    private List<GuestInfo> lastGuestList = new ArrayList<>();

    public GuestsView(MainActivity activity) {
        this.activity = activity;
        this.root = LayoutInflater.from(activity).inflate(R.layout.fragment_guests, null);

        btnRefresh = root.findViewById(R.id.btn_refresh_guests);
        etSearch = root.findViewById(R.id.et_search);
        btnFilterAll = root.findViewById(R.id.filter_all);
        btnFilterRunning = root.findViewById(R.id.filter_running);
        btnFilterStopped = root.findViewById(R.id.filter_stopped);
        btnFilterFavs = root.findViewById(R.id.filter_favorites);
        tvError = root.findViewById(R.id.tv_error);
        layoutGuests = root.findViewById(R.id.layout_guests);
        layoutEmptyGuests = root.findViewById(R.id.layout_empty_guests);

        setupListeners();
    }

    public View getView() {
        return root;
    }

    private void setupListeners() {
        btnRefresh.setOnClickListener(v -> activity.refreshData());

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                currentSearchQuery = s != null ? s.toString().trim().toLowerCase(Locale.US) : "";
                applyFiltersAndRender();
            }
            @Override
            public void afterTextChanged(Editable s) {}
        });

        btnFilterAll.setOnClickListener(v -> setFilter(0));
        btnFilterRunning.setOnClickListener(v -> setFilter(1));
        btnFilterStopped.setOnClickListener(v -> setFilter(2));
        btnFilterFavs.setOnClickListener(v -> setFilter(3));
    }

    private void setFilter(int filter) {
        activeFilter = filter;
        updateFilterButtonStyles();
        applyFiltersAndRender();
    }

    private void updateFilterButtonStyles() {
        int colorActiveBg = R.drawable.bg_btn_phosphor;
        int colorInactiveBg = R.drawable.bg_btn_outline;
        int textActive = activity.getResources().getColor(R.color.matrix_obsidian);
        int textInactive = activity.getResources().getColor(R.color.matrix_text_secondary);

        btnFilterAll.setBackgroundResource(activeFilter == 0 ? colorActiveBg : colorInactiveBg);
        btnFilterAll.setTextColor(activeFilter == 0 ? textActive : textInactive);

        btnFilterRunning.setBackgroundResource(activeFilter == 1 ? colorActiveBg : colorInactiveBg);
        btnFilterRunning.setTextColor(activeFilter == 1 ? textActive : textInactive);

        btnFilterStopped.setBackgroundResource(activeFilter == 2 ? colorActiveBg : colorInactiveBg);
        btnFilterStopped.setTextColor(activeFilter == 2 ? textActive : textInactive);

        btnFilterFavs.setBackgroundResource(activeFilter == 3 ? colorActiveBg : colorInactiveBg);
        btnFilterFavs.setTextColor(activeFilter == 3 ? textActive : textInactive);
    }

    public void render(List<GuestInfo> guests, boolean isLoading, String error) {
        this.lastGuestList = guests != null ? guests : new ArrayList<>();

        if (error != null && !error.isEmpty()) {
            tvError.setVisibility(View.VISIBLE);
            tvError.setText(error);
        } else {
            tvError.setVisibility(View.GONE);
        }

        applyFiltersAndRender();
    }

    private void applyFiltersAndRender() {
        layoutGuests.removeAllViews();
        FavoritesStore favStore = HomelabApp.getInstance().getFavoritesStore();

        List<GuestInfo> filtered = new ArrayList<>();
        for (GuestInfo g : lastGuestList) {
            String favKey = g.serverId + ":" + g.node + ":" + g.vmid;
            boolean isFav = favStore.isFavorite(favKey);

            if (activeFilter == 1 && g.state != GuestInfo.RunState.RUNNING) continue;
            if (activeFilter == 2 && g.state != GuestInfo.RunState.STOPPED) continue;
            if (activeFilter == 3 && !isFav) continue;

            if (!currentSearchQuery.isEmpty()) {
                String matchTarget = (g.name + " " + g.vmid + " " + g.node).toLowerCase(Locale.US);
                if (!matchTarget.contains(currentSearchQuery)) continue;
            }

            filtered.add(g);
        }

        if (filtered.isEmpty()) {
            layoutEmptyGuests.setVisibility(View.VISIBLE);
        } else {
            layoutEmptyGuests.setVisibility(View.GONE);
            for (GuestInfo g : filtered) {
                View card = LayoutInflater.from(activity).inflate(R.layout.item_guest, null);
                populateGuestCard(card, g);
                layoutGuests.addView(card);
            }
        }
    }

    private void populateGuestCard(View card, GuestInfo g) {
        FavoritesStore favStore = HomelabApp.getInstance().getFavoritesStore();
        String favKey = g.serverId + ":" + g.node + ":" + g.vmid;

        ImageButton btnFav = card.findViewById(R.id.btn_favorite);
        TextView tvName = card.findViewById(R.id.tv_guest_name);
        TextView tvVmidNode = card.findViewById(R.id.tv_guest_vmid_node);
        TextView tvType = card.findViewById(R.id.tv_guest_type);
        TextView tvStatus = card.findViewById(R.id.tv_guest_status);
        View layoutMetrics = card.findViewById(R.id.layout_guest_metrics);
        TextView tvCpu = card.findViewById(R.id.tv_guest_cpu);
        TextView tvRam = card.findViewById(R.id.tv_guest_ram);
        ProgressBar progCpu = card.findViewById(R.id.progress_guest_cpu);

        Button btnStart = card.findViewById(R.id.btn_guest_start);
        Button btnShutdown = card.findViewById(R.id.btn_guest_shutdown);
        Button btnReboot = card.findViewById(R.id.btn_guest_reboot);
        Button btnForceStop = card.findViewById(R.id.btn_guest_force_stop);
        Button btnConsole = card.findViewById(R.id.btn_guest_console);

        // Name & Identity
        tvName.setText(g.name);
        tvVmidNode.setText(com.homelabpocket.ui.UiPresentationPolicy.formatGuestHostLabel(g));

        // Type Badge
        if (g.type == GuestInfo.GuestType.LXC) {
            tvType.setText("LXC");
            tvType.setBackgroundResource(R.drawable.bg_chip_lxc);
            tvType.setTextColor(activity.getResources().getColor(R.color.matrix_cyan));
        } else {
            tvType.setText("VM");
            tvType.setBackgroundResource(R.drawable.bg_chip_qemu);
            tvType.setTextColor(activity.getResources().getColor(R.color.matrix_phosphor));
        }

        // Status Badge
        boolean isRunning = g.state == GuestInfo.RunState.RUNNING;
        if (isRunning) {
            tvStatus.setText("RUNNING");
            tvStatus.setBackgroundResource(R.drawable.bg_chip_running);
            tvStatus.setTextColor(activity.getResources().getColor(R.color.matrix_phosphor));
            layoutMetrics.setVisibility(View.VISIBLE);

            int cpuPct = (int) Math.round(!Double.isNaN(g.cpuUsage) ? g.cpuUsage * 100 : 0);
            tvCpu.setText("CPU: " + cpuPct + "%");
            progCpu.setProgress(cpuPct);
            tvRam.setText("RAM: " + JsonUtil.formatBytes(g.memUsed) + " / " + JsonUtil.formatBytes(g.memMax));

            btnStart.setVisibility(View.GONE);
            btnShutdown.setVisibility(View.VISIBLE);
            btnReboot.setVisibility(View.VISIBLE);
            btnForceStop.setVisibility(View.VISIBLE);
        } else {
            tvStatus.setText("STOPPED");
            tvStatus.setBackgroundResource(R.drawable.bg_chip_stopped);
            tvStatus.setTextColor(activity.getResources().getColor(R.color.matrix_text_muted));
            layoutMetrics.setVisibility(View.GONE);

            btnStart.setVisibility(View.VISIBLE);
            btnShutdown.setVisibility(View.GONE);
            btnReboot.setVisibility(View.GONE);
            btnForceStop.setVisibility(View.GONE);
        }

        // Favorites Star
        boolean isFav = favStore.isFavorite(favKey);
        btnFav.setColorFilter(isFav ? activity.getResources().getColor(R.color.matrix_amber)
                                    : activity.getResources().getColor(R.color.matrix_text_muted));
        btnFav.setOnClickListener(v -> {
            favStore.toggleFavorite(favKey);
            boolean newFav = favStore.isFavorite(favKey);
            btnFav.setColorFilter(newFav ? activity.getResources().getColor(R.color.matrix_amber)
                                         : activity.getResources().getColor(R.color.matrix_text_muted));
        });

        ServerConfig serverConfig = HomelabApp.getInstance().getServerConfigStore().findById(g.serverId);
        boolean isPdm = com.homelabpocket.ui.UiPresentationPolicy.isPdm(serverConfig);
        TextView tvPdmNotice = card.findViewById(R.id.tv_guest_pdm_notice);

        if (isPdm) {
            btnStart.setVisibility(View.GONE);
            btnShutdown.setVisibility(View.GONE);
            btnReboot.setVisibility(View.GONE);
            btnForceStop.setVisibility(View.GONE);
            btnConsole.setVisibility(View.GONE);
            if (tvPdmNotice != null) {
                tvPdmNotice.setVisibility(View.VISIBLE);
            }
        } else {
            if (tvPdmNotice != null) {
                tvPdmNotice.setVisibility(View.GONE);
            }
            // Power Actions with double-submit prevention and task polling
            btnStart.setOnClickListener(v -> executePowerAction(g, "start"));
            btnShutdown.setOnClickListener(v -> executePowerAction(g, "shutdown"));
            btnReboot.setOnClickListener(v -> executePowerAction(g, "reboot"));
            btnForceStop.setOnClickListener(v -> confirmForceStop(g));

            // Console / Terminal Action
            if (g.type == GuestInfo.GuestType.LXC) {
                btnConsole.setText("Shell >_");
                btnConsole.setOnClickListener(v -> {
                    Intent intent = new Intent(activity, TerminalActivity.class);
                    intent.putExtra(TerminalActivity.EXTRA_NODE, g.node);
                    intent.putExtra(TerminalActivity.EXTRA_VMID, g.vmid);
                    intent.putExtra(TerminalActivity.EXTRA_SERVER_ID, g.serverId);
                    intent.putExtra(TerminalActivity.EXTRA_TYPE, "LXC");
                    intent.putExtra(TerminalActivity.EXTRA_TITLE, g.name + " [" + g.vmid + "] (LXC Shell)");
                    activity.startActivity(intent);
                });
            } else {
                btnConsole.setText("Console");
                btnConsole.setOnClickListener(v -> showQemuConsoleDisclosure(g));
            }
        }

        card.setOnClickListener(v -> {
            Intent intent = new Intent(activity, VmDetailActivity.class);
            intent.putExtra(VmDetailActivity.EXTRA_VMID, g.vmid);
            intent.putExtra(VmDetailActivity.EXTRA_NODE, g.node);
            intent.putExtra(VmDetailActivity.EXTRA_SERVER_ID, g.serverId);
            intent.putExtra(VmDetailActivity.EXTRA_TYPE, g.type.name());
            activity.startActivity(intent);
        });
    }

    private void confirmForceStop(GuestInfo g) {
        ServerConfig srv = HomelabApp.getInstance().getServerConfigStore().findById(g.serverId);
        String serverName = srv != null ? srv.name : g.serverId;

        View dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_confirm_stop, null);
        TextView tvMsg = dialogView.findViewById(R.id.tv_dialog_message);
        Button btnCancel = dialogView.findViewById(R.id.btn_dialog_cancel);
        Button btnConfirm = dialogView.findViewById(R.id.btn_dialog_confirm);

        String detailMsg = "WARNING: Force stop immediately cuts power to the guest (equivalent to pulling the physical power cord). Unsaved data in the guest may be corrupted.\n\n"
                + "Target Identity:\n"
                + "• Server: " + serverName + "\n"
                + "• Host Node: " + g.node + "\n"
                + "• Guest Type: " + g.type.name() + "\n"
                + "• VMID: " + g.vmid + " (" + g.name + ")\n\n"
                + "Are you sure you want to proceed?";

        tvMsg.setText(detailMsg);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(dialogView)
                .setCancelable(true)
                .create();

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnConfirm.setOnClickListener(v -> {
            dialog.dismiss();
            executePowerAction(g, "stop");
        });

        dialog.show();
    }

    private void showQemuConsoleDisclosure(GuestInfo g) {
        View dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_qemu_console, null);
        TextView tvMsg = dialogView.findViewById(R.id.tv_qemu_disclosure_msg);
        Button btnSsh = dialogView.findViewById(R.id.btn_open_external_ssh);
        Button btnBrowser = dialogView.findViewById(R.id.btn_open_browser_vnc);
        Button btnClose = dialogView.findViewById(R.id.btn_close_disclosure);

        ServerConfig server = HomelabApp.getInstance().getServerConfigStore().findById(g.serverId);
        String host = server != null ? server.host : "pve-host";
        int port = server != null ? server.port : 8006;

        tvMsg.setText(activity.getString(R.string.qemu_terminal_unavailable_msg, host, port));

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(dialogView)
                .setCancelable(true)
                .create();

        btnSsh.setOnClickListener(v -> {
            dialog.dismiss();
            // Launch external SSH intent safely
            try {
                Intent sshIntent = new Intent(Intent.ACTION_VIEW);
                sshIntent.setData(Uri.parse("ssh://" + host));
                activity.startActivity(Intent.createChooser(sshIntent, "Choose SSH Client"));
            } catch (Exception e) {
                Toast.makeText(activity, "No SSH client installed on device.", Toast.LENGTH_SHORT).show();
            }
        });

        btnBrowser.setOnClickListener(v -> {
            dialog.dismiss();
            try {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW);
                browserIntent.setData(Uri.parse("https://" + host + ":" + port + "/#v1:0:=qemu%2F" + g.vmid));
                activity.startActivity(browserIntent);
            } catch (Exception e) {
                Toast.makeText(activity, "Cannot launch browser: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        btnClose.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private void executePowerAction(GuestInfo g, String action) {
        // Enforce session lock before action
        if (HomelabApp.getInstance().getSessionManager().isLocked()) {
            Toast.makeText(activity, "Action blocked: Session is locked. Please unlock in Settings first.", Toast.LENGTH_LONG).show();
            return;
        }

        // PDM misrouting prevention
        ServerConfig cfg = HomelabApp.getInstance().getServerConfigStore().findById(g.serverId);
        if (cfg != null && cfg.type == ServerConfig.ServerType.PDM) {
            Toast.makeText(activity, "Guest power actions are disabled on PDM connections to prevent misrouting. Connect directly to the host's standalone PVE endpoint.", Toast.LENGTH_LONG).show();
            return;
        }

        String actionKey = com.homelabpocket.api.ActionGuard.buildGuestKey(g.serverId, g.node, g.type.name(), g.vmid, action);

        // Double-submit prevention via production ActionGuard
        if (!com.homelabpocket.api.ActionGuard.getInstance().tryAcquire(actionKey)) {
            Toast.makeText(activity, R.string.double_submit_blocked, Toast.LENGTH_SHORT).show();
            return;
        }

        new PowerActionTask(g, cfg, action, actionKey).execute();
    }

    private class PowerActionTask extends AsyncTask<Void, TaskResult, TaskResult> {
        private final GuestInfo guest;
        private final ServerConfig server;
        private final String action;
        private final String actionKey;
        private ProgressDialog progressDialog;
        private String errorMessage;

        PowerActionTask(GuestInfo guest, ServerConfig server, String action, String actionKey) {
            this.guest = guest;
            this.server = server;
            this.action = action;
            this.actionKey = actionKey;
        }

        @Override
        protected void onPreExecute() {
            progressDialog = new ProgressDialog(activity);
            progressDialog.setTitle("Proxmox Operation: " + action.toUpperCase());
            String serverName = server != null ? server.name : guest.serverId;
            progressDialog.setMessage("Initiating " + action.toUpperCase() + "…\n\n"
                    + "Target: " + guest.type.name() + " " + guest.vmid + " (" + guest.name + ")\n"
                    + "Host: " + guest.node + " | Server: " + serverName + "\n"
                    + "Status: Dispatching request…");
            progressDialog.setCancelable(true);
            progressDialog.setOnCancelListener(dialog -> cancel(true));
            progressDialog.show();
        }

        @Override
        protected TaskResult doInBackground(Void... voids) {
            HomelabApp app = HomelabApp.getInstance();
            try {
                if (server == null) {
                    errorMessage = "Server configuration not found.";
                    com.homelabpocket.api.ActionGuard.getInstance().release(actionKey);
                    return null;
                }
                if (server.type == ServerConfig.ServerType.PDM) {
                    errorMessage = "Guest power actions via PDM are disabled in this release to prevent endpoint misrouting. Connect directly to the host's standalone PVE endpoint.";
                    com.homelabpocket.api.ActionGuard.getInstance().release(actionKey);
                    return null;
                }
                AuthCredentials creds = app.getSessionManager().getCredentials(server);
                if (creds == null) {
                    errorMessage = app.getSessionManager().isLocked() ?
                            "Session is locked. Please unlock in Settings." :
                            "Authentication failed: no valid credentials.";
                    com.homelabpocket.api.ActionGuard.getInstance().release(actionKey);
                    return null;
                }

                PveApiDriver pve = new PveApiDriver(server);
                String upid;
                switch (action) {
                    case "start":
                        upid = pve.startGuest(guest.node, guest.vmid, guest.type, creds);
                        break;
                    case "shutdown":
                        upid = pve.shutdownGuest(guest.node, guest.vmid, guest.type, creds);
                        break;
                    case "reboot":
                        upid = pve.rebootGuest(guest.node, guest.vmid, guest.type, creds);
                        break;
                    case "stop":
                        upid = pve.forceStopGuest(guest.node, guest.vmid, guest.type, creds);
                        break;
                    default:
                        errorMessage = "Unknown action: " + action;
                        com.homelabpocket.api.ActionGuard.getInstance().release(actionKey);
                        return null;
                }

                // Shared bounded polling owner preserves action guard until finished
                return com.homelabpocket.api.TaskPoller.getInstance().pollUntilFinished(
                        actionKey,
                        guest.node,
                        upid,
                        creds,
                        (nodeName, taskId, auth, budgetMs) -> pve.pollTask(nodeName, taskId, auth, budgetMs),
                        new com.homelabpocket.api.TaskPoller.PollingCallback() {
                            @Override
                            public void onProgress(TaskResult progress) {
                                publishProgress(progress);
                            }

                            @Override
                            public boolean isCancelled() {
                                return PowerActionTask.this.isCancelled();
                            }
                        },
                        60_000L,
                        1500L
                );

            } catch (ApiException e) {
                com.homelabpocket.api.ActionGuard.getInstance().release(actionKey);
                errorMessage = e.getUserMessage();
                return null;
            } catch (Exception e) {
                com.homelabpocket.api.ActionGuard.getInstance().release(actionKey);
                errorMessage = e.getMessage();
                return null;
            }
        }

        @Override
        protected void onProgressUpdate(TaskResult... values) {
            if (progressDialog != null && progressDialog.isShowing() && values != null && values.length > 0) {
                TaskResult r = values[0];
                String serverName = server != null ? server.name : guest.serverId;
                progressDialog.setMessage("Executing " + action.toUpperCase() + "…\n\n"
                        + "Target: " + guest.type.name() + " " + guest.vmid + " (" + guest.name + ")\n"
                        + "Host: " + guest.node + " | Server: " + serverName + "\n"
                        + "Status: RUNNING (" + (r.upid != null ? r.upid.substring(0, Math.min(30, r.upid.length())) + "…" : "task running") + ")");
            }
        }

        @Override
        protected void onPostExecute(TaskResult result) {
            if (progressDialog != null && progressDialog.isShowing()) {
                progressDialog.dismiss();
            }

            String serverName = server != null ? server.name : guest.serverId;
            String targetInfo = guest.type.name() + " " + guest.vmid + " (" + guest.name + ") on " + guest.node + " [" + serverName + "]";

            if (result != null) {
                switch (result.state) {
                    case OK:
                        Toast.makeText(activity, "Success: " + action + " completed for " + targetInfo, Toast.LENGTH_SHORT).show();
                        break;
                    case ERROR:
                        new AlertDialog.Builder(activity)
                                .setTitle("Task Failed")
                                .setMessage("Operation '" + action + "' failed on Proxmox.\n\n"
                                        + "Target: " + targetInfo + "\n\n"
                                        + "Details: " + (result.exitStatus != null ? result.exitStatus : "Unknown error"))
                                .setPositiveButton("OK", null)
                                .show();
                        break;
                    case TIMEOUT:
                        new AlertDialog.Builder(activity)
                                .setTitle("Task Timed Out")
                                .setMessage("Operation '" + action + "' did not complete within the 60s timeout.\n\n"
                                        + "Target: " + targetInfo + "\n\n"
                                        + "Details: " + result.getSummary())
                                .setPositiveButton("OK", null)
                                .show();
                        break;
                    case UNKNOWN:
                    default:
                        new AlertDialog.Builder(activity)
                                .setTitle("Task Status Unknown")
                                .setMessage("Operation '" + action + "' returned indeterminate status.\n\n"
                                        + "Target: " + targetInfo + "\n\n"
                                        + "Details: " + result.getSummary())
                                .setPositiveButton("OK", null)
                                .show();
                        break;
                }
            } else if (errorMessage != null) {
                new AlertDialog.Builder(activity)
                        .setTitle("Operation Error")
                        .setMessage("Failed to execute '" + action + "' on " + targetInfo + ":\n\n" + errorMessage)
                        .setPositiveButton("OK", null)
                        .show();
            }

            activity.refreshData();
        }

        @Override
        protected void onCancelled(TaskResult result) {
            if (progressDialog != null && progressDialog.isShowing()) {
                progressDialog.dismiss();
            }
            Toast.makeText(activity, "Operation cancelled: " + action + " on " + guest.name, Toast.LENGTH_SHORT).show();
            activity.refreshData();
        }
    }
}
