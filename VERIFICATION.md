# v0.1.3-alpha verification

## Independently exercised for this release

- Isolated build passed resource compilation, Java compilation, **254 JVM behavioral tests**, D8, APK packaging, zipalign, and signature verification.
- The 254-test suite incorporates the 8 frozen acceptance probes overlapping the main behavioral suite (verifying they are part of the baseline, not 8 additional unique test cases).
- API token 401 deadline fixture verified: under lock contention, the 40ms deadline returns bounded in 40ms rather than stalling (previously 361ms RED).
- Exercised PDM guest power dispatch (Start, Graceful Shutdown, Force Stop) with non-retrying POST execution, `ActionGuard` concurrent action prevention (active during client tracking), and task lifecycle tracking.
- Exercised storage backend status enrichment (`/status` endpoint for `dir`, `lvm`, `lvmthin`, `zfspool`, `nfs`) with bounded LRU caching and fallback handling on HTTP 403 or unpopulated endpoints.
- Exercised presentation handling for `UNKNOWN` guest/host states and radial CPU/RAM fleet gauges.
- The four gallery images (`docs/screenshots/{overview,hosts,guests,node-detail}.png`) are actual user-provided phone captures with cropped status and navigation bars. They demonstrate real UI rendering, live multi-cluster inventory, guest power controls, and storage pool types (`lvmthin`, `dir`). No credentials or private network information are exposed.

## Released APK

- Filename: `pve-pocket-v0.1.3-alpha.apk`
- Application ID: `com.homelabpocket`
- Version: `0.1.3-alpha`, versionCode `6`
- Minimum Android API: `26`; target API: `29`
- Size: **175085 bytes**
- SHA-256: `077e5ca653392c73ebf6b3f82540a78d0e69df30ba2ac218447c87a6cf6ae234`
- APK Signature Scheme v2 and v3 verified.
- Signing certificate SHA-256: `869037568e2aabf3b1030d21fc08ed331d4732f3387929504d1b2e3b38ebae5e`, matching the certificate used for v0.1.0-alpha, v0.1.1-alpha, and v0.1.2-alpha for upgrade continuity.
- This is a debug-signed alpha for testing. Local builds may use different debug keys; CI keys are ephemeral, and neither can upgrade this release artifact.
- Cloud CI status: The release workflow verifies this commit on Ubuntu/JDK 17 before APK publication; see the repository Actions tab for its result.

## Limits and disclosures

- Concurrency guard (`ActionGuard`) only holds during client tracking, not necessarily server-side completion. Cancel stops client tracking, not an already-dispatched server operation. Timeout/network failure may leave the outcome unknown; verify guest/task state before submitting another action.
- Real device screenshots confirm phone UI layout and live inventory display. They do not constitute comprehensive physical device lifecycle, Keystore, biometric, or live power-action qualification. Automated JVM tests are not full device tests.
- Testing was conducted without issuing live power mutation requests to production hypervisors.
- Upstream Reboot Limitation: Guest reboot is not supported by upstream PDM API endpoints. The app honestly rejects PDM guest reboot and directs users to perform Graceful Shutdown and wait until confirmed stopped before issuing Start; no synthetic or simulated reboots are performed.
- Storage backend status enrichment queries `/nodes/{node}/storage/{storage}/status` with an in-memory bounded LRU cache. Older PDM versions or permission-restricted accounts cleanly fall back to `Unavailable` or `?`.
- Storage durability: configuration and credentials reside in separate SharedPreferences files. Writes are not crash-atomic across files.
- Terminal streaming remains unsupported on both backends; browser fallback opens the web management interface.

---

# Historical v0.1.1-alpha verification

## Independently exercised for this release

- Frozen source-only release snapshot reviewed separately from the UI development candidate. Application and test changes were authored by Antigravity with controller review; factual verification disclosures were corrected during release review.
- Isolated ARM64 build passed resource compilation, Java compilation, **184 JVM behavioral tests**, D8, APK packaging, alignment and signature verification.
- Independently recompiled and reran the **8 unchanged controller probes**: backend cookie boundaries, cookie-name case, first-pair parsing, partial-TFA refusal, encoded delimiters, reflected HTTP errors and redirects. **8 passed**.
- The full suite includes four authenticated ticket/token HTTP 400 and redirect-refusal tests using the corrected relative `/remotes` path. The real loopback HTTPS handlers are reached.
- All application/build inputs in the publication tree match the independently exercised source snapshot. Only documentation differs. The four README images are actual user-provided phone captures, with status/navigation bars cropped; visible hostnames were approved. No visible IP addresses or credentials were found during independent visual review.

## Released APK

- Filename: `pve-pocket-v0.1.1-alpha.apk`
- Application ID: `com.homelabpocket`
- Version: `0.1.1-alpha`, versionCode `4`
- Minimum Android API: `26`; target API: `29`
- Size: **150435 bytes**
- SHA-256: `7760163c345b8a8413b70be8444192adc1373ac2233a1d9a8b557366f2f55d2d`
- APK Signature Scheme v2 and v3 verified. Signing certificate SHA-256: `869037568e2aabf3b1030d21fc08ed331d4732f3387929504d1b2e3b38ebae5e`, independently matched to the previously released v0.1.0-alpha APK for upgrade compatibility.
- This is a debug-signed alpha. GitHub Actions uses an ephemeral CI signing key; its artifact is not the upgrade APK. The separate controller rebuild also passed; archive timestamps can change its file hash, so the release hash above identifies the exact selected Pi-built artifact.
- Current-release Ubuntu/JDK 17 GitHub Actions evidence is available in the repository's [Actions history](https://github.com/CrashxZ/pve-pocket/actions). Its status must be checked independently; ARM64 success alone does not establish Ubuntu success.

## Limits

- User-provided phone screenshots confirm PDM login and read-only inventory against a live deployment. They do not establish comprehensive Android activity, Keystore, biometric or power-action acceptance.
- PDM power actions, shell streaming and built-in terminal streaming remain unsupported. Missing PDM rootfs/rate/type values remain visibly incomplete in this version; UI work belongs to the separate unreleased 0.1.2-alpha candidate.
- Preference rollback remains in-process, not a crash-atomic multi-file transaction. Fresh toolchain bootstrap was not exercised for this release.
- Fixed HTTP-error descriptions and redirect refusal were behaviorally tested. Logging redaction is defense-in-depth, not a guarantee for arbitrary adversarial JSON text; escaped JSON strings need further hardening. The logging helper has no current production callers.

---

# Historical v0.1.0-alpha verification

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
