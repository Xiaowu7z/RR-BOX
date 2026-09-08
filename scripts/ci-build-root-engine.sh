#!/usr/bin/env bash
set -euo pipefail

# Run after ci-build-hev.sh recreates jniLibs. The APK extracts this PIE from a
# lib*.so packaging name into the install-owned executable nativeLibraryDir.
test -n "${ANDROID_NDK_HOME:-}" || { echo "ANDROID_NDK_HOME is required" >&2; exit 1; }
case "$(uname -s)" in
  Linux) host_tag=linux-x86_64 ;;
  Darwin) host_tag=darwin-x86_64 ;;
  *) echo "Unsupported NDK build host" >&2; exit 1 ;;
esac
toolchain="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$host_tag/bin"
compiler="$toolchain/aarch64-linux-android26-clang"
readelf="$toolchain/llvm-readelf"
test -x "$compiler"
test -x "$readelf"
mkdir -p app/src/main/jniLibs/arm64-v8a build-reports
output=app/src/main/jniLibs/arm64-v8a/librrbox-root-engine.so
"$compiler" -std=c11 -O2 -Wall -Wextra -Werror -fPIE -pie \
  -fstack-protector-strong -D_FORTIFY_SOURCE=2 \
  -Wl,-z,relro,-z,now,-z,max-page-size=16384,--build-id=none \
  native/root_engine.c -o "$output"
test -s "$output"
"$readelf" -h -lW "$output" > build-reports/ROOT-ENGINE-ELF.txt
grep -q 'Type:.*DYN' build-reports/ROOT-ENGINE-ELF.txt
grep -q 'Machine:.*AArch64' build-reports/ROOT-ENGINE-ELF.txt
grep -q 'INTERP' build-reports/ROOT-ENGINE-ELF.txt
grep -q '/system/bin/linker64' build-reports/ROOT-ENGINE-ELF.txt
awk '/^[[:space:]]*LOAD[[:space:]]/ { seen = 1; if ($NF != "0x4000") bad = 1 } END { exit (!seen || bad) }' \
  build-reports/ROOT-ENGINE-ELF.txt
sha256sum "$output" | tee build-reports/ROOT-ENGINE-SHA256.txt
