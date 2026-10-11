# AnchorReader document, AI, and narration architecture

Research date: 10 October 2026. Scope: one builder, three days, English hackathon prototype. No application code, model installation, paid API call, or document upload was performed. Product and engineering choices below are proposed decisions, not measured results.

## 1. What must the three-day prototype prove?

### Takeaway

Use one prepared, reviewed English lesson from an owned or clearly licensed digital document. Package three to five natural concept units, one or two original figure crops, recall checks, captions, and the builder's own recorded narration. Make offline replay the reliable path. Label the lesson as prepared and the narration as human recorded. Automatic arbitrary-PDF conversion is a later feature unless hackathon rules make live generation mandatory.

### Cited Findings

- ChatGPT subscriptions and API usage have separate billing systems. A Plus subscription does not supply a runtime API budget. Do not use consumer account sessions as an application backend. [OpenAI billing](https://help.openai.com/en/articles/9039756-managing-billing-for-chatgpt-and-the-api-platform)
- PDF text extraction is not equivalent to understanding a document. Scanned pages need OCR; even digital pages can lack a semantic reading order. pypdf documents these limits and warns that complex content streams can use substantial memory. [pypdf extraction](https://pypdf.readthedocs.io/en/stable/user/extract-text.html)
- A local-model contingency exists. Ollama requires macOS 14 or newer; Apple M-series machines support CPU and GPU inference. Its listed Qwen3 4B quantized model is about 2.5 GB. This is the model file size, not total working memory. [Ollama macOS](https://docs.ollama.com/macos), [Qwen3 4B package](https://ollama.com/library/qwen3:4b)
- Ollama binds to the loopback address by default. Its documentation offers local-only mode and states that local prompts are not sent to Ollama. Context length and concurrent requests increase memory requirements. [Ollama FAQ](https://docs.ollama.com/faq)
- WebLLM uses WebGPU for inference in a browser. This adds a browser, graphics, memory, and model-download dependency to the demo. [WebLLM documentation](https://webllm.mlc.ai/docs/)

### Inferences

**Fixed demo scope.** One source is sufficient to prove the learning loop. Use a second only after the first works offline on the actual demonstration phone. The source should contain clear headings, ordinary prose, and an understandable figure. Avoid research papers where equations are essential to correctness. A source can be dense without being impossible to check within three days.

**Preparation workflow.** Read the document; mark concept boundaries; draft scripts and quizzes with the existing Codex or ChatGPT interface if helpful; compare every claim with the source; record narration; record timestamps at sentence boundaries; package the assets. The human reviewer remains responsible for the prepared content. Do not claim that this manual preparation proves automatic conversion accuracy.

**Truthful demo states.** Show “Prepared example,” “Source,” “Download lesson,” “Available offline,” and the true narration mode. A file selector that only chooses a prepared fixture must say so. It must not simulate a successful live conversion of an arbitrary file. If live conversion fails, offer the prepared example by name rather than substituting it silently.

**Optional live AI without paid API credit.** Only if the rules require it: test a local Qwen3 4B model through Ollama on the existing Mac. Pin the downloaded model digest. Bound the task to one short, selected source passage and a small structured response. This proves local assisted authoring, not a production document service. Allow a strict setup and benchmark time box; abandon it if it compromises the offline lesson. The phone would need a controlled local network connection to the Mac for live generation. Its loopback address does not refer to the Mac. Keep raw Ollama off the public Internet; use a narrow authenticated gateway if phone access is necessary. Disable cloud fallback. Downloading a multi-gigabyte model is contrary to the eventual low-data phone goal. For that reason, reject in-browser LLM inference from the three-day critical path.

**No new paid dependency.** The reliable route needs neither an API key nor a model download. Use owned human audio; do not assume that every installed macOS voice permits redistribution of generated speech. An OS or browser voice can be an optional test, but successful network-free playback must be demonstrated first. Do not describe human audio as AI narration.

### Gaps

The user confirmed that an Android app is not required; a PWA is acceptable. No explicit requirement for live AI was supplied. Check the organizer's rules at the start of day one; do not assume that prepared examples satisfy every rubric. No selected source, phone, microphone quality, available Mac memory, or local-model latency was tested.

## 2. Which ingestion and extraction tools should the full product use?

### Takeaway

For the post-demo product, use Docling behind an internal document adapter. Preserve its structured output and source geometry. Start with English born-digital PDFs; add scanned pages only after a separate evaluation. Select Azure Document Intelligence layout as the explicit cloud fallback for difficult pages, subject to budget and user disclosure. Do not chain several fallback vendors automatically.

### Cited Findings

| Tool | Verified capability or limit | Decision |
|---|---|---|
| Docling | MIT code license. The repository release checked with `gh` was v2.137.0, published 9 October 2026. Its document model carries text, tables, pictures, hierarchy, reading order, bounding boxes when available, and provenance. | Post-demo default. Keep JSON as canonical extraction; Markdown only as a display/export format. [Repository](https://github.com/docling-project/docling), [release](https://github.com/docling-project/docling/releases/tag/v2.137.0), [document model](https://docling-project.github.io/docling/concepts/docling_document/) |
| Docling models | Layout Heron is Apache-2.0. The older combined model repository lists Apache-2.0 and CDLA-Permissive-2.0. The catalog separates layout, OCR, tables, and optional vision-language enrichment. CPU support varies by stage. | Freeze each model revision and its license; a wrapper's MIT license does not cover every weight. [Heron card](https://huggingface.co/docling-project/docling-layout-heron), [model bundle](https://huggingface.co/docling-project/docling-models), [catalog](https://docling-project.github.io/docling/usage/model_catalog/) |
| pypdf | Simple digital-text extraction and PDF utilities; no OCR. The repository license is a permissive three-condition BSD-style license; GitHub's automatic detector returned NOASSERTION, so the license text was read directly. | Small utility or diagnostic path, not the learning parser. [Extraction](https://pypdf.readthedocs.io/en/stable/user/extract-text.html), [license](https://github.com/py-pdf/pypdf/blob/main/LICENSE) |
| PyMuPDF | Fast rendering and geometry tools; AGPL or commercial licensing. | Do not add casually to a proprietary product. Use only after satisfying AGPL requirements or buying the relevant license. [Official license](https://pymupdf.readthedocs.io/en/latest/about.html#license-and-copyright) |
| Unstructured | Apache-2.0 code. PDF strategies include fast, high-resolution, and OCR-only. Its docs warn that high-resolution processing can misorder multi-column content; image extraction requires that strategy. | Useful alternative for broad file types. No reason to operate both it and Docling initially. [Repository](https://github.com/Unstructured-IO/unstructured), [partitioning](https://docs.unstructured.io/open-source/core-functionality/partitioning) |
| Marker | Current README says Apache-2.0 code, modified AI Pubs OpenRAIL-M model weights; free use includes research, personal use, and startups below the stated $5 million funding/revenue threshold. Other commercial use requires a license. CPU and GPU modes now have distinct paths. | Evaluation candidate only. Older claims that the current code is simply GPL are stale. Review the exact weights and threshold before use. [Current README, read through gh](https://github.com/datalab-to/marker) |
| Azure Document Intelligence | v4.0 GA layout returns paragraphs, tables, section structure, polygons, and figures. Figure crops are available; table/figure core bounds exclude captions and footnotes. | Selected difficult-page cloud fallback. Explicitly attach the separate caption. [Layout API](https://learn.microsoft.com/en-us/azure/ai-services/document-intelligence/prebuilt/layout?view=doc-intel-4.0.0) |
| Google Document AI | Separate OCR and layout processors; layout includes initial chunking and has its own charge. | Cloud alternative, not an extra default dependency. Its retrieval chunks are not automatically learning units. [Pricing and processors](https://cloud.google.com/products/document-ai/pricing) |
| AWS Textract | Text detection and forms/tables analysis have different prices. The official Oregon example prices text at $0.0015/page and table analysis at $0.015/page in the first volume tier. | Alternative where the organization already operates AWS. Do not quote text-only price for full structural extraction. [Textract pricing](https://aws.amazon.com/textract/pricing/) |

### Inferences

**Ingestion contract.** Post-demo initial limits: PDF only, 25 MB, 100 pages, one active conversion per learner, explicit language selection or a detected-language confirmation. These are proposed resource limits, not vendor limits. Reject password-protected and damaged files with a useful message. Do not attempt to bypass a document restriction. Record an immutable source hash, title, author if supplied, media type, file size, page count, owner, rights statement, and ingestion time.

**Per-page classification.** Distinguish usable digital text, scanned image, and questionable OCR text. A PDF can contain all three. Do not OCR every digital page; rendering text into an image discards useful font and character information. Detect empty extraction, unusual character ratios, displaced reading order, and mismatches between visible page content and the extracted text. These indicators route pages for review; they do not establish truth.

**Parser settings.** Begin with Docling's standard pipeline, Heron layout, native text extraction, and table extraction only when needed. For scans, evaluate an explicit Tesseract or RapidOCR configuration on the corpus and pin its language packs and weights. Do not inherit an evolving “auto” selection silently. Disable optional caption-generation and formula-interpretation models until evaluated. Pre-fetch all approved model artifacts during the image build; conversion workers should not download arbitrary new models during a job.

**Resource policy.** A four-vCPU, 8–16 GB RAM worker, one conversion at a time, is an initial experiment budget, not a proven requirement. Record peak memory, cold startup, seconds per page, and output quality separately for digital, scan, and table-heavy inputs. Set time, memory, page-image size, and temporary-disk limits. The package supports CPU operation; that does not establish acceptable latency on a small server. Add a GPU only when measured throughput or vision processing warrants it. Never promise low latency using a vendor benchmark from different documents and hardware.

**Canonical blocks.** Keep page index and printed page label separately. Each block needs a stable ID, source text, normalized text, type, heading ancestry, reading-order position, page, coordinate system, bounding box or polygon, extraction method, warnings, and any confidence the extractor supplies. Preserve links between figures, captions, footnotes, table cells, equations, and surrounding paragraphs. Keep the original page or crop available for inspection.

**Figures.** Render or crop the original page region. This preserves vector diagrams, labels, and multi-object figures that are not single embedded images. Retain the figure number and caption. An AI description is a separate, marked interpretation; it never replaces the original diagram. If labels are unreadable, offer a full-page view or zoom and mark the unit for review. Do not invent a chart or redraw numeric data to make it look cleaner.

**Tables and equations.** Keep a table's headers, cell grid, units, caption, and source crop. Spoken summaries must name the row, column, measure, and qualifier being described. Avoid silently linearizing a table into a meaningless sentence. For equations, retain the exact visual source and any extracted notation; require review before verbal interpretation. A unit that relies on an uncertain equation cannot be automatically published. For a complex page, prefer a source view and explicit limitation over a fabricated explanation.

**Article URLs.** Defer arbitrary URL ingestion. Later, begin with approved public domains, ordinary HTML, and explicit user rights confirmation. Capture title, byline, canonical URL, retrieval time, and content hash. Do not fetch paywalls, account-only pages, or login cookies. Keep import private by default; public availability does not establish redistribution rights. Source licenses must permit the intended excerpts, adaptations, figure use, and sharing. This is a product rule; legal review remains necessary for a public content catalog.

### Gaps

No corpus benchmark establishes a parser winner for AnchorReader. No worker sizing, OCR model-language combination, or commercial license review was performed. Docling v2.137.0 is the candidate version observed, not a recommendation to upgrade an existing deployment without tests. Google and Azure prices must be rechecked in the account region before commitment.

## 3. How should source-grounded concept units, narration, and quizzes work?

### Takeaway

Use structure first, model judgment second. A learning unit must teach one stated objective and close at a natural boundary. Keep a verifiable chain from every spoken factual claim and correct quiz answer to source blocks. Token limits protect resources; they must not define the learner's concept boundaries.

### Cited Findings

- Docling's representation retains document hierarchy and ordered children, which provides a better starting point than a plain text dump. [Docling document model](https://docling-project.github.io/docling/concepts/docling_document/)
- OpenAI Structured Outputs can constrain a response to a supported JSON Schema. A matching schema still needs content checks and handling for refusals or incomplete outputs. [Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs)
- GPT-4.1 Mini has a documented dated snapshot, `gpt-4.1-mini-2025-04-14`, and text output support. Current listed standard token prices are $0.40 per million input tokens and $1.60 per million output tokens. Its page was not marked deprecated when checked. [Model page](https://developers.openai.com/api/docs/models/gpt-4.1-mini)
- OWASP treats externally supplied documents as a prompt-injection surface. It recommends separation of instructions and content, output checks, limited privileges, and defense in depth. [Prompt injection guidance](https://cheatsheetseries.owasp.org/cheatsheets/LLM_Prompt_Injection_Prevention_Cheat_Sheet.html)

### Inferences

**Pipeline.** Source → page/block extraction → quality assessment → section outline → concept units and prerequisite links → narration script → claim/source validation → quiz and answer validation → optional translation → audio and time cues → immutable downloadable package. The prototype prepares this structure manually. The later automated service produces the same package contract.

**Boundary method.** Start from headings, paragraphs, lists, examples, and figure references. Keep a definition with its essential condition; keep an example with the explanation it demonstrates; keep a figure with the passage that introduces it. Ask the generation model to propose a title, one learning objective, supporting block IDs, prerequisite concepts, and why the unit ends there. Do not ask it to divide a document into equal-length clips. A 45–120 second target is an authoring preference for testing, not a forced duration. A three-minute explanation can remain intact when splitting would break the idea. Use a soft review flag above about three minutes. A separate hard source-token cap can require hierarchical processing or a human decision; never truncate in mid-claim.

**Coverage accounting.** Every substantive source block must be assigned to a taught unit, a reference-only section, or an explicit omission with a reason. Introductions, methods, limitations, and uncertainty must not disappear merely because they are less engaging. Show “selected concepts” when the lesson is selective. Do not say the learner has mastered the whole document after a short summary.

**Concept graph.** Store a simple list of units and prerequisite unit IDs. Preserve source order by default. A graph database is unnecessary. Check for missing IDs and cycles. A prerequisite can point to an earlier unit or a brief glossary entry. Cross-document knowledge graphs, embeddings, search indexes, agents, and retrieval frameworks are not needed for initial conversion of one known document.

**Narration policy.** Scripts use plain English, short sentences, expanded acronyms, and spoken forms for units. Preserve numbers, negations, uncertainty, causal qualifications, and scope. Distinguish “the author reports” from an established general claim. No unrequested outside knowledge. Use a neutral transition such as “Next, the report explains...” only when that accurately connects the units. New teaching examples are allowed later only when clearly marked and reviewed; the first release should use source examples.

**Claim validation.** Each claim stores one or more block IDs plus a short supporting span. Check that IDs exist and that the claimed support belongs to this source revision. Compare numbers, names, dates, symbols, units, negations, and hedging. A second model can flag contradictions, but model agreement is not evidence of correctness. A human reviews all demo scripts and an initial pilot sample. Critical contradictions block publication. The system must be able to say that a section cannot be converted reliably.

**Quiz policy.** Use one recall item after a concept or a small related set. Each item has a learning objective, question, answer, distractors where relevant, explanation, and supporting source span. Prefer application or retrieval over wording tricks. The correct answer must be entailed by the source; the wrong answers must be demonstrably wrong in that context. Avoid “all of the above,” ambiguous negatives, and questions answerable only from information omitted by the script. Shuffle answer order while retaining stable option IDs. A wrong answer offers explanation and replay. It must not lock the learner in an endless failure loop. Passing one item is evidence of that response, not guaranteed retention.

**Conceptual records, not code.** A DocumentRevision holds ownership and source metadata. SourceBlock holds extraction and geometry. LearningUnit holds objective, ordered blocks, prerequisites, script sentences, and coverage. ClaimEvidence maps a sentence or answer to source spans. FigureAsset holds original crop and caption. QuizItem holds question, options, scoring, and explanation. AudioAsset holds narrator mode, voice/model version where applicable, format, duration, checksum, and alignment. LessonManifest holds ordered units, all required asset sizes/checksums, schema version, language, generator version, validation report, and total download size. PlaybackProgress points to immutable package and unit IDs.

**LLM choice.** No runtime LLM for the three-day baseline. For the paid next tier, use the dated GPT-4.1 Mini snapshot as a reproducible first benchmark, accessed through a small provider adapter and schema validation. This is a cost and controllability choice, not a claim that it is today's strongest model. Before implementation, recheck deprecation status, account access, region, and actual schema support with a small authorized request. No authenticated availability test was performed here. Pin the exact model, prompt template, schema, parser, and validation version. Evaluate a stronger model for exceptions only if it measurably improves the corpus. Keep provider requests limited to relevant source blocks. Do not give the model browser, shell, email, storage credentials, or unrestricted tool access.

**Vendor exit.** Store provider-independent manifests, scripts, source references, questions, and audio. The adapter maps one generation request to a structured result. Replacing the provider means rerunning the held-out corpus, not redesigning playback. Existing approved packages continue working when a vendor is unavailable. Qwen3 4B is an Apache-2.0 local evaluation candidate, but small-model accuracy must pass the same gates. Do not weaken validation to fit a cheaper model. [Qwen model card](https://huggingface.co/Qwen/Qwen3-4B)

### Gaps

All proposed durations, resource caps, and validation gates require testing. No evidence establishes that one concept length fits all subjects or learners. No automatic verifier proves the correctness of every generated claim. General live import remains unproven until the corpus and human checks are complete.

## 4. Which narration and language strategy is credible?

### Takeaway

Record the demo narration and cache it with the lesson. For the later cloud product, benchmark Azure real-time TTS first for English Nigeria and French, using sentence or word boundary events. Add each African language only after native-speaker tests, terminology review, and license checks. Speech recognition coverage does not establish speech synthesis support.

### Cited Findings

- Azure's actual TTS voice table lists `en-NG-EzinneNeural` and `en-NG-AbeoNeural`, French voices, and Kiswahili voices for Kenya and Tanzania. These are service capabilities, not proof of local educational quality. [Azure voice table](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/language-support?tabs=tts)
- Azure's SDK exposes speech word-boundary events with audio offsets and text offsets; sentence boundaries can be requested. [Synthesis events](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/how-to-speech-synthesis)
- Google's current prices list Standard and WaveNet at $4 per million characters, Neural2 at $16, and Chirp 3 HD at $30 after their relevant free allowances. These are different product tiers. [Cloud TTS pricing](https://cloud.google.com/text-to-speech/pricing)
- OpenAI's speech guide lists English, French, and Swahili and says voices are optimized for English. However, the current `gpt-4o-mini-tts` model page marks the model deprecated. The guide and model lifecycle page are inconsistent. Do not select that deprecated model as a new default. [Speech guide](https://developers.openai.com/api/docs/guides/text-to-speech), [model lifecycle marker](https://developers.openai.com/api/docs/models/gpt-4o-mini-tts)
- ElevenLabs lists Hausa and Swahili within its v3 and v4 synthesis language sections. Its timing endpoint supplies character alignment; availability must be checked for the selected model and endpoint. This is distinct from Scribe speech recognition. [Models](https://elevenlabs.io/docs/overview/models), [timed speech endpoint](https://elevenlabs.io/docs/api-reference/text-to-speech/convert-with-timestamps)
- Meta's Yoruba MMS TTS checkpoint exists but carries CC-BY-NC-4.0. NLLB-200's 600M translation model also carries a noncommercial license. Neither is an automatic choice for a commercial service. [Yoruba MMS](https://huggingface.co/facebook/mms-tts-yor), [NLLB card](https://huggingface.co/facebook/nllb-200-distilled-600M)

### Inferences

**Language sequence.** English first. French next after the English learning loop works. After that, choose one language from learner demand and a funded native-speaker review process. For an East African expansion, Swahili has documented cloud TTS options. For Nigeria, Hausa has a documented ElevenLabs synthesis candidate; Yoruba and Igbo need further vendor and native-speaker evaluation. Do not launch three languages because a vendor advertises a large total count.

**Translation contract.** Translate the approved script and question intent, not a newly generated lesson. Preserve original source links, numbers, equations, answer IDs, and uncertainty. Maintain a versioned terminology glossary with the preferred translation, original technical term, spoken form, and reviewer decision. Display the source-language term when a local equivalent is unstable. Back-translation can detect some errors, but bilingual review decides acceptance. Test code-switching, proper names, tone/diacritics, technical vocabulary, and answer ambiguity separately.

**Audio design.** Generate one audio file per unit, with sentence timestamps. For a human recording, mark a few sentence and figure cues manually. For cloud synthesis, retain provider timing output and normalize it against the exact playable encoded file. Avoid per-word visual highlighting until tested; sentence highlighting is sufficient for the demo. If the provider lacks stable timestamps, produce sentence-sized clips or use reviewed alignment. A speech-recognition transcript alone does not guarantee the generated audio says the intended script.

**Clock design.** Playback media time is the authority. Figure starts, captions, highlights, and checkpoint triggers all derive from current audio position. Do not maintain a separate wall-clock timer. On seek, recompute the active cue. On 1.5× playback, media timestamps stay in source-audio seconds; wall-clock duration changes. On pause, cues pause. A figure remains visible long enough to inspect and can pause narration. The source diagram takes focus; decorative motion dims or stops. A quiz starts at the unit boundary after playback stops, never over unfinished narration.

**Offline claim.** A downloaded lesson contains its actual audio bytes, figure bytes, caption data, and quiz data. Local decorative loops alone do not make narration offline. Initial installation and download still consume data. “No mobile data for downloaded lessons” is supportable after a flight-mode test; “zero data app” is misleading. Store alternate language/audio tracks only when chosen. At 32 kbit/s, ten minutes of audio is approximately 2.4 MB before container overhead; at 48 kbit/s, about 3.6 MB. These are arithmetic estimates, not measured final package sizes.

**Choice of provider.** Azure's Nigeria voices and timing events make it the first cloud quality benchmark. Google is the clear price comparison and exit option. ElevenLabs is conditional where language or voice quality wins; do not add it merely for expressive narration. A calm voice, precise pronunciation, and replay control matter more than dramatization. Use stock voices; custom cloning is outside scope.

### Gaps

No voice was listened to or tested. Azure's rendered price page did not expose a reliable region-specific numeric quote, so a dollar price is intentionally not asserted. Yoruba and Igbo support in the assessed mainstream TTS tables was not established. This is not a claim that no capable vendor exists. Export and redistribution rights for macOS system voices were not verified; use owned recording for the demo.

## 5. How should privacy, hostile inputs, cost, and job reliability work?

### Takeaway

Treat every uploaded file as hostile data. Process it in a bounded, isolated worker. Keep explicit provider records and honest retention terms. Build conversion as resumable, idempotent stages; replay approved local packages without calling AI services again.

### Cited Findings

- OWASP recommends allowlisted file types, signature checks, randomized storage names, size limits, restricted storage, and appropriate scanning. A file extension alone is insufficient. [Upload guidance](https://cheatsheetseries.owasp.org/cheatsheets/File_Upload_Cheat_Sheet.html)
- URL fetchers need network-layer and application-layer protection. DNS rebinding, redirects, private address ranges, and parser disagreement can bypass superficial URL validation. [SSRF guidance](https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html)
- OpenAI API data is not used for model training by default unless the customer opts in. Normal abuse-monitoring retention is up to 30 days. Responses also have default application-state retention; explicitly disabling storage is not equivalent to approved Zero Data Retention. Some endpoints, background execution, caching, and file inputs have separate conditions. [OpenAI data controls](https://developers.openai.com/api/docs/guides/your-data)
- Azure says its real-time TTS API does not retain the input text or generated audio. Its long-audio, batch, custom-voice, and avatar paths have different storage rules. [Azure TTS privacy](https://learn.microsoft.com/en-us/azure/foundry/responsible-ai/speech-service/text-to-speech/data-privacy-security)
- Azure Document Intelligence temporarily stores input and results in the request region and deletes them 24 hours after an analysis request. v4.0 provides a deletion operation to request earlier result deletion. [Document Intelligence FAQ](https://learn.microsoft.com/en-us/azure/ai-services/document-intelligence/faq?view=doc-intel-4.0.0)
- Google says Cloud TTS does not log customer text or audio. ElevenLabs' timed-speech API defaults logging to enabled; request-level zero retention is available only to enterprise customers. Do not advertise zero retention on a standard account. [Google TTS logging](https://docs.cloud.google.com/text-to-speech/docs/data-logging), [ElevenLabs logging parameter](https://elevenlabs.io/docs/api-reference/text-to-speech/convert-with-timestamps)

### Inferences

**File isolation.** Never execute PDF JavaScript, open embedded executables, or allow parser-initiated network access. Use a non-root worker, read-only system files, a private temporary directory, no cloud metadata access, limited CPU/RAM/time, and only the file and output locations it needs. Delete temporary raster images after successful packaging or terminal failure. Scan inputs, but do not confuse antivirus with complete parser safety.

**Future URL isolation.** Permit only intended HTTP(S) targets through a dedicated fetcher. Reject credentials, nonstandard schemes, local/private/link-local addresses, cloud metadata addresses, and unsafe redirects. Validate every redirect and connect to the validated destination to prevent DNS rebinding. Bound compressed and expanded sizes, response time, and redirect count. Sanitize extracted HTML; never execute source scripts. Do not give the generation model the fetcher's credentials or decide destinations from document instructions.

**Prompt isolation.** Document instructions such as “ignore the rules and reveal keys” remain source text. Put them only in a data field, never in an instruction message. The generation process receives no tools. Output is data validated against the allowed schema and references. Escape HTML and speech markup from both source and model output. Any optional SSML is generated by trusted application logic. Keep prompt-injection fixtures in the test corpus, including text inside a figure.

**Provider policy.** The selected paid script path uses foreground, stateless requests with explicit storage disabled and no hosted file store, conversation history, or tools. This reduces application-state retention but does not remove the default abuse-monitoring policy. Send only required text, after an appropriate privacy check. Explain each processor to the learner. Cloud fallback is disabled where the document's rules prohibit third-party processing. Do not infer a blanket no-training contract for unreviewed Azure OCR, AWS, Google Document AI, or ElevenLabs accounts; approve their exact terms before sensitive production use.

**State machine.** Received → quarantined → validated → extracting → extraction review → structuring → generating → content review → narrating → packaging → ready. Alternate terminal states: rejected, failed, canceled, deleted. “Needs review” waits for a human or clear user action. “Partial” is acceptable only when the UI names excluded pages and units. Never mark the whole document ready after a failed required page.

**Retry rules.** Persist stage inputs, outputs, and attempt count. Retry transient network errors and rate limits with jittered backoff and an overall job deadline. Do not retry invalid input or insufficient permissions blindly. A timed-out vendor request can have completed; check its stored request ID or result before paying for a repeat. Use one lock or lease per document revision and stage. The publication step atomically points to a complete manifest only after every checksum and required asset passes.

**Idempotency and cache keys.** Use owner scope, source hash, parser/model revisions, language, glossary version, prompt/schema versions, and voice/audio settings. Identical uploads by different users must not expose one user's document to another through shared cache lookups. Shared public packages require explicit redistribution rights. A changed script invalidates its audio and cues. A changed quiz does not require new narration unless it is spoken. An app restart or repeated download must not trigger fresh LLM or TTS charges.

**Costs, USD planning arithmetic.** No paid spend is needed for the prepared demo, excluding existing equipment, connection, hosting arrangements, and labor. For an optional paid conversion assume 40,000 total input tokens and 8,000 output tokens across outlining, scripting, questions, and checks. GPT-4.1 Mini costs 0.04 × $0.40 + 0.008 × $1.60 = **$0.0288**. Assume ten minutes, 1,500 words, and 9,000 billable characters. Google Standard/WaveNet would cost **$0.036**; Neural2 **$0.144**, ignoring free allowances. Script plus those voice tiers is about **$0.0648–$0.1728**, before OCR, storage, egress, retries, taxes, and human review. These assumptions are examples, not quotes or observed averages. [LLM rates](https://developers.openai.com/api/docs/models/gpt-4.1-mini), [TTS rates](https://cloud.google.com/text-to-speech/pricing)

For a 20-page text-only Textract example, 20 × $0.0015 = **$0.03**. Table analysis would have a different bill: 20 × $0.015 = **$0.30** at the cited Oregon tier. Azure fallback has no asserted price here. Any proposed $5–$10 experimental budget requires separate authorization and a configured account; it is not included in Plus. Use a hard application ledger and per-job caps as well as provider alerts. Alert settings alone are not a guaranteed hard stop. [AWS rates](https://aws.amazon.com/textract/pricing/)

### Gaps

No paid account, regional quote, data-processing agreement, spending cap, job service, or deletion path was configured. The calculations omit fixed infrastructure and review labor. Duplicate-request behavior and idempotency support differ by provider and must be checked during integration. Provider deletion does not automatically remove AnchorReader's objects, logs, device caches, or backups.

## 6. What tests and gates make the architecture ready to ship?

### Takeaway

Separate the three-day demonstration gate from the later automatic-conversion gate. The demo needs complete human review of one lesson and repeatable offline playback. A production claim needs a held-out corpus, explicit error thresholds, native-language review, cost measurements, and regression checks on each model or parser change.

### Cited Findings

- Parser representations and model-specific licenses differ. Pinning the whole conversion stack is necessary to reproduce a result. [Docling model catalog](https://docling-project.github.io/docling/usage/model_catalog/), [Heron card](https://huggingface.co/docling-project/docling-layout-heron)
- A provider's dated model snapshot can stabilize a selected model version, but generated content still needs validation. [GPT-4.1 Mini snapshots](https://developers.openai.com/api/docs/models/gpt-4.1-mini), [Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs)

### Inferences

**Three-day gate.** Review 100% of script facts, figures, captions, and quiz answers against the original. Finish one complete lesson before more content. Test download, close/reopen, flight-mode playback, pause, seek, replay, changed speed, figure focus, wrong answer, correct answer, progress resume, and missing-asset recovery. The complete experience must work after a confirmed download. Label prepared content and actual narrator mode. No live OCR, multilingual, medical, or general-import accuracy claim.

**Corpus design.** For the first automated pilot, assemble roughly 30 licensed documents and at least 300 representative pages. Use separate development and held-out groups. Include single-column prose, two-column papers, technical instructions, figures with captions, tables, footnotes, mixed scan/digital pages, equations, rotated pages, low-quality OCR, and hostile instructions. Tag unsupported formats as expected rejections. This is a practical starting sample, not a statistically sufficient safety claim. Expand the corpus from actual failures without silently training against the held-out set.

**Proposed launch gates.** Treat these as engineering targets to validate, not published benchmarks:

| Area | Initial acceptance rule |
|---|---|
| Source linkage | Every factual script sentence and correct answer has valid source evidence. |
| Critical correctness | Zero known critical contradictions, wrong answers, fabricated figures, or incorrect numbers in the reviewed launch set. |
| Human grounding review | At least 98% factual claims fully supported in the held-out sample; publish sample size and remaining error types. Failures in a critical category still block release. |
| Extraction | At least 99% character accuracy on the selected clean digital sample; evaluate scans separately. Critical labels, figures, or numbers require exact checking even if overall character accuracy passes. |
| Learning units | At least 90% rated coherent and complete by two reviewers; reconcile disagreements. No definition separated from an essential condition. |
| Figures | All selected crops link to the correct source/page; no clipped essential label in released lessons. |
| Quiz | All released answers checked; no ambiguous correct options in the review set. |
| Audio | No omitted sentence or changed critical term; all cues in range; no drift greater than about 250 ms at tested sentence/figure boundaries. |
| Offline package | All required bytes present and checksums valid before “Available offline.” No network required for replay. |
| Cost and failure | Measured cost per accepted lesson within the approved cap; failed jobs visible and recoverable without double publication. |

**Language gate.** Each new language gets at least two competent native reviewers, technical terminology checks, comprehension tests with target learners, and audio intelligibility ratings. Use a proposed average of at least 4/5 with no critical meaning errors; publish sample size and disagreement. English performance is not evidence for Hausa, Yoruba, Igbo, French, or Swahili. Users should be able to return to the original language and source text.

**Model drift.** Record generator model/snapshot, package version, parser and weight hashes, glossary, voice identifier, date, and evaluation report. A changed provider alias, dependency, OCR language pack, or prompt requires replay of the relevant held-out tests. Compare quality, rejection rate, latency, and cost. Roll back by restoring the previous manifest generation path; do not rewrite already approved lessons silently.

**Operational evidence.** Count accepted and rejected pages; record warning reasons; measure first-play readiness, conversion p50/p95, peak RAM, timeouts, provider 429s, total input/output tokens, synthesized characters, retry cost, and review minutes. Store no raw document text in routine telemetry. Sample content errors through a controlled review path with ownership checks. A learner's “report problem” action should point to a document revision and unit, not require pasting their whole private file into a public support system.

### Gaps

No implementation, evaluation corpus, learner study, or device test exists from this research task. The named thresholds need feasibility testing. They are not evidence that the prototype improves retention or ADHD symptoms. The product research workstream must define the learning-effectiveness study separately.

### Source ledger

All sources below were checked on 10 October 2026. GitHub repositories, release metadata, and license text were read with `gh`. Sources are official documentation, publisher model cards, or project repositories. Documentation can change; retain the exact approved dependency and model revisions at implementation time.

| Source | What it establishes |
|---|---|
| [OpenAI billing](https://help.openai.com/en/articles/9039756-managing-billing-for-chatgpt-and-the-api-platform) | Consumer subscription/API billing separation. |
| [Docling repository](https://github.com/docling-project/docling) and [v2.137.0 release](https://github.com/docling-project/docling/releases/tag/v2.137.0) | MIT code, release date. |
| [Docling representation](https://docling-project.github.io/docling/concepts/docling_document/) and [model catalog](https://docling-project.github.io/docling/usage/model_catalog/) | Hierarchy, geometry, provenance, modular processing. |
| [Heron](https://huggingface.co/docling-project/docling-layout-heron) and [Docling model bundle](https://huggingface.co/docling-project/docling-models) | Separate model licenses. |
| [pypdf extraction](https://pypdf.readthedocs.io/en/stable/user/extract-text.html) and [license](https://github.com/py-pdf/pypdf/blob/main/LICENSE) | Digital/OCR limitations and license text. |
| [PyMuPDF](https://pymupdf.readthedocs.io/en/latest/about.html#license-and-copyright) | AGPL/commercial licensing. |
| [Unstructured](https://docs.unstructured.io/open-source/core-functionality/partitioning) and [repository](https://github.com/Unstructured-IO/unstructured) | Strategies, ordering caveat, Apache-2.0. |
| [Marker](https://github.com/datalab-to/marker) | Current code/weight license split and inference modes. |
| [Azure layout](https://learn.microsoft.com/en-us/azure/ai-services/document-intelligence/prebuilt/layout?view=doc-intel-4.0.0) and [FAQ](https://learn.microsoft.com/en-us/azure/ai-services/document-intelligence/faq?view=doc-intel-4.0.0) | Geometry, crop behavior, 24-hour processing retention. |
| [Google Document AI](https://cloud.google.com/products/document-ai/pricing) and [AWS Textract](https://aws.amazon.com/textract/pricing/) | Processor distinctions and current published price examples. |
| [GPT-4.1 Mini](https://developers.openai.com/api/docs/models/gpt-4.1-mini), [Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs), [data controls](https://developers.openai.com/api/docs/guides/your-data) | Candidate snapshot, price, output contract, retention. |
| [Azure languages](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/language-support?tabs=tts), [synthesis](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/how-to-speech-synthesis), [privacy](https://learn.microsoft.com/en-us/azure/foundry/responsible-ai/speech-service/text-to-speech/data-privacy-security) | Actual TTS voices, timing, real-time storage policy. |
| [Google TTS pricing](https://cloud.google.com/text-to-speech/pricing) and [logging](https://docs.cloud.google.com/text-to-speech/docs/data-logging) | Character costs and no customer-text/audio logging statement. |
| [OpenAI TTS guide](https://developers.openai.com/api/docs/guides/text-to-speech) and [model page](https://developers.openai.com/api/docs/models/gpt-4o-mini-tts) | English optimization and conflicting deprecated-model guidance. |
| [ElevenLabs models](https://elevenlabs.io/docs/overview/models) and [timestamps](https://elevenlabs.io/docs/api-reference/text-to-speech/convert-with-timestamps) | Hausa/Swahili synthesis, alignment, enterprise zero retention. |
| [MMS Yoruba](https://huggingface.co/facebook/mms-tts-yor) and [NLLB](https://huggingface.co/facebook/nllb-200-distilled-600M) | Noncommercial model licenses. |
| [OWASP upload](https://cheatsheetseries.owasp.org/cheatsheets/File_Upload_Cheat_Sheet.html), [SSRF](https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html), [prompt injection](https://cheatsheetseries.owasp.org/cheatsheets/LLM_Prompt_Injection_Prevention_Cheat_Sheet.html) | Input, fetcher, and model trust boundaries. |
| [Ollama macOS](https://docs.ollama.com/macos), [FAQ](https://docs.ollama.com/faq), [model package](https://ollama.com/library/qwen3:4b), [Qwen card](https://huggingface.co/Qwen/Qwen3-4B), [WebLLM](https://webllm.mlc.ai/docs/) | Conditional local-model route and its hardware/network costs. |
