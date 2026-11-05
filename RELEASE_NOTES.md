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
