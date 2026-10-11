---
name: git-release-flow
description: Build and hand off this native Android app locally. No Git or publication required.
---

# AnchorReader local release

Use the active Android checkout. README.md is the release source.

1. Complete implementation, UI audit, code review, and QA. Preserve evidence outside .tmp before handoff.
2. Run `./scripts/build.sh :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleRelease :app:bundleRelease`.
3. No npm project exists. Kotlin compile, unit tests, and Android lint are the applicable code checks. Do not create npm scripts only to satisfy the generic pipeline.
4. This directory has no .git. Do not initialize Git, commit, push, open a PR, deploy, or submit to a store unless the user requests it.
5. Produce a local install APK using the documented development signing approach already used for `artifacts/AnchorReader-demo.apk`. Preserve signing files outside source control. No production signing key is required for local delivery.
6. Replace the local demo APK and unsigned release AAB only after all gates pass. Update `artifacts/SHA256SUMS`. Verify install/signature and open the final install APK on the task-owned emulator.
7. Update README and test documentation for the delivered behavior and limits. Never commit .tmp or credentials.
8. Report the APK/AAB paths, hashes, tested emulator, passed checks, and any untested physical-device/voice surface. No claim of store release.

Keep task-owned temporary test data separate. Clean only owned device/files/processes after durable evidence is saved. Keep required screenshots and reports.
