# ChatGPT phone sign-in

The user reported app 1.0.14 on Redmi Note 13, Android 15 and Chrome. The browser returned to the app, which showed “ChatGPT sign-in did not finish. Connect again.” A controlled test reproduced that same app fault with an empty or incomplete browser socket before the valid callback. This does not establish the precise cause on the Redmi.

## Repair

An empty, reset or timed-out client connection no longer closes the callback listener. Each connection and the full attempt have bounded deadlines measured with monotonic time. A validated callback remains usable if writing its browser response fails. Invalid Host, path, method, state and duplicate decoded query keys still fail validation.

The browser page reports pending verification or fixed failure guidance. The app checks PKCE and signed identity before it saves protected credentials or reports a connection. Cancellation still rejects a late grant. Fixed diagnostic categories identify the failed stage without raw errors, URLs, tokens or account details.

## Measured local scope

On 11 October 2026, the six phone-fix files passed independent source review, rendered UI audit and controlled QA. The native debug build used base `25621ad2a1ce9115a5d1aa67987304663bd3753b`; its SHA-256 was `b54a5e1dd7f7b263d6bdc3ea6d17e5201c8c1b2ded3bbae2cec32bcf50e8ef82`. Full JVM results: 192 passed, zero failures/errors/skips. Android lint: zero errors and nine existing warnings.

Android 15 / API 35, ARM64, Chrome 124.0.6367.219, 720 × 1600 at 280 dpi: 11 regular native cases passed. Five used actual MainActivity, default ReaderApp and the retained ReaderViewModel: visible Chrome return while token exchange was held; activity recreation in the background; rotation; cancellation; and a fresh attempt after verified process loss. They checked retained ViewModel/job identity, disconnected state while held, verified identity and encrypted Android Keystore persistence after release. The deliberate process kill is setup evidence, not a passing test.

Three native error cases covered declined consent, invalid state and token-network failure. Each kept the app disconnected with a reconnect action. Three separate core cases covered a signed grant and protected storage, interrupted sockets before a valid Chrome callback, and denial with no token exchange or saved grant. Core cases use a standalone provider and supplement the actual app lifecycle checks.

The app/provider used synthetic signed identities and controlled local HTTP responses. Native lifecycle fixtures intentionally omitted plan-use scope; their connected result correctly showed “Plan use was not granted.” They made no real OAuth or Responses requests. Chrome first-run setup used test-only `--disable-fre` after an unrelated first-run stall; its effective flag was checked, then the task flag file was removed, GMS was restored and adb returned to shell UID. A post-removal effective DOM check did not complete; file removal and UID restoration did.

The UI audit rendered the actual callback HTML at 320, 375, 768 and 1440 CSS pixels and native error screens on API 37. The Android 15 QA later exercised those error paths with actual Chrome and the app. Evidence, fixture sources, run logs, hashes and failed-attempt records are kept outside Git in the task's durable QA package. Published release bytes and signed APK installation need separate exact-commit checks.

## Acceptance limits

The reviewed phone files were integrated with main `1f0782abf404a3f3efb0410619eee995faa6ac29` without changes to those six files. That main update adds whole-page swipes and one PlayerScreen argument in MainActivity; it does not change the authentication lifecycle code. The integrated JVM suite passed 192 tests with zero failures/errors/skips. Integrated lint had zero errors and 15 warnings. Debug APK, optimized unsigned release APK and unsigned AAB builds passed. Final review, affected QA and signed public release checks are separate gates from the earlier native result.

Real OpenAI native sign-in and the physical Redmi remain unverified. A synthetic grant cannot prove account selection, consent, plan access or provider behavior. Earlier real question-generation evidence uses a separate signed-in emulator and build. It cannot establish native phone sign-in for this repair.

For phone acceptance, update using the signed GitHub release APK, open Settings, choose Continue with ChatGPT in Chrome, select the intended account and return to the app. Check that the app shows a connection or a specific safe error. Check eligible plan access before generating questions. Keep credentials out of reports and screenshots.
