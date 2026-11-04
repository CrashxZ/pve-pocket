# PVE Pocket

> **A native, fail-closed Android client for Proxmox VE and Proxmox Datacenter Manager (PDM).**

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Release](https://img.shields.io/badge/Release-v0.1.0--alpha-green.svg)](CHANGELOG.md)
[![Android](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026%2B)-brightgreen.svg)](app/src/main/AndroidManifest.xml)

PVE Pocket provides real-time telemetry, guest instance monitoring, and fleet management for Proxmox Virtual Environment (PVE) and Proxmox Datacenter Manager (PDM). Built with a strict **fail-closed security architecture**, hardware-backed Keystore encryption, and pure native Android views designed to avoid missing system WebView crashes.

---

## ✨ Features

- **Fleet Discovery & Multi-Cluster Support**:
  - Connect directly to standalone **Proxmox VE (PVE)** nodes (port `8006`).
  - Connect to **Proxmox Datacenter Manager (PDM)** instances (port `8443`) for unified multi-cluster fleet monitoring and resource discovery (`/api2/json/resources`).
  - Remote/node identity preservation across clusters (`{remote}/{node}`).

- **Hardware & Storage Telemetry**:
  - Node CPU load, core count, RAM allocation, uptime, and release kernel.
  - **Explicit Storage Pool Accounting**: Root filesystem (`/`) is clearly distinguished from configured datastores (`/nodes/{node}/storage`).
  - Supports `dir`, `lvm`, `lvmthin`, `zfspool`, and `nfs` pools with used, total, and available capacity.
  - Avoids double-counting shared network datastores across nodes.

- **Hosted Guest Instance Tracking**:
  - Inspect QEMU virtual machines and LXC containers with live and stopped statuses.
  - Dynamic status indicators with resource utilization meters (CPU & RAM).
  - Clean state differentiation: loading, offline hosts, permission-denied (403), authentication-expired (401), and zero-guest states with actionable explanations.

- **Safe Native Terminal Fallbacks**:
  - **Zero WebView Dependency**: Eliminates `MissingWebViewPackageException` and XML inflation crashes on devices lacking Google WebView.
  - **Honest Terminal Routing Decision Engine (`TerminalRoutingDecision`)**: Homelab Pocket never invents IP addresses or synthetic routes. Direct SSH is strictly disabled whenever the target cannot be verified (e.g., PDM remote nodes, LXC/QEMU containers without guest network configuration, and PVE cluster peer nodes differing from the configured API node).
  - **Explicit Management UI Labeling**: The web console browser fallback is explicitly labeled as opening the configured management endpoint web UI, not the selected guest console.
  - **Caller Honesty**: In `NodesView` and `NodeDetailActivity`, PDM node shell buttons display `No Shell (PDM)` / `Shell Unavailable on PDM` and are disabled.

- **Fail-Closed Security Model**:
  - **Hardware Keystore Encryption**: Stored credentials encrypted with AES-256-GCM via the Android Keystore system.
  - **Strict TLS Certificate Pinning**: SHA-256 fingerprint validation on every TLS handshake (`PinnedTrustManager`).
  - **Optional Explicit Per-Server TLS Skip**: Individual opt-in bypass requiring interactive confirmation (default: OFF). Persistently displays `⚠️ TLS VERIFICATION DISABLED (Insecure)` in danger/amber colors whenever enabled, even if an old certificate fingerprint is retained.
  - **Session Locking**: Biometric/device-credential screen lock protects in-memory credentials.
  - **Zero Telemetry**: No third-party analytics, tracking SDKs, or background telemetry.

---

## ⚠️ Alpha Limitations (v0.1.0-alpha)

See [independent verification and signing details](VERIFICATION.md). This alpha is debug-signed and has not been launched on a physical Android device. Saves span separate preference files: failed-disk-write tests establish in-process rollback, **not crash-atomic or guaranteed durable rollback** when recovery writes also fail. Review saved endpoints after a storage error before reconnecting.

Please note the following honest disclosures and architectural limitations in this alpha release:

1. **PDM Power & Terminal Routes Disabled & Live-Device Qualification**:
   - Guest power actions (start/stop/reboot) and terminal streams via PDM are explicitly disabled in this release to prevent speculative endpoint misrouting. Terminal streaming is disabled.
   - PDM mode provides read-only discovery, inventory tracking, and telemetry based on upstream API conformance testing.
   - **Live-Device Qualification:** PDM integration has NOT been verified on live physical PDM appliance hardware or physical Android devices. To perform power operations, connect directly to the target host's standalone PVE endpoint.

2. **Built-in Live Streaming Terminal Disabled & SSH Target Honesty**:
   - Proxmox `termproxy` / `vncwebsocket` requires a dedicated pinned WebSocket client transport. Built-in interactive streaming is disabled to ensure honest operation without false-success prompts.
   - Direct SSH is disabled when the target host cannot be verified to match the selected guest or peer node. External SSH client dispatch and web console browser fallbacks are available as alternative mechanisms.

3. **Behavioral JVM Tests vs Device Acceptance**:
   - The test suite executes 158 automated behavioral tests across API, security, routing, transactional rollback, and lifecycle components on an ARM64 JVM.
   - **Important Limitation:** These automated receipts prove software logic, HTTP protocol conformance, and crypto boundaries, but do NOT constitute physical Android hardware device proof (no physical phone or emulator was attached to the build host).

4. **Internal Application ID**:
   - The application ID remains `com.homelabpocket` to ensure seamless upgrade continuity for existing users. The user-facing application label is **PVE Pocket**.

5. **Demo Mode Removed**:
   - All synthetic mock fixtures and demo toggles have been removed. The application only interacts with user-configured servers.

---

## 🛠️ Building from Source

PVE Pocket uses a transparent, portable shell-based build pipeline supporting both standard Ubuntu x86_64 environments and ARM64 single-board computers (such as Raspberry Pi).

### Option A: Building on Ubuntu x86_64 / Debian (Aligned with GitHub Actions Setup)

```bash
# 1. Install prerequisites (JDK 21, test dependencies, packaging utilities)
sudo apt-get update
sudo apt-get install -y openjdk-21-jdk junit4 libhamcrest-java libandroid-json-java zipalign

# 2. Install Android SDK Platform 29 and Build-Tools (d8, aapt) via sdkmanager
# (Ubuntu apt packages alone do not install platform-29 or d8; install via sdkmanager matching CI)
yes | sdkmanager --licenses || true
sdkmanager "platforms;android-29" "build-tools;29.0.3"

# 3. Run the portable build script
export ANDROID_HOME="${ANDROID_SDK_ROOT:-/usr/lib/android-sdk}"
bash tools/build.sh
```

> **CI / Ubuntu Runner Qualification:** The Ubuntu x86_64 build recipe and `.github/workflows/ci.yml` align with standard Android SDK tooling, but remote GitHub Actions CI runner execution remains untested until actual GitHub Actions runs execute in a remote cloud runner. Local verification receipts reflect execution on the ARM64 reference host.

### Option B: Building on Raspberry Pi (ARM64)

```bash
# 1. Run user-local bootstrap (verifies pinned SHA-256 tool checksums and writes tools/env.sh)
bash tools/bootstrap-tools.sh

# 2. Compile and run behavioral tests
source tools/env.sh
bash tools/build.sh
```

> **Environment & Verification Disclosure:** The master build pipeline and behavioral test suite (158 tests) were verified locally on an ARM64 Linux development host (Raspberry Pi OS). While `tools/bootstrap-tools.sh` has been designed to verify pinned SHA-256 checksums and `tools/build.sh` supports standard Ubuntu x86_64, GitHub Actions CI workflows have not yet executed in a remote cloud runner and real physical Android hardware deployment has not been verified. Toolchain bootstrap from scratch was not re-exercised on this offline device.

The resulting signed APK will be written to `artifacts/pve-pocket-debug.apk`.

---

## 🔐 Signing & Release Management

- **CI Ephemeral Debug Builds**: Pull request and branch builds generated by GitHub Actions are signed with an ephemeral debug keystore generated during the build. These builds are intended for testing and behavioral verification only.
- **Production Upgrades**: To update an existing device install without uninstalling, the APK must be signed with the repository owner's original private signing key.
- **Release Signing Pipeline (Future / Unconfigured)**:
  Dedicated GitHub Actions release secret injection (`RELEASE_KEYSTORE_BASE64`, `RELEASE_KEY_ALIAS`, etc.) is currently unconfigured in the default CI workflow to maintain a strictly read-only, credential-less security posture. Production releases are signed offline or through dedicated owner-configured release workflows; configuring these secrets in GitHub repository settings does not automatically enable production release publishing in the current workflow.

---

## 📄 License

PVE Pocket is licensed under the [GNU General Public License v3.0 or later](LICENSE) (GPL-3.0-or-later).
Third-party component notices and licenses are documented in [NOTICE.md](NOTICE.md).
Security policies and vulnerability disclosure guidelines are documented in [SECURITY.md](SECURITY.md).
