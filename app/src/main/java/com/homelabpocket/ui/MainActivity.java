// SPDX-License-Identifier: GPL-3.0-or-later
// Homelab Pocket — Main Matrix Operations Activity
package com.homelabpocket.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
import com.homelabpocket.api.ActionGuard;
import com.homelabpocket.api.ApiException;
import com.homelabpocket.api.PdmApiDriver;
import com.homelabpocket.api.PveApiDriver;
import com.homelabpocket.model.AuthCredentials;
import com.homelabpocket.model.GuestInfo;
import com.homelabpocket.model.NodeStatus;
import com.homelabpocket.model.ServerConfig;
import com.homelabpocket.scheduler.AutoRefreshScheduler;
import com.homelabpocket.ui.dashboard.DashboardView;
import com.homelabpocket.ui.guests.GuestsView;
import com.homelabpocket.ui.nodes.NodesView;
import com.homelabpocket.ui.settings.AddServerActivity;
import com.homelabpocket.ui.settings.SettingsView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private Map<String, String> cachedServerProvenances = new LinkedHashMap<>();
    private String lastConfigFingerprint = null;
    private boolean isLoading = false;
    private String lastError = null;
    private long lastSuccessfulFetchTime = 0;
    private boolean isActivityResumed = false;

    // Single loading owner scheduler for fleet overview/hosts/guests
    private AutoRefreshScheduler scheduler;

    // Screen off/on broadcast receiver for lifecycle awareness
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

        // Initialize single loading owner scheduler
        scheduler = new AutoRefreshScheduler(
                HomelabApp.getInstance().getRefreshPreferenceStore(),
                callback -> new LoadFleetTask(callback).execute(),
                () -> ActionGuard.getInstance().getActiveCount() > 0 || (guestsView != null && guestsView.isPowerOperationActive())
        );
        // Prompt fresh read is handled during onResume, avoiding duplicate startup fetch
    }

    @Override
    protected void onResume() {
        super.onResume();
        isActivityResumed = true;
        syncConfigFingerprintProvenance();
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
            scheduler.setPollingEnabledForScreen(currentTab != 3);
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

    private void syncConfigFingerprintProvenance() {
        String currentFingerprint = HomelabApp.getInstance().getServerConfigStore().getConfigFingerprint();
        if (lastConfigFingerprint != null && !lastConfigFingerprint.equals(currentFingerprint)) {
            List<ServerConfig> configuredServers = HomelabApp.getInstance().getServerConfigStore().loadAll();
            UiPresentationPolicy.FleetCacheMergeResult filtered =
                    UiPresentationPolicy.filterCacheToConfiguredProvenance(cachedNodes, cachedGuests, cachedServerProvenances, configuredServers);
            cachedNodes = new ArrayList<>(filtered.nodes);
            cachedGuests = new ArrayList<>(filtered.guests);
            cachedServerProvenances = new LinkedHashMap<>(filtered.provenances);
        }
        lastConfigFingerprint = currentFingerprint;
    }

    public void selectTab(int tab) {
        syncConfigFingerprintProvenance();
        currentTab = tab;
        if (scheduler != null) {
            scheduler.setSessionLocked(HomelabApp.getInstance().getSessionManager().isLocked());
            // Settings tab (tab 3) need not poll
            scheduler.setPollingEnabledForScreen(tab != 3);
        }
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
                dashboardView.render(cachedNodes, cachedGuests, isLoading, lastError, lastSuccessfulFetchTime);
                break;
            case 1:
                contentFrame.addView(nodesView.getView());
                nodesView.render(cachedNodes, isLoading, lastError, lastSuccessfulFetchTime);
                break;
            case 2:
                contentFrame.addView(guestsView.getView());
                guestsView.render(cachedGuests, isLoading, lastError, lastSuccessfulFetchTime);
                break;
            case 3:
                contentFrame.addView(settingsView.getView());
                settingsView.render();
                break;
        }
    }

    public void refreshData() {
        syncConfigFingerprintProvenance();
        boolean sessionLocked = HomelabApp.getInstance().getSessionManager().isLocked();
        if (scheduler != null) {
            scheduler.setSessionLocked(sessionLocked);
        }
        if (sessionLocked) {
            cachedNodes.clear();
            cachedGuests.clear();
            cachedServerProvenances.clear();
            isLoading = false;
            lastError = "Session locked. Unlock in Settings to restore credentials and live telemetry.";
            renderCurrentView();
            return;
        }
        if (scheduler != null) {
            scheduler.triggerManualRefresh();
        } else {
            if (isLoading) return;
            new LoadFleetTask(null).execute();
        }
    }

    public void requestPostOperationRefresh() {
        syncConfigFingerprintProvenance();
        boolean sessionLocked = HomelabApp.getInstance().getSessionManager().isLocked();
        if (scheduler != null) {
            scheduler.setSessionLocked(sessionLocked);
        }
        if (sessionLocked) {
            cachedNodes.clear();
            cachedGuests.clear();
            cachedServerProvenances.clear();
            isLoading = false;
            lastError = "Session locked. Unlock in Settings to restore credentials and live telemetry.";
            renderCurrentView();
            return;
        }
        if (scheduler != null) {
            scheduler.requestPostOperationRefresh();
        } else {
            if (isLoading) return;
            new LoadFleetTask(null).execute();
        }
    }

    public AutoRefreshScheduler getScheduler() {
        return scheduler;
    }

    public long getLastSuccessfulFetchTime() {
        return lastSuccessfulFetchTime;
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
        private final AutoRefreshScheduler.RefreshCallback refreshCallback;
        private final String serverSnapshot;

        LoadFleetTask(AutoRefreshScheduler.RefreshCallback callback) {
            this.refreshCallback = callback;
            this.serverSnapshot = HomelabApp.getInstance().getServerConfigStore().getConfigFingerprint();
        }

        @Override
        protected void onPreExecute() {
            isLoading = true;
            // Prevent loading flicker during background auto-refresh:
            // only render if initial cold start has no existing data
            if (cachedNodes.isEmpty() && cachedGuests.isEmpty()) {
                renderCurrentView();
            }
        }

        @Override
        protected FleetDataResult doInBackground(Void... voids) {
            HomelabApp app = HomelabApp.getInstance();
            List<NodeStatus> allNodes = new ArrayList<>();
            List<GuestInfo> allGuests = new ArrayList<>();
            Set<String> successfulNodeServers = new HashSet<>();
            Set<String> successfulGuestServers = new HashSet<>();
            Set<String> successfulGuestNodeKeys = new HashSet<>();
            Set<String> successfulRemoteKeys = new HashSet<>();
            Set<String> failedRemoteKeys = new HashSet<>();
            Map<String, String> failedRemoteErrors = new HashMap<>();

            // 1. Load configured servers
            List<ServerConfig> servers = app.getServerConfigStore().loadAll();
            if (servers.isEmpty()) {
                // First-run empty state
                return new FleetDataResult(allNodes, allGuests, null, successfulNodeServers, successfulGuestServers, successfulGuestNodeKeys);
            }

            String firstError = null;
            for (ServerConfig server : servers) {
                try {
                    // Handle saved demo entries safely without touching real connections
                    if (server.isLegacyDemo()) {
                        allNodes.add(new NodeStatus(server.name != null ? server.name : "Demo Server", server.id, NodeStatus.Status.UNKNOWN,
                                Double.NaN, 0, -1, -1, -1, -1, -1, -1,
                                "Demo entry (offline; demo mode removed)", -1, System.currentTimeMillis()));
                        successfulNodeServers.add(server.id);
                        continue;
                    }

                    AuthCredentials creds = app.getSessionManager().getCredentials(server);
                    if (creds == null) {
                        if (firstError == null) {
                            firstError = "Server '" + server.name + "': " + getString(R.string.error_no_credentials);
                        }
                        continue;
                    }

                    if (server.type == ServerConfig.ServerType.PDM) {
                        PdmApiDriver pdm = new PdmApiDriver(server, app.getSessionManager());
                        PdmApiDriver.PdmFleetResult pdmResult = pdm.fetchFleetResources(creds);

                        for (String rem : pdmResult.successfulRemotes) {
                            successfulRemoteKeys.add(server.id + "/" + rem);
                        }
                        for (String rem : pdmResult.failedRemotes) {
                            failedRemoteKeys.add(server.id + "/" + rem);
                        }
                        failedRemoteErrors.putAll(pdmResult.remoteErrors);

                        allNodes.addAll(pdmResult.nodes);
                        allGuests.addAll(pdmResult.guests);
                        successfulNodeServers.add(server.id);
                        successfulGuestServers.add(server.id);

                        if (pdmResult.isAllFailed) {
                            if (firstError == null) {
                                firstError = pdmResult.firstError != null
                                        ? pdmResult.firstError
                                        : "All PDM remotes reported errors";
                            }
                        } else if (pdmResult.isPartialFailure) {
                            if (firstError == null) {
                                firstError = pdmResult.firstError;
                            }
                        }
                    } else {
                        PveApiDriver pve = new PveApiDriver(server);
                        List<NodeStatus> nodes = pve.fetchAllNodes(creds);
                        allNodes.addAll(nodes);
                        successfulNodeServers.add(server.id);
                        for (NodeStatus n : nodes) {
                            if (n.isOnline()) {
                                try {
                                    allGuests.addAll(pve.fetchAllGuests(n.node, creds));
                                    successfulGuestNodeKeys.add(server.id + "/" + n.node);
                                } catch (Exception e) {
                                    if (firstError == null) {
                                        firstError = "Failed to fetch guests for host " + n.node + ": " + (e.getMessage() != null ? e.getMessage() : "Telemetry error");
                                    }
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

            return new FleetDataResult(allNodes, allGuests, firstError,
                    successfulNodeServers, successfulGuestServers, successfulGuestNodeKeys,
                    successfulRemoteKeys, failedRemoteKeys, failedRemoteErrors);
        }

        @Override
        protected void onPostExecute(FleetDataResult result) {
            isLoading = false;

            // Activity state validation: reject detached callbacks when not in resumed foreground state
            if (isFinishing() || isDestroyed() || !isActivityResumed) {
                if (refreshCallback != null) {
                    refreshCallback.onError(new IllegalStateException("Activity not in resumed foreground state"), "Detached");
                }
                return;
            }

            if (HomelabApp.getInstance().getSessionManager().isLocked()) {
                if (scheduler != null) {
                    scheduler.setSessionLocked(true);
                }
                cachedNodes.clear();
                cachedGuests.clear();
                cachedServerProvenances.clear();
                lastError = "Session locked. Unlock in Settings to restore credentials and live telemetry.";
                renderCurrentView();
                if (refreshCallback != null) {
                    refreshCallback.onError(new IllegalStateException("Session locked"), "Session locked");
                }
                return;
            }

            // Scheduler callback validity / owner check
            if (refreshCallback != null && !refreshCallback.canAcceptResult()) {
                refreshCallback.onError(new IllegalStateException("Stale or detached callback"), "Stale");
                return;
            }

            // Action in progress check: don't recreate cards, dismiss confirmation, or overwrite busy owner
            if (ActionGuard.getInstance().getActiveCount() > 0 || (guestsView != null && guestsView.isPowerOperationActive())) {
                if (refreshCallback != null) {
                    refreshCallback.onError(new IllegalStateException("Action in progress"), "Action in progress");
                }
                return;
            }

            // Server replacement check: reject if servers changed while fetch was in flight
            String currentFingerprint = HomelabApp.getInstance().getServerConfigStore().getConfigFingerprint();
            if (!serverSnapshot.equals(currentFingerprint)) {
                if (refreshCallback != null) {
                    refreshCallback.onError(new IllegalStateException("Server configuration changed"), "Configuration changed");
                }
                return;
            }

            List<ServerConfig> configuredServers = HomelabApp.getInstance().getServerConfigStore().loadAll();
            UiPresentationPolicy.FleetCacheMergeResult mergeResult = UiPresentationPolicy.mergeFleetData(
                    cachedNodes, cachedGuests, cachedServerProvenances, configuredServers,
                    result.nodes, result.guests, result.error,
                    result.successfulNodeServers, result.successfulGuestServers, result.successfulGuestNodeKeys,
                    result.successfulRemoteKeys, result.failedRemoteKeys, result.failedRemoteErrors);

            cachedNodes = new ArrayList<>(mergeResult.nodes);
            cachedGuests = new ArrayList<>(mergeResult.guests);
            cachedServerProvenances = new LinkedHashMap<>(mergeResult.provenances);
            lastConfigFingerprint = currentFingerprint;

            boolean anyScopeSuccess = (result.successfulRemoteKeys != null && !result.successfulRemoteKeys.isEmpty());
            if (!anyScopeSuccess && result.successfulNodeServers != null && configuredServers != null) {
                for (ServerConfig s : configuredServers) {
                    if (s != null && s.type != ServerConfig.ServerType.PDM && result.successfulNodeServers.contains(s.id)) {
                        anyScopeSuccess = true;
                        break;
                    }
                }
            }

            if (result.error == null) {
                lastError = null;
                lastSuccessfulFetchTime = System.currentTimeMillis();
                renderCurrentView();
                if (refreshCallback != null) {
                    refreshCallback.onSuccess();
                }
            } else {
                lastError = result.error;
                if (anyScopeSuccess) {
                    lastSuccessfulFetchTime = System.currentTimeMillis();
                }
                renderCurrentView();
                if (refreshCallback != null) {
                    refreshCallback.onError(new Exception(result.error), result.error);
                }
            }
        }

        @Override
        protected void onCancelled(FleetDataResult result) {
            isLoading = false;
            if (refreshCallback != null) {
                refreshCallback.onError(new IllegalStateException("Task cancelled"), "Cancelled");
            }
        }

        @Override
        protected void onCancelled() {
            isLoading = false;
            if (refreshCallback != null) {
                refreshCallback.onError(new IllegalStateException("Task cancelled"), "Cancelled");
            }
        }
    }

    private void renderCurrentView() {
        switch (currentTab) {
            case 0:
                dashboardView.render(cachedNodes, cachedGuests, isLoading, lastError, lastSuccessfulFetchTime);
                break;
            case 1:
                nodesView.render(cachedNodes, isLoading, lastError, lastSuccessfulFetchTime);
                break;
            case 2:
                guestsView.render(cachedGuests, isLoading, lastError, lastSuccessfulFetchTime);
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
        final Set<String> successfulNodeServers;
        final Set<String> successfulGuestServers;
        final Set<String> successfulGuestNodeKeys;
        final Set<String> successfulRemoteKeys;
        final Set<String> failedRemoteKeys;
        final Map<String, String> failedRemoteErrors;

        FleetDataResult(List<NodeStatus> nodes, List<GuestInfo> guests, String error,
                        Set<String> successfulNodeServers,
                        Set<String> successfulGuestServers,
                        Set<String> successfulGuestNodeKeys) {
            this(nodes, guests, error, successfulNodeServers, successfulGuestServers,
                    successfulGuestNodeKeys, Collections.emptySet(), Collections.emptySet(), Collections.emptyMap());
        }

        FleetDataResult(List<NodeStatus> nodes, List<GuestInfo> guests, String error,
                        Set<String> successfulNodeServers,
                        Set<String> successfulGuestServers,
                        Set<String> successfulGuestNodeKeys,
                        Set<String> successfulRemoteKeys,
                        Set<String> failedRemoteKeys,
                        Map<String, String> failedRemoteErrors) {
            this.nodes = nodes != null ? nodes : Collections.emptyList();
            this.guests = guests != null ? guests : Collections.emptyList();
            this.error = error;
            this.successfulNodeServers = successfulNodeServers != null ? successfulNodeServers : Collections.emptySet();
            this.successfulGuestServers = successfulGuestServers != null ? successfulGuestServers : Collections.emptySet();
            this.successfulGuestNodeKeys = successfulGuestNodeKeys != null ? successfulGuestNodeKeys : Collections.emptySet();
            this.successfulRemoteKeys = successfulRemoteKeys != null ? successfulRemoteKeys : Collections.emptySet();
            this.failedRemoteKeys = failedRemoteKeys != null ? failedRemoteKeys : Collections.emptySet();
            this.failedRemoteErrors = failedRemoteErrors != null ? failedRemoteErrors : Collections.emptyMap();
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
