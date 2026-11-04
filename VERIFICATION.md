# v0.1.0-alpha verification

## Independently exercised

- Source-only export: application source, portable build scripts, JVM tests, documentation, GPL-3.0-or-later license and notices. No private signing material, generated test keystores, APKs, operational transcripts or machine-local configuration are included in Git.
- All 92 application/build input files match the independently built snapshot by SHA-256. Documentation is not an APK build input.
- ARM64 build from an isolated source copy: resources, Java compilation, **158 JVM tests**, D8, APK packaging, alignment and signature verification passed. Generated TLS test certificates were deliberately absent from the export and recreated by the build.
- Independently reran the unchanged Android-faithful 9-test save regression: **9 passed**.
- Independently reran a separate 9-test variant that makes credential rollback return false after the forward credential commit succeeds and the configuration commit fails: **9 passed**. This variant initially failed two assertions; those failing receipts were preserved before AGY repaired the implementation.
- Android SharedPreferences can mutate in-process state before commit returns false. Production save mutation flags are now set before persistence calls; rollback preserves original raw ciphertext instead of re-encrypting it, and rollback exceptions are attached to the save exception.

## Released APK

- Filename: `pve-pocket-v0.1.0-alpha.apk`
- Application ID: `com.homelabpocket`
- Version: `0.1.0-alpha`, versionCode `3`
- Minimum Android API: `26`; target API: `29`
- Size: 150435 bytes
- SHA-256: `e1108df9cff5d5adfbd0a11e31caca10862db8ecf5a9f0b21b5e34cf9b70fb50`
- APK Signature Scheme v2 and v3 verification passed.
- Signing certificate SHA-256: `869037568e2aabf3b1030d21fc08ed331d4732f3387929504d1b2e3b38ebae5e`
- This is an alpha **debug-signed** APK, not a production-release signing claim. CI generates its own ephemeral debug key; do not assume CI artifacts can upgrade this APK.

## Limits and cautions

- No physical Android phone/emulator launch or live PDM deployment acceptance was performed. JVM tests do not establish Android Keystore, biometric UI or activity launch behavior on a device.
- PDM is read-only; PDM power/terminal operations and built-in terminal streaming are disabled. External browser/SSH fallback availability depends on known target identity and installed device applications.
- Credentials and server configuration occupy separate SharedPreferences files. This is **not a crash-atomic cross-file transaction**. The passing false-commit tests establish in-process restoration and error propagation, not guaranteed durable restoration when subsequent disk writes also fail. After a storage error or process crash, review saved configuration before reconnecting; disk recovery across restart remains unverified.
- Local builds reused an existing ARM64 toolchain. Fresh bootstrap was not exercised. The Ubuntu recipe assumes Android SDK command-line tools (`sdkmanager`) are already installed and on PATH; the Actions setup step supplies them for CI.
- Cloud CI status is recorded separately by the actual GitHub Actions run. The original README's pre-publication CI disclaimer describes the point before that run, not a substitute for CI evidence.

Private operational reports with obsolete counts/checksums were intentionally excluded rather than represented as current verification.
