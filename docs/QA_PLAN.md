# Android QA

## Open Design regression

Follow [the screen and behavior map](OPEN_DESIGN.md) for issue #8 and children #9–#25. Check the five affected screens at 320/411dp, 800dp tablet, landscape and 200% text. Include long content, zero/partial/full reading visits, absent/current-concept questions, correct/wrong/skipped checks, final Complete and scoped results, source/full-text return and actual process restart. Verify lazy 3,000-concept overview selection/persistence. Preserve authentic settled screenshots; keep setup failures and early transition frames separate. Synthetic Chrome callbacks need signed-out first-run browser setup before tests. Do not describe separate passing retries as one clean full runner.

Use a separate test emulator. Tests change app data. Do not use a personal device with saved books.

## Run

JDK 17 or 21, Android SDK 35, and an Android emulator or approved test phone are required. Android Studio's bundled Java works on macOS.

```sh
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_SERIAL=emulator-5596
scripts/qa.sh all
```

Run `scripts/qa.sh unit` for JVM tests and Android lint. Run `scripts/qa.sh device` for instrumentation tests. Set `ANDROID_SERIAL` to the test device. The script installs and tests only that selected device. It does not start or stop devices.

Results: `app/build/reports/tests/testDebugUnitTest/`, `app/build/reports/lint-results-debug.html`, and `.tmp/qa/instrumentation.log` (or the `ANCHOR_QA_OUTPUT` folder).

## Automated acceptance checks

- Section detection: titled text, PDF headings/bookmarks and font contrast; one topic per concept across paragraphs/pages. Headingless source uses `Full document`. Wrapped names, page continuation and unmatched bookmarks preserve source text.
- Sentence identity: each complete sentence is one progress step, including abbreviations, numbered prefixes and long sentences. Bounded speech buffers do not add progress steps.
- Player: open sample/text/PDF or Continue/outline to autoplay after voice readiness. A pending start can be cancelled. Pause, dialogs, settings, background/return and stale speech callbacks cannot restart or move a new concept.
- Swipe up/down: full-height pages follow the finger with no gap. A held drag moves the background, video, header, artwork, text, actions and footer by the same distance. The next page has its own concept, count and controls. Progress and speech stay on the current concept until settlement. Moving and preview controls cannot trigger an action. A settled adjacent page starts at sentence one and requests autoplay once. Check short/reversed/horizontal gestures, strong ACTION_CANCEL, one-page fast flings, video, side/lower controls, long sentence scroll and edge transfer. First/last bounds do not wrap or open a quiz/finish screen. Natural completion pauses at the final sentence; sample Quick check remains explicit.
- Swipe state: Next/Previous, outline, external progress and book changes keep the pager in step. Continue movement after external navigation during a held drag, then check the next swipe. Old narration completion cannot advance a newly selected concept. Tap still mode then swipe immediately, with no timing gap, in both still-mode directions from a paused/completed sample concept.
- Concept progress: for concept A with three sentences and B with five, count/bar progress stays inside each concept and resets from `3 / 3` to `1 / 5`.
- Shared cards: sample/text/PDF use common title/body fonts and layout; author stays visible; Start/Continue has Play icon; Details is a named 48 dp icon control.
- Supertonic 3 HD neural TTS: on-device 44.1 kHz PCM16 synthesis via ONNX Runtime with zero network calls during playback; HTTP streaming download with BZip2 and Tar extraction; cryptographic verification of outer archive SHA-256 and 7 individual asset SHA-256 hashes; zip-slip and tamper rejection; 10 speaker voices (F1..F5, M1..M5); N+1 background lookahead with proactive pre-buffering on Player and Overview screens; 16 KB ELF native library alignment; minified release build without telemetry providers; Settings UI lifecycle states (NotInstalled, Downloading, Failed, Installed HD, System fallback) with >= 48dp touch targets and responsive wrapping; synthesis quality modes (`STUDIO_5_STEP` vs `FAST_3_STEP`) persisted via DataStore; natural prosodic clause streaming pipelining (`splitStreamClauses`) and streaming chunk playback via `Flow<ShortArray>` into `AudioTrack`.
- Cover: pixel/aspect/white-background checks prove actual PDF page one; image-only cover with digital body is accepted. Missing/corrupt cover has a safe fallback.
- Migration: old section metadata and progress map to source sentence offsets. Partial retry and corrupt record identity cannot alter a different saved book.
- Web articles and social links: URL validation for HTTP/HTTPS; 5 MiB payload ceiling; extraction of Twitter threads, Reddit posts/comments, Substack newsletters, Medium articles, and generic blogs. Heading chunking and 2–4 sentence fallback chunking.
- Android system share: `ACTION_SEND` intent with `text/plain` extracts URL, imports article, and starts immediate Player playback.
- Responsive overview actions: "Full text", "Source", and "Open web page" buttons adapt to 3-button row, 2-button row, or vertical stack under 320dp / 200% text scale.
- Sample library → player → transcript and source → still mode → concept controls.
- Correct, wrong, and skipped recall answers → lesson end → saved record after activity restart.
- Text book: required fields → save → open player → local persistence → delete.
- PDF: readable text import and original-file copy; damaged, oversized, protected, empty, and over-limit files fail without a saved book.
- Repository progress and saved books survive a new repository instance. Delete removes source and progress.
- Device speech: clear initialization status; with an installed offline English voice, real speech completion, pause cancellation, and speed change. The audio test reports a skip if voice data is absent.
- Video: two background frames change during narration; two frames remain fixed after still mode is selected. Frame images are saved to the test app's external `qa-motion/` folder.
- Landscape: the test Activity alone rotates; concept, source and transcript controls stay in the window. A saved text book has a 120-character title; its source and transcript remain readable. Source footer and transcript close work. The test restores orientation.

Imported text remains source text. Heading/bookmark evidence does not prove semantic AI conversion. No source evidence means one whole-document concept. Android speech output depends on the installed speech engine and offline voice.

## Manual checks

1. On a physical Android phone, create a book. Open its card and confirm narration starts without Play. Check voice, complete sentence changes, local count/bar, replay, speed and Pause. Check speech stops on exit, screen lock and another app taking audio focus.
2. In airplane mode, open the sample and a saved text/PDF book. Check text, video/still mode, source, recall checks, and installed offline voice. If the voice needs a network, check the message and text controls.
3. With TalkBack, check all controls, answer choices, dialogs, and errors. Check navigation order and control names. Use large text and landscape. Confirm content and buttons remain reachable.
4. In the Android file picker, select a PDF. Cancel once. Import a readable PDF. Open its saved source and use Zoom in, Reset view and Back to lesson. Remove the task-owned original picker file, then force-stop and reopen. Its private source copy must still open. Delete the saved book. Check that a scanned PDF gives a clear error.
5. On the test emulator only, disable its installed TTS engine. Confirm a clear voice message and disabled Play. Open source and transcript in portrait and at 200% text size in landscape. Restore the TTS engine and display settings in cleanup, even if a check fails. Verify real offline narration after restore.
6. Create a book with a 120-character title. At 200% text size in landscape, read its source and transcript. Use the source footer and transcript close action. Delete the test book and restore display settings.
7. Force-stop then reopen. Check resume location, saved books, still-mode preference, and answer record. Clear progress in settings; books must remain. Delete one book; other books must remain.
8. Import a PDF with a distinctive first-page cover and two sections on later pages. One section must cross a page boundary. Confirm the cover, source pages, section titles and full sentence order. In the library compare its card with the sample and a text book. Capture the native picker, final card, autoplay/count and both swipe directions.
9. At 411 dp, 320 dp, about 800 dp tablet, landscape and 200% font scale, check shared cards, complete sentence scroll and control reach. Restore device size, density, font scale and rotation after checks. Keep this evidence separate from previous release images.
10. On a physical phone, compare slow drags and fast flings with the requested TikTok/Reels behavior. Check that the entire page follows the finger: background, header, artwork, text, actions and footer. Check adjacent preview, release snap, one-page bounds, cancelled gestures and repeated strokes. Measure frame rate or jank before making a performance claim. Emulator input and screenshots do not prove physical touch feel.

Capture the visible screen:

```sh
scripts/qa.sh screenshot library
```

Use the original test fixture for a repeatable file-picker check:

```sh
"$ANDROID_HOME/platform-tools/adb" -s "$ANDROID_SERIAL" push app/src/androidTest/assets/digital-source.pdf /sdcard/Download/AnchorReader-QA.pdf
```

Screenshots are written to `.tmp/qa/`. Preserve reviewed evidence in the final QA report. Remove only task-owned temporary emulator files after tests; do not stop or change a user's emulator.

## Optional PDF practice: local automated checks

- New import starts off. No sign-in or source transfer while off. Save works offline. Enabled import saves a readable book before sign-in; cancel/failure retains it. Add later does not re-upload.
- Source-section identity includes location. Repeated headings remain separate; bounded requests cover every source span without creating narration sections.
- Validate completed JSON, distinct choices, answer index and exact source excerpt. Derive source page locally. Invalid/corrupt practice does not remove books or valid saved questions.
- Preserve question/answer versions, partial ready sections and explicit retry/cancel state. Process exit marks unfinished jobs paused. Account/source change, cancel/delete and reset reject late writes.
- Verify multiple questions in one section, saved correct/wrong/skipped answers, source page, exact paused reading return and offline restart.
- Verify total storage includes practice after answer/progress/import/delete/reset. Deliberately release a queued old answer after reset and confirm it does not return.
- Verify real Android Keystore encrypted storage, tamper/key-loss recovery and clear confirmation before resetting connection. Fake token fixtures must use isolated files/key aliases and clean them.
- Verify actual loopback callback path/method/host/state/duplicate/timeout, PKCE, signed-token issuer/audience/nonce/time/signature, refresh rotation and logout races, platform TLS/hostname rejection, redirects and bounded completed SSE. Test credentials/endpoints remain internal to tests.
- Verify empty, reset and partial browser sockets do not consume the callback attempt. Bound each read and the full attempt. Verify a valid callback survives a failed response write; rejected state remains rejected. On Android 15, use actual MainActivity and Chrome: return while token exchange is held, recreate/rotate the activity, cancel before a late grant and recover after verified process loss. Check ViewModel/job identity and protected storage, not only the app PID. See [sign-in scope](PHONE_SIGNIN.md).

## Optional PDF practice: manual and live checks

1. With networking disabled, import a PDF with the switch off. Check saved Details, original source and reading. Confirm no browser opens.
2. Enable the switch for another PDF. Confirm transfer/plan notice, then saved book before browser. Cancel login and verify readable source. From Details, add later and cancel the notice.
3. Use clearly labeled safe saved practice fixtures for question/answer/skip/source/return, partial/error/limit/insufficient/retry states. Check 320/411dp, tablet, landscape and 200% text. Do not describe fixtures as provider answers.
4. **Human live gate:** install the signed preview on an Android test phone. Sign in with an eligible ChatGPT account, consent, return to the app and confirm plan-use notice. Keep credentials out of logs/screenshots. Generate questions for a known digital PDF. Verify actual section scope, complete output, source quotes, pages, answer quality and saved offline use.
5. With that live connection, check reconnect, plan limit/Manage usage, token refresh, logout/revocation, browser return and process-loss recovery. Confirm saved reading/questions survive. A protocol fixture cannot close these gates.
6. With TalkBack and large text, check the switch, notices, question choices/feedback, source/back, task actions and account/reset confirmation. Check a physical phone, screen lock and audio focus. These remain open until actual evidence exists.

Use task-owned devices/files; restore network/display/font/rotation/voice settings. Preserve safe evidence outside `.tmp`; clean only owned resources afterward.
