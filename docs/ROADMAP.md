# Roadmap

This document outlines shipped features and future development priorities for PVE Pocket.

## Shipped in v0.1.3-alpha

The [v0.1.3-alpha](https://github.com/CrashxZ/pve-pocket/releases/tag/v0.1.3-alpha) release consolidates the v0.1.2 UI enhancements and introduces PDM guest power management:

- **Fleet Overview & UI Polish:**
  - Side-by-side radial CPU and memory fleet gauges with core-weighted CPU calculation and host-only RAM aggregation.
  - Compact hosts list displaying online/offline status, scannable host basenames, and separate remote cluster labels.
  - Compact guest counters and consistent theme-aware controls and contrast.
  - Resilient UI handling for `UNKNOWN` guest and host runtime states.

- **PDM Guest Power Management:**
  - Added Start, Graceful Shutdown, and Force Stop support for QEMU VMs and LXC containers via the PDM API.
  - Confirmation dialogs distinguishing graceful shutdown from forceful termination.
  - In-flight double-submit prevention (`ActionGuard`) preventing concurrent duplicate actions on the same guest during client tracking.
  - Upstream UPID task tracking via bounded asynchronous polling (`TaskPoller`).
  - Non-retrying POST dispatch to prevent unintended duplicate mutation requests.
  - Honest upstream disclosure: guest reboot is unsupported by upstream PDM API (no synthetic/fake reboot).

- **Storage Backend Status Enrichment:**
  - Enriched node storage pools with active backend types (`dir`, `lvm`, `lvmthin`, `zfspool`, `nfs`) queried from upstream `/nodes/{node}/storage/{storage}/status`.
  - In-memory bounded LRU cache with positive and negative TTL caching to minimize network load.
  - Graceful fallback to `Unavailable` or `?` when permissions (HTTP 403), older PDM versions, or missing endpoints occur.

- **Authentication & Core Reliability:**
  - Bound session lock acquisition deadlines on API token 401 handling, preventing monitor thread stalls.
  - Carried forward the v0.1.1 PDM HttpOnly cookie ticket parser, strict cookie scope, redirect refusal, and credential redaction.

## Future Milestones

Planned areas of focus for upcoming releases:

- **Storage & State Durability:**
  - Transition configuration and encrypted credentials to crash-atomic persistence to guard against abnormal termination during writes.
- **Console & Remote Access:**
  - Investigate external intent handoffs for console sessions (such as external VNC or SPICE client integration) while maintaining a strict zero-WebView architecture.
- **Upstream PDM Tracking:**
  - Monitor upstream Proxmox Datacenter Manager releases for native guest reboot endpoints or expanded fleet metrics.
- **Widgets & Background Polling:**
  - Android home screen widgets for high-level fleet status and configurable background health checks.
- **Production Packaging & Qualification:**
  - Production release signing pipeline and expanded physical device compatibility testing across varied form factors.

---

[Published releases](https://github.com/CrashxZ/pve-pocket/releases) · [Feedback and issues](https://github.com/CrashxZ/pve-pocket/issues) · [Back to README](../README.md)
