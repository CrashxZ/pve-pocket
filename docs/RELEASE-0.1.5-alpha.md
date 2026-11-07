# PVE Pocket v0.1.5-alpha — Release Notes (Candidate)

> **Status:** Release candidate admitted for phone testing. Not yet published to GitHub Releases. The download link and screenshots in the README remain at the last published v0.1.3-alpha until publication is performed. These notes describe the candidate and are written in advance for publication.

---

## Overview

PVE Pocket v0.1.5-alpha is a targeted field-regression release built on top of v0.1.4-alpha. It addresses three categories of real-world phone failures reported after v0.1.4: incorrect task `WARNINGS` classification, misleading fake-zero telemetry placeholders in node detail, and sibling remote network failures causing healthy node data to hang or vanish. No new features or unrelated code changes are included.

This candidate also includes the full foreground auto-refresh lifecycle introduced in v0.1.4-alpha (not previously published to GitHub Releases). The combined release therefore delivers both the foreground auto-refresh foundation and the phone-field regression fixes.

---

## What's New in v0.1.5-alpha

### Correct Proxmox Task `WARNINGS` Classification

Proxmox VE and PDM task completion uses a three-way terminal status: `TASK OK`, `TASK ERROR: ...`, and `TASK WARNINGS: n`. Previously, tasks ending with `WARNINGS: n` (e.g., triggered by a missing EFI disk or non-fatal configuration notice) were misclassified as failures and shown to the user as "Task Failed."

**Fix:** Tasks ending with `status: "stopped"` and `exitstatus: "WARNINGS: n"` are now classified as successful completions with upstream warnings (`isSuccess() == true`). The app shows a "Task Completed with Warnings" dialog describing the upstream notice, instead of a misleading failure dialog. Genuine errors (`TASK ERROR: ...`) remain `ERROR`; unrecognized or missing status fields remain `UNKNOWN` — neither is promoted to success.

### Node Detail: No More Fake Zeros / Sibling Failure Isolation

Before this fix, opening a VM/LXC node detail screen against a PDM cluster with an offline sibling remote could result in:

- Telemetry displayed as `CPU: 0.0%`, `RAM: 0 / 0 GB`, `Root FS (/): 0 / 0 GB` before data arrived (misleading fake zeros)
- Infinite loading spinner or missing data when a sibling remote (e.g., an offline second cluster) failed to respond

**Fix:**
- Initial presentation now explicitly shows `CPU: -`, `RAM: -`, `Root FS (/): -` before telemetry arrives, clearly distinguishing "not yet loaded" from genuine zero usage.
- Fleet resources are fetched in a single bounded request (15 s source budget) rather than sequential calls, followed by optional backend type enrichment (5 s source budget), reducing redundant roundtrips and timeout vulnerability when sibling remotes are slow or offline. (Note: These 15 s and 5 s values are client source fetch budgets excluding preceding credential acquisition; a 100 ms synthetic sibling delay fixture demonstrates transport-seam isolation without hanging, but does not prove a 20 s total UI deadline or physical-runtime completion. No total UI ceiling is proven.)
- Sibling remote failure isolation: Sibling connection errors are isolated in synthetic fixtures so healthy node telemetry and guests load without being blocked by sibling errors or infinite spinners, while failed siblings are marked `UNKNOWN` / `Unreachable` without contaminating healthy results.
- Retained stale metrics are marked `(stale)` on subsequent refresh failures; loading spinners are cleared properly.

### Foreground Auto-Refresh (from v0.1.4-alpha)

Configurable background telemetry polling — Off, 5 s, 10 s (default recommended), 30 s, 60 s — that runs strictly while activities are visible and resumed. Polling pauses automatically when the app is backgrounded, the screen turns off, the device is keyguard-locked, the session is PIN-locked, or the Settings tab is active. No background services or wake locks.

Additional scheduling properties:
- **Completion-relative scheduling:** next refresh waits for the previous fetch to finish, preventing overlapping requests.
- **Power action deferral:** auto-refresh pauses during confirmation dialogs and in-flight power actions.
- **Bounded exponential backoff:** 30 s on first error, 60 s maximum, resetting only after a successful fetch. Stale data is retained and labeled during backoff.
- **Single loading owner:** fleet overview, hosts, and guest tabs share one loading scheduler; node and VM/LXC detail screens manage their own.

### Acknowledged Task Isolation from Background Telemetry

When a power action (Start, Shutdown, Force Stop) completes, its acknowledged result — including warnings — is now isolated from subsequent background telemetry refreshes or lagging PDM cache entries. A subsequent network hiccup does not corrupt or override an already-acknowledged task outcome.

Preflight target verification rejects power mutations against selected unreachable remotes before dispatching any HTTP POST.

---

## Verification Summary

| Property | Value |
| --- | --- |
| **Version** | `0.1.5-alpha` (versionCode `8`) |
| **Application ID** | `com.homelabpocket` |
| **Compatibility** | Android 8.0+ (minSdkVersion 26, targetSdkVersion 29) |
| **Candidate APK size** | `207853 bytes` |
| **Candidate SHA-256** | `7c247714d3cb04854ad10c01184443c353d34742d0c2732cb2e232dd2ddbe8b1` |
| **Signing certificate SHA-256** | `869037568e2aabf3b1030d21fc08ed331d4732f3387929504d1b2e3b38ebae5e` |
| **APK signatures** | Scheme v2 and v3 verified |
| **Automated behavioral tests** | **354 passed / 0 failed** (100% pass rate across 25 test classes: 330 baseline + 24 field regressions in `PhoneFieldRegression015Test`) |
| **Baseline tests preserved** | All 330 baseline tests preserved |
| **Independent probes** | All GREEN (exit 0): `TerminalStatusProbe` (10/10), `OwnerProbe`, `AdditionalProbe`, `LeaseProbe`, `MixedEndpointProbe`, `PartialAdmissionProbe`, `FinalFleetProbe` |
| **Candidate qualification** | Headless-verified candidate for phone testing |

### Signing continuity

The signing certificate (`869037568e2aabf3b1030d21fc08ed331d4732f3387929504d1b2e3b38ebae5e`) is the same certificate used for every release from v0.1.0-alpha through v0.1.3-alpha. Installing v0.1.5-alpha over an existing v0.1.3-alpha install is a standard Android upgrade (application ID `com.homelabpocket` unchanged).

---

## Known Limitations & Disclosures

- **Physical phone runtime gap:** All verification was performed on headless JVM test harnesses, mock Android doubles, and loopback HTTPS fixtures; phone runtime is NOT_TESTED. Physical phone runtime — Android Activity windowing, live dialog display, hardware Keystore, and live power action execution on physical hardware — remains an explicit acceptance gap not covered by the automated test suite. No live lab environments were tested.

- **VM guest shutdown failure cause: UNKNOWN.** A VM guest shutdown failure was reported on a physical phone. Source-level analysis identifies code conditions consistent with the reported failure, but the specific runtime cause on that physical device remains UNKNOWN without physical device and hypervisor logs. The v0.1.5 fixes address the identified code-level conditions; whether they resolve the specific physical phone occurrence requires end-user validation on physical hardware.

- **Request deadline budgets & timing limitations:** The 15 s fleet resources and 5 s optional storage enrichment budgets represent client source fetch bounds; credential acquisition occurs before node data loading and is not included in these bounds. Synthetic sibling delay tests (100 ms fixture) demonstrate sibling failure isolation at the transport seam, but do not prove a 20 s UI deadline or physical-runtime completion. No total end-to-end UI ceiling is proven.

- **Concurrency guard scope:** `ActionGuard` holds during client-side operation tracking, not necessarily through server-side completion. Cancelling an operation stops client tracking; the already-dispatched server operation may continue. After a timeout or network failure, verify guest and task state in the Proxmox interface before submitting another action.

- **PDM reboot unsupported upstream:** Upstream Proxmox Datacenter Manager API provides no guest reboot endpoint. PDM connections support Start, Graceful Shutdown, and Force Stop. The app rejects PDM reboot requests honestly rather than attempting unsafe synthetic alternatives.

- **Cadence target vs. metric freshness:** The configured auto-refresh interval is a client polling cadence target, not a guarantee of hypervisor metric freshness. Upstream PDM metric collection cycles may limit actual metric freshness regardless of client polling frequency.

- **Storage saves not crash-atomic:** Configuration and credentials span separate SharedPreferences files. Saves across both files are not guaranteed atomic. After an abnormal shutdown or storage error, review saved server configuration before reconnecting.

- **Screenshots:** Existing screenshots in the README are real Android captures from v0.1.3-alpha. No new screenshots from the v0.1.5-alpha candidate are included; physical phone captures of this candidate have not been taken.

- **Debug signing:** This is a debug-signed alpha build for homelab and testing use. Production signing is not configured in the default CI workflow. Local builds and CI builds use ephemeral keys that cannot upgrade an official release APK.

---

## Upgrade from v0.1.3-alpha

The v0.1.5-alpha APK is signed with the same certificate as v0.1.3-alpha (and all prior official releases). Android will treat this as a standard in-place upgrade of the existing install; no data migration or uninstall is required.

After upgrading:
1. Confirm the installed version shows `0.1.5-alpha` in the app.
2. If you use PDM connections, navigate to a node detail screen and verify that initial telemetry shows `-` placeholders (not `0.0%` / `0 / 0 GB`) before data loads.
3. If your PDM deployment has multiple remotes and one is offline, confirm that the online remote's node detail loads successfully without hanging or infinite spinners.

---

*See [CHANGELOG.md](../CHANGELOG.md) for the complete technical change log and [VERIFICATION.md](../VERIFICATION.md) for version-scoped verification evidence.*
