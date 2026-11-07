# Roadmap

This document outlines shipped features and future development priorities for PVE Pocket.

## In Progress / Candidate (v0.1.5-alpha)

- **Field Failure Repairs & Task Semantics Alignment:**
  - Upstream Proxmox Task Status `WARNINGS: n` terminal classification: accurately classifies non-fatal warning completions (`exitstatus: "WARNINGS: 1"`) as success across PVE and PDM drivers, replacing erroneous "Task Failed" alerts with informative "Task Completed with Warnings" dialogs.
  - Node Detail Telemetry & Sibling Failure Resilience: `NodeDetailActivity` fetches unified fleet resources in a single HTTP roundtrip instead of serial redundant calls, executes on thread pool with task cancellation on pause/resume, isolates slow/offline siblings (`remote-2` "No route to host"), and renders explicit unavailable state without fake zero metrics (`CPU: 0.0%`, `RAM: 0/0`).
  - Truthful Ack Isolation & Lagging Cache Handling: Distinguishes completed tasks from subsequent background telemetry errors or lagging PDM cache, preventing fresh acknowledged actions from being falsely flagged as stopped or failed.
  - Fail-closed mutation preflight on selected unreachable remotes: Zero POST dispatches to offline/unreachable nodes.

## Prior Candidate (v0.1.4-alpha)

- **Foreground Lifecycle-Aware Telemetry Auto-Refresh:**
  - Configurable auto-refresh cadence: Off, 5s, 10s (default recommended), 30s, 60s, persisted as a non-secret global preference.
  - Cadence target disclosure: The setting is a client polling cadence target, not measured hypervisor API freshness; upstream PDM metric collection cycles (~10s–30s) may limit true freshness.
  - Foreground-only lifecycle awareness: Automatically pauses when backgrounded, screen off, keyguard locked, session locked, or Settings tab selected. Zero background services or wake locks.
  - Completion-relative interval scheduling (no overlap, coalesced manual refresh).
  - Deferral during power operations preserving cards, dialogs, and task tracking.
  - Bounded exponential backoff on errors (30s then 60s max) keeping previous telemetry stale without auth retry loops.

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
