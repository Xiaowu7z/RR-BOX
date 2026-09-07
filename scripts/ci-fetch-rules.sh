#!/usr/bin/env bash
set -euo pipefail
rules_dir="app/src/main/assets/rules"
rm -rf "$rules_dir"
mkdir -p "$rules_dir" build-reports
fetch_rule() {
  local output="$1"; shift
  local tmp="${output}.tmp"
  for url in "$@"; do
    rm -f "$tmp"
    if curl --fail --location --silent --show-error --retry 3 --connect-timeout 15 --max-time 60 "$url" -o "$tmp"; then
      if [ "$(xxd -p -l 3 "$tmp")" = "535253" ] && [ "$(stat -c '%s' "$tmp")" -gt 8 ]; then
        mv "$tmp" "$output"
        return 0
      fi
    fi
  done
  return 1
}
fetch_rule "$rules_dir/geosite-geolocation-cn.srs" \
  "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-geolocation-cn.srs" \
  "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-geolocation-cn.srs"
fetch_rule "$rules_dir/geoip-cn.srs" \
  "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-cn.srs" \
  "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geoip@rule-set/geoip-cn.srs"
sha256sum "$rules_dir"/*.srs | tee build-reports/CHINA-RULES-SHA256.txt
