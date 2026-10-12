# Open Design changes

Issue [#8](https://github.com/chilliesdev/anchor-reader/issues/8) tracks 17 final changes. Native implementation uses the exact 11 October 2026 [design export](https://github.com/chilliesdev/anchor-reader/blob/8f70e4aac64a7cd39e7f297bddb48180b5802750/design/open-design/index.html). Pink marks and dashed outlines are annotations.

## Screens

| Issues | Behavior |
| --- | --- |
| #9–#11, #13 | Check-circle has accessible name Quick check. It is last below Source in a vertical lower-right rail. It stays visible and disabled when the current concept has no saved question. Short-height rails scroll. |
| #12, #14–#16 | Play has previous sentence/count/next beside it. Concept title is bottom left. Local text shadows and edge gradients keep video visible. Segments show current-concept sentence progress; selection resets to sentence one and saved reading resumes its sentence. |
| #17–#18 | Shared compact cards place uncropped artwork beside book details. Every card has a visible visit-progress track, including zero and complete states. Answers do not change reading visits. |
| #19–#20 | Overview has one header Back and retained Settings. Main reading action, Full text/Source and grouped lazy concept states remain. Source/text actions stack when full labels cannot fit at the user's text size. |
| #21–#23 | Checks use the concept title and a compact question/prompt group. Intermediate feedback has Next question; final feedback has Complete. Complete opens results for that check session and retains saved answer identity. Continue reading returns paused at the same sentence. |
| #24–#25 | Results omit the completion score note. Completion heading, tinted actual-count summary, bordered correct/wrong/skipped rows and Review/Library/Source actions form clear groups. Summary groups stack if full words cannot fit in three columns. |

Native PDF/source handling, offline narration, data, practice generation/status controls and account safeguards remain. Check completion does not mark unread concepts visited or finish the book.

## Local reproduction

Use a task-owned Android emulator; native tests clear its app data. Select its actual serial. No npm project exists.

```sh
./scripts/build.sh :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
ANDROID_SERIAL=emulator-5584 ./scripts/qa.sh device
python3 -m unittest discover -s scripts/tests -v
```

Inspect Library → Details → Continue → Quick check → answer/skip → Complete → Results → Review/Source/Library. Repeat with a retained digital PDF and saved practice fixtures. Check correct/wrong/skipped records and reading progress after a real process restart. Use phone 411dp, narrow 320dp, tablet 800dp, landscape, long labels/questions and 200% text. Check overview first/last borders and a 3,000-concept book. Fixtures do not prove live provider behavior.

12 October 2026 local result: independent rendered audit/review passed; build/lint and 194 JVM tests passed. All 82 native cases have passing evidence from initial 79 passes plus an affected three-case Chrome setup retry. Zero skips; no fabricated combined runner result. [QA detail](QA_RESULTS.md), [UI audit](UI_AUDIT.md), [review](REVIEW.md).

Actual local result/process screenshots and full logs remain outside Git. Physical phones, spoken TalkBack, acoustic output and new live-provider acceptance remain unverified. [Signed publication rules](RELEASES.md) apply after the exact commit's CI gates.
