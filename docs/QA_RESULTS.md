# Android QA results

11 October 2026. **PASSED for local PDF questions preview QA.** Live ChatGPT acceptance remains open.

56 JVM tests passed, with zero failures/errors/skips. Final QA reused unchanged passing JVM outputs. The full device run executed 52 methods: 51 passed and one test-helper failed before opening import. A reviewed direct-click correction then passed all four methods in the affected PracticeJourney class. All 52 unique methods have passing evidence, with three overlapping methods. This is not one clean 52-test run.

Owned emulator-5594: API 37 arm64, 1080 × 2400, 420dpi/about411dp, font1.0. Actual offline PDF import, default-off/on option, save before sign-in, cancellation, add later, saved practice/source/sequence and exact paused sentence return passed. Storage/reset fences and real Keystore tests passed. Protocol tests use task-owned socket/TLS/signed-token fixtures.

[Complete QA report](../reports/pdf-questions-preview-20261011/QA_REPORT.md), [process/result screenshots](../reports/pdf-questions-preview-20261011/UI_EVIDENCE.md), [native build/sign/install proof](../reports/pdf-questions-preview-20261011/HANDOFF.md), [UI audit](UI_AUDIT.md), [independent review](REVIEW.md).

Live plan eligibility, browser login/consent/callback, issued registration/scopes/models/schema, refresh/revocation, completed inference and real-PDF answer quality remain unverified. Safe saved question/status fixtures do not prove live generation. Physical phone, spoken TalkBack, other API levels, screen lock/audio-focus and audible speaker quality remain open.

Prior demo artifacts and evidence remain unchanged. Previous QA document retained under [historical docs](../reports/pdf-questions-preview-20261011/previous-docs/docs/QA_RESULTS.md). Its earlier release claims apply to that earlier artifact. See [test plan](QA_PLAN.md) for reproduction and live checks.
