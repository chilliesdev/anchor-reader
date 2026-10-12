# Open Design reference

This is the exported AnchorReader design. It is a browser prototype. The Android
app is in `app/`; these files do not change it.

Exported on **12 October 2026** from Open Design:

- Project: `b29fb5d5-b7f7-4b6d-81f5-8fd52e96905e`
- Conversation: `2f102e1d-2a6c-44d2-a238-6ff287a27845`
- Design file: `index.html`
- Entry: [index.html](index.html)

The HTML is the original standalone export, unchanged. It embeds the CSS,
application scripts, sample data and PDF.js module. The ocean media comes from
the project ZIP. PDF.js worker, CMaps, fonts and licenses come from the same
Open Design project. Screenshot marks, chat and export metadata are excluded.

## Preview

From the repository root:

```sh
python3 -m http.server 8765 --bind 127.0.0.1 --directory design/open-design
```

Open [the local preview](http://127.0.0.1:8765/index.html). Use HTTP; opening the
file directly can block PDF imports. Keep the `assets/` and `vendor/` folders
beside the HTML. No build or package install is needed.

## Screen references

The previous design batch is closed: [#8](https://github.com/chilliesdev/anchor-reader/issues/8).
Its issues link the unchanged [11 October export](https://github.com/chilliesdev/anchor-reader/blob/8f70e4aac64a7cd39e7f297bddb48180b5802750/design/open-design/index.html).
The earlier screen changes remain in this export:

| Screen | Route | Child issues | From a fresh Library |
| --- | --- | --- | --- |
| Player | `#player` | #9–#16 | Select Start. |
| Library | `#library` | #17–#18 | Initial screen; select Library to return. |
| Book overview | `#overview` | #19–#20 | Select the book's Details icon. |
| Quick check | `#check` | #21–#23 | Start reading, move to concept 2, select the check-circle icon. |
| Results | `#finish` | #24–#25 | Answer or skip each Quick check, then select Complete. |

New uses `#new`. Settings uses `#settings`. The hash opens a screen; use the
controls above to create its reading and answer state. The check icon is disabled
on concept 1 because that concept has no question. The player bar tracks
sentences within the current concept. The Library bar tracks visited concepts.

## 12 October updates

- Concepts without a diagram show text only. Imported concepts no longer show
  a speaker placeholder.
- Player sentences fit the available area without inner scrolling. Very long
  sentences use Back and Next parts. Parts keep the same sentence count,
  progress and narration.
- The overview shows Practice questions before Concepts for the sample and
  imported PDF books. The card shows ready questions, answered questions and
  concept coverage. Its action changes from Start practice to Continue practice
  or Practice again.
- Practice opened from the overview uses Back to book. Complete returns to the
  overview. Practice alone does not mark the lesson complete.
- The card includes empty and generating designs. Generation in this HTML is a
  preview; it does not send a service request.

Preview the card states:

| State | URL | Expected content |
| --- | --- | --- |
| Ready | `index.html#overview` | Saved questions and answer counts; Start practice. |
| Empty | Import a PDF, then open its overview. | Not ready; No questions yet. |
| Generating | `index.html?practice=generating#overview` | Generating; Making questions; reading remains available. |

For text-only sentences, select New, enter a title and text, import the book,
then Start. Use a long single sentence to see sentence parts when fitting needs
them. The sample still has its original diagrams.

## Prototype limits and credits

- Browser speech depends on the browser and installed voices. Offline speech is
  not guaranteed. Still mode stops the background video.
- ChatGPT sign-in and generated questions are Android flows. This prototype
  explains them; it does not connect or send source text to OpenAI.
- Text/PDF imports and progress use browser storage. Clearing that site's
  storage removes them. Digital PDF extraction is local; there is no OCR.
- The ocean video uses public-domain GOES-16 imagery by CSU/CIRA and NOAA.
  The original Settings credit link remains in the HTML.
- PDF.js is version **4.10.38**, build `f9bea397f`. See its
  [Apache 2.0 license](vendor/pdfjs/LICENSE),
  [CMap license](vendor/pdfjs/cmaps/LICENSE),
  [Foxit font license](vendor/pdfjs/standard_fonts/LICENSE_FOXIT) and
  [Liberation font license](vendor/pdfjs/standard_fonts/LICENSE_LIBERATION).
- Debug source maps were absent from the source project. They are not required
  for the preview.
