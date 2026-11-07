# PVE Pocket v0.1.5-alpha (Candidate)

> **Status:** Release candidate in review; not yet published to GitHub Releases. The latest published release remains [v0.1.3-alpha](https://github.com/CrashxZ/pve-pocket/releases/tag/v0.1.3-alpha). These notes describe the candidate and are prepared in advance for publication.

PVE Pocket v0.1.5-alpha delivers targeted, verified fixes for real-world phone failures reported after v0.1.4-alpha. It aligns task terminal status classification with official Proxmox task semantics (`WARNINGS: n` treated as successful completion with upstream warnings), consolidates `NodeDetailActivity` telemetry into a single bounded fleet request to isolate slow or unreachable siblings (`remote-2`), presents explicit unavailable telemetry without misleading fake zeros (`CPU: -`, `RAM: -`, `Root FS (/): -`), and isolates acknowledged task success from subsequent background telemetry network errors or lagging PDM cache.

## What's New in v0.1.5-alpha

- **Official Proxmox Task `WARNINGS` Classification & Feedback:**
  - Upstream Proxmox VE and PDM task terminal status is parsed strictly according to Proxmox semantics: tasks ending with `status: "stopped"` and `exitstatus: "WARNINGS: <n>"` (e.g. `WARNINGS: 1` triggered by a missing EFI disk or non-fatal configuration notice) are classified as successful completions with upstream warnings (`TaskResult.TaskState.WARNINGS`), rather than false failures.
  - Informative dialogs in `GuestsView` and `VmDetailActivity`: operations completing with warnings display a "Task Completed with Warnings" dialog detailing the upstream notice string, replacing misleading "Task Failed" dialogs.
  - Genuine errors (`TASK ERROR: ...`) strictly remain classified as `ERROR`; missing or malformed status fields strictly map to `UNKNOWN`.

- **Node Detail Telemetry Consolidation & Sibling Isolation:**
  - `NodeDetailActivity` now fetches fleet resources in a single unified `pdm.fetchFleetResources(...)` call (15s source budget), followed by optional backend type enrichment if storage pools are discovered (5s source budget), reducing redundant roundtrips and timeout vulnerability when sibling remotes were offline or slow. Note: 15s and 5s are client source fetch budgets that exclude preceding credential acquisition; a 100ms synthetic sibling delay fixture demonstrates transport-seam isolation without hanging, but does not prove a 20s UI deadline or physical-runtime completion. No total UI ceiling is proven.
  - Sibling connection errors (e.g. `remote-2` "No route to host") are isolated in synthetic fixtures so healthy remotes finish loading telemetry, guests, and storage pools without hanging spinners or zero-metric lockups.
  - Initial UI presentation explicitly displays `CPU: -`, `RAM: -`, and `Root FS (/): -`, eliminating fake zero placeholders (`CPU: 0.0%`, `RAM: 0 / 0 GB`) before telemetry arrives.
  - Activity instance owner binding: `applyPresentation` populates and preserves `cachedNode`, `cachedStoragePools`, and `cachedGuests`, and marks retained metrics stale on subsequent errors without losing cache.
  - Task execution safety: tasks execute on `THREAD_POOL_EXECUTOR` and active loading tasks are cancelled (`cancel(true)`) during pause, resume, and refresh transitions.
  - Shell action safety: Shell button is disabled for PDM servers and offline hosts.

- **Truthful Task Ack Isolation & Lagging Cache Protection:**
  - Acknowledged power actions remain successful even if subsequent background telemetry refreshes encounter network hiccups or lagging PDM resource cache entries.
  - Preflight target verification rejects mutating actions against selected unreachable remotes with zero POST dispatches.
  - Shutdown endpoint paths verified synthetically: executed new exact shutdown fixture for PDM with POST, path, CSRF, form body (`node={node}`), returned UPID, and exact poll path identity, while the PVE path was separately source-reviewed (the complete fixture was not executed on PVE). Specific real-world phone failure cause remains explicitly UNKNOWN without physical device and hypervisor logs.

## Candidate Verification & Asset

| Property | Value |
| --- | --- |
| **Candidate Artifact** | `pve-pocket-debug.apk` (headless-verified candidate; not claimed for release distribution) |
| **Application ID** | `com.homelabpocket` |
| **Version** | `0.1.5-alpha` (versionCode `8`) |
| **Compatibility** | Android 8.0+ (minSdkVersion 26, targetSdkVersion 29) |
| **File Size** | `207853 bytes` |
| **SHA-256 Checksum** | `7c247714d3cb04854ad10c01184443c353d34742d0c2732cb2e232dd2ddbe8b1` |
| **APK Signatures** | Scheme v2 and v3 verified (certificate SHA-256: `869037568e2aabf3b1030d21fc08ed331d4732f3387929504d1b2e3b38ebae5e`) |
| **Behavioral Test Suite** | 354 passed / 0 failed (100% pass rate across 25 test classes: 330 baseline + 24 field regressions in `PhoneFieldRegression015Test`) |
| **Independent Probes** | All independent probes (`TerminalStatusProbe` 10/10, `OwnerProbe`, `AdditionalProbe`, `LeaseProbe`, `MixedEndpointProbe`, `PartialAdmissionProbe`, `FinalFleetProbe`) verified GREEN (exit code 0) |
| **Physical Phone Limitation** | Headless JVM test harnesses, mock Android doubles, and loopback HTTPS fixtures; phone runtime NOT_TESTED. No live lab tested. Real VM shutdown cause on physical phone remains UNKNOWN without physical device and hypervisor logs. 15s/5s source budgets exclude credentials; 100ms sibling fixture does not prove 20s UI deadline or physical completion; no total UI ceiling proven. |

---

# PVE Pocket v0.1.4-alpha (Candidate)

PVE Pocket v0.1.4-alpha introduces foreground lifecycle-aware telemetry auto-refresh across fleet overview and detail screens, configurable cadence intervals (Off, 5s, 10s default, 30s, 60s), robust overlap prevention, power operation protection, and error backoff handling.

## What's New in v0.1.4-alpha

- **Foreground Lifecycle-Aware Telemetry Auto-Refresh:**
  - Configurable auto-refresh interval: Off, 5s, 10s (default recommended), 30s, 60s, stored as a non-secret global preference.
  - **Cadence Target vs Freshness Disclosure:** The cadence setting represents client polling frequency, not measured hypervisor API freshness. Metrics on upstream Proxmox Datacenter Manager (PDM) are collected on PDM's own collection interval (~10s–30s), which may limit true metric freshness.
  - **Foreground Lifecycle Awareness:** Polling occurs only when an activity is actively visible and resumed. Polling automatically pauses when backgrounded, when the screen turns off, when the device is keyguard locked, when the app session is PIN locked, or when the Settings tab is visible. Zero background services, alarms, or wake locks.
  - **Completion-Relative Scheduling (No Overlap):** Next refresh is scheduled strictly after the prior fetch completes plus the configured interval (`prior completion + interval`), preventing overlapping or queued requests. Manual refreshes coalesce safely with in-flight fetches.
  - **Single Loading Owner Architecture:** Main fleet overview, hosts, and guests views share a single loading owner to prevent redundant network requests. Node and VM/LXC detail screens manage their own visible lifecycle-aware scheduler.
  - **Power Operation Protection:** Auto-refresh is deferred while virtual machine or container power actions or confirmation dialogs are active, preserving interactive cards, dialogs, and async task tracking without UI flickering or toast spam. Preserves explicit operation-completion refreshes.
  - **Bounded Exponential Backoff on Errors:** If an API or network error occurs, previous telemetry is retained and labeled stale. The scheduler backs off to 30s on the first error, and 60s maximum on subsequent errors, avoiding infinite retry loops or re-authenticating while locked. Manual refresh can retry immediately, resetting backoff upon success.
  - **Strict UI Data Binding Audit:** Background fetch completions audit activity lifecycle state, session locks, server configuration changes, and active guards before binding data to views.
- **PDM Partial-Fleet Discard Repair & Resilient Telemetry Isolation:**
  - **Remote-Scoped Error Containment (`PdmFleetResult`):** Resolves the regression where an offline or unreachable PDM remote cluster (e.g. an offline sibling remote) triggered whole-fleet failure, blanking healthy sibling clusters.
  - **Scoped Status Presentation:** Healthy clusters preserve live metrics, guests, storage, and power controls. Unreachable clusters are presented honestly as `UNKNOWN` / `Unreachable` (never fabricated as intentional powered-down offline or 0 capacity).
  - **Scope-Aware Fleet Aggregates:** CPU and RAM gauges reflect only live online node capacity and usage, explicitly labeled `(partial coverage)` when some nodes are offline or unreachable. Retained offline/errored nodes do not contaminate live aggregates.
  - **Mutation Fail-Closed Preflight:** Fresh target resolution before power actions strictly validates identity on the target remote and aborts without issuing HTTP POST if that remote has failed. Unrelated sibling errors do not poison healthy targets.
  - **Full Lifecycle Handling:** Authoritative empty responses clear vanished resources; unlinked/removed remotes are purged; replaced endpoint provenances invalidate old cache. Duplicate VMIDs and hostnames across remotes never collide or mix.
- **PVE Per-Node Guest Authority & Provenance Lifecycle:**
  - Differentiates PDM server-level guest inventory from PVE per-node guest inventory (`successfulGuestNodeKeys`) in `UiPresentationPolicy.mergeFleetData`.
  - Authoritative empty guest results on a specific PVE node clear vanished guests from that node while preserving cached guests on failed sibling nodes within the same PVE cluster.
  - Server provenance tracking accounts for per-node guest fetch success (`guestNodeSucceeded`), maintaining accurate cache validity across partial cluster reads.
- **VM/LXC Detail Stale Metrics Retention & Action Confirmation Guards:**
  - In `VmDetailActivity` and `UiPresentationPolicy.GuestMetricsBindingState`: on routine telemetry transport error or selected remote failure, cached CPU, RAM, Disk, and Uptime metrics are preserved and explicitly marked `(stale)` / `State: Telemetry refresh failed (<error> - cached data)` under `UNKNOWN` state.
  - Power control card is hidden, power action buttons (Start, Shutdown, Reboot, Force Stop) and terminal are disabled/hidden.
  - Action confirmation guards (`confirmPowerAction`, `confirmForceStop`) strictly reject execution when metrics are stale (`isGuestMetricsStale = true`) with `"Action blocked: Target not verified, moved, or offline. Please refresh."`
  - Revalidation cleanly clears stale flags and restores authorized controls upon successful fresh fetch.

## Candidate Verification & Asset

| Property | Value |
| --- | --- |
| **Candidate Artifact** | `pve-pocket-debug.apk` (headless-verified candidate) |
| **Application ID** | `com.homelabpocket` |
| **Version** | `0.1.4-alpha` (versionCode `7`) |
| **Compatibility** | Android 8.0+ (minSdkVersion 26, targetSdkVersion 29) |
| **File Size** | `199661 bytes` |
| **SHA-256 Checksum** | `b7abe13123352889e67a803c1696224d1b785745f044bacfc9c119cb1e6c37e3` |
| **APK Signatures** | Scheme v2 and v3 verified (certificate SHA-256: `869037568e2aabf3b1030d21fc08ed331d4732f3387929504d1b2e3b38ebae5e`) |
| **Behavioral Test Suite** | 330 passed / 0 failed (100% pass rate) |
| **Independent Probes** | Verified deterministic lifecycle, watchdog, late callback, Off deferred resume read, post-power transitions (`OwnerProbe`, `AdditionalProbe`, `LeaseProbe`), PVE per-node empty guest purge probe (`MixedEndpointProbe`), loopback HTTPS admission and fleet probes (`PartialAdmissionProbe`, `FinalFleetProbe`), and immutable RED/GREEN reproduction probes |
| **Physical Phone Limitation** | Headless unit/integration harness tested; view-binding seams evaluated headless; phone runtime NOT_TESTED; no live lab tested |

---

# PVE Pocket v0.1.3-alpha

PVE Pocket v0.1.3-alpha adds guest power management for Proxmox Datacenter Manager (PDM) endpoints, storage backend status enrichment, reliability fixes, and consolidates the v0.1.2 radial fleet gauges and v0.1.1 authentication fixes into a verified release.

## What's New in v0.1.3-alpha

- **PDM Guest Power Management:**
  - Control QEMU virtual machines and LXC containers connected via Proxmox Datacenter Manager: Start, Graceful Shutdown, and Force Stop.
  - Explicit confirmation dialogs before dispatching actions to prevent unintended shutdowns.
  - Concurrency safety: `ActionGuard` prevents simultaneous conflicting power actions on the same guest during client tracking.
  - Mutation safety: non-retrying POST dispatch prevents duplicate action submission.
  - Asynchronous UPID task tracking: Exact guest/node verification before dispatch, followed by remote-bound UPID task polling.
  - **Honest Upstream Reboot Disclosure:** The upstream PDM REST API does not provide a guest reboot route. The app honestly surfaces this limitation and rejects reboot actions on PDM guests rather than attempting unsafe synthetic reboots. Users should issue Graceful Shutdown, wait until confirmed stopped, and then issue Start.

- **Storage Backend Status Enrichment:**
  - Queries upstream `/nodes/{node}/storage/{storage}/status` to enrich node storage pools with real backend types (`lvmthin`, `dir`, `zfspool`, `nfs`).
  - In-memory bounded LRU cache with positive and negative TTL caching to minimize network overhead.
  - Graceful fallback to `Unavailable` or `?` when permissions (HTTP 403), older PDM versions, or missing endpoints occur.

- **UI & Presentation Polish (Consolidating v0.1.2):**
  - Side-by-side radial CPU and memory fleet gauges with core-weighted CPU calculation and host-only RAM totals.
  - Compact hosts list retaining offline hosts, displaying scannable host basenames alongside remote cluster labels.
  - Compact guest counters and resilient UI rendering for `UNKNOWN` guest/host runtime states.
  - High-contrast typography and theme-aware button styling.

- **Security & Reliability (Consolidating v0.1.1):**
  - Bounded acquisition deadline for `SessionManager` lock during API token 401 handling, preventing thread stalls under contention.
  - PDM HttpOnly cookie authentication ticket extraction (`__Host-PDMAuthCookie`, `PDMAuthCookie`).
  - Automatic redirect refusal on credential-bearing endpoints and strict credential redaction in diagnostic strings.

## Release Asset & Checksums

| Property | Value |
| --- | --- |
| **Asset Filename** | `pve-pocket-v0.1.3-alpha.apk` |
| **Application ID** | `com.homelabpocket` |
| **Version** | `0.1.3-alpha` (versionCode `6`) |
| **Compatibility** | Android 8.0+ (minSdkVersion 26, targetSdkVersion 29) |
| **File Size** | `175085 bytes` |
| **SHA-256 Checksum** | `077e5ca653392c73ebf6b3f82540a78d0e69df30ba2ac218447c87a6cf6ae234` |
| **APK Signatures** | Scheme v2 and v3 verified |
| **Signing Certificate SHA-256** | `869037568e2aabf3b1030d21fc08ed331d4732f3387929504d1b2e3b38ebae5e` |

*The signing certificate matches the official v0.1.0-alpha, v0.1.1-alpha, and v0.1.2-alpha release artifacts, allowing direct in-place upgrade.*

## Alpha Limitations & Disclosures

- **Early Alpha Testing:** This is a debug-signed test build intended for homelab evaluation, not a production-hardened release. Local development builds may use different debug keys; CI keys are ephemeral, and neither can upgrade this official APK.
- **Power Action Concurrency & Tracking:** The concurrency guard only holds during client tracking, not necessarily server-side completion. Cancel stops client tracking, not an already-dispatched server operation. Timeout/network failure may leave the outcome unknown; verify guest/task state before submitting another action.
- **Device Acceptance:** Real user phone captures confirm phone UI rendering and live PDM inventory against a live multi-cluster deployment. They do not constitute comprehensive physical device lifecycle, Keystore, biometric, or live power-action qualification. Automated JVM tests are not full device tests. Testing was conducted without live hypervisor power execution.
- **Storage Durability:** Configuration and credentials reside in separate SharedPreferences files. Writes are not crash-atomic across files; in the event of an abnormal shutdown or storage error, review server settings before reconnecting.
- **CI Status:** The release workflow verifies this commit on Ubuntu/JDK 17 before APK publication; see the repository Actions tab for its result.
- **Remote Shell & Terminal Streaming:** Built-in terminal streaming is not supported on either PVE or PDM backends; browser fallback opens the web management interface.

For full details, see the [Changelog](CHANGELOG.md) and [Verification Document](VERIFICATION.md).
