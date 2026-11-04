// SPDX-License-Identifier: GPL-3.0-or-later
// Homelab Pocket — Host Nodes Fleet View
package com.homelabpocket.ui.nodes;

import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.homelabpocket.HomelabApp;
import com.homelabpocket.R;
import com.homelabpocket.model.NodeStatus;
import com.homelabpocket.model.ServerConfig;
import com.homelabpocket.ui.MainActivity;
import com.homelabpocket.ui.terminal.TerminalActivity;
import com.homelabpocket.util.JsonUtil;

import java.util.List;

public class NodesView {

    private final MainActivity activity;
    private final View root;

    private final Button btnRefresh;
    private final TextView tvError;
    private final LinearLayout layoutNodes;
    private final TextView tvOfflineHeader;
    private final LinearLayout layoutOfflineNodes;

    public NodesView(MainActivity activity) {
        this.activity = activity;

        // Inflate a clean host node container
        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundColor(activity.getResources().getColor(R.color.matrix_obsidian));

        // Toolbar
        LinearLayout toolbar = new LinearLayout(activity);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setBackgroundColor(activity.getResources().getColor(R.color.matrix_surface));
        toolbar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 56 * (int) activity.getResources().getDisplayMetrics().density));
        toolbar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        toolbar.setPadding(16 * (int) activity.getResources().getDisplayMetrics().density, 0,
                8 * (int) activity.getResources().getDisplayMetrics().density, 0);

        TextView tvTitle = new TextView(activity);
        tvTitle.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        tvTitle.setText(R.string.nav_nodes);
        tvTitle.setTextSize(18);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setTextColor(activity.getResources().getColor(R.color.matrix_text_primary));
        toolbar.addView(tvTitle);

        btnRefresh = new Button(activity);
        btnRefresh.setText(R.string.refresh);
        btnRefresh.setTextColor(activity.getResources().getColor(R.color.matrix_phosphor));
        btnRefresh.setBackgroundResource(android.R.drawable.btn_default);
        btnRefresh.setTextSize(12);
        btnRefresh.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        toolbar.addView(btnRefresh);

        container.addView(toolbar);

        View divider = new View(activity);
        divider.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2));
        divider.setBackgroundColor(activity.getResources().getColor(R.color.matrix_surface_border));
        container.addView(divider);

        tvError = new TextView(activity);
        tvError.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        tvError.setBackgroundColor(activity.getResources().getColor(R.color.matrix_danger_dim));
        tvError.setTextColor(activity.getResources().getColor(R.color.matrix_danger));
        tvError.setPadding(20, 20, 20, 20);
        tvError.setVisibility(View.GONE);
        container.addView(tvError);

        ScrollView scrollView = new ScrollView(activity);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));
        scrollView.setFillViewport(true);

        LinearLayout scrollContent = new LinearLayout(activity);
        scrollContent.setOrientation(LinearLayout.VERTICAL);
        scrollContent.setPadding(16, 16, 16, 16);

        layoutNodes = new LinearLayout(activity);
        layoutNodes.setOrientation(LinearLayout.VERTICAL);
        scrollContent.addView(layoutNodes);

        tvOfflineHeader = new TextView(activity);
        tvOfflineHeader.setText("OFFLINE HOSTS (STANDBY)");
        tvOfflineHeader.setTextSize(12);
        tvOfflineHeader.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        tvOfflineHeader.setTextColor(activity.getResources().getColor(R.color.matrix_amber));
        tvOfflineHeader.setPadding(0, 24, 0, 8);
        tvOfflineHeader.setVisibility(View.GONE);
        scrollContent.addView(tvOfflineHeader);

        layoutOfflineNodes = new LinearLayout(activity);
        layoutOfflineNodes.setOrientation(LinearLayout.VERTICAL);
        layoutOfflineNodes.setVisibility(View.GONE);
        scrollContent.addView(layoutOfflineNodes);

        scrollView.addView(scrollContent);
        container.addView(scrollView);

        this.root = container;
        btnRefresh.setOnClickListener(v -> activity.refreshData());
    }

    public View getView() {
        return root;
    }

    public void render(List<NodeStatus> nodes, boolean isLoading, String error) {
        if (error != null && !error.isEmpty()) {
            tvError.setVisibility(View.VISIBLE);
            tvError.setText(error);
        } else {
            tvError.setVisibility(View.GONE);
        }

        layoutNodes.removeAllViews();
        layoutOfflineNodes.removeAllViews();

        int offlineCount = 0;
        if (nodes != null) {
            for (NodeStatus n : nodes) {
                View card = LayoutInflater.from(activity).inflate(R.layout.item_node, null);
                populateNodeCard(card, n);

                if (n.isOnline()) {
                    layoutNodes.addView(card);
                } else {
                    offlineCount++;
                    layoutOfflineNodes.addView(card);
                }
            }
        }

        if (offlineCount > 0) {
            tvOfflineHeader.setVisibility(View.VISIBLE);
            layoutOfflineNodes.setVisibility(View.VISIBLE);
        } else {
            tvOfflineHeader.setVisibility(View.GONE);
            layoutOfflineNodes.setVisibility(View.GONE);
        }
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

            ServerConfig nodeServer = null;
            try {
                if (HomelabApp.getInstance() != null && HomelabApp.getInstance().getServerConfigStore() != null) {
                    nodeServer = HomelabApp.getInstance().getServerConfigStore().findById(n.serverId);
                }
            } catch (Throwable ignored) {}

            if (nodeServer != null && nodeServer.type == ServerConfig.ServerType.PDM) {
                btnShell.setText("No Shell (PDM)");
                btnShell.setEnabled(false);
                btnShell.setAlpha(0.4f);
            } else {
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
            }

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
