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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    public static final String STORAGE_TYPE_UNAVAILABLE = "Type unavailable from this endpoint/permission";

    public interface TimeProvider {
        long monotonicTimeMs();
    }

    public static final TimeProvider SYSTEM_TIME_PROVIDER = () -> System.nanoTime() / 1_000_000L;

    private static volatile TimeProvider timeProvider = SYSTEM_TIME_PROVIDER;

    public static void setTimeProviderForTesting(TimeProvider provider) {
        timeProvider = (provider != null) ? provider : SYSTEM_TIME_PROVIDER;
    }

    public static void resetTimeProviderForTesting() {
        timeProvider = SYSTEM_TIME_PROVIDER;
    }

    public static long getMonotonicTimeMs() {
        return timeProvider.monotonicTimeMs();
    }

    public static class StorageCacheEntry {
        public final String type;
        public final long expiryTimeMs;

        public StorageCacheEntry(String type, long expiryTimeMs) {
            this.type = type;
            this.expiryTimeMs = expiryTimeMs;
        }

        public boolean isExpired(long nowMs) {
            return nowMs > expiryTimeMs;
        }
    }

    private static final int MAX_STORAGE_CACHE_SIZE = 256;
    public static final long STORAGE_POSITIVE_CACHE_TTL_MS = 3600_000L; // 1 hour for confirmed types
    public static final long STORAGE_NEGATIVE_CACHE_TTL_MS = 60_000L;   // 60 seconds for errors/missing

    // Shared global cache across driver instances (survives detail refresh re-instantiations)
    private static final java.util.Map<String, StorageCacheEntry> GLOBAL_STORAGE_TYPE_CACHE =
            Collections.synchronizedMap(
                    new java.util.LinkedHashMap<String, StorageCacheEntry>(MAX_STORAGE_CACHE_SIZE, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(java.util.Map.Entry<String, StorageCacheEntry> eldest) {
                            return size() > MAX_STORAGE_CACHE_SIZE;
                        }
                    }
            );

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

    public AuthCredentials authenticateWithDeadline(String username, String password, long deadlineNanos)
            throws ApiException, IOException {
        return apiClient.authenticateWithDeadline(username, password, deadlineNanos);
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
     * In a partial fleet scenario where some remotes fail and others succeed,
     * returns live resources from healthy remotes and cold-start placeholders for failed remotes.
     * Fails closed if all remotes reported errors or if whole-PDM HTTP call failed.
     */
    public List<NodeStatus> fetchAllNodes(AuthCredentials auth)
            throws ApiException, GeneralSecurityException, IOException {
        PdmFleetResult res = fetchFleetResources(auth);
        if (res.isAllFailed) {
            throw new ApiException(0, res.firstError != null ? res.firstError : "All PDM remotes reported errors");
        }
        List<NodeStatus> all = new ArrayList<>(res.nodes);
        all.addAll(res.placeholderNodes);
        return all;
    }

    /**
     * Typed representation of an individual PDM remote group within Vec<RemoteResources>.
     */
    public static final class PdmRemoteGroupResult {
        public final String remote;
        public final boolean isSuccess;
        public final String error;
        public final List<NodeStatus> nodes;
        public final List<GuestInfo> guests;
        public final List<StoragePool> storagePools;

        public PdmRemoteGroupResult(String remote, boolean isSuccess, String error,
                                    List<NodeStatus> nodes, List<GuestInfo> guests,
                                    List<StoragePool> storagePools) {
            this.remote = remote != null ? remote : "";
            this.isSuccess = isSuccess;
            this.error = error != null ? error : "";
            this.nodes = nodes != null ? Collections.unmodifiableList(nodes) : Collections.emptyList();
            this.guests = guests != null ? Collections.unmodifiableList(guests) : Collections.emptyList();
            this.storagePools = storagePools != null ? Collections.unmodifiableList(storagePools) : Collections.emptyList();
        }
    }

    /**
     * Explicit typed partial-fleet result scoping remote status, errors, and successful resources.
     */
    public static final class PdmFleetResult {
        public final List<PdmRemoteGroupResult> remoteGroups;
        public final List<NodeStatus> nodes;
        public final List<NodeStatus> placeholderNodes;
        public final List<GuestInfo> guests;
        public final List<StoragePool> storagePools;
        public final Set<String> successfulRemotes;
        public final Set<String> failedRemotes;
        public final Map<String, String> remoteErrors;
        public final boolean isAllFailed;
        public final boolean isPartialFailure;
        public final String firstError;

        public PdmFleetResult(
                List<PdmRemoteGroupResult> remoteGroups,
                List<NodeStatus> nodes,
                List<NodeStatus> placeholderNodes,
                List<GuestInfo> guests,
                List<StoragePool> storagePools,
                Set<String> successfulRemotes,
                Set<String> failedRemotes,
                Map<String, String> remoteErrors,
                boolean isAllFailed,
                boolean isPartialFailure,
                String firstError) {
            this.remoteGroups = remoteGroups != null ? Collections.unmodifiableList(remoteGroups) : Collections.emptyList();
            this.nodes = nodes != null ? Collections.unmodifiableList(nodes) : Collections.emptyList();
            this.placeholderNodes = placeholderNodes != null ? Collections.unmodifiableList(placeholderNodes) : Collections.emptyList();
            this.guests = guests != null ? Collections.unmodifiableList(guests) : Collections.emptyList();
            this.storagePools = storagePools != null ? Collections.unmodifiableList(storagePools) : Collections.emptyList();
            this.successfulRemotes = successfulRemotes != null ? Collections.unmodifiableSet(successfulRemotes) : Collections.emptySet();
            this.failedRemotes = failedRemotes != null ? Collections.unmodifiableSet(failedRemotes) : Collections.emptySet();
            this.remoteErrors = remoteErrors != null ? Collections.unmodifiableMap(remoteErrors) : Collections.emptyMap();
            this.isAllFailed = isAllFailed;
            this.isPartialFailure = isPartialFailure;
            this.firstError = firstError;
        }
    }

    /**
     * Fetches typed partial-fleet telemetry across all managed remotes.
     * Retains live resources from healthy remotes and captures remote-scoped errors without
     * discarding healthy sibling resources.
     */
    public PdmFleetResult fetchFleetResources(AuthCredentials auth)
            throws ApiException, GeneralSecurityException, IOException {
        return fetchFleetResources(auth, 15_000L);
    }

    public PdmFleetResult fetchFleetResources(AuthCredentials auth, long remainingBudgetMs)
            throws ApiException, GeneralSecurityException, IOException {
        if (remainingBudgetMs <= 0) {
            throw new ApiException(0, "Request deadline already expired before fetching PDM fleet resources (" + remainingBudgetMs + "ms remaining)");
        }
        long deadlineNanos = System.nanoTime() + remainingBudgetMs * 1_000_000L;
        return fetchFleetResourcesWithDeadline(auth, deadlineNanos);
    }

    public PdmFleetResult fetchFleetResourcesWithDeadline(AuthCredentials auth, long deadlineNanos)
            throws ApiException, GeneralSecurityException, IOException {
        if (deadlineNanos > 0 && System.nanoTime() >= deadlineNanos) {
            throw new ApiException(0, "Request deadline expired before fetching PDM fleet resources");
        }
        JSONArray groups = fetchResourcesGroupsWithDeadline(auth, deadlineNanos);

        List<PdmRemoteGroupResult> remoteGroups = new ArrayList<>();
        List<NodeStatus> successfulNodes = new ArrayList<>();
        List<NodeStatus> placeholderNodes = new ArrayList<>();
        List<GuestInfo> successfulGuests = new ArrayList<>();
        List<StoragePool> successfulStorage = new ArrayList<>();
        Set<String> successfulRemotes = new LinkedHashSet<>();
        Set<String> failedRemotes = new LinkedHashSet<>();
        Map<String, String> remoteErrors = new LinkedHashMap<>();
        String firstError = null;

        for (int i = 0; i < groups.length(); i++) {
            JSONObject group = groups.optJSONObject(i);
            if (group == null) continue;
            String remote = JsonUtil.optString(group, "remote", "");
            String error = JsonUtil.optString(group, "error", "");

            if (!error.isEmpty()) {
                failedRemotes.add(remote);
                remoteErrors.put(remote, error);
                if (firstError == null) {
                    firstError = "Remote '" + remote + "' reported error: " + error;
                }
                String serverId = config != null ? config.id : "pdm";
                NodeStatus placeholder = new NodeStatus(
                        remote, serverId, NodeStatus.Status.UNKNOWN,
                        Double.NaN, 0, -1L, -1L, -1L, -1L, -1L, -1L,
                        null, -1L, System.currentTimeMillis());
                placeholderNodes.add(placeholder);

                // Embedded resources under a failed remote group are strictly NOT treated as fresh
                remoteGroups.add(new PdmRemoteGroupResult(remote, false, error,
                        Collections.emptyList(), Collections.emptyList(), Collections.emptyList()));
            } else {
                successfulRemotes.add(remote);
                List<NodeStatus> groupNodes = new ArrayList<>();
                List<GuestInfo> groupGuests = new ArrayList<>();
                List<StoragePool> groupStorage = new ArrayList<>();

                JSONArray resources = group.optJSONArray("resources");
                if (resources != null) {
                    for (int j = 0; j < resources.length(); j++) {
                        JSONObject item = resources.optJSONObject(j);
                        if (item == null) continue;
                        String type = JsonUtil.optString(item, "type", "");
                        if ("pve-node".equalsIgnoreCase(type) || "node".equalsIgnoreCase(type)) {
                            NodeStatus n = parsePdmNode(item, remote, config);
                            groupNodes.add(n);
                            successfulNodes.add(n);
                        } else if ("pve-qemu".equalsIgnoreCase(type) || "qemu".equalsIgnoreCase(type)
                                || "pve-lxc".equalsIgnoreCase(type) || "lxc".equalsIgnoreCase(type)) {
                            GuestInfo g = parsePdmGuest(item, remote, config);
                            groupGuests.add(g);
                            successfulGuests.add(g);
                        } else if ("pve-storage".equalsIgnoreCase(type) || "storage".equalsIgnoreCase(type)) {
                            String itemNode = JsonUtil.optString(item, "node", "");
                            StoragePool pool = parsePdmStorageItem(item, remote, itemNode);
                            groupStorage.add(pool);
                            successfulStorage.add(pool);
                        }
                    }
                }
                remoteGroups.add(new PdmRemoteGroupResult(remote, true, null,
                        groupNodes, groupGuests, groupStorage));
            }
        }

        boolean isAllFailed = !failedRemotes.isEmpty() && successfulRemotes.isEmpty();
        boolean isPartialFailure = !failedRemotes.isEmpty() && !successfulRemotes.isEmpty();

        return new PdmFleetResult(
                remoteGroups, successfulNodes, placeholderNodes,
                successfulGuests, successfulStorage,
                successfulRemotes, failedRemotes, remoteErrors,
                isAllFailed, isPartialFailure, firstError);
    }

    /**
     * Helper to parse a single storage pool JSON item with positive type caching.
     */
    public StoragePool parsePdmStorageItem(JSONObject item, String remote, String itemNode) {
        String name = JsonUtil.optString(item, "storage", "unknown");
        String cacheKey = buildStorageCacheKey(remote, itemNode, name);
        StorageCacheEntry cachedEntry = GLOBAL_STORAGE_TYPE_CACHE.get(cacheKey);
        String storageType = (cachedEntry != null && !cachedEntry.isExpired(getMonotonicTimeMs()))
                ? cachedEntry.type : null;
        if (storageType == null) {
            storageType = JsonUtil.optString(item, "plugintype", "unknown");
        }
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

        String nodeIdentity = (itemNode == null || itemNode.isEmpty()) ? "" : (remote == null || remote.isEmpty() ? itemNode : remote + "/" + itemNode);
        return new StoragePool(name, storageType, maxdisk, disk, avail, active, true, shared, "", nodeIdentity);
    }

    /**
     * Discovers all virtual guests (QEMU VMs and LXC Containers) across all managed remotes.
     * Queries canonical GET /api2/json/resources/list returning Vec<RemoteResources>.
     * Preserves remote identity: guest node is formatted as "{remote}/{node}".
     */
    public List<GuestInfo> fetchAllGuests(AuthCredentials auth)
            throws ApiException, GeneralSecurityException, IOException {
        return fetchAllGuests(auth, 15_000L);
    }

    public List<GuestInfo> fetchAllGuests(AuthCredentials auth, long remainingBudgetMs)
            throws ApiException, GeneralSecurityException, IOException {
        if (remainingBudgetMs <= 0) {
            throw new ApiException(0, "Request deadline already expired before fetching guests (" + remainingBudgetMs + "ms remaining)");
        }
        long deadlineNanos = System.nanoTime() + remainingBudgetMs * 1_000_000L;
        return fetchAllGuestsWithDeadline(auth, deadlineNanos);
    }

    public List<GuestInfo> fetchAllGuestsWithDeadline(AuthCredentials auth, long deadlineNanos)
            throws ApiException, GeneralSecurityException, IOException {
        PdmFleetResult res = fetchFleetResourcesWithDeadline(auth, deadlineNanos);
        if (res.isAllFailed) {
            throw new ApiException(0, res.firstError != null ? res.firstError : "All PDM remotes reported errors");
        }
        return res.guests;
    }

    /**
     * Fetches hosted guests on a specific node preserving exact remote/node identity.
     * Scoped to the target remote: unrelated sibling remote failures do NOT poison healthy queries.
     * If the selected target remote failed, fails closed with an ApiException.
     */
    public List<GuestInfo> fetchNodeGuests(String nodeName, AuthCredentials auth)
            throws ApiException, GeneralSecurityException, IOException {
        if (nodeName == null || nodeName.trim().isEmpty()) return Collections.emptyList();
        String targetRemote = extractRemote(nodeName);

        JSONArray groups = fetchResourcesGroups(auth);
        List<GuestInfo> matched = new ArrayList<>();

        for (int i = 0; i < groups.length(); i++) {
            JSONObject group = groups.optJSONObject(i);
            if (group == null) continue;
            String remote = JsonUtil.optString(group, "remote", "");

            boolean matches = targetRemote.isEmpty() || targetRemote.equals(remote);
            if (matches) {
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
                        GuestInfo g = parsePdmGuest(item, remote, config);
                        if (nodeName.equals(g.node)) {
                            matched.add(g);
                        }
                    }
                }
            }
        }
        return matched;
    }

    /**
     * Fetches configured storage pools for a specific node from PDM resource inventory.
     * Preserves exact remote/node identity ({remote}/{node}) to avoid mixing storage pools.
     * Scoped to target remote: unrelated sibling remote errors do NOT throw.
     * Selected remote failure fails closed with ApiException.
     */
    public List<StoragePool> fetchNodeStorage(String nodeName, AuthCredentials auth)
            throws ApiException, GeneralSecurityException, IOException {
        if (nodeName == null || nodeName.trim().isEmpty()) return Collections.emptyList();
        String targetRemote = extractRemote(nodeName);

        JSONArray groups = fetchResourcesGroups(auth);
        List<StoragePool> pools = new ArrayList<>();

        for (int i = 0; i < groups.length(); i++) {
            JSONObject group = groups.optJSONObject(i);
            if (group == null) continue;
            String remote = JsonUtil.optString(group, "remote", "");

            boolean matches = targetRemote.isEmpty() || targetRemote.equals(remote);
            if (matches) {
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
                            pools.add(parsePdmStorageItem(item, remote, itemNode));
                        }
                    }
                }
            }
        }
        return pools;
    }

    /**
     * Fetches detailed guest status for a specific VMID, node, and guest type across the PDM resource tree.
     * Preserves exact identity: matches vmid, guestType (QEMU vs LXC), and exact node identity ({remote}/{node}).
     * Scoped to the target remote: unrelated sibling failures do NOT poison healthy queries.
     * Selected target remote failure fails closed by throwing ApiException.
     */
    public GuestInfo fetchGuestDetail(String nodeName, int vmid, GuestInfo.GuestType guestType, AuthCredentials auth)
            throws ApiException, GeneralSecurityException, IOException {
        return fetchGuestDetail(nodeName, vmid, guestType, auth, 15_000L);
    }

    public GuestInfo fetchGuestDetail(String nodeName, int vmid, GuestInfo.GuestType guestType, AuthCredentials auth, long remainingBudgetMs)
            throws ApiException, GeneralSecurityException, IOException {
        if (remainingBudgetMs <= 0) {
            throw new ApiException(0, "Request deadline already expired before fetching guest detail (" + remainingBudgetMs + "ms remaining)");
        }
        long deadlineNanos = System.nanoTime() + remainingBudgetMs * 1_000_000L;
        return fetchGuestDetailWithDeadline(nodeName, vmid, guestType, auth, deadlineNanos);
    }

    public GuestInfo fetchGuestDetailWithDeadline(String nodeName, int vmid, GuestInfo.GuestType guestType, AuthCredentials auth, long deadlineNanos)
            throws ApiException, GeneralSecurityException, IOException {
        if (guestType == null) {
            return null; // Reject wildcard: guestType is strictly required
        }
        if (deadlineNanos > 0 && System.nanoTime() >= deadlineNanos) {
            throw new ApiException(0, "Request deadline expired before fetching guest detail");
        }
        if (nodeName == null || nodeName.trim().isEmpty()) {
            return null;
        }
        String targetRemote = extractRemote(nodeName);

        JSONArray groups = fetchResourcesGroupsWithDeadline(auth, deadlineNanos);
        for (int i = 0; i < groups.length(); i++) {
            JSONObject group = groups.optJSONObject(i);
            if (group == null) continue;
            String remote = JsonUtil.optString(group, "remote", "");

            boolean matches = targetRemote.isEmpty() || targetRemote.equals(remote);
            if (matches) {
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
                        GuestInfo g = parsePdmGuest(item, remote, config);
                        if (g.vmid == vmid && g.type == guestType && nodeName.equals(g.node)) {
                            return g;
                        }
                    }
                }
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
        return fetchResourcesGroups(auth, 15_000L);
    }

    private JSONArray fetchResourcesGroups(AuthCredentials auth, long remainingBudgetMs)
            throws ApiException, GeneralSecurityException, IOException {
        if (remainingBudgetMs <= 0) {
            throw new ApiException(0, "Request deadline already expired before fetching resources (" + remainingBudgetMs + "ms remaining)");
        }
        long deadlineNanos = System.nanoTime() + remainingBudgetMs * 1_000_000L;
        return fetchResourcesGroupsWithDeadline(auth, deadlineNanos);
    }

    private JSONArray fetchResourcesGroupsWithDeadline(AuthCredentials auth, long deadlineNanos)
            throws ApiException, GeneralSecurityException, IOException {
        String response = executeWithAuthRetryWithDeadline(PATH_RESOURCES_LIST, "GET", null, auth, deadlineNanos);
        try {
            return JsonUtil.getDataArray(response);
        } catch (JSONException e) {
            throw new ApiException(0, "Unexpected PDM resources response shape: " + e.getMessage());
        }
    }

    // ---- Remote & Node Identity Helpers ----

    public static String extractRemote(String nodeIdentity) {
        if (nodeIdentity == null) return "";
        int slash = nodeIdentity.indexOf('/');
        return (slash > 0) ? nodeIdentity.substring(0, slash) : "";
    }

    public static String extractNode(String nodeIdentity) {
        if (nodeIdentity == null) return "";
        int slash = nodeIdentity.indexOf('/');
        return (slash >= 0) ? nodeIdentity.substring(slash + 1) : nodeIdentity;
    }

    public static boolean isValidIdentifier(String id) {
        if (id == null || id.isEmpty()) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '-' && c != '_' && c != '.') {
                return false;
            }
        }
        return true;
    }

    public static String extractRemoteFromUpid(String remoteUpid) {
        if (remoteUpid == null) return "";
        int bang = remoteUpid.indexOf('!');
        if (bang <= 0) return "";
        String prefix = remoteUpid.substring(0, bang);
        int colon = prefix.indexOf(':');
        return (colon >= 0) ? prefix.substring(colon + 1) : prefix;
    }

    /**
     * Fail-closed client binding verification for PDM RemoteUpid.
     * Mirrors upstream tasks.rs verify_upid(remote, RemoteType::Pve, upid).
     * Rejects UPID type mismatches (must be 'pve') and remote target mismatches before network.
     * Enforces exact opaque token identity without whitespace normalization or trimming.
     */
    public static void verifyRemoteUpidBinding(String expectedRemote, String upid) throws ApiException {
        if (upid == null || upid.isEmpty()) {
            throw new ApiException(0, "Cannot verify empty task UPID");
        }
        int bang = upid.indexOf('!');
        if (bang <= 0) {
            throw new ApiException(0, "Invalid PDM RemoteUpid format: missing '!' separator: " + upid);
        }
        String prefix = upid.substring(0, bang);
        int colon = prefix.indexOf(':');
        if (colon <= 0) {
            throw new ApiException(0, "Invalid PDM RemoteUpid format: missing ':' remote-type separator: " + upid);
        }
        String remoteType = prefix.substring(0, colon);
        String upidRemote = prefix.substring(colon + 1);

        if (!"pve".equals(remoteType)) {
            throw new ApiException(0, "Task RemoteUpid type mismatch: expected 'pve', found '" + remoteType + "'");
        }
        if (expectedRemote != null && !expectedRemote.isEmpty()) {
            if (!expectedRemote.equals(upidRemote)) {
                throw new ApiException(0, "Task RemoteUpid remote mismatch: expected '" + expectedRemote +
                        "', but UPID specifies '" + upidRemote + "'");
            }
        }
    }

    // ---- Official Upstream PDM Guest Power Actions ----

    /**
     * Dispatches a guest power action via official PDM REST API.
     * Upstream routes:
     * - QEMU: POST /api2/json/pve/remotes/{remote}/qemu/{vmid}/{action}
     * - LXC:  POST /api2/json/pve/remotes/{remote}/lxc/{vmid}/{action}
     *
     * Returns RemoteUpid string returned by PDM (e.g. "pve:cluster-west!UPID:...").
     *
     * Guarantees:
     * - Explicit target identity validation (node, remote, type, VMID) strictly required before network.
     * - Never blindly retries state-changing POST on 401 or timeout.
     * - Single monotonic budget.
     * - Reboot is honestly rejected with unsupported capability disclosure.
     */
    public String executeGuestPowerAction(String remote, String nodeName, int vmid,
                                          GuestInfo.GuestType type, String action,
                                          AuthCredentials auth, long budgetMs)
            throws ApiException, GeneralSecurityException, IOException {
        if (budgetMs <= 0) {
            throw new ApiException(0, "Operation budget exhausted before dispatch (" + budgetMs + "ms remaining)");
        }
        long deadlineNanos = System.nanoTime() + budgetMs * 1_000_000L;
        return executeGuestPowerActionWithDeadline(remote, nodeName, vmid, type, action, auth, deadlineNanos);
    }

    public String executeGuestPowerActionWithDeadline(String remote, String nodeName, int vmid,
                                                      GuestInfo.GuestType type, String action,
                                                      AuthCredentials auth, long deadlineNanos)
            throws ApiException, GeneralSecurityException, IOException {
        if (remote == null || remote.isEmpty()) {
            throw new ApiException(0, "Cannot execute PDM guest power action: missing remote cluster identifier");
        }
        if (nodeName == null || nodeName.isEmpty()) {
            throw new ApiException(0, "Cannot execute PDM guest power action: missing node identifier");
        }
        if (type == null) {
            throw new ApiException(0, "Cannot execute PDM guest power action: missing guest type");
        }
        if (vmid <= 0) {
            throw new ApiException(0, "Cannot execute PDM guest power action: invalid VMID: " + vmid);
        }
        if (action == null || action.isEmpty()) {
            throw new ApiException(0, "Power action cannot be empty");
        }

        if (!isValidIdentifier(remote)) {
            throw new ApiException(0, "Invalid remote identifier: '" + remote + "'");
        }
        if (!isValidIdentifier(nodeName)) {
            throw new ApiException(0, "Invalid node identifier: '" + nodeName + "'");
        }

        String actionNorm = action.toLowerCase(java.util.Locale.ROOT);
        if ("reboot".equals(actionNorm)) {
            throw new ApiException(0, "Guest Reboot is not supported by the Proxmox Datacenter Manager API. " +
                    "Upstream PDM provides Start, Graceful Shutdown, and Stop endpoints only. Please perform a Graceful Shutdown followed by Start.");
        }
        if (!"start".equals(actionNorm) && !"shutdown".equals(actionNorm) && !"stop".equals(actionNorm)) {
            throw new ApiException(0, "Unsupported PDM guest power action: '" + action + "'. Supported actions: start, shutdown, stop.");
        }

        if (deadlineNanos > 0 && System.nanoTime() >= deadlineNanos) {
            throw new ApiException(0, "Operation budget exhausted before dispatch");
        }

        String typeStr = (type == GuestInfo.GuestType.LXC) ? "lxc" : "qemu";
        String encodedRemote = java.net.URLEncoder.encode(remote, "UTF-8");
        String path = "/pve/remotes/" + encodedRemote + "/" + typeStr + "/" + vmid + "/" + actionNorm;

        String formBody = "node=" + java.net.URLEncoder.encode(nodeName, "UTF-8");

        String response = executePostWithoutRetryWithDeadline(path, formBody, auth, deadlineNanos);
        try {
            JSONObject json = new JSONObject(response);
            if (json.has("data") && !json.isNull("data")) {
                String upid = json.getString("data");
                verifyRemoteUpidBinding(remote, upid);
                return upid;
            }
            throw new ApiException(0, "Missing data in PDM power action response: " + response);
        } catch (JSONException e) {
            throw new ApiException(0, "Failed to parse PDM power action response: " + e.getMessage());
        }
    }

    public String executeGuestPowerAction(String nodeIdentity, int vmid,
                                          GuestInfo.GuestType type, String action,
                                          AuthCredentials auth, long budgetMs)
            throws ApiException, GeneralSecurityException, IOException {
        String remote = extractRemote(nodeIdentity);
        String node = extractNode(nodeIdentity);
        if (remote.isEmpty() || node.isEmpty()) {
            throw new ApiException(0, "Cannot execute PDM guest power action: composite nodeIdentity '" +
                    nodeIdentity + "' must include both remote cluster and node name (e.g. 'remote/node')");
        }
        return executeGuestPowerAction(remote, node, vmid, type, action, auth, budgetMs);
    }

    public String executeGuestPowerActionWithDeadline(String nodeIdentity, int vmid,
                                                      GuestInfo.GuestType type, String action,
                                                      AuthCredentials auth, long deadlineNanos)
            throws ApiException, GeneralSecurityException, IOException {
        String remote = extractRemote(nodeIdentity);
        String node = extractNode(nodeIdentity);
        if (remote.isEmpty() || node.isEmpty()) {
            throw new ApiException(0, "Cannot execute PDM guest power action: composite nodeIdentity '" +
                    nodeIdentity + "' must include both remote cluster and node name (e.g. 'remote/node')");
        }
        return executeGuestPowerActionWithDeadline(remote, node, vmid, type, action, auth, deadlineNanos);
    }

    public String startGuest(String nodeIdentity, int vmid, GuestInfo.GuestType type, AuthCredentials auth, long budgetMs)
            throws ApiException, GeneralSecurityException, IOException {
        return executeGuestPowerAction(nodeIdentity, vmid, type, "start", auth, budgetMs);
    }

    public String startGuestWithDeadline(String nodeIdentity, int vmid, GuestInfo.GuestType type, AuthCredentials auth, long deadlineNanos)
            throws ApiException, GeneralSecurityException, IOException {
        return executeGuestPowerActionWithDeadline(nodeIdentity, vmid, type, "start", auth, deadlineNanos);
    }

    public String shutdownGuest(String nodeIdentity, int vmid, GuestInfo.GuestType type, AuthCredentials auth, long budgetMs)
            throws ApiException, GeneralSecurityException, IOException {
        return executeGuestPowerAction(nodeIdentity, vmid, type, "shutdown", auth, budgetMs);
    }

    public String shutdownGuestWithDeadline(String nodeIdentity, int vmid, GuestInfo.GuestType type, AuthCredentials auth, long deadlineNanos)
            throws ApiException, GeneralSecurityException, IOException {
        return executeGuestPowerActionWithDeadline(nodeIdentity, vmid, type, "shutdown", auth, deadlineNanos);
    }

    public String forceStopGuest(String nodeIdentity, int vmid, GuestInfo.GuestType type, AuthCredentials auth, long budgetMs)
            throws ApiException, GeneralSecurityException, IOException {
        return executeGuestPowerAction(nodeIdentity, vmid, type, "stop", auth, budgetMs);
    }

    public String forceStopGuestWithDeadline(String nodeIdentity, int vmid, GuestInfo.GuestType type, AuthCredentials auth, long deadlineNanos)
            throws ApiException, GeneralSecurityException, IOException {
        return executeGuestPowerActionWithDeadline(nodeIdentity, vmid, type, "stop", auth, deadlineNanos);
    }

    public String rebootGuest(String nodeIdentity, int vmid, GuestInfo.GuestType type, AuthCredentials auth, long budgetMs)
            throws ApiException {
        throw new ApiException(0, "Guest Reboot is not supported by the Proxmox Datacenter Manager API. " +
                "Upstream PDM provides Start, Graceful Shutdown, and Stop endpoints only. Please perform a Graceful Shutdown followed by Start.");
    }

    public String rebootGuestWithDeadline(String nodeIdentity, int vmid, GuestInfo.GuestType type, AuthCredentials auth, long deadlineNanos)
            throws ApiException {
        throw new ApiException(0, "Guest Reboot is not supported by the Proxmox Datacenter Manager API. " +
                "Upstream PDM provides Start, Graceful Shutdown, and Stop endpoints only. Please perform a Graceful Shutdown followed by Start.");
    }

    /** Backward-compatible execution + polling helper carrying one monotonic budget */
    public TaskResult executeGuestPowerAction(String node, String guestType, int vmid, String action, AuthCredentials auth)
            throws Exception {
        if (guestType == null) {
            throw new ApiException(0, "Cannot execute PDM guest power action: missing guest type");
        }
        GuestInfo.GuestType type;
        if ("lxc".equalsIgnoreCase(guestType)) {
            type = GuestInfo.GuestType.LXC;
        } else if ("qemu".equalsIgnoreCase(guestType)) {
            type = GuestInfo.GuestType.QEMU;
        } else {
            throw new ApiException(0, "Cannot execute PDM guest power action: invalid or unsupported guest type '" + guestType + "'");
        }

        long totalBudgetMs = 60_000L;
        long startNanos = System.nanoTime();
        long deadlineNanos = startNanos + totalBudgetMs * 1_000_000L;

        long dispatchBudgetMs = (deadlineNanos - System.nanoTime()) / 1_000_000L;
        if (dispatchBudgetMs <= 0) {
            throw new ApiException(0, "Operation budget exhausted before dispatch");
        }

        String upid = executeGuestPowerAction(node, vmid, type, action, auth, dispatchBudgetMs);

        long pollBudgetMs = (deadlineNanos - System.nanoTime()) / 1_000_000L;
        if (pollBudgetMs <= 0) {
            throw new ApiException(0, "Operation budget exhausted before task polling");
        }

        return TaskPoller.getInstance().pollUntilFinished(
                (ActionGuard.TaskLifecycleOwner) null, node, upid, auth,
                (nodeName, taskId, creds, curBudget) -> pollTask(nodeName, taskId, creds, curBudget),
                null, pollBudgetMs, 1000L);
    }

    // ---- Official Upstream PDM Task Status Polling ----

    /**
     * Polls a remote PVE task status through PDM.
     * Route: GET /api2/json/pve/remotes/{remote}/tasks/{encodedUpid}/status
     *
     * Accepts either remote name ("cluster-west") or node identity ("cluster-west/pve-node-01").
     * Enforces fail-closed RemoteUpid validation before network.
     * This is an idempotent GET request. If session ticket has expired (401),
     * a single ticket refresh retry within the remaining budget is permitted.
     */
    public TaskResult pollTask(String remoteOrNodeIdentity, String upid, AuthCredentials auth, long remainingBudgetMs)
            throws ApiException, GeneralSecurityException, IOException {
        if (remainingBudgetMs <= 0) {
            throw new ApiException(0, "Operation budget exhausted before task polling (" + remainingBudgetMs + "ms remaining)");
        }
        long deadlineNanos = System.nanoTime() + remainingBudgetMs * 1_000_000L;
        return pollTaskWithDeadline(remoteOrNodeIdentity, upid, auth, deadlineNanos);
    }

    public TaskResult pollTaskWithDeadline(String remoteOrNodeIdentity, String upid, AuthCredentials auth, long deadlineNanos)
            throws ApiException, GeneralSecurityException, IOException {
        if (deadlineNanos > 0 && System.nanoTime() >= deadlineNanos) {
            throw new ApiException(0, "Operation budget exhausted before task polling");
        }
        String remote = extractRemote(remoteOrNodeIdentity);
        if (remote.isEmpty()) {
            if (remoteOrNodeIdentity != null && !remoteOrNodeIdentity.isEmpty()) {
                remote = remoteOrNodeIdentity;
            } else if (upid != null) {
                remote = extractRemoteFromUpid(upid);
            }
        }
        if (remote == null || remote.isEmpty()) {
            throw new ApiException(0, "Cannot poll PDM task: missing remote identifier");
        }
        if (upid == null || upid.isEmpty()) {
            throw new ApiException(0, "Cannot poll PDM task: missing task UPID");
        }

        // Upstream verify_upid fail-closed client binding
        verifyRemoteUpidBinding(remote, upid);

        String encodedRemote = java.net.URLEncoder.encode(remote, "UTF-8");
        String encodedUpid = java.net.URLEncoder.encode(upid, "UTF-8");
        String path = "/pve/remotes/" + encodedRemote + "/tasks/" + encodedUpid + "/status";

        String resp = executeWithAuthRetryWithDeadline(path, "GET", null, auth, deadlineNanos);
        try {
            JSONObject data = JsonUtil.getDataObject(resp);
            return parseTaskStatus(upid, data, remote);
        } catch (JSONException e) {
            throw new ApiException(0, "Failed to parse PDM task status: " + e.getMessage());
        }
    }

    public TaskResult pollTask(String remoteOrNodeIdentity, String upid, AuthCredentials auth)
            throws ApiException, GeneralSecurityException, IOException {
        return pollTask(remoteOrNodeIdentity, upid, auth, 15_000L);
    }

    private TaskResult parseTaskStatus(String upid, JSONObject data, String fallbackNode) {
        String status = data.optString("status", "unknown");
        String exitStatus = data.has("exitstatus") && !data.isNull("exitstatus") ? data.optString("exitstatus") : null;
        long startTime = JsonUtil.optLong(data, "starttime", 0);
        long endTime = JsonUtil.optLong(data, "endtime", 0);
        String taskNode = JsonUtil.optString(data, "node", fallbackNode);

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

        return new TaskResult(upid, state, exitStatus, null, startTime, endTime, taskNode);
    }

    // ---- Storage Status & Backend Type Enrichment ----

    /**
     * Encodes a tuple component safely using length prefixing to prevent delimiter collision.
     */
    private static String encodeComponent(String s) {
        if (s == null) {
            return "-1:";
        }
        return s.length() + ":" + s;
    }

    /**
     * Builds a server-scoped collision-proof cache key for storage backend types.
     * Prevents cross-contamination between distinct PDM endpoints having identical
     * remote, node, or storage names.
     */
    private String buildStorageCacheKey(String remote, String nodeName, String storage) {
        String serverId = (config != null && config.id != null && !config.id.trim().isEmpty())
                ? config.id
                : (config != null ? (config.host + ":" + config.port) : "default");
        return "storage[" +
                encodeComponent(serverId) + "," +
                encodeComponent(remote != null ? remote : "") + "," +
                encodeComponent(nodeName != null ? nodeName : "") + "," +
                encodeComponent(storage != null ? storage : "") + "]";
    }

    /**
     * Queries detailed storage status for a storage pool on a specific node.
     * Route: GET /api2/json/pve/remotes/{remote}/nodes/{node}/storage/{storage}/status
     *
     * Returns backend storage type ("dir", "lvmthin", "zfspool", "nfs", etc.)
     * from pve_api_types::StorageStatus, or explicit unavailable string on 403/404/older PDM.
     *
     * Cached in bounded storageTypeCache (max 256 entries).
     */
    public String fetchStorageType(String remote, String nodeName, String storage,
                                   AuthCredentials auth, long budgetMs) {
        if (remote == null || remote.isEmpty() || nodeName == null || nodeName.isEmpty() || storage == null || storage.isEmpty()) {
            return STORAGE_TYPE_UNAVAILABLE;
        }

        String cacheKey = buildStorageCacheKey(remote, nodeName, storage);
        long now = getMonotonicTimeMs();
        StorageCacheEntry cached = GLOBAL_STORAGE_TYPE_CACHE.get(cacheKey);
        if (cached != null && !cached.isExpired(now)) {
            return cached.type;
        }

        if (budgetMs <= 0) {
            return STORAGE_TYPE_UNAVAILABLE + " (budget exhausted)";
        }

        try {
            String encodedRemote = java.net.URLEncoder.encode(remote, "UTF-8");
            String encodedNode = java.net.URLEncoder.encode(nodeName, "UTF-8");
            String encodedStorage = java.net.URLEncoder.encode(storage, "UTF-8");
            String path = "/pve/remotes/" + encodedRemote + "/nodes/" + encodedNode + "/storage/" + encodedStorage + "/status";

            String resp = executeWithAuthRetry(path, "GET", null, auth, budgetMs > 0 ? budgetMs : 5_000L);
            JSONObject data = JsonUtil.getDataObject(resp);
            String backendType = JsonUtil.optString(data, "type", "");
            if (!backendType.isEmpty()) {
                GLOBAL_STORAGE_TYPE_CACHE.put(cacheKey, new StorageCacheEntry(backendType, now + STORAGE_POSITIVE_CACHE_TTL_MS));
                return backendType;
            } else {
                GLOBAL_STORAGE_TYPE_CACHE.put(cacheKey, new StorageCacheEntry(STORAGE_TYPE_UNAVAILABLE, now + STORAGE_NEGATIVE_CACHE_TTL_MS));
                return STORAGE_TYPE_UNAVAILABLE;
            }
        } catch (ApiException e) {
            String reason = (e.getHttpCode() == 403) ? " (403 Forbidden)"
                    : (e.getHttpCode() == 404) ? " (404 Not Found)" : "";
            String result = STORAGE_TYPE_UNAVAILABLE + reason;
            GLOBAL_STORAGE_TYPE_CACHE.put(cacheKey, new StorageCacheEntry(result, now + STORAGE_NEGATIVE_CACHE_TTL_MS));
            return result;
        } catch (Exception e) {
            String result = STORAGE_TYPE_UNAVAILABLE;
            GLOBAL_STORAGE_TYPE_CACHE.put(cacheKey, new StorageCacheEntry(result, now + STORAGE_NEGATIVE_CACHE_TTL_MS));
            return result;
        }
    }

    public List<StoragePool> enrichStoragePools(String nodeIdentity, List<StoragePool> pools, AuthCredentials auth) {
        return enrichStoragePools(nodeIdentity, pools, auth, 10_000L);
    }

    public List<StoragePool> enrichStoragePools(String nodeIdentity, List<StoragePool> pools, AuthCredentials auth, long aggregateBudgetMs) {
        if (pools == null || pools.isEmpty()) return pools;
        String remote = extractRemote(nodeIdentity);
        String node = extractNode(nodeIdentity);
        List<StoragePool> enriched = new ArrayList<>(pools.size());

        // Strictly honor exhausted budget: if aggregateBudgetMs <= 0, do not reset to fallback.
        // Instead, resolve pools from existing positive cache only without making network calls.
        if (aggregateBudgetMs <= 0) {
            for (StoragePool p : pools) {
                String backendType = fetchStorageType(remote, node, p.name, auth, 0L);
                enriched.add(new StoragePool(p.name, backendType, p.total, p.used, p.avail, p.active, p.enabled, p.shared, p.content, p.node));
            }
            return enriched;
        }

        long deadlineMs = getMonotonicTimeMs() + aggregateBudgetMs;
        for (StoragePool p : pools) {
            long remaining = deadlineMs - getMonotonicTimeMs();
            long poolBudget = remaining > 0 ? Math.min(remaining, 5_000L) : 0L;
            String backendType = fetchStorageType(remote, node, p.name, auth, poolBudget);
            enriched.add(new StoragePool(p.name, backendType, p.total, p.used, p.avail, p.active, p.enabled, p.shared, p.content, p.node));
        }
        return enriched;
    }

    public static void clearStorageCache() {
        GLOBAL_STORAGE_TYPE_CACHE.clear();
    }

    public void clearCache() {
        clearStorageCache();
    }

    /**
     * Executes a state-changing POST request strictly WITHOUT retry.
     * Enforces the security guarantee that ambiguous outcomes (401 or timeout)
     * are NEVER blindly retried on state-changing endpoints.
     */
    private String executePostWithoutRetry(String path, String formBody, AuthCredentials auth, long budgetMs)
            throws ApiException, GeneralSecurityException, IOException {
        if (budgetMs <= 0) {
            throw new ApiException(0, "Request deadline exceeded before dispatch (" + budgetMs + "ms limit)");
        }
        long deadlineNanos = System.nanoTime() + budgetMs * 1_000_000L;
        return executePostWithoutRetryWithDeadline(path, formBody, auth, deadlineNanos);
    }

    private String executePostWithoutRetryWithDeadline(String path, String formBody, AuthCredentials auth, long deadlineNanos)
            throws ApiException, GeneralSecurityException, IOException {
        if (deadlineNanos > 0 && System.nanoTime() >= deadlineNanos) {
            throw new ApiException(0, "Request deadline exceeded before dispatch");
        }
        try {
            return apiClient.postWithDeadline(path, formBody != null ? formBody : "", auth, deadlineNanos);
        } catch (ApiException e) {
            if (e.getHttpCode() == 403) {
                throw new ApiException(403, "Permission denied (403): User lacks required PDM privilege for " + path);
            }
            if (e.getHttpCode() == 401) {
                throw new ApiException(401, "Authentication failed (401): Ticket expired or invalid. Action was NOT retried to prevent ambiguous state.");
            }
            throw e;
        }
    }

    // ---- Explicit Fail-Closed Guards: Terminal Streaming ----

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
        return "PDM Supported: Real Proxmox Datacenter Manager fleet discovery, inventory monitoring, and guest VM/LXC power actions (Start, Shutdown, Stop) active. " +
                "Terminal streaming is disabled in this release to prevent endpoint misrouting.";
    }

    // ---- Transport & Auth Retry Logic ----

    private String executeWithAuthRetry(String path, String method, String formBody, AuthCredentials auth)
            throws ApiException, GeneralSecurityException, IOException {
        return executeWithAuthRetry(path, method, formBody, auth, 15_000L);
    }

    private String executeWithAuthRetry(String path, String method, String formBody, AuthCredentials auth, long budgetMs)
            throws ApiException, GeneralSecurityException, IOException {
        if (budgetMs <= 0) {
            throw new ApiException(0, "Request deadline already expired before dispatch (" + budgetMs + "ms remaining)");
        }
        long deadlineNano = System.nanoTime() + budgetMs * 1_000_000L;
        return executeWithAuthRetryWithDeadline(path, method, formBody, auth, deadlineNano);
    }

    private String executeWithAuthRetryWithDeadline(String path, String method, String formBody, AuthCredentials auth, long deadlineNano)
            throws ApiException, GeneralSecurityException, IOException {
        if (deadlineNano > 0 && System.nanoTime() >= deadlineNano) {
            throw new ApiException(0, "Request deadline exceeded before dispatch");
        }
        AuthCredentials currentAuth = auth;
        try {
            if ("POST".equalsIgnoreCase(method)) {
                return apiClient.postWithDeadline(path, formBody != null ? formBody : "", currentAuth, deadlineNano);
            } else {
                return apiClient.getWithDeadline(path, currentAuth, deadlineNano);
            }
        } catch (ApiException e) {
            // Strict 401 vs 403 differentiation:
            // 403 Forbidden is surfaced directly as permission denied without looping or invalidating session.
            if (e.getHttpCode() == 403) {
                throw new ApiException(403, "Permission denied (403): User lacks required PDM privilege for " + path);
            }

            // 401 Unauthorized: Attempt exactly one session ticket refresh if sessionManager is available
            if (e.getHttpCode() == 401 && sessionManager != null && currentAuth != null && !currentAuth.isApiToken()) {
                if (deadlineNano > 0 && System.nanoTime() >= deadlineNano) {
                    throw new ApiException(0, "Request deadline exceeded before ticket refresh");
                }
                AuthCredentials fresh = sessionManager.refreshSessionWithDeadline(config, deadlineNano);
                if (fresh != null) {
                    if (deadlineNano > 0 && System.nanoTime() >= deadlineNano) {
                        throw new ApiException(0, "Request deadline exceeded after ticket refresh");
                    }
                    if ("POST".equalsIgnoreCase(method)) {
                        return apiClient.postWithDeadline(path, formBody != null ? formBody : "", fresh, deadlineNano);
                    } else {
                        return apiClient.getWithDeadline(path, fresh, deadlineNano);
                    }
                }
            }
            throw e;
        }
    }
}
