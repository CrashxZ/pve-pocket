// SPDX-License-Identifier: GPL-3.0-or-later
// PVE Pocket — Node Detail Activity with Storage Pools and Hosted Guests
package com.homelabpocket.ui.nodes;

import android.app.Activity;
import android.content.Intent;
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
import com.homelabpocket.api.ApiException;
import com.homelabpocket.api.PdmApiDriver;
import com.homelabpocket.api.PveApiDriver;
import com.homelabpocket.model.AuthCredentials;
import com.homelabpocket.model.GuestInfo;
import com.homelabpocket.model.NodeStatus;
import com.homelabpocket.model.ServerConfig;
import com.homelabpocket.model.StoragePool;
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

    private LinearLayout layoutDetailStorage;
    private LinearLayout layoutDetailGuests;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_node_detail);

        nodeName = getIntent().getStringExtra(EXTRA_NODE);
        serverId = getIntent().getStringExtra(EXTRA_SERVER_ID);

        initViews();
        loadData();
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
    }

    private void setStorageLoading() {
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

    private void setGuestsLoading() {
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
        new LoadNodeTask().execute();
    }

    private void bindNode(NodeStatus n) {
        int cpuPct = (int) Math.round(!Double.isNaN(n.cpuUsage) ? n.cpuUsage * 100 : 0);
        tvCpu.setText("CPU: " + cpuPct + "% (" + n.cpuCount + " cores)");
        progCpu.setProgress(cpuPct);

        long ramPct = n.memTotal > 0 ? (n.memUsed * 100 / n.memTotal) : 0;
        tvRam.setText("RAM: " + JsonUtil.formatBytes(n.memUsed) + " / " + JsonUtil.formatBytes(n.memTotal));
        progRam.setProgress((int) ramPct);

        boolean isPdm = com.homelabpocket.ui.UiPresentationPolicy.isPdm(server);
        tvDisk.setText(com.homelabpocket.ui.UiPresentationPolicy.formatNodeRootFs(n, isPdm));
        if (progDisk != null) {
            boolean showBar = com.homelabpocket.ui.UiPresentationPolicy.isRootFsBarVisible(n);
            progDisk.setVisibility(showBar ? View.VISIBLE : View.GONE);
            if (showBar) {
                int diskPct = (int) ((n.diskUsed * 100L) / n.diskTotal);
                progDisk.setProgress(diskPct);
            }
        }

        tvUptime.setText("Uptime: " + JsonUtil.formatUptime(n.uptime));
        tvKernel.setText("Release: " + (n.pveVersion != null ? n.pveVersion : "Proxmox VE"));
    }

    public void bindStorage(List<StoragePool> pools, String error) {
        if (layoutDetailStorage == null) return;
        layoutDetailStorage.removeAllViews();

        if (error != null) {
            TextView tvError = new TextView(this);
            tvError.setText("Storage Pools: " + error);
            tvError.setTextColor(getResources().getColor(R.color.matrix_warning));
            tvError.setTextSize(12);
            layoutDetailStorage.addView(tvError);
            return;
        }

        if (pools == null || pools.isEmpty()) {
            TextView tvEmpty = new TextView(this);
            tvEmpty.setText("No storage pools configured on this host.");
            tvEmpty.setTextColor(getResources().getColor(R.color.matrix_text_muted));
            tvEmpty.setTextSize(12);
            layoutDetailStorage.addView(tvEmpty);
            return;
        }

        for (StoragePool pool : pools) {
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
        layoutDetailGuests.removeAllViews();

        if (isOffline) {
            TextView tvOffline = new TextView(this);
            tvOffline.setText("Host is offline or unreachable. Hosted guest instances cannot be inspected.");
            tvOffline.setTextColor(getResources().getColor(R.color.matrix_warning));
            tvOffline.setTextSize(12);
            layoutDetailGuests.addView(tvOffline);
            return;
        }

        if (error != null) {
            TextView tvErr = new TextView(this);
            String msg;
            if (error.contains("403") || error.toLowerCase().contains("permission")) {
                msg = "Permission denied (403): User lacks VM/LXC privileges on this host.";
            } else if (error.contains("401") || error.toLowerCase().contains("authentication")) {
                msg = "Authentication failed (401): Session ticket expired or credentials invalid.";
            } else {
                msg = "Failed to load guest instances: " + error;
            }
            tvErr.setText(msg);
            tvErr.setTextColor(getResources().getColor(R.color.matrix_warning));
            tvErr.setTextSize(12);
            layoutDetailGuests.addView(tvErr);
            return;
        }

        if (guests == null || guests.isEmpty()) {
            TextView tvEmpty = new TextView(this);
            tvEmpty.setText("No hosted guest instances (VMs or containers) found on this node.");
            tvEmpty.setTextColor(getResources().getColor(R.color.matrix_text_muted));
            tvEmpty.setTextSize(12);
            layoutDetailGuests.addView(tvEmpty);
            return;
        }

        LayoutInflater inflater = LayoutInflater.from(this);
        for (GuestInfo g : guests) {
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

    private class LoadNodeTask extends AsyncTask<Void, Void, NodeDataResult> {
        @Override
        protected NodeDataResult doInBackground(Void... voids) {
            NodeStatus status = null;
            List<StoragePool> storagePools = new ArrayList<>();
            String storageError = null;
            List<GuestInfo> guests = new ArrayList<>();
            String guestError = null;
            boolean isOffline = false;

            try {
                ServerConfig cfg = HomelabApp.getInstance().getServerConfigStore().findById(serverId);
                if (cfg == null || cfg.isLegacyDemo()) {
                    return new NodeDataResult(null, storagePools, "Server not found", guests, "Server not found", true);
                }
                AuthCredentials creds = HomelabApp.getInstance().getSessionManager().getCredentials(cfg);
                if (creds == null) {
                    return new NodeDataResult(null, storagePools, "Authentication required", guests, "Authentication required", false);
                }

                if (cfg.type == ServerConfig.ServerType.PDM) {
                    PdmApiDriver pdm = new PdmApiDriver(cfg, HomelabApp.getInstance().getSessionManager());
                    List<NodeStatus> allNodes = pdm.fetchAllNodes(creds);
                    for (NodeStatus n : allNodes) {
                        if (nodeName != null && nodeName.equals(n.node)) {
                            status = n;
                            break;
                        }
                    }
                    if (status != null && status.status == NodeStatus.Status.OFFLINE) {
                        isOffline = true;
                    }

                    try {
                        guests = pdm.fetchNodeGuests(nodeName, creds);
                    } catch (ApiException e) {
                        guestError = e.getUserMessage();
                    } catch (Exception e) {
                        guestError = e.getMessage();
                    }

                    try {
                        storagePools = pdm.fetchNodeStorage(nodeName, creds);
                        storagePools = pdm.enrichStoragePools(nodeName, storagePools, creds);
                    } catch (ApiException e) {
                        storageError = e.getUserMessage();
                    } catch (Exception e) {
                        storageError = e.getMessage();
                    }

                } else {
                    PveApiDriver pve = new PveApiDriver(cfg, HomelabApp.getInstance().getSessionManager());
                    status = pve.fetchNodeDetail(nodeName, creds);

                    if (status != null && status.status == NodeStatus.Status.OFFLINE) {
                        isOffline = true;
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
                if (status == null) {
                    guestError = e.getUserMessage();
                    storageError = e.getUserMessage();
                }
            } catch (Exception e) {
                if (status == null) {
                    guestError = e.getMessage();
                    storageError = e.getMessage();
                }
            }

            return new NodeDataResult(status, storagePools, storageError, guests, guestError, isOffline);
        }

        @Override
        protected void onPostExecute(NodeDataResult res) {
            if (res.node != null) {
                bindNode(res.node);
            }
            bindStorage(res.storagePools, res.storageError);
            bindGuests(res.guests, res.guestError, res.isOffline);
        }
    }
}
