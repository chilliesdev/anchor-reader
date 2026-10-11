# AnchorReader: mobile platform, architecture, tools, cost and delivery

Research date: 10 October 2026. Confirmed scope: one builder, three calendar days, English, existing Codex Plus plan. Android app is optional, not required. This is a research plan. No application code, software installation, deployment, account creation, or runtime test was done. Prices are USD before tax. Proposed limits and targets below are design decisions, not measured results.

## 1. What can one person deliver in three days?

### Takeaway

Build a phone-first progressive web app (PWA). Demonstrate one reviewed document as three to five concept lessons, narration, one figure, recall questions, source links, resume and downloaded playback. Use about 18–24 focused work hours across three days. Do not put public document conversion, native store release, or paid AI on the critical path.

### Cited Findings

- Vite produces a static build that can be deployed to static hosting. Its preview server is for local preview, not production hosting. [Vite deployment guide](https://vite.dev/guide/static-deploy.html)
- A service worker can serve cached app resources offline. It requires a secure context, with a local development exception. A browser can stop a service worker; it is not a permanent background process. [Service Worker API](https://developer.mozilla.org/en-US/docs/Web/API/Service_Worker_API), [MDN offline operation](https://developer.mozilla.org/en-US/docs/Web/Progressive_web_apps/Guides/Offline_and_background_operation)
- Cloudflare Pages advertises a free tier with static requests and bandwidth included. A single site asset is limited to 25 MiB. Functions have separate limits and billing. [Pages](https://www.cloudflare.com/products/pages/), [Pages limits](https://developers.cloudflare.com/pages/platform/limits/), [Functions pricing](https://developers.cloudflare.com/pages/functions/pricing/)
- Official OpenAI documentation says API token prices are separate from subscription usage. Treat Codex Plus as a development aid, not a funded API for arbitrary AnchorReader users. [OpenAI pricing](https://learn.chatgpt.com/docs/pricing)
- Read-only local check found Node, npm, pnpm, Java, adb, Android emulator, Android SDK, Android Studio, Xcode and macOS `say`. The Node path identifies v24.15.0. Flutter was not found. This establishes tool presence only; it does not establish successful native builds, a connected phone, installed voices, or redistribution rights. Evidence: local command-path and directory checks in this research session.

### Inferences

**Chosen demo:** React, TypeScript, Vite, vite-plugin-pwa with Workbox, Dexie over IndexedDB, browser media playback, CSS/SVG motion, Cloudflare Pages. Assume no new cash budget. Existing laptop, Android phone, internet, electricity and Plus subscription are already available. If hosting registration fails, use a preloaded deployed build from another approved static host; also keep a tested laptop build and a clearly identified demo recording.

**Scope that must work:** open library; choose prepared source; see original source and lesson outline; start narration by tap; read transcript; pause/replay/change speed; stop at a checkpoint; answer and see explanation; view figure/source page; save progress; download the complete lesson; reopen and play in flight mode; delete the download. One document with three solid lessons is enough. Three documents are optional.

**Audio decision:** own human-recorded English narration is the deterministic, zero API spend fallback. Label it as recorded narration. Permit device speech as an optional synthetic narration mode only after testing the selected installed English voice offline on the demonstration phone. Do not describe recorded audio as AI output. Do not assume that macOS-generated voice recordings may be redistributed; verify the exact voice licence first. Allocate 1–2 hours to record and check 6–10 minutes of narration, within the build allowance.

**Document input:** primary demo uses a prepared source with clear rights. A static public deployment makes bundled text, figures and audio publicly retrievable. Use owned, public-domain or compatible licensed material that permits redistribution and adaptation. Permission to read a PDF is insufficient. Check separate figure licences and credit requirements. Label preparation truthfully. A local text paste or text-based PDF import may be a stretch feature, with simple heading/paragraph boundaries and visible limits. Do not present fixed sample output as live arbitrary document analysis. The document research note specifies the import fallback and future AI pipeline.

**Exclude from this deadline:** account creation, payments, social feeds, institutional tenancy, live OCR, arbitrary website fetching, cloud model orchestration, African-language claims, perfect word timing, push messages, leaderboards, store approval and a claim of proven retention.

| Platform | Evidence and trade-off | Decision |
| --- | --- | --- |
| Phone PWA | Link distribution; offline cache supported; browser storage and background execution have limits. | Select for this hackathon. Target current Android Chrome first. |
| React Native with Expo development build | Native libraries/configuration supported; Expo audio supports background media controls; SQLite supported. Requires a tested binary and native lifecycle work. | Select for the later Android pilot, if the PWA proves useful. |
| Flutter | Official offline architecture and Android/iOS support. Adds Dart/Flutter setup and does not remove native audio/device testing. | Viable alternative when the builder/team has demonstrable Flutter skill; no such evidence here. |
| Kotlin/Jetpack | Direct Android integration; Android documents local-first data repositories and Media3 background playback. Separate iOS route later. | Prefer only if Android-specific playback/reliability becomes the main engineering need. |

Sources for the comparison: [Expo development builds](https://docs.expo.dev/develop/development-builds/introduction/), [Expo audio](https://docs.expo.dev/versions/latest/sdk/audio/), [Expo SQLite](https://docs.expo.dev/versions/latest/sdk/sqlite/), [Flutter offline architecture](https://docs.flutter.dev/app-architecture/design-patterns/offline-first), [Flutter supported platforms](https://docs.flutter.dev/reference/supported-platforms), [Android offline architecture](https://developer.android.com/topic/architecture/data-layer/offline-first), [Android background media](https://developer.android.com/media/media3/session/background-playback).

**Why React now:** this is a planning choice for one web demo and a possible later React Native app. It is not a claim that the user already knows React. Shared TypeScript content models and validation can carry forward; web screens and service workers will not transfer unchanged into native screens and native storage.

### Gaps

- No hackathon rules, judging rubric, exact submission time, current phone model, browser version, verified narrator rights, or measured builder skill were supplied.
- No runtime API balance was verified. No runtime API funding is needed for the selected demo.
- If judging explicitly requires live AI conversion or an APK, that changes the must-have scope. A prepared-content demo must be described as such.

## 2. How should the offline lesson player work?

### Takeaway

Treat a lesson as a small, versioned download package. Audio, transcript, quiz, figures and source references must be usable without network requests after download. Keep playback independent of authentication and the server.

### Cited Findings

- Browser storage is normally best-effort. Persistent storage can be requested, but Chromium/Safari decide whether to grant it. Users can erase data; private browsing has different persistence. Cache and IndexedDB space is browser-controlled. [Storage quotas and eviction](https://developer.mozilla.org/en-US/docs/Web/API/Storage_API/Storage_quotas_and_eviction_criteria)
- Workbox warns that media played from network may be fetched as partial responses. Playing a file once does not prove it is fully cached. Explicitly cache the complete file; support media range requests. [Workbox cached audio/video](https://developer.chrome.com/docs/workbox/serving-cached-audio-and-video)
- Dexie supports IndexedDB transactions and version upgrades. Upgrade failures can roll back. [Dexie transactions](https://dexie.org/docs/Transaction/Transaction.html), [Dexie database design](https://dexie.org/docs/Tutorial/Design)
- Audible autoplay is restricted by browsers. Start narration from a clear learner tap and handle a rejected playback request. [Browser autoplay guide](https://developer.mozilla.org/en-US/docs/Web/Media/Guides/Autoplay)
- A browser voice exposes whether its service is local or remote. This is a useful filter, not proof that the complete user journey works offline. [Speech voice localService](https://developer.mozilla.org/en-US/docs/Web/API/SpeechSynthesisVoice/localService)
- Media Session supplies metadata and media actions where supported. Browser media time updates vary with load; they are not a precision word clock. [Media Session](https://developer.mozilla.org/en-US/docs/Web/API/MediaSession), [Media time updates](https://developer.mozilla.org/en-US/docs/Web/API/HTMLMediaElement/timeupdate_event)

### Inferences

**Package content:** stable lesson ID; immutable version; title; language; source title/author/rights; source pages and text spans; ordered concept IDs; transcript; narration mode; audio paths and durations; sentence or paragraph cues; figure paths, captions, alt text and source regions; questions, answer explanations and source references; total bytes; each asset's size and SHA-256 digest; package format version. Keep filenames and IDs opaque enough for future private content. This is a conceptual contract, not a code/schema deliverable.

**PWA storage layout:** cache the app shell, fonts and one motion asset once. Use lesson-specific Cache Storage entries for complete audio and image responses. Use IndexedDB for package status, active version, progress, preferences and quiz attempts. Keep one active player and render only the current and next card. Do not put binary audio or document text in localStorage. Browser storage is accessible to anyone using the same unlocked browser profile. Use only public sample content in the shared-phone demo; offer a clear delete-all-local-data action. A later private-content product needs account separation and an explicit shared-device policy.

**Download state:** absent → downloading → verifying → ready; with paused, failed and deleting paths. Download to a new package version. Verify every file's size/hash. Only then change the active-version record in one IndexedDB transaction. Cache Storage and IndexedDB do not share a transaction: therefore a ready record is the commit marker, and startup checks/cleanup repair incomplete staging data. Preserve the old playable version until the new one is verified. Never show “Downloaded” because only the service-worker app shell is ready.

**Small-package recovery:** split narration at concept boundaries. Resume at completed-file granularity first; re-fetch one incomplete small file after a connection break. Do not promise byte-range-resumable background downloads in all browsers. Put download progress, bytes remaining, cancel and retry on screen. For native later, use resumable file transfers with checksum verification, scoped temporary files and atomic promotion.

**Storage controls:** initial app-shell target at most 2 MB compressed excluding lesson audio; core demo package target below 10 MB. One figure target below 300 KB, with separate full-resolution opt-in only when necessary. Pilot default local allowance 200 MB, adjustable. Display real bytes before download. Keep pinned/in-use lessons; suggest removal of oldest unpinned lessons; do not erase without visible policy. Leave space for both old and new versions during updates. Handle quota errors with a useful message and preserve working lessons. Request persistent storage after the user asks to download, then report whether it was granted.

**Data claim:** say “Play downloaded lessons without mobile data.” First installation, source upload, cloud conversion, language/voice download, lesson download, sync and updates can consume data. A bundled loop removes repeated video transfer; it does not make narration or all app operations free. In strict offline mode, block all network fallback, remote fonts, analytics, crash uploads, update checks and remote narration. Show “Download required” for a missing asset. Test the build with flight mode and with network-request capture; cached playback alone is insufficient evidence of silence. A PWA/browser may perform its own maintenance outside app control, so limit the product claim to lesson playback traffic and the tested environment.

**Playback:** audio position is the time source. On seek/speed change/resume, choose the transcript/figure cue from that position. Do not advance diagrams from independent wall-clock timers. Use paragraph cues in the demo; add sentence cues later. Stop at the end of a concept file and show the quiz before starting the next. Persist position on pause, navigation and periodic coarse checkpoints. Save quiz submission before moving forward. Handle headphone removal, call interruption, app hiding and errors. If playback is interrupted, show a stable resume action. Do not promise uninterrupted playback after the browser or OS kills the app.

**Accessibility and motion:** default to a quiet static background, with a visible optional gentle-motion switch. Honour reduced-motion preference; provide transcript-only and audio-only modes. A figure spotlight stops/dims motion, supports zoom, offers a caption and returns focus predictably. Use proper button names, reading order, contrast and large touch controls. Avoid flashing, fast cuts, rapid scroll and decorative screen-reader announcements. Continuous screen-on animation costs energy; pause it when hidden or at a checkpoint. Test a 15-minute session on a low-memory phone. No universal battery percentage is claimed.

**Visual choice:** use a simple local CSS/SVG loop, not a WebGL world. A short local MP4 may be suitable after device decoding/battery tests; it adds initial bytes. Lottie adds a renderer and asset complexity. Neither “vector” nor “low-poly” proves low energy use. Native later can use a simple native/UI-thread animation; keep a static fallback. Do not stream a background video service.

**Update policy:** offer updates when the learner is idle. Never force a new service worker/app reload in the middle of a quiz. A corrupt package returns to download-needed status. Keep a compatibility range for the package format and reject unsupported versions with explanation. [Vite PWA update prompt](https://vite-pwa-org.netlify.app/guide/prompt-for-update.html)

### Gaps

- Performance, cache size, speech availability, memory, battery, headphones and force-close behavior require physical-device evidence. No such test was run in this research task.
- iOS background audio and long-term PWA persistence are outside the guaranteed demo scope. Native delivery remains the route for stronger media integration.
- A quiet motion design is a product hypothesis. It does not establish a treatment benefit for ADHD.

## 3. What tools are actually needed?

### Takeaway

Use a small tool set. Every added service consumes setup time, creates failure paths and can create bills. Most production tools are deferred until user-uploaded private content and a funded pilot exist.

### Cited Findings

Core repository licences were checked read-only with GitHub CLI on 10 October 2026: React, Vite, vite-plugin-pwa, Workbox and Vitest: MIT. TypeScript, Dexie and Playwright: Apache-2.0. Expo and FastAPI: MIT. These are top-level licences; transitive dependencies and media licences still need review at the selected versions. Sources: [React licence metadata](https://api.github.com/repos/facebook/react/license), [Vite](https://api.github.com/repos/vitejs/vite/license), [Vite PWA](https://api.github.com/repos/vite-pwa/vite-plugin-pwa/license), [Workbox](https://api.github.com/repos/GoogleChrome/workbox/license), [Vitest](https://api.github.com/repos/vitest-dev/vitest/license), [TypeScript](https://api.github.com/repos/microsoft/TypeScript/license), [Dexie](https://api.github.com/repos/dexie/Dexie.js/license), [Playwright](https://api.github.com/repos/microsoft/playwright/license), [Expo](https://api.github.com/repos/expo/expo/license), [FastAPI](https://api.github.com/repos/tiangolo/fastapi/license).

Playwright has an offline browser-context option and device emulation options. Emulation does not replace physical Android tests. [Playwright configuration](https://playwright.dev/docs/test-use-options)

WCAG 2.2 includes requirements concerning text alternatives, keyboard access, contrast, audio controls and moving content. Use it as the design/test baseline, with actual TalkBack checks. [WCAG 2.2](https://www.w3.org/TR/WCAG22/)

### Inferences

| Tool | Purpose and ownership | Cost/licence model | Alternative or trigger |
| --- | --- | --- | --- |
| Codex Plus, existing editor | Research/build/review aid; human checks outputs | Existing subscription; usage limits still apply | Work in small verified tasks; no consumer subscription forwarding into a public API |
| Node 24, npm, TypeScript | Build and typed data validation | Local tooling; TypeScript Apache-2.0 | Use existing runtime; pin dependencies; do not change runtime needlessly |
| React + Vite | Mobile web screens and static build | MIT | Plain web components if simpler; no need for Next.js/server rendering |
| vite-plugin-pwa + Workbox | App-shell cache, update flow, full media cache | MIT | Custom service worker only when needed; plugin defaults still require verification |
| Dexie + IndexedDB | Transactional local progress and package index | Apache-2.0 | Browser IndexedDB directly; native SQLite later |
| Browser audio + optional Web Speech | Local recorded playback; tested device narration | Browser API; voice/content rights separate | Native Expo audio later; no required cloud TTS |
| CSS/SVG and local assets | Quiet optional visual motion and figure display | App-owned assets; preserve source licences | MP4/Lottie only after measured benefit; no game engine |
| Own microphone recorder | Record demonstration narration | Existing hardware/software; narrator owns/permits recording | Cloud TTS only with separate budget and rights check |
| Git + hosted source repository | Reproducible versions and rollback | Existing/free repository plan subject to host terms | Keep private where source rights require; do not commit private documents or secrets |
| Cloudflare Pages | HTTPS demo URL and static assets | Hosted free tier, quotas/terms apply | Another static HTTPS host; no domain purchase required |
| Vitest | Package validation, quiz scoring and state transitions | MIT | Avoid low-value snapshots; test actual errors and recovery |
| Playwright + Chrome DevTools | Flow tests, network evidence, cache/offline checks | Playwright Apache-2.0; local browser tools | Physical phone remains required |
| TalkBack + physical Android phone | Screen reader, tap/scroll, audio and flight-mode proof | Device feature; existing phone assumed | Borrow test device; desktop emulation alone is insufficient |
| Expo development builds | Later native Android app and media service | Expo MIT; optional cloud build billing | Local Android builds are available; no paid EAS assumption |
| FastAPI + Python worker | Later upload/job API and content processing | FastAPI MIT; hosted compute paid | A short serverless text request only for optional live demo; durable work in a worker |
| Supabase Postgres/Auth/Storage/Queues | Later managed identity, data, private files and durable jobs | Hosted plan; database/extension licences and terms separate | Render Postgres + own identity provider only if a clear need justifies extra operations |
| Render | Later API/worker hosting | Hosted compute/workspace billing | Container-compatible cloud; benchmark before sizing |
| Sentry and OpenTelemetry | Later crashes, traces and operational measures | Hosted monitoring plan plus open instrumentation | Keep demo diagnosis local; add scrubbing and offline queue controls before telemetry |
| PDF/OCR/LLM/TTS tools | Later conversion and multilingual voice | See document research; provider/model terms differ | No dependency on live models for the core demo |

No Figma, paid stock loops, video generation, vector database, Redis, Kubernetes, Kafka, Terraform, remote feature flag system or real-time subscription service is necessary for the three-day result. For one deployment, a documented dashboard setup is sufficient; record it so infrastructure configuration can be versioned later.

### Gaps

- Runtime versions and package compatibility must be pinned at build start, with lockfile and licence notices. Top-level licence discovery is not a complete legal audit.
- No cloud account, custom domain, microphone, test phone, or monitoring subscription was inspected or purchased.

## 4. What architecture should follow a successful demo?

### Takeaway

Use an Android app with local lesson packages and a small modular backend. Select React Native/Expo development builds, a FastAPI service, one Python worker and managed Supabase Postgres/Auth/private Storage/Queues. Keep one domain model and a single database. Introduce more infrastructure only when measured load or residency requirements demand it.

### Cited Findings

- Expo development builds allow native libraries and configuration. Local native builds are possible; paid cloud builds are optional. Expo audio documents Android foreground media playback and lock-screen controls. SQLite can persist app state; SecureStore provides protected small-value storage with platform-specific backup/uninstall behavior. [Expo development builds](https://docs.expo.dev/develop/development-builds/introduction/), [Expo audio](https://docs.expo.dev/versions/latest/sdk/audio/), [Expo SQLite](https://docs.expo.dev/versions/latest/sdk/sqlite/), [Expo SecureStore](https://docs.expo.dev/versions/latest/sdk/securestore/)
- FastAPI advises a separate worker/queue for heavier background work. Render workers run continuously and consume queued work without receiving public requests. [FastAPI background tasks](https://fastapi.tiangolo.com/tutorial/background-tasks/), [Render workers](https://render.com/docs/background-workers)
- Supabase Queues uses Postgres/pgmq with durable messages and a visibility window. This does not imply that an external model call has exactly-once effects. [Supabase Queues](https://supabase.com/docs/guides/queues)
- Supabase supports JWT verification and row-level policies. A privileged service credential can bypass ordinary user policies and therefore must stay server-side. [Supabase JWTs](https://supabase.com/docs/guides/auth/jwts), [Row Level Security](https://supabase.com/docs/guides/database/postgres/row-level-security)
- Firestore provides useful offline persistence but resolves multiple changes to the same document with last-write-wins. That alone does not preserve a complete quiz-attempt history or cache media files. [Firestore offline data](https://firebase.google.com/docs/firestore/manage-data/enable-offline)
- Supabase Edge Functions have execution/resource limits. Large OCR/PDF work belongs in a bounded worker with explicit failure handling, not a mobile request waiting for a function. [Function limits](https://supabase.com/docs/guides/functions/limits)

### Inferences

**Conceptual flow:** mobile upload request → authenticated API → private quarantine storage → durable generation job → isolated parser → structured concept/quiz generation → validation and review → speech/assets → immutable package → user-approved download → local playback → later event sync. This is a design description, not an implementation diagram or code.

**Service boundaries:** API owns authentication enforcement, quotas, job creation, signed upload/download grants, status, sync and deletion. Worker owns expensive parsing, generation, quality checks, audio, packaging and cleanup. Shared Postgres stores state. Private object storage holds input/output files. pgmq avoids a separate Redis broker at pilot scale. Store large assets outside database rows. Do not let a mobile client submit arbitrary queue commands or hold service-role keys.

**Why this blend:** Supabase removes the need to build login, managed Postgres and private object-storage access from scratch. FastAPI/worker preserves Python parser support and control over long jobs. Firebase is a valid simpler real-time option, but package provenance, job state and append-only learning events fit relational modelling well. Microservices add deployment and consistency work before proven demand.

**Entities:** account; device; consent record; document; source version; source block and figure; generation job and stage attempt; concept; lesson package version; media asset; question version; quiz attempt; learning event; resume cursor; download entitlement; deletion request; audit record. Store model/parser/prompt/voice versions and source hashes to reproduce output. Every private row/file belongs to an owner. Add organisation/membership/role only when institutional use is selected.

**API contracts:** initiate/complete upload; request conversion with idempotency key; read/cancel job; list owned sources/lessons; fetch authorised package manifest; obtain short-lived signed asset access; submit batched learning events; get changes after a server cursor; delete source/package/account; report unsafe or wrong generated content. Version the contract, return stable machine-readable errors and retry guidance, and enforce ownership on each object access. Poll jobs with backoff; real-time sockets are unnecessary initially.

**Job correctness:** persist state before dispatch. Stage key combines owner, source hash, content/prompt version and output language. Check existing completed stages before paying again. Use leases, heartbeats, bounded attempts, exponential retry and dead-letter/manual inspection. Do not treat a timeout as proof that a provider did no work. Persist provider request IDs and reconcile ambiguous outcomes. Set per-source page/byte/time/token/audio-minute limits. Keep one parser job at a time until memory is measured. Cancel future work and remove temporary files. Publish only after the whole package passes checks.

**Native local state:** SQLite stores package index and an event outbox; files stay in app-private durable storage, not the OS disposable cache. Keep refresh tokens and content keys in platform secure storage. If private documents are accepted, encrypt sensitive database/file contents with vetted platform/SQLCipher tooling and test key backup/reset behavior. Do not invent cryptography or call an unencrypted app sandbox “encrypted content.”

**Sync policy:** every event has a random unique ID, actor/device, monotonic device sequence, package version, local time and server receipt time. Server deduplicates by ID. Quiz attempts are append-only, including retries and revised questions. Derived completion can be monotonic within one lesson version; it must not silently transfer to materially changed content. Resume cursor is separate from completion: keep device cursors and offer a sensible most-recent server-received choice; do not rely only on untrusted device clocks. Deletions use tombstones so old clients cannot recreate deleted records. Download assets only when requested. Batch small events on explicit sync or allowed unmetered connectivity.

**Account/offline policy:** public demo lessons have no expiry. A later private lesson may have a signed, disclosed offline access window, initially 30 days for ordinary personal study unless source rights require less. Local access expiry and API-token expiry are different. An expired network token must not destroy locally authorised lessons. Revoke server grants immediately online; disconnected devices cannot receive immediate revocation. A rooted/modified client or changed clock limits local enforcement. Do not sell this as DRM. On logout, ask to keep only public samples and remove private downloads, account data and keys. Unsynced attempts need a clear warning before deletion. Remote account deletion needs a local purge on next connection; existing disconnected copies cannot be magically erased.

### Gaps

- No post-hackathon funding, team capacity, institutional agreement, private-document policy, service-region approval or maximum upload target is confirmed.
- Worker memory, conversion latency, model cost and provider idempotency support require a representative corpus benchmark.
- A 30-day window is a proposed policy, not a legal requirement. Stronger rights controls may conflict with long offline access.

## 5. What safety and operational controls are needed before real uploads?

### Takeaway

Keep the demo public-content-only and local. Before real uploads, protect documents as private data, isolate parsing, enforce ownership, test deletion and recovery, and cap generation spend. Do not defer those controls until after public access.

### Cited Findings

- OWASP recommends upload allowlists, size limits, server-generated names, content checks, restricted storage and scanning. A file extension alone is insufficient. [OWASP file upload](https://cheatsheetseries.owasp.org/cheatsheets/File_Upload_Cheat_Sheet.html)
- Server-side URL fetching creates SSRF risk; validate destinations and constrain outbound access. [OWASP SSRF prevention](https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html)
- Uploaded or retrieved text may carry prompt-injection instructions. Treat it as data; separate it from trusted instructions and restrict tool powers. [OWASP prompt injection](https://cheatsheetseries.owasp.org/cheatsheets/LLM_Prompt_Injection_Prevention_Cheat_Sheet.html)
- Render offers Frankfurt among its regions. Supabase offers the specific Frankfurt AWS region. Region choice alone does not establish legal compliance; Supabase explicitly notes that general Europe is not a promise of one jurisdiction. [Render regions](https://render.com/docs/regions), [Supabase regions](https://supabase.com/docs/guides/platform/regions)
- Supabase Pro daily database backups retain seven days, but database backups do not include stored file objects. [Supabase backups](https://supabase.com/docs/guides/platform/backups)
- OpenTelemetry provides vendor-neutral traces, metrics and logs; it is not itself the storage/analysis backend. [OpenTelemetry](https://opentelemetry.io/docs/what-is-opentelemetry/)

### Inferences

**Upload boundary:** authenticated, per-user byte/page quotas; approved MIME plus signature checks; quarantine; malware scan; bounded parsing in a non-root sandbox with CPU, memory, wall-time and temporary-disk limits. Reject encrypted, malformed or unsupported files with an explanation. Do not execute document scripts, follow embedded links, fetch remote images or allow a parser unrestricted internet access. Scan is one layer, not proof of safety. Keep model generation tools disabled for untrusted source content; schema validation and source-grounding checks still apply.

**URL import:** exclude from demo. Later, separate fetch service must block loopback, link-local, private/internal IPv4/IPv6 and cloud metadata destinations, recheck redirects/DNS changes, enforce HTTP(S), timeout and response-byte limits, and prevent arbitrary credentials. Respect source rights/access restrictions. The feature should fail closed.

**Access:** server derives user identity from verified credentials; it never trusts a caller-supplied owner ID. RLS is defence in depth, not a substitute for API checks. Test account A against every account B record, source object, manifest, figure, job and signed-link creation path. Signed URLs are short-lived bearer credentials: keep them out of logs; expiry does not retract files already downloaded. Admin actions need separate roles and audit records. App-integrity checks may reduce abuse later, but cannot replace authorisation or be required for offline playback.

**Secrets and delivery:** no provider keys in app bundles, source repositories or logs. Use hosted secret storage, least privilege, rotation and separate environments. Each change passes review plus unit/contract/device checks appropriate to risk. Use reproducible container builds for the server and immutable releases. Run backward-compatible expand/migrate/contract database changes; take backups first; roll back app/worker releases, not arbitrary destructive database migrations. Pin package-format compatibility so an old offline app can still read its downloads.

**Location:** provisional pilot region is exact Frankfurt for API, worker, database and private primary storage. Measure Nigerian mobile latency before purchase; proximity on a map is not a network benchmark. This is cross-border processing from Nigeria. Agreements, subprocessors, model providers, backups, CDN caches and logs need their own data-location review. If Nigeria-only storage is mandatory, this selection changes. No legal adequacy conclusion is made here.

**Recovery objectives:** pilot proposal: at most 24 hours of server-side data loss after a disaster; recovery within 8 hours. These are targets until a restore exercise proves them. Use daily database backup plus separately backed-up/versioned objects and periodic restore drills. A DB-only restore can leave missing audio/source assets. Once paying institutions need tighter recovery, select PITR and an independently tested object recovery plan. Verify deletion retention across primary storage, worker temporaries, logs and backups.

**Measures:** queue age, stage failures, retry count, dead-letter count, generation minutes and spend, package failure rate, sync duplicates, rejected cross-user access, download bytes, crashes and audio interruption. Record IDs and timings; exclude document bodies, answer text, credentials and signed URLs by default. Disable third-party session replay on private content. A network-silent mode queues or suppresses telemetry. Alert on sustained job failure, budget thresholds and storage growth; never silently retry expensive generation without bounds.

### Gaps

- Formal Nigeria data-protection analysis is handled in the product/legal research, not resolved by selecting an EU region.
- No scanner, backup, restore, monitoring, cross-user policy, or penetration test was executed.
- App-integrity support and native SDK requirements must be checked when the native release is planned.

## 6. What will it cost, and what proves completion?

### Takeaway

The chosen hackathon path can require zero additional service spend under the stated existing-resource assumptions. A later private-upload pilot has real infrastructure, AI and operating costs. A working demo is not a production release or evidence of improved retention.

### Cited Findings

- Render lists 512 MB service compute at $7/month, 2 GB/1 CPU at $25 and 4 GB/2 CPU at $85. Workspace billing is separate; Pro is $25/month. [Render pricing](https://render.com/pricing)
- Supabase Pro starts at $25/month, including one Micro project via compute credit, 8 GB database, 100 GB file storage and 250 GB egress, with additional cached-egress allocation. Overages and other features can add fees. [Supabase pricing](https://supabase.com/pricing)
- R2 Standard lists $0.015/GB-month, free internet egress, $4.50/million Class A operations and $0.36/million Class B operations, with a free allowance. It is a later bandwidth alternative, not a required demo service. [R2 pricing](https://developers.cloudflare.com/r2/pricing/)
- Google Play registration is a one-time US$25 fee. New personal accounts covered by its testing policy need at least 12 opted-in testers for 14 continuous days before applying for production access. [Play registration](https://support.google.com/googleplay/android-developer/answer/6112435?hl=en), [Closed-test requirements](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en)
- Current Google Play policy requires new apps/updates to target Android 16/API 36 from 31 August 2026. Target SDK is not minimum supported OS. [Target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en)
- Google requires accurate privacy/data-safety information; apps that create accounts must offer account-deletion requests in-app and through a web route. Apple's developer programme is $99 annually. These are later native-release considerations. [Google account deletion](https://support.google.com/googleplay/android-developer/answer/13327111?hl=en), [Google user-data policy](https://support.google.com/googleplay/android-developer/answer/18258653), [Apple Developer Programme](https://developer.apple.com/programs/)

### Inferences

**Data arithmetic, not a market statistic:** 32 kb/s mono narration uses 32,000 × 60 ÷ 8 = 240,000 bytes/minute, or 0.24 decimal MB/minute before container overhead. Ten minutes uses 2.4 MB. At 48 kb/s it is 3.6 MB; at 64 kb/s, 4.8 MB. Choose a supported compressed speech format after listening tests; do not assume 32 kb/s always sounds good. A 30-second loop encoded at 200 kb/s costs 0.75 MB once per installed version, not once per replay. Keeping motion local does not remove the 2.4–4.8 MB narration download. Figures, original PDF, app shell and protocol overhead are additional.

**Illustrative lesson budget:** allow 6 MB for a ten-minute package, including 48 kb/s audio, compressed figures and text, excluding the original PDF and one-time app shell. Twenty new packages/user/month cost about 120 MB/user/month downloaded once. At 100 users that is 12 GB; 1,000 users is 120 GB. Replaying downloaded content adds no lesson transfer. Reinstalls, retries, multiple devices and repeated downloads add usage. If every package is private/unique and retained for three months, storage is about 36 GB for 100 users or 360 GB for 1,000. Shared public lessons reduce storage and generation substantially; cross-user private deduplication must not leak ownership.

| Scenario | Illustrative recurring platform cost | What is excluded |
| --- | --- | --- |
| Three-day prepared-content PWA | $0 additional services on selected free static tier | Existing Plus, laptop/phone, connectivity, labour, domain if chosen |
| Later 100-user private-upload pilot | $142/month baseline: Render Pro $25 + API $7 + 4 GB worker $85 + Supabase Pro $25 | Models, speech, mail, monitoring, separate file backup, tax, labour |
| Later 1,000-user scenario | Same $142 base only if measured capacity is sufficient; 360 GB stored adds about $5.54 above Supabase's 100 GB allowance | More worker/database capacity may be needed; not a load guarantee |

The $142 example deliberately reserves a 4 GB worker; parsing must prove it is enough. It also includes optional Render Pro for a later managed pilot with isolated environments and team controls. Pro is not required for this solo static demo. A pilot using the $0 workspace tier would have a $117 compute/data base, with its workspace feature limits accepted. A light-text worker might fit 2 GB, reducing those totals to $82 or $57 respectively. If the API needs 2 GB, add $18. The number of users alone cannot size conversion throughput. For 1,000 users each generating twenty ten-minute packages, there are 20,000 conversions and 200,000 speech minutes per month. Multiply those volumes by the selected provider's actual costs and measured retry rate. This may dominate hosting cost. The document research gives provider scenarios; do not substitute subscription price for API spend.

Use a formula for a funded pilot: fixed platform + source pages × parsing/OCR rate + input/output model tokens × token rates + narration minutes or characters × voice rate + billable storage/transfer + email/observability/backup + tax. Keep human review/support cost separate. Proposed first pilot limit: 3 new documents/user/month, capped pages and minutes, not the generous twenty-package sizing scenario. Use per-account concurrency limits, daily global budget ceilings, alerts at 50/80/100%, and a generation pause that leaves existing downloads usable. Provider dashboards may not stop expenditure at the exact threshold; application-side quota reservation is needed.

**Three-day plan:**

| Day | Focus, about 6–8 hours | End-of-day gate and cut line |
| --- | --- | --- |
| 1 | Confirm source rights and script; prepare three concepts, figure and questions; set visual style; create a working lesson/player; record/check narration; establish HTTPS preview early. | One complete source-to-lesson example plays on the target phone. If setup/audio takes too long, freeze import and use prepared package plus own recording. |
| 2 | Add recall feedback, source spotlight, progress/resume, explicit downloads, verified package-ready state, service-worker cache, quota failure, motion controls and reading mode. | Flight-mode reload plays the whole package and quiz. If it fails, cut all stretch work. Do not replace this gate with a spinner or a planned feature. |
| 3 | Test actual phone, narrow screens, TalkBack, network silence, interrupted download, resume and deletion; fix defects; record backup video; rehearse two-minute demo and limitations. | Freeze features early. Spend remaining time on defects and evidence. Reserve 3–4 of the total 18–24 hours for unknowns and demo preparation. |

**Demo script:** show the original dense page; open the concept map; start a short narrated lesson; switch motion off/on; spotlight the source figure; answer a checkpoint; show the explanation/source; download; turn on flight mode; reopen and resume; explain that arbitrary document generation and native distribution are future stages. Preload and test the exact presentation phone and browser before arriving. Backup video must be labelled a recording. No deceptive processing animation for precomputed content.

**Definition of demo done:** one reviewed document, at least three coherent concepts, reliable recorded narration or verified device voice, transcript/source references, at least one figure, working quiz feedback, actual offline reopen/play/resume, usable 360–430 px layout, readable controls, reduced motion, clear deletion, and a reproducible demo build. Five small automated tests for package validity/state/scoring plus one full browser flow are more valuable than a large superficial suite. Browser tests must include range-seek playback from cached audio and an incomplete download that never becomes ready.

**Later pilot gates:** English content benchmark; reviewed factuality/source coverage; real low-memory device tests; accessibility review; duplicate/retried jobs; corrupt packages; disk-full; source deletion; force-stop/reboot; clock skew; expired token; account switch/logout; cross-user access denial; append-only attempts; offline-to-online conflicts; realistic queue load; backup/object restore; metered-download opt-in. Test Android versions and manufacturers based on recruited users, including a low-memory 2 GB device if supported. Native minimum OS must follow the chosen Expo SDK and target audience; do not promise all old Android phones.

**Release order:** demo → user/problem evaluation → limited funded native/processing pilot → measured public beta → store launch. Do not assign a guaranteed production date before measuring document quality and securing budget. A solo builder could spend many additional weeks on the private-upload system; the earlier multi-person 10–12-week idea is not a commitment for this user. Track store testing and verification as separate calendar dependencies. No public store release is promised within three days.

### Gaps

- All workload amounts are explicit scenarios, not forecasts. No Nigerian data tariff, exchange rate, salaries or user willingness to pay was invented.
- Hosting accounts may need identity/payment verification even when a free service is selected. Exact free-tier eligibility was not tested.
- No completion gate has passed yet: this task produced research only.
