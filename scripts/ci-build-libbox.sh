#!/usr/bin/env bash
set -euo pipefail
export PATH="$PATH:$(go env GOPATH)/bin"
rm -rf sing-box-src
rm -f app/libs/libbox.aar
mkdir -p app/libs build-reports
git clone --branch "$SING_BOX_TAG" --depth 1 https://github.com/SagerNet/sing-box.git sing-box-src
cd sing-box-src
test "$(git rev-parse HEAD)" = "$SING_BOX_COMMIT"
make lib_install
grep -q 'with_gvisor' cmd/internal/build_libbox/main.go
grep -q 'with_tailscale' cmd/internal/build_libbox/main.go
grep -q 'with_naive_outbound' cmd/internal/build_libbox/main.go
go run ./cmd/internal/build_libbox -target android -platform android/arm64
test -s libbox.aar
cp libbox.aar ../app/libs/libbox.aar
cd ..
unzip -Z1 app/libs/libbox.aar | grep -q '^jni/arm64-v8a/libbox\.so$'
sha256sum app/libs/libbox.aar | tee build-reports/LIBBOX-SHA256.txt

cp sing-box-src/LICENSE app/src/main/assets/licenses/sing-box-LICENSE.txt
