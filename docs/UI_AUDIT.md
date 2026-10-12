# Android UI audit

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
