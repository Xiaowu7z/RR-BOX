#!/usr/bin/env bash
set -euo pipefail
: "${RR_KEYSTORE_BASE64:?Missing RR_KEYSTORE_BASE64}"
: "${RR_KEYSTORE_PASSWORD:?Missing RR_KEYSTORE_PASSWORD}"
: "${RR_KEY_ALIAS_VALUE:?Missing RR_KEY_ALIAS}"
: "${RR_KEY_PASSWORD_VALUE:?Missing RR_KEY_PASSWORD_VALUE}"
keystore_path="$RUNNER_TEMP/rr-client-release.jks"
printf '%s' "$RR_KEYSTORE_BASE64" | base64 --decode > "$keystore_path"
keytool -list -keystore "$keystore_path" -storepass "$RR_KEYSTORE_PASSWORD" -alias "$RR_KEY_ALIAS_VALUE" >/dev/null
echo "RR_KEYSTORE_PATH=$keystore_path" >> "$GITHUB_ENV"
