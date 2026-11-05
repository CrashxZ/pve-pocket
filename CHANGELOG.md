# Changelog

All notable changes to the PVE Pocket project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

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
