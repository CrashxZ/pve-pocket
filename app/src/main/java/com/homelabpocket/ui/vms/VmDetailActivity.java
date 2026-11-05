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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vm_detail);

        vmid = getIntent().getIntExtra(EXTRA_VMID, -1);
        node = getIntent().getStringExtra(EXTRA_NODE);
        serverId = getIntent().getStringExtra(EXTRA_SERVER_ID);
        String typeStr = getIntent().getStringExtra(EXTRA_TYPE);
        type = "LXC".equals(typeStr) ? GuestInfo.GuestType.LXC : GuestInfo.GuestType.QEMU;

        initViews();

        ServerConfig serverConfig = HomelabApp.getInstance().getServerConfigStore().findById(serverId);
        if (serverConfig != null && serverConfig.type == ServerConfig.ServerType.PDM) {
            btnStart.setVisibility(View.GONE);
            btnShutdown.setVisibility(View.GONE);
            btnReboot.setVisibility(View.GONE);
            btnForceStop.setVisibility(View.GONE);
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

        btnBack.setOnClickListener(v -> finish());
        tvTitle.setText("Guest [" + vmid + "]");
        tvIdNode.setText(com.homelabpocket.ui.UiPresentationPolicy.formatVmDetailSubtitle(type != null ? type.name() : "Guest", node));

        btnStart.setOnClickListener(v -> executeAction("start"));
        btnShutdown.setOnClickListener(v -> executeAction("shutdown"));
        btnReboot.setOnClickListener(v -> executeAction("reboot"));
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
        } else {
            btnTerminal.setText("Console Options (noVNC / SSH)");
            btnTerminal.setOnClickListener(v -> showQemuConsoleDisclosure());
        }
    }

    private void loadData() {
        new LoadGuestTask().execute();
    }

    private void bindGuest(GuestInfo g) {
        this.currentGuest = g;
        tvTitle.setText(g.name + " [" + g.vmid + "]");

        ServerConfig serverConfig = null;
        try {
            if (HomelabApp.getInstance() != null && HomelabApp.getInstance().getServerConfigStore() != null) {
                serverConfig = HomelabApp.getInstance().getServerConfigStore().findById(serverId);
            }
        } catch (Throwable ignored) {}
        boolean isPdm = com.homelabpocket.ui.UiPresentationPolicy.isPdm(serverConfig);

        boolean isRunning = g.state == GuestInfo.RunState.RUNNING;
        if (isRunning) {
            tvStatus.setText("RUNNING");
            tvStatus.setBackgroundResource(R.drawable.bg_chip_running);
            tvStatus.setTextColor(getResources().getColor(R.color.matrix_phosphor));

            btnStart.setVisibility(View.GONE);
            btnShutdown.setVisibility(View.VISIBLE);
            btnReboot.setVisibility(View.VISIBLE);
            btnForceStop.setVisibility(View.VISIBLE);

            int cpuPct = (int) Math.round(!Double.isNaN(g.cpuUsage) ? g.cpuUsage * 100 : 0);
            tvCpu.setText("CPU: " + cpuPct + "%");
            progCpu.setProgress(cpuPct);

            long ramPct = g.memTotal > 0 ? (g.memUsed * 100 / g.memTotal) : 0;
            tvRam.setText("RAM: " + JsonUtil.formatBytes(g.memUsed) + " / " + JsonUtil.formatBytes(g.memTotal));
            progRam.setProgress((int) ramPct);

            tvDisk.setText(com.homelabpocket.ui.UiPresentationPolicy.formatGuestDisk(g, isPdm));
            tvUptime.setText("Uptime: " + JsonUtil.formatUptime(g.uptime));
        } else {
            tvStatus.setText("STOPPED");
            tvStatus.setBackgroundResource(R.drawable.bg_chip_stopped);
            tvStatus.setTextColor(getResources().getColor(R.color.matrix_text_muted));

            btnStart.setVisibility(View.VISIBLE);
            btnShutdown.setVisibility(View.GONE);
            btnReboot.setVisibility(View.GONE);
            btnForceStop.setVisibility(View.GONE);

            tvCpu.setText("CPU: -");
            progCpu.setProgress(0);
            tvRam.setText("RAM: - / " + JsonUtil.formatBytes(g.memTotal));
            progRam.setProgress(0);
            tvDisk.setText(com.homelabpocket.ui.UiPresentationPolicy.formatGuestDisk(g, isPdm));
            tvUptime.setText("State: Powered down");
        }

        View cardPower = findViewById(R.id.card_guest_power);
        View cardTerminal = findViewById(R.id.card_guest_terminal);
        View cardPdmNotice = findViewById(R.id.card_pdm_notice);

        if (isPdm) {
            btnStart.setVisibility(View.GONE);
            btnShutdown.setVisibility(View.GONE);
            btnReboot.setVisibility(View.GONE);
            btnForceStop.setVisibility(View.GONE);
            btnTerminal.setVisibility(View.GONE);
            if (cardPower != null) cardPower.setVisibility(View.GONE);
            if (cardTerminal != null) cardTerminal.setVisibility(View.GONE);
            if (cardPdmNotice != null) cardPdmNotice.setVisibility(View.VISIBLE);
        } else {
            if (cardPower != null) cardPower.setVisibility(View.VISIBLE);
            if (cardTerminal != null) cardTerminal.setVisibility(View.VISIBLE);
            if (cardPdmNotice != null) cardPdmNotice.setVisibility(View.GONE);
        }
    }

    private void confirmForceStop() {
        ServerConfig srv = HomelabApp.getInstance().getServerConfigStore().findById(serverId);
        String serverName = srv != null ? srv.name : serverId;
        String guestName = (tvTitle != null && tvTitle.getText() != null) ? tvTitle.getText().toString() : ("VM " + vmid);

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_confirm_stop, null);
        TextView tvMsg = dialogView.findViewById(R.id.tv_dialog_message);
        Button btnCancel = dialogView.findViewById(R.id.btn_dialog_cancel);
        Button btnConfirm = dialogView.findViewById(R.id.btn_dialog_confirm);

        String detailMsg = "WARNING: Force stop immediately cuts power to the guest (equivalent to pulling the physical power cord). Unsaved data in the guest may be corrupted.\n\n"
                + "Target Identity:\n"
                + "• Server: " + serverName + "\n"
                + "• Host Node: " + node + "\n"
                + "• Guest Type: " + (type != null ? type.name() : "QEMU") + "\n"
                + "• VMID: " + vmid + " (" + guestName + ")\n\n"
                + "Are you sure you want to proceed?";

        tvMsg.setText(detailMsg);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(true)
                .create();

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnConfirm.setOnClickListener(v -> {
            dialog.dismiss();
            executeAction("stop");
        });

        dialog.show();
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
        // Enforce session lock before action
        if (HomelabApp.getInstance().getSessionManager().isLocked()) {
            Toast.makeText(this, "Action blocked: Session is locked. Please unlock in Settings first.", Toast.LENGTH_LONG).show();
            return;
        }

        // PDM misrouting prevention
        ServerConfig cfg = HomelabApp.getInstance().getServerConfigStore().findById(serverId);
        if (cfg != null && cfg.type == ServerConfig.ServerType.PDM) {
            Toast.makeText(this, "Guest power actions are disabled on PDM connections to prevent misrouting. Connect directly to host PVE.", Toast.LENGTH_LONG).show();
            return;
        }

        String actionKey = com.homelabpocket.api.ActionGuard.buildGuestKey(serverId, node, type != null ? type.name() : "qemu", vmid, action);
        if (!com.homelabpocket.api.ActionGuard.getInstance().tryAcquire(actionKey)) {
            Toast.makeText(this, R.string.double_submit_blocked, Toast.LENGTH_SHORT).show();
            return;
        }

        if (cfg != null && cfg.isLegacyDemo()) {
            Toast.makeText(this, "Demo server entries cannot execute actions.", Toast.LENGTH_SHORT).show();
            com.homelabpocket.api.ActionGuard.getInstance().release(actionKey);
            return;
        }

        new ExecutePowerTask(action, actionKey).execute();
    }

    private class LoadGuestTask extends AsyncTask<Void, Void, GuestInfo> {
        @Override
        protected GuestInfo doInBackground(Void... voids) {
            try {
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
                        if (g.vmid == vmid && (type == null || g.type == type)) return g;
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
                bindGuest(g);
            }
        }
    }

    private class ExecutePowerTask extends AsyncTask<Void, TaskResult, TaskResult> {
        private final String action;
        private final String actionKey;
        private ProgressDialog dialog;
        private String error;

        ExecutePowerTask(String action, String actionKey) {
            this.action = action;
            this.actionKey = actionKey;
        }

        @Override
        protected void onPreExecute() {
            isActionRunning = true;
            ServerConfig cfg = HomelabApp.getInstance().getServerConfigStore().findById(serverId);
            String serverName = cfg != null ? cfg.name : serverId;
            String guestName = (tvTitle != null && tvTitle.getText() != null) ? tvTitle.getText().toString() : ("VM " + vmid);

            dialog = new ProgressDialog(VmDetailActivity.this);
            dialog.setTitle("Proxmox Operation: " + action.toUpperCase());
            dialog.setMessage("Initiating " + action.toUpperCase() + "…\n\n"
                    + "Target: " + (type != null ? type.name() : "QEMU") + " " + vmid + " (" + guestName + ")\n"
                    + "Host: " + node + " | Server: " + serverName + "\n"
                    + "Status: Dispatching request…");
            dialog.setCancelable(true);
            dialog.setOnCancelListener(d -> cancel(true));
            dialog.show();
        }

        @Override
        protected TaskResult doInBackground(Void... voids) {
            try {
                ServerConfig cfg = HomelabApp.getInstance().getServerConfigStore().findById(serverId);
                if (cfg == null) {
                    error = "Server configuration not found.";
                    com.homelabpocket.api.ActionGuard.getInstance().release(actionKey);
                    return null;
                }
                if (cfg.type == ServerConfig.ServerType.PDM) {
                    error = "Guest power actions via PDM are disabled in this release to prevent endpoint misrouting. Connect directly to the host's standalone PVE endpoint.";
                    com.homelabpocket.api.ActionGuard.getInstance().release(actionKey);
                    return null;
                }
                AuthCredentials creds = HomelabApp.getInstance().getSessionManager().getCredentials(cfg);
                if (creds == null) {
                    error = HomelabApp.getInstance().getSessionManager().isLocked() ?
                            "Session is locked. Please unlock in Settings." :
                            "Authentication failed: no valid credentials.";
                    com.homelabpocket.api.ActionGuard.getInstance().release(actionKey);
                    return null;
                }

                PveApiDriver pve = new PveApiDriver(cfg);
                String upid;
                switch (action) {
                    case "start":
                        upid = pve.startGuest(node, vmid, type, creds);
                        break;
                    case "shutdown":
                        upid = pve.shutdownGuest(node, vmid, type, creds);
                        break;
                    case "reboot":
                        upid = pve.rebootGuest(node, vmid, type, creds);
                        break;
                    case "stop":
                        upid = pve.forceStopGuest(node, vmid, type, creds);
                        break;
                    default:
                        error = "Unknown action: " + action;
                        com.homelabpocket.api.ActionGuard.getInstance().release(actionKey);
                        return null;
                }

                // Shared bounded polling owner preserves action guard until finished
                return com.homelabpocket.api.TaskPoller.getInstance().pollUntilFinished(
                        actionKey,
                        node,
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
                                return ExecutePowerTask.this.isCancelled();
                            }
                        },
                        60_000L,
                        1500L
                );

            } catch (ApiException e) {
                com.homelabpocket.api.ActionGuard.getInstance().release(actionKey);
                error = e.getUserMessage();
                return null;
            } catch (Exception e) {
                com.homelabpocket.api.ActionGuard.getInstance().release(actionKey);
                error = e.getMessage();
                return null;
            }
        }

        @Override
        protected void onProgressUpdate(TaskResult... values) {
            if (dialog != null && dialog.isShowing() && values != null && values.length > 0) {
                TaskResult r = values[0];
                ServerConfig cfg = HomelabApp.getInstance().getServerConfigStore().findById(serverId);
                String serverName = cfg != null ? cfg.name : serverId;
                String guestName = (tvTitle != null && tvTitle.getText() != null) ? tvTitle.getText().toString() : ("VM " + vmid);
                dialog.setMessage("Executing " + action.toUpperCase() + "…\n\n"
                        + "Target: " + (type != null ? type.name() : "QEMU") + " " + vmid + " (" + guestName + ")\n"
                        + "Host: " + node + " | Server: " + serverName + "\n"
                        + "Status: RUNNING (" + (r.upid != null ? r.upid.substring(0, Math.min(30, r.upid.length())) + "…" : "task running") + ")");
            }
        }

        @Override
        protected void onPostExecute(TaskResult taskResult) {
            isActionRunning = false;
            if (dialog != null && dialog.isShowing()) {
                dialog.dismiss();
            }

            ServerConfig cfg = HomelabApp.getInstance().getServerConfigStore().findById(serverId);
            String serverName = cfg != null ? cfg.name : serverId;
            String guestName = (tvTitle != null && tvTitle.getText() != null) ? tvTitle.getText().toString() : ("VM " + vmid);
            String targetInfo = (type != null ? type.name() : "QEMU") + " " + vmid + " (" + guestName + ") on " + node + " [" + serverName + "]";

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
            isActionRunning = false;
            if (dialog != null && dialog.isShowing()) {
                dialog.dismiss();
            }
            Toast.makeText(VmDetailActivity.this, "Operation cancelled: " + action, Toast.LENGTH_SHORT).show();
            loadData();
        }
    }
}
