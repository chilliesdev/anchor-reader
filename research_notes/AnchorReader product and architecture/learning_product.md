# AnchorReader: learning, product, market, access, and validation

Research date: 2026-10-10. All linked sources accessed on this date. User confirmed: one builder, three days, English, hackathon prototype, Codex Plus plan; Android is optional. Proposed delivery: a static hosted progressive web app (PWA), tested on the actual presentation device. Nigeria and adult university or technical learners remain proposed first-pilot choices. They are not confirmed customer requirements. No code was written for this research.

Evidence labels: **Verified** means the cited source supports the stated fact. **Decision** means a proposed project choice. **Hypothesis** means a claim that needs a project test. All scope limits, schedules, sample sizes, and acceptance thresholds below are proposed operating choices, not established scientific results.

## 1. Which learning claims are supported?

### Takeaway

Build around learner control, clear concept boundaries, source-linked diagrams, and recall practice. Optional background motion is an unproven preference. Do not promise dopamine effects, ADHD treatment, guaranteed retention, or zero abandonment.

### Cited Findings

- **Verified — retrieval practice.** Roediger and Karpicke's 2006 experiments compared repeated study with free recall of prose passages. Study helped the immediate test more; prior testing helped tests after two days and one week more. This supports delayed measurement and recall opportunities. It does not establish that a generated multiple-choice question guarantees learning. [Test-Enhanced Learning, Roediger and Karpicke, 2006](https://www.psychologicalscience.org/journals/psychological-science/j.1467-9280.2006.01693.x/).
- **Verified — multimedia design.** Mayer and Moreno describe limited processing capacity and report benefits from learner-controlled segments, advance explanation of key components, useful cues, and close alignment of relevant pictures and narration. Their work concerns instructional content, not unrelated gameplay. Reported outcomes include transfer to new problems. It supplies a rationale for these features, not an optimal reel duration for AnchorReader. [Nine Ways to Reduce Cognitive Load in Multimedia Learning, 2003](https://www.uky.edu/~gmswan3/575/9_ways_to_reduce_CL.pdf).
- **Verified — spaced review.** A synthesis covered 317 experiments from 184 articles. Useful spacing depended partly on the intended retention interval. This supports revisiting content later; it does not prescribe one universal schedule. [Distributed Practice in Verbal Recall Tasks, Cepeda et al., 2006](https://pubmed.ncbi.nlm.nih.gov/16719566/).
- **Verified — decorative-content risk.** A 2020 meta-analysis reports that interesting irrelevant details can impair learning, with effects moderated by design and study conditions. The publisher abstract was available; full text was behind a subscription. [Keep It Coherent, Sundararajan and Adesope, 2020](https://link.springer.com/article/10.1007/s10648-020-09522-4).
- **Verified — direct contrary evidence limits a blanket claim.** Two preregistered within-person studies, with 75 and 100 participants, found no strong evidence that split-screen secondary videos changed memory. The stimuli were short video lectures, about 7–15 minutes, paired with muted unrelated videos. Participants came from a US university pool and/or English-speaking-country online recruitment. Tests followed a short distractor task; this was not a week-long retention trial, an African mobile study, or an ADHD treatment trial. Results show neither established benefit nor universal harm. [Split-screen distraction, Schuetze, 25 March 2026](https://link.springer.com/article/10.1186/s41235-026-00720-2).
- **Verified — preferences vary.** FocusView evaluated a video-customization interface with 12 participants with ADHD. It reports varied reactions to background stimuli and improved viewability after customization. This small design study supports offering control; it does not validate a clinical treatment or prove retention gains. [FocusView, Zhu, Chen and Zhao, 2025 author preprint](https://arxiv.org/abs/2507.13309).

### Inferences

**Decision: replace the claimed mechanism.** Describe AnchorReader as “guided audio lessons from dense documents, with source diagrams, short recall checks, and offline replay.” Describe motion as optional. “Dopamine-anchored,” “eliminate drop-off,” and “guarantee retention” are not acceptable product claims on the available evidence.

**Decision: one meaningful concept per unit.** A unit needs an objective, enough context, one main idea, a source reference, and a transition. Keep a definition with its conditions; keep a worked example with the step that it explains. Do not split an equation from its variables or a figure from its caption. Start from headings, paragraphs, examples, and figure references. A word or duration limit is a backstop, not the semantic boundary. For the demo, select content that fits four to six coherent units. Review every boundary manually.

**Decision: retain the source.** Distinguish “read source” from “explain source.” An explanation must preserve qualifications, units, uncertainties, and citations. Clearly mark simplification. A source may be wrong or dated; a citation proves traceability, not truth. Give learners a source-page view when the short lesson lacks detail.

**Decision: recall is support, not a locked gate.** At a boundary, pause narration and offer one short question. Include “I do not know,” “Review,” and “Skip for now.” Record skipped separately from wrong. Give an answer explanation and source reference. Do not force repeated guessing before continuation. Use one application question as well as factual questions. For a three-day build, use reviewed answer choices and explanations; defer AI marking of open answers.

**Decision: finite sessions.** Show topic order, lesson end, and a clear stop point. Avoid endless autoplay, public rankings, streak loss, or variable rewards. Mark “completed” separately from “answered correctly.” Do not label a topic “mastered” after one answer. A future review queue can revisit weak topics; initial intervals are testable defaults, not neuroscience claims.

**Decision: calm first.** Start with a plain or static scene. Offer one local, silent, slow-motion option. Pause it during a diagram and quiz. Keep motion choice independent of narration. Do not remove captions because some multimedia studies find redundant text unhelpful: access needs and individual preferences require control.

### Gaps

- No identified study validates this exact combination of semantic PDF conversion, local loops, diagram spotlighting, and quizzes for the target cohort.
- Optimal unit size depends on prior knowledge, topic, source quality, and language. No universal 30-, 60-, or 90-second rule is established here.
- Better completion may coexist with worse learning. The project must measure both.
- ADHD-specific effectiveness needs a separate, consented study and suitable expertise. It is outside the hackathon claim.

## 2. What should the three-day prototype contain?

### Takeaway

Prove one complete learning journey from one authorized document. Prepare up to two reserve documents only after the main journey passes. Build the interface around a real lesson; label all prepared content honestly.

### Cited Findings

- Existing tools already cover much of the broad promise. Google introduced mobile offline Audio Overviews in May 2025 and mobile quizzes and flashcards in November 2025. Offline audio or a PDF quiz alone is not a new category. [NotebookLM mobile announcement](https://blog.google/innovation-and-ai/products/notebooklm-app/), [NotebookLM mobile quizzes announcement](https://blog.google/innovation-and-ai/models-and-research/google-labs/notebooklm-app-quizzes-flashcards/).
- Speechify advertises document reading, speed control, text highlighting, summaries, quizzes, and offline listening for Premium. These are first-party feature claims, not independent learning-effect evidence. [Speechify Web Application](https://speechify.com/web/).

### Inferences

**Decision register for immediate scope**

| Area | Three-day baseline | Change trigger |
|---|---|---|
| User | Adult English reader with a difficult assigned text | Pilot interviews identify a narrower, stronger need |
| Corpus | One clean, text-based PDF licensed for public demo redistribution and adaptation; one clear concept lesson | Main demo passes and remaining time is sufficient |
| Lesson | Four to six units, one or two source diagrams, three reviewed recall checks | Topic requires another unit to remain correct |
| Narration | One consistent English voice; prepared audio allowed with disclosure | Reliable runtime synthesis is already available and tested |
| Input | Select a bundled sample; show provenance | Add bounded user upload only after core acceptance |
| Motion | Calm default and one optional local loop | User test identifies a useful alternative |
| Navigation | Next, previous, pause, replay, topic list, source view | Do not add swipe-only operation |
| Offline | Complete prepared lesson works without connectivity | If this fails, it blocks the core demo claim |
| Progress | Local position and quiz state; reset control | Account sync becomes necessary for a real pilot |
| Accounts | None | Institutional or multi-device use is proven |
| Revenue | None in prototype | Pilot validates willingness to pay and cost |
| Language | English only | Separate language review and budget exist |

**P0: must show.** Open lesson; state its objective; play and pause narration; navigate coherent units; show the right figure with its source page; pause at recall; show correct feedback; continue or skip; finish; resume local progress; replay offline. Include static mode, text transcript, clear control labels, and reset.

**P1: only if P0 is complete.** A second content example; a document outline preview; a restrained import path with explicit size/type limits; one flagged extraction-failure state; adjustable speed; download-size display; one short review session.

**Deferred.** Arbitrary scanned PDF OCR; handwriting; difficult equations; nested tables; complex multi-column extraction; multilingual production; African-language speech; automatic diagram interpretation; personalized tutoring; online voice chat; LMS or university login; teacher dashboards; paid plans; app-store launch; sharing; public content library; adaptive recommendation; clinical personalization; production cloud ingestion. Defer advanced word-by-word effects if sentence highlighting is sufficient.

**Proposed first flow.** Choose “Try sample.” See title, author, source, language, included topics, and approximate duration. Start the first unit. See a concept heading and transcript. Open a figure; motion stops and figure controls remain visible. Answer or skip the checkpoint. At the end see completed units and review items. Close and reopen to resume. Open settings to clear local progress.

**Proposed failure flow.** If an optional upload is unsupported, explain which constraint failed and offer the sample. Never produce confident content from missing pages. If extraction or generation needs the network, show that before import. An offline lesson is “ready” only when all required audio, text, diagrams, and questions are present. A partial package must not be labeled ready.

**Demo acceptance, not claimed current performance**

| Check | Pass condition |
|---|---|
| Source fidelity | Builder compares every demo sentence, figure, and answer with the source; no known material error |
| Concept integrity | All four to six units have a coherent objective and intact conditions/examples |
| Figure use | One or two verified crops include necessary labels, caption, and page reference; learner can enlarge them |
| Recall | Three reviewed checks work; incorrect, unknown, skip, and retry states are distinct |
| Offline proof | From the prepared device, disconnect network, restart the app, play all units, open figures, answer questions, and resume |
| Local progress | Closing and reopening preserves position and answer state without a server |
| Access | Complete the core path with motion disabled and sound muted; text remains usable at enlarged size |
| Honesty | Prepared output is called prepared; no fake upload spinner or fabricated live AI result |
| Recovery | Reset returns to a clean starting state; a second full run works |

**Three-day delivery allocation.** Day 1: freeze the use case, rights-cleared source, lesson objective, reviewed script, diagram crops, quiz answers, and basic player path. Day 2: complete offline storage, transitions, progress, accessible controls, and failure states; add one loop last. Day 3: use the real demonstration device, run offline and restart checks, get brief feedback from three to five adults, fix critical problems, prepare a backup recording, and rehearse. Reserve the last half day for repair and presentation. This is a work sequence, not a promise that one person can sustain 72 working hours.

**Judging story.** Show the original dense page, then one concept, one diagram, and one recall check. Disconnect the network and continue. State which processing steps are automated, reviewed, or prepared. Close with the next experiment: delayed recall and data use against ordinary reading or audio. Do not display invented improvement percentages.

### Gaps

- Hackathon rules, judging rubric, permitted prebuilt assets, required live AI behavior, submission format, and exact deadline are not yet known.
- The actual presentation device, browser, audio setup, and venue connectivity are unconfirmed. Test the PWA on the available device; do not require Android solely for the demo.
- No rights-cleared demo document has been selected in this research. The owner must supply one or approve a suitable open-license source.
- The Codex Plus plan must not be treated as a cash API or production-hosting budget. Runtime entitlement is an independent technical decision.

## 3. How should access and learner control work?

### Takeaway

Access features belong in the first learning flow. A moving feed that cannot be stopped conflicts with the intended focus benefit.

### Cited Findings

- WCAG 2.2 requires a pause, stop, or hide mechanism for certain automatically starting motion that lasts more than five seconds alongside other content. [W3C: Pause, Stop, Hide](https://www.w3.org/WAI/WCAG22/Understanding/pause-stop-hide.html).
- Disabling nonessential interaction-triggered animation is a Level AAA criterion, not a blanket Level AA requirement. It remains a suitable product choice. [W3C: Animation from Interactions](https://www.w3.org/WAI/WCAG22/Understanding/animation-from-interactions.html).
- WCAG restricts content that flashes more than three times in one second unless it is below defined flash thresholds. [W3C: Three Flashes or Below Threshold](https://www.w3.org/WAI/WCAG22/Understanding/three-flashes-or-below-threshold.html).
- Prerecorded synchronized media generally needs captions, with a stated exception for clearly labeled media alternatives for text. [W3C: Captions](https://www.w3.org/WAI/WCAG22/Understanding/captions-prerecorded.html).
- WCAG 2.2 AA target size is generally at least 24 by 24 CSS pixels or qualifying spacing/other exceptions. Larger targets are reasonable for this product. [W3C: Target Size Minimum](https://www.w3.org/WAI/WCAG22/Understanding/target-size-minimum.html).
- Text resizing to 200% without lost content or function is an AA requirement, with specified exceptions. [W3C: Resize Text](https://www.w3.org/WAI/WCAG22/Understanding/resize-text.html).
- WCAG2ICT gives guidance for applying WCAG concepts to non-web software. It is not a separate certification. [W3C: Applying WCAG to Non-Web ICT](https://www.w3.org/TR/wcag2ict-22/).

### Inferences

**Decision: target WCAG 2.2 AA principles, with reduced motion beyond the minimum.** For a native app, use the non-web guidance and platform accessibility APIs. Do not claim compliance from a short checklist alone.

Use explicit play; do not start sound on launch. Keep a pause control available. Offer transcript and captions as real selectable text, not pixels in a video. Keep diagram labels and captions legible. Supply a reviewed text description of the figure's important relationship. For the prototype, choose a figure that can be described accurately; do not let a model guess missing labels.

Honor the device reduced-motion preference and give an in-app “Motion off” switch. Preserve it on restart. Avoid flashing, rapid zoom, parallax, and abrupt color pulses. Switch motion off during questions. Use a single quiet scene rather than constant scene changes. A label such as “Optional background motion” is clearer than a claim such as “ADHD mode.”

Keep every gesture action available through labeled controls. Use a logical focus order. Announce question feedback and progress without forcing the screen reader to speak all content again. Prevent app narration and screen-reader speech from competing; provide manual controls and test the chosen device. Do not use color alone for correct and wrong answers. Avoid timed questions. Allow replay without penalty.

Later pilot acceptance must include an actual screen-reader run, large-font checks, switch or keyboard access where supported, caption synchronization, device interruption, low storage, and a source figure with dense labels. Involve learners with relevant access needs. Automated checks cannot substitute for these tasks.

### Gaps

- Individual motion tolerance varies. A preset cannot identify it from a diagnosis.
- Screen-reader compatibility, audio focus, and usable figure magnification need device evidence after implementation.
- The prototype can demonstrate access choices; it cannot support a general accessibility-conformance claim until a full review is complete.

## 4. Is there a market need, and what is distinctive?

### Takeaway

Data and device constraints are credible context. They are not proof that AnchorReader solves reading abandonment or that a specific population will pay. Compete on a complete low-data learning task and content reliability, not on AI summaries alone.

### Cited Findings

- ITU's 2025 release reports that data-only mobile broadband remained unaffordable in about 60% of low- and middle-income countries. This is a cross-country affordability statement, not a Nigeria student price estimate. [ITU Facts and Figures 2025 release, 17 November 2025](https://www.itu.int/en/mediacentre/Pages/PR-2025-11-17-facts-and-figures.aspx).
- GSMA's 2025 affordability report states that an entry-level internet-enabled device in Sub-Saharan Africa costs 87% of average monthly income for the poorest fifth of the population. The indexed official report extract was available; direct PDF retrieval failed. Treat this as regional background, not a target-user survey. [GSMA: Affordability of Internet-Enabled Handsets and Data, 2025](https://www.gsma.com/somic/wp-content/uploads/2025/11/The-State-of-Mobile-Internet-Connectivity-2025-Affordability-of-Internet-Enabled-Handsets-and-Data.pdf).
- StudyFetch advertises PDF-to-quiz, flashcards, audio recaps, study plans, and explainer video, including short social-style formats. Its grade-improvement claims were not independently validated in this research. [StudyFetch: Study Tools](https://www.studyfetch.com/tools).
- NotebookLM offers source-based study output and offline audio; Speechify offers document narration and offline listening. This research verified advertised functions, not a side-by-side device test or feature absence. [Google mobile announcement](https://blog.google/innovation-and-ai/products/notebooklm-app/), [Speechify](https://speechify.com/web/).

### Inferences

**Competitive decision table**

| Alternative | Why a learner may use it | AnchorReader must prove |
|---|---|---|
| PDF reader plus phone text-to-speech | Simple, familiar, may need no new account | Better concept navigation and recall support justify another app |
| NotebookLM | Broad source-based study tools; offline audio | A smaller, clearer guided journey with complete offline interaction |
| Speechify | Mature document narration and reading controls | Useful source diagrams and recall at topic boundaries, with low package cost |
| StudyFetch | Broad study workflow and generated materials | Reliability and access for the chosen context, not feature quantity |
| Downloaded lesson videos | Familiar offline playback | Lower transferred bytes for comparable material and readable source figures |
| Ordinary reading and notes | Direct source access; no AI errors | At least comparable learning with less friction for the selected task |

These are differentiation hypotheses. Do not claim competitors lack a feature without testing current versions. A local loop itself is easy to copy. Potential longer-term advantages are a tested conversion-quality corpus, rights-cleared local course partnerships, trustworthy source alignment, strong offline behavior, and language quality assessed by native speakers. None is an existing moat at hackathon stage.

**Language scope.** English first. French is a later candidate, not an automatic second release. Select the next language from actual partner demand, source availability, voice quality, and review capacity. “Local African languages” is too broad for a commitment. Each language and dialect needs pronunciation, technical vocabulary, code-switching, and comprehension checks. Translation and speech quality are separate acceptance gates.

**Data claim.** Use “offline replay after preparation or download.” Account for app installation, source upload, model calls, audio download, diagrams, updates, telemetry, and progress sync. Local loops only remove repeated streaming of that visual asset. Measure the bytes for a fixed lesson and a specified comparison; do not claim “zero data” for the complete lifecycle. Lower bandwidth does not itself establish better learning.

**Discovery before a paid product.** Interview 10–15 adults in one reachable learning context, then three to five instructors or course organizers. These are practical discovery targets. Ask about the last dense document they failed to finish, what they tried, their phone, connectivity, spending, time pressure, access needs, and rights to course materials. Ask them to show the workflow, with consent. Avoid leading questions such as whether they would like “TikTok for learning.” Record contrary findings.

**Provisional commercial choice.** Free hackathon demonstration. For an adult pilot, test a small allowance for new document processing, free replay of existing local lessons, and paid processing credits or institution-funded access. Compare credits with a capped subscription using actual purchase choices or clearly labeled non-charging interest tests. Do not set a final naira price from foreign competitor pricing. Recheck unit cost, tax, store fees, support, failed imports, and willingness to pay before launch. Avoid ads and selling learner attention.

**Market sizing method.** Start with named partner cohorts and reachable learners, their actual course-material frequency, eligible device share, uptake, and payment capacity. Build scenarios from observed inputs. Do not multiply the African population by an invented subscription price.

### Gaps

- There is no verified target-cohort abandonment rate, document mix, device distribution, data spend, acquisition cost, conversion rate, or willingness to pay.
- Nigeria is a practical proposed starting location, not evidence that all African markets have the same needs.
- No market-size estimate is justified by the sources collected.
- Local device research must include storage and battery conditions. The regional affordability statistic does not specify a correct Android version or RAM floor.

## 5. What privacy and rights decisions apply?

### Takeaway

Keep the prototype local, adult-facing, and free of sensitive uploads. A later hosted pilot needs an explicit data map, rights process, vendor checks, and deletion behavior before collecting real documents.

### Cited Findings

- Nigeria's Data Protection Act covers lawful processing, consent, information for the subject, impact assessment, sensitive data, children, subject rights, security, and international transfers. Section 41 requires an applicable transfer basis and a recorded basis/adequacy assessment. Official indexed statutory extracts were retrieved; direct regulator PDF access returned 403. [Nigeria Data Protection Act, 2023, official copy](https://ndpc.gov.ng/wp-content/uploads/2024/03/Nigeria_Data_Protection_Act_2023.pdf), [NDPC FAQ](https://ndpc.gov.ng/faqs/).
- Nigeria's Copyright Act gives rights over reproduction, translation, recording, and adaptation. Section 20 includes fair dealing for private use, non-commercial research, and private study, assessed through factors such as purpose, amount, and market effect. “Educational” is not a blanket permission for a commercial service or public demo. [Nigeria Copyright Act, 2022, official gazette published 2023](https://www.copyright.gov.ng/CopyrightAct/CopyrightAct2023FinalPublication1.pdf).
- A Google Play app that enables account creation must provide an in-app account-deletion path and an external web resource, with associated data deletion and disclosed legitimate retention exceptions. [Google Play account deletion requirements](https://support.google.com/googleplay/android-developer/answer/13327111?hl=en-en).
- Google Play generally requires its billing system for in-app digital goods and education subscriptions, subject to specified programs and exceptions. Country and program eligibility must be checked before selecting another checkout. [Google Play Payments policy](https://support.google.com/googleplay/android-developer/answer/9858738?hl=en).

### Inferences

**Prototype decisions.** A static hosted PWA exposes bundled assets to public download. Use a creator-owned document or a source whose terms permit public redistribution and the planned adaptations. Record the exact license and version, author, source URL, attribution text, changes, and permitted uses. Check each third-party figure separately; the surrounding paper's license may exclude it. Check generated narration distribution rights, the chosen voice's terms, and the background loop license. Put required credits in the app and presentation. Do not assume open access means free adaptation. Do not bundle private learner documents in the public app. Do not upload classmates' records, medical data, restricted exam papers, or employer documents. Do not collect health diagnoses to choose a visual mode.

Ask informal testers for plain consent. Explain purpose, time, observations collected, whether recording occurs, and that they can stop. Do not record by default. Store only notes needed for the test. Avoid names in the product; keep any scheduling contact details separately. A hackathon prototype does not need advertising IDs, behavioral tracking SDKs, or a remote analytics account.

**Later pilot data map.** Enumerate account identifiers, documents, extracted text, images, audio, prompts, model outputs, quiz attempts, preferences, device events, support requests, and backups. For each, record purpose, lawful basis, storage location, provider, access, retention, and deletion route. “Pseudonymous” events can still be personal data when linked to an account. Keep raw document text, names, signed URLs, and answers out of routine logs.

**Later vendor gate.** Verify whether uploads or prompts train vendor models; exact retention; regional processing; subprocessors; deletion support; security terms; and the international-transfer basis. Do not rely on a generic “secure cloud” statement. Source consent and provider terms are separate checks. A user cannot necessarily authorize disclosure of everyone else's personal data in a document.

**Later deletion design.** Delete source and derived files together, including extracted images, audio, embeddings if any, and queued jobs. Expire temporary links. Explain backup expiry rather than promising immediate erasure from all backups. Separate local-device deletion from server deletion. Apply a documented minimum retention policy; do not invent a legal deadline from an example policy.

**Public-release gate.** Recheck current Nigerian implementation guidance, registration or DPO duties, impact-assessment needs, breach process, consumer terms, and all chosen markets. Keep an adult-only pilot until child-specific consent and safety requirements are designed. Add a rights complaint route and keep sharing private by default. If using Play, align the privacy notice and Data Safety answers with actual SDK/network behavior; recheck payment policy at launch. Apple requirements belong to a separate iOS release gate.

### Gaps

- This research did not determine the legal entity, processing scale, hosting jurisdiction, provider contracts, or exact registration duties.
- Full current NDPC implementation guidance could not be retrieved directly. Final public-launch legal interpretation remains an expert review item, not a completed decision.
- A local prototype does not prove that a later hosted product meets privacy law.

## 6. How should the concept be validated after the demonstration?

### Takeaway

Separate usability, content fidelity, data cost, and learning tests. Three days can establish a credible demonstration and identify obvious friction. It cannot establish lasting retention or product-market fit.

### Cited Findings

- Delayed testing can reverse the apparent ranking of study methods seen on an immediate test. [Roediger and Karpicke, 2006](https://www.psychologicalscience.org/journals/psychological-science/j.1467-9280.2006.01693.x/).
- Multimedia research distinguishes recall from applying an idea to a new problem. [Mayer and Moreno, 2003](https://www.uky.edu/~gmswan3/575/9_ways_to_reduce_CL.pdf).
- The 2026 split-screen study explicitly leaves longer retention intervals and more demanding material for future research. [Schuetze, 2026](https://link.springer.com/article/10.1186/s41235-026-00720-2).

### Inferences

**Stage A — hackathon.** Observe three to five adults using the exact demo. Ask them to find the source, stop motion, replay, skip a question, and resume offline. Record task completion, errors, and comments. A small convenience sample finds usability issues; it does not establish efficacy. Request one brief explanation of a concept to expose unclear teaching. Label results descriptive.

**Stage B — conversion quality.** Before arbitrary uploads, build a rights-cleared evaluation set representing clean prose, two-column papers, captions, tables, equations, scans, and difficult layouts. Score reading order, omitted/duplicated text, concept coherence, figure-caption links, source support, and question answerability. Mark supported classes explicitly. A failed class must fall back or be rejected. Do not report a single accuracy number that hides figure or equation failures.

**Stage C — feasibility pilot.** Propose 20–40 consenting adults across the selected context, with varied devices and reading confidence. This is a feasibility range, not a powered efficacy claim. Follow use over two to four weeks. Measure first usable lesson, failed imports, delayed starts, reasons for stopping, actual data transfer, offline success, source-check use, and return for a second assigned reading. Use interviews to explain telemetry. Do not exclude everyone who fails to return from the results.

**Stage D — learning comparison.** Compare ordinary source reading or audio with a calm AnchorReader lesson of matched content and time. Assess prior knowledge. Use balanced assignment across different but comparable topics to avoid a learner seeing the same answer twice. Use a pre-specified scoring rubric, independent questions, and blinded marking where possible. Test immediately, after roughly seven days, and later if feasible. Include recall and a new application problem. Keep reminders and study time comparable. Record attrition, extra study, and missing results.

**Stage E — isolate motion.** After the base lesson works, compare the same lesson with and without optional motion. Keep narration, questions, layout, and source unchanged. Pre-specify learning and discomfort outcomes. A longer watch time does not count as a learning gain. If users prefer motion but delayed performance falls, remove or redesign it. Do not combine a segmentation change and motion change then attribute the result to motion.

**Metrics and decisions**

| Metric | Why it matters | Misinterpretation to avoid |
|---|---|---|
| Delayed recall | Information remembered later | Immediate quiz score equals durable learning |
| Transfer answer | Application to a new case | Repeating the exact practice question proves understanding |
| Completion plus reason for exit | Task fit and barriers | More time in app is always better |
| Data per usable lesson | Cost of the full journey | Small loop file means zero total data |
| Offline completion after restart | Real independence from connectivity | One cached screen proves the whole lesson works |
| Content error severity | Trust and learning safety | Average score hides one serious wrong explanation |
| Voluntary second-document use | Repeated need | A prompted demo replay proves demand |
| Paid choice or institution commitment | Revenue evidence | Survey enthusiasm proves willingness to pay |

**Go/no-go decisions.** Proceed from demo to pilot only when rights, accuracy, offline replay, and basic access pass. Fix critical content errors before recruiting more learners. Decide the minimum meaningful learning difference and acceptable data cost with the pilot partner before the efficacy study; then conduct a sample-size calculation. Do not invent a target improvement percentage now. Public claims must match the tested population, lesson type, interval, and comparison.

### Gaps

- No human study, interviews, benchmark run, or network measurement was performed in this research.
- Owner decisions still needed: hackathon rules, document rights, demonstration device, achievable daily work time, testing contacts, and whether any paid runtime service is permitted.
- Later stakeholder decisions: course domain, recruitment partner, local language priority, acceptable per-lesson cost, institutional procurement terms, and the learning outcome worth paying for.
