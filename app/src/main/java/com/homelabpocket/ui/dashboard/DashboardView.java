// SPDX-License-Identifier: GPL-3.0-or-later
// Homelab Pocket — Dashboard View (Fleet Overview & Signature Topology Strip)
package com.homelabpocket.ui.dashboard;

import android.content.Intent;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.homelabpocket.HomelabApp;
import com.homelabpocket.R;
import com.homelabpocket.model.GuestInfo;
import com.homelabpocket.model.NodeStatus;
import com.homelabpocket.ui.MainActivity;
import com.homelabpocket.ui.nodes.NodeDetailActivity;
import com.homelabpocket.ui.settings.AddServerActivity;
import com.homelabpocket.ui.terminal.TerminalActivity;
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
    private final TextView tvQuorumBadge;
    private final TextView tvFleetStatus;
    private final LinearLayout layoutTopologyBars;

    private final TextView tvFleetCpu;
    private final ProgressBar progressFleetCpu;
    private final TextView tvFleetRam;
    private final ProgressBar progressFleetRam;
    private final TextView tvNodeCounts;
    private final TextView tvGuestCounts;

    private final LinearLayout layoutNodes;
    private final TextView tvOfflineHeader;
    private final LinearLayout layoutOfflineNodes;
    private final TextView tvLastUpdated;

    public DashboardView(MainActivity activity) {
        this.activity = activity;
        this.root = LayoutInflater.from(activity).inflate(R.layout.fragment_dashboard, null);

        btnRefresh = root.findViewById(R.id.btn_refresh);
        tvError = root.findViewById(R.id.tv_error);
        layoutEmpty = root.findViewById(R.id.layout_empty);
        btnEmptyAddServer = root.findViewById(R.id.btn_empty_add_server);

        layoutFleetContent = root.findViewById(R.id.layout_fleet_content);
        tvQuorumBadge = root.findViewById(R.id.tv_quorum_badge);
        tvFleetStatus = root.findViewById(R.id.tv_fleet_status);
        layoutTopologyBars = root.findViewById(R.id.layout_topology_bars);

        tvFleetCpu = root.findViewById(R.id.tv_fleet_cpu);
        progressFleetCpu = root.findViewById(R.id.progress_fleet_cpu);
        tvFleetRam = root.findViewById(R.id.tv_fleet_ram);
        progressFleetRam = root.findViewById(R.id.progress_fleet_ram);
        tvNodeCounts = root.findViewById(R.id.tv_node_counts);
        tvGuestCounts = root.findViewById(R.id.tv_guest_counts);

        layoutNodes = root.findViewById(R.id.layout_nodes);
        tvOfflineHeader = root.findViewById(R.id.tv_offline_header);
        layoutOfflineNodes = root.findViewById(R.id.layout_offline_nodes);
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

        // Calculate Fleet Telemetry
        int onlineNodes = 0;
        int offlineNodes = 0;
        int totalCores = 0;
        double weightedCpuSum = 0;
        long totalRamUsed = 0;
        long totalRamMax = 0;

        for (NodeStatus n : nodes) {
            if (n.isOnline()) {
                onlineNodes++;
                int cores = n.cpuCount > 0 ? n.cpuCount : 1;
                totalCores += cores;
                double cpu = !Double.isNaN(n.cpuUsage) ? n.cpuUsage : 0.0;
                weightedCpuSum += (cpu * cores);

                if (n.memUsed > 0 && n.memTotal > 0) {
                    totalRamUsed += n.memUsed;
                    totalRamMax += n.memTotal;
                }
            } else {
                offlineNodes++;
            }
        }

        double fleetCpuAvg = totalCores > 0 ? (weightedCpuSum / totalCores) * 100.0 : 0.0;
        double fleetRamPct = totalRamMax > 0 ? ((double) totalRamUsed / totalRamMax) * 100.0 : 0.0;

        int runningGuests = 0;
        int stoppedGuests = 0;
        for (GuestInfo g : guests) {
            if (g.state == GuestInfo.RunState.RUNNING) {
                runningGuests++;
            } else {
                stoppedGuests++;
            }
        }

        // Render Original Signature Element: Fleet Topology & Health Strip
        int totalNodes = nodes.size();
        if (offlineNodes == 0 && totalNodes > 0) {
            tvQuorumBadge.setText(totalNodes == 1 ? activity.getString(R.string.topology_standalone) : activity.getString(R.string.topology_quorum_ok));
            tvQuorumBadge.setTextColor(activity.getResources().getColor(R.color.matrix_phosphor));
        } else if (onlineNodes > 0) {
            tvQuorumBadge.setText("● PARTIAL FLEET (" + onlineNodes + "/" + totalNodes + " ONLINE)");
            tvQuorumBadge.setTextColor(activity.getResources().getColor(R.color.matrix_cyan));
        } else if (totalNodes > 0) {
            tvQuorumBadge.setText("○ ALL HOSTS OFFLINE (" + totalNodes + ")");
            tvQuorumBadge.setTextColor(activity.getResources().getColor(R.color.matrix_amber));
        } else {
            tvQuorumBadge.setText("● STANDALONE");
            tvQuorumBadge.setTextColor(activity.getResources().getColor(R.color.matrix_cyan));
        }
        tvFleetStatus.setText(onlineNodes + "/" + totalNodes + " ONLINE" + (offlineNodes > 0 ? " (" + offlineNodes + " OFF)" : ""));

        // Build dynamic visual topology bar
        layoutTopologyBars.removeAllViews();
        for (NodeStatus n : nodes) {
            TextView barText = new TextView(activity);
            barText.setTextSize(11);
            barText.setTypeface(android.graphics.Typeface.MONOSPACE);
            if (n.isOnline()) {
                int cpuPct = (int) Math.round(!Double.isNaN(n.cpuUsage) ? n.cpuUsage * 100 : 0);
                String ramUsed = JsonUtil.formatBytes(n.memUsed);
                String ramTot = JsonUtil.formatBytes(n.memTotal);
                barText.setText("● " + n.node + "  [CPU: " + cpuPct + "% | RAM: " + ramUsed + " / " + ramTot + "]");
                barText.setTextColor(activity.getResources().getColor(R.color.matrix_text_primary));
            } else {
                barText.setText("○ " + n.node + "  [OFFLINE / STANDBY]");
                barText.setTextColor(activity.getResources().getColor(R.color.matrix_amber));
            }
            barText.setPadding(0, 4, 0, 4);
            layoutTopologyBars.addView(barText);
        }

        // Render Capacity Card
        tvFleetCpu.setText(String.format(Locale.US, "%.1f%% (%d vCPUs)", fleetCpuAvg, totalCores));
        progressFleetCpu.setProgress((int) Math.min(100, Math.round(fleetCpuAvg)));

        tvFleetRam.setText(String.format(Locale.US, "%s / %s (%.0f%%)",
                JsonUtil.formatBytes(totalRamUsed), JsonUtil.formatBytes(totalRamMax), fleetRamPct));
        progressFleetRam.setProgress((int) Math.min(100, Math.round(fleetRamPct)));

        tvNodeCounts.setText(onlineNodes + " online, " + offlineNodes + " off");
        tvGuestCounts.setText(runningGuests + " running, " + stoppedGuests + " stopped");

        // Render Online and Offline Nodes
        layoutNodes.removeAllViews();
        layoutOfflineNodes.removeAllViews();

        for (NodeStatus n : nodes) {
            View nodeCard = LayoutInflater.from(activity).inflate(R.layout.item_node, null);
            populateNodeCard(nodeCard, n);

            if (n.isOnline()) {
                layoutNodes.addView(nodeCard);
            } else {
                layoutOfflineNodes.addView(nodeCard);
            }
        }

        if (offlineNodes > 0) {
            tvOfflineHeader.setVisibility(View.VISIBLE);
            layoutOfflineNodes.setVisibility(View.VISIBLE);
        } else {
            tvOfflineHeader.setVisibility(View.GONE);
            layoutOfflineNodes.setVisibility(View.GONE);
        }

        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        tvLastUpdated.setText(activity.getString(R.string.last_updated, sdf.format(new Date())));
    }

    private void populateNodeCard(View card, NodeStatus n) {
        TextView tvName = card.findViewById(R.id.tv_node_name);
        TextView tvStatus = card.findViewById(R.id.tv_node_status);
        TextView tvMeta = card.findViewById(R.id.tv_node_meta);
        TextView tvOfflineMsg = card.findViewById(R.id.tv_offline_msg);
        View layoutMetrics = card.findViewById(R.id.layout_metrics);
        TextView tvCpuVal = card.findViewById(R.id.tv_cpu_val);
        ProgressBar progCpu = card.findViewById(R.id.progress_cpu);
        TextView tvRamVal = card.findViewById(R.id.tv_ram_val);
        ProgressBar progRam = card.findViewById(R.id.progress_ram);
        TextView tvDisk = card.findViewById(R.id.tv_node_disk);
        TextView tvNet = card.findViewById(R.id.tv_node_net);
        Button btnShell = card.findViewById(R.id.btn_node_shell);

        tvName.setText(n.node);

        if (n.isOnline()) {
            tvStatus.setText("ONLINE");
            tvStatus.setBackgroundResource(R.drawable.bg_chip_running);
            tvStatus.setTextColor(activity.getResources().getColor(R.color.matrix_phosphor));
            tvOfflineMsg.setVisibility(View.GONE);
            layoutMetrics.setVisibility(View.VISIBLE);

            String uptime = JsonUtil.formatUptime(n.uptime);
            String ver = n.pveVersion != null ? n.pveVersion : "PVE";
            tvMeta.setText(ver + " • Up " + uptime);

            int cpuPct = (int) Math.round(!Double.isNaN(n.cpuUsage) ? n.cpuUsage * 100 : 0);
            tvCpuVal.setText(cpuPct + "% (" + n.cpuCount + " cores)");
            progCpu.setProgress(cpuPct);

            long ramPct = n.memTotal > 0 ? (n.memUsed * 100 / n.memTotal) : 0;
            tvRamVal.setText(JsonUtil.formatBytes(n.memUsed) + " / " + JsonUtil.formatBytes(n.memTotal));
            progRam.setProgress((int) ramPct);

            tvDisk.setText("Disk: " + JsonUtil.formatBytes(n.diskUsed) + " / " + JsonUtil.formatBytes(n.diskTotal));
            tvNet.setText("Net: ↓" + JsonUtil.formatRate(n.netIn) + " ↑" + JsonUtil.formatRate(n.netOut));

            btnShell.setOnClickListener(v -> {
                Intent intent = new Intent(activity, TerminalActivity.class);
                intent.putExtra(TerminalActivity.EXTRA_NODE, n.node);
                intent.putExtra(TerminalActivity.EXTRA_SERVER_ID, n.serverId);
                intent.putExtra(TerminalActivity.EXTRA_TYPE, "NODE");
                intent.putExtra(TerminalActivity.EXTRA_TITLE, n.node + " (Host Shell)");
                activity.startActivity(intent);
            });

            card.setOnClickListener(v -> {
                Intent intent = new Intent(activity, NodeDetailActivity.class);
                intent.putExtra(NodeDetailActivity.EXTRA_NODE, n.node);
                intent.putExtra(NodeDetailActivity.EXTRA_SERVER_ID, n.serverId);
                activity.startActivity(intent);
            });
        } else {
            tvStatus.setText("OFFLINE");
            tvStatus.setBackgroundResource(R.drawable.bg_chip_stopped);
            tvStatus.setTextColor(activity.getResources().getColor(R.color.matrix_amber));
            tvMeta.setText("Unreachable / Intentionally powered down");
            tvOfflineMsg.setVisibility(View.VISIBLE);
            layoutMetrics.setVisibility(View.GONE);
        }
    }
}
