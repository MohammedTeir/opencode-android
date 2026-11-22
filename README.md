# OpenCode Mobile

A native Android app that packages ARM64 PRoot, installs a Debian Bookworm ARM64 root filesystem and the original OpenCode V2 CLI on first run, starts the V2 web/API server in a foreground service, and displays its loopback web UI in an authenticated WebView. It is a single installable APK and has no Termux dependency. The first-run installer performs Debian setup, a real PRoot execution check, OpenCode installation, and required Linux package installation in one resumable action. Large Debian and OpenCode payloads are downloaded into app-private storage and SHA-256 verified rather than inflating every APK release.

## Scope and milestones

The app implements M1–M4 plus the requested V2 migration and CI/release workflows. Later product polish remains out of scope until M1–M4 pass on a real phone.

1. **M1:** install Debian and run a real command through the PRoot executable bundled in the APK.
2. **M2:** download and checksum-verify OpenCode V2.3.2 ARM64, install Git/ripgrep/curl/CA certificates/procps, and create V2-native permission defaults. The one-tap installer resumes from completed stages.
3. **M3:** run `opencode serve` in an Android foreground service, bind only to `127.0.0.1`, keep a random 256-bit password in private app storage, and pass it through `OPENCODE_PASSWORD`.
4. **M4:** require authenticated JSON from `/api/info` reporting V2.3.2, then load the server UI in an authenticated WebView. Navigation is limited to `http://127.0.0.1:4096`.

Shared storage is optional. On Android 11 and newer, users can explicitly grant Android **All files access** from the setup screen; when granted, every PRoot process binds the primary shared-storage directory at both `/sdcard` and `/storage/emulated/0`. Without the permission, no Android shared-storage bind is added. Android still restricts other apps' private data, including `Android/data` and `Android/obb`. Direct shared-storage mounting is unavailable on Android 8–10; use Android 11+ for this feature. The permission is sensitive and may make the app ineligible for Google Play distribution unless its core use case qualifies under Play policy. The server records whether shared storage was mounted when it started. If All files access is granted or revoked while the server is running, the setup screen warns that the running PRoot mount cannot be changed in place; stop the server from its notification and launch it again. The setup screen includes a directory browser to choose a persistent OpenCode workspace folder under shared storage and a temporary read/write check that writes a unique probe file under `/sdcard/Download`, verifies it through `/storage/emulated/0/Download`, and removes it. OpenCode starts with the selected directory as its working directory; if it is no longer accessible, it safely falls back to `/root`. Changing the selected directory requires stopping and restarting the server. The folder browser canonicalizes paths and refuses selections that resolve outside the primary shared-storage root. Default V2 permissions allow read/glob/grep and ask before other actions, including edits, shell commands, and access outside the OpenCode working directory. Android OS access and OpenCode's own `external_directory` permission are separate: both must allow an operation. Existing config files are preserved; V2 supports its documented V1 config format during migration. Users must still inspect the actual path or command shown in each permission prompt. A user-invoked control can open Android battery-optimization settings.

## Debian rootfs integrity record

After the pinned Debian Bookworm ARM64 archive passes SHA-256 verification and extraction checks, the installer writes an atomic, app-private installation record containing the distribution, release, architecture, source URL, pinned archive digest, completion marker, installation timestamp, and canonical rootfs path. Runtime diagnostics validate that record against the app's pinned inputs and check critical Debian paths, including the shell, package manager, and dpkg database.

This is an installation-provenance and structural-integrity check, **not** a cryptographic checksum of every live Debian file. The rootfs is mutable: APT upgrades, package installs, and user changes legitimately alter its contents. Existing installations created before this record was introduced are reported as legacy/degraded rather than being modified or rejected. A missing or invalid record never triggers automatic deletion or reinstallation. A future recovery action must be explicit, preserve backups, and account for projects stored inside the Debian filesystem.

## Runtime component health

The runtime diagnostics include a read-only component inventory for the APK-bundled PRoot executable/loader, Debian core filesystem, OpenCode CLI/version metadata, required Debian tools, and OpenCode default configuration. Each component reports ready, degraded, missing, or incomplete, with a targeted recovery hint. The inventory checks expected files and metadata; it is not a cryptographic checksum of the installed Debian filesystem and does not execute the OpenCode binary to prove runtime behavior. Network and real PRoot execution checks remain separate. The inventory never deletes, repairs, or overwrites files.

## Build locally

Requirements: JDK 17 or newer, Android SDK Platform 34, Build Tools 34.0.0, and Gradle 8.9. The Gradle wrapper is included. Only `arm64-v8a` is packaged.

```sh
./gradlew assembleDebug
```

The debug APK output is `app/build/outputs/apk/debug/app-debug.apk`. Install it on an ARM64 phone running Android 8.0 or newer. An x86 emulator is not supported. This ARM64 sandbox cannot run Google’s x86-64 AAPT2, so use the included GitHub Actions workflow for hosted x86-64 Android builds.

## GitHub Actions

`.github/workflows/android-ci.yml` builds a debug APK on pushes to `main` and pull requests. `.github/workflows/android-release.yml` builds a signed release APK and creates a GitHub Release when a `v*` tag is pushed.

Release signing is configured with these repository Actions secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`. The keystore is supplied to `ANDROID_KEYSTORE_BASE64` as one-line Base64; the configured key alias is `opencode-release`. The keystore and recovery values are backed up separately from this repository. Never commit a keystore, Base64 keystore content, or signing passwords. If the secrets are missing, the release workflow intentionally stops before signing. The permanent application ID is `io.github.mohammedteir.opencodemobile`.

After the pinned initial install, the setup screen can check the official OpenCode GitHub releases API for a newer stable ARM64 release. Updates require explicit user approval, accept only the exact `opencode-linux-arm64.tar.gz` asset hosted on GitHub, verify its SHA-256 digest, and keep the previous binary at `usr/local/bin/opencode.previous` with its version metadata. The rollback copy remains available after the updated server starts; a later update may replace the single rollback slot. Updates do not downgrade the installed version and do not run while the OpenCode server is active.

The setup screen also provides **Repair OpenCode** and **Restore previous** actions. Repair downloads the latest stable ARM64 release in the app's supported major-version channel, verifies its published SHA-256 digest, stages the executable, and replaces only the OpenCode binary. It does not reinstall Debian or overwrite OpenCode configuration or projects, and it refuses a downgrade. If a known-good rollback copy exists, repair preserves it. Restore previous swaps the saved binary back into place and retains the replaced executable under a timestamped `opencode.failed-*` filename. Both actions require the server to be stopped.

## Pinned inputs

- **PRoot for Android:** release `v26.10.08-a179d3e`; ARM64 archive SHA-256 `d621d71fb68bf07accbada37ffc4208cf4a963eed70916ee68f848cbd9555cc3`. The binary has not been exercised as an Android process.
- **Debian Bookworm ARM64 rootfs:** `https://images.linuxcontainers.org/images/debian/bookworm/arm64/default/20261117_05:24/rootfs.tar.xz`; SHA-256 `125416b51b1272db7abd932bc7cc5f286fd29a190cb5a1b18460500f5448d5fc`.
- **OpenCode V2.3.2 ARM64 release:** `https://github.com/anomalyco/opencode/releases/download/v2.3.2/opencode-linux-arm64.tar.gz`; SHA-256 `9d10185acdb72336e086e699ca363a6065f410fd3c1caed0dc8046e95ac06ccb`.

The Debian rootfs and OpenCode archives are downloaded at first run rather than embedded in the APK and are verified against pinned digests. Rootfs extraction uses a fresh staging directory, rejects traversal and symlink-parent traversal, and skips device nodes and FIFOs. If Android force-stops the app during extraction, the next setup attempt removes only abandoned directories matching the app's private `rootfs-staging-<timestamp>` pattern before retrying; a verified cached archive is retained for reuse.

## Validation still required

The V2.3.2 archive checksum matched GitHub’s published digest, and the binary reported version 2.3.2 on native ARM64 Linux. A local smoke test confirmed authenticated `/api/info`, the HTML web UI at `/`, and HTTP 401 for an unauthenticated API request. This did **not** test PRoot or Android.

On a physical ARM64 device, verify M1–M4 in order, including rootfs symlinks, DNS/HTTPS inside PRoot, package installation, the OpenCode version, OpenCode permission prompts, WebView auth and streaming, server behavior with the screen off, and restart without duplicate listeners. For shared storage, test granting and revoking All files access, read/write operations under `/sdcard` and `/storage/emulated/0`, and confirm every PRoot entry point uses the same mount. Verify `Android/data` and `Android/obb` remain inaccessible. Repeat on supported Android versions and vendors. Android’s phantom-process killer and vendor battery policies may still terminate PRoot children. Keep the foreground-service notification active.

Before public distribution, M1–M4 must pass on real devices, the release signing key must have a secure backup, application ID/versioning must be finalized, and third-party license/source obligations and merged notices must be addressed.


## Workspace locations

OpenCode Mobile supports two selectable workspace roots:

- **Debian filesystem:** projects live inside the app-private Debian rootfs. The default development directory is `/root/projects`, recommended for Git repositories, package managers, builds, and Linux tooling.
- **Android shared storage:** when Android's All files access permission is granted, shared storage is mounted into PRoot at `/sdcard` and `/storage/emulated/0`. Select folders such as `/sdcard/Projects`, `/sdcard/Download`, or `/sdcard/Documents` to work on them directly.

The workspace picker first asks which filesystem to browse, then lets the user navigate and select a directory. OpenCode's normal permission system still governs file and shell operations; a PRoot bind mount does not grant blanket OpenCode authorization. Changing the workspace requires stopping and restarting the server.


## PRoot network and provider DNS

Before each PRoot process starts, the app refreshes Debian's resolver configuration from Android's active network and resolves OpenCode provider endpoints through Android's per-network resolver. A managed, replaceable block in Debian's `/etc/hosts` provides a fallback when direct DNS packets from Linux userspace cannot use the active network's upstream resolvers. The fallback includes common built-in providers (Gemini, Vertex AI, OpenAI, Anthropic, OpenRouter, Groq, Mistral, Cohere, DeepSeek, xAI) and GitHub endpoints. It also discovers custom provider hosts from `baseURL` / `baseUrl` / `base_url` values in the user's OpenCode configuration.

Provider IP addresses are resolved dynamically by Android; they are not hardcoded. This is a per-launch compatibility fallback, not a continuously running DNS proxy. If a provider rotates CDN addresses during a long-running OpenCode server session, restart the server to refresh the managed records. Normal Debian DNS remains configured through `/etc/resolv.conf` for other hostnames; arbitrary shell/MCP destinations not represented in the provider configuration still depend on direct DNS transport. HTTPS/TLS and provider authentication failures are separate from DNS resolution and are not bypassed by this fallback.
