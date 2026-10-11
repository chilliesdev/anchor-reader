"""Release regressions use fake GitHub responses; these tests never publish."""

import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("github_release", Path(__file__).parents[1] / "github_release.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)

SHA = "a" * 40


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.env = patch.dict(os.environ, {
            "GITHUB_RUN_NUMBER": "7", "GITHUB_REPOSITORY": "owner/repo",
            "GITHUB_EVENT_NAME": "push", "GITHUB_REF": "refs/heads/main",
            "ANCHOR_SOURCE_SHA": SHA, "ANCHOR_PUSH_BEFORE": "b" * 40,
        })
        self.env.start()
        self.addCleanup(self.env.stop)
        for name in release.asset_names("1.0.7")[:2]:
            (self.directory / name).write_bytes(name.encode())
        (self.directory / "SOURCE.json").write_text(json.dumps({
            "commit": SHA, "versionName": "1.0.7", "versionCode": 8,
        }))
        self.write_checksums()

    def write_checksums(self):
        (self.directory / "SHA256SUMS").write_text("".join(
            f"{release.digest(self.directory / name)}  {name}\n"
            for name in release.asset_names("1.0.7")[:-1]))

    def test_version_is_stable_and_android_safe(self):
        self.assertEqual(release.version("7"), ("1.0.7", 8))
        self.assertEqual(release.version("2099999999"), ("1.0.2099999999", 2100000000))
        for invalid in ("0", "-1", "07", "7\n", "2100000000", "x"):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                release.version(invalid)

    def test_asset_corruption_or_wrong_source_blocks_publication(self):
        release.validate_assets(self.directory, "1.0.7", 8, SHA)
        with self.assertRaisesRegex(ValueError, "manifest"):
            release.validate_assets(self.directory, "1.0.7", 8, "b" * 40)
        (self.directory / "AnchorReader-1.0.7.apk").write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "checksums"):
            release.validate_assets(self.directory, "1.0.7", 8, SHA)

    def test_missing_asset_blocks_release_before_tag_creation(self):
        (self.directory / "AnchorReader-1.0.7.aab").unlink()
        with patch.object(release, "source_sha", return_value=SHA), \
                patch.object(release, "tag_sha", return_value=None), \
                patch.object(release, "gh_api", return_value=None) as api:
            with self.assertRaisesRegex(ValueError, "Missing or empty"):
                release.publish(self.directory)
            self.assertEqual(api.call_count, 1)
            self.assertEqual(api.call_args[0][0], "repos/owner/repo/releases/tags/v1.0.7")

    def test_incorrect_apk_metadata_is_rejected(self):
        badging = self.directory / "badging.txt"
        badging.write_text("package: name='com.anchorreader' versionCode='1' versionName='1.0.0'\nsdkVersion:'26'\n")
        with patch.object(release, "source_sha", return_value=SHA), \
                self.assertRaisesRegex(ValueError, "unexpected package or version"):
            release.prepare_assets(self.directory, badging)

    def test_only_published_workflow_versions_select_latest(self):
        data = [
            {"tag_name": "v1.0.9", "draft": True, "prerelease": False},
            {"tag_name": "v1.0.8", "draft": False, "prerelease": True},
            {"tag_name": "manual", "draft": False, "prerelease": False},
            {"tag_name": "v1.0.5", "draft": False, "prerelease": False},
        ]
        with patch.object(release, "gh_api", return_value=data):
            self.assertEqual(release.published_versions("repos/owner/repo"), [(5, "v1.0.5")])

    def test_pr_cannot_publish(self):
        with patch.dict(os.environ, {"GITHUB_EVENT_NAME": "pull_request"}), patch.object(release, "gh_api") as api:
            with self.assertRaisesRegex(ValueError, "main push"):
                release.publish(self.directory)
            api.assert_not_called()

    def test_conflicting_tag_cannot_be_replaced(self):
        with patch.object(release, "source_sha", return_value=SHA), \
                patch.object(release, "tag_sha", return_value="c" * 40), \
                patch.object(release, "gh_api") as api:
            with self.assertRaisesRegex(ValueError, "different commit"):
                release.publish(self.directory)
            api.assert_not_called()

    def test_published_rerun_leaves_release_and_assets_unchanged(self):
        with patch.object(release, "source_sha", return_value=SHA), \
                patch.object(release, "tag_sha", return_value=SHA), \
                patch.object(release, "gh_api", return_value={"draft": False, "html_url": "release"}) as api, \
                patch.object(release, "command") as command:
            release.publish(self.directory)
            self.assertEqual(api.call_count, 1)
            command.assert_not_called()

    def run_publish(self, existing_draft=False, newer_release=False, missing_asset=False, failed_upload=False,
                    earlier_release=True, missing_tag=False):
        calls = []
        draft = {"id": 42, "draft": True, "html_url": "release"}

        def api(endpoint, method="GET", payload=None, missing=False):
            calls.append((endpoint, method, payload))
            if endpoint.endswith("/releases/tags/v1.0.7"):
                return draft if existing_draft else None
            if endpoint.endswith("/releases/generate-notes"):
                return {"body": "Generated change notes"}
            if method in ("POST", "PATCH"):
                return draft
            assets = [{"name": name, "state": "uploaded", "size": (self.directory / name).stat().st_size,
                       "digest": f"sha256:{release.digest(self.directory / name)}"}
                      for name in release.asset_names("1.0.7")]
            return {**draft, "assets": assets[1:] if missing_asset else assets}

        versions = ([(5, "v1.0.5")] if earlier_release else []) + ([(9, "v1.0.9")] if newer_release else [])
        with patch.object(release, "source_sha", return_value=SHA), \
                patch.object(release, "tag_sha", side_effect=[None, SHA, SHA, SHA] if missing_tag else None,
                             return_value=SHA), \
                patch.object(release, "published_versions", return_value=versions), \
                patch.object(release, "commit_notes", return_value="- Direct push commit") as notes, \
                patch.object(release, "gh_api", side_effect=api), \
                patch.object(release, "command", side_effect=RuntimeError("upload failed") if failed_upload else None):
            if missing_asset or failed_upload:
                with self.assertRaises((ValueError, RuntimeError)):
                    release.publish(self.directory)
            else:
                release.publish(self.directory)
            notes.assert_called_once_with(SHA, SHA if earlier_release else "b" * 40)
        return calls

    def test_new_release_uploads_before_publication_and_sets_latest(self):
        calls = self.run_publish()
        create = next(payload for _, method, payload in calls if method == "POST" and payload.get("draft"))
        self.assertTrue(create["draft"])
        self.assertIn("Direct push commit", create["body"])
        self.assertEqual(calls[-1][2], {"draft": False, "make_latest": "true"})

    def test_new_tag_points_to_exact_tested_commit(self):
        calls = self.run_publish(missing_tag=True)
        tag = next(payload for endpoint, _, payload in calls if endpoint.endswith("/git/refs"))
        self.assertEqual(tag, {"ref": "refs/tags/v1.0.7", "sha": SHA})

    def test_older_run_recovers_draft_without_replacing_newer_latest(self):
        calls = self.run_publish(existing_draft=True, newer_release=True)
        generated = next(payload for endpoint, _, payload in calls if endpoint.endswith("generate-notes"))
        self.assertEqual(generated["previous_tag_name"], "v1.0.5")
        self.assertEqual(calls[-1][2], {"draft": False, "make_latest": "false"})

    def test_older_first_release_does_not_generate_comparison_against_newer_source(self):
        calls = self.run_publish(newer_release=True, earlier_release=False)
        self.assertFalse(any(endpoint.endswith("generate-notes") for endpoint, _, _ in calls))
        self.assertEqual(calls[-1][2], {"draft": False, "make_latest": "false"})

    def test_upload_failure_keeps_draft_unpublished(self):
        for failure in ({"missing_asset": True}, {"failed_upload": True}):
            with self.subTest(failure=failure):
                calls = self.run_publish(existing_draft=True, **failure)
                self.assertFalse(any(payload and payload.get("draft") is False for _, _, payload in calls))

    def test_incorrect_uploaded_digest_cannot_publish(self):
        names = release.asset_names("1.0.7")
        assets = [{"name": name, "state": "uploaded", "size": (self.directory / name).stat().st_size,
                   "digest": "sha256:incorrect"} for name in names]
        with self.assertRaisesRegex(ValueError, "checksum differs"):
            release.verify_uploaded({"assets": assets}, self.directory, names)


if __name__ == "__main__":
    unittest.main()
