# AnchorReader for Android

A native Android reader based on the supplied original design. Kotlin and Jetpack Compose implement the screens. Books, progress, source files, preferences and saved practice stay on the device. Optional question generation sends selected section text to OpenAI through the user's ChatGPT connection.

## Read and practise

- Create a book from text or import a digital PDF through the Android file picker.
- Read one complete sentence at a time. Source sections form concepts, including sections across PDF pages.
- Start offline device narration from a book, Continue or a concept. Swipe up/down to change concept. Pause, change speed or use still mode.
- Open the retained original PDF, lesson outline or full text. PDF cards show its original first page.
- On each new PDF import, **Add practice questions** starts off. Save without it to read locally.
- Turn it on to save the book, then connect to ChatGPT. The form explains that section text goes to OpenAI and uses the connected ChatGPT plan. Cancellation or an error keeps the saved book readable.
- Add questions later from book Details. No new upload is needed. The app saves up to three multiple-choice questions per section; short sections can have fewer.
- Practise questions in order. Answer, skip or check the source excerpt/page. Continue reading or Back restores the same reading sentence with narration paused.
- Ready questions and answers work offline. Incomplete sections retain an explicit retry action. Cancel, restart, sign-out and deletion preserve safe reading state.
- Settings has the ChatGPT connection, sign-out and Manage usage. No provider/model picker or API key is required. A damaged local connection can be reset after confirmation; books and practice remain.
- Save/resume reading, inspect storage, clear progress or delete imported books and sources. Clearing progress also clears answers; saved questions remain.
- Prepared sample lesson, media, diagram and authored recall checks remain available.

## Source and offline limits

Section detection uses source headings, font differences and PDF bookmarks. Sections can span paragraphs/pages. A source without clear section evidence becomes one `Full document` concept. This does not generate summaries. Each complete sentence remains one reading step, even when long. Narration stops at the final sentence of a concept; source/transcript dialogs and background return stay paused.

PDF extraction can change reading order; check the original source. An image-only first-page cover is accepted with digital body text. Scanned body pages, encrypted and damaged PDFs return an error. Maximum input: 25 MiB, 200 pages and one million text characters. No OCR or cloud conversion is included. A missing original PDF cannot be rebuilt from extracted text.

Reading, source views, saved questions/answers and bundled media work offline. Narration needs an installed offline English device voice. If unavailable, the player explains the issue; text stays readable. The app does not select a network voice silently. New questions require internet access and a ChatGPT account with the required plan access.

## Optional ChatGPT connection

This preview uses this app's own system-browser sign-in. It does not use the desktop Codex connection. OAuth uses PKCE, a local callback and verified identity. Protected credentials use Android Keystore and private storage excluded from backup. Sign-out clears local credentials and attempts remote revocation; Manage usage remains available if that cannot be confirmed. Lost sign-in after process exit needs a fresh attempt.

The app asks for a completed structured response and checks each question's choices, answer and quoted source locally. Source pages come from the retained local spans. These checks cannot prove teaching quality or that an answer is correct in every context; check the source. Failed, partial, refused or invalid responses do not become questions. No automatic generation retry or API-funded fallback occurs.

Authentic ChatGPT acceptance passed on 11 October 2026 with the authorized account, public River Guide and a cap1 diagnostic APK on an API37 emulator. Six source-valid questions were saved across two READY sections. Source pages and all answer keys, native correct/wrong feedback, offline practice and persistence after a real process restart passed. This result covers that account, fixture and emulator. Other accounts/models, physical Android browser return and provider billing remain unverified. The upstream cause of the empty terminal output remains unknown.

Earlier diagnostic test on 11 October 2026, before this repair: genuine sign-in passed, but the final test **failed** on the separate cap1 test APK. One first-section invocation reached HTTP 200 but was rejected at `output_empty`; zero questions were saved and zero of two sections were ready. Source review maps this fixed category to a present empty completed-output array. At that point, the cause was unknown and no deterministic local fix had been found. The captured `media=missing` category does not prove the wire header was absent. [Measured failure](reports/chatgpt-live-20261011/stream-branch-diagnostics/live/LIVE_QA_REPORT.md), [independent audit](reports/chatgpt-live-20261011/stream-branch-diagnostics/live/INDEPENDENT_OUTCOME_AUDIT.md), [finding](reports/chatgpt-live-20261011/stream-branch-diagnostics/live/EMPTY_OUTPUT_FINDING.md).

Earlier diagnostic-only preview on 11 October 2026: fixed diagnostic categories retained the earlier acceptance rules. Its separate two-request/six-question grant used two app-level invocations and at most six requested questions, both failures. That grant was exhausted. Full-book completion, live answer/source quality and offline use of live questions were not proved by those earlier tests. [Historical accounting](reports/chatgpt-live-20261011/stream-branch-diagnostics/live/LAST_CALL_ACCOUNTING.json), [historical handoff](reports/chatgpt-live-20261011/current/CURRENT_HANDOFF.md).

## Build and run

Requirements: Android Studio, JDK 17 or 21, Android SDK 35, Android 8.0/API 26 or later. Gradle/dependencies are pinned; the first build needs internet access. Open this directory in Android Studio, sync, select `app`, then Run.

```sh
./scripts/build.sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.anchorreader/.MainActivity
```

The script can use Android Studio's bundled JDK on macOS. Set `JAVA_HOME` and `ANDROID_HOME` for another installation. `local.properties` is machine-specific.

## Local files and validation

The links in this section refer to local build and QA files. These files are not included in the public repository. Build the app with the commands above.

Earlier diagnostic preview on 11 October 2026, before this repair: [signed normal default0 APK](artifacts/AnchorReader-pdf-questions-stream-diagnostics-default0-20261011.apk), SHA-256 `ab33d927b051483019c5a15defd0d81e8bf8f1ce7481e9000d86edff54c08a02`. [Unsigned preview AAB](artifacts/AnchorReader-pdf-questions-stream-diagnostics-default0-20261011-unsigned.aab). The separate [cap1 test APK](artifacts/AnchorReader-pdf-questions-stream-diagnostics-cap1-20261011.apk), SHA-256 `d1a70012df7ea3e426c875b751c179cdcecbaa63e4bbb2e32786b4ed124b75c4`, was used for the failed final bounded test. Those signed local previews did not pass live question generation. After the test, normal Sign out removed the local connection. Remote revocation is app-reported only. Exact normal0 bytes were installed with data retained; the original PDF was readable and import questions were OFF. The owned emulator was stopped; its private AVD and evidence remain. Cleanup used zero extra Responses invocations. [Cleanup proof](reports/chatgpt-live-20261011/stream-branch-diagnostics/live/CLEANUP_REPORT.md), [independent cleanup audit](reports/chatgpt-live-20261011/stream-branch-diagnostics/live/INDEPENDENT_CLEANUP_AUDIT.md), [final handoff](reports/chatgpt-live-20261011/stream-branch-diagnostics/final/FINAL_HANDOFF.md). No store submission or accepted demo replacement.

Earlier optional-question [preview APK](artifacts/AnchorReader-pdf-questions-preview.apk) and [unsigned preview AAB](artifacts/AnchorReader-pdf-questions-preview-unsigned.aab) remain unchanged. The AAB needs a release key for store use.

The prior [demo APK](artifacts/AnchorReader-demo.apk) and [unsigned release AAB](artifacts/AnchorReader-release-unsigned.aab) remain unchanged. They contain the earlier section/swipe release and do not contain this ChatGPT flow. See [all file hashes](artifacts/SHA256SUMS).

Recovery-patch validation: **178 JVM tests and 62 isolated native device tests passed**, with zero failures or skipped tests. Android lint has zero errors and nine warnings. Independent code review and the native UI audit passed. Generation/save/error, source checks, persistence and offline fixtures use the actual ViewModel and repositories. The normal default0 local release APK passed signature, alignment, installation and app-open checks. Authentic generation used the separate cap1 diagnostic build described above. GitHub build/test/lint checks and public signed releases are verified separately against their exact source commit.

Historical optional-feature QA: 52 unique device methods have passing evidence from an initial 51/52 run plus the corrected four-method class, with three overlaps. This was not one clean 52-test run. That earlier 52-method suite was not repeated on the earlier diagnostic APK. Prior rendered UI audit and practice fixture evidence remain in the [earlier preview report](reports/pdf-questions-preview-20261011/HANDOFF.md) and [UI/process screenshots](reports/pdf-questions-preview-20261011/UI_EVIDENCE.md). [Original test plan](docs/QA_PLAN.md), [historical QA](docs/QA_RESULTS.md), [UI audit](docs/UI_AUDIT.md), [review](docs/REVIEW.md).

```sh
./scripts/build.sh :app:testDebugUnitTest :app:lintDebug
ANDROID_SERIAL=emulator-5594 ./scripts/qa.sh device
```

Use a separate test emulator; device tests clear its app data. Select its actual serial. Tests include import/sections, full sentences, autoplay/pause/swipes, cards/covers, migration, storage, source/reading return, practice persistence/task fences, reset races, Keystore and provider protocol security. Fixture practice states are labeled in evidence. See [QA plan](docs/QA_PLAN.md).

Physical phones, spoken TalkBack, other Android API levels, screen-lock/audio-focus interactions and acoustic speaker quality remain unverified. Emulator checks do not replace these checks.

## Signing

Public signed APKs, signed AABs, checksums and change notes are published to [GitHub Releases](https://github.com/chilliesdev/anchor-reader/releases) after checks pass for each new `main` push. See [release setup, versions and safe reruns](docs/RELEASES.md). Existing local previews use a different signing certificate; keep needed books/progress and original sources before planning a migration. Uninstalling removes local data.

```sh
./scripts/build.sh :app:assembleRelease :app:bundleRelease
```

Without signing variables, these are unsigned. For a store release, set `ANCHOR_KEYSTORE`, `ANCHOR_STORE_PASSWORD`, `ANCHOR_KEY_ALIAS` and `ANCHOR_KEY_PASSWORD` to your own key. Keep keys/passwords outside source control. For local delivery, align/sign the optimized APK with SDK tools and the existing external development key; verify its signature and installation. Signing keys and passwords must stay outside Git. Local APKs, bundles, QA reports and screenshots are excluded from the public repository.

## Structure

- `ReaderViewModel`: navigation, reading/practice/account state, ordered writes and lifecycle fences.
- `data/`: source sections/spans, bounded PDF/text extraction, atomic book/practice storage and question validation.
- `chatgpt/`: OAuth, protected credentials, fixed HTTPS transport, identity verification and completed streaming output.
- `ui/`: native screens, optional import, practice and account controls.
- `Narrator`, `PlayerScreen`, `SourceDialog`: offline speech, local media and retained source views.

The native Android request supersedes the older PWA report. No measured learning benefit or automatic AI narration is claimed. See [media/dependency credits](docs/CREDITS.md).
