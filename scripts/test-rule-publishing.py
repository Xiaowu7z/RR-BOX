#!/usr/bin/env python3
"""Exercise publishing failure boundaries without credentials or network mutations."""
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("publisher", Path(__file__).with_name("publish-rule-bundle.py"))
PUBLISHER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PUBLISHER)


class PublishRulesTest(unittest.TestCase):
    def test_policy_version_cannot_change_meaning_or_decrease(self):
        old = {"policyVersion": 3, "assets": [{"fileName": "rrbox-policy.json", "sha256": "a"}]}
        PUBLISHER.require_policy_progress(old, old)
        for version, digest in ((2, "a"), (3, "b")):
            with self.assertRaises(ValueError):
                PUBLISHER.require_policy_progress(old, {"policyVersion": version, "assets": [{"fileName": "rrbox-policy.json", "sha256": digest}]})
        PUBLISHER.require_policy_progress(old, {"policyVersion": 4, "assets": [{"fileName": "rrbox-policy.json", "sha256": "b"}]})

    def test_json_rejects_duplicate_metadata(self):
        with self.assertRaises(ValueError):
            PUBLISHER.strict_json(b'{"ruleVersion":1,"ruleVersion":2}')

    def run_publish(self, damaged_asset=False, racing_newer=False):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            rules = root / "app/src/main/assets/rules"
            rules.mkdir(parents=True)
            (root / "build-reports").mkdir()
            (rules / "rrbox-policy.json").write_text('{"ruleVersion":3}')
            (rules / "geosite-geolocation-cn.srs").write_bytes(b"tested geosite bytes")
            (rules / "geoip-cn.srs").write_bytes(b"tested geoip bytes")
            calls = []
            manifest = {}

            def command(argv, **_):
                if argv[0] == "java":
                    manifest.update(json.loads(Path(argv[3]).read_bytes()))
                    Path(argv[4]).write_bytes(b"verified signed envelope")
                else:
                    self.assertEqual(argv[:3], ["gh", "release", "upload"])
                    calls.append("upload")

            def api(path, method="GET", body=None, **_):
                calls.append((method, path))
                if path == "releases/tags/rules-v1-10001":
                    return None
                if path == "releases" and method == "POST":
                    self.assertTrue(body["draft"])
                    self.assertTrue(body["prerelease"])
                    self.assertEqual(body["make_latest"], "false")
                    return {"id": 7}
                if path == "releases/7" and method == "GET":
                    assets = [{"name": p.name, "state": "uploaded", "size": p.stat().st_size,
                               "digest": "sha256:" + PUBLISHER.sha256(p.read_bytes())}
                              for p in (root / "dist/rules").iterdir()]
                    if damaged_asset:
                        assets[0]["digest"] = "sha256:wrong"
                    return {"assets": assets}
                if path == "releases/7" and method == "PATCH":
                    self.assertFalse(body["draft"])
                    self.assertEqual(body["make_latest"], "false")
                    return {}
                if path == "git/blobs":
                    self.assertEqual(body["encoding"], "base64")
                    return {"sha": "d" * 40}
                if path == "git/trees":
                    self.assertEqual({entry["path"] for entry in body["tree"]}, {
                        "channel.json", *[f"bundles/10001/{name}" for name in (*PUBLISHER.NAMES, "bundle-manifest.json")],
                    })
                    self.assertEqual(json.loads(body["tree"][0]["content"])["bundleVersion"], 10001)
                    return {"sha": "b" * 40}
                if path == "git/commits":
                    return {"sha": "b" * 40}
                if path == "git/refs":
                    self.assertEqual(body["ref"], "refs/heads/rules-channel")
                    return {}
                self.fail(f"Unexpected API call {method} {path}")

            def channel():
                # A concurrent fully tested release wins while this build uploads.
                if racing_newer and "upload" in calls:
                    return {"sha": "c" * 40}, dict(manifest, bundleVersion=10002)
                return None, None

            with patch.object(PUBLISHER, "ROOT", root), patch.object(PUBLISHER, "gh", api), \
                 patch.object(PUBLISHER, "read_channel", channel), \
                 patch.object(PUBLISHER, "verified_payload", lambda _: manifest), \
                 patch.object(PUBLISHER.subprocess, "run", command), \
                 patch.dict(os.environ, {"GITHUB_REPOSITORY": PUBLISHER.REPOSITORY, "GITHUB_SHA": "a" * 40,
                                         "GITHUB_RUN_ID": "100", "GITHUB_RUN_ATTEMPT": "1"}):
                if damaged_asset:
                    with self.assertRaisesRegex(ValueError, "digest mismatch"):
                        PUBLISHER.publish()
                else:
                    PUBLISHER.publish()
            return calls

    def test_channel_is_last_and_release_is_never_stable(self):
        calls = self.run_publish()
        self.assertLess(calls.index("upload"), calls.index(("PATCH", "releases/7")))
        self.assertLess(calls.index(("PATCH", "releases/7")), calls.index(("POST", "git/refs")))
        self.assertEqual(calls[-1], ("POST", "git/refs"))
        self.assertEqual(calls.count(("POST", "git/blobs")), 4)

    def test_bad_uploaded_asset_never_publishes_release_or_channel(self):
        calls = self.run_publish(damaged_asset=True)
        self.assertNotIn(("PATCH", "releases/7"), calls)
        self.assertFalse(any(isinstance(c, tuple) and c[1].startswith("git/") for c in calls))

    def test_racing_newer_bundle_is_not_rolled_back(self):
        calls = self.run_publish(racing_newer=True)
        self.assertIn(("PATCH", "releases/7"), calls)
        self.assertFalse(any(isinstance(c, tuple) and c[1].startswith("git/") for c in calls))


if __name__ == "__main__":
    unittest.main()
