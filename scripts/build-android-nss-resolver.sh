#!/usr/bin/env bash
set -euo pipefail

out_dir=${1:?Usage: build-android-nss-resolver.sh <output-assets-directory>}
compiler=${AARCH64_GLIBC_CC:-aarch64-linux-gnu-gcc}
command -v "$compiler" >/dev/null 2>&1 || {
  echo "Missing $compiler. Install the AArch64 GNU/Linux cross-compiler (gcc-aarch64-linux-gnu)." >&2
  exit 1
}
source_file="$(cd "$(dirname "$0")/.." && pwd)/third_party/android-nss-resolver/nss_android.c"
mkdir -p "$out_dir"
"$compiler" \
  -std=gnu11 -O2 -fPIC -fvisibility=hidden -Wall -Wextra -Werror \
  -shared -Wl,-soname,libnss_android.so.2 -Wl,-z,max-page-size=16384 \
  -o "$out_dir/libnss_android.so.2" "$source_file"
file "$out_dir/libnss_android.so.2"
