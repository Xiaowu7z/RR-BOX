#!/usr/bin/env bash
# Narrow source guards complement real unit tests and Lint; they are not runtime tests.
set -euo pipefail
grep -q 'versionCode = 100' app/build.gradle.kts
grep -q 'versionName = "1.0.0"' app/build.gradle.kts
grep -q 'START_NOT_STICKY' app/src/main/java/com/rr/client/vpn/RRVpnService.kt
grep -q 'Ignoring duplicate equivalent VPN start request' app/src/main/java/com/rr/client/vpn/RRVpnService.kt
grep -q 'HevConfigAdapter.adapt' app/src/main/java/com/rr/client/vpn/RRVpnService.kt
grep -q 'MTU = 8500' app/src/main/java/com/rr/client/vpn/HevTunnelConfig.kt
grep -q 'pipeline: true' app/src/main/java/com/rr/client/vpn/HevTunnelConfig.kt
grep -q 'tcp-fastopen: true' app/src/main/java/com/rr/client/vpn/HevTunnelConfig.kt
grep -q 'TRANSPORT_WIFI' app/src/main/java/com/rr/client/lab/NetworkDiagnostics.kt
grep -q 'TRANSPORT_CELLULAR' app/src/main/java/com/rr/client/lab/NetworkDiagnostics.kt
grep -q 'class RRQuickTileService' app/src/main/java/com/rr/client/vpn/RRQuickTileService.kt
grep -q 'Quick tile fast path' app/src/main/java/com/rr/client/vpn/RRQuickTileController.kt
grep -q 'android.permission.BIND_QUICK_SETTINGS_TILE' app/src/main/AndroidManifest.xml
grep -q 'LocalNodeDeletionPolicy.canDelete' app/src/main/java/com/rr/client/MainActivity.kt
grep -q 'activeRuntimeNodeId' app/src/main/java/com/rr/client/vpn/RRVpnService.kt
grep -q 'class NetworkContinuityMonitor' app/src/main/java/com/rr/client/vpn/NetworkContinuityMonitor.kt
grep -q 'NetworkContinuityObserver' app/src/main/java/com/rr/client/lab/NetworkContinuityObserver.kt
grep -q 'ACTION_LAB_DROP_DATA_PLANE' app/src/main/java/com/rr/client/vpn/RRVpnService.kt
grep -q 'object ProtocolCapabilityRegistry' app/src/main/java/com/rr/client/subscription/ProtocolCapabilityRegistry.kt
grep -q 'object RawLocalNodeImporter' app/src/main/java/com/rr/client/lab/RawLocalNodeImporter.kt

# Keep the already validated adaptive, monochrome and status-bar icon resources frozen for 1.0.
test "$(grep -c 'android:icon="@mipmap/ic_rrbox_app_097"' app/src/main/AndroidManifest.xml)" -ge 3
grep -q 'android:logo="@mipmap/ic_rrbox_app_097"' app/src/main/AndroidManifest.xml
grep -q 'android:roundIcon="@mipmap/ic_rrbox_app_097"' app/src/main/AndroidManifest.xml
grep -A6 'android:name=".vpn.RRVpnService"' app/src/main/AndroidManifest.xml | grep -q 'android:icon="@mipmap/ic_rrbox_app_097"'
grep -A6 'android:name=".vpn.RRQuickTileService"' app/src/main/AndroidManifest.xml | grep -q 'android:icon="@drawable/ic_rrbox_status_097"'
cmp app/src/main/res/drawable-nodpi/ic_rrbox_launcher.webp app/src/main/res/drawable-nodpi/ic_rrbox_launcher_097.webp
test -s app/src/main/res/mipmap-anydpi-v26/ic_rrbox_app_097.xml
test -s app/src/main/res/mipmap-anydpi-v33/ic_rrbox_app_097.xml
grep -q '<monochrome android:drawable="@drawable/ic_rrbox_monochrome_097"' app/src/main/res/mipmap-anydpi-v33/ic_rrbox_app_097.xml
grep -q '<monochrome android:drawable="@drawable/ic_rrbox_monochrome_097"' app/src/main/res/mipmap-anydpi-v33/ic_launcher.xml
grep -q '@drawable/ic_rrbox_adaptive_foreground_097' app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml
test -s app/src/main/res/drawable/ic_rrbox_monochrome_097.xml
grep -q 'CHANNEL_ID = "rrbox_status_channel_097"' app/src/main/java/com/rr/client/vpn/NotificationManager.kt
grep -q 'NOTIFICATION_ID = 1007' app/src/main/java/com/rr/client/vpn/NotificationManager.kt
grep -q 'R.drawable.ic_rrbox_status_097' app/src/main/java/com/rr/client/vpn/NotificationManager.kt
if grep -q '\.setLargeIcon' app/src/main/java/com/rr/client/vpn/NotificationManager.kt; then exit 1; fi
if grep -q 'BitmapFactory' app/src/main/java/com/rr/client/vpn/NotificationManager.kt; then exit 1; fi

grep -q 'REPOSITORY = "RR-BOX"' app/src/main/java/com/rr/client/update/AppUpdateChecker.kt
grep -q 'releases/latest' app/src/main/java/com/rr/client/update/AppUpdateChecker.kt
grep -q '"url": "https://github.com/Xiaowu7z/RR-BOX"' obtainium.json

grep -q 'NodeOverridePatcher.resolve' app/src/main/java/com/rr/client/MainActivity.kt
grep -q 'expectedConfigJson = configJson' app/src/main/java/com/rr/client/vpn/RRQuickTileController.kt
grep -q 'onImportText = ::importClipboardContent' app/src/main/java/com/rr/client/MainActivity.kt
grep -q 'val generation = ++requestGeneration' app/src/main/java/com/rr/client/vpn/RRVpnService.kt
grep -q 'SafeConstructor' app/src/main/java/com/rr/client/subscription/ClashSubscriptionConverter.kt
grep -q 'SecretRedactor.redact' app/src/main/java/com/rr/client/lab/RRLogStore.kt
