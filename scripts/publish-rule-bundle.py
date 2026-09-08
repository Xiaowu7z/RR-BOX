#!/usr/bin/env python3
"""Publish exactly the three tested rule assets; move the manual-update channel last.

This runs only after the existing full APK/native-routing verification gates. A
rule release is separate from the stable APK channel and never becomes latest.
"""
from __future__ import annotations

import base64
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

REPOSITORY = "Xiaowu7z/RR-BOX"
CHANNEL_BRANCH = "rules-channel"
NAMES = ("rrbox-policy.json", "geosite-geolocation-cn.srs", "geoip-cn.srs")
ROOT = Path(__file__).resolve().parent.parent
SIGNER = ROOT / "scripts/SignRuleManifest.java"


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def json_bytes(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode()


def gh(path: str, method: str = "GET", body: object = None, missing_ok: bool = False) -> object:
    command = ["gh", "api", f"repos/{REPOSITORY}/{path}", "--method", method]
    if body is not None:
        command += ["--input", "-"]
    result = subprocess.run(command, input=json_bytes(body) if body is not None else None, capture_output=True)
    if result.returncode:
        if missing_ok and b"(HTTP 404)" in result.stderr:
            return None
        raise RuntimeError(f"GitHub {method} {path} failed: {result.stderr.decode(errors='replace')[:1000]}")
    return json.loads(result.stdout) if result.stdout else None


def strict_json(data: bytes) -> object:
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError(f"Duplicate JSON key: {key}")
            result[key] = value
        return result
    return json.loads(data.decode("utf-8"), object_pairs_hook=pairs)


def verified_payload(envelope: bytes) -> dict:
    if len(envelope) > 128 * 1024:
        raise ValueError("Previous signed manifest is oversized")
    document = strict_json(envelope)
    if set(document) != {"schemaVersion", "payload", "signature", "certificate"} or document["schemaVersion"] != 1:
        raise ValueError("Invalid signed manifest envelope")
    with tempfile.TemporaryDirectory(prefix="rrbox-rule-verify-") as temporary:
        paths = []
        for field, limit in (("payload", 65536), ("certificate", 8192), ("signature", 1024)):
            content = base64.b64decode(document[field], validate=True)
            if not content or len(content) > limit:
                raise ValueError("Invalid signed manifest field size")
            path = Path(temporary, field)
            path.write_bytes(content)
            paths.append(path)
        subprocess.run(["java", str(SIGNER), "verify", *map(str, paths)], check=True)
        return strict_json(paths[0].read_bytes())


def read_channel() -> tuple[dict | None, dict | None]:
    reference = gh(f"git/ref/heads/{CHANNEL_BRANCH}", missing_ok=True)
    if reference is None:
        return None, None
    commit = gh(f"git/commits/{reference['object']['sha']}")
    contents = gh(f"contents/channel.json?ref={reference['object']['sha']}")
    channel = strict_json(base64.b64decode(contents["content"]))
    if set(channel) != {"schemaVersion", "bundleVersion"} or channel["schemaVersion"] != 1:
        raise ValueError("Unsupported existing channel")
    version = channel["bundleVersion"]
    if type(version) is not int or not re.fullmatch(r"[1-9][0-9]{0,15}", str(version)):
        raise ValueError("Invalid channel version")
    release = gh(f"releases/tags/rules-v1-{version}")
    matches = [a for a in release["assets"] if a["name"] == "bundle-manifest.json"]
    if release["draft"] or len(matches) != 1:
        raise ValueError("Published channel does not have exactly one manifest")
    result = subprocess.run(["gh", "api", f"repos/{REPOSITORY}/releases/assets/{matches[0]['id']}",
                             "-H", "Accept: application/octet-stream"], capture_output=True, check=True)
    manifest = verified_payload(result.stdout)
    if manifest["bundleVersion"] != version:
        raise ValueError("Existing channel manifest mismatch")
    return commit, manifest


def require_policy_progress(previous: dict | None, manifest: dict) -> None:
    if previous is None:
        return
    before = previous["policyVersion"]
    after = manifest["policyVersion"]
    if after < before:
        raise ValueError("Policy version may not decrease; publish a reviewed rollback with a new policy version")
    old_digest = next(asset["sha256"] for asset in previous["assets"] if asset["fileName"] == "rrbox-policy.json")
    new_digest = next(asset["sha256"] for asset in manifest["assets"] if asset["fileName"] == "rrbox-policy.json")
    if after == before and old_digest != new_digest:
        raise ValueError("Changed policy bytes require a higher ruleVersion")


def publish() -> None:
    if os.environ.get("GITHUB_REPOSITORY") != REPOSITORY:
        raise ValueError("Only the RRBOX repository may publish the trusted channel")
    source = os.environ["GITHUB_SHA"]
    if not re.fullmatch(r"[0-9a-f]{40}", source):
        raise ValueError("Expected immutable source commit")
    attempt = int(os.environ["GITHUB_RUN_ATTEMPT"])
    if not 1 <= attempt < 100:
        raise ValueError("Run attempt is outside the allocated version range")
    version = int(os.environ["GITHUB_RUN_ID"]) * 100 + attempt
    if not re.fullmatch(r"[1-9][0-9]{0,15}", str(version)):
        raise ValueError("Bundle version out of range")
    output = ROOT / "dist/rules"
    output.mkdir(parents=True, exist_ok=False)
    assets = []
    for name in NAMES:
        original = ROOT / "app/src/main/assets/rules" / name
        content = original.read_bytes()
        limit = 1024 * 1024 if name.endswith(".json") else 8 * 1024 * 1024
        if not content or len(content) > limit:
            raise ValueError(f"Asset size outside allowed range: {name}")
        shutil.copyfile(original, output / name)
        assets.append({"fileName": name, "size": len(content), "sha256": sha256(content)})
    policy = strict_json((output / "rrbox-policy.json").read_bytes())
    policy_version = policy["ruleVersion"]
    if type(policy_version) is not int or not re.fullmatch(r"[1-9][0-9]{0,15}", str(policy_version)):
        raise ValueError("Invalid ruleVersion")
    manifest = {
        "schemaVersion": 1, "bundleVersion": version, "policyVersion": policy_version,
        "policySchemaVersion": 1, "minAppVersionCode": 100, "coreVersion": "1.14.0",
        "publishedAt": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
        "sourceCommit": source, "assets": assets,
    }
    payload_path = ROOT / "build-reports/rule-manifest-payload.json"
    payload_path.write_bytes(json_bytes(manifest))
    envelope_path = output / "bundle-manifest.json"
    subprocess.run(["java", str(SIGNER), "sign", str(payload_path), str(envelope_path)], check=True)
    if verified_payload(envelope_path.read_bytes()) != manifest:
        raise ValueError("Signed manifest did not round trip")
    _, previous = read_channel()
    require_policy_progress(previous, manifest)
    if previous and previous["bundleVersion"] >= version:
        raise ValueError("A newer tested bundle already owns the channel; refusing a stale release")

    tag = f"rules-v1-{version}"
    if gh(f"releases/tags/{tag}", missing_ok=True) is not None:
        raise ValueError("Immutable rule release already exists; rerun to allocate a new bundle version")
    release = gh("releases", "POST", {
        "tag_name": tag, "target_commitish": source, "name": f"RRBOX rules {version}",
        "draft": True, "prerelease": True, "make_latest": "false",
        "body": f"Signed rules only. Policy {policy_version}; tested source `{source}`.\n\n"
                "This release does not publish or replace the stable APK. Update manually in RRBOX settings.",
    })
    files = [output / name for name in (*NAMES, "bundle-manifest.json")]
    subprocess.run(["gh", "release", "upload", tag, *map(str, files), "--repo", REPOSITORY], check=True)
    uploaded = gh(f"releases/{release['id']}")
    for file in files:
        matching = [asset for asset in uploaded["assets"] if asset["name"] == file.name]
        if len(matching) != 1 or matching[0]["state"] != "uploaded" or matching[0]["size"] != file.stat().st_size:
            raise ValueError(f"Incomplete uploaded asset: {file.name}")
        if matching[0].get("digest") != "sha256:" + sha256(file.read_bytes()):
            raise ValueError(f"Uploaded asset digest mismatch: {file.name}")
    gh(f"releases/{release['id']}", "PATCH", {"draft": False, "prerelease": True, "make_latest": "false"})

    # Assets and signature are public and verified before the channel pointer can become visible.
    commit, previous = read_channel()
    require_policy_progress(previous, manifest)
    if previous and previous["bundleVersion"] >= version:
        print(f"Bundle {version} published; newer channel kept unchanged")
        return
    channel_content = json_bytes({"schemaVersion": 1, "bundleVersion": version}).decode()
    # Identical signed assets on the data branch let the HTTPS raw/CDN mirrors
    # serve devices whose RRBOX UID bypasses its own Root or VPN tunnel.
    # All four paths and the pointer become visible in one commit; no partial
    # bundle can be selected by a newly fetched channel document.
    entries = [{"path": "channel.json", "mode": "100644", "type": "blob", "content": channel_content}]
    for file in files:
        blob = gh("git/blobs", "POST", {"encoding": "base64", "content": base64.b64encode(file.read_bytes()).decode("ascii")})
        entries.append({"path": f"bundles/{version}/{file.name}", "mode": "100644", "type": "blob", "sha": blob["sha"]})
    tree_body = {"tree": entries}
    if commit:
        tree_body["base_tree"] = commit["tree"]["sha"]
    tree = gh("git/trees", "POST", tree_body)
    new_commit = gh("git/commits", "POST", {
        "message": f"rules: activate tested bundle {version}", "tree": tree["sha"],
        "parents": [commit["sha"]] if commit else [],
    })
    if commit:
        gh(f"git/refs/heads/{CHANNEL_BRANCH}", "PATCH", {"sha": new_commit["sha"], "force": False})
    else:
        gh("git/refs", "POST", {"ref": f"refs/heads/{CHANNEL_BRANCH}", "sha": new_commit["sha"]})
    (ROOT / "build-reports/RULE-BUNDLE-REPORT.json").write_bytes(json_bytes({
        "status": "published", "bundleVersion": version, "policyVersion": policy_version,
        "sourceCommit": source, "tag": tag, "channelCommit": new_commit["sha"], "assets": assets,
    }))
    print(f"Published signed bundle {version}, policy {policy_version}; stable APK release unchanged")


if __name__ == "__main__":
    publish()
