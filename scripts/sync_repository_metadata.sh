#!/usr/bin/env bash
# Use an already-authorized gh session; never echo or save the token.
set -euo pipefail
cd "$(dirname "$0")/.."
command -v gh >/dev/null || { echo "GitHub CLI (gh) is required." >&2; exit 1; }
repo="Xiaowu7z/RR-BOX"
payload="$(python3 - <<'PYMETA'
import json
p=json.load(open('.github/repository-metadata.json'))
print(json.dumps({k:p[k] for k in ('description','homepage')}))
PYMETA
)"
printf '%s' "$payload" | gh api --method PATCH "repos/$repo" --input - --jq '{description,homepage}'
python3 - <<'PYMETA' | gh api --method PUT "repos/$repo/topics" --input - --jq '.names'
import json
print(json.dumps({'names':json.load(open('.github/repository-metadata.json'))['topics']}))
PYMETA
gh api "repos/$repo" --jq '{description,homepage,topics}'
