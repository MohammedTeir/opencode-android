#!/usr/bin/env bash
set -euo pipefail

out_dir=${1:?Usage: fetch-bundled-runtime.sh <output-assets-directory>}
mkdir -p "$out_dir"

fetch_verified() {
  local url=$1 name=$2 expected=$3 destination="$out_dir/$2"
  if [[ -f "$destination" ]] && echo "$expected  $destination" | sha256sum --check --status; then
    echo "Using verified bundled runtime asset: $name"
    return
  fi

  local temporary="$destination.part"
  rm -f "$temporary"
  echo "Fetching $name for APK packaging..."
  curl --fail --location --retry 3 --retry-delay 2 --connect-timeout 30 "$url" --output "$temporary"
  echo "$expected  $temporary" | sha256sum --check --status || {
    rm -f "$temporary"
    echo "SHA-256 verification failed for $name" >&2
    return 1
  }
  mv "$temporary" "$destination"
}

fetch_verified \
  'https://images.linuxcontainers.org/images/debian/bookworm/arm64/default/20261117_05:24/rootfs.tar.xz' \
  'debian-bookworm-arm64-rootfs.tar.xz' \
  '125416b51b1272db7abd932bc7cc5f286fd29a190cb5a1b18460500f5448d5fc'

fetch_verified \
  'https://github.com/anomalyco/opencode/releases/download/v2.3.2/opencode-linux-arm64.tar.gz' \
  'opencode-v2.3.2-linux-arm64.tar.gz' \
  '9d10185acdb72336e086e699ca363a6065f410fd3c1caed0dc8046e95ac06ccb'
