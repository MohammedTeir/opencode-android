# OpenCode Mobile (prototype)

A native Android proof of concept that packages ARM64 PRoot, installs a Debian Bookworm ARM64 root filesystem and the original OpenCode V2 CLI on first run, starts the V2 web/API server in a foreground service, and displays its loopback web UI in an authenticated WebView. It has no Termux dependency. It is not yet release-ready or validated on a physical Android device.

## Scope and milestones

The app implements M1–M4 plus the requested V2 migration and CI/release workflows. Later product polish remains out of scope until M1–M4 pass on a real phone.

1. **M1:** install Debian and run `/bin/echo hi` through the bundled PRoot executable.
2. **M2:** download and checksum-verify OpenCode V2.3.2 ARM64, install Git/ripgrep/curl/CA certificates/procps, and create V2-native permission defaults.
3. **M3:** run `opencode serve` in an Android foreground service, bind only to `127.0.0.1`, keep a random 256-bit password in private app storage, and pass it through `OPENCODE_PASSWORD`.
4. **M4:** require authenticated JSON from `/api/info` reporting V2.3.2, then load the server UI in an authenticated WebView. Navigation is limited to `http://127.0.0.1:4096`.

The app never binds `/sdcard` or other Android storage directories. Default V2 permissions allow read/glob/grep and ask before other actions, including edits, shell commands, and access outside the OpenCode working directory. Existing config files are preserved; V2 supports its documented V1 config format during migration. Users must still inspect the actual path or command shown in each permission prompt. A user-invoked control can open Android battery-optimization settings.

## Build locally

Requirements: JDK 17 or newer, Android SDK Platform 34, Build Tools 34.0.0, and Gradle 8.9. The Gradle wrapper is included. Only `arm64-v8a` is packaged.

```sh
./gradlew assembleDebug
```

The debug APK output is `app/build/outputs/apk/debug/app-debug.apk`. Install it on an ARM64 phone running Android 8.0 or newer. An x86 emulator is not supported. This ARM64 sandbox cannot run Google’s x86-64 AAPT2, so use the included GitHub Actions workflow for hosted x86-64 Android builds.

## GitHub Actions

`.github/workflows/android-ci.yml` builds a debug APK on pushes to `main` and pull requests. `.github/workflows/android-release.yml` builds a signed release APK and creates a GitHub Release when a `v*` tag is pushed.

Release signing is configured with these repository Actions secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`. The keystore is supplied to `ANDROID_KEYSTORE_BASE64` as one-line Base64; the configured key alias is `opencode-release`. The keystore and recovery values are backed up separately from this repository. Never commit a keystore, Base64 keystore content, or signing passwords. If the secrets are missing, the release workflow intentionally stops before signing. The permanent application ID is `io.github.mohammedteir.opencodemobile`.

After the pinned initial install, the setup screen can check the official OpenCode GitHub releases API for a newer stable ARM64 release. Updates require explicit user approval, accept only the exact `opencode-linux-arm64.tar.gz` asset hosted on GitHub, verify its SHA-256 digest, and keep the previous binary at `usr/local/bin/opencode.previous` during replacement. That rollback copy is deleted only after the updated server starts and authenticated `/api/info` confirms the installed version. The update check does not downgrade the installed version and does not run while the OpenCode server is active.

## Pinned inputs

- **PRoot for Android:** release `v26.10.08-a179d3e`; ARM64 archive SHA-256 `d621d71fb68bf07accbada37ffc4208cf4a963eed70916ee68f848cbd9555cc3`. The binary has not been exercised as an Android process.
- **Debian Bookworm ARM64 rootfs:** `https://images.linuxcontainers.org/images/debian/bookworm/arm64/default/20261117_05:24/rootfs.tar.xz`; SHA-256 `125416b51b1272db7abd932bc7cc5f286fd29a190cb5a1b18460500f5448d5fc`.
- **OpenCode V2.3.2 ARM64 release:** `https://github.com/anomalyco/opencode/releases/download/v2.3.2/opencode-linux-arm64.tar.gz`; SHA-256 `9d10185acdb72336e086e699ca363a6065f410fd3c1caed0dc8046e95ac06ccb`.

The Debian rootfs and OpenCode archives are downloaded at first run rather than embedded in the APK and are verified against pinned digests. Rootfs extraction uses a fresh staging directory, rejects traversal and symlink-parent traversal, and skips device nodes and FIFOs.

## Validation still required

The V2.3.2 archive checksum matched GitHub’s published digest, and the binary reported version 2.3.2 on native ARM64 Linux. A local smoke test confirmed authenticated `/api/info`, the HTML web UI at `/`, and HTTP 401 for an unauthenticated API request. This did **not** test PRoot or Android.

On a physical ARM64 device, verify M1–M4 in order, including rootfs symlinks, DNS/HTTPS inside PRoot, package installation, the OpenCode version, permission prompts, WebView auth and streaming, server behavior with the screen off, and restart without duplicate listeners. Repeat on supported Android versions and vendors. Android’s phantom-process killer and vendor battery policies may still terminate PRoot children. Keep the foreground-service notification active.

Do not distribute this prototype until M1–M4 pass on real devices, the release signing key has a secure backup, the application ID/versioning are finalized, and third-party license/source obligations and merged notices are addressed.
