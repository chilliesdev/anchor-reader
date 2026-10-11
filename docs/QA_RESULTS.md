# Android QA results

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
