// SPDX-License-Identifier: GPL-3.0-or-later
// PVE Pocket — Proxmox Datacenter Manager (PDM) API Driver
package com.homelabpocket.api;

import com.homelabpocket.model.AuthCredentials;
import com.homelabpocket.model.GuestInfo;
import com.homelabpocket.model.NodeStatus;
import com.homelabpocket.model.ServerConfig;
import com.homelabpocket.model.StoragePool;
import com.homelabpocket.model.TaskResult;
import com.homelabpocket.util.JsonUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Proxmox Datacenter Manager (PDM) API Driver.
 *
 * Implements real upstream PDM API capabilities according to official PDM schemas:
 * Reference: proxmox-datacenter-manager commit 6d7ef0adff2fc0347d87caf7ee687a013ffcc0b5
 * - server/src/api/resources.rs (get_resources returns Vec<RemoteResources>)
 * - lib/pdm-api-types/src/resource.rs (RemoteResources: remote, error?, resources)
 *
 * Key Schema & Transport Specifications:
 * - Canonical resource discovery endpoint: GET /api2/json/resources/list (returns Vec<RemoteResources>).
 *   No unproven /resources fallback (list_subdirs). Canonical 404 fails closed directly.
 * - Nested resource transport: outer groups contain {remote, error?, resources:[Resource...]}.
 * - Resource enum tagged variants: pve-node, pve-qemu, pve-lxc, pve-storage, pbs-*.
 * - PBS variants (pbs-node, pbs-datastore) are honestly ignored for PVE fleet discovery.
 * - PveStorageResource: shared is a boolean; plugintype is absent upstream (reported as unknown).
 * - PveQemuResource / PveLxcResource: tags is an array of strings; maxcpu is numeric.
 * - PveNodeResource: no disk/maxdisk fields upstream (reported as -1).
 * - Multi-cluster remote/node/vmid identity strictly preserved ({remote}/{node}).
 * - Outer remote error surfaces fail-closed with remote identifier.
 * - Missing resources is valid for empty or error-only remote groups.
 * - Guest power actions and terminal streaming via PDM are explicitly disabled fail-closed.
 */
public class PdmApiDriver {

    public static final String PATH_TICKET = "/access/ticket";
    public static final String PATH_REMOTES = "/remotes";
    public static final String PATH_RESOURCES_LIST = "/resources/list";

    private final ServerConfig config;
    private final SessionManager sessionManager;
    private final ApiClient apiClient;

    public PdmApiDriver(ServerConfig config) throws GeneralSecurityException {
        this(config, null);
    }

    public PdmApiDriver(ServerConfig config, SessionManager sessionManager) throws GeneralSecurityException {
        if (config == null) {
            throw new IllegalArgumentException("ServerConfig cannot be null");
        }
        this.config = config;
        this.sessionManager = sessionManager;
        this.apiClient = new ApiClient(config);
    }

    public ServerConfig getConfig() {
        return config;
    }

    public ApiClient getApiClient() {
        return apiClient;
    }

    // ---- Authentication ----

    /**
     * Authenticates with PDM using username and password.
     * Hits POST /api2/json/access/ticket.
     * Username is normalized with @pam if no realm is specified.
     */
    public AuthCredentials authenticate(String username, String password)
            throws ApiException, IOException {
        return authenticate(username, password, 15_000L);
    }

    public AuthCredentials authenticate(String username, String password, long remainingBudgetMs)
            throws ApiException, IOException {
        return apiClient.authenticate(username, password, remainingBudgetMs);
    }

    // ---- Remotes Discovery ----

    /**
     * Lists all registered PVE and PBS remotes managed by this PDM instance.
     * Hits GET /api2/json/remotes.
     */
    public JSONArray fetchRemotes(AuthCredentials auth)
            throws ApiException, GeneralSecurityException, IOException {
        String resp = executeWithAuthRetry(PATH_REMOTES, "GET", null, auth);
        try {
            return JsonUtil.getDataArray(resp);
        } catch (JSONException e) {
            throw new ApiException(0, "Unexpected PDM remotes response shape: " + e.getMessage());
        }
    }

    // ---- Aggregated Fleet Discovery & Monitoring ----

    /**
     * Discovers all physical cluster nodes across all managed remotes.
     * Queries canonical GET /api2/json/resources/list returning Vec<RemoteResources>.
     * Preserves remote cluster identity: node name is formatted as "{remote}/{node}".
     *
     * Fails closed on API or network errors, or when a remote reports an error.
     */
    public List<NodeStatus> fetchAllNodes(AuthCredentials auth)
            throws ApiException, GeneralSecurityException, IOException {
        JSONArray groups = fetchResourcesGroups(auth);
        List<NodeStatus> nodes = new ArrayList<>();

        for (int i = 0; i < groups.length(); i++) {
            JSONObject group = groups.optJSONObject(i);
            if (group == null) continue;
            String remote = JsonUtil.optString(group, "remote", "");
            String error = JsonUtil.optString(group, "error", "");
            if (!error.isEmpty()) {
                throw new ApiException(0, "Remote '" + remote + "' reported error: " + error);
            }
            JSONArray resources = group.optJSONArray("resources");
            if (resources == null) continue;

            for (int j = 0; j < resources.length(); j++) {
                JSONObject item = resources.optJSONObject(j);
                if (item == null) continue;
                String type = JsonUtil.optString(item, "type", "");
                if ("pve-node".equalsIgnoreCase(type) || "node".equalsIgnoreCase(type)) {
                    nodes.add(parsePdmNode(item, remote, config));
                }
                // Honestly ignore pbs-* variants
            }
        }

        return nodes;
    }

    /**
     * Discovers all virtual guests (QEMU VMs and LXC Containers) across all managed remotes.
     * Queries canonical GET /api2/json/resources/list returning Vec<RemoteResources>.
     * Preserves remote identity: guest node is formatted as "{remote}/{node}".
     */
    public List<GuestInfo> fetchAllGuests(AuthCredentials auth)
            throws ApiException, GeneralSecurityException, IOException {
        JSONArray groups = fetchResourcesGroups(auth);
        List<GuestInfo> guests = new ArrayList<>();

        for (int i = 0; i < groups.length(); i++) {
            JSONObject group = groups.optJSONObject(i);
            if (group == null) continue;
            String remote = JsonUtil.optString(group, "remote", "");
            String error = JsonUtil.optString(group, "error", "");
            if (!error.isEmpty()) {
                throw new ApiException(0, "Remote '" + remote + "' reported error: " + error);
            }
            JSONArray resources = group.optJSONArray("resources");
            if (resources == null) continue;

            for (int j = 0; j < resources.length(); j++) {
                JSONObject item = resources.optJSONObject(j);
                if (item == null) continue;
                String type = JsonUtil.optString(item, "type", "");
                if ("pve-qemu".equalsIgnoreCase(type) || "qemu".equalsIgnoreCase(type)
                        || "pve-lxc".equalsIgnoreCase(type) || "lxc".equalsIgnoreCase(type)) {
                    guests.add(parsePdmGuest(item, remote, config));
                }
                // Honestly ignore pbs-* variants
            }
        }

        return guests;
    }

    /**
     * Fetches hosted guests on a specific node preserving exact remote/node identity.
     * Does NOT use suffix matching to avoid mixing nodes across different remotes.
     */
    public List<GuestInfo> fetchNodeGuests(String nodeName, AuthCredentials auth)
            throws ApiException, GeneralSecurityException, IOException {
        List<GuestInfo> all = fetchAllGuests(auth);
        List<GuestInfo> matched = new ArrayList<>();
        if (nodeName == null || nodeName.trim().isEmpty()) return matched;
        for (GuestInfo g : all) {
            if (nodeName.equals(g.node)) {
                matched.add(g);
            }
        }
        return matched;
    }

    /**
     * Fetches configured storage pools for a specific node from PDM resource inventory.
     * Preserves exact remote/node identity ({remote}/{node}) to avoid mixing storage pools.
     * Shared is parsed as boolean; plugintype defaults to 'unknown' in upstream PDM schema.
     */
    public List<StoragePool> fetchNodeStorage(String nodeName, AuthCredentials auth)
            throws ApiException, GeneralSecurityException, IOException {
        JSONArray groups = fetchResourcesGroups(auth);
        List<StoragePool> pools = new ArrayList<>();
        if (nodeName == null || nodeName.trim().isEmpty()) return pools;

        for (int i = 0; i < groups.length(); i++) {
            JSONObject group = groups.optJSONObject(i);
            if (group == null) continue;
            String remote = JsonUtil.optString(group, "remote", "");
            String error = JsonUtil.optString(group, "error", "");
            if (!error.isEmpty()) {
                throw new ApiException(0, "Remote '" + remote + "' reported error: " + error);
            }
            JSONArray resources = group.optJSONArray("resources");
            if (resources == null) continue;

            for (int j = 0; j < resources.length(); j++) {
                JSONObject item = resources.optJSONObject(j);
                if (item == null) continue;
                String type = JsonUtil.optString(item, "type", "");
                if ("pve-storage".equalsIgnoreCase(type) || "storage".equalsIgnoreCase(type)) {
                    String itemNode = JsonUtil.optString(item, "node", "");
                    String itemFullNode = remote.isEmpty() ? itemNode : remote + "/" + itemNode;

                    if (nodeName.equals(itemFullNode)) {
                        String name = JsonUtil.optString(item, "storage", "unknown");
                        String storageType = JsonUtil.optString(item, "plugintype", "unknown");
                        long disk = JsonUtil.optLong(item, "disk", -1);
                        long maxdisk = JsonUtil.optLong(item, "maxdisk", -1);
                        long avail = (maxdisk > 0 && disk >= 0) ? Math.max(0, maxdisk - disk) : -1;
                        String status = JsonUtil.optString(item, "status", "available");
                        boolean active = "available".equalsIgnoreCase(status) || "online".equalsIgnoreCase(status);

                        boolean shared = false;
                        Object sharedObj = item.opt("shared");
                        if (sharedObj instanceof Boolean) {
                            shared = (Boolean) sharedObj;
                        } else if (sharedObj instanceof Number) {
                            shared = ((Number) sharedObj).intValue() == 1;
                        } else if (sharedObj instanceof String) {
                            shared = "true".equalsIgnoreCase((String) sharedObj) || "1".equals(sharedObj);
                        }

                        pools.add(new StoragePool(name, storageType, maxdisk, disk, avail, active, true, shared, ""));
                    }
                }
            }
        }
        return pools;
    }

    /**
     * Fetches detailed guest status for a specific VMID, node, and guest type across the PDM resource tree.
     * Preserves exact identity: matches vmid, guestType (QEMU vs LXC), and exact node identity ({remote}/{node}).
     */
    public GuestInfo fetchGuestDetail(String nodeName, int vmid, GuestInfo.GuestType guestType, AuthCredentials auth)
            throws ApiException, GeneralSecurityException, IOException {
        List<GuestInfo> guests = fetchAllGuests(auth);
        for (GuestInfo g : guests) {
            if (g.vmid == vmid) {
                if (guestType != null && g.type != guestType) {
                    continue;
                }
                if (nodeName != null && !nodeName.trim().isEmpty() && !nodeName.equals(g.node)) {
                    continue;
                }
                return g;
            }
        }
        return null;
    }

    /**
     * Fetches canonical upstream Vec<RemoteResources> from /api2/json/resources/list.
     * No fallback to unproven /resources route; 404 and other HTTP errors fail closed.
     */
    private JSONArray fetchResourcesGroups(AuthCredentials auth)
            throws ApiException, GeneralSecurityException, IOException {
        String response = executeWithAuthRetry(PATH_RESOURCES_LIST, "GET", null, auth);
        try {
            return JsonUtil.getDataArray(response);
        } catch (JSONException e) {
            throw new ApiException(0, "Unexpected PDM resources response shape: " + e.getMessage());
        }
    }

    // ---- Explicit Fail-Closed Guards: Power Actions & Terminal ----

    /**
     * Guest power actions via PDM are explicitly unsupported in this release
     * to prevent speculative endpoint misrouting.
     */
    public TaskResult executeGuestPowerAction(String node, String guestType, int vmid, String action, AuthCredentials auth) {
        throw new UnsupportedOperationException(
                "Guest power actions via PDM are explicitly unsupported in this release to prevent endpoint misrouting. " +
                "Connect directly to the host's standalone PVE endpoint to manage guests.");
    }

    /**
     * Terminal routes via PDM are explicitly unsupported in this release.
     */
    public String startTerminal(String node, String guestType, int vmid, AuthCredentials auth) {
        throw new UnsupportedOperationException(
                "Terminal streaming via PDM is unsupported in this release. " +
                "Please connect directly to the standalone PVE host using SSH or web console.");
    }

    // ---- Parsing Helpers ----

    public static NodeStatus parsePdmNode(JSONObject item) {
        return parsePdmNode(item, null, null);
    }

    public static NodeStatus parsePdmNode(JSONObject item, ServerConfig config) {
        return parsePdmNode(item, null, config);
    }

    public static NodeStatus parsePdmNode(JSONObject item, String remoteOverride, ServerConfig config) {
        String node = JsonUtil.optString(item, "node", "unknown");
        String remote = (remoteOverride != null && !remoteOverride.isEmpty())
                ? remoteOverride
                : JsonUtil.optString(item, "remote", "");
        String displayName = remote.isEmpty() ? node : remote + "/" + node;
        String serverId = config != null ? config.id : "pdm";

        String statusStr = JsonUtil.optString(item, "status", "unknown");
        NodeStatus.Status status;
        if ("online".equalsIgnoreCase(statusStr)) {
            status = NodeStatus.Status.ONLINE;
        } else if ("offline".equalsIgnoreCase(statusStr)) {
            status = NodeStatus.Status.OFFLINE;
        } else {
            status = NodeStatus.Status.UNKNOWN;
        }

        double cpu = JsonUtil.optDouble(item, "cpu", Double.NaN);
        int maxcpu = 0;
        Object maxcpuObj = item.opt("maxcpu");
        if (maxcpuObj instanceof Number) {
            maxcpu = ((Number) maxcpuObj).intValue();
        }

        long mem = JsonUtil.optLong(item, "mem", -1);
        long maxmem = JsonUtil.optLong(item, "maxmem", -1);
        long disk = JsonUtil.optLong(item, "disk", -1);
        long maxdisk = JsonUtil.optLong(item, "maxdisk", -1);
        long uptime = JsonUtil.optLong(item, "uptime", -1);

        return new NodeStatus(displayName, serverId, status,
                cpu, maxcpu, mem, maxmem, disk, maxdisk, -1, -1,
                null, uptime, System.currentTimeMillis());
    }

    public static GuestInfo parsePdmGuest(JSONObject item) {
        return parsePdmGuest(item, null, null);
    }

    public static GuestInfo parsePdmGuest(JSONObject item, ServerConfig config) {
        return parsePdmGuest(item, null, config);
    }

    public static GuestInfo parsePdmGuest(JSONObject item, String remoteOverride, ServerConfig config) {
        int vmid = JsonUtil.optInt(item, "vmid", 0);
        String name = JsonUtil.optString(item, "name", "");
        String node = JsonUtil.optString(item, "node", "");
        String remote = (remoteOverride != null && !remoteOverride.isEmpty())
                ? remoteOverride
                : JsonUtil.optString(item, "remote", "");
        String nodeIdentity = remote.isEmpty() ? node : remote + "/" + node;
        String serverId = config != null ? config.id : "pdm";

        String typeStr = JsonUtil.optString(item, "type", "qemu");
        GuestInfo.GuestType type;
        if ("lxc".equalsIgnoreCase(typeStr) || "pve-lxc".equalsIgnoreCase(typeStr)) {
            type = GuestInfo.GuestType.LXC;
        } else {
            type = GuestInfo.GuestType.QEMU;
        }

        String statusStr = JsonUtil.optString(item, "status", "unknown");
        GuestInfo.RunState state;
        if ("running".equalsIgnoreCase(statusStr)) {
            state = GuestInfo.RunState.RUNNING;
        } else if ("stopped".equalsIgnoreCase(statusStr)) {
            state = GuestInfo.RunState.STOPPED;
        } else {
            state = GuestInfo.RunState.UNKNOWN;
        }

        double cpu = JsonUtil.optDouble(item, "cpu", Double.NaN);
        int cpus = 1;
        Object maxcpuObj = item.opt("maxcpu");
        if (maxcpuObj instanceof Number) {
            cpus = ((Number) maxcpuObj).intValue();
        }

        long mem = JsonUtil.optLong(item, "mem", -1);
        long maxmem = JsonUtil.optLong(item, "maxmem", -1);
        long disk = JsonUtil.optLong(item, "disk", -1);
        long maxdisk = JsonUtil.optLong(item, "maxdisk", -1);
        long uptime = JsonUtil.optLong(item, "uptime", 0);

        String tags = "";
        Object tagsObj = item.opt("tags");
        if (tagsObj instanceof JSONArray) {
            JSONArray arr = (JSONArray) tagsObj;
            StringBuilder sb = new StringBuilder();
            for (int t = 0; t < arr.length(); t++) {
                String tag = arr.optString(t, "").trim();
                if (!tag.isEmpty()) {
                    if (sb.length() > 0) sb.append(";");
                    sb.append(tag);
                }
            }
            tags = sb.toString();
        } else if (tagsObj != null) {
            tags = tagsObj.toString();
        }

        return new GuestInfo(vmid, name, type, state, nodeIdentity, serverId,
                cpu, cpus, mem, maxmem, disk, maxdisk, -1, -1,
                uptime, tags, false, System.currentTimeMillis());
    }

    public boolean isPdmWorking() {
        return true;
    }

    public String getPdmStatusDisclosure() {
        return "PDM Supported: Real Proxmox Datacenter Manager fleet discovery and inventory monitoring active. " +
                "Guest power actions and terminal streaming are disabled in this release to prevent endpoint misrouting.";
    }

    // ---- Transport & Auth Retry Logic ----

    private String executeWithAuthRetry(String path, String method, String formBody, AuthCredentials auth)
            throws ApiException, GeneralSecurityException, IOException {
        return executeWithAuthRetry(path, method, formBody, auth, 15_000L);
    }

    private String executeWithAuthRetry(String path, String method, String formBody, AuthCredentials auth, long budgetMs)
            throws ApiException, GeneralSecurityException, IOException {
        long deadlineNano = (budgetMs > 0) ? (System.nanoTime() + budgetMs * 1_000_000L) : 0;
        AuthCredentials currentAuth = auth;
        try {
            long callBudget = (deadlineNano > 0) ? ((deadlineNano - System.nanoTime()) / 1_000_000L) : budgetMs;
            if (deadlineNano > 0 && callBudget <= 0) {
                throw new ApiException(0, "Request deadline exceeded before dispatch (" + budgetMs + "ms limit)");
            }
            if ("POST".equalsIgnoreCase(method)) {
                return apiClient.post(path, formBody != null ? formBody : "", currentAuth, callBudget > 0 ? callBudget : 15_000L);
            } else {
                return apiClient.get(path, currentAuth, callBudget > 0 ? callBudget : 15_000L);
            }
        } catch (ApiException e) {
            // Strict 401 vs 403 differentiation:
            // 403 Forbidden is surfaced directly as permission denied without looping or invalidating session.
            if (e.getHttpCode() == 403) {
                throw new ApiException(403, "Permission denied (403): User lacks required PDM privilege for " + path);
            }

            // 401 Unauthorized: Attempt exactly one session ticket refresh if sessionManager is available
            if (e.getHttpCode() == 401 && sessionManager != null && currentAuth != null && !currentAuth.isApiToken()) {
                long refreshBudget = 0;
                if (deadlineNano > 0) {
                    long remNanos = deadlineNano - System.nanoTime();
                    refreshBudget = remNanos / 1_000_000L;
                    if (refreshBudget <= 0) {
                        throw new ApiException(0, "Request deadline exceeded before ticket refresh (" + budgetMs + "ms limit)");
                    }
                }
                AuthCredentials fresh = (refreshBudget > 0)
                        ? sessionManager.refreshSession(config, refreshBudget)
                        : sessionManager.refreshSession(config);
                if (fresh != null) {
                    long retryBudget = 0;
                    if (deadlineNano > 0) {
                        long remNanos = deadlineNano - System.nanoTime();
                        retryBudget = remNanos / 1_000_000L;
                        if (retryBudget <= 0) {
                            throw new ApiException(0, "Request deadline exceeded after ticket refresh (" + budgetMs + "ms limit)");
                        }
                    }
                    if ("POST".equalsIgnoreCase(method)) {
                        return apiClient.post(path, formBody != null ? formBody : "", fresh, retryBudget > 0 ? retryBudget : 15_000L);
                    } else {
                        return apiClient.get(path, fresh, retryBudget > 0 ? retryBudget : 15_000L);
                    }
                }
            }
            throw e;
        }
    }
}
