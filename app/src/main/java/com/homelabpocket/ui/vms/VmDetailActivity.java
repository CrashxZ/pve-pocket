// SPDX-License-Identifier: GPL-3.0-or-later
// Homelab Pocket — VM / LXC Detail Activity
package com.homelabpocket.ui.vms;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
import com.homelabpocket.ui.UiPresentationPolicy;
import com.homelabpocket.api.ActionGuard;
import com.homelabpocket.api.ApiException;
import com.homelabpocket.api.PdmApiDriver;
import com.homelabpocket.api.PveApiDriver;
import com.homelabpocket.model.AuthCredentials;
import com.homelabpocket.model.GuestInfo;
import com.homelabpocket.model.ServerConfig;
import com.homelabpocket.model.TaskResult;
import com.homelabpocket.scheduler.AutoRefreshScheduler;
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
    private boolean isActivityResumed = false;
    private int activeConfirmationCount = 0;

    private AutoRefreshScheduler scheduler;
    private com.homelabpocket.ui.UiPresentationPolicy.ActionCallerTransitionState lastAcknowledgedAction;

    public void setLastAcknowledgedAction(com.homelabpocket.ui.UiPresentationPolicy.ActionCallerTransitionState ack) {
        this.lastAcknowledgedAction = ack;
    }

    public com.homelabpocket.ui.UiPresentationPolicy.ActionCallerTransitionState getLastAcknowledgedAction() {
        return lastAcknowledgedAction;
    }

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                if (scheduler != null) scheduler.setScreenOff(true);
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction()) ||
                       Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
                android.app.KeyguardManager km = (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
                boolean locked = km != null && km.isKeyguardLocked();
                if (scheduler != null) {
                    scheduler.setDeviceLocked(locked);
                    scheduler.setScreenOff(false);
                }
            }
        }
    };

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
    private boolean isGuestMetricsStale = false;
    private boolean isActionRunning = false;
    private ExecutePowerTask currentPowerTask;
    public ExecutePowerTask getCurrentPowerTask() { return currentPowerTask; }
    public void setCurrentPowerTask(ExecutePowerTask task) { this.currentPowerTask = task; }
    private boolean hasShownTargetLookupFailureToast = false;

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

        scheduler = new AutoRefreshScheduler(
                HomelabApp.getInstance().getRefreshPreferenceStore(),
                callback -> new LoadGuestTask(callback).execute(),
                () -> isActionRunning || currentPowerTask != null || activeConfirmationCount > 0 ||
                      ActionGuard.getInstance().isGuestActionRunning(serverId, node, type != null ? type.name() : null, vmid)
        );
        // Prompt fresh read handled in onResume
    }

    @Override
    protected void onResume() {
        super.onResume();
        isActivityResumed = true;
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        try {
            registerReceiver(screenReceiver, filter);
        } catch (Throwable ignored) {}

        android.app.KeyguardManager km = (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        boolean keyguardLocked = km != null && km.isKeyguardLocked();
        boolean sessionLocked = HomelabApp.getInstance().getSessionManager().isLocked();

        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        boolean isInteractive = (pm == null) || pm.isInteractive();

        if (scheduler != null) {
            scheduler.setScreenOff(!isInteractive);
            scheduler.setDeviceLocked(keyguardLocked);
            scheduler.setSessionLocked(sessionLocked);
            scheduler.resume();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        isActivityResumed = false;
        try {
            unregisterReceiver(screenReceiver);
        } catch (Throwable ignored) {}

        if (scheduler != null) {
            scheduler.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isActivityResumed = false;
        if (scheduler != null) {
            scheduler.destroy();
        }
        if (currentPowerTask != null) {
            currentPowerTask.cancel(true);
            if (currentPowerTask.getLifecycleOwner() != null) {
                currentPowerTask.getLifecycleOwner().requestCancel();
            }
            currentPowerTask = null;
        }
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
        hasShownTargetLookupFailureToast = false;
        if (scheduler != null) {
            scheduler.triggerManualRefresh();
        } else {
            new LoadGuestTask(null).execute();
        }
    }

    public AutoRefreshScheduler getScheduler() {
        return scheduler;
    }

    private void handleTargetLookupFailure(String message) {
        handleTargetLookupFailure(message, true);
    }

    private void handleTargetLookupFailure(String message, boolean showToast) {
        this.currentGuest = null;
        this.isGuestMetricsStale = false;
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
        if (showToast && !hasShownTargetLookupFailureToast && message != null && !message.isEmpty()) {
            hasShownTargetLookupFailureToast = true;
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }
    }

    private void bindGuest(GuestInfo g) {
        this.currentGuest = g;
        this.isGuestMetricsStale = false;
        this.hasShownTargetLookupFailureToast = false;
        // Do NOT replace type from fetched guest! Explicit intent validation preserves type invariant.
        tvTitle.setText(g.name + " [" + g.vmid + "]");
        tvIdNode.setText(com.homelabpocket.ui.UiPresentationPolicy.formatVmDetailSubtitle(type != null ? type.name() : "Guest", node));

        ServerConfig serverConfig = null;
        try {
            if (HomelabApp.getInstance() != null && HomelabApp.getInstance().getServerConfigStore() != null) {
                serverConfig = HomelabApp.getInstance().getServerConfigStore().findById(serverId);
            }
        } catch (Throwable ignored) {}

        applyGuestMetrics(com.homelabpocket.ui.UiPresentationPolicy.evaluateGuestMetricsBinding(g, false, serverConfig, null));
    }

    public void markGuestMetricsStale(String error) {
        this.isGuestMetricsStale = true;
        if (currentGuest != null) {
            // Preserve cached metrics clearly stale/Unknown with original fetchedAt
            this.currentGuest = new GuestInfo(
                    currentGuest.vmid, currentGuest.name, currentGuest.type,
                    GuestInfo.RunState.UNKNOWN,
                    currentGuest.node, currentGuest.serverId,
                    currentGuest.cpuUsage, currentGuest.cpuCount,
                    currentGuest.memUsed, currentGuest.memMax,
                    currentGuest.diskUsed, currentGuest.diskTotal,
                    currentGuest.diskRead, currentGuest.diskWrite,
                    currentGuest.netIn, currentGuest.netOut,
                    currentGuest.uptime, currentGuest.tags,
                    currentGuest.favorited, currentGuest.fetchedAt);
        }
        ServerConfig serverConfig = null;
        try {
            if (HomelabApp.getInstance() != null && HomelabApp.getInstance().getServerConfigStore() != null) {
                serverConfig = HomelabApp.getInstance().getServerConfigStore().findById(serverId);
            }
        } catch (Throwable ignored) {}

        applyGuestMetrics(com.homelabpocket.ui.UiPresentationPolicy.evaluateGuestMetricsBinding(
                currentGuest, true, serverConfig, error));
    }

    private void applyGuestMetrics(com.homelabpocket.ui.UiPresentationPolicy.GuestMetricsBindingState state) {
        if (state == null) return;
        boolean isRunning = "RUNNING".equals(state.statusText);
        boolean isStopped = "STOPPED".equals(state.statusText);

        if (tvStatus != null) {
            tvStatus.setText(state.statusText);
            if (isRunning) {
                tvStatus.setBackgroundResource(R.drawable.bg_chip_running);
                tvStatus.setTextColor(getResources().getColor(R.color.matrix_phosphor));
            } else {
                tvStatus.setBackgroundResource(R.drawable.bg_chip_stopped);
                tvStatus.setTextColor(getResources().getColor(R.color.matrix_text_muted));
            }
        }

        if (tvCpu != null) tvCpu.setText(state.cpuText);
        if (progCpu != null) progCpu.setProgress(state.cpuPercent);
        if (tvRam != null) tvRam.setText(state.ramText);
        if (progRam != null) progRam.setProgress(state.ramPercent);
        if (tvDisk != null) tvDisk.setText(state.diskText);
        if (tvUptime != null) tvUptime.setText(state.uptimeText);

        View cardPower = findViewById(R.id.card_guest_power);
        View cardTerminal = findViewById(R.id.card_guest_terminal);
        View cardPdmNotice = findViewById(R.id.card_pdm_notice);

        if (cardPower != null) cardPower.setVisibility(state.isPowerCardVisible ? View.VISIBLE : View.GONE);
        if (cardPdmNotice != null) cardPdmNotice.setVisibility(View.GONE);

        if (btnStart != null && btnShutdown != null && btnReboot != null && btnForceStop != null) {
            if (state.isPowerControlsEnabled) {
                if (isRunning) {
                    btnStart.setVisibility(View.GONE);
                    btnStart.setEnabled(false);
                    btnShutdown.setVisibility(View.VISIBLE);
                    btnShutdown.setEnabled(true);
                    btnReboot.setVisibility(View.VISIBLE);
                    btnReboot.setEnabled(true);
                    btnForceStop.setVisibility(View.VISIBLE);
                    btnForceStop.setEnabled(true);
                } else if (isStopped) {
                    btnStart.setVisibility(View.VISIBLE);
                    btnStart.setEnabled(true);
                    btnShutdown.setVisibility(View.GONE);
                    btnShutdown.setEnabled(false);
                    btnReboot.setVisibility(View.GONE);
                    btnReboot.setEnabled(false);
                    btnForceStop.setVisibility(View.GONE);
                    btnForceStop.setEnabled(false);
                }
            } else {
                btnStart.setVisibility(View.GONE);
                btnStart.setEnabled(false);
                btnShutdown.setVisibility(View.GONE);
                btnShutdown.setEnabled(false);
                btnReboot.setVisibility(View.GONE);
                btnReboot.setEnabled(false);
                btnForceStop.setVisibility(View.GONE);
                btnForceStop.setEnabled(false);
            }
        }

        if (btnTerminal != null) {
            if (state.isTerminalVisible) {
                btnTerminal.setVisibility(View.VISIBLE);
                btnTerminal.setEnabled(true);
                if (cardTerminal != null) cardTerminal.setVisibility(View.VISIBLE);
            } else {
                btnTerminal.setVisibility(View.GONE);
                btnTerminal.setEnabled(false);
                if (cardTerminal != null) cardTerminal.setVisibility(View.GONE);
            }
        }
    }

    public GuestInfo getCurrentGuest() {
        return currentGuest;
    }

    public boolean isGuestMetricsStale() {
        return isGuestMetricsStale;
    }

    public static boolean preflightActionCheck(GuestInfo currentGuest, String node, int vmid, GuestInfo.GuestType type, boolean isGuestMetricsStale, boolean isSessionLocked) {
        if (isSessionLocked) return false;
        if (currentGuest == null || type == null || isGuestMetricsStale) return false;
        return com.homelabpocket.ui.UiPresentationPolicy.isGuestTargetValid(currentGuest, node, vmid, type);
    }

    private void confirmPowerAction(String action) {
        boolean sessionLocked = HomelabApp.getInstance().getSessionManager().isLocked();
        if (!preflightActionCheck(currentGuest, node, vmid, type, isGuestMetricsStale, sessionLocked)) {
            Toast.makeText(this, sessionLocked ? "Action blocked: Session is locked. Please unlock in Settings first." : "Action blocked: Target not verified, moved, or offline. Please refresh.", Toast.LENGTH_SHORT).show();
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

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(actionTitle)
                .setMessage(msg.toString())
                .setPositiveButton(actionVerb, (d, which) -> executeAction(action))
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnDismissListener(d -> activeConfirmationCount = Math.max(0, activeConfirmationCount - 1));
        activeConfirmationCount++;
        dialog.show();
    }

    private void confirmForceStop() {
        boolean sessionLocked = HomelabApp.getInstance().getSessionManager().isLocked();
        if (!preflightActionCheck(currentGuest, node, vmid, type, isGuestMetricsStale, sessionLocked)) {
            Toast.makeText(this, sessionLocked ? "Action blocked: Session is locked. Please unlock in Settings first." : "Action blocked: Target not verified, moved, or offline. Please refresh.", Toast.LENGTH_SHORT).show();
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

        AlertDialog warnDialog = new AlertDialog.Builder(this)
                .setTitle("Warning: Force Stop")
                .setMessage(warnMsg.toString())
                .setPositiveButton("Proceed...", (dialog, which) -> {
                    // Step 2: Separate second confirmation
                    showSecondForceStopConfirmation(isPdm, remote, baseNode, serverName, guestTypeName, guestName);
                })
                .setNegativeButton("Cancel", null)
                .create();
        warnDialog.setOnDismissListener(dialog -> activeConfirmationCount = Math.max(0, activeConfirmationCount - 1));
        activeConfirmationCount++;
        warnDialog.show();
    }

    private void showSecondForceStopConfirmation(boolean isPdm, String remote, String baseNode, String serverName, String guestTypeName, String guestName) {
        AlertDialog secondDialog = new AlertDialog.Builder(this)
                .setTitle("Second Confirmation: Force Stop")
                .setMessage("Are you ABSOLUTELY SURE you want to force stop " + guestTypeName + " " + vmid + " (" + guestName + ")?\n\n"
                        + "This immediate power-kill action cannot be undone.\n\n"
                        + (isPdm && !remote.isEmpty() ? ("• Remote: " + remote + "\n") : "")
                        + "• Node: " + baseNode + "\n"
                        + "• Server: " + serverName)
                .setPositiveButton("Force Stop (Kill)", (dialog, which) -> executeAction("stop"))
                .setNegativeButton("Cancel", null)
                .create();
        secondDialog.setOnDismissListener(dialog -> activeConfirmationCount = Math.max(0, activeConfirmationCount - 1));
        activeConfirmationCount++;
        secondDialog.show();
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

    public void executeAction(String action) {
        // Fail-closed: Require successful exact current target lookup before controls/POST
        boolean sessionLocked = HomelabApp.getInstance().getSessionManager().isLocked();
        if (!preflightActionCheck(currentGuest, node, vmid, type, isGuestMetricsStale, sessionLocked)) {
            Toast.makeText(this, sessionLocked ? "Action blocked: Session is locked. Please unlock in Settings first." : "Action blocked: Target not verified, moved, or offline. Please refresh.", Toast.LENGTH_SHORT).show();
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

    public static class GuestFetchResult {
        public final GuestInfo guest;
        public final String error;
        public final boolean targetNotFound;

        public GuestFetchResult(GuestInfo guest, String error, boolean targetNotFound) {
            this.guest = guest;
            this.error = error;
            this.targetNotFound = targetNotFound;
        }
    }

    private class LoadGuestTask extends AsyncTask<Void, Void, GuestFetchResult> {
        private final AutoRefreshScheduler.RefreshCallback callback;
        private final String serverSnapshot;

        LoadGuestTask(AutoRefreshScheduler.RefreshCallback callback) {
            this.callback = callback;
            this.serverSnapshot = HomelabApp.getInstance().getServerConfigStore().getConfigFingerprint();
        }

        LoadGuestTask() {
            this(null);
        }

        @Override
        protected GuestFetchResult doInBackground(Void... voids) {
            try {
                if (type == null) {
                    return new GuestFetchResult(null, "Missing or invalid intent guest type", true);
                }
                ServerConfig cfg = HomelabApp.getInstance().getServerConfigStore().findById(serverId);
                if (cfg == null || cfg.isLegacyDemo()) {
                    return new GuestFetchResult(null, "Demo server entries cannot execute actions or load detail.", false);
                }
                AuthCredentials creds = HomelabApp.getInstance().getSessionManager().getCredentials(cfg);
                if (creds == null) {
                    return new GuestFetchResult(null, "No credentials found for server.", false);
                }

                if (cfg.type == ServerConfig.ServerType.PDM) {
                    PdmApiDriver pdm = new PdmApiDriver(cfg);
                    GuestInfo g = pdm.fetchGuestDetail(node, vmid, type, creds);
                    if (g == null) {
                        return new GuestFetchResult(null, "Target unavailable: Guest [" + vmid + "] not found on host " + node, true);
                    }
                    return new GuestFetchResult(g, null, false);
                } else {
                    PveApiDriver pve = new PveApiDriver(cfg);
                    List<GuestInfo> guests = pve.fetchAllGuests(node, creds);
                    for (GuestInfo g : guests) {
                        if (g.vmid == vmid && g.type == type && node.equals(g.node)) {
                            return new GuestFetchResult(g, null, false);
                        }
                    }
                    return new GuestFetchResult(null, "Target unavailable: Guest [" + vmid + "] not found on host " + node, true);
                }
            } catch (Exception e) {
                return new GuestFetchResult(null, e.getMessage() != null ? e.getMessage() : "Telemetry fetch failed", false);
            }
        }

        @Override
        protected void onPostExecute(GuestFetchResult res) {
            if (isFinishing() || isDestroyed() || !isActivityResumed) {
                if (callback != null) {
                    callback.onError(new IllegalStateException("Activity not in resumed foreground state"), "Detached");
                }
                return;
            }

            if (HomelabApp.getInstance().getSessionManager().isLocked()) {
                if (scheduler != null) {
                    scheduler.setSessionLocked(true);
                }
                if (callback != null) {
                    callback.onError(new IllegalStateException("Session locked"), "Session locked");
                }
                return;
            }

            // Scheduler callback validity / owner check
            if (callback != null && !callback.canAcceptResult()) {
                callback.onError(new IllegalStateException("Stale or detached callback"), "Stale");
                return;
            }

            // Action in progress / power task / confirmation protection
            if (isActionRunning || currentPowerTask != null || activeConfirmationCount > 0 ||
                ActionGuard.getInstance().isGuestActionRunning(serverId, node, type != null ? type.name() : null, vmid)) {
                if (callback != null) {
                    callback.onError(new IllegalStateException("Action in progress"), "Action in progress");
                }
                return;
            }

            // Server existence and replacement check
            ServerConfig currentCfg = HomelabApp.getInstance().getServerConfigStore().findById(serverId);
            if (currentCfg == null || !serverSnapshot.equals(HomelabApp.getInstance().getServerConfigStore().getConfigFingerprint())) {
                if (callback != null) {
                    callback.onError(new IllegalStateException("Server configuration changed"), "Configuration changed");
                }
                return;
            }

            if (res.guest != null) {
                GuestInfo g = res.guest;
                if (type == null || g.type != type || node == null || !node.equals(g.node) || g.vmid != vmid) {
                    handleTargetLookupFailure("Guest type or identity mismatch on target: expected " +
                            (type != null ? type.name() : "null") + " [" + vmid + "] on " + node +
                            ", found " + g.type + " [" + g.vmid + "] on " + g.node, true);
                    if (callback != null) {
                        callback.onError(new Exception("Guest identity mismatch"), "Identity mismatch");
                    }
                    return;
                }
                bindGuest(g);
                if (callback != null) {
                    callback.onSuccess();
                }
            } else if (res.targetNotFound) {
                handleTargetLookupFailure(res.error, true);
                if (callback != null) {
                    callback.onError(new Exception("Target unavailable"), "Target unavailable");
                }
            } else {
                // Routine telemetry transport error or selected remote failure: preserve old data as stale/UNKNOWN, DISABLE power controls!
                if (currentGuest == null) {
                    handleTargetLookupFailure(res.error, false);
                } else {
                    markGuestMetricsStale(res.error);
                }
                if (callback != null) {
                    callback.onError(new Exception(res.error != null ? res.error : "Telemetry error"), res.error);
                }
            }
        }

        @Override
        protected void onCancelled(GuestFetchResult res) {
            if (callback != null) {
                callback.onError(new IllegalStateException("Task cancelled"), "Cancelled");
            }
        }

        @Override
        protected void onCancelled() {
            if (callback != null) {
                callback.onError(new IllegalStateException("Task cancelled"), "Cancelled");
            }
        }
    }

    public class ExecutePowerTask extends AsyncTask<Void, TaskResult, TaskResult> {
        private final String action;
        private final com.homelabpocket.api.ActionGuard.TaskLifecycleOwner lifecycleOwner;
        private ProgressDialog dialog;
        private String error;

        public ExecutePowerTask(String action, com.homelabpocket.api.ActionGuard.TaskLifecycleOwner lifecycleOwner) {
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
        public TaskResult doInBackground(Void... voids) {
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
        public void onPostExecute(TaskResult taskResult) {
            releaseGuardOnce();
            isActionRunning = false;
            currentPowerTask = null;
            try {
                if (dialog != null && dialog.isShowing()) {
                    dialog.dismiss();
                }

                ServerConfig cfg = HomelabApp.getInstance().getServerConfigStore().findById(serverId);
                String serverName = cfg != null ? cfg.name : serverId;
                String guestName = (tvTitle != null && tvTitle.getText() != null) ? tvTitle.getText().toString() : ("VM " + vmid);
                String targetTypeStr = (type != null ? type.name() : (currentGuest != null && currentGuest.type != null ? currentGuest.type.name() : "Guest"));
                String targetInfo = targetTypeStr + " " + vmid + " (" + guestName + ") on " + node + " [" + serverName + "]";

                com.homelabpocket.ui.UiPresentationPolicy.ActionCallerTransitionState transition =
                        transitionCallerAction(taskResult, action, targetInfo, error,
                                () -> {
                                    if (scheduler != null) {
                                        scheduler.requestPostOperationRefresh();
                                    } else {
                                        loadData();
                                    }
                                });

                if (transition.presentation.type == com.homelabpocket.ui.UiPresentationPolicy.TaskResultPresentation.DialogType.TOAST_SUCCESS) {
                    Toast.makeText(VmDetailActivity.this, transition.presentation.message, Toast.LENGTH_SHORT).show();
                } else {
                    new AlertDialog.Builder(VmDetailActivity.this)
                            .setTitle(transition.presentation.title)
                            .setMessage(transition.presentation.message)
                            .setPositiveButton("OK", null)
                            .show();
                }

                VmDetailActivity.this.lastAcknowledgedAction = transition;
                isActionRunning = transition.isActionRunning;
            } finally {
                currentPowerTask = null;
            }
        }

        @Override
        protected void onCancelled(TaskResult taskResult) {
            releaseGuardOnce();
            currentPowerTask = null;
            isActionRunning = false;
            try {
                if (dialog != null && dialog.isShowing()) {
                    dialog.dismiss();
                }
                Toast.makeText(VmDetailActivity.this, "Operation cancelled: " + action, Toast.LENGTH_SHORT).show();
                if (scheduler != null) {
                    scheduler.requestPostOperationRefresh();
                } else {
                    loadData();
                }
            } catch (Throwable ignored) {}
        }

        @Override
        protected void onCancelled() {
            releaseGuardOnce();
            currentPowerTask = null;
            isActionRunning = false;
            try {
                if (dialog != null && dialog.isShowing()) {
                    dialog.dismiss();
                }
                if (scheduler != null) {
                    scheduler.requestPostOperationRefresh();
                } else {
                    loadData();
                }
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Package-private caller transition seam wired to production ExecutePowerTask.onPostExecute.
     * Can be invoked directly in behavioral unit tests to verify caller state transitions and isolation.
     */
    public static com.homelabpocket.ui.UiPresentationPolicy.ActionCallerTransitionState transitionCallerAction(
            TaskResult taskResult, String action, String targetInfo, String error,
            com.homelabpocket.ui.UiPresentationPolicy.PostRefreshTrigger refreshTrigger) {
        return com.homelabpocket.ui.UiPresentationPolicy.performActionCompletionTransition(
                taskResult, action, targetInfo, error, refreshTrigger);
    }
}
