# Asset provenance for this prototype

- **PRoot archive:** https://github.com/ahmed-alnassif/proot/releases/download/v26.10.08-a179d3e/proot-aarch64.zip
  SHA-256: `d621d71fb68bf07accbada37ffc4208cf4a963eed70916ee68f848cbd9555cc3`
  Archive listing was inspected; it contains `proot`, `loader`, and `loader-m32`. The ARM64 executable is dynamically linked against Android's `/system/bin/linker64`; the loader is statically linked. It still requires real-device validation.
- **Debian rootfs archive:** https://images.linuxcontainers.org/images/debian/bookworm/arm64/default/20261117_05:24/rootfs.tar.xz
  SHA-256 from the published `SHA256SUMS`: `125416b51b1272db7abd932bc7cc5f286fd29a190cb5a1b18460500f5448d5fc`.
- **OpenCode V2.3.2 ARM64 archive:** https://github.com/anomalyco/opencode/releases/download/v2.3.2/opencode-linux-arm64.tar.gz
  SHA-256 from the official GitHub release asset metadata: `9d10185acdb72336e086e699ca363a6065f410fd3c1caed0dc8046e95ac06ccb`. The archive contains a root-level `opencode` executable. The digest was independently verified in the sandbox; the native ARM64 Linux binary reported version `2.3.2`.

A native ARM64 Linux smoke test confirmed authenticated `GET /api/info` reported V2.3.2, `/` returned HTML, and an unauthenticated API request returned HTTP 401. The binary and web server have not been run inside Android or PRoot. The OpenCode package declares MIT licensing; its license text is in `OPENCODE-LICENSE`.
