---
name: git-release-flow
description: Deliver completed AnchorReader changes through a PR to main, then merge after all applicable checks pass.
---

# AnchorReader Git release

Use the active checkout. Read `README.md`, `docs/RELEASES.md` and `.github/workflows/android.yml` for current build and publication rules. Explicit user scope, such as local-only or PR-only, overrides this flow. When the user wants an opening prompt for the full release, use [the release prompt](references/release-opening-prompt.md). The saved example grants no authority by itself.

1. Complete the project delivery pipeline: implementation, applicable UI audit, code review and QA. Update affected documentation. Preserve required evidence outside `.tmp` before handoff.
2. Select local checks by changed scope. For Android app changes, run `./scripts/build.sh :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` and affected device QA. For release automation, run `python3 -m unittest discover -s scripts/tests -v`. For skill or documentation-only changes, run relevant validation and `git diff --check`; no APK build is needed solely for text. No npm project exists; do not add npm scripts to satisfy the generic pipeline.
3. Check Git status, remote and current branch. Reuse the current task branch; otherwise create a `codex/<task>` branch from the intended start point. Do not release from detached HEAD or commit directly to `main`. Fetch `origin/main` and record the expected head and base commits.
4. Review the diff and staged paths. Commit only intended changes. Keep `.tmp`, signing files, credentials, local build data and unrelated edits out of Git. Push the task branch with its upstream; do not force-push.
5. Write the PR body to an ignored file. State the change, validation and material limits. Create a non-draft PR with `gh pr create --base main --head <branch> --body-file <file>`, or update the existing task PR. Attach every created or continued task PR with `mcp__codex_app__attach_artifact`.
6. Inspect PR checks/statuses and all applicable branch-push and PR workflow runs for the current head. Wait for completion, then confirm each run with `gh run view <run-id>`; `gh run watch` alone is insufficient. For a PR's synthetic merge commit, verify its PR head and base. No-check output is not success. Failed, cancelled, pending or stale applicable checks block merge.
7. Require all applicable checks to pass. The Android `Build, tests and lint` job must pass on both push and PR runs. Accept only expected conditional skips: `Publish signed Android release` is skipped on branch/PR runs, but must run on a `main` push. Do not bypass a failed check or remove a gate.
8. Immediately before merge, recheck that the PR is open, non-draft, targets `main`, has the expected head and current base, and has no conflict, unresolved required review or other merge blocker. Recheck passing checks and current repository rules. If head or base changes, refresh checks and repeat affected review/QA before merging.
9. Merge with the repository's normal allowed method, currently squash: `gh pr merge <PR> --squash --match-head-commit <verified-head>`. Do not use `--admin`, disable rules, or push directly to `main`. If GitHub rejects the merge, resolve the blocker and repeat the checks.
10. Verify PR state `MERGED` and record the merge commit. Wait for the `main` push workflow at that exact commit; use `gh run view` to confirm checks and signed publication separately. Verify the published tag, `SOURCE.json` source SHA, APK/AAB and `SHA256SUMS`. Follow `docs/RELEASES.md` for safe reruns. Keep a failed publication explicit; PR merge alone is not release success.

When local install files are part of the task, also build `:app:assembleRelease :app:bundleRelease`. Use the documented signing approach and an existing key kept outside Git. Replace local delivery files and checksums only within the requested scope and after the gates pass. Verify APK signature, alignment, install and app-open on the task-owned emulator. Never create or replace the public signing key to obtain a passing build. A `main` merge publishes signed files automatically; it does not submit to Google Play.

Report the PR URL, head/merge commits, local validation, CI and publication state separately. Include artifact paths/hashes and device limits when applicable. Clean only task-owned data, files, devices and processes after required evidence is saved.
