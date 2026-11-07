// SPDX-License-Identifier: GPL-3.0-or-later
// PVE Pocket — Node Detail Activity with Storage Pools and Hosted Guests
package com.homelabpocket.ui.nodes;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.homelabpocket.HomelabApp;
import com.homelabpocket.R;
import com.homelabpocket.api.ActionGuard;
import com.homelabpocket.api.ApiException;
import com.homelabpocket.api.PdmApiDriver;
import com.homelabpocket.api.PveApiDriver;
import com.homelabpocket.api.SessionManager;
import com.homelabpocket.model.AuthCredentials;
import com.homelabpocket.model.GuestInfo;
import com.homelabpocket.model.NodeStatus;
import com.homelabpocket.model.ServerConfig;
import com.homelabpocket.model.StoragePool;
import com.homelabpocket.scheduler.AutoRefreshScheduler;
import com.homelabpocket.ui.UiPresentationPolicy;
import com.homelabpocket.ui.terminal.TerminalActivity;
import com.homelabpocket.ui.vms.VmDetailActivity;
import com.homelabpocket.util.JsonUtil;

import java.util.ArrayList;
import java.util.List;

public class NodeDetailActivity extends Activity {

    public static final String EXTRA_NODE = "extra_node";
    public static final String EXTRA_SERVER_ID = "extra_server_id";

    private String nodeName;
    private String serverId;
    private ServerConfig server;
    private boolean isActivityResumed = false;

    private AutoRefreshScheduler scheduler;

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
    private TextView tvName;
    private Button btnShell;

    private TextView tvCpu;
    private ProgressBar progCpu;
    private TextView tvRam;
    private ProgressBar progRam;
    private TextView tvDisk;
    private ProgressBar progDisk;
    private TextView tvUptime;
    private TextView tvKernel;

    private NodeStatus cachedNode = null;
    private boolean isNodeMetricsStale = false;
    private LoadNodeTask currentLoadTask = null;

    private LinearLayout layoutDetailStorage;
    private LinearLayout layoutDetailGuests;
    private List<StoragePool> cachedStoragePools = new ArrayList<>();
    private List<GuestInfo> cachedGuests = new ArrayList<>();

    public interface ViewBindingListener {
        void onStorageLoading();
        void onGuestsLoading();
        void onNodeMetricsBound(UiPresentationPolicy.NodeMetricsBindingState metricsState);
        void onStorageBound(UiPresentationPolicy.NodeStorageBindingState storageState);
        void onGuestsBound(UiPresentationPolicy.NodeGuestsBindingState guestsState);
        void onShellControlBound(boolean shellEnabled);
    }

    public static class HeadlessViewRecorder implements ViewBindingListener {
        public boolean isStorageLoading = false;
        public boolean isGuestsLoading = false;
        public UiPresentationPolicy.NodeMetricsBindingState lastMetricsState;
        public UiPresentationPolicy.NodeStorageBindingState lastStorageState;
        public UiPresentationPolicy.NodeGuestsBindingState lastGuestsState;
        public Boolean lastShellEnabled;

        @Override
        public void onStorageLoading() {
            this.isStorageLoading = true;
        }

        @Override
        public void onGuestsLoading() {
            this.isGuestsLoading = true;
        }

        @Override
        public void onNodeMetricsBound(UiPresentationPolicy.NodeMetricsBindingState metricsState) {
            this.lastMetricsState = metricsState;
        }

        @Override
        public void onStorageBound(UiPresentationPolicy.NodeStorageBindingState storageState) {
            this.isStorageLoading = false;
            this.lastStorageState = storageState;
        }

        @Override
        public void onGuestsBound(UiPresentationPolicy.NodeGuestsBindingState guestsState) {
            this.isGuestsLoading = false;
            this.lastGuestsState = guestsState;
        }

        @Override
        public void onShellControlBound(boolean shellEnabled) {
            this.lastShellEnabled = shellEnabled;
        }
    }

    private ViewBindingListener viewBindingListener;

    public void setViewBindingListener(ViewBindingListener listener) {
        this.viewBindingListener = listener;
    }

    public ViewBindingListener getViewBindingListener() {
        return this.viewBindingListener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_node_detail);

        nodeName = getIntent().getStringExtra(EXTRA_NODE);
        serverId = getIntent().getStringExtra(EXTRA_SERVER_ID);

        initViews();

        scheduler = new AutoRefreshScheduler(
                HomelabApp.getInstance().getRefreshPreferenceStore(),
                callback -> {
                    if (currentLoadTask != null) {
                        currentLoadTask.cancel(true);
                    }
                    currentLoadTask = new LoadNodeTask(callback);
                    currentLoadTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                },
                () -> ActionGuard.getInstance().isRunning(nodeName)
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

        if (currentLoadTask != null) {
            currentLoadTask.cancel(true);
            currentLoadTask = null;
        }

        if (scheduler != null) {
            scheduler.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isActivityResumed = false;
        if (currentLoadTask != null) {
            currentLoadTask.cancel(true);
            currentLoadTask = null;
        }
        if (scheduler != null) {
            scheduler.destroy();
        }
    }

    public AutoRefreshScheduler getScheduler() {
        return scheduler;
    }

    private void initViews() {
        btnBack = findViewById(R.id.btn_back);
        tvName = findViewById(R.id.tv_detail_node_name);
        btnShell = findViewById(R.id.btn_detail_shell);

        tvCpu = findViewById(R.id.tv_detail_cpu);
        progCpu = findViewById(R.id.progress_detail_cpu);
        tvRam = findViewById(R.id.tv_detail_ram);
        progRam = findViewById(R.id.progress_detail_ram);
        tvDisk = findViewById(R.id.tv_detail_disk);
        progDisk = findViewById(R.id.progress_detail_disk);
        tvUptime = findViewById(R.id.tv_detail_uptime);
        tvKernel = findViewById(R.id.tv_detail_pve_ver);

        layoutDetailStorage = findViewById(R.id.layout_detail_storage);
        layoutDetailGuests = findViewById(R.id.layout_detail_guests);

        btnBack.setOnClickListener(v -> finish());
        if (nodeName != null && nodeName.contains("/")) {
            int slash = nodeName.indexOf('/');
            String remote = nodeName.substring(0, slash);
            String base = nodeName.substring(slash + 1);
            tvName.setText(base + "\n[Remote: " + remote + "]");
            tvName.setTextSize(13);
        } else {
            tvName.setText(nodeName != null ? nodeName : "Host Node");
        }

        server = null;
        try {
            if (HomelabApp.getInstance() != null && HomelabApp.getInstance().getServerConfigStore() != null) {
                server = HomelabApp.getInstance().getServerConfigStore().findById(serverId);
            }
        } catch (Throwable ignored) {}

        TextView tvPdmBadge = findViewById(R.id.tv_detail_pdm_badge);
        boolean isPdm = com.homelabpocket.ui.UiPresentationPolicy.isPdm(server);
        if (isPdm) {
            btnShell.setVisibility(View.GONE);
            if (tvPdmBadge != null) {
                tvPdmBadge.setVisibility(View.VISIBLE);
            }
        } else {
            if (tvPdmBadge != null) {
                tvPdmBadge.setVisibility(View.GONE);
            }
            btnShell.setVisibility(View.VISIBLE);
            btnShell.setText("Shell");
            btnShell.setEnabled(true);
            btnShell.setAlpha(1.0f);
            btnShell.setOnClickListener(v -> {
                Intent intent = new Intent(this, TerminalActivity.class);
                intent.putExtra(TerminalActivity.EXTRA_NODE, nodeName);
                intent.putExtra(TerminalActivity.EXTRA_SERVER_ID, serverId);
                intent.putExtra(TerminalActivity.EXTRA_TYPE, "NODE");
                intent.putExtra(TerminalActivity.EXTRA_TITLE, nodeName + " (Host Shell)");
                startActivity(intent);
            });
        }

        setStorageLoading();
        setGuestsLoading();
        applyNodeMetrics(com.homelabpocket.ui.UiPresentationPolicy.evaluateNodeMetricsBinding(null, false, server));
    }

    public void setStorageLoading() {
        if (viewBindingListener != null) {
            viewBindingListener.onStorageLoading();
        }
        if (layoutDetailStorage != null) {
            layoutDetailStorage.removeAllViews();
            TextView tv = new TextView(this);
            tv.setText("Loading configured storage pools...");
            tv.setTextColor(getResources().getColor(R.color.matrix_text_secondary));
            tv.setTextSize(12);
            tv.setPadding(0, 8, 0, 8);
            layoutDetailStorage.addView(tv);
        }
    }

    public void setGuestsLoading() {
        if (viewBindingListener != null) {
            viewBindingListener.onGuestsLoading();
        }
        if (layoutDetailGuests != null) {
            layoutDetailGuests.removeAllViews();
            TextView tv = new TextView(this);
            tv.setText("Loading hosted guest instances (VMs & containers)...");
            tv.setTextColor(getResources().getColor(R.color.matrix_text_secondary));
            tv.setTextSize(12);
            tv.setPadding(0, 8, 0, 8);
            layoutDetailGuests.addView(tv);
        }
    }

    private void loadData() {
        if (scheduler != null) {
            scheduler.triggerManualRefresh();
        } else {
            if (currentLoadTask != null) {
                currentLoadTask.cancel(true);
            }
            currentLoadTask = new LoadNodeTask(null);
            currentLoadTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        }
    }

    private void bindNode(NodeStatus n) {
        cachedNode = n;
        isNodeMetricsStale = false;
        applyNodeMetrics(com.homelabpocket.ui.UiPresentationPolicy.evaluateNodeMetricsBinding(n, false, server));
    }

    public void markNodeMetricsStale() {
        isNodeMetricsStale = true;
        applyNodeMetrics(com.homelabpocket.ui.UiPresentationPolicy.evaluateNodeMetricsBinding(cachedNode, true, server));
    }

    private void applyNodeMetrics(com.homelabpocket.ui.UiPresentationPolicy.NodeMetricsBindingState state) {
        if (state == null) return;
        if (viewBindingListener != null) {
            viewBindingListener.onNodeMetricsBound(state);
        }
        if (tvCpu != null) tvCpu.setText(state.cpuText);
        if (progCpu != null) progCpu.setProgress(state.cpuPercent);
        if (tvRam != null) tvRam.setText(state.ramText);
        if (progRam != null) progRam.setProgress(state.ramPercent);
        if (tvDisk != null) tvDisk.setText(state.diskText);
        if (progDisk != null) {
            progDisk.setVisibility(state.isDiskBarVisible ? View.VISIBLE : View.GONE);
            if (state.isDiskBarVisible) {
                progDisk.setProgress(state.diskPercent);
            }
        }
        if (tvUptime != null) tvUptime.setText(state.uptimeText);
        if (tvKernel != null) tvKernel.setText(state.releaseText);
    }

    public NodeStatus getCachedNode() {
        return cachedNode;
    }

    public boolean isNodeMetricsStale() {
        return isNodeMetricsStale;
    }

    public List<StoragePool> getCachedStoragePools() {
        return cachedStoragePools != null ? new ArrayList<>(cachedStoragePools) : new ArrayList<>();
    }

    public List<GuestInfo> getCachedGuests() {
        return cachedGuests != null ? new ArrayList<>(cachedGuests) : new ArrayList<>();
    }

    public void bindStorage(List<StoragePool> pools, String error) {
        if (layoutDetailStorage == null) return;
        UiPresentationPolicy.NodeStorageBindingState state =
                UiPresentationPolicy.evaluateNodeStorageBinding(pools, error, cachedStoragePools);
        applyStorageState(state);
    }

    public void applyStorageState(UiPresentationPolicy.NodeStorageBindingState state) {
        if (state == null) return;
        cachedStoragePools = new ArrayList<>(state.cachedPools);
        if (viewBindingListener != null) {
            viewBindingListener.onStorageBound(state);
        }
        if (layoutDetailStorage == null) return;
        layoutDetailStorage.removeAllViews();

        if (state.errorMessage != null) {
            TextView tvError = new TextView(this);
            tvError.setText(state.errorMessage);
            tvError.setTextColor(getResources().getColor(R.color.matrix_warning));
            tvError.setTextSize(12);
            layoutDetailStorage.addView(tvError);
            if (state.displayedPools.isEmpty()) {
                return;
            }
        }

        if (state.emptyMessage != null) {
            TextView tvEmpty = new TextView(this);
            tvEmpty.setText(state.emptyMessage);
            tvEmpty.setTextColor(getResources().getColor(R.color.matrix_text_muted));
            tvEmpty.setTextSize(12);
            layoutDetailStorage.addView(tvEmpty);
            return;
        }

        for (StoragePool pool : state.displayedPools) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundResource(R.drawable.bg_card_matrix);
            int p = (int) (12 * getResources().getDisplayMetrics().density);
            card.setPadding(p, p, p, p);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = (int) (8 * getResources().getDisplayMetrics().density);
            card.setLayoutParams(lp);

            // Row 1: Name and Type badge
            LinearLayout header = new LinearLayout(this);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            TextView tvName = new TextView(this);
            tvName.setText(pool.name);
            tvName.setTextColor(getResources().getColor(R.color.matrix_text_primary));
            tvName.setTextSize(14);
            tvName.setTypeface(null, android.graphics.Typeface.BOLD);
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
            tvName.setLayoutParams(nameLp);
            header.addView(tvName);

            TextView tvBadge = new TextView(this);
            tvBadge.setText(pool.getTypeBadge());
            tvBadge.setTextColor(getResources().getColor(R.color.matrix_cyan));
            tvBadge.setTextSize(10);
            tvBadge.setTypeface(null, android.graphics.Typeface.BOLD);
            tvBadge.setBackgroundResource(R.drawable.bg_chip_lxc);
            tvBadge.setPadding(12, 4, 12, 4);
            header.addView(tvBadge);

            card.addView(header);

            boolean isPdm = com.homelabpocket.ui.UiPresentationPolicy.isPdm(server);
            String typeFootnote = com.homelabpocket.ui.UiPresentationPolicy.getStorageTypeFootnote(pool, isPdm);
            if (typeFootnote != null) {
                TextView tvTypeNote = new TextView(this);
                tvTypeNote.setText(typeFootnote);
                tvTypeNote.setTextColor(getResources().getColor(R.color.matrix_text_muted));
                tvTypeNote.setTextSize(10);
                tvTypeNote.setTypeface(android.graphics.Typeface.MONOSPACE);
                card.addView(tvTypeNote);
            }

            // Row 2: Usage text
            TextView tvUsage = new TextView(this);
            String usageStr = "Used: " + pool.getFormattedUsed() + " / " + pool.getFormattedTotal() +
                    " (Avail: " + pool.getFormattedAvail() + " • " + pool.getUsagePercent() + "%)";
            tvUsage.setText(usageStr);
            tvUsage.setTextColor(getResources().getColor(R.color.matrix_text_secondary));
            tvUsage.setTextSize(11);
            LinearLayout.LayoutParams usageLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            usageLp.topMargin = (int) (6 * getResources().getDisplayMetrics().density);
            usageLp.bottomMargin = (int) (4 * getResources().getDisplayMetrics().density);
            tvUsage.setLayoutParams(usageLp);
            card.addView(tvUsage);

            // Row 3: Progress bar
            ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            pb.setMax(100);
            pb.setProgress(pool.getUsagePercent());
            LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (int) (6 * getResources().getDisplayMetrics().density));
            pb.setLayoutParams(pbLp);
            card.addView(pb);

            layoutDetailStorage.addView(card);
        }
    }

    public void bindGuests(List<GuestInfo> guests, String error, boolean isOffline) {
        if (layoutDetailGuests == null) return;
        UiPresentationPolicy.NodeGuestsBindingState state =
                UiPresentationPolicy.evaluateNodeGuestsBinding(guests, error, isOffline, cachedGuests);
        applyGuestsState(state);
    }

    public void applyGuestsState(UiPresentationPolicy.NodeGuestsBindingState state) {
        if (state == null) return;
        cachedGuests = new ArrayList<>(state.cachedGuests);
        if (viewBindingListener != null) {
            viewBindingListener.onGuestsBound(state);
        }
        if (layoutDetailGuests == null) return;
        layoutDetailGuests.removeAllViews();

        if (state.offlineMessage != null) {
            TextView tvOffline = new TextView(this);
            tvOffline.setText(state.offlineMessage);
            tvOffline.setTextColor(getResources().getColor(R.color.matrix_warning));
            tvOffline.setTextSize(12);
            layoutDetailGuests.addView(tvOffline);
            return;
        }

        if (state.errorMessage != null) {
            TextView tvErr = new TextView(this);
            tvErr.setText(state.errorMessage);
            tvErr.setTextColor(getResources().getColor(R.color.matrix_warning));
            tvErr.setTextSize(12);
            layoutDetailGuests.addView(tvErr);
            if (state.displayedGuests.isEmpty()) {
                return;
            }
        }

        if (state.emptyMessage != null) {
            TextView tvEmpty = new TextView(this);
            tvEmpty.setText(state.emptyMessage);
            tvEmpty.setTextColor(getResources().getColor(R.color.matrix_text_muted));
            tvEmpty.setTextSize(12);
            layoutDetailGuests.addView(tvEmpty);
            return;
        }

        LayoutInflater inflater = LayoutInflater.from(this);
        for (GuestInfo g : state.displayedGuests) {
            View card = inflater.inflate(R.layout.item_guest, layoutDetailGuests, false);

            TextView tvName = card.findViewById(R.id.tv_guest_name);
            TextView tvVmidNode = card.findViewById(R.id.tv_guest_vmid_node);
            TextView tvType = card.findViewById(R.id.tv_guest_type);
            TextView tvStatus = card.findViewById(R.id.tv_guest_status);
            ImageButton btnFav = card.findViewById(R.id.btn_favorite);
            if (btnFav != null) {
                btnFav.setVisibility(View.GONE);
            }

            View layoutMetrics = card.findViewById(R.id.layout_guest_metrics);
            TextView tvCpu = card.findViewById(R.id.tv_guest_cpu);
            TextView tvRam = card.findViewById(R.id.tv_guest_ram);
            ProgressBar progCpu = card.findViewById(R.id.progress_guest_cpu);

            // Hide the power button row in node detail view
            View btnStart = card.findViewById(R.id.btn_guest_start);
            if (btnStart != null && btnStart.getParent() != null && btnStart.getParent().getParent() instanceof View) {
                ((View) btnStart.getParent().getParent()).setVisibility(View.GONE);
            }

            if (tvName != null) {
                tvName.setText(g.name != null && !g.name.isEmpty() ? g.name : "Guest " + g.vmid);
            }
            if (tvVmidNode != null) {
                tvVmidNode.setText("[" + g.vmid + "] • " + g.node);
            }
            if (tvType != null) {
                tvType.setText(g.type == GuestInfo.GuestType.LXC ? "LXC" : "QEMU");
                tvType.setBackgroundResource(g.type == GuestInfo.GuestType.LXC ? R.drawable.bg_chip_lxc : R.drawable.bg_chip_qemu);
            }
            if (tvStatus != null) {
                boolean running = g.state == GuestInfo.RunState.RUNNING;
                tvStatus.setText(g.state.name());
                tvStatus.setBackgroundResource(running ? R.drawable.bg_chip_running : R.drawable.bg_chip_stopped);
                tvStatus.setTextColor(getResources().getColor(running ? R.color.matrix_phosphor : R.color.matrix_text_muted));
            }

            if (g.state == GuestInfo.RunState.RUNNING) {
                if (layoutMetrics != null) layoutMetrics.setVisibility(View.VISIBLE);
                if (tvCpu != null && progCpu != null) {
                    int cpuPct = (int) Math.round(!Double.isNaN(g.cpuUsage) ? g.cpuUsage * 100 : 0);
                    tvCpu.setText("CPU: " + cpuPct + "%");
                    progCpu.setProgress(cpuPct);
                }
                if (tvRam != null) {
                    tvRam.setText("RAM: " + JsonUtil.formatBytes(g.memUsed) + " / " + JsonUtil.formatBytes(g.memTotal));
                }
            } else {
                if (layoutMetrics != null) layoutMetrics.setVisibility(View.GONE);
            }

            card.setOnClickListener(v -> {
                Intent intent = new Intent(this, VmDetailActivity.class);
                intent.putExtra(VmDetailActivity.EXTRA_VMID, g.vmid);
                intent.putExtra(VmDetailActivity.EXTRA_NODE, g.node);
                intent.putExtra(VmDetailActivity.EXTRA_SERVER_ID, g.serverId);
                intent.putExtra(VmDetailActivity.EXTRA_TYPE, g.type == GuestInfo.GuestType.LXC ? "LXC" : "QEMU");
                intent.putExtra(VmDetailActivity.EXTRA_NAME, g.name);
                startActivity(intent);
            });

            layoutDetailGuests.addView(card);
        }
    }

    public static class NodeDataResult {
        public final NodeStatus node;
        public final List<StoragePool> storagePools;
        public final String storageError;
        public final List<GuestInfo> guests;
        public final String guestError;
        public final boolean isOffline;

        public NodeDataResult(NodeStatus node, List<StoragePool> storagePools, String storageError,
                              List<GuestInfo> guests, String guestError, boolean isOffline) {
            this.node = node;
            this.storagePools = storagePools;
            this.storageError = storageError;
            this.guests = guests;
            this.guestError = guestError;
            this.isOffline = isOffline;
        }
    }

    public static class LoadCompletionDecision {
        public final boolean accepted;
        public final String rejectionReason;
        public final boolean isSuccess;
        public final String callbackError;

        public LoadCompletionDecision(boolean accepted, String rejectionReason, boolean isSuccess, String callbackError) {
            this.accepted = accepted;
            this.rejectionReason = rejectionReason;
            this.isSuccess = isSuccess;
            this.callbackError = callbackError;
        }
    }

    public static LoadCompletionDecision evaluateLoadCompletion(
            boolean isActivityResumed,
            boolean isSessionLocked,
            boolean isActionRunning,
            boolean isConfigChanged,
            boolean canCallbackAccept,
            NodeDataResult res) {
        if (!isActivityResumed) {
            return new LoadCompletionDecision(false, "Detached", false, "Activity not in resumed foreground state");
        }
        if (isSessionLocked) {
            return new LoadCompletionDecision(false, "Session locked", false, "Session locked");
        }
        if (!canCallbackAccept) {
            return new LoadCompletionDecision(false, "Stale", false, "Stale or detached callback");
        }
        if (isActionRunning) {
            return new LoadCompletionDecision(false, "Action in progress", false, "Action in progress");
        }
        if (isConfigChanged) {
            return new LoadCompletionDecision(false, "Configuration changed", false, "Server configuration changed");
        }
        if (res == null || res.node == null || res.isOffline || res.storageError != null || res.guestError != null) {
            return new LoadCompletionDecision(true, null, false, "Node offline or telemetry error");
        }
        return new LoadCompletionDecision(true, null, true, null);
    }

    public static class NodeDetailPresentationState {
        public final LoadCompletionDecision decision;
        public final UiPresentationPolicy.NodeMetricsBindingState metricsState;
        public final UiPresentationPolicy.NodeStorageBindingState storageState;
        public final UiPresentationPolicy.NodeGuestsBindingState guestsState;
        public final boolean shellEnabled;

        public NodeDetailPresentationState(LoadCompletionDecision decision,
                                           UiPresentationPolicy.NodeMetricsBindingState metricsState,
                                           UiPresentationPolicy.NodeStorageBindingState storageState,
                                           UiPresentationPolicy.NodeGuestsBindingState guestsState,
                                           boolean shellEnabled) {
            this.decision = decision;
            this.metricsState = metricsState;
            this.storageState = storageState;
            this.guestsState = guestsState;
            this.shellEnabled = shellEnabled;
        }
    }

    public static NodeDetailPresentationState completeAndBind(
            boolean isActivityResumed,
            boolean isSessionLocked,
            boolean isActionRunning,
            boolean isConfigChanged,
            boolean canCallbackAccept,
            NodeDataResult res,
            ServerConfig server,
            List<StoragePool> currentStorageCache,
            List<GuestInfo> currentGuestCache,
            AutoRefreshScheduler.RefreshCallback callback,
            AutoRefreshScheduler scheduler) {
        return completeAndBind(isActivityResumed, isSessionLocked, isActionRunning, isConfigChanged,
                canCallbackAccept, res, server, null, currentStorageCache, currentGuestCache, callback, scheduler);
    }

    public static NodeDetailPresentationState completeAndBind(
            boolean isActivityResumed,
            boolean isSessionLocked,
            boolean isActionRunning,
            boolean isConfigChanged,
            boolean canCallbackAccept,
            NodeDataResult res,
            ServerConfig server,
            NodeStatus currentNodeCache,
            List<StoragePool> currentStorageCache,
            List<GuestInfo> currentGuestCache,
            AutoRefreshScheduler.RefreshCallback callback,
            AutoRefreshScheduler scheduler) {
        LoadCompletionDecision decision = evaluateLoadCompletion(
                isActivityResumed, isSessionLocked, isActionRunning, isConfigChanged, canCallbackAccept, res);

        if (!decision.accepted) {
            if (isSessionLocked && scheduler != null) {
                scheduler.setSessionLocked(true);
            }
            if (callback != null) {
                callback.onError(new IllegalStateException(decision.callbackError), decision.rejectionReason);
            }
            return new NodeDetailPresentationState(decision, null, null, null, false);
        }

        NodeStatus effectiveNode = (res != null && res.node != null) ? res.node : currentNodeCache;
        boolean isMetricsStale = (res == null || res.node == null || res.isOffline);
        boolean isMetricsStalePreserved = isMetricsStale && (currentNodeCache != null && currentNodeCache.isOnline());
        UiPresentationPolicy.NodeMetricsBindingState metricsState =
                UiPresentationPolicy.evaluateNodeMetricsBinding(
                        isMetricsStalePreserved ? currentNodeCache : effectiveNode,
                        isMetricsStalePreserved,
                        server);

        UiPresentationPolicy.NodeStorageBindingState storageState =
                UiPresentationPolicy.evaluateNodeStorageBinding(res != null ? res.storagePools : null, res != null ? res.storageError : null, currentStorageCache);

        UiPresentationPolicy.NodeGuestsBindingState guestsState =
                UiPresentationPolicy.evaluateNodeGuestsBinding(res != null ? res.guests : null, res != null ? res.guestError : null, res != null && res.isOffline, currentGuestCache);

        boolean isPdm = UiPresentationPolicy.isPdm(server);
        boolean canShell = !isPdm && res != null && !res.isOffline && res.node != null && res.node.isOnline();

        if (callback != null) {
            if (decision.isSuccess) {
                callback.onSuccess();
            } else {
                callback.onError(new Exception(decision.callbackError), decision.callbackError);
            }
        }

        return new NodeDetailPresentationState(decision, metricsState, storageState, guestsState, canShell);
    }

    public static NodeDataResult loadNodeData(ServerConfig cfg, AuthCredentials creds, String nodeName, String serverId) {
        NodeStatus status = null;
        List<StoragePool> storagePools = new ArrayList<>();
        String storageError = null;
        List<GuestInfo> guests = new ArrayList<>();
        String guestError = null;
        boolean isOffline = false;

        try {
            if (cfg == null || cfg.isLegacyDemo()) {
                return new NodeDataResult(null, storagePools, "Server not found", guests, "Server not found", true);
            }
            if (creds == null) {
                return new NodeDataResult(null, storagePools, "Authentication required", guests, "Authentication required", false);
            }

            SessionManager sm = (HomelabApp.getInstance() != null) ? HomelabApp.getInstance().getSessionManager() : null;
            if (cfg.type == ServerConfig.ServerType.PDM) {
                PdmApiDriver pdm = new PdmApiDriver(cfg, sm);
                String targetRemote = PdmApiDriver.extractRemote(nodeName);
                String targetBaseNode = PdmApiDriver.extractNode(nodeName);

                // Single unified fleet fetch: retrieves nodes, guests, and storage pools in ONE HTTP roundtrip
                long fetchBudgetMs = 15_000L;
                PdmApiDriver.PdmFleetResult fleet = pdm.fetchFleetResources(creds, fetchBudgetMs);

                PdmApiDriver.PdmRemoteGroupResult matchedGroup = null;
                for (PdmApiDriver.PdmRemoteGroupResult g : fleet.remoteGroups) {
                    if (targetRemote.equals(g.remote) || (targetRemote.isEmpty() && targetBaseNode.equals(g.remote))) {
                        matchedGroup = g;
                        break;
                    }
                }

                if (matchedGroup == null) {
                    isOffline = true;
                    storageError = "Remote cluster '" + targetRemote + "' unavailable";
                    guestError = "Remote cluster '" + targetRemote + "' unavailable";
                    status = NodeStatus.offline(nodeName, cfg.id);
                } else if (!matchedGroup.isSuccess) {
                    isOffline = true;
                    String remErr = !matchedGroup.error.isEmpty() ? matchedGroup.error : "Remote cluster unreachable";
                    storageError = "Host is offline: " + remErr;
                    guestError = "Host is offline: " + remErr;
                    status = NodeStatus.offline(nodeName, cfg.id);
                } else {
                    for (NodeStatus n : fleet.nodes) {
                        if (nodeName != null && nodeName.equals(n.node)) {
                            status = n;
                            break;
                        }
                    }
                    if (status == null) {
                        for (NodeStatus n : fleet.placeholderNodes) {
                            if (nodeName != null && nodeName.equals(n.node)) {
                                status = n;
                                break;
                            }
                        }
                    }

                    if (status == null) {
                        isOffline = true;
                        storageError = "Node '" + nodeName + "' unavailable";
                        guestError = "Node '" + nodeName + "' unavailable";
                        status = NodeStatus.offline(nodeName, cfg.id);
                    } else if (status.status == NodeStatus.Status.OFFLINE || status.status == NodeStatus.Status.UNKNOWN) {
                        isOffline = true;
                        storageError = "Host is offline (unreachable)";
                        guestError = "Host is offline (unreachable)";
                    }

                    if (!isOffline) {
                        for (GuestInfo g : matchedGroup.guests) {
                            if (nodeName.equals(g.node)) {
                                guests.add(g);
                            }
                        }
                        for (StoragePool sp : matchedGroup.storagePools) {
                            boolean matches;
                            if (sp.node != null && !sp.node.isEmpty()) {
                                matches = nodeName.equals(sp.node)
                                        || (targetBaseNode != null && targetBaseNode.equals(sp.node))
                                        || (targetRemote != null && !targetRemote.isEmpty() && (targetRemote + "/" + sp.node).equals(nodeName));
                            } else {
                                // Sibling node pools cannot leak: a storage pool without explicit node provenance
                                // is only matched if it is explicitly shared cluster-wide
                                matches = sp.shared;
                            }
                            if (matches) {
                                storagePools.add(sp);
                            }
                        }

                        // Optional storage pool backend type enrichment: up to 5 seconds budget.
                        // Total cumulative deadline for full node detail is up to 20 seconds (15s fleet fetch + 5s enrichment).
                        if (!storagePools.isEmpty()) {
                            try {
                                storagePools = pdm.enrichStoragePools(nodeName, storagePools, creds, 5_000L);
                            } catch (Exception ignored) {}
                        }
                    }
                }

            } else {
                PveApiDriver pve = new PveApiDriver(cfg, sm);
                status = pve.fetchNodeDetail(nodeName, creds);

                if (status != null && status.status == NodeStatus.Status.OFFLINE) {
                    isOffline = true;
                    storageError = "Host is offline (unreachable)";
                    guestError = "Host is offline (unreachable)";
                }

                if (!isOffline) {
                    try {
                        guests = pve.fetchAllGuests(nodeName, creds);
                    } catch (ApiException e) {
                        guestError = e.getUserMessage();
                    } catch (Exception e) {
                        guestError = e.getMessage();
                    }

                    try {
                        storagePools = pve.fetchNodeStorage(nodeName, creds);
                    } catch (ApiException e) {
                        storageError = e.getUserMessage();
                    } catch (Exception e) {
                        storageError = e.getMessage();
                    }
                }
            }

        } catch (ApiException e) {
            isOffline = true;
            if (status == null) {
                guestError = e.getUserMessage();
                storageError = e.getUserMessage();
                status = (cfg != null) ? NodeStatus.offline(nodeName, cfg.id) : null;
            }
        } catch (Exception e) {
            isOffline = true;
            if (status == null) {
                guestError = e.getMessage();
                storageError = e.getMessage();
                status = (cfg != null) ? NodeStatus.offline(nodeName, cfg.id) : null;
            }
        }

        return new NodeDataResult(status, storagePools, storageError, guests, guestError, isOffline);
    }

    public void applyPresentation(NodeDetailPresentationState pres) {
        if (pres == null || !pres.decision.accepted) return;
        if (pres.metricsState != null) {
            if (pres.metricsState.node != null) {
                cachedNode = pres.metricsState.node;
            }
            isNodeMetricsStale = pres.metricsState.isStale;
            applyNodeMetrics(pres.metricsState);
        }
        if (pres.storageState != null) {
            applyStorageState(pres.storageState);
        }
        if (pres.guestsState != null) {
            applyGuestsState(pres.guestsState);
        }
        if (btnShell != null) {
            btnShell.setEnabled(pres.shellEnabled);
        }
        if (viewBindingListener != null) {
            viewBindingListener.onShellControlBound(pres.shellEnabled);
        }
    }

    class LoadNodeTask extends AsyncTask<Void, Void, NodeDataResult> {
        private final AutoRefreshScheduler.RefreshCallback callback;
        private final String serverSnapshot;

        LoadNodeTask(AutoRefreshScheduler.RefreshCallback callback) {
            this.callback = callback;
            this.serverSnapshot = HomelabApp.getInstance().getServerConfigStore().getConfigFingerprint();
        }

        LoadNodeTask() {
            this(null);
        }

        @Override
        protected NodeDataResult doInBackground(Void... voids) {
            if (isCancelled()) {
                return null;
            }
            try {
                ServerConfig cfg = HomelabApp.getInstance().getServerConfigStore().findById(serverId);
                if (cfg == null || cfg.isLegacyDemo()) {
                    return new NodeDataResult(null, new ArrayList<>(), "Server not found", new ArrayList<>(), "Server not found", true);
                }
                AuthCredentials creds = HomelabApp.getInstance().getSessionManager().getCredentials(cfg);
                if (creds == null) {
                    return new NodeDataResult(null, new ArrayList<>(), "Authentication required", new ArrayList<>(), "Authentication required", false);
                }
                NodeDataResult result = loadNodeData(cfg, creds, nodeName, serverId);
                if (isCancelled()) {
                    return null;
                }
                return result;
            } catch (Exception e) {
                return new NodeDataResult(null, new ArrayList<>(), e.getMessage(), new ArrayList<>(), e.getMessage(), true);
            }
        }

        @Override
        protected void onPostExecute(NodeDataResult res) {
            if (currentLoadTask == this) {
                currentLoadTask = null;
            }

            boolean resumed = !isFinishing() && !isDestroyed() && isActivityResumed;
            boolean locked = HomelabApp.getInstance().getSessionManager().isLocked();
            boolean actionRunning = ActionGuard.getInstance().isRunning(nodeName);
            ServerConfig currentCfg = HomelabApp.getInstance().getServerConfigStore().findById(serverId);
            boolean configChanged = (currentCfg == null || !serverSnapshot.equals(HomelabApp.getInstance().getServerConfigStore().getConfigFingerprint()));
            boolean canAccept = (callback == null || callback.canAcceptResult());

            NodeDetailPresentationState pres = completeAndBind(
                    resumed, locked, actionRunning, configChanged, canAccept, res, server,
                    cachedNode, cachedStoragePools, cachedGuests, callback, scheduler);

            if (!pres.decision.accepted) {
                return;
            }

            applyPresentation(pres);
        }

        @Override
        protected void onCancelled(NodeDataResult res) {
            if (currentLoadTask == this) {
                currentLoadTask = null;
            }
            if (callback != null) {
                callback.onError(new IllegalStateException("Task cancelled"), "Cancelled");
            }
        }

        @Override
        protected void onCancelled() {
            onCancelled(null);
        }
    }
}
