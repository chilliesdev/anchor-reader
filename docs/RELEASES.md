# GitHub Android releases

The `Android` workflow builds a debug APK, runs JVM tests and Android lint on branch pushes and pull requests. It installs Android command-line tools before calling `sdkmanager`. This fixes the earlier `sdkmanager: command not found` failure. Test/lint reports and the debug APK remain workflow artifacts.

Each successful push to `main` also builds a signed, optimized APK and AAB from that push's exact commit. Publication requires that commit's checks to pass. Pull requests have read-only repository access and do not receive release signing secrets.

Get the installable APK from [GitHub Releases](https://github.com/chilliesdev/anchor-reader/releases). Android 8.0/API 26 or later is required. The AAB is for distribution tools; it cannot be installed directly. A GitHub release does not submit the app to Google Play.

## Version and change notes

Workflow run number `N` gives version `1.0.N`, tag `v1.0.N` and Android versionCode `N + 1`. The extra code keeps the first public version above the existing local versionCode `1`. Each run has a unique version; a rerun keeps the same version. PR and other branch runs can leave gaps. Do not delete/recreate the workflow or reset its run numbering without a version migration plan. Android versionCode must not exceed `2100000000`.

Release notes contain the exact source SHA, a commit list and GitHub-generated change notes. The commit list covers changes since the nearest earlier published workflow release. The first release uses the push's previous commit when that commit is available; otherwise it lists the reachable history. Thus direct pushes are included even when no pull request exists.

If an older run publishes after a newer run and has no earlier published release, its notes use the commit list only. This avoids a misleading GitHub comparison against a later source version.

Release assets:

- `AnchorReader-1.0.N.apk`: signed installable app.
- `AnchorReader-1.0.N.aab`: signed Android App Bundle.
- `SOURCE.json`: source SHA, version and workflow run URL.
- `SHA256SUMS`: SHA-256 checksums for those three files.

Verify a downloaded APK with Android SDK `apksigner verify --verbose --print-certs`. The configured release certificate SHA-256 is `a0afa847ec0c3b5bc3cb96bb3a9786b2b19b6f1444b91bb3924c2918e9e16fa9`.

## Signing setup and recovery

One stable private signing key signs all public releases. Configure these repository Actions secrets:

- `ANCHOR_KEYSTORE_BASE64`: base64-encoded keystore bytes, on one line.
- `ANCHOR_STORE_PASSWORD`: keystore password.
- `ANCHOR_KEY_ALIAS`: signing key alias.
- `ANCHOR_KEY_PASSWORD`: signing key password.

Keep an access-controlled recovery copy of the keystore, alias and passwords outside Git and outside public build artifacts. Back it up securely. Keep the same certificate for future updates. Losing or replacing this key prevents existing public installs from accepting an update signed by another certificate.

The workflow decodes the keystore into runner temporary storage, requires all four secrets, verifies APK signature/alignment and bundle signing, then removes the decoded key in an always-run cleanup step. It never creates a new signing key during a run. Missing/partial secrets fail the release; no unsigned APK is published. The normal local build may still produce unsigned release files when no signing variables are set.

To build a signed local release, set `ANCHOR_KEYSTORE` to the private keystore path and the other three signing variables in the process environment. Then use:

```sh
./scripts/build.sh :app:assembleRelease :app:bundleRelease \
  -PanchorRequireSigning=true -PanchorVersionCode=8 -PanchorVersionName=1.0.7
```

Choose an appropriate code for local use. These properties change only the release variant; debug builds retain `1.0.0`/code `1`. Set version properties together. Keep signing passwords out of shell history, reports and command output.

## Safe reruns and failures

The release job has one shared concurrency group, `cancel-in-progress: false`, and `queue: max`. GitHub retains up to 100 pending jobs. Main pushes are not cancelled by newer pushes. If the queue limit is reached, inspect Actions and rerun any job GitHub could not retain. Jobs can arrive out of source order; only the greatest published workflow version becomes Latest.

The script creates a tag at the exact checked commit, then creates a draft. It uploads all assets and checks upload state, size and available server digests before publishing. A failed upload leaves a draft, not a partial public release. Rerun that same workflow to repair the draft. Existing draft assets are replaced before publication. A tag that points to another commit stops the release; investigate it rather than moving a public tag.

An already published release is left unchanged on a rerun. Freshly rebuilt APK bytes do not replace published assets. A new source push creates a new version. Keep manual changes to the automated tag/version series out of the release job while it is running.

If Actions fails, inspect the failed step and saved reports. SDK installation must precede Gradle. Signing failures need the four secrets and the matching stable key. Run `python3 -m unittest discover -s scripts/tests -v` for local publication safeguards; these tests use fake GitHub responses and never publish.

## Existing preview installs

Existing debug/local previews use a different certificate. Android cannot update them in place with the first public release. Keep the installed preview and original imported files until you have a data migration plan. The app does not yet export all books/progress; uninstalling deletes that local data. Do not uninstall solely to test the new release if that data is needed.

The recovery patch passed 178 JVM tests and 62 isolated native device tests. Bounded authentic ChatGPT acceptance on 11 October 2026 used a cap1 diagnostic APK on an API37 emulator and saved six source-valid questions across two READY sections. Source/answer checks, native correct/wrong feedback, offline practice and real process restart persistence passed. See the README for scope and dated failure history. Other accounts/models, physical devices and provider billing remain unverified; the upstream empty-output cause remains unknown. CI checks each release commit separately and does not establish those additional behaviors or Google Play acceptance.
