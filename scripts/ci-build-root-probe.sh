#!/usr/bin/env bash
set -euo pipefail

# Run after ci-build-hev.sh, which recreates the arm64-v8a output directory.
# The PIE executable uses a lib*.so name so Android legacy native packaging
# extracts it into nativeLibraryDir with an executable SELinux-compatible path.
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
output=app/src/main/jniLibs/arm64-v8a/librrbox-root-probe.so
"$compiler" -std=c11 -O2 -Wall -Wextra -Werror -fPIE -pie \
  -fstack-protector-strong -D_FORTIFY_SOURCE=2 \
  -Wl,-z,relro,-z,now,-z,max-page-size=16384,--build-id=none \
  native/root_probe.c -o "$output"
test -s "$output"
"$readelf" -h -lW "$output" > build-reports/ROOT-PROBE-ELF.txt
grep -q 'Type:.*DYN' build-reports/ROOT-PROBE-ELF.txt
grep -q 'Machine:.*AArch64' build-reports/ROOT-PROBE-ELF.txt
grep -q 'INTERP' build-reports/ROOT-PROBE-ELF.txt
grep -q '/system/bin/linker64' build-reports/ROOT-PROBE-ELF.txt
awk '/^[[:space:]]*LOAD[[:space:]]/ { seen = 1; if ($NF != "0x4000") bad = 1 } END { exit (!seen || bad) }' \
  build-reports/ROOT-PROBE-ELF.txt
sha256sum "$output" | tee build-reports/ROOT-PROBE-SHA256.txt
