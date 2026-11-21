# Third-party notices

## PRoot and Android build

The app packages PRoot from [ahmed-alnassif/proot release v26.10.08-a179d3e](https://github.com/ahmed-alnassif/proot/releases/tag/v26.10.08-a179d3e). The executable is built from upstream [termux/proot commit a179d3e8a4e045aaa1fb8cc3284f23509d96d353](https://github.com/termux/proot/commit/a179d3e8a4e045aaa1fb8cc3284f23509d96d353). The PRoot program is GPL-2.0; its license is in `PROOT-LICENSE`, and its source snapshot is `third_party/termux-proot-a179d3e8a4e045aaa1fb8cc3284f23509d96d353.tar.gz`.

The Android build scripts are from the release repository and are licensed under AGPL-3.0. Their license is in `third_party/PROOT-ANDROID-BUILDER-LICENSE`, with source in `third_party/proot-android-builder-source-v26.10.08-a179d3e.tar.gz`. The workflow statically links talloc 2.4.3. Its LGPL-3.0 license is in `third_party/TALLOC-LICENSE`, and source is in `third_party/talloc-2.4.3.tar.gz`. Keep these sources and notices with any distributed binary and review the applicable source-offer requirements before release.

## OpenCode

The installer downloads the unmodified ARM64 CLI from [anomalyco/opencode v2.3.2](https://github.com/anomalyco/opencode/releases/tag/v2.3.2). The OpenCode package declares MIT licensing; its license text is in `OPENCODE-LICENSE`.

The launcher icons are copied from the official production Android assets in [anomalyco/opencode](https://github.com/anomalyco/opencode/tree/main/packages/desktop/icons/prod/android). They are distributed under the upstream MIT license; the applicable license text is in `OPENCODE-LICENSE`.

## Debian

The first-run root filesystem is a Debian Bookworm ARM64 container image from [Linux Containers image server](https://images.linuxcontainers.org/). Debian packages have individual copyright and license notices under `/usr/share/doc/*/copyright` within the installed root filesystem. This prototype does not bundle a consolidated license viewer.

## Android dependencies

The Android code uses AndroidX Activity and Lifecycle libraries, Apache Commons Compress, XZ for Java, and OkHttp. Their license notices must be included in any redistributed release APK. This prototype has not yet generated the final merged notices file.
