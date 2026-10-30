# Android UI audit

## Supertonic 3 HD neural text-to-speech UI audit

30 October 2026. Independent rendered UI audit **PASSED** for issue #39 on `emulator-5554` (API 35, 16 KB page-size kernel).
- Audited states: Not installed (~129 MB size indication), Downloading (determinate progress bar with downloaded/total MB), Failed (error alert in `Palette.ErrorInk` with Retry), Installed HD voice (HD toggle, 10-voice chips `F1`–`F5` and `M1`–`M5`, Preview voice, Delete HD voices dialog), and System voice fallback.
- Viewports tested: 411dp standard portrait, 840x411dp landscape, 320dp narrow portrait, and 200% font scale accessibility.
- All touch targets satisfy >= 48dp x 48dp minimum requirements. Filter chip minimum bounds enforced via `.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)`.
- Responsive chip layout uses `FlowRow` to wrap without clipping or horizontal overflow on 320dp and 200% font scale.
- 9/9 UI device tests passed in `SettingsUiDeviceTest`.

## Web and social reading platforms update

30 October 2026. Independent rendered audit **PASSED**. Reviewed base `722ab2f` plus web extraction and UI changes. Tested configurations: phone 411dp (`Pixel_8`), narrow 320dp, font scale 1.0 and 2.0 (200% text scaling).

Surfaces audited:
- `NewBookScreen`: "Import web link" choice card, URL input dialog, validation states (empty, invalid protocol), and polite live region loading indicator.
- `BookCard`: Web domain badge (`Palette.TealSoft` container with `Palette.Deep` icon and label, contrast ratio > 7:1).
- `OverviewScreen`: 3-tier dynamic action layout (`threeFit`, `twoFit`, full-width vertical stack). All action labels ("Full text", "Source", "Open web page") remain on single lines at 320dp / 200% text scale.
- `SourceDialog`: "Open original web page" external link action via Android `Intent.ACTION_VIEW`.

All touch targets satisfy the >= 48dp minimum requirement. Defect with `OverviewActions` text wrapping at 200% font scale was resolved and verified via `OverviewActionsDeviceTest`. Durable evidence preserved in `docs/evidence/web-reader/`.

## Open Design update

12 October 2026. Independent rendered audit **PASSED** for issue #8 and children #9–#25 on reviewed base `538ef6783b701752d5389f0c1feae5c8890e36ec` plus final design changes. Exact design export: `8f70e4aac64a7cd39e7f297bddb48180b5802750`. Actual native Library, Overview, Player, Quick check and Results screens were captured and viewed at phone 411dp, narrow 320dp, tablet 800dp, landscape and 200% text, including long content and partial practice states.

The lower-right player rail, sentence/footer group, clear video and compact cards match the design. Results keep whole summary words by stacking groups when needed. Overview keeps full Full text/Source labels by stacking actions when needed. Its concept rows form one joined card with rounded outer corners and single dividers while remaining lazy. All required controls, states and long content are reachable. Initial D1/D2 failed captures remain separate; fresh corrected captures passed. No required visual defect remains.

Desktop native layout is unsupported; tablet/landscape provide wide evidence. Physical feel, spoken TalkBack and acoustic output remain unverified. [Behavior and checks](OPEN_DESIGN.md), [QA results](QA_RESULTS.md).

## Previous full-page concept swipe UI audit

11 October 2026. Independent rendered audit **PASSED**. The entire concept page moves: background/video, header, artwork, sentence, actions, count, progress and playback footer. Adjacent pages meet without a blank strip. Each preview has its own concept content. Moving controls do not trigger actions. Android system bars remain outside the page.

Still/video layouts passed at phone 411 dp, narrow 320 dp, tablet 800 dp and 200% font. Actual sample landscape, source/transcript controls and long sentence scroll passed. Up/down, reverse and cancel states were checked. The local swipe-preview bytes match the audited debug APK. This audit predates current-main integration; its player files remain unchanged.

[Complete audit](../reports/full-page-swipe-preview-20261011/UI_AUDIT.md), [process/result evidence](../reports/full-page-swipe-preview-20261011/UI_EVIDENCE.md), [QA](QA_RESULTS.md), [release handoff](../reports/full-page-swipe-preview-20261011/release/HANDOFF.md).

Physical touch feel, measured frame rate, acoustic speech and spoken TalkBack remain unverified. No live provider request was made. Prior practice audit follows unchanged.

## Previous PDF practice audit

11 October 2026. Independent rendered audit **PASSED**. Actual Android APK checked at 320/411dp, 800dp tablet, landscape and 200% font. Default-off import, source-transfer notice, saved book before browser, cancellation, add later, practice/source/return, task statuses and connection-reset confirmation were inspected.

The practice header correction keeps Continue reading fully visible at small width/large font. Final storage checks match the actual saved byte total. Controls and source page/choice semantics passed the inspected scope. Real spoken TalkBack remains unverified.

[Complete audit and image matrix](../reports/pdf-questions-preview-20261011/UI_AUDIT.md), [workflow/result evidence](../reports/pdf-questions-preview-20261011/UI_EVIDENCE.md), [local QA](QA_RESULTS.md), [preview handoff](../reports/pdf-questions-preview-20261011/HANDOFF.md).

Ready questions and error/limit/recovery states used safe saved fixtures. No live generated result is claimed. Physical browser return, connected-plan notice, active live generation and live model quality remain open. At 320dp/200% the unchanged Details Source label wraps; the new practice source action remains clear. Add-later copy repeats one sentence. These are retained scope notes.

Earlier UI evidence stays under docs/evidence; the earlier report is preserved in [historical docs](../reports/pdf-questions-preview-20261011/previous-docs/docs/UI_AUDIT.md). Failed-header screenshots remain separately labeled history in the current evidence package.
