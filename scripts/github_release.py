#!/usr/bin/env python3
"""Prepare and publish one signed Android release. GitHub access uses gh."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys


def version(run_number):
    if not re.fullmatch(r"[1-9][0-9]*", run_number):
        raise ValueError("GITHUB_RUN_NUMBER must be a positive integer.")
    number = int(run_number)
    if number >= 2_100_000_000:
        raise ValueError("Workflow run number exceeds the Android versionCode limit.")
    return f"1.0.{number}", number + 1


def command(args, input_text=None):
    return subprocess.run(args, input=input_text, text=True, capture_output=True, check=True).stdout


def gh_api(endpoint, method="GET", payload=None, missing=False):
    args = ["gh", "api", endpoint, "--method", method, "-H", "X-GitHub-Api-Version: 2022-11-28"]
    if payload is not None:
        args += ["--input", "-"]
    result = subprocess.run(args, input=json.dumps(payload) if payload is not None else None,
                            text=True, capture_output=True)
    if result.returncode:
        if missing and "(HTTP 404)" in result.stderr:
            return None
        raise RuntimeError(result.stderr.strip() or f"GitHub request failed: {method} {endpoint}")
    return json.loads(result.stdout) if result.stdout.strip() else None


def source_sha():
    sha = os.environ["ANCHOR_SOURCE_SHA"]
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ValueError("ANCHOR_SOURCE_SHA must be a full commit SHA.")
    if command(["git", "rev-parse", "HEAD"]).strip() != sha:
        raise ValueError("Checkout does not match the release source SHA.")
    return sha


def asset_names(name):
    return [f"AnchorReader-{name}.apk", f"AnchorReader-{name}.aab", "SOURCE.json", "SHA256SUMS"]


def digest(path):
    with path.open("rb") as stream:
        checksum = hashlib.sha256()
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            checksum.update(chunk)
        return checksum.hexdigest()


def prepare_assets(directory, badging):
    name, code = version(os.environ["GITHUB_RUN_NUMBER"])
    sha = source_sha()
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'",
                        badging.read_text(), re.MULTILINE)
    if package is None or package.groups() != ("com.anchorreader", str(code), name):
        raise ValueError("Signed APK has an unexpected package or version.")
    if not re.search(r"^sdkVersion:'26'$", badging.read_text(), re.MULTILINE):
        raise ValueError("Signed APK has an unexpected minimum Android SDK.")
    directory.mkdir(parents=True, exist_ok=True)
    apk, bundle, source, sums = asset_names(name)
    shutil.copyfile("app/build/outputs/apk/release/app-release.apk", directory / apk)
    shutil.copyfile("app/build/outputs/bundle/release/app-release.aab", directory / bundle)
    (directory / source).write_text(json.dumps({
        "commit": sha, "versionName": name, "versionCode": code,
        "run": os.environ["GITHUB_RUN_ID"],
        "workflow": f"https://github.com/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{os.environ['GITHUB_RUN_ID']}",
    }, indent=2) + "\n")
    (directory / sums).write_text("".join(f"{digest(directory / file)}  {file}\n"
                                          for file in (apk, bundle, source)))


def validate_assets(directory, name, code, sha):
    names = asset_names(name)
    for file in names:
        if not (directory / file).is_file() or (directory / file).stat().st_size == 0:
            raise ValueError(f"Missing or empty release asset: {file}")
    source = json.loads((directory / "SOURCE.json").read_text())
    if (source.get("commit"), source.get("versionName"), source.get("versionCode")) != (sha, name, code):
        raise ValueError("Release source manifest does not match this run.")
    expected = "".join(f"{digest(directory / file)}  {file}\n" for file in names[:-1])
    if (directory / "SHA256SUMS").read_text() != expected:
        raise ValueError("Release checksums do not match the assets.")
    return names


def tag_sha(api, tag):
    ref = gh_api(f"{api}/git/ref/tags/{tag}", missing=True)
    if ref is None:
        return None
    target = ref["object"]
    for _ in range(5):
        if target["type"] == "commit":
            return target["sha"]
        if target["type"] != "tag":
            break
        target = gh_api(f"{api}/git/tags/{target['sha']}")["object"]
    raise ValueError("Release tag does not resolve to a commit.")


def published_versions(api):
    releases = []
    page = 1
    while True:
        batch = gh_api(f"{api}/releases?per_page=100&page={page}")
        for release in batch:
            match = re.fullmatch(r"v1\.0\.([1-9][0-9]*)", release["tag_name"])
            if match and not release["draft"] and not release["prerelease"]:
                releases.append((int(match[1]), release["tag_name"]))
        if len(batch) < 100:
            return sorted(releases)
        page += 1


def commit_notes(sha, base):
    # Full history is available in the release job. A force-push may remove the prior base.
    valid_base = base and base != "0" * 40 and re.fullmatch(r"[0-9a-f]{40}", base)
    if valid_base:
        ancestor = subprocess.run(["git", "merge-base", "--is-ancestor", base, sha],
                                  capture_output=True, check=False)
        valid_base = ancestor.returncode == 0
    revision = f"{base}..{sha}" if valid_base else sha
    lines = command(["git", "log", "--format=%h%x09%s", revision]).splitlines()
    return "\n".join(f"- `{short}` {subject}" for short, subject in
                     (line.split("\t", 1) for line in lines)) or "- No new commits since the prior release."


def verify_uploaded(release, directory, names):
    assets = {asset["name"]: asset for asset in release["assets"]}
    for name in names:
        asset = assets.get(name)
        if asset is None or asset["state"] != "uploaded" or asset["size"] != (directory / name).stat().st_size:
            raise ValueError(f"Release asset upload is incomplete: {name}")
        if asset.get("digest") and asset["digest"] != f"sha256:{digest(directory / name)}":
            raise ValueError(f"Uploaded asset checksum differs: {name}")


def publish(directory):
    if os.environ.get("GITHUB_EVENT_NAME") != "push" or os.environ.get("GITHUB_REF") != "refs/heads/main":
        raise ValueError("Only a main push can publish a release.")
    repo = os.environ["GITHUB_REPOSITORY"]
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
        raise ValueError("Invalid GitHub repository.")
    api = f"repos/{repo}"
    sha = source_sha()
    name, code = version(os.environ["GITHUB_RUN_NUMBER"])
    number = int(os.environ["GITHUB_RUN_NUMBER"])
    tag = f"v{name}"
    resolved = tag_sha(api, tag)
    if resolved is not None and resolved != sha:
        raise ValueError(f"Refusing to replace {tag}: it points to a different commit.")
    release = gh_api(f"{api}/releases/tags/{tag}", missing=True)
    if release is not None and not release["draft"]:
        if resolved != sha:
            raise ValueError("Published release is missing its exact source tag.")
        print(f"Already published; unchanged: {release['html_url']}")
        return
    names = validate_assets(directory, name, code, sha)
    if resolved is None:
        gh_api(f"{api}/git/refs", "POST", {"ref": f"refs/tags/{tag}", "sha": sha})
    if tag_sha(api, tag) != sha:
        raise ValueError("Release tag changed before publication.")
    versions = published_versions(api)
    previous = next((previous_tag for value, previous_tag in reversed(versions) if value < number), None)
    base = tag_sha(api, previous) if previous else os.environ.get("ANCHOR_PUSH_BEFORE")
    notes_input = {"tag_name": tag, "target_commitish": sha}
    if previous:
        notes_input["previous_tag_name"] = previous
    # Without an earlier release, GitHub's default could compare this older run to a later tag.
    generated = ""
    if previous or not versions:
        generated = gh_api(f"{api}/releases/generate-notes", "POST", notes_input)["body"]
    body = (f"Signed Android app. Android 8.0 (API 26) or later.\n\n"
            f"Install `AnchorReader-{name}.apk`. The AAB is for distribution tooling. "
            "Verify downloads with `SHA256SUMS`.\n\n"
            f"Source: `{sha}` · Android versionCode: `{code}`\n\n"
            "First public release uses a new signing certificate. Existing debug/preview installs "
            "cannot update in place. Keep those installs and original source files until data migration "
            "is planned; uninstalling deletes local books and progress.\n\n"
            "Optional live ChatGPT question generation remains unverified as working; "
            "the latest measured live test failed. Reading and saved content work offline.\n\n"
            f"## Commits\n\n{commit_notes(sha, base)}\n\n{generated}")
    payload = {"tag_name": tag, "target_commitish": sha, "name": f"AnchorReader {name}",
               "body": body, "draft": True, "prerelease": False, "make_latest": "false"}
    if release is None:
        release = gh_api(f"{api}/releases", "POST", payload)
    else:
        release = gh_api(f"{api}/releases/{release['id']}", "PATCH", payload)
    command(["gh", "release", "upload", tag, *[str(directory / file) for file in names],
             "--repo", repo, "--clobber"])
    release = gh_api(f"{api}/releases/{release['id']}")
    verify_uploaded(release, directory, names)
    if tag_sha(api, tag) != sha:
        raise ValueError("Release tag changed during asset upload.")
    # The workflow serializes this job, including older runs which arrive after newer ones.
    newest = max((value for value, _ in versions), default=0)
    release = gh_api(f"{api}/releases/{release['id']}", "PATCH",
                     {"draft": False, "make_latest": "true" if number > newest else "false"})
    print(f"Published: {release['html_url']}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("version", "assets", "publish"))
    parser.add_argument("--assets", type=Path, default=Path("release-assets"))
    parser.add_argument("--badging", type=Path)
    args = parser.parse_args()
    if args.operation == "version":
        name, code = version(os.environ["GITHUB_RUN_NUMBER"])
        print(f"ANCHOR_VERSION_NAME={name}\nANCHOR_VERSION_CODE={code}")
    elif args.operation == "assets":
        if args.badging is None:
            parser.error("assets requires --badging")
        prepare_assets(args.assets, args.badging)
    else:
        publish(args.assets)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, KeyError, OSError, subprocess.CalledProcessError) as error:
        print(f"Release failed: {error}", file=sys.stderr)
        sys.exit(1)
