# Android review

## Open Design update

12 October 2026. Independent Standards and Spec reviews **PASSED**, zero unresolved material findings. Reviewed final production changes and all device/JVM regressions against fixed integrated base `538ef6783b701752d5389f0c1feae5c8890e36ec`.

Review covers all children #9–#25, scoped completed-session questions/answers, actual visit progress, paused reading return, autoplay and full-page gesture ownership. Narrow/large-text layouts use measured native text widths without reducing the user's text size. The overview keeps lazy per-concept rows and joined card borders; a 3,000-concept regression verifies bounded composition and last-concept persistence. No new ignore, suppression, disabled gate or timeout increase was added.

Rendered UI audit and functional QA passed separately. Review does not establish physical-device or new live-provider acceptance. [Behavior and checks](OPEN_DESIGN.md), [QA results](QA_RESULTS.md).

## Previous full-page concept swipe review

11 October 2026. Independent review **PASSED**. Zero Standards or Spec findings. Reviewed four application/test files against base `483bc410f58a859e750b2b1bb11d6737818970b5`; diff SHA-256 `0ed00e2d54bc0bf267f893316e298efbf4a838c3854fdad775be7e6b4ee89238`.

This review covers the unchanged four-file swipe change before current-main integration. Combined-source review is separate.

Review covered complete page ownership, preview content, disabled moving controls, one settlement, sentence-one autoplay, video lifecycle and native input delivery. The test input correction keeps the original assertions and deadline. No timeout increase or suppression.

[Complete review](../reports/full-page-swipe-preview-20261011/REVIEW_FEEDBACK.md), [input/timeout diagnosis](../reports/full-page-swipe-preview-20261011/FIX_DIAGNOSIS.md), [QA](QA_RESULTS.md), [UI audit](UI_AUDIT.md).

## Previous PDF questions review

11 October 2026. Independent review **PASSED** for the PDF questions preview. Zero required findings remain. Final app hashes are preserved in the [source manifest](../reports/pdf-questions-preview-20261011/pdf-questions-final-source-sha256.json).

Review covered source sections/spans and question evidence, atomic practice/task/answer storage, exact reading return, reset/delete/cancel/account fences, system-browser OAuth, fixed HTTPS origins, PKCE/state/nonce, verified RSA identity, protected credentials, refresh/revocation races and completed bounded SSE. Separate rendered audit and local QA passed.

Two P2 issues were fixed: storage totals include practice on every refresh; reset rejects queued old answer writes. Deterministic regressions have passing device evidence. A subsequent one-line test-helper correction was reviewed; production hashes did not change.

[Complete review](../reports/pdf-questions-preview-20261011/REVIEW_FEEDBACK.md), [QA correction review](../reports/pdf-questions-preview-20261011/QA_TEST_FIX_REPORT.md), [UI audit](UI_AUDIT.md), [local QA](QA_RESULTS.md), [preview handoff](../reports/pdf-questions-preview-20261011/HANDOFF.md).

Static review and fixtures do not prove live ChatGPT eligibility/login/return/scopes/models/inference, refresh/revocation or answer quality. Both damaged connection registry and encrypted credentials can prevent retaining issued IDs; inaccessible storage reports failure. A failed initialization can require restarting the app after storage recovery. These limits remain disclosed.

Earlier section/player review is preserved in [historical docs](../reports/pdf-questions-preview-20261011/previous-docs/docs/REVIEW.md). Its no-network/no-generated-questions statements describe the prior demo, not the new preview.
