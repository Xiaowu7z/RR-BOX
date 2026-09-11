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
grep -q "versionCode='108'" dist/PACKAGE-REPORT.txt
grep -q "versionName='1.0.8'" dist/PACKAGE-REPORT.txt
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
cp build-reports/APP-NODE-ROUTING-REPORT.json dist/APP-NODE-ROUTING-REPORT.json
cp build-reports/HEV-APP-ROUTING-REPORT.json dist/HEV-APP-ROUTING-REPORT.json
cp build-reports/HEV-JNI-OWNER-REPORT.json dist/HEV-JNI-OWNER-REPORT.json
cp build-reports/DESTINATION-RECOVERY-REPORT.json dist/DESTINATION-RECOVERY-REPORT.json
cp build-reports/WECHAT-IPV6-REPORT.json dist/WECHAT-IPV6-REPORT.json
cp build-reports/HEV-DNS-REPORT.json dist/HEV-DNS-REPORT.json
cp build-reports/HEV-NATIVE-DNS-REPORT.json dist/HEV-NATIVE-DNS-REPORT.json
cp build-reports/ROOT-ENGINE-REPORT.json dist/ROOT-ENGINE-REPORT.json
cp build-reports/ROOT-TCP-REPORT.json dist/ROOT-TCP-REPORT.json
cat > dist/BUILD-REPORT.md <<EOF
# RRBOX 1.0.8 Build Report

- Source commit: $(git rev-parse HEAD)
- Package: com.rr.client
- Version code: 108
- Version name: 1.0.8
- ABI: arm64-v8a
- APK: ${OUTPUT_APK_NAME}
- APK size: ${apk_size} bytes
- APK SHA-256: ${apk_sha256}
- Signing certificate SHA-256: ${certificate_sha256}
- System engine: sing-box system TUN stable baseline
- HEV engine: native/lwIP + unified real DNS + SOCKS5 pipeline + best-effort client TFO
- HEV owner lookup: dedicated JNI pthread; real JVM with HEV coroutine owner, timeout and restart verification
- Root engine: native nonpersistent TUN handed to System stack, UID policy routing, exact TCP peer return routes, explicit DNS and dual-stack routes, supervised rollback
- Root DNS rules: exact netlink attributes and readback; Android legacy ip parser compatibility
- App binding edits: complete shared-UID group, atomic current-scope commit, independent inactive scope and main node
- Diagnostics: POLICY edit and engine events; actual configuration snapshot and HEV entrance candidates; lightweight policy retention
- Root verification: isolated Linux TUN/FD/routing/cleanup plus real System stack TCP/UDP with Android-style policy routing; Android real-device acceptance still required
- Root lab: optional isolated capability probe and export
- Network continuity: event-driven physical path tracking + validated runtime recovery
- Quick Settings: current persisted config checked before cache reuse
- Routing: shared domestic DNS/route policy, WeChat / Douyin / TikTok separation
- App-specific nodes: main outlet retained, explicit app bindings independent of smart routing, failed auxiliary nodes reject their bound apps
- App outlet verification: pinned core rule metadata plus isolated TCP/UDP/DNS exit observers; HEV native original-tuple sessions; Android owner lookup and device performance still require acceptance
- Routing verification: pinned host core TCP/UDP/DNS fixtures including BIGO login domain precedence and exact-domain boundaries (device acceptance still required)
- Destination recovery verification: System/HEV/Root production rules, actual observed destination, trusted DNS cache and QUIC original reply addresses; all fixture traffic terminates on loopback
- WeChat IPv6 verification: enabled candidate rules with the real direct outbound, IPv4 TCP recovery, AAAA-only answers and dual-stack TCP fallback, unchanged UDP; Root physical-family gating is separate and Android/WeChat business acceptance remains required
- App/notification icon resources: frozen validated production resources
- Update channels: GitHub releases/latest + Obtainium
EOF

cat dist/TEST-REPORT.json >> dist/BUILD-REPORT.md
cp CHANGELOG.md dist/RELEASE-NOTES.md
cat dist/BUILD-REPORT.md >> dist/RELEASE-NOTES.md
