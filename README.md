# PVE Pocket

A native Android app for monitoring and managing Proxmox VE (PVE) hosts and guests, with multi-cluster fleet monitoring and guest power controls through Proxmox Datacenter Manager (PDM).

**Android 8.0+ · GPL-3.0-or-later · Early alpha**

## Download

**Latest published version: [v0.1.3-alpha](https://github.com/CrashxZ/pve-pocket/releases/tag/v0.1.3-alpha)** — includes PDM VM/LXC power management (Start, Graceful Shutdown, Force Stop), storage backend status enrichment, radial fleet gauges, and security fixes.

- **[Download the Android APK](https://github.com/CrashxZ/pve-pocket/releases/download/v0.1.3-alpha/pve-pocket-v0.1.3-alpha.apk)**
- [Release notes and checksum](https://github.com/CrashxZ/pve-pocket/releases/tag/v0.1.3-alpha) · [All releases](https://github.com/CrashxZ/pve-pocket/releases) · [Changelog](CHANGELOG.md)

This is a **debug-signed test release**, not a production-ready client. See the [project roadmap](docs/ROADMAP.md) for future milestones.

## Screenshots

Real Android captures of **v0.1.3-alpha** connected to a live PDM deployment; system bars are cropped. These show the released app, not UI mockups.

<table>
  <tr>
    <td align="center"><img src="docs/screenshots/overview.png" width="260" alt="PDM fleet overview with radial CPU and memory gauges"><br><strong>Overview</strong></td>
    <td align="center"><img src="docs/screenshots/hosts.png" width="260" alt="PDM hosts list with online status and scannable host labels"><br><strong>Hosts</strong></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/guests.png" width="260" alt="PDM inventory of QEMU virtual machines and LXC containers with power controls"><br><strong>Guests</strong></td>
    <td align="center"><img src="docs/screenshots/node-detail.png" width="260" alt="PDM node details and enriched storage pools"><br><strong>Node and storage</strong></td>
  </tr>
</table>

## What works

| Capability | Direct PVE connection | PDM connection |
| --- | --- | --- |
| Endpoint | PVE API, usually port `8006` | PDM API, usually port `8443` |
| Inventory | Hosts, QEMU VMs and LXC containers | Multi-cluster inventory across remotes |
| Monitoring | CPU, memory, uptime and storage, subject to API permissions | Fleet/node/guest metrics; radial fleet CPU/RAM gauges; storage status enrichment (`dir`, `lvm`, `lvmthin`, `zfspool`, `nfs`) with fallback |
| Guest power actions | Start, stop and reboot, subject to permissions | **Start, graceful shutdown, and force stop** with confirmation dialogs, client-side resource guard, and task polling. **Reboot is unsupported upstream by the PDM API** (no synthetic reboot) |
| Shell and console | External SSH only when a target can be verified; browser fallback opens the management UI | **No remote shell or console routing**; management UI browser fallback only |

Built-in interactive terminal streaming is **not supported** on either backend. The browser fallback is not a selected guest's console.

The app uses native Android views without an embedded WebView dependency. Credentials are encrypted with AES-GCM through Android Keystore; connections support certificate fingerprint pinning and a device-credential/biometric session lock. TLS verification bypass is an explicit per-server opt-in with a persistent warning; leave it disabled. There are no third-party analytics or tracking SDKs.

## Install and connect

1. Download the release APK above and compare its SHA-256 (`077e5ca653392c73ebf6b3f82540a78d0e69df30ba2ac218447c87a6cf6ae234`) with the release notes.
2. Open it on Android 8.0 or newer and allow installation from that source when prompted.
3. Add your PVE or PDM endpoint and credentials. Use a reachable trusted network or VPN, grant only the permissions you need, and verify the server certificate fingerprint through a trusted channel.
4. Select the connection to browse hosts and guests and perform supported power actions. Direct PVE connections support start, stop, and reboot. PDM connections support start, graceful shutdown, and force stop (reboot is unsupported upstream).

The published v0.1.3 APK uses the same signing certificate as older releases (v0.1.0/v0.1.1/v0.1.2) and can upgrade an existing install. Local builds may use different debug keys; CI keys are ephemeral, and neither can replace an existing install. The application ID remains `com.homelabpocket` for upgrade continuity.

## Alpha limitations

- **Incomplete device coverage:** user-provided phone screenshots confirm phone UI layout and live inventory on a live PDM deployment, not comprehensive device lifecycle, Keystore, biometric, or live power-action execution. Automated JVM tests are not device tests. Testing was conducted without live power mutation requests.
- **Power action concurrency & tracking:** Concurrency guard (`ActionGuard`) only holds during client tracking, not necessarily server-side completion. Cancel stops client tracking, not an already-dispatched server operation. Timeout/network failure may leave the outcome unknown; verify guest/task state before submitting another action.
- **Upstream reboot limitation:** PDM guest reboot is not supported by upstream Proxmox Datacenter Manager API routes. PDM mode provides Start, Graceful Shutdown, and Force Stop only; the app honestly surfaces this limitation rather than attempting unsafe or synthetic reboots.
- **Storage backend status & metrics:** storage backend status enrichment queries upstream `/nodes/{node}/storage/{storage}/status` with a bounded cache; missing/unsupported metrics, HTTP 403, or older PDM versions fall back to `Unavailable` or `?`. Missing metrics are distinct from zero usage. UNKNOWN guest/node states are handled safely.
- **Storage errors:** configuration and credentials span separate preference files. Saves are not crash-atomic across files, and rollback is not guaranteed if recovery writes fail. Review saved endpoints after a storage error before reconnecting.
- **Debug signing:** this alpha and CI builds are for testing. Production release signing is not configured in the default workflow. Local builds may use different debug keys; CI keys are ephemeral and will not upgrade release APKs.

See [verification and signing details](VERIFICATION.md) for version-scoped evidence and [SECURITY.md](SECURITY.md) for security reporting.

## Build from source

The project uses a shell build pipeline, not a Gradle wrapper. Clone the repository first:

```bash
git clone https://github.com/CrashxZ/pve-pocket.git
cd pve-pocket
# Optional: build the published release instead of current main.
git checkout v0.1.3-alpha
```

### Ubuntu / Debian x86_64

Install **JDK 17** and the [Android SDK command-line tools](https://developer.android.com/studio#command-tools). Set `JAVA_HOME` to your JDK 17 installation, set `ANDROID_SDK_ROOT` to your SDK directory, and put `sdkmanager` on `PATH`. Then:

```bash
sudo apt-get update
sudo apt-get install -y openjdk-17-jdk junit4 libhamcrest-java libandroid-json-java zipalign
sdkmanager --licenses
sdkmanager "platforms;android-29" "build-tools;29.0.3"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
bash tools/build.sh
```

JDK 17 matches the [GitHub Actions workflow](.github/workflows/ci.yml); newer JDKs can be incompatible with this legacy SDK's D8.

### Raspberry Pi / Debian ARM64

The [ARM64 bootstrap](tools/bootstrap-tools.sh) downloads a user-local JDK 21 and Android toolchain; it requires network access, `curl`, `unzip`, `tar`, and Debian package tools/repositories. It is an alternative to the x86_64 SDK setup, not an additional step.

```bash
bash tools/bootstrap-tools.sh
source tools/env.sh
bash tools/build.sh
```

Both paths compile the app, run the JVM behavioral suite, package and sign **`artifacts/pve-pocket-debug.apk`**. Build outputs and signing keys must not be committed.

## Roadmap

See the [Roadmap](docs/ROADMAP.md) for recent shipped capabilities and future milestone priorities, including storage atomicity, widget support, and external console routing investigations.

[Report a bug or request a feature](https://github.com/CrashxZ/pve-pocket/issues). Do not include credentials, private keys or sensitive server details.

## License and attribution

Licensed under the [GNU General Public License v3.0 or later](LICENSE). See [NOTICE.md](NOTICE.md) for third-party notices and [SECURITY.md](SECURITY.md) for vulnerability disclosure.

Application and tests authored with Antigravity; independently reviewed and released with Hermes. Maintained by [Mainak Mondal / CrashxZ](https://github.com/CrashxZ).
