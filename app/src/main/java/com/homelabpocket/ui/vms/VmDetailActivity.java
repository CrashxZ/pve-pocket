// SPDX-License-Identifier: GPL-3.0-or-later
// Homelab Pocket — VM / LXC Detail Activity
package com.homelabpocket.ui.vms;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.homelabpocket.HomelabApp;
import com.homelabpocket.R;
import com.homelabpocket.api.ApiException;
import com.homelabpocket.api.PdmApiDriver;
import com.homelabpocket.api.PveApiDriver;
import com.homelabpocket.model.AuthCredentials;
import com.homelabpocket.model.GuestInfo;
import com.homelabpocket.model.ServerConfig;
import com.homelabpocket.model.TaskResult;
import com.homelabpocket.ui.terminal.TerminalActivity;
import com.homelabpocket.util.JsonUtil;

import java.util.List;

public class VmDetailActivity extends Activity {

    public static final String EXTRA_VMID = "extra_vmid";
    public static final String EXTRA_NODE = "extra_node";
    public static final String EXTRA_SERVER_ID = "extra_server_id";
    public static final String EXTRA_TYPE = "extra_type";
    public static final String EXTRA_NAME = "extra_name";

    private int vmid;
    private String node;
    private String serverId;
    private GuestInfo.GuestType type;

    private Button btnBack;
    private TextView tvTitle;
    private TextView tvStatus;
    private TextView tvIdNode;

    private TextView tvCpu;
    private ProgressBar progCpu;
    private TextView tvRam;
    private ProgressBar progRam;
    private TextView tvDisk;
    private TextView tvUptime;

    private Button btnStart;
    private Button btnShutdown;
    private Button btnReboot;
    private Button btnForceStop;
    private Button btnTerminal;

    private GuestInfo currentGuest;
    private boolean isActionRunning = false;
    private ExecutePowerTask currentPowerTask;

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (currentPowerTask != null) {
            currentPowerTask.cancel(true);
            if (currentPowerTask.getLifecycleOwner() != null) {
                currentPowerTask.getLifecycleOwner().requestCancel();
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vm_detail);

        vmid = getIntent().getIntExtra(EXTRA_VMID, -1);
        node = getIntent().getStringExtra(EXTRA_NODE);
        serverId = getIntent().getStringExtra(EXTRA_SERVER_ID);
        String typeStr = getIntent().getStringExtra(EXTRA_TYPE);
        type = com.homelabpocket.ui.UiPresentationPolicy.parseIntentGuestType(typeStr);

        initViews();

        if (vmid <= 0 || node == null || node.trim().isEmpty() ||
                serverId == null || serverId.trim().isEmpty() || type == null) {
            handleTargetLookupFailure("Malformed or missing intent parameters: valid serverId, node, vmid, and guest type (QEMU/LXC) are strictly required.");
            return;
        }

        ServerConfig serverConfig = HomelabApp.getInstance().getServerConfigStore().findById(serverId);
        if (serverConfig != null && serverConfig.type == ServerConfig.ServerType.PDM) {
            btnTerminal.setVisibility(View.GONE);
        }

        loadData();
    }

    private void initViews() {
        btnBack = findViewById(R.id.btn_back);
        tvTitle = findViewById(R.id.tv_guest_title);
        tvStatus = findViewById(R.id.tv_guest_detail_status);
        tvIdNode = findViewById(R.id.tv_guest_id_node);

        tvCpu = findViewById(R.id.tv_detail_guest_cpu);
        progCpu = findViewById(R.id.progress_guest_cpu);
        tvRam = findViewById(R.id.tv_detail_guest_ram);
        progRam = findViewById(R.id.progress_guest_ram);
        tvDisk = findViewById(R.id.tv_detail_guest_disk);
        tvUptime = findViewById(R.id.tv_detail_guest_uptime);

        btnStart = findViewById(R.id.btn_action_start);
        btnShutdown = findViewById(R.id.btn_action_shutdown);
        btnReboot = findViewById(R.id.btn_action_reboot);
        btnForceStop = findViewById(R.id.btn_action_force_stop);
        btnTerminal = findViewById(R.id.btn_action_terminal);

        // Power controls are strictly unavailable before successful current target lookup
        View cardPower = findViewById(R.id.card_guest_power);
        if (cardPower != null) cardPower.setVisibility(View.GONE);
        btnStart.setVisibility(View.GONE);
        btnShutdown.setVisibility(View.GONE);
        btnReboot.setVisibility(View.GONE);
        btnForceStop.setVisibility(View.GONE);
        btnStart.setEnabled(false);
        btnShutdown.setEnabled(false);
        btnReboot.setEnabled(false);
        btnForceStop.setEnabled(false);

        btnBack.setOnClickListener(v -> finish());
        tvTitle.setText("Guest [" + vmid + "]");
        tvIdNode.setText(com.homelabpocket.ui.UiPresentationPolicy.formatVmDetailSubtitle(type != null ? type.name() : "Guest", node));

        btnStart.setOnClickListener(v -> confirmPowerAction("start"));
        btnShutdown.setOnClickListener(v -> confirmPowerAction("shutdown"));
        btnReboot.setOnClickListener(v -> confirmPowerAction("reboot"));
        btnForceStop.setOnClickListener(v -> confirmForceStop());

        if (type == GuestInfo.GuestType.LXC) {
            btnTerminal.setText("Open LXC Shell >_");
            btnTerminal.setOnClickListener(v -> {
                Intent intent = new Intent(this, TerminalActivity.class);
                intent.putExtra(TerminalActivity.EXTRA_NODE, node);
                intent.putExtra(TerminalActivity.EXTRA_VMID, vmid);
                intent.putExtra(TerminalActivity.EXTRA_SERVER_ID, serverId);
                intent.putExtra(TerminalActivity.EXTRA_TYPE, "LXC");
                intent.putExtra(TerminalActivity.EXTRA_TITLE, "LXC [" + vmid + "] Shell");
                startActivity(intent);
            });
        } else if (type == GuestInfo.GuestType.QEMU) {
            btnTerminal.setText("Console Options (noVNC / SSH)");
            btnTerminal.setOnClickListener(v -> showQemuConsoleDisclosure());
        } else {
            btnTerminal.setVisibility(View.GONE);
        }
    }

    private void loadData() {
        new LoadGuestTask().execute();
    }

    private void handleTargetLookupFailure(String message) {
        this.currentGuest = null;
        View cardPower = findViewById(R.id.card_guest_power);
        if (cardPower != null) cardPower.setVisibility(View.GONE);
        btnStart.setVisibility(View.GONE);
        btnShutdown.setVisibility(View.GONE);
        btnReboot.setVisibility(View.GONE);
        btnForceStop.setVisibility(View.GONE);
        btnStart.setEnabled(false);
        btnShutdown.setEnabled(false);
        btnReboot.setEnabled(false);
        btnForceStop.setEnabled(false);

        tvStatus.setText("OFFLINE / UNKNOWN");
        tvStatus.setBackgroundResource(R.drawable.bg_chip_stopped);
        tvStatus.setTextColor(getResources().getColor(R.color.matrix_text_muted));
        tvUptime.setText("State: Target unreachable, offline, or moved");
        if (message != null && !message.isEmpty()) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }
    }

    private void bindGuest(GuestInfo g) {
        this.currentGuest = g;
        // Do NOT replace type from fetched guest! Explicit intent validation preserves type invariant.
        tvTitle.setText(g.name + " [" + g.vmid + "]");
        tvIdNode.setText(com.homelabpocket.ui.UiPresentationPolicy.formatVmDetailSubtitle(type != null ? type.name() : "Guest", node));

        ServerConfig serverConfig = null;
        try {
            if (HomelabApp.getInstance() != null && HomelabApp.getInstance().getServerConfigStore() != null) {
                serverConfig = HomelabApp.getInstance().getServerConfigStore().findById(serverId);
            }
        } catch (Throwable ignored) {}
        boolean isPdm = com.homelabpocket.ui.UiPresentationPolicy.isPdm(serverConfig);

        boolean isRunning = g.state == GuestInfo.RunState.RUNNING;
        boolean isStopped = g.state == GuestInfo.RunState.STOPPED;
        if (isRunning) {
            tvStatus.setText("RUNNING");
            tvStatus.setBackgroundResource(R.drawable.bg_chip_running);
            tvStatus.setTextColor(getResources().getColor(R.color.matrix_phosphor));

            btnStart.setVisibility(View.GONE);
            btnStart.setEnabled(false);
            btnShutdown.setVisibility(View.VISIBLE);
            btnShutdown.setEnabled(true);
            btnReboot.setVisibility(View.VISIBLE);
            btnReboot.setEnabled(true);
            btnForceStop.setVisibility(View.VISIBLE);
            btnForceStop.setEnabled(true);

            int cpuPct = (int) Math.round(!Double.isNaN(g.cpuUsage) ? g.cpuUsage * 100 : 0);
            tvCpu.setText("CPU: " + cpuPct + "%");
            progCpu.setProgress(cpuPct);

            long ramPct = g.memTotal > 0 ? (g.memUsed * 100 / g.memTotal) : 0;
            tvRam.setText("RAM: " + JsonUtil.formatBytes(g.memUsed) + " / " + JsonUtil.formatBytes(g.memTotal));
            progRam.setProgress((int) ramPct);

            tvDisk.setText(com.homelabpocket.ui.UiPresentationPolicy.formatGuestDisk(g, isPdm));
            tvUptime.setText("Uptime: " + JsonUtil.formatUptime(g.uptime));
        } else if (isStopped) {
            tvStatus.setText("STOPPED");
            tvStatus.setBackgroundResource(R.drawable.bg_chip_stopped);
            tvStatus.setTextColor(getResources().getColor(R.color.matrix_text_muted));

            btnStart.setVisibility(View.VISIBLE);
            btnStart.setEnabled(true);
            btnShutdown.setVisibility(View.GONE);
            btnShutdown.setEnabled(false);
            btnReboot.setVisibility(View.GONE);
            btnReboot.setEnabled(false);
            btnForceStop.setVisibility(View.GONE);
            btnForceStop.setEnabled(false);

            tvCpu.setText("CPU: -");
            progCpu.setProgress(0);
            tvRam.setText("RAM: - / " + JsonUtil.formatBytes(g.memTotal));
            progRam.setProgress(0);
            tvDisk.setText(com.homelabpocket.ui.UiPresentationPolicy.formatGuestDisk(g, isPdm));
            tvUptime.setText("State: Powered down");
        } else {
            tvStatus.setText("UNKNOWN");
            tvStatus.setBackgroundResource(R.drawable.bg_chip_stopped);
            tvStatus.setTextColor(getResources().getColor(R.color.matrix_text_muted));

            btnStart.setVisibility(View.GONE);
            btnStart.setEnabled(false);
            btnShutdown.setVisibility(View.GONE);
            btnShutdown.setEnabled(false);
            btnReboot.setVisibility(View.GONE);
            btnReboot.setEnabled(false);
            btnForceStop.setVisibility(View.GONE);
            btnForceStop.setEnabled(false);

            tvCpu.setText("CPU: -");
            progCpu.setProgress(0);
            tvRam.setText("RAM: - / " + JsonUtil.formatBytes(g.memTotal));
            progRam.setProgress(0);
            tvDisk.setText(com.homelabpocket.ui.UiPresentationPolicy.formatGuestDisk(g, isPdm));
            tvUptime.setText("State: Target unreachable, offline, or unknown");
        }

        View cardPower = findViewById(R.id.card_guest_power);
        View cardTerminal = findViewById(R.id.card_guest_terminal);
        View cardPdmNotice = findViewById(R.id.card_pdm_notice);

        if (cardPower != null) cardPower.setVisibility((isRunning || isStopped) ? View.VISIBLE : View.GONE);
        if (cardPdmNotice != null) cardPdmNotice.setVisibility(View.GONE);

        if (isPdm || (!isRunning && !isStopped)) {
            btnTerminal.setVisibility(View.GONE);
            btnTerminal.setEnabled(false);
            if (cardTerminal != null) cardTerminal.setVisibility(View.GONE);
        } else {
            btnTerminal.setEnabled(true);
            if (cardTerminal != null) cardTerminal.setVisibility(View.VISIBLE);
        }
    }

    private void confirmPowerAction(String action) {
        if (currentGuest == null || type == null || !com.homelabpocket.ui.UiPresentationPolicy.isGuestTargetValid(currentGuest, node, vmid, type)) {
            Toast.makeText(this, "Action blocked: Target not verified, moved, or offline. Please refresh.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (HomelabApp.getInstance().getSessionManager().isLocked()) {
            Toast.makeText(this, "Action blocked: Session is locked. Please unlock in Settings first.", Toast.LENGTH_LONG).show();
            return;
        }

        ServerConfig cfg = HomelabApp.getInstance().getServerConfigStore().findById(serverId);
        boolean isPdm = (cfg != null && cfg.type == ServerConfig.ServerType.PDM);
        String remote = isPdm ? PdmApiDriver.extractRemote(node) : "";
        String baseNode = isPdm ? PdmApiDriver.extractNode(node) : node;
        String serverName = cfg != null ? cfg.name : serverId;
        String guestName = (tvTitle != null && tvTitle.getText() != null) ? tvTitle.getText().toString() : ("Guest [" + vmid + "]");
        String guestTypeName = (type == GuestInfo.GuestType.LXC) ? "LXC Container" : "QEMU Virtual Machine";

        // Reboot on PDM: Upstream API limitation honest disclosure
        if (isPdm && "reboot".equalsIgnoreCase(action)) {
            new AlertDialog.Builder(this)
                    .setTitle("Reboot Not Supported by PDM")
                    .setMessage("Guest Reboot is not supported by the Proxmox Datacenter Manager API.\n\n"
                            + "Upstream PDM supports Start, Graceful Shutdown, and Stop operations only.\n\n"
                            + "To restart this guest, please perform a Graceful Shutdown followed by Start.\n\n"
                            + "Target Identity:\n"
                            + "• Server: " + serverName + "\n"
                            + "• Remote: " + (remote.isEmpty() ? "N/A" : remote) + "\n"
                            + "• Node: " + baseNode + "\n"
                            + "• Type: " + guestTypeName + "\n"
                            + "• VMID: " + vmid + "\n"
                            + "• Name: " + guestName)
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
        msg.append("• Node: ").append(baseNode).append("\n");
        msg.append("• Type: ").append(guestTypeName).append("\n");
        msg.append("• VMID: ").append(vmid).append("\n");
        msg.append("• Name: ").append(guestName);

        new AlertDialog.Builder(this)
                .setTitle(actionTitle)
                .setMessage(msg.toString())
                .setPositiveButton(actionVerb, (dialog, which) -> executeAction(action))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmForceStop() {
        if (currentGuest == null || type == null || !com.homelabpocket.ui.UiPresentationPolicy.isGuestTargetValid(currentGuest, node, vmid, type)) {
            Toast.makeText(this, "Action blocked: Target not verified, moved, or offline. Please refresh.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (HomelabApp.getInstance().getSessionManager().isLocked()) {
            Toast.makeText(this, "Action blocked: Session is locked. Please unlock in Settings first.", Toast.LENGTH_LONG).show();
            return;
        }

        ServerConfig srv = HomelabApp.getInstance().getServerConfigStore().findById(serverId);
        boolean isPdm = (srv != null && srv.type == ServerConfig.ServerType.PDM);
        String remote = isPdm ? PdmApiDriver.extractRemote(node) : "";
        String baseNode = isPdm ? PdmApiDriver.extractNode(node) : node;
        String serverName = srv != null ? srv.name : serverId;
        String guestName = (tvTitle != null && tvTitle.getText() != null) ? tvTitle.getText().toString() : ("VM " + vmid);
        String guestTypeName = (type != null ? type.name() : (currentGuest != null && currentGuest.type != null ? currentGuest.type.name() : "Guest"));

        // Step 1: Warning dialog
        StringBuilder warnMsg = new StringBuilder();
        warnMsg.append("WARNING: Force stop immediately cuts power to the guest (equivalent to pulling the physical power cord). Unsaved data in the guest may be corrupted.\n\n");
        warnMsg.append("This may cause DATA LOSS or filesystem corruption.\n\n");
        warnMsg.append("Target Identity:\n");
        warnMsg.append("• Server: ").append(serverName).append("\n");
        if (isPdm && !remote.isEmpty()) {
            warnMsg.append("• Remote: ").append(remote).append("\n");
        }
        warnMsg.append("• Node: ").append(baseNode).append("\n");
        warnMsg.append("• Type: ").append(guestTypeName).append("\n");
        warnMsg.append("• VMID: ").append(vmid).append(" (").append(guestName).append(")\n\n");
        warnMsg.append("Do you want to proceed to the second confirmation?");

        new AlertDialog.Builder(this)
                .setTitle("Warning: Force Stop")
                .setMessage(warnMsg.toString())
                .setPositiveButton("Proceed...", (dialog, which) -> {
                    // Step 2: Separate second confirmation
                    showSecondForceStopConfirmation(isPdm, remote, baseNode, serverName, guestTypeName, guestName);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showSecondForceStopConfirmation(boolean isPdm, String remote, String baseNode, String serverName, String guestTypeName, String guestName) {
        new AlertDialog.Builder(this)
                .setTitle("Second Confirmation: Force Stop")
                .setMessage("Are you ABSOLUTELY SURE you want to force stop " + guestTypeName + " " + vmid + " (" + guestName + ")?\n\n"
                        + "This immediate power-kill action cannot be undone.\n\n"
                        + (isPdm && !remote.isEmpty() ? ("• Remote: " + remote + "\n") : "")
                        + "• Node: " + baseNode + "\n"
                        + "• Server: " + serverName)
                .setPositiveButton("Force Stop (Kill)", (dialog, which) -> executeAction("stop"))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showQemuConsoleDisclosure() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_qemu_console, null);
        TextView tvMsg = dialogView.findViewById(R.id.tv_qemu_disclosure_msg);
        Button btnSsh = dialogView.findViewById(R.id.btn_open_external_ssh);
        Button btnBrowser = dialogView.findViewById(R.id.btn_open_browser_vnc);
        Button btnClose = dialogView.findViewById(R.id.btn_close_disclosure);

        ServerConfig server = HomelabApp.getInstance().getServerConfigStore().findById(serverId);
        String host = server != null ? server.host : "pve-host";
        int port = server != null ? server.port : 8006;

        tvMsg.setText(getString(R.string.qemu_terminal_unavailable_msg, host, port));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(true)
                .create();

        btnSsh.setOnClickListener(v -> {
            dialog.dismiss();
            try {
                Intent sshIntent = new Intent(Intent.ACTION_VIEW);
                sshIntent.setData(Uri.parse("ssh://" + host));
                startActivity(Intent.createChooser(sshIntent, "Choose SSH Client"));
            } catch (Exception e) {
                Toast.makeText(this, "No SSH client installed.", Toast.LENGTH_SHORT).show();
            }
        });

        btnBrowser.setOnClickListener(v -> {
            dialog.dismiss();
            try {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW);
                browserIntent.setData(Uri.parse("https://" + host + ":" + port + "/#v1:0:=qemu%2F" + vmid));
                startActivity(browserIntent);
            } catch (Exception e) {
                Toast.makeText(this, "Cannot launch browser: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        btnClose.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private void executeAction(String action) {
        // Fail-closed: Require successful exact current target lookup before controls/POST
        if (!com.homelabpocket.ui.UiPresentationPolicy.isGuestTargetValid(currentGuest, node, vmid, type)) {
            Toast.makeText(this, "Action blocked: Target not verified, moved, or offline. Please refresh.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Enforce session lock before action
        if (HomelabApp.getInstance().getSessionManager().isLocked()) {
            Toast.makeText(this, "Action blocked: Session is locked. Please unlock in Settings first.", Toast.LENGTH_LONG).show();
            return;
        }

        ServerConfig cfg = HomelabApp.getInstance().getServerConfigStore().findById(serverId);
        String actionKey = com.homelabpocket.api.ActionGuard.buildGuestResourceKey(serverId, node, type.name(), vmid);
        com.homelabpocket.api.ActionGuard.Lease lease = com.homelabpocket.api.ActionGuard.getInstance().acquireLease(actionKey);
        if (lease == null) {
            Toast.makeText(this, R.string.double_submit_blocked, Toast.LENGTH_SHORT).show();
            return;
        }

        com.homelabpocket.api.ActionGuard.TaskLifecycleOwner lifecycleOwner = new com.homelabpocket.api.ActionGuard.TaskLifecycleOwner(lease);

        if (cfg != null && cfg.isLegacyDemo()) {
            Toast.makeText(this, "Demo server entries cannot execute actions.", Toast.LENGTH_SHORT).show();
            lifecycleOwner.release();
            return;
        }

        currentPowerTask = new ExecutePowerTask(action, lifecycleOwner);
        currentPowerTask.execute();
    }

    private class LoadGuestTask extends AsyncTask<Void, Void, GuestInfo> {
        @Override
        protected GuestInfo doInBackground(Void... voids) {
            try {
                if (type == null) {
                    return null; // Missing or invalid intent guest type must never recover via lookup
                }
                ServerConfig cfg = HomelabApp.getInstance().getServerConfigStore().findById(serverId);
                if (cfg == null || cfg.isLegacyDemo()) return null;
                AuthCredentials creds = HomelabApp.getInstance().getSessionManager().getCredentials(cfg);
                if (creds == null) return null;

                if (cfg.type == ServerConfig.ServerType.PDM) {
                    PdmApiDriver pdm = new PdmApiDriver(cfg);
                    return pdm.fetchGuestDetail(node, vmid, type, creds);
                } else {
                    PveApiDriver pve = new PveApiDriver(cfg);
                    List<GuestInfo> guests = pve.fetchAllGuests(node, creds);
                    for (GuestInfo g : guests) {
                        if (g.vmid == vmid && g.type == type && node.equals(g.node)) return g;
                    }
                    return null;
                }
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        protected void onPostExecute(GuestInfo g) {
            if (g != null) {
                if (type == null || g.type != type || node == null || !node.equals(g.node) || g.vmid != vmid) {
                    handleTargetLookupFailure("Guest type or identity mismatch on target: expected " +
                            (type != null ? type.name() : "null") + " [" + vmid + "] on " + node +
                            ", found " + g.type + " [" + g.vmid + "] on " + g.node);
                    return;
                }
                bindGuest(g);
            } else {
                handleTargetLookupFailure("Target unavailable: Guest [" + vmid + "] not found on host " + node);
            }
        }
    }

    private class ExecutePowerTask extends AsyncTask<Void, TaskResult, TaskResult> {
        private final String action;
        private final com.homelabpocket.api.ActionGuard.TaskLifecycleOwner lifecycleOwner;
        private ProgressDialog dialog;
        private String error;

        ExecutePowerTask(String action, com.homelabpocket.api.ActionGuard.TaskLifecycleOwner lifecycleOwner) {
            this.action = action;
            this.lifecycleOwner = lifecycleOwner;
        }

        com.homelabpocket.api.ActionGuard.TaskLifecycleOwner getLifecycleOwner() {
            return lifecycleOwner;
        }

        void releaseGuardOnce() {
            if (lifecycleOwner != null) {
                lifecycleOwner.release();
            }
        }

        @Override
        protected void onPreExecute() {
            isActionRunning = true;
            ServerConfig cfg = HomelabApp.getInstance().getServerConfigStore().findById(serverId);
            String serverName = cfg != null ? cfg.name : serverId;
            String guestName = (tvTitle != null && tvTitle.getText() != null) ? tvTitle.getText().toString() : ("VM " + vmid);
            String targetTypeStr = (type != null ? type.name() : (currentGuest != null && currentGuest.type != null ? currentGuest.type.name() : "Guest"));

            dialog = new ProgressDialog(VmDetailActivity.this);
            dialog.setTitle("Proxmox Operation: " + action.toUpperCase());
            dialog.setMessage("Initiating " + action.toUpperCase() + "…\n\n"
                    + "Target: " + targetTypeStr + " " + vmid + " (" + guestName + ")\n"
                    + "Host: " + node + " | Server: " + serverName + "\n"
                    + "Status: Dispatching request…");
            dialog.setCancelable(true);
            dialog.setOnCancelListener(d -> {
                cancel(true);
                if (lifecycleOwner != null) {
                    lifecycleOwner.requestCancel();
                }
            });
            dialog.show();
        }

        @Override
        protected TaskResult doInBackground(Void... voids) {
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

                ServerConfig cfg = HomelabApp.getInstance().getServerConfigStore().findById(serverId);
                if (cfg == null) {
                    error = "Server configuration not found.";
                    releaseGuardOnce();
                    return null;
                }

                if (deadlineNanos - System.nanoTime() <= 0) {
                    error = "Operation budget exhausted before credential acquisition.";
                    releaseGuardOnce();
                    return null;
                }

                AuthCredentials creds = HomelabApp.getInstance().getSessionManager().getCredentialsWithDeadline(cfg, deadlineNanos);
                if (creds == null) {
                    error = HomelabApp.getInstance().getSessionManager().isLocked() ?
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
                    error = "Operation budget exhausted before target resolution.";
                    releaseGuardOnce();
                    return null;
                }
                GuestInfo freshTarget = null;
                if (cfg.type == ServerConfig.ServerType.PDM) {
                    PdmApiDriver pdmResolve = new PdmApiDriver(cfg, HomelabApp.getInstance().getSessionManager());
                    freshTarget = pdmResolve.fetchGuestDetailWithDeadline(node, vmid, type, creds, deadlineNanos);
                } else {
                    PveApiDriver pveResolve = new PveApiDriver(cfg);
                    List<GuestInfo> guests = pveResolve.fetchAllGuestsWithDeadline(node, creds, deadlineNanos);
                    for (GuestInfo g : guests) {
                        if (g.vmid == vmid && g.type == type && node.equals(g.node)) {
                            freshTarget = g;
                            break;
                        }
                    }
                }

                // Strict validation on freshly resolved target: reject removed, moved, offline, or unknown target without POST
                if (freshTarget == null) {
                    error = "Target verification failed: guest [" + vmid + "] removed or moved from host " + node + ". No operation dispatched.";
                    releaseGuardOnce();
                    return null;
                }
                if (!com.homelabpocket.ui.UiPresentationPolicy.isGuestTargetValid(freshTarget, node, vmid, type)) {
                    error = "Target verification failed: guest moved, offline, or invalid target parameters. No operation dispatched.";
                    releaseGuardOnce();
                    return null;
                }
                if (freshTarget.state == null || freshTarget.state == GuestInfo.RunState.UNKNOWN) {
                    error = "Target verification failed: guest state is unknown or offline. No operation dispatched.";
                    releaseGuardOnce();
                    return null;
                }
                if ("start".equals(action) && freshTarget.state == GuestInfo.RunState.RUNNING) {
                    error = "Target already running: guest [" + vmid + "] is currently active on " + node + ". No operation dispatched.";
                    releaseGuardOnce();
                    return null;
                }
                if (("shutdown".equals(action) || "stop".equals(action)) && freshTarget.state == GuestInfo.RunState.STOPPED) {
                    error = "Target already stopped: guest [" + vmid + "] is currently stopped on " + node + ". No operation dispatched.";
                    releaseGuardOnce();
                    return null;
                }

                if (isCancelled() || (lifecycleOwner != null && lifecycleOwner.isCancelled())) {
                    releaseGuardOnce();
                    return null;
                }

                if (cfg.type == ServerConfig.ServerType.PDM) {
                    PdmApiDriver pdm = new PdmApiDriver(cfg, HomelabApp.getInstance().getSessionManager());
                    if (deadlineNanos - System.nanoTime() <= 0) {
                        error = "Operation budget exhausted before request dispatch.";
                        releaseGuardOnce();
                        return null;
                    }

                    String upid;
                    switch (action) {
                        case "start":
                            upid = pdm.startGuestWithDeadline(node, vmid, type, creds, deadlineNanos);
                            break;
                        case "shutdown":
                            upid = pdm.shutdownGuestWithDeadline(node, vmid, type, creds, deadlineNanos);
                            break;
                        case "stop":
                            upid = pdm.forceStopGuestWithDeadline(node, vmid, type, creds, deadlineNanos);
                            break;
                        case "reboot":
                            releaseGuardOnce();
                            error = "Guest Reboot is not supported by the Proxmox Datacenter Manager API. Please perform a Graceful Shutdown followed by Start.";
                            return null;
                        default:
                            releaseGuardOnce();
                            error = "Unknown action: " + action;
                            return null;
                    }

                    if (isCancelled() || (lifecycleOwner != null && lifecycleOwner.isCancelled())) {
                        releaseGuardOnce();
                        return null;
                    }

                    long remainingBudgetMs = (deadlineNanos - System.nanoTime()) / 1_000_000L;
                    if (remainingBudgetMs <= 0) {
                        error = "Operation budget exhausted before task status polling.";
                        releaseGuardOnce();
                        return new TaskResult(upid, TaskResult.TaskState.TIMEOUT, "Operation budget exhausted before task polling", null, 0, 0, node);
                    }

                    // Shared bounded polling owner preserves action guard until finished
                    return com.homelabpocket.api.TaskPoller.getInstance().pollUntilFinished(
                            lifecycleOwner,
                            node,
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
                                    return ExecutePowerTask.this.isCancelled() || (lifecycleOwner != null && lifecycleOwner.isCancelled());
                                }
                            },
                            remainingBudgetMs,
                            1500L
                    );
                }

                PveApiDriver pve = new PveApiDriver(cfg);
                if (deadlineNanos - System.nanoTime() <= 0) {
                    error = "Operation budget exhausted before request dispatch.";
                    releaseGuardOnce();
                    return null;
                }

                String upid;
                switch (action) {
                    case "start":
                        upid = pve.startGuestWithDeadline(node, vmid, type, creds, deadlineNanos);
                        break;
                    case "shutdown":
                        upid = pve.shutdownGuestWithDeadline(node, vmid, type, creds, deadlineNanos);
                        break;
                    case "reboot":
                        upid = pve.rebootGuestWithDeadline(node, vmid, type, creds, deadlineNanos);
                        break;
                    case "stop":
                        upid = pve.stopGuestWithDeadline(node, vmid, type, creds, deadlineNanos);
                        break;
                    default:
                        error = "Unknown action: " + action;
                        releaseGuardOnce();
                        return null;
                }

                if (isCancelled() || (lifecycleOwner != null && lifecycleOwner.isCancelled())) {
                    releaseGuardOnce();
                    return null;
                }

                long remainingBudgetMs = (deadlineNanos - System.nanoTime()) / 1_000_000L;
                if (remainingBudgetMs <= 0) {
                    error = "Operation budget exhausted before task status polling.";
                    releaseGuardOnce();
                    return new TaskResult(upid, TaskResult.TaskState.TIMEOUT, "Operation budget exhausted before task polling", null, 0, 0, node);
                }

                // Shared bounded polling owner preserves action guard until finished
                return com.homelabpocket.api.TaskPoller.getInstance().pollUntilFinished(
                        lifecycleOwner,
                        node,
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
                                return ExecutePowerTask.this.isCancelled() || (lifecycleOwner != null && lifecycleOwner.isCancelled());
                            }
                        },
                        remainingBudgetMs,
                        1500L
                );

            } catch (ApiException e) {
                releaseGuardOnce();
                error = e.getUserMessage();
                return null;
            } catch (Exception e) {
                releaseGuardOnce();
                error = e.getMessage();
                return null;
            } finally {
                releaseGuardOnce();
            }
        }

        @Override
        protected void onProgressUpdate(TaskResult... values) {
            if (dialog != null && dialog.isShowing() && values != null && values.length > 0) {
                TaskResult r = values[0];
                ServerConfig cfg = HomelabApp.getInstance().getServerConfigStore().findById(serverId);
                String serverName = cfg != null ? cfg.name : serverId;
                String guestName = (tvTitle != null && tvTitle.getText() != null) ? tvTitle.getText().toString() : ("VM " + vmid);
                String targetTypeStr = (type != null ? type.name() : (currentGuest != null && currentGuest.type != null ? currentGuest.type.name() : "Guest"));
                dialog.setMessage("Executing " + action.toUpperCase() + "…\n\n"
                        + "Target: " + targetTypeStr + " " + vmid + " (" + guestName + ")\n"
                        + "Host: " + node + " | Server: " + serverName + "\n"
                        + "Status: RUNNING (" + (r.upid != null ? r.upid.substring(0, Math.min(30, r.upid.length())) + "…" : "task running") + ")");
            }
        }

        @Override
        protected void onPostExecute(TaskResult taskResult) {
            releaseGuardOnce();
            isActionRunning = false;
            if (dialog != null && dialog.isShowing()) {
                dialog.dismiss();
            }

            ServerConfig cfg = HomelabApp.getInstance().getServerConfigStore().findById(serverId);
            String serverName = cfg != null ? cfg.name : serverId;
            String guestName = (tvTitle != null && tvTitle.getText() != null) ? tvTitle.getText().toString() : ("VM " + vmid);
            String targetTypeStr = (type != null ? type.name() : (currentGuest != null && currentGuest.type != null ? currentGuest.type.name() : "Guest"));
            String targetInfo = targetTypeStr + " " + vmid + " (" + guestName + ") on " + node + " [" + serverName + "]";

            if (taskResult != null) {
                switch (taskResult.state) {
                    case OK:
                        Toast.makeText(VmDetailActivity.this, "Success: " + action + " completed for " + targetInfo, Toast.LENGTH_SHORT).show();
                        break;
                    case ERROR:
                        new AlertDialog.Builder(VmDetailActivity.this)
                                .setTitle("Task Failed")
                                .setMessage("Operation '" + action + "' failed on Proxmox.\n\n"
                                        + "Target: " + targetInfo + "\n\n"
                                        + "Details: " + (taskResult.exitStatus != null ? taskResult.exitStatus : "Unknown error"))
                                .setPositiveButton("OK", null)
                                .show();
                        break;
                    case TIMEOUT:
                        new AlertDialog.Builder(VmDetailActivity.this)
                                .setTitle("Task Timed Out")
                                .setMessage("Operation '" + action + "' did not complete within the 60s timeout.\n\n"
                                        + "Target: " + targetInfo + "\n\n"
                                        + "Details: " + taskResult.getSummary())
                                .setPositiveButton("OK", null)
                                .show();
                        break;
                    case UNKNOWN:
                    default:
                        new AlertDialog.Builder(VmDetailActivity.this)
                                .setTitle("Task Status Unknown")
                                .setMessage("Operation '" + action + "' returned indeterminate status.\n\n"
                                        + "Target: " + targetInfo + "\n\n"
                                        + "Details: " + taskResult.getSummary())
                                .setPositiveButton("OK", null)
                                .show();
                        break;
                }
            } else if (error != null) {
                new AlertDialog.Builder(VmDetailActivity.this)
                        .setTitle("Operation Error")
                        .setMessage("Failed to execute '" + action + "' on " + targetInfo + ":\n\n" + error)
                        .setPositiveButton("OK", null)
                        .show();
            }

            loadData();
        }

        @Override
        protected void onCancelled(TaskResult taskResult) {
            releaseGuardOnce();
            isActionRunning = false;
            try {
                if (dialog != null && dialog.isShowing()) {
                    dialog.dismiss();
                }
                Toast.makeText(VmDetailActivity.this, "Operation cancelled: " + action, Toast.LENGTH_SHORT).show();
                loadData();
            } catch (Throwable ignored) {}
        }

        @Override
        protected void onCancelled() {
            releaseGuardOnce();
            onCancelled(null);
        }
    }
}
