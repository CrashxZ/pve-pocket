// SPDX-License-Identifier: GPL-3.0-or-later
// Homelab Pocket — Main Matrix Operations Activity
package com.homelabpocket.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.homelabpocket.HomelabApp;
import com.homelabpocket.R;
import com.homelabpocket.api.ApiException;
import com.homelabpocket.api.PdmApiDriver;
import com.homelabpocket.api.PveApiDriver;
import com.homelabpocket.model.AuthCredentials;
import com.homelabpocket.model.GuestInfo;
import com.homelabpocket.model.NodeStatus;
import com.homelabpocket.model.ServerConfig;
import com.homelabpocket.ui.dashboard.DashboardView;
import com.homelabpocket.ui.guests.GuestsView;
import com.homelabpocket.ui.nodes.NodesView;
import com.homelabpocket.ui.settings.AddServerActivity;
import com.homelabpocket.ui.settings.SettingsView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Main Activity for Homelab Pocket.
 * Orchestrates navigation across the four primary operation views:
 * Dashboard (Fleet Overview + Topology Strip), Nodes (Hosts), Guests (VMs/LXCs), and Settings.
 */
public class MainActivity extends Activity {

    public static final int REQ_ADD_SERVER = 1001;
    public static final int REQ_CONFIRM_CREDENTIAL = 1002;

    private FrameLayout contentFrame;
    private Button btnNavDashboard;
    private Button btnNavNodes;
    private Button btnNavGuests;
    private Button btnNavSettings;

    private DashboardView dashboardView;
    private NodesView nodesView;
    private GuestsView guestsView;
    private SettingsView settingsView;

    private int currentTab = 0; // 0=Dashboard, 1=Nodes, 2=Guests, 3=Settings

    // Cached state across views
    private List<NodeStatus> cachedNodes = new ArrayList<>();
    private List<GuestInfo> cachedGuests = new ArrayList<>();
    private boolean isLoading = false;
    private String lastError = null;

    // In-flight action guard to prevent double submissions
    private final Set<String> inFlightActions = Collections.synchronizedSet(new HashSet<>());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        contentFrame = findViewById(R.id.content_frame);

        btnNavDashboard = findViewById(R.id.nav_dashboard);
        btnNavNodes = findViewById(R.id.nav_nodes);
        btnNavGuests = findViewById(R.id.nav_guests);
        btnNavSettings = findViewById(R.id.nav_settings);

        dashboardView = new DashboardView(this);
        nodesView = new NodesView(this);
        guestsView = new GuestsView(this);
        settingsView = new SettingsView(this);

        setupNavigation();
        selectTab(0);
        refreshData();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // If coming back from AddServerActivity, refresh
        if (cachedNodes.isEmpty() && !isLoading) {
            refreshData();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (guestsView != null) {
            guestsView.cancelActiveTasks();
        }
    }

    private void setupNavigation() {
        btnNavDashboard.setOnClickListener(v -> selectTab(0));
        btnNavNodes.setOnClickListener(v -> selectTab(1));
        btnNavGuests.setOnClickListener(v -> selectTab(2));
        btnNavSettings.setOnClickListener(v -> selectTab(3));
    }

    public void selectTab(int tab) {
        currentTab = tab;
        contentFrame.removeAllViews();

        int colorActive = getResources().getColor(R.color.matrix_phosphor);
        int colorInactive = getResources().getColor(R.color.matrix_text_secondary);

        btnNavDashboard.setTextColor(tab == 0 ? colorActive : colorInactive);
        btnNavNodes.setTextColor(tab == 1 ? colorActive : colorInactive);
        btnNavGuests.setTextColor(tab == 2 ? colorActive : colorInactive);
        btnNavSettings.setTextColor(tab == 3 ? colorActive : colorInactive);

        switch (tab) {
            case 0:
                contentFrame.addView(dashboardView.getView());
                dashboardView.render(cachedNodes, cachedGuests, isLoading, lastError);
                break;
            case 1:
                contentFrame.addView(nodesView.getView());
                nodesView.render(cachedNodes, isLoading, lastError);
                break;
            case 2:
                contentFrame.addView(guestsView.getView());
                guestsView.render(cachedGuests, isLoading, lastError);
                break;
            case 3:
                contentFrame.addView(settingsView.getView());
                settingsView.render();
                break;
        }
    }

    public void refreshData() {
        if (HomelabApp.getInstance().getSessionManager().isLocked()) {
            cachedNodes.clear();
            cachedGuests.clear();
            isLoading = false;
            lastError = "Session locked. Unlock in Settings to restore credentials and live telemetry.";
            renderCurrentView();
            return;
        }
        if (isLoading) return;
        new LoadFleetTask().execute();
    }

    public boolean isActionInFlight(String key) {
        return com.homelabpocket.api.ActionGuard.getInstance().isRunning(key);
    }

    public boolean trackActionStart(String key) {
        return com.homelabpocket.api.ActionGuard.getInstance().tryAcquire(key);
    }

    public void trackActionEnd(String key) {
        com.homelabpocket.api.ActionGuard.getInstance().release(key);
    }

    private class LoadFleetTask extends AsyncTask<Void, Void, FleetDataResult> {
        @Override
        protected void onPreExecute() {
            isLoading = true;
            lastError = null;
            renderCurrentView();
        }

        @Override
        protected FleetDataResult doInBackground(Void... voids) {
            HomelabApp app = HomelabApp.getInstance();
            List<NodeStatus> allNodes = new ArrayList<>();
            List<GuestInfo> allGuests = new ArrayList<>();

            // 1. Load configured servers
            List<ServerConfig> servers = app.getServerConfigStore().loadAll();
            if (servers.isEmpty()) {
                // First-run empty state
                return new FleetDataResult(allNodes, allGuests, null);
            }

            String firstError = null;
            for (ServerConfig server : servers) {
                try {
                    // Handle saved demo entries safely without touching real connections
                    if (server.isLegacyDemo()) {
                        allNodes.add(new NodeStatus(server.name != null ? server.name : "Demo Server", server.id, NodeStatus.Status.UNKNOWN,
                                Double.NaN, 0, -1, -1, -1, -1, -1, -1,
                                "Demo entry (offline; demo mode removed)", -1, System.currentTimeMillis()));
                        continue;
                    }

                    AuthCredentials creds = app.getSessionManager().getCredentials(server);
                    if (creds == null) {
                        firstError = "Server '" + server.name + "': " + getString(R.string.error_no_credentials);
                        continue;
                    }

                    if (server.type == ServerConfig.ServerType.PDM) {
                        PdmApiDriver pdm = new PdmApiDriver(server, app.getSessionManager());
                        List<NodeStatus> nodes = pdm.fetchAllNodes(creds);
                        allNodes.addAll(nodes);
                        try {
                            List<GuestInfo> guests = pdm.fetchAllGuests(creds);
                            allGuests.addAll(guests);
                        } catch (Exception e) {
                            // Soft failure on guest list
                        }
                    } else {
                        PveApiDriver pve = new PveApiDriver(server);
                        List<NodeStatus> nodes = pve.fetchAllNodes(creds);
                        allNodes.addAll(nodes);
                        for (NodeStatus n : nodes) {
                            if (n.isOnline()) {
                                try {
                                    allGuests.addAll(pve.fetchAllGuests(n.node, creds));
                                } catch (Exception e) {
                                    // Soft failure on guest list
                                }
                            }
                        }
                    }
                } catch (ApiException e) {
                    if (firstError == null) firstError = e.getUserMessage();
                } catch (Exception e) {
                    if (firstError == null) firstError = e.getMessage();
                }
            }

            return new FleetDataResult(allNodes, allGuests, firstError);
        }

        @Override
        protected void onPostExecute(FleetDataResult result) {
            isLoading = false;
            cachedNodes = result.nodes;
            cachedGuests = result.guests;
            lastError = result.error;
            renderCurrentView();
        }
    }

    private void renderCurrentView() {
        switch (currentTab) {
            case 0:
                dashboardView.render(cachedNodes, cachedGuests, isLoading, lastError);
                break;
            case 1:
                nodesView.render(cachedNodes, isLoading, lastError);
                break;
            case 2:
                guestsView.render(cachedGuests, isLoading, lastError);
                break;
            case 3:
                settingsView.render();
                break;
        }
    }

    private static class FleetDataResult {
        final List<NodeStatus> nodes;
        final List<GuestInfo> guests;
        final String error;

        FleetDataResult(List<NodeStatus> nodes, List<GuestInfo> guests, String error) {
            this.nodes = nodes;
            this.guests = guests;
            this.error = error;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ADD_SERVER && resultCode == RESULT_OK) {
            refreshData();
        } else if (requestCode == REQ_CONFIRM_CREDENTIAL) {
            if (resultCode == RESULT_OK) {
                boolean unlocked = HomelabApp.getInstance().getSessionManager().unlock();
                if (unlocked) {
                    if (settingsView != null) {
                        settingsView.render();
                    }
                    refreshData();
                    Toast.makeText(this, "Session unlocked via device authentication.", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Failed to persist unlocked state to storage; session remains locked.", Toast.LENGTH_LONG).show();
                }
            } else {
                Toast.makeText(this, "Device authentication cancelled or failed.", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
