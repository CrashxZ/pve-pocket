// SPDX-License-Identifier: GPL-3.0-or-later
// Homelab Pocket — Dashboard View (Fleet Overview & Unified Hosts Section)
package com.homelabpocket.ui.dashboard;

import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.homelabpocket.HomelabApp;
import com.homelabpocket.R;
import com.homelabpocket.model.GuestInfo;
import com.homelabpocket.model.NodeStatus;
import com.homelabpocket.model.ServerConfig;
import com.homelabpocket.ui.MainActivity;
import com.homelabpocket.ui.UiPresentationPolicy;
import com.homelabpocket.ui.nodes.NodeDetailActivity;
import com.homelabpocket.ui.settings.AddServerActivity;
import com.homelabpocket.ui.terminal.TerminalActivity;
import com.homelabpocket.ui.widget.RadialGaugeView;
import com.homelabpocket.util.JsonUtil;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class DashboardView {

    private final MainActivity activity;
    private final View root;

    private final Button btnRefresh;
    private final TextView tvError;
    private final LinearLayout layoutEmpty;
    private final Button btnEmptyAddServer;

    private final LinearLayout layoutFleetContent;
    private final RadialGaugeView gaugeFleetCpu;
    private final TextView tvFleetCpuCapacity;
    private final RadialGaugeView gaugeFleetRam;
    private final TextView tvFleetRamCapacity;
    private final TextView tvGuestCounts;

    private final TextView tvHostsHeader;
    private final LinearLayout layoutHosts;
    private final TextView tvLastUpdated;

    public DashboardView(MainActivity activity) {
        this.activity = activity;
        this.root = LayoutInflater.from(activity).inflate(R.layout.fragment_dashboard, null);

        btnRefresh = root.findViewById(R.id.btn_refresh);
        tvError = root.findViewById(R.id.tv_error);
        layoutEmpty = root.findViewById(R.id.layout_empty);
        btnEmptyAddServer = root.findViewById(R.id.btn_empty_add_server);

        layoutFleetContent = root.findViewById(R.id.layout_fleet_content);
        gaugeFleetCpu = root.findViewById(R.id.gauge_fleet_cpu);
        if (gaugeFleetCpu != null) {
            gaugeFleetCpu.setProgressColor(activity.getResources().getColor(R.color.matrix_phosphor));
            gaugeFleetCpu.setTrackColor(0xFF14261C);
        }
        tvFleetCpuCapacity = root.findViewById(R.id.tv_fleet_cpu_capacity);

        gaugeFleetRam = root.findViewById(R.id.gauge_fleet_ram);
        if (gaugeFleetRam != null) {
            gaugeFleetRam.setProgressColor(activity.getResources().getColor(R.color.matrix_cyan));
            gaugeFleetRam.setTrackColor(0xFF0F282E);
        }
        tvFleetRamCapacity = root.findViewById(R.id.tv_fleet_ram_capacity);
        tvGuestCounts = root.findViewById(R.id.tv_guest_counts);

        tvHostsHeader = root.findViewById(R.id.tv_hosts_header);
        layoutHosts = root.findViewById(R.id.layout_hosts);
        tvLastUpdated = root.findViewById(R.id.tv_last_updated);

        btnRefresh.setOnClickListener(v -> activity.refreshData());
        btnEmptyAddServer.setOnClickListener(v -> {
            Intent intent = new Intent(activity, AddServerActivity.class);
            activity.startActivityForResult(intent, MainActivity.REQ_ADD_SERVER);
        });
    }

    public View getView() {
        return root;
    }

    public void render(List<NodeStatus> nodes, List<GuestInfo> guests, boolean isLoading, String error) {
        HomelabApp app = HomelabApp.getInstance();
        boolean hasServers = !app.getServerConfigStore().loadAll().isEmpty();

        // Error message
        if (error != null && !error.isEmpty()) {
            tvError.setVisibility(View.VISIBLE);
            tvError.setText(error);
        } else {
            tvError.setVisibility(View.GONE);
        }

        // Check if first-run empty state
        if (!hasServers) {
            layoutEmpty.setVisibility(View.VISIBLE);
            layoutFleetContent.setVisibility(View.GONE);
            return;
        }

        layoutEmpty.setVisibility(View.GONE);
        layoutFleetContent.setVisibility(View.VISIBLE);

        // Calculate Fleet Telemetry via central presentation policy
        UiPresentationPolicy.FleetCapacitySummary summary =
                UiPresentationPolicy.calculateFleetCapacity(nodes, guests);

        // Bind CPU Radial Gauge
        if (gaugeFleetCpu != null) {
            gaugeFleetCpu.setValue(summary.cpuPercent, summary.cpuCenterText, summary.isCpuAvailable);
            gaugeFleetCpu.setContentDescription(summary.cpuContentDescription);
        }
        if (tvFleetCpuCapacity != null) {
            tvFleetCpuCapacity.setText(summary.cpuCapacityText);
        }

        // Bind RAM Radial Gauge
        if (gaugeFleetRam != null) {
            gaugeFleetRam.setValue(summary.ramPercent, summary.ramCenterText, summary.isRamAvailable);
            gaugeFleetRam.setContentDescription(summary.ramContentDescription);
        }
        if (tvFleetRamCapacity != null) {
            tvFleetRamCapacity.setText(summary.ramCapacityText);
        }

        // Bind Compact Guest Summary below gauges
        if (tvGuestCounts != null) {
            tvGuestCounts.setText(summary.guestSummaryText);
            tvGuestCounts.setContentDescription(summary.guestContentDescription);
        }

        // Bind Unified Hosts Header (e.g. "HOSTS (2/2 online)")
        if (tvHostsHeader != null) {
            tvHostsHeader.setText(summary.hostsHeaderTitle);
        }

        // Populate Unified Hosts List (Offline hosts remain visible)
        if (layoutHosts != null) {
            layoutHosts.removeAllViews();
            if (nodes != null) {
                for (NodeStatus n : nodes) {
                    View hostRow = LayoutInflater.from(activity).inflate(R.layout.item_host_row, null);
                    bindHostRow(hostRow, n);
                    layoutHosts.addView(hostRow);
                }
            }
        }

        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        tvLastUpdated.setText(activity.getString(R.string.last_updated, sdf.format(new Date())));
    }

    /**
     * Binds a compact host row in the unified Hosts section of Overview.
     */
    public void bindHostRow(View row, NodeStatus n) {
        TextView tvName = row.findViewById(R.id.tv_node_name);
        TextView tvRemote = row.findViewById(R.id.tv_node_remote);
        TextView tvStatus = row.findViewById(R.id.tv_node_status);
        TextView tvMetrics = row.findViewById(R.id.tv_node_metrics);
        TextView tvPdmBadge = row.findViewById(R.id.tv_node_pdm_badge);
        Button btnShell = row.findViewById(R.id.btn_node_shell);

        tvName.setText(n.getDisplayName());

        // Separate remote label preserving full internal identity
        if (n.hasSeparateRemote()) {
            tvRemote.setVisibility(View.VISIBLE);
            tvRemote.setText("[Remote: " + n.getRemoteName() + "]");
        } else {
            tvRemote.setVisibility(View.GONE);
        }

        ServerConfig nodeServer = null;
        try {
            if (HomelabApp.getInstance() != null && HomelabApp.getInstance().getServerConfigStore() != null) {
                nodeServer = HomelabApp.getInstance().getServerConfigStore().findById(n.serverId);
            }
        } catch (Throwable ignored) {}

        boolean isPdm = UiPresentationPolicy.isPdm(nodeServer);

        // PDM shell hide & monitoring badge binding evaluated regardless of online/offline
        if (isPdm) {
            if (btnShell != null) {
                btnShell.setVisibility(View.GONE);
            }
            if (tvPdmBadge != null) {
                tvPdmBadge.setVisibility(View.VISIBLE);
            }
        } else {
            if (tvPdmBadge != null) {
                tvPdmBadge.setVisibility(View.GONE);
            }
            if (btnShell != null) {
                if (n.isOnline()) {
                    btnShell.setVisibility(View.VISIBLE);
                    btnShell.setText("Shell");
                    btnShell.setEnabled(true);
                    btnShell.setAlpha(1.0f);
                    btnShell.setOnClickListener(v -> {
                        Intent intent = new Intent(activity, TerminalActivity.class);
                        intent.putExtra(TerminalActivity.EXTRA_NODE, n.node);
                        intent.putExtra(TerminalActivity.EXTRA_SERVER_ID, n.serverId);
                        intent.putExtra(TerminalActivity.EXTRA_TYPE, "NODE");
                        intent.putExtra(TerminalActivity.EXTRA_TITLE, n.node + " (Host Shell)");
                        activity.startActivity(intent);
                    });
                } else {
                    btnShell.setVisibility(View.GONE);
                }
            }
        }

        if (n.isOnline()) {
            tvStatus.setText("ONLINE");
            tvStatus.setBackgroundResource(R.drawable.bg_chip_running);
            tvStatus.setTextColor(activity.getResources().getColor(R.color.matrix_phosphor));
            tvMetrics.setText(UiPresentationPolicy.formatHostRowMetrics(n));
            tvMetrics.setTextColor(activity.getResources().getColor(R.color.matrix_text_secondary));
        } else {
            tvStatus.setText("OFFLINE");
            tvStatus.setBackgroundResource(R.drawable.bg_chip_stopped);
            tvStatus.setTextColor(activity.getResources().getColor(R.color.matrix_amber));
            tvMetrics.setText(UiPresentationPolicy.formatHostRowMetrics(n));
            tvMetrics.setTextColor(activity.getResources().getColor(R.color.matrix_amber));
        }

        // Accessibility content description with actual percent & values
        row.setContentDescription(UiPresentationPolicy.formatHostRowContentDescription(n));

        // Card navigation preserves full composite internal identity for intent routing
        row.setOnClickListener(v -> {
            Intent intent = new Intent(activity, NodeDetailActivity.class);
            intent.putExtra(NodeDetailActivity.EXTRA_NODE, n.node);
            intent.putExtra(NodeDetailActivity.EXTRA_SERVER_ID, n.serverId);
            activity.startActivity(intent);
        });
    }

    /**
     * Backward-compatible binding method for item_node layout.
     */
    public void bindNode(View card, NodeStatus n) {
        TextView tvName = card.findViewById(R.id.tv_node_name);
        TextView tvStatus = card.findViewById(R.id.tv_node_status);
        TextView tvMeta = card.findViewById(R.id.tv_node_meta);
        TextView tvOfflineMsg = card.findViewById(R.id.tv_offline_msg);
        View layoutMetrics = card.findViewById(R.id.layout_metrics);
        TextView tvCpuVal = card.findViewById(R.id.tv_cpu_val);
        android.widget.ProgressBar progCpu = card.findViewById(R.id.progress_cpu);
        TextView tvRamVal = card.findViewById(R.id.tv_ram_val);
        android.widget.ProgressBar progRam = card.findViewById(R.id.progress_ram);
        TextView tvDisk = card.findViewById(R.id.tv_node_disk);
        TextView tvNet = card.findViewById(R.id.tv_node_net);
        TextView tvPdmBadge = card.findViewById(R.id.tv_node_pdm_badge);
        Button btnShell = card.findViewById(R.id.btn_node_shell);

        if (tvName != null) tvName.setText(n.getDisplayName());
        if (tvMeta != null) tvMeta.setText(UiPresentationPolicy.formatNodeMeta(n));

        ServerConfig nodeServer = null;
        try {
            if (HomelabApp.getInstance() != null && HomelabApp.getInstance().getServerConfigStore() != null) {
                nodeServer = HomelabApp.getInstance().getServerConfigStore().findById(n.serverId);
            }
        } catch (Throwable ignored) {}

        boolean isPdm = UiPresentationPolicy.isPdm(nodeServer);

        if (isPdm) {
            if (btnShell != null) btnShell.setVisibility(View.GONE);
            if (tvPdmBadge != null) tvPdmBadge.setVisibility(View.VISIBLE);
        } else {
            if (tvPdmBadge != null) tvPdmBadge.setVisibility(View.GONE);
            if (btnShell != null) {
                if (n.isOnline()) {
                    btnShell.setVisibility(View.VISIBLE);
                    btnShell.setText("Shell");
                    btnShell.setEnabled(true);
                    btnShell.setAlpha(1.0f);
                    btnShell.setOnClickListener(v -> {
                        Intent intent = new Intent(activity, TerminalActivity.class);
                        intent.putExtra(TerminalActivity.EXTRA_NODE, n.node);
                        intent.putExtra(TerminalActivity.EXTRA_SERVER_ID, n.serverId);
                        intent.putExtra(TerminalActivity.EXTRA_TYPE, "NODE");
                        intent.putExtra(TerminalActivity.EXTRA_TITLE, n.node + " (Host Shell)");
                        activity.startActivity(intent);
                    });
                } else {
                    btnShell.setVisibility(View.GONE);
                }
            }
        }

        if (n.isOnline()) {
            if (tvStatus != null) {
                tvStatus.setText("ONLINE");
                tvStatus.setBackgroundResource(R.drawable.bg_chip_running);
                tvStatus.setTextColor(activity.getResources().getColor(R.color.matrix_phosphor));
            }
            if (tvOfflineMsg != null) tvOfflineMsg.setVisibility(View.GONE);
            if (layoutMetrics != null) layoutMetrics.setVisibility(View.VISIBLE);

            int cpuPct = (int) Math.round(!Double.isNaN(n.cpuUsage) ? n.cpuUsage * 100 : 0);
            if (tvCpuVal != null) tvCpuVal.setText(cpuPct + "% (" + n.cpuCount + " cores)");
            if (progCpu != null) progCpu.setProgress(cpuPct);

            long ramPct = n.memTotal > 0 ? (n.memUsed * 100 / n.memTotal) : 0;
            if (tvRamVal != null) {
                tvRamVal.setText(JsonUtil.formatBytes(n.memUsed) + " / " + JsonUtil.formatBytes(n.memTotal));
            }
            if (progRam != null) progRam.setProgress((int) ramPct);

            if (tvDisk != null) tvDisk.setText(UiPresentationPolicy.formatHostDisk(n, isPdm));
            if (tvNet != null) tvNet.setText(UiPresentationPolicy.formatHostNet(n, isPdm));
        } else {
            if (tvStatus != null) {
                tvStatus.setText("OFFLINE");
                tvStatus.setBackgroundResource(R.drawable.bg_chip_stopped);
                tvStatus.setTextColor(activity.getResources().getColor(R.color.matrix_amber));
            }
            if (tvOfflineMsg != null) tvOfflineMsg.setVisibility(View.VISIBLE);
            if (layoutMetrics != null) layoutMetrics.setVisibility(View.GONE);
        }

        card.setOnClickListener(v -> {
            Intent intent = new Intent(activity, NodeDetailActivity.class);
            intent.putExtra(NodeDetailActivity.EXTRA_NODE, n.node);
            intent.putExtra(NodeDetailActivity.EXTRA_SERVER_ID, n.serverId);
            activity.startActivity(intent);
        });
    }
}
