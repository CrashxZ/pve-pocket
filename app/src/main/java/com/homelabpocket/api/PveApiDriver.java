// SPDX-License-Identifier: GPL-3.0-or-later
// Homelab Pocket — Proxmox VE API driver
package com.homelabpocket.api;

import com.homelabpocket.HomelabApp;
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
import java.util.List;

/**
 * Proxmox VE (PVE) API driver.
 *
 * API documentation: https://pve.proxmox.com/pve-docs/api-viewer/index.html
 *
 * All methods are synchronous — callers must run on background threads.
 *
 * Resource counting notes:
 * - Node CPU usage is reported by the node itself via /nodes/{node}/status
 * - Guest CPU usage is per-guest allocated CPU time, NOT additive to host
 * - Fleet CPU is computed as: sum(node.cpu * node.maxcpu) / sum(node.maxcpu)
 * - RAM: host total includes guest allocations; we show host-level RAM only in fleet view
 *   to avoid double-counting. Per-guest RAM shown separately in guest detail.
 */
public class PveApiDriver {

    private final ServerConfig config;
    private final ApiClient client;
    private final SessionManager sessionManager;

    public PveApiDriver(ServerConfig config) throws GeneralSecurityException {
        this(config, HomelabApp.getInstance() != null ? HomelabApp.getInstance().getSessionManager() : null);
    }

    public PveApiDriver(ServerConfig config, SessionManager sessionManager) throws GeneralSecurityException {
        this.config = config;
        this.sessionManager = sessionManager;
        this.client = new ApiClient(config);
    }

    @FunctionalInterface
    private interface RequestAction<T> {
        T execute(AuthCredentials auth, long remainingBudgetMs) throws ApiException, IOException;
    }

    @FunctionalInterface
    private interface RequestActionWithDeadline<T> {
        T execute(AuthCredentials auth, long deadlineNano) throws ApiException, IOException;
    }

    private <T> T executeWithAuthRetry(AuthCredentials auth, RequestAction<T> action) throws ApiException, IOException {
        return executeWithAuthRetry(auth, 15_000L, action);
    }

    private <T> T executeWithAuthRetry(AuthCredentials auth, long budgetMs, RequestAction<T> action) throws ApiException, IOException {
        if (budgetMs <= 0) {
            throw new ApiException(0, "Request deadline already expired before dispatch (" + budgetMs + "ms remaining)");
        }
        long deadlineNano = System.nanoTime() + budgetMs * 1_000_000L;
        return executeWithAuthRetryWithDeadline(auth, deadlineNano, (a, dl) -> {
            long remMs = (dl > 0) ? Math.max(1, (dl - System.nanoTime()) / 1_000_000L) : 15_000L;
            return action.execute(a, remMs);
        });
    }

    private <T> T executeWithAuthRetryWithDeadline(AuthCredentials auth, long deadlineNano, RequestActionWithDeadline<T> action)
            throws ApiException, IOException {
        if (deadlineNano > 0 && System.nanoTime() >= deadlineNano) {
            throw new ApiException(0, "Request deadline exceeded before dispatch");
        }
        try {
            return action.execute(auth, deadlineNano);
        } catch (ApiException e) {
            if (e.getHttpCode() == 401) {
                if (sessionManager != null) {
                    if (auth == null || !auth.isApiToken()) {
                        if (deadlineNano > 0 && System.nanoTime() >= deadlineNano) {
                            throw new ApiException(0, "Request deadline exceeded before ticket refresh");
                        }
                        // Ticket auth: invalidate stale ticket and attempt to obtain a fresh ticket via stored credentials
                        AuthCredentials fresh = null;
                        try {
                            fresh = sessionManager.refreshSessionWithDeadline(config, deadlineNano);
                        } catch (ApiException apiEx) {
                            // If refresh failed due to deadline, rethrow immediately
                            if (apiEx.getHttpCode() == 0 && apiEx.getMessage() != null
                                    && (apiEx.getMessage().contains("deadline") || apiEx.getMessage().contains("expired"))) {
                                throw apiEx;
                            }
                        } catch (Exception ignored) {
                            // Refresh failed, fall through to rethrow original 401
                        }

                        if (fresh != null) {
                            if (deadlineNano > 0 && System.nanoTime() >= deadlineNano) {
                                throw new ApiException(0, "Request deadline exceeded after ticket refresh");
                            }
                            return action.execute(fresh, deadlineNano);
                        }
                    } else {
                        // API Token or unrefreshable: invalidate cached session
                        sessionManager.invalidateSessionWithDeadline(config.id, deadlineNano);
                    }
                }
            }
            throw e;
        }
    }

    /**
     * Returns the ApiClient (for probing cert fingerprint during setup).
     */
    public ApiClient getApiClient() {
        return client;
    }

    // ---- Authentication ----

    /**
     * Authenticates with username/password and returns a short-lived ticket.
     * Password is used once and not retained by this driver.
     */
    public AuthCredentials authenticate(String username, String password)
            throws ApiException, IOException {
        return client.authenticate(username, password);
    }

    /**
     * Authenticates with username/password bounded by an explicit remaining budget.
     */
    public AuthCredentials authenticate(String username, String password, long remainingBudgetMs)
            throws ApiException, IOException {
        return client.authenticate(username, password, remainingBudgetMs);
    }

    public AuthCredentials authenticateWithDeadline(String username, String password, long deadlineNanos)
            throws ApiException, IOException {
        return client.authenticateWithDeadline(username, password, deadlineNanos);
    }

    // ---- Node status ----

    /**
     * Fetches status for all nodes in the cluster.
     * Nodes that are offline appear in the list with OFFLINE status.
     */
    public List<NodeStatus> fetchAllNodes(AuthCredentials auth) throws ApiException, IOException {
        String response = executeWithAuthRetry(auth, (a, rem) -> client.get("/nodes", a));
        List<NodeStatus> results = new ArrayList<>();
        try {
            JSONArray nodes = JsonUtil.getDataArray(response);
            for (int i = 0; i < nodes.length(); i++) {
                JSONObject node = nodes.getJSONObject(i);
                results.add(parseNodeStatus(node));
            }
        } catch (JSONException e) {
            throw new ApiException(0, "Failed to parse nodes response: " + e.getMessage());
        }
        return results;
    }

    /**
     * Fetches detailed status for a single node.
     */
    public NodeStatus fetchNodeDetail(String nodeName, AuthCredentials auth)
            throws ApiException, IOException {
        try {
            String response = executeWithAuthRetry(auth, (a, rem) -> client.get("/nodes/" + nodeName + "/status", a));
            JSONObject data = JsonUtil.getDataObject(response);
            // /nodes/{node}/status has different field layout than /nodes list
            String status = data.optString("status", "online");
            double cpu = JsonUtil.optDouble(data, "cpu", Double.NaN);
            JSONObject mem = data.optJSONObject("memory");
            long memUsed = mem != null ? JsonUtil.optLong(mem, "used", -1) : -1;
            long memTotal = mem != null ? JsonUtil.optLong(mem, "total", -1) : -1;
            JSONObject rootfs = data.optJSONObject("rootfs");
            long diskUsed = rootfs != null ? JsonUtil.optLong(rootfs, "used", -1) : -1;
            long diskTotal = rootfs != null ? JsonUtil.optLong(rootfs, "total", -1) : -1;
            long netIn = JsonUtil.optLong(data, "netin", -1);
            long netOut = JsonUtil.optLong(data, "netout", -1);
            long uptime = JsonUtil.optLong(data, "uptime", -1);
            int cpuCount = JsonUtil.optInt(data, "cpuinfo", 0);
            // cpuinfo is an object; get cpu count from it
            JSONObject cpuInfo = data.optJSONObject("cpuinfo");
            if (cpuInfo != null) {
                cpuCount = JsonUtil.optInt(cpuInfo, "cpus", 0);
            }

            return new NodeStatus(nodeName, config.id,
                    "online".equals(status) ? NodeStatus.Status.ONLINE : NodeStatus.Status.OFFLINE,
                    cpu, cpuCount, memUsed, memTotal, diskUsed, diskTotal,
                    netIn, netOut,
                    data.optString("pveversion", null),
                    uptime, System.currentTimeMillis());
        } catch (JSONException e) {
            throw new ApiException(0, "Failed to parse node detail: " + e.getMessage());
        }
    }

    private NodeStatus parseNodeStatus(JSONObject node) throws JSONException {
        String name = node.getString("node");
        String statusStr = node.optString("status", "unknown");
        NodeStatus.Status status;
        switch (statusStr) {
            case "online": status = NodeStatus.Status.ONLINE; break;
            case "offline": status = NodeStatus.Status.OFFLINE; break;
            default: status = NodeStatus.Status.UNKNOWN;
        }

        double cpu = JsonUtil.optDouble(node, "cpu", Double.NaN);
        int maxcpu = JsonUtil.optInt(node, "maxcpu", 0);
        long memUsed = JsonUtil.optLong(node, "mem", -1);
        long memTotal = JsonUtil.optLong(node, "maxmem", -1);
        long diskUsed = JsonUtil.optLong(node, "disk", -1);
        long diskTotal = JsonUtil.optLong(node, "maxdisk", -1);
        long netIn = JsonUtil.optLong(node, "netin", -1);
        long netOut = JsonUtil.optLong(node, "netout", -1);
        long uptime = JsonUtil.optLong(node, "uptime", -1);
        String pveVersion = node.optString("pveversion", null);

        return new NodeStatus(name, config.id, status,
                cpu, maxcpu,
                memUsed, memTotal,
                diskUsed, diskTotal,
                netIn, netOut,
                pveVersion, uptime,
                System.currentTimeMillis());
    }

    // ---- Guest listing ----

    /**
     * Fetches all VMs (QEMU) on a specific node.
     */
    public List<GuestInfo> fetchVms(String nodeName, AuthCredentials auth)
            throws ApiException, IOException {
        return fetchVms(nodeName, auth, 15_000L);
    }

    public List<GuestInfo> fetchVms(String nodeName, AuthCredentials auth, long remainingBudgetMs)
            throws ApiException, IOException {
        if (remainingBudgetMs <= 0) {
            throw new ApiException(0, "Request deadline already expired before fetching VMs (" + remainingBudgetMs + "ms remaining)");
        }
        long deadlineNanos = System.nanoTime() + remainingBudgetMs * 1_000_000L;
        return fetchVmsWithDeadline(nodeName, auth, deadlineNanos);
    }

    public List<GuestInfo> fetchVmsWithDeadline(String nodeName, AuthCredentials auth, long deadlineNanos)
            throws ApiException, IOException {
        String path = "/nodes/" + nodeName + "/qemu";
        String response = executeWithAuthRetryWithDeadline(auth, deadlineNanos, (a, dl) -> client.getWithDeadline(path, a, dl));
        return parseGuestList(response, nodeName, GuestInfo.GuestType.QEMU);
    }

    /**
     * Fetches all LXC containers on a specific node.
     */
    public List<GuestInfo> fetchContainers(String nodeName, AuthCredentials auth)
            throws ApiException, IOException {
        return fetchContainers(nodeName, auth, 15_000L);
    }

    public List<GuestInfo> fetchContainers(String nodeName, AuthCredentials auth, long remainingBudgetMs)
            throws ApiException, IOException {
        if (remainingBudgetMs <= 0) {
            throw new ApiException(0, "Request deadline already expired before fetching containers (" + remainingBudgetMs + "ms remaining)");
        }
        long deadlineNanos = System.nanoTime() + remainingBudgetMs * 1_000_000L;
        return fetchContainersWithDeadline(nodeName, auth, deadlineNanos);
    }

    public List<GuestInfo> fetchContainersWithDeadline(String nodeName, AuthCredentials auth, long deadlineNanos)
            throws ApiException, IOException {
        String path = "/nodes/" + nodeName + "/lxc";
        String response = executeWithAuthRetryWithDeadline(auth, deadlineNanos, (a, dl) -> client.getWithDeadline(path, a, dl));
        return parseGuestList(response, nodeName, GuestInfo.GuestType.LXC);
    }

    /**
     * Fetches all guests (VMs + containers) on a node.
     * Enforces explicit error semantics: if either VM or container fetching fails
     * (e.g. HTTP 500, malformed response, auth error, or timeout), throws ApiException
     * rather than silently converting failures into an empty or partial list.
     * Truly empty semantics: returns an empty list only when both endpoints return valid empty lists.
     */
    public List<GuestInfo> fetchAllGuests(String nodeName, AuthCredentials auth)
            throws ApiException, IOException {
        return fetchAllGuests(nodeName, auth, 15_000L);
    }

    public List<GuestInfo> fetchAllGuests(String nodeName, AuthCredentials auth, long remainingBudgetMs)
            throws ApiException, IOException {
        if (remainingBudgetMs <= 0) {
            throw new ApiException(0, "Request deadline already expired before fetching guests (" + remainingBudgetMs + "ms remaining)");
        }
        long deadlineNanos = System.nanoTime() + remainingBudgetMs * 1_000_000L;
        return fetchAllGuestsWithDeadline(nodeName, auth, deadlineNanos);
    }

    public List<GuestInfo> fetchAllGuestsWithDeadline(String nodeName, AuthCredentials auth, long deadlineNanos)
            throws ApiException, IOException {
        List<GuestInfo> all = new ArrayList<>();
        all.addAll(fetchVmsWithDeadline(nodeName, auth, deadlineNanos));
        all.addAll(fetchContainersWithDeadline(nodeName, auth, deadlineNanos));
        return all;
    }

    /**
     * Fetches all configured storage pools on a specific node.
     * Hits GET /nodes/{node}/storage.
     */
    public List<StoragePool> fetchNodeStorage(String nodeName, AuthCredentials auth)
            throws ApiException, IOException {
        String response = executeWithAuthRetry(auth, (a, rem) -> client.get("/nodes/" + nodeName + "/storage", a));
        List<StoragePool> pools = new ArrayList<>();
        try {
            JSONArray array = JsonUtil.getDataArray(response);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                pools.add(parseStoragePool(obj, nodeName));
            }
        } catch (JSONException e) {
            throw new ApiException(0, "Failed to parse storage response: " + e.getMessage());
        }
        return pools;
    }

    public static StoragePool parseStoragePool(JSONObject obj) {
        return parseStoragePool(obj, null);
    }

    public static StoragePool parseStoragePool(JSONObject obj, String node) {
        String name = obj.optString("storage", "unknown");
        String type = obj.optString("type", "");
        long total = JsonUtil.optLong(obj, "total", -1);
        long used = JsonUtil.optLong(obj, "used", -1);
        long avail = JsonUtil.optLong(obj, "avail", -1);
        boolean active = obj.optInt("active", 1) == 1;
        boolean enabled = obj.optInt("enabled", 1) == 1;
        boolean shared = obj.optInt("shared", 0) == 1;
        String content = obj.optString("content", "");
        return new StoragePool(name, type, total, used, avail, active, enabled, shared, content, node);
    }

    private List<GuestInfo> parseGuestList(String response, String nodeName,
                                            GuestInfo.GuestType type)
            throws ApiException {
        List<GuestInfo> results = new ArrayList<>();
        try {
            JSONArray items = JsonUtil.getDataArray(response);
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                results.add(parseGuest(item, nodeName, type));
            }
        } catch (JSONException e) {
            throw new ApiException(0, "Failed to parse guest list: " + e.getMessage());
        }
        return results;
    }

    private GuestInfo parseGuest(JSONObject item, String nodeName, GuestInfo.GuestType type)
            throws JSONException {
        int vmid = item.getInt("vmid");
        String name = item.optString("name", "");
        String statusStr = item.optString("status", "unknown");
        GuestInfo.RunState state = parseRunState(statusStr);

        double cpu = JsonUtil.optDouble(item, "cpu", Double.NaN);
        int cpus = JsonUtil.optInt(item, "cpus", 1);
        long mem = JsonUtil.optLong(item, "mem", -1);
        long maxmem = JsonUtil.optLong(item, "maxmem", -1);
        long disk = JsonUtil.optLong(item, "disk", -1);
        long maxdisk = JsonUtil.optLong(item, "maxdisk", -1);
        long diskread = JsonUtil.optLong(item, "diskread", -1);
        long diskwrite = JsonUtil.optLong(item, "diskwrite", -1);
        long netin = JsonUtil.optLong(item, "netin", -1);
        long netout = JsonUtil.optLong(item, "netout", -1);
        long uptime = JsonUtil.optLong(item, "uptime", 0);
        String tags = item.optString("tags", "");

        return new GuestInfo(vmid, name, type, state, nodeName, config.id,
                cpu, cpus, mem, maxmem, disk, maxdisk, diskread, diskwrite, netin, netout,
                uptime, tags, false, System.currentTimeMillis());
    }

    private GuestInfo.RunState parseRunState(String status) {
        switch (status) {
            case "running": return GuestInfo.RunState.RUNNING;
            case "stopped": return GuestInfo.RunState.STOPPED;
            case "paused": return GuestInfo.RunState.PAUSED;
            case "suspended": return GuestInfo.RunState.SUSPENDED;
            default: return GuestInfo.RunState.UNKNOWN;
        }
    }

    // ---- VM/LXC controls ----

    /**
     * Starts a guest. Returns the UPID task ID for polling.
     * @param type "qemu" or "lxc"
     */
    public String startGuest(String nodeName, int vmid, GuestInfo.GuestType type,
                              AuthCredentials auth)
            throws ApiException, IOException {
        return startGuest(nodeName, vmid, type, auth, 15_000L);
    }

    public String startGuest(String nodeName, int vmid, GuestInfo.GuestType type,
                              AuthCredentials auth, long budgetMs)
            throws ApiException, IOException {
        if (budgetMs <= 0) {
            throw new ApiException(0, "Request deadline already expired before dispatch (" + budgetMs + "ms remaining)");
        }
        long deadlineNano = System.nanoTime() + budgetMs * 1_000_000L;
        return startGuestWithDeadline(nodeName, vmid, type, auth, deadlineNano);
    }

    public String startGuestWithDeadline(String nodeName, int vmid, GuestInfo.GuestType type,
                                         AuthCredentials auth, long deadlineNanos)
            throws ApiException, IOException {
        String path = guestPath(nodeName, vmid, type) + "/status/start";
        String response = executeWithAuthRetryWithDeadline(auth, deadlineNanos, (a, dl) -> client.postWithDeadline(path, "", a, dl));
        return parseUpid(response);
    }

    /**
     * Graceful shutdown. Returns UPID for polling.
     * Uses "shutdown" for QEMU (ACPI) and "shutdown" for LXC.
     */
    public String shutdownGuest(String nodeName, int vmid, GuestInfo.GuestType type,
                                 AuthCredentials auth)
            throws ApiException, IOException {
        return shutdownGuest(nodeName, vmid, type, auth, 15_000L);
    }

    public String shutdownGuest(String nodeName, int vmid, GuestInfo.GuestType type,
                                 AuthCredentials auth, long budgetMs)
            throws ApiException, IOException {
        if (budgetMs <= 0) {
            throw new ApiException(0, "Request deadline already expired before dispatch (" + budgetMs + "ms remaining)");
        }
        long deadlineNano = System.nanoTime() + budgetMs * 1_000_000L;
        return shutdownGuestWithDeadline(nodeName, vmid, type, auth, deadlineNano);
    }

    public String shutdownGuestWithDeadline(String nodeName, int vmid, GuestInfo.GuestType type,
                                            AuthCredentials auth, long deadlineNanos)
            throws ApiException, IOException {
        String path = guestPath(nodeName, vmid, type) + "/status/shutdown";
        String response = executeWithAuthRetryWithDeadline(auth, deadlineNanos, (a, dl) -> client.postWithDeadline(path, "", a, dl));
        return parseUpid(response);
    }

    /**
     * Reboot a running guest. Returns UPID for polling.
     */
    public String rebootGuest(String nodeName, int vmid, GuestInfo.GuestType type,
                               AuthCredentials auth)
            throws ApiException, IOException {
        return rebootGuest(nodeName, vmid, type, auth, 15_000L);
    }

    public String rebootGuest(String nodeName, int vmid, GuestInfo.GuestType type,
                               AuthCredentials auth, long budgetMs)
            throws ApiException, IOException {
        if (budgetMs <= 0) {
            throw new ApiException(0, "Request deadline already expired before dispatch (" + budgetMs + "ms remaining)");
        }
        long deadlineNano = System.nanoTime() + budgetMs * 1_000_000L;
        return rebootGuestWithDeadline(nodeName, vmid, type, auth, deadlineNano);
    }

    public String rebootGuestWithDeadline(String nodeName, int vmid, GuestInfo.GuestType type,
                                          AuthCredentials auth, long deadlineNanos)
            throws ApiException, IOException {
        String path = guestPath(nodeName, vmid, type) + "/status/reboot";
        String response = executeWithAuthRetryWithDeadline(auth, deadlineNanos, (a, dl) -> client.postWithDeadline(path, "", a, dl));
        return parseUpid(response);
    }

    /**
     * Force stop (hard power-off). Returns UPID for polling.
     * This is destructive — always require a second confirmation in the UI.
     */
    public String forceStopGuest(String nodeName, int vmid, GuestInfo.GuestType type,
                                  AuthCredentials auth)
            throws ApiException, IOException {
        return forceStopGuest(nodeName, vmid, type, auth, 15_000L);
    }

    public String forceStopGuest(String nodeName, int vmid, GuestInfo.GuestType type,
                                  AuthCredentials auth, long budgetMs)
            throws ApiException, IOException {
        if (budgetMs <= 0) {
            throw new ApiException(0, "Request deadline already expired before dispatch (" + budgetMs + "ms remaining)");
        }
        long deadlineNano = System.nanoTime() + budgetMs * 1_000_000L;
        return forceStopGuestWithDeadline(nodeName, vmid, type, auth, deadlineNano);
    }

    public String forceStopGuestWithDeadline(String nodeName, int vmid, GuestInfo.GuestType type,
                                             AuthCredentials auth, long deadlineNanos)
            throws ApiException, IOException {
        String path = guestPath(nodeName, vmid, type) + "/status/stop";
        String response = executeWithAuthRetryWithDeadline(auth, deadlineNanos, (a, dl) -> client.postWithDeadline(path, "", a, dl));
        return parseUpid(response);
    }

    public String stopGuest(String nodeName, int vmid, GuestInfo.GuestType type,
                             AuthCredentials auth, long budgetMs)
            throws ApiException, IOException {
        return forceStopGuest(nodeName, vmid, type, auth, budgetMs);
    }

    public String stopGuest(String nodeName, int vmid, GuestInfo.GuestType type,
                             AuthCredentials auth)
            throws ApiException, IOException {
        return forceStopGuest(nodeName, vmid, type, auth, 15_000L);
    }

    public String stopGuestWithDeadline(String nodeName, int vmid, GuestInfo.GuestType type,
                                        AuthCredentials auth, long deadlineNanos)
            throws ApiException, IOException {
        return forceStopGuestWithDeadline(nodeName, vmid, type, auth, deadlineNanos);
    }

    public String executeGuestPowerActionWithDeadline(String nodeName, int vmid, GuestInfo.GuestType type,
                                                      String action, AuthCredentials auth, long deadlineNanos)
            throws ApiException, IOException {
        if ("start".equals(action)) {
            return startGuestWithDeadline(nodeName, vmid, type, auth, deadlineNanos);
        } else if ("shutdown".equals(action)) {
            return shutdownGuestWithDeadline(nodeName, vmid, type, auth, deadlineNanos);
        } else if ("reboot".equals(action)) {
            return rebootGuestWithDeadline(nodeName, vmid, type, auth, deadlineNanos);
        } else if ("stop".equals(action)) {
            return forceStopGuestWithDeadline(nodeName, vmid, type, auth, deadlineNanos);
        } else {
            throw new ApiException(0, "Unsupported power action: " + action);
        }
    }

    public String executeGuestPowerAction(String nodeName, int vmid, GuestInfo.GuestType type,
                                          String action, AuthCredentials auth, long budgetMs)
            throws ApiException, IOException {
        if (budgetMs <= 0) {
            throw new ApiException(0, "Request deadline already expired before dispatch (" + budgetMs + "ms remaining)");
        }
        long deadlineNano = System.nanoTime() + budgetMs * 1_000_000L;
        return executeGuestPowerActionWithDeadline(nodeName, vmid, type, action, auth, deadlineNano);
    }

    // ---- Task polling ----

    /**
     * Polls task status once with default timeouts.
     */
    public TaskResult pollTask(String nodeName, String upid, AuthCredentials auth)
            throws ApiException, IOException {
        return pollTask(nodeName, upid, auth, 15_000L);
    }

    /**
     * Polls task status once with network timeout bound to remaining budget.
     */
    public TaskResult pollTask(String nodeName, String upid, AuthCredentials auth, long remainingBudgetMs)
            throws ApiException, IOException {
        if (remainingBudgetMs <= 0) {
            throw new ApiException(0, "Operation budget exhausted before task polling (" + remainingBudgetMs + "ms remaining)");
        }
        long deadlineNano = System.nanoTime() + remainingBudgetMs * 1_000_000L;
        return pollTaskWithDeadline(nodeName, upid, auth, deadlineNano);
    }

    public TaskResult pollTaskWithDeadline(String nodeName, String upid, AuthCredentials auth, long deadlineNanos)
            throws ApiException, IOException {
        if (deadlineNanos > 0 && System.nanoTime() >= deadlineNanos) {
            throw new ApiException(0, "Operation budget exhausted before task polling");
        }
        // URL-encode the UPID for the path
        String encodedUpid;
        try {
            encodedUpid = java.net.URLEncoder.encode(upid, "UTF-8");
        } catch (Exception e) {
            encodedUpid = upid;
        }
        String path = "/nodes/" + nodeName + "/tasks/" + encodedUpid + "/status";
        try {
            String response = executeWithAuthRetryWithDeadline(auth, deadlineNanos, (a, dl) -> client.getWithDeadline(path, a, dl));
            JSONObject data = JsonUtil.getDataObject(response);
            return parseTaskStatus(upid, data, nodeName);
        } catch (JSONException e) {
            throw new ApiException(0, "Failed to parse task status: " + e.getMessage());
        }
    }

    private TaskResult parseTaskStatus(String upid, JSONObject data, String nodeName)
            throws JSONException {
        String status = data.optString("status", "unknown");
        String exitStatus = data.has("exitstatus") && !data.isNull("exitstatus") ? data.optString("exitstatus") : null;
        long startTime = JsonUtil.optLong(data, "starttime", 0);
        long endTime = JsonUtil.optLong(data, "endtime", 0);

        TaskResult.TaskState state;
        if ("running".equalsIgnoreCase(status)) {
            state = TaskResult.TaskState.RUNNING;
        } else if ("stopped".equalsIgnoreCase(status)) {
            if (exitStatus == null || exitStatus.trim().isEmpty()) {
                state = TaskResult.TaskState.UNKNOWN;
            } else if ("OK".equalsIgnoreCase(exitStatus.trim())) {
                state = TaskResult.TaskState.OK;
            } else if (exitStatus.trim().matches("^WARNINGS:\\s*\\d+$")) {
                state = TaskResult.TaskState.WARNINGS;
            } else if (exitStatus.trim().startsWith("WARNINGS:")) {
                state = TaskResult.TaskState.UNKNOWN;
            } else {
                state = TaskResult.TaskState.ERROR;
            }
        } else {
            state = TaskResult.TaskState.UNKNOWN;
        }

        return new TaskResult(upid, state, exitStatus, null, startTime, endTime, nodeName);
    }

    // ---- Terminal (termproxy) ----

    /**
     * Creates a termproxy session for an LXC container.
     * Returns a JSONObject with "ticket", "port", "upid".
     *
     * Note: termproxy is ONLY available for LXC containers and node shells.
     * QEMU VMs use noVNC graphical console — NOT available via termproxy.
     */
    public JSONObject createLxcTermproxy(String nodeName, int vmid, AuthCredentials auth)
            throws ApiException, IOException {
        String path = "/nodes/" + nodeName + "/lxc/" + vmid + "/termproxy";
        String response = executeWithAuthRetry(auth, (a, rem) -> client.post(path, "", a));
        try {
            return JsonUtil.getDataObject(response);
        } catch (JSONException e) {
            throw new ApiException(0, "Failed to parse termproxy response: " + e.getMessage());
        }
    }

    /**
     * Creates a termproxy session for a node shell.
     * Returns JSONObject with "ticket", "port", "upid".
     */
    public JSONObject createNodeTermproxy(String nodeName, AuthCredentials auth)
            throws ApiException, IOException {
        String path = "/nodes/" + nodeName + "/termproxy";
        String response = executeWithAuthRetry(auth, (a, rem) -> client.post(path, "", a));
        try {
            return JsonUtil.getDataObject(response);
        } catch (JSONException e) {
            throw new ApiException(0, "Failed to parse termproxy response: " + e.getMessage());
        }
    }

    /**
     * Returns the VNC WebSocket URL for an LXC termproxy session.
     * The WebSocket URL format for PVE termproxy:
     * wss://{host}:{port}/api2/json/nodes/{node}/lxc/{vmid}/vncwebsocket?port={port}&vncticket={ticket}
     */
    public String getLxcVncWebsocketUrl(String nodeName, int vmid, int port, String vncTicket) {
        return "wss://" + config.host + ":" + config.port
                + "/api2/json/nodes/" + nodeName + "/lxc/" + vmid
                + "/vncwebsocket?port=" + port + "&vncticket=" + urlEncode(vncTicket);
    }

    /**
     * Returns the VNC WebSocket URL for a node shell termproxy session.
     */
    public String getNodeVncWebsocketUrl(String nodeName, int port, String vncTicket) {
        return "wss://" + config.host + ":" + config.port
                + "/api2/json/nodes/" + nodeName
                + "/vncwebsocket?port=" + port + "&vncticket=" + urlEncode(vncTicket);
    }

    // ---- Helpers ----

    private String guestPath(String nodeName, int vmid, GuestInfo.GuestType type) {
        String typeStr = type == GuestInfo.GuestType.QEMU ? "qemu" : "lxc";
        return "/nodes/" + nodeName + "/" + typeStr + "/" + vmid;
    }

    private String parseUpid(String response) throws ApiException {
        try {
            JSONObject root = new JSONObject(response);
            Object data = root.get("data");
            if (data instanceof String) {
                return (String) data;
            }
            throw new ApiException(0, "Unexpected task response format");
        } catch (JSONException e) {
            throw new ApiException(0, "Failed to parse UPID: " + e.getMessage());
        }
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
