#!/usr/bin/env bash
set -euo pipefail
rm -rf hev-socks5-tunnel "$RUNNER_TEMP/rr-hev" app/src/main/jniLibs/arm64-v8a
mkdir -p "$RUNNER_TEMP/rr-hev/libs" "$RUNNER_TEMP/rr-hev/obj" app/src/main/jniLibs/arm64-v8a build-reports
git clone https://github.com/heiher/hev-socks5-tunnel.git hev-socks5-tunnel
git -C hev-socks5-tunnel checkout --detach "$HEV_COMMIT"
git -C hev-socks5-tunnel submodule update --init --recursive
test "$(git -C hev-socks5-tunnel rev-parse HEAD)" = "$HEV_COMMIT"
python3 scripts/apply-hev-app-routing.py hev-socks5-tunnel
pushd hev-socks5-tunnel
"$ANDROID_NDK_HOME/ndk-build" \
  NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=Android.mk APP_ABI=arm64-v8a APP_PLATFORM=android-26 \
  NDK_LIBS_OUT="$RUNNER_TEMP/rr-hev/libs" NDK_OUT="$RUNNER_TEMP/rr-hev/obj" \
  "APP_CFLAGS=-O3 -DNDEBUG -DPKGNAME=com/rr/client/vpn -DCLSNAME=HevTunnelNative" \
  "APP_LDFLAGS=-Wl,--build-id=none -Wl,--hash-style=gnu"
popd
cp "$RUNNER_TEMP/rr-hev/libs/arm64-v8a/libhev-socks5-tunnel.so" app/src/main/jniLibs/arm64-v8a/
test -s app/src/main/jniLibs/arm64-v8a/libhev-socks5-tunnel.so
sha256sum app/src/main/jniLibs/arm64-v8a/libhev-socks5-tunnel.so | tee build-reports/HEV-SHA256.txt
