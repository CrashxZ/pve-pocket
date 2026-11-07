# Changelog

All notable changes to the PVE Pocket project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [0.1.5-alpha] - Candidate (Code 8)

### Added
- **Upstream Proxmox VE / PDM Task `WARNINGS` Terminal Classification**:
  - Aligned client task status evaluation with official Proxmox task semantics where `exitstatus: "WARNINGS: <n>"` indicates legitimate non-fatal completion rather than task failure.
  - Both `PveApiDriver` and `PdmApiDriver` now classify `status: "stopped"` with `exitstatus: "WARNINGS: ..."` as `TaskResult.TaskState.WARNINGS` (`isSuccess() == true`).
  - Added dedicated user feedback in `GuestsView` and `VmDetailActivity`: operations ending with upstream warnings display a "Task Completed with Warnings" dialog detailing the warning string (e.g. `WARNINGS: 1` caused by missing EFI disk or non-fatal configuration notices) rather than a misleading "Task Failed" error.
  - Genuine errors (`TASK ERROR: ...`) strictly remain classified as `TaskResult.TaskState.ERROR` (`isSuccess() == false`).
  - Missing or unknown status fields strictly map to `TaskResult.TaskState.UNKNOWN` without falsely presuming failure or success.

- **Node Detail Telemetry Resilience & Single Fleet Roundtrip**:
  - Replaced multiple sequential PDM API requests in `NodeDetailActivity` (`fetchAllNodes`, `fetchNodeGuests`, `fetchNodeStorage`) with a single bounded `pdm.fetchFleetResources(...)` call (15s source budget) plus optional storage enrichment (5s source budget), reducing redundant roundtrips and timeout vulnerability when sibling remotes are slow or offline. Note: 15s and 5s are client source fetch budgets that exclude preceding credential acquisition; a 100ms synthetic sibling delay fixture demonstrates transport-seam isolation without hanging, but does not prove a 20s UI deadline or physical-runtime completion. No total UI ceiling is proven.
  - Sibling remote failure isolation: Sibling connection errors (such as an offline sibling remote ("No route to host")) are isolated in synthetic fixtures so that healthy nodes finish loading guests and storage pools without hanging or infinite spinners.
  - Explicit unavailable initial presentation: Replaced layout default placeholders (`CPU: 0.0%`, `RAM: 0 / 0 GB`, `Root FS (/): 0 / 0 GB`) with explicit unavailable state (`CPU: -`, `RAM: -`, `Root FS (/): -`) preventing misleading fake zeros before fresh telemetry arrives.
  - Task lifecycle and execution safety: Replaced serial AsyncTask execution with `THREAD_POOL_EXECUTOR` and added explicit task cancellation (`currentLoadTask.cancel(true)`) across pause, resume, and refresh transitions to prevent hung tasks from queueing.
  - Shell action safety: Shell button in `NodeDetailActivity` is disabled for PDM targets or offline hosts.

- **Truthful Task Ack Isolation & Lagging Cache Protection**:
  - Shared production caller transition wired to BOTH `GuestsView` and `VmDetailActivity`: evaluates task presentation, triggers post-operation refresh, and resets action-running state.
  - Verified through actual completion callbacks `GuestsView.PowerActionTask.onPostExecute` and `VmDetailActivity.ExecutePowerTask.onPostExecute`, recording actual rendered `AlertDialog` title/message ("Task Completed with Warnings", "WARNINGS: 1").
  - Distinguishes acknowledged task results from subsequent background telemetry updates or lagging PDM resource cache entries. Subsequent telemetry errors mark cached telemetry as stale without corrupting acknowledged task outcomes.
  - Validated target preflight prevents mutating actions against selected unreachable remotes, guaranteeing zero HTTP POST dispatches.
  - Verified shutdown endpoint paths and parameter bindings: executed new exact shutdown fixture for PDM (`/api2/json/pve/remotes/{remote}/{type}/{vmid}/shutdown` with `node={node}` form body, CSRF token, returned UPID, and exact poll path identity), while the PVE path (`/api2/json/nodes/{node}/{type}/{vmid}/status/shutdown`) was separately source-reviewed (the complete fixture was not executed on PVE). Note: Physical phone VM guest shutdown failure cause remains explicitly UNKNOWN without physical device and hypervisor logs.

### Verification
- Full test suite passed: **354 automated behavioral tests passed** (100% pass rate across 25 test classes: 330 baseline + 24 field regressions).
- Preserved all 330 baseline tests, with all independent probes verified.
- 24 regression tests in `PhoneFieldRegression015Test`:
  - Upstream Proxmox task `WARNINGS: <n>` terminal classification in `TaskResult`, `PdmApiDriver`, and `PveApiDriver`.
  - True `ERROR` preservation and `UNKNOWN` mapping without false success or blind retries.
  - NodeDetailActivity owner load seam with storage provenance isolation and cluster-shared pool inclusion.
  - NodeDetailActivity owner missing remote or node explicit unavailable handling.
  - NodeDetailActivity lifecycle owner completion rejection/acceptance seam.
  - Cold and cached start presentation through completeAndBind seam.
  - Whole endpoint failure handling with actual HTTP 500 status code.
  - Endpoint failure handling with HTTP 200 JSON error payload.
  - Task result presentation seam genuinely wired to both `GuestsView` and `VmDetailActivity`.
  - Truthful ack preservation through actual production caller completion callbacks (`PowerActionTask.onPostExecute` and `ExecutePowerTask.onPostExecute`) rendering `AlertDialog` with upstream warnings, isolated from subsequent telemetry network errors.
  - Exact PDM shutdown fixture with POST, path, CSRF, node body, returned UPID, and exact poll path identity (PVE path separately source-reviewed).
  - Selected unreachable remote controls disabled with zero POST dispatch at production preflight gate in both `VmDetailActivity` and `GuestsView`.
  - Explicit unavailable node metrics presentation without fake zeros for initial, placeholder, and offline states.
  - Sibling failure isolation in both remote group orderings (`healthyFirst` and `failedFirst`).
  - Slow sibling delay fixture (100ms synthetic delay) confirming healthy node completion without freezing under simulated transport delay (does not prove a total UI deadline or physical-runtime completion).
  - Activity instance owner binding (`applyPresentation`) preserving `cachedNode`, `cachedStoragePools`, and `cachedGuests`, and marking metrics stale on subsequent errors without losing cache.
  - Stale scheduler callback rejection firing terminal error callback.
  - Session locked lifecycle rejection.
  - Action in progress lifecycle rejection.
  - Paused activity single transport ownership cancelling in-flight task without corrupting cached state.
  - View-binding owner protocol verifying `applyPresentation` binds exact states computed in `completeAndBind` and clears loading spinners.
  - Cluster-shared storage pool matching without node provenance while filtering unshared sibling pools.
  - VmDetailActivity power task owner clearing before post-operation refresh dispatch.
  - Watchdog timeout vs cumulative loading behavior verifying rejected stale callbacks and fresh retry clearing loading.
  - Pause/resume single transport ownership cancelling in-flight tasks without corrupting view states.
- All independent probes verified: `TerminalStatusProbe` (10/10), `OwnerProbe`, `AdditionalProbe`, `LeaseProbe`, `MixedEndpointProbe`, `PartialAdmissionProbe`, `FinalFleetProbe` exit 0 (GREEN).
- Candidate artifact (headless-verified candidate, not claimed for release distribution): `artifacts/pve-pocket-debug.apk` (207,853 bytes, SHA-256: `7c247714d3cb04854ad10c01184443c353d34742d0c2732cb2e232dd2ddbe8b1`).
- Verified APK Signature Scheme v2 and v3 compatibility; signer certificate SHA-256 `869037568e2aabf3b1030d21fc08ed331d4732f3387929504d1b2e3b38ebae5e`.
- Verification limits disclosed: Headless presentation helper and view-binding seam evaluation, in-process loopback HTTPS tests; phone runtime NOT_TESTED; no live lab tested; real VM shutdown cause on physical phone remains UNKNOWN without physical device and hypervisor logs.

## [0.1.4-alpha] - Candidate (Code 7)

### Added
- **Foreground Lifecycle-Aware Telemetry Auto-Refresh**:
  - Configurable auto-refresh cadence: Off, 5s, 10s (default recommended), 30s, 60s, persisted as a non-secret global preference (`RefreshPreferenceStore`).
  - Cadence target disclosure: The configured interval is a client-side polling cadence target, not measured hypervisor API freshness. Upstream metric collection cycles (such as Proxmox Datacenter Manager collection cadence) may limit true freshness.
  - Foreground-only lifecycle awareness: Polls strictly while activities are in the resumed foreground state. Automatically pauses when backgrounded, paused, screen turns off, device is keyguard locked, session is PIN locked, or Settings tab is active. Zero background services or wake locks.
  - Prompt fresh read on resume: Resuming from pause/lock dispatches a single prompt fresh read and then schedules the next interval. Avoids duplicate startup `onCreate`/`onResume` requests.
  - Single loading owner architecture: Fleet overview, Hosts, and Guests tabs share one loading owner with no duplicate timers; Settings tab does not poll. Node and VM/LXC detail screens maintain their own visible lifecycle-aware scheduler.
  - Completion-relative interval scheduling: Schedules next refresh strictly `prior completion + interval` (no fixed-rate overlapping requests). Coalesces rapid manual refreshes safely without network queuing.
  - Power action deferral & UI state preservation: Defers telemetry auto-refresh while VM/LXC power operations or confirmation dialogs are active. Preserves card selections, dialogs, search queries, filter tabs, and scroll positions without UI flickering or toast spam. Preserves explicit operation-completion refreshes.
  - Bounded exponential error backoff & stale data integrity: Network/API errors keep existing telemetry marked as stale. Implements bounded exponential backoff (30s on first error, 60s max thereafter) without infinite auth retry or attempting re-authentication when session is locked. Manual refresh retries safely, resetting backoff only after success.
  - Completion owner UI binding audit: All background fetch completions audit activity resumed state, session lock, server configuration replacement, action guards, and scheduler generation tokens before executing UI data binding or view re-renders.
  - True transport ownership across watchdog timeouts: Watchdog timeout marks error/backoff and invalidates data binding without synthetic HTTP cancellation; true transport ownership is preserved until terminal callback arrival. Prevents duplicate network requests across BACKOFF, PAUSED, Settings, manual, and resume states. Terminal callback arrival releases transport ownership and dispatches retry safely according to lifecycle and backoff, preventing scheduler starvation while never evicting newer requests or replacing active watchdogs.
  - Explicit one-shot deferral preservation across action guard release even when Off: Telemetry refresh requests deferred by active action guards (e.g. confirmation dialogs or in-flight power actions) schedule a bounded deferral re-check without requiring active auto-refresh polling, safely dispatching the promised read upon guard release and never triggering background polling when configured to Off. Preserves explicit post-operation and resume reads hitting active guards.
  - Retained node telemetry stale indicator: `NodeDetailActivity` clearly marks retained CPU, RAM, Root FS, and uptime telemetry as `(stale)` / `(stale - showing cached data)` when `res.node` is null on failed refresh status, clearing the stale indicator only on successful rebind with no toast spam.
- **PDM Partial-Fleet Discard Repair & Resilient Telemetry Isolation**:
  - Isolated Remote Telemetry (`PdmFleetResult`): Resolves the regression where an offline or unreachable PDM remote cluster (e.g., an offline sibling remote) threw a global exception and discarded healthy sibling clusters.
  - Scoped Status & Error Presentation: Live metrics, hosts, guests, and power controls are preserved for healthy remotes; errored remotes are explicitly presented as `UNKNOWN` / `Unreachable` (never fabricated as intentional powered-down offline or 0 capacity).
  - Scope-Aware Fleet Aggregation: CPU/RAM fleet aggregates compute capacity and usage strictly from live online nodes, explicitly labeled with partial coverage (`"X vCPUs (partial coverage)"`). Retained offline/errored nodes do not contaminate live aggregates.
  - Fail-Closed Mutation Preflight: Target verification before any power mutation validates identity on the target remote; errors on that remote abort with clear user disclosure before any HTTP POST is dispatched. Sibling errors do not poison healthy mutations.
  - Complete Provenance & Cache Lifecycle: Authoritative empty sets clear vanished resources; unlinked/removed remotes are purged; replaced endpoint provenances invalidate old cache. Duplicate VMIDs and node names across remotes never collide or mix.
- **PVE Per-Node Guest Authority & Provenance Lifecycle**:
  - Differentiates PDM server-level guest inventory from PVE per-node guest inventory (`successfulGuestNodeKeys`) in `UiPresentationPolicy.mergeFleetData`.
  - Authoritative empty guest results on a specific PVE node clear vanished guests from that node while preserving cached guests on failed sibling nodes within the same PVE cluster.
  - Server provenance tracking accounts for per-node guest fetch success (`guestNodeSucceeded`), maintaining accurate cache validity across partial cluster reads.
- **VM/LXC Detail Stale Metrics Retention & Action Confirmation Guards**:
  - Encapsulated presentation state in `UiPresentationPolicy.GuestMetricsBindingState` and `evaluateGuestMetricsBinding`: on routine telemetry transport error or selected remote failure, cached CPU, RAM, Disk, and Uptime metrics are preserved and explicitly marked `(stale)` / `State: Telemetry refresh failed (<error> - cached data)` under `UNKNOWN` state.
  - In `VmDetailActivity`, power controls card is hidden and power action buttons (Start, Shutdown, Reboot, Force Stop) as well as terminal are disabled/hidden during stale telemetry.
  - Action confirmation guards (`confirmPowerAction`, `confirmForceStop`) strictly reject execution when metrics are stale (`isGuestMetricsStale = true`) with `"Action blocked: Target not verified, moved, or offline. Please refresh."`
  - Revalidation cleanly clears stale flags and restores authorized controls upon successful fresh fetch.

### Verification
- Full test suite passed: **330 automated behavioral tests passed** (100% pass rate).
- Baseline security, API routing, TLS, credential store, target guard, PDM driver, and task lifecycle tests preserved.
- Partial-fleet regression matrix (`PdmPartialFleetGreenRegressionTest`) covering both group ordering permutations, cold start/cached states, duplicate IDs across remotes, embedded error resource ignoring, all-failed handling, authoritative remote removal during allfailed, whole-endpoint failure cache preservation without duplicate placeholders, endpoint replacement, detail/storage scoping, per-scope timestamps, preflight POST prevention, PVE per-node empty guest purge vs sibling failure, and selected remote failure power control disabling.
- Behavioral scheduler and view-binding integration tests (`AutoRefreshSchedulerTest`, `RefreshPreferenceStoreTest`, `UiPolishViewModelBindingTest` including guest metrics stale binding).
- Independent verification probes verified:
  - `OwnerProbe`, `AdditionalProbe`, `LeaseProbe`: deterministic execution of scheduler state transitions, 90/180s single transport ownership, late callback handling, and post-power transitions verified.
  - `MixedEndpointProbe`: verified PVE node empty guest purge with unrelated PDM partial error (`retained guests=0 expected=0`).
  - `PartialAdmissionProbe` & `FinalFleetProbe`: actual loopback HTTPS verified in both remote orderings with failed embedded resources, selected failed remote rejection, all-failed authoritative removal, and HTTP 500 cache preservation.
  - `RedPartialFleetReproductionProbe` & `GreenPartialFleetReproductionProbe`: immutable baseline probe freezing 4 partial-fleet failures and companion green verification .
- Build artifact: `artifacts/pve-pocket-debug.apk` (199,661 bytes, SHA-256: `b7abe13123352889e67a803c1696224d1b785745f044bacfc9c119cb1e6c37e3`).
- Verified APK Signature Scheme v2 and v3 compatibility; signer certificate SHA-256 `869037568e2aabf3b1030d21fc08ed331d4732f3387929504d1b2e3b38ebae5e`.
- Verification limits disclosed: Headless presentation helper and view-binding seam evaluation, in-process loopback HTTPS tests; no physical phone runtime or real hardware keyguard execution claimed; physical phone runtime remains NOT_TESTED.

## [0.1.3-alpha] - 2026-11-05

### Added
- **Proxmox Datacenter Manager (PDM) Guest Power Actions**:
  - Implemented guest power management for QEMU virtual machines and LXC containers via upstream PDM REST endpoints: Start, Graceful Shutdown, and Force Stop.
  - Interactive confirmation dialogs before dispatching power mutations, distinguishing graceful shutdown from forceful termination.
  - Concurrency and mutation protection: `ActionGuard` prevents overlapping power operations on the same guest during client tracking; non-retrying POST execution (`executePostWithoutRetryWithDeadline`) prevents duplicate mutation dispatch.
  - Asynchronous task tracking: Exact guest/node verification before dispatch, followed by remote-bound UPID task polling.
  - Upstream Reboot Disclosure: Honest disclosure that upstream PDM API provides no reboot endpoint; unsupported action rejected cleanly without attempting unsafe synthetic reboots.
- **Storage Backend Status Enrichment**:
  - Node storage pools enriched with backend types (`dir`, `lvm`, `lvmthin`, `zfspool`, `nfs`) by querying upstream `/pve/remotes/{remote}/nodes/{node}/storage/{storage}/status`.
  - In-memory bounded LRU cache with positive and negative TTL caching to minimize redundant network requests.
  - Graceful fallback: returns explicit `Unavailable` or `?` when permissions (HTTP 403), older PDM versions, or missing endpoints occur.
- **Safety and Reliability Fixes**:
  - API Token 401 Lock Deadline: Added bounded acquisition deadline in `SessionManager` during token 401 handling, preventing thread lock contention stalls.
  - UI State Safety: Resilient handling of `UNKNOWN` guest and host runtime states without crashing or reporting synthetic zeros.
  - Full consolidation of prior v0.1.2 UI enhancements (radial CPU/RAM fleet gauges, compact hosts list, scannable labels) and v0.1.1 PDM HttpOnly cookie authentication fix.

### Changed
- Updated `versionName` to `0.1.3-alpha` and `versionCode` to `6`.
- Release asset filename: `pve-pocket-v0.1.3-alpha.apk`.
- Updated repository screenshots with four real user captures showing phone UI layout, live inventory, guest power controls, and storage pools.

### Verification
- Full test suite passed: **254 automated behavioral tests passed** (100% pass rate).
- Test suite incorporates 8 frozen acceptance probes overlapping the main test suite (not 8 additional unique tests).
- Token 401 deadline fixture verified: returns in bounded 40ms vs previously stalled 361ms under lock contention.
- Release APK verified: exact size **175085 bytes**, SHA-256 `077e5ca653392c73ebf6b3f82540a78d0e69df30ba2ac218447c87a6cf6ae234`.
- Verified APK Signature Scheme v2 and v3 using certificate SHA-256 `869037568e2aabf3b1030d21fc08ed331d4732f3387929504d1b2e3b38ebae5e`, matching prior releases for upgrade compatibility (`com.homelabpocket`, min API 26, target API 29).
- Debug alpha release: does not claim production signing, physical device lifecycle/Keystore qualification, or live hypervisor power execution. Screenshots confirm phone UI rendering and live inventory.
- CI status: The release workflow verifies this commit on Ubuntu/JDK 17 before APK publication; see the repository Actions tab for its result.

## [0.1.2-alpha] - 2026-11-05

### Changed
- Replaced the Overview topology strip and linear fleet meters with side-by-side native radial CPU/RAM gauges, compact guest counts, and one compact Hosts list that retains offline hosts.
- Fleet CPU is weighted by reported host core capacity; RAM totals use host telemetry only, without adding guest allocations. Missing fleet metrics are unavailable rather than synthetic zeroes; gauge percentages are bounded.
- Separated host basenames from remote-cluster labels while retaining full composite identifiers for navigation and API routing.
- Labeled unavailable root filesystem, disk, network and storage-driver telemetry, attributing missing values to PDM only for confirmed PDM connections.
- Replaced unavailable PDM power/console/shell buttons with monitoring notices and badges. PDM remains monitoring-only in this version; power-management work is not included.
- Increased secondary/muted text contrast, added themed placeholder colors, and replaced the Hosts refresh button's light platform background with a theme-aware background.
- Added gauge text-sizing and accessibility descriptions, including a short unavailable marker inside the ring.
- Updated `versionName` to `0.1.2-alpha` and `versionCode` to `5`.

### Verification
- Independently rebuilt the publication source on ARM64: all **209 JVM tests passed**, followed by resource compilation, D8, APK packaging/alignment and successful APK v2/v3 signature verification.
- UI tests exercise presentation policies, binding-state models, gauge calculation/text-sizing helpers and XML structure. They do **not** execute Android View inflation, Canvas rendering, TalkBack or device screenshots; those require Android runtime verification.
- Retained the 0.1.1 PDM HttpOnly authentication repair, credential redaction, redirect refusal, TLS checks and backend PDM action restrictions unchanged.
- This entry documents source publication, not a new downloadable GitHub Release. The existing 0.1.1-alpha release and version-scoped screenshots remain unchanged.

## [0.1.1-alpha] - 2026-11-04

### Fixed
- **Proxmox Datacenter Manager (PDM) Password Authentication & HttpOnly Cookie Handling**:
  - Resolved `Failed to parse ticket response: No value for ticket` error during PDM password authentication by conforming to upstream Proxmox schema (`proxmox-auth-api` `API_METHOD_CREATE_TICKET_HTTP_ONLY`).
  - Added extraction of authentication tickets from response `Set-Cookie` headers (`__Host-PDMAuthCookie`, `PDMAuthCookie`) when `ticket-info` is returned in JSON payload and `data.ticket` is omitted by PDM for HttpOnly cookie protection.
  - Enforced exact upstream cookie header formatting on PDM followup requests (`__Host-PDMAuthCookie=<ticket>`) ensuring strict case-sensitive server acceptance and RFC 6265 boundary enforcement without multi-cookie injection or uppercase aliases.
  - Hardened ticket parser fail-closed behavior for TFA/MFA challenges (`NeedTFA: 1`, `!tfa!`, and `CSRFPreventionToken: "invalid"`), malformed JSON payloads, and missing credentials.
  - Secured followup requests: `ApiClient` enforces explicit credential passing, refuses automatic HTTP 3xx redirects on credential-bearing endpoints to prevent credential leakage to third-party sinks, and returns fixed static error descriptions for HTTP 4xx/5xx responses to prevent server reflection of sensitive error bodies.
  - Hardened credential redaction: `JsonUtil.sanitizeForLog` adds redaction coverage for PDM API tokens (`PDMAPIToken`), PDM cookies (`__Host-PDMAuthCookie`, `PDMAuthCookie`, `PDMAUTHCOOKIE`, `PDMCookie`), and payload fields (`ticket-info`, `ticket_info`) in diagnostics and logging.
  - Expanded automated test suite: 184 passing behavioral tests, including `PdmUpstreamAuthRegressionTest` (covering upstream HttpOnly ticket extraction, strict cookie scopes, TFA refusal, non-disclosure on HTTP 400, and HTTP 302 redirect refusal) and transactional preference save tests.
  - Updated application versioning: `versionName="0.1.1-alpha"`, `versionCode="4"`.
  - Scope Note: This release strictly addresses PDM authentication and security hardening; pending UI polish and layout enhancements are intentionally deferred to future releases.

## [0.1.0-alpha] - 2026-11-04

### Added
- **Proxmox Datacenter Manager (PDM) Integration**:
  - Implemented upstream PDM REST driver (`PdmApiDriver`) supporting HTTPS port 8443.
  - Support for PDM ticket authentication via `/api2/json/access/ticket` with `PDMAUTHCOOKIE`.
  - Support for PDM API tokens via `PDMAPIToken` header.
  - Remotes discovery via `/api2/json/remotes` (identifying managed PVE clusters and PBS backup instances).
  - Unified multi-cluster fleet discovery and inventory tracking via `/api2/json/resources`.
  - Preservation of remote cluster identity (`{remote}/{node}`) across all views and navigation stacks.
  - Strict differentiation between HTTP 401 (triggers single ticket refresh) and HTTP 403 (fails closed with explicit permission-denied messaging).
- **Node Resource Completeness**:
  - Hosted Guest Instances: `NodeDetailActivity` now actively fetches and renders both QEMU VMs and LXC containers for the target node.
  - Support for all guest runtime states, including `RUNNING`, `STOPPED`, and `UNKNOWN`.
  - Configured Storage Pools: Added dedicated storage section querying `/nodes/{node}/storage`.
  - Support for `dir`, `lvm`, `lvmthin`, `zfspool`, and `nfs` (shared) storage pool types with total, used, and available metrics.
  - Explicit Rootfs Labeling: Host root filesystem metric is explicitly labeled (`Root FS (/)`) and visually separated from configured datastores to avoid double-counting shared storage.
  - Transparent State Handling: Distinct visual feedback for loading, offline hosts, permission-denied (403), authentication failures (401), and empty nodes ("No hosted guest instances found").
- **Portable Build & CI Pipeline**:
  - Added `.github/workflows/ci.yml` supporting standard Ubuntu x86_64 runners with least-privilege `contents: read` permissions.
  - Added portable `tools/build.sh` supporting both standard Android SDK / Ubuntu environments and ARM64 Raspberry Pi user-local toolchains.
  - 115 passing behavioral unit tests verifying PDM routing, storage pool parsing, security boundaries, and view binding.

### Changed
- **Rebranding to PVE Pocket**:
  - Updated user-facing application label to **PVE Pocket**.
  - Updated versioning: `versionName="0.1.0-alpha"`, `versionCode="3"`.
  - Internal package namespace `com.homelabpocket` preserved to support seamless upgrades from previous alpha builds.
- **Terminal Crash Remediation & Routing Decision Engine**:
  - Removed all `android.webkit.WebView` tags and dependencies from `TerminalActivity` and `activity_terminal.xml`.
  - Migrated terminal interface to pure native Android views, preventing `MissingWebViewPackageException` and XML inflation crashes on devices lacking Google WebView.
  - Implemented `TerminalRoutingDecision`: strictly disables direct SSH when the target host cannot be verified to match the selected target (PDM remote nodes, LXC/QEMU containers without configured guest IP, and PVE cluster peer nodes differing from the configured API node).
  - Explicitly labels web browser fallback as opening the configured management endpoint web UI rather than the selected guest console.
  - Updated `NodesView` and `NodeDetailActivity` to honestly disable shell triggers for PDM nodes ("No Shell (PDM)").
  - Preserved missing-handler safety with try-catch fallbacks and clipboard copy.

### Fixed
- **Guest Fetching Explicit Error Semantics (`PveApiDriver.fetchAllGuests`)**:
  - Fixed error suppression bug where non-auth `ApiException` was caught and swallowed, converting HTTP 500, timeouts, and malformed payload responses into empty list or silently partial results.
  - Enforced fail-closed error propagation. Reserved empty guest list exclusively for verified empty endpoints (`{"data":[]}`).
  - Added 9 production-driver HTTPS fixtures covering populated, stopped, empty, 401, 403, offline, qemu500+lxcempty, lxc500+qemupopulated, and malformed payload.
- **Prominent TLS Verification Status & Confirmation (`TlsVerificationStatus`, `TlsConfirmationDecision`)**:
  - Fixed bug where `SettingsView` rendered green "Pinned TLS" even when `skipTlsVerify=true`.
  - Enforced persistent `⚠️ TLS VERIFICATION DISABLED (Insecure)` warning whenever skip TLS is active, overriding any retained fingerprint.
  - Added interactive confirmation dialog before enabling skip TLS in `AddServerActivity` with default-OFF policy.
- **Clean Toolchain Bootstrap (`bootstrap-tools.sh`)**:
  - Repaired directory extraction bug where platform `android-10` was nested into precreated `android-29`.
  - Added verification of pinned SHA-256 tool checksums for OpenJDK, platform-29, and R8.
  - Added dynamic generation of `tools/env.sh` for build script execution.
- **Test Suite Expansion**:
  - Expanded behavioral test suite to 158 passing unit tests (100% pass rate).

### Security
- Retained fail-closed certificate pinning (`PinnedTrustManager`) and RFC 6125 hostname verification.
- Maintained per-server TLS bypass option with default-OFF policy, persistent UI warning badges, and confirmation disclosures.
- Hardware-backed Android Keystore AES-256-GCM credential encryption.
- Explicit fail-closed guards disabling speculative PDM guest power actions and terminal streaming.
