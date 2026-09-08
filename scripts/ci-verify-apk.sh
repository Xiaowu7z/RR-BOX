#!/usr/bin/env bash
set -euo pipefail
source_apk="app/build/outputs/apk/release/app-release.apk"
test -s "$source_apk"
mkdir -p dist
output_apk="dist/${OUTPUT_APK_NAME}"
cp "$source_apk" "$output_apk"
cp obtainium.json dist/obtainium.json
apksigner="$ANDROID_HOME/build-tools/$ANDROID_BUILD_TOOLS/apksigner"
aapt2="$ANDROID_HOME/build-tools/$ANDROID_BUILD_TOOLS/aapt2"
"$apksigner" verify --verbose --print-certs "$output_apk" | tee dist/SIGNATURE-REPORT.txt
"$aapt2" dump badging "$output_apk" | tee dist/PACKAGE-REPORT.txt
grep -q "package: name='com.rr.client'" dist/PACKAGE-REPORT.txt
grep -q "versionCode='100'" dist/PACKAGE-REPORT.txt
grep -q "versionName='1.0.0'" dist/PACKAGE-REPORT.txt
grep -q "application-label:'RRBOX'" dist/PACKAGE-REPORT.txt
unzip -Z1 "$output_apk" | tee dist/APK-FILE-LIST.txt
grep -q '^lib/arm64-v8a/libbox\.so$' dist/APK-FILE-LIST.txt
grep -q '^lib/arm64-v8a/libhev-socks5-tunnel\.so$' dist/APK-FILE-LIST.txt
grep -q '^lib/arm64-v8a/librrbox-root-probe\.so$' dist/APK-FILE-LIST.txt
grep -q '^lib/arm64-v8a/librrbox-root-engine\.so$' dist/APK-FILE-LIST.txt
grep -q '^assets/rules/geosite-geolocation-cn\.srs$' dist/APK-FILE-LIST.txt
grep -q '^assets/rules/geoip-cn\.srs$' dist/APK-FILE-LIST.txt
if grep -Eq '^lib/(armeabi-v7a|x86|x86_64)/' dist/APK-FILE-LIST.txt; then exit 1; fi
(cd dist && sha256sum "${OUTPUT_APK_NAME}") | tee dist/SHA256SUMS.txt
apk_sha256="$(awk '{print $1}' dist/SHA256SUMS.txt)"
apk_size="$(stat -c '%s' "$output_apk")"
certificate_sha256="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' dist/SIGNATURE-REPORT.txt | head -n 1)"
test "$certificate_sha256" = "fe1368cf16ee9e8b56199655d0b1e2606a6ec9b8f3d4ac5e16e8cf66e180d816"
"$ANDROID_HOME/build-tools/$ANDROID_BUILD_TOOLS/zipalign" -c 4 "$output_apk"
python3 scripts/collect_reports.py
cp build-reports/SOURCE-AUDIT.json dist/SOURCE-AUDIT.json
cp build-reports/ROUTING-CORE-REPORT.json dist/ROUTING-CORE-REPORT.json
cp build-reports/HEV-DNS-REPORT.json dist/HEV-DNS-REPORT.json
cp build-reports/HEV-NATIVE-DNS-REPORT.json dist/HEV-NATIVE-DNS-REPORT.json
cp build-reports/ROOT-ENGINE-REPORT.json dist/ROOT-ENGINE-REPORT.json
cp build-reports/ROOT-TCP-REPORT.json dist/ROOT-TCP-REPORT.json
cat > dist/BUILD-REPORT.md <<EOF
# RRBOX 1.0.0 Build Report

- Source commit: $(git rev-parse HEAD)
- Package: com.rr.client
- Version code: 100
- Version name: 1.0.0
- ABI: arm64-v8a
- APK: ${OUTPUT_APK_NAME}
- APK size: ${apk_size} bytes
- APK SHA-256: ${apk_sha256}
- Signing certificate SHA-256: ${certificate_sha256}
- System engine: sing-box system TUN stable baseline
- HEV engine: native/lwIP + unified real DNS + SOCKS5 pipeline + best-effort client TFO
- Root engine: native nonpersistent TUN handed to System stack, UID policy routing, exact TCP peer return routes, explicit DNS and dual-stack routes, supervised rollback
- Root verification: isolated Linux TUN/FD/routing/cleanup plus real System stack TCP/UDP with Android-style policy routing; Android real-device acceptance still required
- Root lab: optional isolated capability probe and export
- Network continuity: event-driven physical path tracking + validated runtime recovery
- Quick Settings: current persisted config checked before cache reuse
- Routing: shared domestic DNS/route policy, WeChat / Douyin / TikTok separation
- Routing verification: pinned host core TCP/UDP/DNS fixtures including BIGO login domain precedence and exact-domain boundaries (device acceptance still required)
- App/notification icon resources: frozen validated production resources
- Update channels: GitHub releases/latest + Obtainium
EOF

cat dist/TEST-REPORT.json >> dist/BUILD-REPORT.md
cp CHANGELOG.md dist/RELEASE-NOTES.md
cat dist/BUILD-REPORT.md >> dist/RELEASE-NOTES.md
