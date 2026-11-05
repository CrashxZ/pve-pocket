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
import com.homelabpocket.api.PdmApiDriver;
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
    private PowerActionTask currentPowerTask;

    public void cancelActiveTasks() {
        if (currentPowerTask != null) {
            currentPowerTask.cancel(true);
            if (currentPowerTask.lifecycleOwner != null) {
                currentPowerTask.lifecycleOwner.requestCancel();
            }
        }
    }

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
        boolean isRunning = (g.state == GuestInfo.RunState.RUNNING);
        boolean isStopped = (g.state == GuestInfo.RunState.STOPPED);
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
            btnStart.setEnabled(false);
            btnShutdown.setVisibility(View.VISIBLE);
            btnShutdown.setEnabled(true);
            btnReboot.setVisibility(View.VISIBLE);
            btnReboot.setEnabled(true);
            btnForceStop.setVisibility(View.VISIBLE);
            btnForceStop.setEnabled(true);
        } else if (isStopped) {
            tvStatus.setText("STOPPED");
            tvStatus.setBackgroundResource(R.drawable.bg_chip_stopped);
            tvStatus.setTextColor(activity.getResources().getColor(R.color.matrix_text_muted));
            layoutMetrics.setVisibility(View.GONE);

            btnStart.setVisibility(View.VISIBLE);
            btnStart.setEnabled(true);
            btnShutdown.setVisibility(View.GONE);
            btnShutdown.setEnabled(false);
            btnReboot.setVisibility(View.GONE);
            btnReboot.setEnabled(false);
            btnForceStop.setVisibility(View.GONE);
            btnForceStop.setEnabled(false);
        } else {
            tvStatus.setText("UNKNOWN");
            tvStatus.setBackgroundResource(R.drawable.bg_chip_stopped);
            tvStatus.setTextColor(activity.getResources().getColor(R.color.matrix_text_muted));
            layoutMetrics.setVisibility(View.GONE);

            btnStart.setVisibility(View.GONE);
            btnStart.setEnabled(false);
            btnShutdown.setVisibility(View.GONE);
            btnShutdown.setEnabled(false);
            btnReboot.setVisibility(View.GONE);
            btnReboot.setEnabled(false);
            btnForceStop.setVisibility(View.GONE);
            btnForceStop.setEnabled(false);
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

        if (tvPdmNotice != null) {
            tvPdmNotice.setVisibility(View.GONE);
        }

        btnStart.setOnClickListener(v -> confirmPowerAction(g, "start", isPdm));
        btnShutdown.setOnClickListener(v -> confirmPowerAction(g, "shutdown", isPdm));
        btnReboot.setOnClickListener(v -> confirmPowerAction(g, "reboot", isPdm));
        btnForceStop.setOnClickListener(v -> confirmForceStop(g, isPdm));

        if (isPdm || (!isRunning && !isStopped)) {
            btnConsole.setVisibility(View.GONE);
            btnConsole.setEnabled(false);
        } else {
            btnConsole.setVisibility(View.VISIBLE);
            btnConsole.setEnabled(true);
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

    private void confirmPowerAction(GuestInfo g, String action, boolean isPdm) {
        if (g == null || g.state == GuestInfo.RunState.UNKNOWN || !com.homelabpocket.ui.UiPresentationPolicy.isGuestTargetValid(g, g.node, g.vmid, g.type)) {
            Toast.makeText(activity, "Action blocked: Target not verified, moved, or offline. Please refresh.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (HomelabApp.getInstance().getSessionManager().isLocked()) {
            Toast.makeText(activity, "Action blocked: Session is locked. Please unlock in Settings first.", Toast.LENGTH_LONG).show();
            return;
        }

        String remote = PdmApiDriver.extractRemote(g.node);
        String node = PdmApiDriver.extractNode(g.node);
        ServerConfig srv = HomelabApp.getInstance().getServerConfigStore().findById(g.serverId);
        String serverName = srv != null ? srv.name : g.serverId;
        String guestTypeName = (g.type == GuestInfo.GuestType.LXC) ? "LXC Container" : "QEMU Virtual Machine";

        // Reboot on PDM: Upstream API limitation honest disclosure
        if (isPdm && "reboot".equalsIgnoreCase(action)) {
            new AlertDialog.Builder(activity)
                    .setTitle("Reboot Not Supported by PDM")
                    .setMessage("Guest Reboot is not supported by the Proxmox Datacenter Manager API.\n\n"
                            + "Upstream PDM supports Start, Graceful Shutdown, and Stop operations only.\n\n"
                            + "To restart this guest, please perform a Graceful Shutdown followed by Start.\n\n"
                            + "Target Identity:\n"
                            + "• Server: " + serverName + "\n"
                            + "• Remote: " + (remote.isEmpty() ? "N/A" : remote) + "\n"
                            + "• Node: " + node + "\n"
                            + "• Type: " + guestTypeName + "\n"
                            + "• VMID: " + g.vmid + "\n"
                            + "• Name: " + g.name)
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        String actionTitle = "start".equalsIgnoreCase(action) ? "Confirm Start"
                : "reboot".equalsIgnoreCase(action) ? "Confirm Reboot" : "Confirm Graceful Shutdown";
        String actionVerb = "start".equalsIgnoreCase(action) ? "Start"
                : "reboot".equalsIgnoreCase(action) ? "Reboot" : "Shutdown";

        StringBuilder msg = new StringBuilder();
        msg.append("Are you sure you want to ").append(actionVerb.toLowerCase()).append(" this guest?\n\n");
        msg.append("Target Identity:\n");
        msg.append("• Server: ").append(serverName).append("\n");
        if (isPdm && !remote.isEmpty()) {
            msg.append("• Remote: ").append(remote).append("\n");
        }
        msg.append("• Node: ").append(node).append("\n");
        msg.append("• Type: ").append(guestTypeName).append("\n");
        msg.append("• VMID: ").append(g.vmid).append("\n");
        msg.append("• Name: ").append(g.name);

        new AlertDialog.Builder(activity)
                .setTitle(actionTitle)
                .setMessage(msg.toString())
                .setPositiveButton(actionVerb, (dialog, which) -> executePowerAction(g, action))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmForceStop(GuestInfo g, boolean isPdm) {
        if (g == null || g.state == GuestInfo.RunState.UNKNOWN || !com.homelabpocket.ui.UiPresentationPolicy.isGuestTargetValid(g, g.node, g.vmid, g.type)) {
            Toast.makeText(activity, "Action blocked: Target not verified, moved, or offline. Please refresh.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (HomelabApp.getInstance().getSessionManager().isLocked()) {
            Toast.makeText(activity, "Action blocked: Session is locked. Please unlock in Settings first.", Toast.LENGTH_LONG).show();
            return;
        }

        String remote = PdmApiDriver.extractRemote(g.node);
        String node = PdmApiDriver.extractNode(g.node);
        ServerConfig srv = HomelabApp.getInstance().getServerConfigStore().findById(g.serverId);
        String serverName = srv != null ? srv.name : g.serverId;
        String guestTypeName = (g.type == GuestInfo.GuestType.LXC) ? "LXC Container" : "QEMU Virtual Machine";

        // Step 1: Warning dialog
        StringBuilder warnMsg = new StringBuilder();
        warnMsg.append("WARNING: Force Stop will immediately terminate the virtual machine/container without shutting down the OS cleanly.\n\n");
        warnMsg.append("This may cause DATA LOSS, uncommitted disk cache corruption, or filesystem damage.\n\n");
        warnMsg.append("Target Identity:\n");
        warnMsg.append("• Server: ").append(serverName).append("\n");
        if (isPdm && !remote.isEmpty()) {
            warnMsg.append("• Remote: ").append(remote).append("\n");
        }
        warnMsg.append("• Node: ").append(node).append("\n");
        warnMsg.append("• Type: ").append(guestTypeName).append("\n");
        warnMsg.append("• VMID: ").append(g.vmid).append("\n");
        warnMsg.append("• Name: ").append(g.name).append("\n\n");
        warnMsg.append("Do you want to proceed to the second confirmation?");

        new AlertDialog.Builder(activity)
                .setTitle("Warning: Force Stop")
                .setMessage(warnMsg.toString())
                .setPositiveButton("Proceed...", (dialog, which) -> {
                    // Step 2: Separate second confirmation
                    showSecondForceStopConfirmation(g, isPdm, remote, node, serverName, guestTypeName);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showSecondForceStopConfirmation(GuestInfo g, boolean isPdm, String remote, String node, String serverName, String guestTypeName) {
        new AlertDialog.Builder(activity)
                .setTitle("Second Confirmation: Force Stop")
                .setMessage("Are you ABSOLUTELY SURE you want to force stop " + guestTypeName + " " + g.vmid + " (" + g.name + ")?\n\n"
                        + "This immediate power-kill action cannot be undone.\n\n"
                        + (isPdm && !remote.isEmpty() ? ("• Remote: " + remote + "\n") : "")
                        + "• Node: " + node + "\n"
                        + "• Server: " + serverName)
                .setPositiveButton("Force Stop (Kill)", (dialog, which) -> executePowerAction(g, "stop"))
                .setNegativeButton("Cancel", null)
                .show();
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
        if (g == null || g.vmid <= 0 || g.node == null || g.node.trim().isEmpty() || g.type == null) {
            Toast.makeText(activity, "Action blocked: Invalid guest target.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!com.homelabpocket.ui.UiPresentationPolicy.isGuestTargetValid(g, g.node, g.vmid, g.type)) {
            Toast.makeText(activity, "Action blocked: Target not verified, moved, or offline. Please refresh.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Enforce session lock before action
        if (HomelabApp.getInstance().getSessionManager().isLocked()) {
            Toast.makeText(activity, "Action blocked: Session is locked. Please unlock in Settings first.", Toast.LENGTH_LONG).show();
            return;
        }

        ServerConfig cfg = HomelabApp.getInstance().getServerConfigStore().findById(g.serverId);
        String actionKey = com.homelabpocket.api.ActionGuard.buildGuestResourceKey(g.serverId, g.node, g.type.name(), g.vmid);

        // Double-submit prevention via production ActionGuard
        com.homelabpocket.api.ActionGuard.Lease lease = com.homelabpocket.api.ActionGuard.getInstance().acquireLease(actionKey);
        if (lease == null) {
            Toast.makeText(activity, R.string.double_submit_blocked, Toast.LENGTH_SHORT).show();
            return;
        }

        com.homelabpocket.api.ActionGuard.TaskLifecycleOwner lifecycleOwner = new com.homelabpocket.api.ActionGuard.TaskLifecycleOwner(lease);

        currentPowerTask = new PowerActionTask(g, cfg, action, lifecycleOwner);
        currentPowerTask.execute();
    }

    private class PowerActionTask extends AsyncTask<Void, TaskResult, TaskResult> {
        private final GuestInfo guest;
        private final ServerConfig server;
        private final String action;
        private final com.homelabpocket.api.ActionGuard.TaskLifecycleOwner lifecycleOwner;
        private ProgressDialog progressDialog;
        private String errorMessage;

        PowerActionTask(GuestInfo guest, ServerConfig server, String action, com.homelabpocket.api.ActionGuard.TaskLifecycleOwner lifecycleOwner) {
            this.guest = guest;
            this.server = server;
            this.action = action;
            this.lifecycleOwner = lifecycleOwner;
        }

        void releaseGuardOnce() {
            if (lifecycleOwner != null) {
                lifecycleOwner.release();
            }
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
            progressDialog.setOnCancelListener(dialog -> {
                cancel(true);
                if (lifecycleOwner != null) {
                    lifecycleOwner.requestCancel();
                }
            });
            progressDialog.show();
        }

        @Override
        protected TaskResult doInBackground(Void... voids) {
            HomelabApp app = HomelabApp.getInstance();
            try {
                if (lifecycleOwner != null) {
                    lifecycleOwner.markStarted();
                }

                long totalBudgetMs = 60_000L;
                long startNanos = System.nanoTime();
                long deadlineNanos = startNanos + totalBudgetMs * 1_000_000L;

                if (isCancelled() || (lifecycleOwner != null && lifecycleOwner.isCancelled())) {
                    releaseGuardOnce();
                    return null;
                }

                if (server == null) {
                    errorMessage = "Server configuration not found.";
                    releaseGuardOnce();
                    return null;
                }

                if (deadlineNanos - System.nanoTime() <= 0) {
                    errorMessage = "Operation budget exhausted before credential acquisition.";
                    releaseGuardOnce();
                    return null;
                }

                AuthCredentials creds = app.getSessionManager().getCredentialsWithDeadline(server, deadlineNanos);
                if (creds == null) {
                    errorMessage = app.getSessionManager().isLocked() ?
                            "Session is locked. Please unlock in Settings." :
                            "Authentication failed: no valid credentials.";
                    releaseGuardOnce();
                    return null;
                }

                if (isCancelled() || (lifecycleOwner != null && lifecycleOwner.isCancelled()) || System.nanoTime() >= deadlineNanos) {
                    releaseGuardOnce();
                    return null;
                }

                // Fresh target resolution before POST under the same absolute action budget
                if (deadlineNanos - System.nanoTime() <= 0) {
                    errorMessage = "Operation budget exhausted before target resolution.";
                    releaseGuardOnce();
                    return null;
                }
                GuestInfo freshTarget = null;
                if (server.type == ServerConfig.ServerType.PDM) {
                    PdmApiDriver pdmResolve = new PdmApiDriver(server, app.getSessionManager());
                    freshTarget = pdmResolve.fetchGuestDetailWithDeadline(guest.node, guest.vmid, guest.type, creds, deadlineNanos);
                } else {
                    PveApiDriver pveResolve = new PveApiDriver(server);
                    List<GuestInfo> guests = pveResolve.fetchAllGuestsWithDeadline(guest.node, creds, deadlineNanos);
                    for (GuestInfo g : guests) {
                        if (g.vmid == guest.vmid && g.type == guest.type && guest.node.equals(g.node)) {
                            freshTarget = g;
                            break;
                        }
                    }
                }

                // Strict validation on freshly resolved target: reject removed, moved, offline, or unknown target without POST
                if (freshTarget == null) {
                    errorMessage = "Target verification failed: guest [" + guest.vmid + "] removed or moved from host " + guest.node + ". No operation dispatched.";
                    releaseGuardOnce();
                    return null;
                }
                if (!com.homelabpocket.ui.UiPresentationPolicy.isGuestTargetValid(freshTarget, guest.node, guest.vmid, guest.type)) {
                    errorMessage = "Target verification failed: guest moved, offline, or invalid target parameters. No operation dispatched.";
                    releaseGuardOnce();
                    return null;
                }
                if (freshTarget.state == null || freshTarget.state == GuestInfo.RunState.UNKNOWN) {
                    errorMessage = "Target verification failed: guest state is unknown or offline. No operation dispatched.";
                    releaseGuardOnce();
                    return null;
                }
                if ("start".equals(action) && freshTarget.state == GuestInfo.RunState.RUNNING) {
                    errorMessage = "Target already running: guest [" + guest.vmid + "] is currently active on " + guest.node + ". No operation dispatched.";
                    releaseGuardOnce();
                    return null;
                }
                if (("shutdown".equals(action) || "stop".equals(action)) && freshTarget.state == GuestInfo.RunState.STOPPED) {
                    errorMessage = "Target already stopped: guest [" + guest.vmid + "] is currently stopped on " + guest.node + ". No operation dispatched.";
                    releaseGuardOnce();
                    return null;
                }

                if (isCancelled() || (lifecycleOwner != null && lifecycleOwner.isCancelled())) {
                    releaseGuardOnce();
                    return null;
                }

                if (server.type == ServerConfig.ServerType.PDM) {
                    PdmApiDriver pdm = new PdmApiDriver(server, app.getSessionManager());
                    if (deadlineNanos - System.nanoTime() <= 0) {
                        errorMessage = "Operation budget exhausted before request dispatch.";
                        releaseGuardOnce();
                        return null;
                    }

                    String upid;
                    switch (action) {
                        case "start":
                            upid = pdm.startGuestWithDeadline(guest.node, guest.vmid, guest.type, creds, deadlineNanos);
                            break;
                        case "shutdown":
                            upid = pdm.shutdownGuestWithDeadline(guest.node, guest.vmid, guest.type, creds, deadlineNanos);
                            break;
                        case "stop":
                            upid = pdm.forceStopGuestWithDeadline(guest.node, guest.vmid, guest.type, creds, deadlineNanos);
                            break;
                        case "reboot":
                            releaseGuardOnce();
                            errorMessage = "Guest Reboot is not supported by the Proxmox Datacenter Manager API. Please perform a Graceful Shutdown followed by Start.";
                            return null;
                        default:
                            releaseGuardOnce();
                            errorMessage = "Unknown action: " + action;
                            return null;
                    }

                    if (isCancelled() || (lifecycleOwner != null && lifecycleOwner.isCancelled())) {
                        releaseGuardOnce();
                        return null;
                    }

                    long remainingBudgetMs = (deadlineNanos - System.nanoTime()) / 1_000_000L;
                    if (remainingBudgetMs <= 0) {
                        errorMessage = "Operation budget exhausted before task polling.";
                        releaseGuardOnce();
                        return new TaskResult(upid, TaskResult.TaskState.TIMEOUT, "Operation budget exhausted before task polling", null, 0, 0, guest.node);
                    }

                    // Shared bounded polling owner preserves action guard until finished
                    return com.homelabpocket.api.TaskPoller.getInstance().pollUntilFinished(
                            lifecycleOwner,
                            guest.node,
                            upid,
                            creds,
                            (nodeName, taskId, auth, curBudget) -> pdm.pollTaskWithDeadline(nodeName, taskId, auth, deadlineNanos),
                            new com.homelabpocket.api.TaskPoller.PollingCallback() {
                                @Override
                                public void onProgress(TaskResult progress) {
                                    publishProgress(progress);
                                }

                                @Override
                                public boolean isCancelled() {
                                    return PowerActionTask.this.isCancelled() || (lifecycleOwner != null && lifecycleOwner.isCancelled());
                                }
                            },
                            remainingBudgetMs,
                            1500L
                    );
                }

                PveApiDriver pve = new PveApiDriver(server);
                if (deadlineNanos - System.nanoTime() <= 0) {
                    errorMessage = "Operation budget exhausted before request dispatch.";
                    releaseGuardOnce();
                    return null;
                }

                String upid;
                switch (action) {
                    case "start":
                        upid = pve.startGuestWithDeadline(guest.node, guest.vmid, guest.type, creds, deadlineNanos);
                        break;
                    case "shutdown":
                        upid = pve.shutdownGuestWithDeadline(guest.node, guest.vmid, guest.type, creds, deadlineNanos);
                        break;
                    case "reboot":
                        upid = pve.rebootGuestWithDeadline(guest.node, guest.vmid, guest.type, creds, deadlineNanos);
                        break;
                    case "stop":
                        upid = pve.stopGuestWithDeadline(guest.node, guest.vmid, guest.type, creds, deadlineNanos);
                        break;
                    default:
                        errorMessage = "Unknown action: " + action;
                        releaseGuardOnce();
                        return null;
                }

                if (isCancelled() || (lifecycleOwner != null && lifecycleOwner.isCancelled())) {
                    releaseGuardOnce();
                    return null;
                }

                long remainingBudgetMs = (deadlineNanos - System.nanoTime()) / 1_000_000L;
                if (remainingBudgetMs <= 0) {
                    errorMessage = "Operation budget exhausted before task polling.";
                    releaseGuardOnce();
                    return new TaskResult(upid, TaskResult.TaskState.TIMEOUT, "Operation budget exhausted before task polling", null, 0, 0, guest.node);
                }

                // Shared bounded polling owner preserves action guard until finished
                return com.homelabpocket.api.TaskPoller.getInstance().pollUntilFinished(
                        lifecycleOwner,
                        guest.node,
                        upid,
                        creds,
                        (nodeName, taskId, auth, budgetMs) -> pve.pollTaskWithDeadline(nodeName, taskId, auth, deadlineNanos),
                        new com.homelabpocket.api.TaskPoller.PollingCallback() {
                            @Override
                            public void onProgress(TaskResult progress) {
                                publishProgress(progress);
                            }

                            @Override
                            public boolean isCancelled() {
                                return PowerActionTask.this.isCancelled() || (lifecycleOwner != null && lifecycleOwner.isCancelled());
                            }
                        },
                        remainingBudgetMs,
                        1500L
                );

            } catch (ApiException e) {
                releaseGuardOnce();
                errorMessage = e.getUserMessage();
                return null;
            } catch (Exception e) {
                releaseGuardOnce();
                errorMessage = e.getMessage();
                return null;
            } finally {
                releaseGuardOnce();
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
            releaseGuardOnce();
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
            releaseGuardOnce();
            try {
                if (progressDialog != null && progressDialog.isShowing()) {
                    progressDialog.dismiss();
                }
                Toast.makeText(activity, "Operation cancelled: " + action + " on " + (guest != null ? guest.name : ""), Toast.LENGTH_SHORT).show();
                activity.refreshData();
            } catch (Throwable ignored) {}
        }

        @Override
        protected void onCancelled() {
            releaseGuardOnce();
            onCancelled(null);
        }
    }
}
