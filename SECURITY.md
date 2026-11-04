# Security Policy

## Security Architecture

PVE Pocket is designed with a **fail-closed, privacy-first security posture** for homelab and enterprise Proxmox operators:

1. **Hardware-Backed Credential Protection**:
   - Authentication secrets (passwords and API tokens) are never stored in plaintext on disk, in SQLite databases, in shared preferences, or inside Android debug backups (`android:allowBackup="false"`).
   - Saved credentials are encrypted using AES-256-GCM authenticated encryption through the Android Keystore system. Encryption keys are non-exportable and guarded by hardware security modules (TEE / StrongBox) where available.

2. **Fail-Closed TLS Verification & Certificate Pinning**:
   - By default, all network connections to Proxmox VE (port 8006) and Proxmox Datacenter Manager (port 8443) strictly validate server TLS certificates against a pinned SHA-256 certificate fingerprint (`PinnedTrustManager`).
   - Hostname and Subject Alternative Name (SAN) validation strictly enforces RFC 6125 identity matching (`EndpointHostnameVerifier`), preventing TLS man-in-the-middle attacks across private or untrusted networks.
   - Self-signed certificates must be explicitly approved and pinned during onboarding.

3. **Per-Server TLS Bypass Option (Default OFF) & Persistent Warning**:
   - For environments utilizing dynamic self-signed certificates or temporary proxy setups, users may explicitly opt-in to skip TLS verification on a per-server basis.
   - This option is strictly server-scoped, defaults to OFF, and requires explicit confirmation in an interactive warning dialog prior to activation.
   - When enabled, the connection listing persistently displays a prominent `⚠️ TLS VERIFICATION DISABLED (Insecure)` indicator regardless of whether a previously pinned fingerprint is retained (`TlsVerificationStatus`).

4. **Honest Terminal & Network Routing Policy**:
   - PVE Pocket never invents IP addresses or synthetic network routing.
   - Direct SSH commands and client intent launches are strictly disabled when the target host cannot be verified to match the selected guest or peer node (`TerminalRoutingDecision`).
   - Browser console fallbacks are explicitly labeled as opening the configured management endpoint web UI, not the selected guest console.

5. **In-Memory Credential Isolation & Session Locking**:
   - Working credentials (`AuthCredentials`) exist in memory only as short-lived ticket tokens or API tokens.
   - Devices secured with biometric or lock-screen credentials can lock the active session. When locked, in-memory keys are latched and inaccessible until the user re-authenticates via system biometric/device credential prompts.

5. **Bounded Request Timeouts & Deadlines**:
   - All network operations enforce monotonic request budgets (connect, TLS handshake, header reading, body streaming) backed by watchdog timers to prevent hangs or resource exhaustion.

6. **Zero Analytics & Trackers**:
   - The application bundles zero advertising SDKs, zero crash analytics libraries, and zero external telemetry endpoints.

---

## Reporting a Vulnerability

We take the security of homelab infrastructure very seriously. If you discover a security vulnerability in PVE Pocket:

1. **Do NOT report security vulnerabilities via public GitHub issues.**
2. Please submit a private advisory through GitHub's [Security Advisories](https://github.com/CrashxZ/pve-pocket/security/advisories) tab or contact the repository maintainers directly.
3. Include detailed steps to reproduce, affected versions, and system environment details.
4. Maintainers will review and respond to reports within 48 hours and work with you to coordinate a patched release prior to public disclosure.
