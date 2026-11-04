// SPDX-License-Identifier: GPL-3.0-or-later
// PVE Pocket — Node Storage Pool Model
package com.homelabpocket.model;

import com.homelabpocket.util.JsonUtil;

/**
 * Represents a configured storage pool on a Proxmox VE node or PDM resource.
 *
 * Upstream Proxmox VE API: GET /nodes/{node}/storage
 * Upstream PDM API: GET /resources (type = "storage")
 *
 * Distinguishes host root filesystem (rootfs) from configured datastores
 * and avoids double-counting shared pools across multiple nodes.
 */
public class StoragePool {

    public final String name;
    public final String type;
    public final long total;
    public final long used;
    public final long avail;
    public final boolean active;
    public final boolean enabled;
    public final boolean shared;
    public final String content;

    public StoragePool(String name, String type, long total, long used, long avail,
                       boolean active, boolean enabled, boolean shared, String content) {
        this.name = name != null ? name : "storage";
        this.type = type != null ? type : "unknown";
        this.total = total;
        this.used = used;
        this.avail = avail;
        this.active = active;
        this.enabled = enabled;
        this.shared = shared;
        this.content = content != null ? content : "";
    }

    public int getUsagePercent() {
        if (total <= 0 || used < 0) return 0;
        return (int) Math.min(100, Math.max(0, (used * 100L) / total));
    }

    public String getFormattedUsed() {
        return JsonUtil.formatBytes(used);
    }

    public String getFormattedTotal() {
        return JsonUtil.formatBytes(total);
    }

    public String getFormattedAvail() {
        return JsonUtil.formatBytes(avail);
    }

    public String getTypeBadge() {
        StringBuilder sb = new StringBuilder();
        sb.append(type.toUpperCase());
        if (shared) {
            sb.append(" (SHARED)");
        }
        if (!enabled) {
            sb.append(" [DISABLED]");
        } else if (!active) {
            sb.append(" [INACTIVE]");
        }
        return sb.toString();
    }
}
