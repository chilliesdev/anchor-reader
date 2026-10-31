# Android QA results

## Supertonic 3 HD neural speech latency optimization and quality mode

31 October 2026. **PASSED for issue #42 (Supertonic 3 HD neural speech latency optimization).** Reviewed base plus Supertonic engine streaming, clause pipelining, quality modes (Studio 5-step vs Fast 3-step), DataStore preferences, proactive pre-buffering, and Settings UI selector.
- Native debug build and Android lint passed with zero errors and zero warnings.
- 221 JVM unit tests passed cleanly (0 failures, 0 errors, 0 skipped), including `SupertonicQualityModeTest`, `SupertonicTextFrontendTest`, and `NarrationStateMachineTest`.
- 9 connected device tests passed on `emulator-5554` (API 37 `sdk_gphone16k_arm64`, 16 KB page-size kernel) in `SettingsUiDeviceTest`.
- Synthesis quality selection verified: `Fast (3 steps)` and `Studio (5 steps)` options persist correctly via DataStore; radio cards meet accessibility >= 48dp target requirements.
- Responsive layout verified across standard portrait (411dp), narrow portrait (320dp), landscape (840x411dp), and 200% font scale.
- Streaming audio playback verified: clause 1 emits immediately via `Flow<ShortArray>` sub-chunks to `AudioTrack`, reducing time-to-first-audio latency.
- Lookahead cache verified: proactive pre-buffering retains pre-synthesized audio without spurious re-synthesis when concepts match.

## Supertonic 3 HD neural text-to-speech update

30 October 2026. **PASSED for issue #39 (On-device Supertonic 3 HD neural text-to-speech).** Reviewed base `722ab2f` plus Supertonic engine, ONNX runtime integration, VoicePackService, dual-engine Narrator, and Settings UI.
- Native debug build and Android lint passed with zero errors and zero warnings.
- 218 JVM unit and integration tests passed cleanly (0 failures, 0 skipped).
- 98 connected device tests passed on `emulator-5554` (API 35, 16 KB page-size kernel).
- 16 KB ELF native page alignment verified on release APK across all ABIs (`zipalign -c -P 16 -v 4`).
- Merged manifest verified: `ACCESS_NETWORK_STATE` removed and `ai.onnxruntime.TelemetryInitializer` stripped.
- Security and tamper safeguards verified: outer archive SHA-256 and 7 inner file hashes verified; zip-slip path traversal and corrupt files rejected.
- Dual-engine playback verified: 44.1 kHz PCM16 streaming via `AudioTrack`, speed adjustment (0.75x–2.0x), instant pause and flush, N+1 lookahead pre-synthesis, and 3-minute idle session release.

## Web and social reading platforms update

30 October 2026. **PASSED for Web & Social Reading Platforms.** Reviewed base `722ab2f` plus web extraction and UI changes. Native debug build and Android lint passed with zero errors and zero warnings. 219 JVM unit tests passed with zero failures, zero errors, and zero skips.

Key verified scopes:
- Twitter/X thread extraction (FXTwitter API, thread chunking `Tweet N/M · @author`, oEmbed fallback).
- Reddit post extraction (public `.json` parser, post body, top discussion comments filtered for AutoMod/deleted).
- Substack, Medium and generic web articles (Jsoup DOM cleaning, heading chunking, 2–4 sentence fallback chunking).
- WebHttp network guard (15s connect / 30s read timeouts, 5 MiB payload ceiling, safe redirect limit).
- Android system share integration (`android.intent.action.SEND` for `text/plain` triggers direct extraction and playback).
- Persistence & compatibility (`sourceUrl` serialization, legacy record loading with default `null`).
- Device UI tests on `Pixel_8` AVD: `OverviewActionsDeviceTest` (2/2 pass) and `LibraryCardsDeviceTest` (3/3 pass). Responsive action stack verified at 320dp and 200% font scale.

Durable test evidence and UI screenshots are preserved in `docs/evidence/web-reader/`.

## Open Design update

12 October 2026. **PASSED for issue #8 and all 17 children #9–#25.** Reviewed base `538ef6783b701752d5389f0c1feae5c8890e36ec` plus final design changes. Debug APK SHA-256 `09392c3d0a47f1d51e079f867f8741d57c53a0aed66c63c49e517c9f728d2ada`. Native build/lint passed; 194 JVM tests passed with zero failures/errors/skips. Lint has zero errors and 15 existing warnings.

All 82 unique native cases have passing evidence across two runs. The initial full run passed 79, failed three and skipped zero in 379.917 seconds. Chrome first-run setup blocked the three synthetic callback cases. After signed-out browser setup, those identical cases passed 3/3, zero skips, in 16.321 seconds. No app change or live account/provider request was made. Initial failures and per-case run identities remain in local evidence; this is not one clean 82/82 runner result.

Independent rendered audit and Standards/Spec review passed. Actual sample and imported workflows verified correct/wrong/skipped checks, final Complete, scoped results, source/full-text actions, Review and byte-identical saved records after a real process restart. Reading progress remains actual: sample 3/4 concepts; imported PDF 1/2 despite completed checks. The 3,000-concept overview regression passed lazy composition, final selection and saved progress. Real offline narration and video/still motion passed without skips.

Owned API37 arm64 emulator: phone 411dp; independent UI matrix also covers 320dp, 800dp tablet, landscape, long content and 200% text. Settled process/error images were captured and viewed. Three early transition frames are excluded from claimed-state proof. No physical-device, spoken TalkBack, acoustic output or new live-provider acceptance is claimed. CI and signed publication have separate exact-commit gates. See [screen behavior and reproduction](OPEN_DESIGN.md).

## Phone sign-in repair

11 October 2026. Controlled sign-in QA passed on Android 15 with Chrome 124.0.6367.219. The reviewed phone files passed 192 JVM tests, zero failures/errors/skips; lint had zero errors and nine existing warnings. Native QA passed 11 regular cases: five MainActivity lifecycle cases, three error UI cases and three transport/security/storage cases. The deliberately killed process-loss preparation is excluded from that count.

Those native bytes used base `25621ad2a1ce9115a5d1aa67987304663bd3753b` plus the six phone-fix files. Later integration adds main `1f0782abf404a3f3efb0410619eee995faa6ac29`; the six phone files remain identical. The integrated JVM suite passed 192 tests with zero failures/errors/skips; integrated lint had zero errors and 15 warnings. Debug APK, optimized unsigned release APK and unsigned AAB builds passed. Final integration review passed with matching auth, ViewModel and Manifest bytes. The exact integrated debug APK passed a separate production-default startup and Settings smoke on AOSP Android 15, disconnected and without provider/voice injection. The extra healthy-Chrome return repeat remained blocked by emulator/browser setup; its AOSP crash-resume diagnostic does not prove a healthy browser return. These checks do not increase the eleven earlier authentication passes. Signed public release/update checks remain separate gates. Actual OpenAI sign-in, physical Redmi acceptance and the precise phone failure cause remain unverified. [Behavior, fixtures, evidence and limits](PHONE_SIGNIN.md).

## Previous full-page swipe preview

11 October 2026. **PASSED for the local full-page swipe preview.** Independent rendered UI audit and source review passed. Final full native runner: **60/60 passed, zero failures/skips**, 299.808 s. Release build passed in 2m58s: debug APK, 141 JVM tests across 13 suites, lint, optimized release APK and unsigned AAB. Zero JVM failures/errors/skips. Lint: zero errors/fatals, 15 existing warnings.

These results and the linked local APK cover the reviewed swipe preview before current-main generation-recovery/OAuth integration. Combined-source review and QA are separate.

The entire page moves in still/video modes: background, header, artwork, sentence, actions, count, progress and footer. Adjacent page edges meet without a blank strip. Preview content belongs to the adjacent concept; moving controls cannot trigger actions. Progress/narration stay on the selected concept until settlement. Settlement starts its first sentence and requests autoplay once. Up/down, cancel/reverse/horizontal, first/last bounds, one-page flings, long sentence scroll and immediate still-toggle/swipe passed.

Owned AnchorFullPage emulator-5596: API37 arm64, 1080×2400, 420dpi, font1.0. Independent layouts passed at phone 411dp, narrow 320dp, tablet 800dp, landscape and 200% font. Real offline voice completion/pause/speed and video motion/still tests passed with no skip. Headless emulator had no audio output.

Reviewed four-file diff SHA-256 `0ed00e2d54bc0bf267f893316e298efbf4a838c3854fdad775be7e6b4ee89238`, base `483bc410f58a859e750b2b1bb11d6737818970b5`. Final debug APK remains byte-identical to the audited app. [Current QA](../reports/full-page-swipe-preview-20261011/QA_REPORT.md), [UI/process evidence](../reports/full-page-swipe-preview-20261011/UI_EVIDENCE.md), [review](../reports/full-page-swipe-preview-20261011/REVIEW_FEEDBACK.md), [release handoff](../reports/full-page-swipe-preview-20261011/release/HANDOFF.md).

Earlier full runs were 59/60. Run1 lost native MOVE events under shell input backpressure; the test injector was corrected without changing assertions or deadline. Run2 timed out on a practice restart source-image wait; its isolated diagnostic passed without a final code change. A focused Compose timeout also passed in isolation. The untraced timeout causes remain unknown. All affected methods passed the fresh full runner; earlier failures retain their original status. [Diagnosis and failure links](../reports/full-page-swipe-preview-20261011/FIX_DIAGNOSIS.md).

Distinct [signed APK](../artifacts/AnchorReader-full-page-swipe-preview-20261011.apk), SHA-256 `708d78a1f05574e1a2a9437b9fbbdc225ef373b7013934f455a0efdecab8d370`; [unsigned AAB](../artifacts/AnchorReader-full-page-swipe-preview-20261011-unsigned.aab), SHA-256 `290603c009f6249b73d5d946b23bd32d7695bfd8880dda61d781c7e19f9b2a04`. Signed optimized APK install/smoke, v2/v3 signature, 16 KiB alignment and installed-byte equality passed. Owned emulator/AVD cleanup passed after durable evidence checks. Development APK and bundle are local previews. No store submission. No live provider request. For this pre-integration APK, live generation acceptance remained open. README records the later recovery evidence. Physical finger feel, measured FPS/jank, acoustic output, spoken TalkBack, other API levels and screen-lock/audio-focus checks remain unverified.

## Previous concept swipe preview

11 October 2026. **PASSED for the local concept swipe preview.** Independent source review, rendered UI audit, native QA and signed optimized APK smoke passed. Live ChatGPT generation remains a separate failed acceptance gate.

The player now tracks a vertical drag, shows the adjacent concept and snaps after release. Controls stay fixed. Preview keeps current progress and narration; settlement selects sentence one and requests autoplay once. Native regressions cover strong cancellation, short/reversed/horizontal gestures, one-page fling, first/last bounds, long text, held model navigation and immediate still-toggle/swipe.

Reviewed five-file diff SHA-256: `775e58423b7c4666b4f37df6aa9ad76c69f959ba4b95ddeac96a696c2f401876`, against base `b4945c90a7cb500e3e0c362a4b8abfa4a05494ee`. The final affected UI audit passed both actual paused/completed concept-one still-mode directions with an immediate 400ms swipe. Phone 411dp, narrow 320dp, tablet 800dp, landscape and 200% font layouts passed; the final guard correction does not change that layout.

Final native build passed in 2m14s: debug APK, 141 JVM tests across 13 suites, lint, optimized release APK and unsigned release AAB. Zero JVM failures/errors/skips. Lint has zero errors/fatals and 15 existing warnings. Final full device retry passed 58/58 in 66.384s on unchanged reviewed APKs. The first full run had one existing 10s storage wait timeout; that method passed alone in 0.468s, then the complete suite passed. The cause remains unconfirmed; no timeout or source change was made for it.

Owned AnchorSwipe, `emulator-5584`: API37, 1080×2400, 420dpi, font1.0. Both exact no-gap still-toggle directions passed. Two rapid 100ms strokes before settlement ended in one coherent adjacent page with sentence-one autoplay and no skip. The signed optimized APK then passed actual sample open/autoplay, up/down page selection, first-sentence count, source return paused and an immediate still-toggle/up swipe. App runtime had no fatal marker during that smoke. Signature v2/v3, the existing development certificate, 16 KiB alignment, unsigned AAB integrity and installed APK byte equality passed.

Distinct [swipe preview APK](../artifacts/AnchorReader-concept-swipe-preview.apk), SHA-256 `dd9e46e270d0b72342e07a6b76cc6c236612e012dd954ce9fea72497c4706894`; [unsigned AAB](../artifacts/AnchorReader-concept-swipe-preview-unsigned.aab), SHA-256 `73f69010394b3006544393878a731ab1c41291d67e51fc82acf8c5abfce576f3`. Prior demo files and checksum records remain unchanged. [Complete handoff](../reports/concept-swipe-preview-20261011/release/HANDOFF.md), [final QA](../reports/concept-swipe-preview-20261011/QA_REPORT.md), [UI/process evidence](../reports/concept-swipe-preview-20261011/UI_EVIDENCE.md).

Swipe QA made no live provider request. It does not close live ChatGPT generation or physical phone, spoken TalkBack, acoustic narration and frame-rate/jank gaps. The preflight wrong-target data-reset incident remains documented in the handoff; no later action touched that unrelated emulator.

## Previous PDF questions preview

**PASSED for local PDF questions preview QA.** Live ChatGPT acceptance remains open. The following results belong to that earlier preview.

56 JVM tests passed, with zero failures/errors/skips. Final QA reused unchanged passing JVM outputs. The full device run executed 52 methods: 51 passed and one test-helper failed before opening import. A reviewed direct-click correction then passed all four methods in the affected PracticeJourney class. All 52 unique methods have passing evidence, with three overlapping methods. This is not one clean 52-test run.

Owned emulator-5594: API 37 arm64, 1080 × 2400, 420dpi/about411dp, font1.0. Actual offline PDF import, default-off/on option, save before sign-in, cancellation, add later, saved practice/source/sequence and exact paused sentence return passed. Storage/reset fences and real Keystore tests passed. Protocol tests use task-owned socket/TLS/signed-token fixtures.

[Complete QA report](../reports/pdf-questions-preview-20261011/QA_REPORT.md), [process/result screenshots](../reports/pdf-questions-preview-20261011/UI_EVIDENCE.md), [native build/sign/install proof](../reports/pdf-questions-preview-20261011/HANDOFF.md), [UI audit](UI_AUDIT.md), [independent review](REVIEW.md).

Live plan eligibility, browser login/consent/callback, issued registration/scopes/models/schema, refresh/revocation, completed inference and real-PDF answer quality remain unverified. Safe saved question/status fixtures do not prove live generation. Physical phone, spoken TalkBack, other API levels, screen lock/audio-focus and audible speaker quality remain open.

Prior demo artifacts and evidence remain unchanged. Previous QA document retained under [historical docs](../reports/pdf-questions-preview-20261011/previous-docs/docs/QA_RESULTS.md). Its earlier release claims apply to that earlier artifact. See [test plan](QA_PLAN.md) for reproduction and live checks.
