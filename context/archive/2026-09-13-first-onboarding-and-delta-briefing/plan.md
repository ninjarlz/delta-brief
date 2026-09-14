# First Onboarding and Delta Briefing Implementation Plan

## Overview

Build the `briefing` bounded-context module end-to-end: ingest each topic's preset RSS/Atom sources, persist what was fetched, generate a structured onboarding or delta briefing via OpenAI (through Spring AI's `ChatClient`), and let the user trigger generation and read the result in the app. This is roadmap slice **S-03**, explicitly the "go/no-go slice for the whole product" — the first place DeltaBrief's core value (separating genuine change from noise) actually gets built and tested against real content.

## Current State Analysis

- No `briefing` package exists yet. Only `auth` and `topic` are built.
- `topic.domain.Source` (feed URL, name, categoryId) is fully modeled and DB-seeded with 8 real RSS URLs across 4 categories (`src/main/resources/db/migration/V6__seed_categories_and_sources.sql`) but has **no repository/port anywhere** — nothing reads it.
- Spring AI 2.0.0 (`spring-ai-starter-model-openai`) is a wired dependency with a placeholder API key (`application.properties:42`) but **zero code calls it**.
- No RSS/Atom parsing library, no HTTP fetch client for feeds, no `@Scheduled` job, no "content item" concept exists.
- No HTMX wiring exists despite `tech-stack.md` naming it as the eventual plan for live generation feedback — no script include, no dependency, no `hx-*` attribute anywhere.
- `auth` and `topic` establish a firm, consistent convention: `domain` → `application` (+`application/port/out`) → `adapter.in.web` / `adapter.out.persistence`; MapStruct mapping; Lombok fluent accessors on aggregates; `@Controller`-only (never `@RestController`); redirect-after-POST; owner-scoped queries pushed into JPA repository methods (e.g. `TopicRepository.deleteByIdAndUserId`); `<Unit>Tests` naming; `@SpringBootTest` + Testcontainers Postgres (no mocking framework used anywhere yet); one `*FlowIntegrationTests` MockMvc suite per module.
- AGENTS.md's cross-module rule is explicit and, so far, unbroken: "modules reference each other by ID only ... never by importing another module's `domain` aggregate." `topic` only ever holds `auth.domain.UserId`, never `auth.domain.User`.
- Spring AI's OpenAI autoconfiguration module (confirmed directly against its `v2.0.0` source tree) registers **no** Actuator health indicator — the AI-equivalent of the mail-health-indicator incident this repo already hit and fixed does not apply here; nothing to guard against.
- `infrastructure.md` steers toward in-process, synchronous work over background jobs (Render Cron Jobs can't share the Spring context/Hikari pool/AI client) and flags LLM API cost as the one real, metered budget risk (~$2/mo on a mini-class model vs. ~$25-40/mo on a flagship model at MVP volume).

Full detail: `context/changes/first-onboarding-and-delta-briefing/research.md`.

## Desired End State

A logged-in user, viewing their topics list, can click "Generate briefing" on any topic. The app fetches that topic's category's sources, generates either an onboarding briefing (first time) or a delta briefing (subsequent times) via OpenAI, persists it, and redirects to a page showing all 7 structured sections (key changes, trend continuation, noise/speculation, significance, uncertainties, source impact on scenarios, sources) plus a small clickable list of that topic's past briefings. If a source is unreachable, generation proceeds with the rest and notes what was skipped. If the AI call itself fails, the user sees a clear error with a retry action.

**Verification**: `./gradlew build` passes; `BriefingFlowIntegrationTests` covers generate → view → history → cross-user isolation; a real generation run against a live topic and live OpenAI produces a plausible, non-fabricated 7-section briefing (manual, Phase 3/4 gates).

### Key Discoveries:

- `topic.domain.Source` javadoc (`topic/domain/Source.java:7-13`) literally says ingestion "arrives with briefing generation" — this slice is its intended consumer, confirming the data model was built with this in mind.
- Spring AI 2.0's `ChatClient` exposes structured output directly via `.call().entity(SomeRecord.class)`, backed by `BeanOutputConverter` (JSON-schema generation + prompt augmentation + deserialization) — this is the mechanism this plan uses to get the 7-section response back as a typed Java record instead of parsing free text. ([Spring AI 2.0 structured-output docs](https://docs.spring.io/spring-ai/reference/2.0-SNAPSHOT/api/structured-output.html))
- `com.rometools:rome:1.7.2` is the current, actively maintained RSS/Atom parsing library for the JVM ([Maven Central](https://central.sonatype.com/artifact/com.rometools/rome)) — chosen over hand-rolled XML parsing for correctness across the mixed RSS/Atom feed versions this app's preset sources actually use.
- `org.wiremock.integrations:wiremock-spring-boot:4.2.2` provides `@EnableWireMock`, which exposes a `wiremock.server.baseUrl` property automatically injectable into a `@SpringBootTest` — used to stub both the RSS fetch calls and the OpenAI chat-completions call in tests, so the automated suite never makes a real network call to either. Spring AI's OpenAI client respects `spring.ai.openai.base-url` as a standard override point, which is what makes stubbing the AI call practical here.

## What We're NOT Doing

- Scheduled/automatic briefing generation (S-04) — this slice is manual-trigger only.
- Email delivery (S-06) or rating (S-07).
- A full browsable briefing history page (S-05) — this slice ships only a minimal inline list of past briefings alongside the latest one; S-05 owns the real history experience.
- Per-topic source customization (parked in the roadmap) — sources are still resolved via the topic's whole category.
- Real HTMX/SSE live progress during generation — this slice uses a plain synchronous POST + redirect; the "please wait" experience relies on the existing submit-button-disable script.
- Automatic retry-with-backoff on AI call failure — failures surface to the user with a manual retry action only.
- Any model-upgrade automation or A/B mechanism — the model is a fixed config value (`spring.ai.openai.chat.options.model`), changed by hand if quality warrants it later.
- Citation-level structured linking between specific sentences and specific sources — traceability is provided by a full numbered source list plus a prompt instruction to cite by number, not per-sentence structured references.
- An `IngestedItem`/`Briefing` public repository/port for other modules to consume — nothing downstream needs this yet.

## Implementation Approach

Four phases, each independently buildable and testable, following the existing `domain → persistence → business logic → web` ordering this codebase already uses for `topic`:

1. **Domain & persistence foundation** — model `Briefing` (aggregate root) with `IngestedItem` as a child entity within its boundary (not a separate top-level aggregate/repository — it has no independent lifecycle), wire the long-dormant `Source` data path via a `briefing`-owned read model, and extend `TopicRepository` with an ownership+category lookup.
2. **Content ingestion** — a Rome-based fetch adapter behind a port, tolerant of individual source failures.
3. **AI generation** — the Spring AI `ChatClient` call behind a port, with the anti-hallucination prompt design and structured-output mapping.
4. **Orchestration & web** — the `BriefingService` that ties the above together, plus the controller, templates, and integration tests.

## Critical Implementation Details

### Cross-module read access without importing `topic`'s domain aggregates

`briefing` needs two things from `topic`: (a) whether a given topic belongs to the current user and what its `categoryId` is, and (b) the list of sources for a category. Neither is a plain ID pass-through like `UserId`, so this plan resolves both without ever letting `briefing.domain`/`briefing.application` import `topic.domain.Topic` or `topic.domain.Source`:

- **Ownership + category lookup**: add `TopicRepository.findCategoryIdByIdAndUserId(TopicId, UserId): Optional<CategoryId>` to `topic`'s existing port (mirrors the already-established owner-scoped-query convention, e.g. `deleteByIdAndUserId`). `briefing`'s web/application layers only ever receive a `CategoryId` — `topic.domain.CategoryId` is an ID-record type, reused exactly the way `UserId` already crosses into `topic` today. `Topic` itself is never touched outside `topic`'s own adapter.
- **Source catalog**: `briefing` defines its **own** port, `application/port/out/FeedSourceCatalog#findByCategoryId(CategoryId): List<FeedSource>`, where `FeedSource(String name, String feedUrl)` is a `briefing`-owned record — not `topic.domain.Source`. The adapter (`briefing/adapter/out/persistence/FeedSourceCatalogAdapter`) reads the same physical `sources` table directly through its own lightweight, `briefing`-owned JPA mapping. This duplicates a thin read-only view of a small, migration-seeded reference table — the standard way modular monoliths avoid cross-module aggregate coupling, and cheap here since `sources` has 4 columns and never changes at runtime.

### Ingested items are a child entity of `Briefing`, not their own aggregate

`IngestedItem` (title, link, publishedAt, fetchedAt, source name) has no lifecycle independent of the `Briefing` it belongs to — it's always created, persisted, and loaded together with its parent. `Briefing.ingestedItems()` is an immutable list set at construction; `BriefingRepository.save(Briefing)` persists both tables; there is no separate `IngestedItemRepository`. This also means the "Sources" section of FR-010's template is rendered directly from `Briefing.ingestedItems()`, not a separate query.

### Anti-hallucination via numbered citation, not per-sentence structured links

The prompt lists every ingested item with a number (`[1] <title> — <link>`), instructs the model to write each of the six narrative sections using only that numbered list, and to cite claims inline as `[n]`. The "Sources" section of the rendered briefing is simply that same numbered list, rendered from the persisted `IngestedItem`s — not something the model has to also emit. This keeps the guardrail concrete (every ingested item is transparently listed and cross-checkable) without requiring a more complex per-claim citation schema in the structured output.

### Ingested items are capped per source

Uncapped feed items would make prompt size (and therefore cost and latency) unbounded. Fetch and pass at most the 10 most recent items per source into the prompt — generous for the "since last briefing" window at any realistic generation cadence, and keeps cost predictable.

### WireMock stubbing points

- **RSS fetch tests**: `SourceContentFetcher.fetch(FeedSource)` takes the feed URL as a plain value — tests construct a `FeedSource` pointing directly at `${wiremock.server.baseUrl}/feed.xml` and stub that path. No database involved for this adapter's tests.
- **OpenAI tests**: override `spring.ai.openai.base-url=${wiremock.server.baseUrl}` in the test's `@SpringBootTest(properties = ...)` and stub the chat-completions endpoint response shape.
- **`FeedSourceCatalogAdapter` tests**: these read the real `sources` table (Testcontainers Postgres, V6-seeded) — no WireMock needed here at all, it's a plain DB read.

### Synchronous generation and request threads

Generation runs inside one HTTP request (per this plan's chosen UX). At this app's expected scale (PRD `target_scale.qps: low`, solo/testing phase), Tomcat's default thread pool comfortably absorbs an occasional 10-30s request — no async/queueing infrastructure is needed for this slice. Revisit only if S-04's scheduling or real usage proves otherwise.

## Phase 1: Domain & Persistence Foundation

### Overview

Model `Briefing` (with `IngestedItem` as its child entity) and stand up the two new tables, plus extend `topic.TopicRepository` with the ownership+category lookup this module needs. No ingestion, no AI, no web yet — this phase is pure data model.

### Changes Required:

#### 1. `Briefing` and `IngestedItem` domain

**Files**:
- `src/main/java/pl/tul/deltabrief/briefing/domain/BriefingId.java`
- `src/main/java/pl/tul/deltabrief/briefing/domain/BriefingType.java`
- `src/main/java/pl/tul/deltabrief/briefing/domain/IngestedItem.java`
- `src/main/java/pl/tul/deltabrief/briefing/domain/Briefing.java`

**Intent**: `BriefingId` is a record ID type, following `TopicId`/`UserId`. `BriefingType` is an enum `{ONBOARDING, DELTA}`. `IngestedItem` is a plain immutable value (sourceName, link, publishedAt nullable, fetchedAt) — a child entity, not its own aggregate. `Briefing` is the aggregate root: `id`, `topicId` (topic module's `TopicId`, ID-only reference), `type`, `generatedAt`, the six narrative section texts (`keyChanges`, `trendContinuation`, `noiseSpeculation`, `significance`, `uncertainties`, `sourceImpact`), and `ingestedItems: List<IngestedItem>`.

**Contract**: `@Getter @Accessors(fluent = true)` on `Briefing`, matching `Topic`/`User`'s convention. Static factory `Briefing.generate(TopicId topicId, BriefingType type, Instant generatedAt, String keyChanges, ..., List<IngestedItem> ingestedItems)` constructs with `id=null`; `assignId(BriefingId)` sets it post-persist, called only from the adapter's `save()`, mirroring `Topic.assignId`. No `@Version`/`assignVersion` — a briefing is never concurrently edited after creation.

#### 2. `briefing`-owned `FeedSource` and cross-module port additions

**Files**:
- `src/main/java/pl/tul/deltabrief/briefing/application/port/out/FeedSource.java`
- `src/main/java/pl/tul/deltabrief/briefing/application/port/out/FeedSourceCatalog.java`
- `src/main/java/pl/tul/deltabrief/topic/application/port/out/TopicRepository.java` (edit)
- `src/main/java/pl/tul/deltabrief/topic/adapter/out/persistence/TopicRepositoryAdapter.java` (edit)
- `src/main/java/pl/tul/deltabrief/topic/adapter/out/persistence/TopicJpaRepository.java` (edit)

**Intent**: `FeedSource` is a `briefing`-owned record (`name`, `feedUrl`) — no dependency on `topic.domain.Source`. `FeedSourceCatalog` is the port `briefing`'s application layer will call for ingestion (implemented in Phase 2). `TopicRepository` gains `findCategoryIdByIdAndUserId(TopicId, UserId): Optional<CategoryId>` — see Critical Implementation Details above for why this is the chosen cross-module access shape.

**Contract**: `findCategoryIdByIdAndUserId` pushes the owner-scoped filter into the JPA query (a derived or `@Query` method on `TopicJpaRepository` selecting just `category_id` where `id = ?1 and user_id = ?2`), matching `deleteByIdAndUserId`'s existing pattern of not loading the full entity for an ownership-scoped operation.

#### 3. Persistence: `briefings` and `ingested_items` tables

**Files**:
- `src/main/resources/db/migration/V8__create_briefings_table.sql`
- `src/main/resources/db/migration/V9__create_ingested_items_table.sql`
- `src/main/java/pl/tul/deltabrief/briefing/adapter/out/persistence/BriefingJpaEntity.java`
- `src/main/java/pl/tul/deltabrief/briefing/adapter/out/persistence/IngestedItemJpaEntity.java`
- `src/main/java/pl/tul/deltabrief/briefing/adapter/out/persistence/BriefingEntityMapper.java`
- `src/main/java/pl/tul/deltabrief/briefing/adapter/out/persistence/BriefingJpaRepository.java`
- `src/main/java/pl/tul/deltabrief/briefing/adapter/out/persistence/BriefingSummary.java`
- `src/main/java/pl/tul/deltabrief/briefing/application/port/out/BriefingRepository.java`
- `src/main/java/pl/tul/deltabrief/briefing/adapter/out/persistence/BriefingRepositoryAdapter.java`

**Intent**: `V8` creates `briefings` (id, topic_id FK → topics, type, generated_at, and the six text section columns), with an index on `(topic_id, generated_at DESC)` for the latest/history queries. `V9` creates `ingested_items` (id, briefing_id FK → briefings **ON DELETE CASCADE**, source_name, link, published_at nullable, fetched_at) — `source_name` is a denormalized text copy at ingest time (not an FK to `topic.sources`), consistent with `briefing`'s decoupling from `topic`'s tables, with the side benefit that a briefing still shows accurate historical source names even if a source is later renamed. `BriefingJpaEntity`/`IngestedItemJpaEntity` follow the established `@Getter @NoArgsConstructor(access = PROTECTED) @AllArgsConstructor` pattern. `BriefingEntityMapper` is a MapStruct `@Mapper(componentModel = "spring")`, matching `TopicEntityMapper`'s approach to fluent-accessor aggregates. `BriefingRepository` exposes `save(Briefing): Briefing`, `findLatestByTopicId(TopicId): Optional<Briefing>` (full aggregate, needed both as delta-generation context and to determine "new since last"), `findByIdAndTopicId(BriefingId, TopicId): Optional<Briefing>` (full aggregate, for viewing one), and `findSummariesByTopicId(TopicId): List<BriefingSummary>` — a lightweight `(BriefingId, BriefingType, Instant generatedAt)` projection for the inline history list that does not hydrate ingested items.

### Success Criteria:

#### Automated Verification:

- `./gradlew build` compiles cleanly (migrations apply, entities/mappers wired)
- Domain unit tests pass: `./gradlew test --tests "pl.tul.deltabrief.briefing.domain.*"`
- `BriefingRepositoryAdapterTests` pass (Testcontainers Postgres)
- `TopicRepositoryAdapterTests` (extended) pass, covering `findCategoryIdByIdAndUserId`

#### Manual Verification:

- Run `./gradlew bootRun` locally and confirm via psql/DBeaver that `briefings` and `ingested_items` exist with the expected columns and the cascade FK

---

## Phase 2: Content Ingestion

### Overview

Fetch and parse RSS/Atom feeds for a topic's sources, tolerating individual source failures, and wire up `FeedSourceCatalog` against the real `sources` table.

### Changes Required:

#### 1. Rome dependency

**File**: `build.gradle`

**Intent**: Add `implementation 'com.rometools:rome:1.7.2'` for RSS/Atom parsing.

**Contract**: One new dependency line, same section as the other `implementation` entries.

#### 2. Content-fetch port and adapter

**Files**:
- `src/main/java/pl/tul/deltabrief/briefing/application/port/out/FetchedItem.java`
- `src/main/java/pl/tul/deltabrief/briefing/application/port/out/SourceContentFetcher.java`
- `src/main/java/pl/tul/deltabrief/briefing/adapter/out/rss/RomeSourceContentFetcher.java`

**Intent**: `FetchedItem` is a record (`title`, `link`, `publishedAt` nullable — not every feed entry carries a reliable publish date). `SourceContentFetcher#fetch(FeedSource): List<FetchedItem>` is the port; on any fetch/parse failure it throws a nested `SourceContentFetcher.SourceUnavailableException` rather than returning an empty list, so the caller can distinguish "genuinely no new items" from "couldn't reach this source" and handle each per the partial-failure decision (Phase 4). `RomeSourceContentFetcher` fetches the feed bytes and parses with Rome's `SyndFeedInput`, capping the result at the 10 most recent items (see Critical Implementation Details) sorted by published date descending where available.

#### 3. `FeedSourceCatalog` adapter

**Files**:
- `src/main/java/pl/tul/deltabrief/briefing/adapter/out/persistence/FeedSourceJpaEntity.java`
- `src/main/java/pl/tul/deltabrief/briefing/adapter/out/persistence/FeedSourceJpaRepository.java`
- `src/main/java/pl/tul/deltabrief/briefing/adapter/out/persistence/FeedSourceCatalogAdapter.java`

**Intent**: A `briefing`-owned, read-only JPA mapping onto the existing `sources` table (see Critical Implementation Details for why this duplicates rather than reuses `topic.adapter.out.persistence.SourceJpaEntity`). `FeedSourceCatalogAdapter#findByCategoryId(CategoryId)` queries by `category_id` and maps rows to `FeedSource` records.

### Success Criteria:

#### Automated Verification:

- `./gradlew build` compiles with the Rome dependency
- `RomeSourceContentFetcherTests` pass (WireMock-stubbed feed responses, including a malformed-XML and an unreachable-host case both raising `SourceUnavailableException`)
- `FeedSourceCatalogAdapterTests` pass (Testcontainers, reads the real V6-seeded `sources` rows)

#### Manual Verification:

- Temporarily exercise `RomeSourceContentFetcher` against one real, live source URL (e.g. the BBC feed) to confirm Rome parses actual production feed markup correctly — the WireMock tests alone don't prove compatibility with real-world feed quirks

---

## Phase 3: AI Generation

### Overview

Call OpenAI via Spring AI's `ChatClient` to produce the six narrative sections as structured output, with the anti-hallucination prompt design as the core deliverable of this phase.

### Changes Required:

#### 1. Generation port

**File**: `src/main/java/pl/tul/deltabrief/briefing/application/port/out/BriefingContentGenerator.java`

**Intent**: Defines `GenerationRequest` (topic name, category name, `BriefingType`, the previous briefing's six sections — null/absent for `ONBOARDING`, and the numbered ingested-items list) and `GeneratedBriefingContent` (the six narrative-section strings) as records, plus `generate(GenerationRequest): GeneratedBriefingContent`, nested `GenerationFailedException`.

**Contract**: `GeneratedBriefingContent` is the exact type passed to Spring AI's `.call().entity(GeneratedBriefingContent.class)` — its field names and Javadoc double as the schema the model is instructed to fill in, so each field's Javadoc should name its corresponding FR-010 section precisely.

#### 2. OpenAI adapter and prompt design

**Files**:
- `src/main/java/pl/tul/deltabrief/briefing/adapter/out/ai/OpenAiBriefingContentGenerator.java`
- `src/main/java/pl/tul/deltabrief/briefing/adapter/out/ai/BriefingPromptBuilder.java`
- `src/main/resources/application.properties` (edit)

**Intent**: `OpenAiBriefingContentGenerator` injects the auto-configured `ChatClient.Builder`, builds a `ChatClient` once, and calls `.prompt().user(promptText).call().entity(GeneratedBriefingContent.class)`, translating any failure from the call into `BriefingContentGenerator.GenerationFailedException`. `BriefingPromptBuilder` constructs the prompt text: a hard instruction to use only the numbered source list and cite claims as `[n]`, a request never to state anything not attributable to the list, the numbered `[1] <title> — <link>` items, the previous briefing's six sections as delta-comparison baseline (`DELTA` type) or an "initial state summary, no prior briefing exists" instruction (`ONBOARDING` type — same 7-section template, per the decided approach), and per-section writing instructions matching FR-010's definitions. `application.properties` gets `spring.ai.openai.chat.options.model=gpt-4o-mini`.

**Contract**: The anti-hallucination instruction and the numbered source list must always be present in the constructed prompt regardless of `BriefingType` — this is asserted directly in 3.3 below, not left to manual review alone.

### Success Criteria:

#### Automated Verification:

- `./gradlew build` compiles
- `OpenAiBriefingContentGeneratorTests` pass (WireMock-stubbed OpenAI chat-completions response, structured-output deserialization into `GeneratedBriefingContent` verified)
- A prompt-content test asserts the constructed prompt always contains the anti-hallucination instruction and the numbered source list, for both `BriefingType` values

#### Manual Verification:

- Trigger one real generation call against the real OpenAI API (real, budget-capped key) for one real topic and read the output for fabricated claims, garbled structured-output parsing, or citation numbers that don't match the source list — the single most load-bearing manual check in this plan, directly verifying AGENTS.md's hallucination guardrail

---

## Phase 4: Orchestration & Web

### Overview

Tie ingestion, generation, and persistence together in `BriefingService`, expose the manual-trigger endpoint and briefing/history views, and handle partial-source and generation failures per the decisions made during planning.

### Changes Required:

#### 1. `BriefingService`

**File**: `src/main/java/pl/tul/deltabrief/briefing/application/BriefingService.java`

**Intent**: `generateBriefing(TopicId, UserId): Briefing` — resolves ownership+`categoryId` via `TopicRepository.findCategoryIdByIdAndUserId` (throwing a nested `TopicNotFoundException` if absent/not owned), determines `BriefingType` from whether `BriefingRepository.findLatestByTopicId` returns a prior briefing, fetches sources via `FeedSourceCatalog`, calls `SourceContentFetcher` per source collecting successes and recording which sources failed (per the "proceed with partial sources" decision — failed sources are noted, not fatal), builds the `GenerationRequest`, calls `BriefingContentGenerator.generate(...)` (any `GenerationFailedException` propagates to the caller, per the "clear error + manual retry" decision), and persists the resulting `Briefing` via `BriefingRepository.save`. Also exposes `listSummaries(TopicId, UserId): List<BriefingSummary>` and `findOne(TopicId, BriefingId, UserId): Optional<Briefing>`, both ownership-checked through the same `findCategoryIdByIdAndUserId`-style existence check (or a parallel `TopicRepository.existsByIdAndUserId` if cleaner — implementer's call, matching whichever reads better alongside the existing method).

**Contract**: Nested `TopicNotFoundException`, re-throws `BriefingContentGenerator.GenerationFailedException` uncaught (the controller handles it) — follows `TopicService`'s nested-exception convention. Javadoc documents the partial-source-failure tradeoff explicitly, matching this codebase's existing style of documenting accepted tradeoffs inline (see `TopicService.createTopic`'s topic-cap comment).

#### 2. Web adapter

**Files**:
- `src/main/java/pl/tul/deltabrief/briefing/adapter/in/web/BriefingController.java`
- `src/main/resources/templates/briefing.html`
- `src/main/resources/templates/briefing-generation-failed.html`
- `src/main/resources/templates/topics.html` (edit)
- `src/main/resources/static/css/app.css` (edit)

**Intent**: `POST /topics/{topicId}/briefings` triggers `generateBriefing` synchronously; on success, redirects to `GET /topics/{topicId}/briefings/latest`; on `GenerationFailedException`, renders `briefing-generation-failed` (which re-POSTs the same endpoint for retry) — never a Spring Boot whitelabel error. `GET /topics/{topicId}/briefings/latest` and `GET /topics/{topicId}/briefings/{briefingId}` render `briefing.html` (the six sections, the numbered sources list, and the inline history list from `listSummaries`); either redirects to `/` (never a distinguishable 404) if the topic isn't found/owned or the specific briefing doesn't belong to the topic — mirroring `TopicController.deleteTopic`'s "never distinguish" ownership-leak-avoidance convention. `topics.html` gains a "Generate briefing" button/form per topic card, following the existing inline per-row `<form>` pattern already used for delete.

**Contract**: Current-user resolution via `((AppUserDetails) authentication.getPrincipal()).userId()`, matching `TopicController`.

### Success Criteria:

#### Automated Verification:

- `./gradlew build` compiles
- `BriefingServiceTests` pass (Testcontainers + WireMock for both externals): first generation for a topic produces `ONBOARDING`, second produces `DELTA`, a failing source doesn't block generation, a failing AI call surfaces `GenerationFailedException`
- `BriefingFlowIntegrationTests` pass (MockMvc): generate → view latest → view history list → view a specific past briefing → cross-user isolation (one user cannot view another's briefing by guessing an ID)
- Full suite passes: `./gradlew test`

#### Manual Verification:

- Generate an onboarding briefing for a real topic in the running app; confirm all 7 sections and the sources list render correctly and the wait doesn't feel broken
- Generate a second (delta) briefing for the same topic; confirm it reads as a genuine delta referencing the prior briefing, and the inline history list shows both entries correctly ordered
- Simulate a generation failure (e.g. temporarily invalidate the API key) and confirm the error+retry page works, then confirm retry succeeds once the failure condition is removed

### Addendum: Optional topic description (FR-004), fed into the generation prompt

Discovered mid-Phase-4, after manual verification of the core flow was already confirmed: PRD **FR-004** ("optional topic-description field") was originally parked in the roadmap with the reasoning *"does anyone fill in optional fields in v1? If the AI doesn't use it, it's a dead field."* That objection no longer holds once briefing generation exists — the description can now feed directly into the generation prompt as real context (e.g. "I care about the humanitarian angle, not politics"), sharpening `significance`/`sourceImpact`. Folded into this change rather than opened as a separate one, per explicit user direction.

**Files touched** (beyond this phase's original list):
- `src/main/resources/db/migration/V10__add_topic_description_column.sql` — new nullable `topics.description` column
- `src/main/java/pl/tul/deltabrief/topic/domain/Topic.java` (edit) — new `description` field; original 4-arg `create(...)` kept as a convenience overload defaulting to `null`, so every pre-existing call site across the codebase keeps compiling unchanged
- `src/main/java/pl/tul/deltabrief/topic/adapter/out/persistence/TopicJpaEntity.java`, `TopicEntityMapper.java`, `TopicJpaRepository.java` (edit) — column mapping + `TopicNameAndCategoryView` projection gains `getDescription()`
- `src/main/java/pl/tul/deltabrief/topic/adapter/out/persistence/TopicRepositoryAdapter.java` (edit) — `findSummaryByIdAndUserId` now returns the description too
- `src/main/java/pl/tul/deltabrief/topic/application/port/out/TopicSummary.java` (edit) — gains a `description` component
- `src/main/java/pl/tul/deltabrief/topic/application/TopicService.java` (edit) — `createTopic(...)` gains a `description` overload; original 3-arg signature kept, calling through with `null`
- `src/main/java/pl/tul/deltabrief/topic/application/dto/CreateTopicRequest.java` (edit) — new `description` field, `@Size(max = 1000)`, deliberately no `@NotBlank`/`@NotNull` — genuinely optional
- `src/main/java/pl/tul/deltabrief/topic/adapter/in/web/TopicController.java` (edit) — passes `form.getDescription()` through
- `src/main/resources/templates/topic-form.html` (edit) — new "Observation goal (optional)" textarea, explicitly labeled optional (unlike the required `name`/`categoryId` fields above it), with helper text explaining its purpose
- `src/main/java/pl/tul/deltabrief/briefing/application/port/out/BriefingContentGenerator.java` (edit) — `GenerationRequest` gains `topicDescription`
- `src/main/java/pl/tul/deltabrief/briefing/adapter/out/ai/BriefingPromptBuilder.java` (edit) — appends a delimited `"""..."""` description block only when present (never leaks a literal "null" into the prompt when absent); `INJECTION_GUARDRAIL` wording extended to cover it, matching the topic-name prompt-injection mitigation from the Phase 3 review (same treatment: user-authored free text, verbatim data, never instructions)
- `src/main/java/pl/tul/deltabrief/briefing/application/BriefingService.java` (edit) — passes `topic.description()` into the `GenerationRequest`

**Tests added/extended**: `TopicTests` (description assignment), `TopicRepositoryAdapterTests` (description round-trips through persistence, both set and unset), `BriefingPromptBuilderTests` (description appears delimited when present; the block is omitted entirely — not just left blank — when absent).

#### Automated Verification:

- `./gradlew build` compiles
- Full suite passes: `./gradlew test` (76 tests)

#### Manual Verification:

- Create a topic with a description filled in, generate a briefing, confirm the description doesn't break anything and (ideally) that `significance`/`sourceImpact` reads as if it factored the stated framing in
- Create a topic with the description left blank, confirm topic creation and generation both work exactly as before this addendum

### Addendum 2: Post-merge UX and source-relevance refinements

Discovered after S-03 shipped and was merged (PR #41, squashed to `main` as `db2bdb7`), during a follow-up review of the live app. Not opened as a separate change — same reasoning as Addendum 1: small, directly related refinements to an already-built feature, not new scope. **Not yet committed as of this writing** — see Progress below.

1. **Cited-sources-only display.** `briefing.sources` showed every ingested item, not just the ones the model actually cited via `[n]` markers — undermining the point of the citation mechanism (grounding each claim in a specific source). `BriefingController` now scans the six generated sections for citation markers and displays only the cited items; every ingested item is still persisted for traceability (per the anti-hallucination hard rule in `AGENTS.md`) — only the *display* is filtered. **Follow-up, found during this addendum's own impl-review**: once the Sources list is filtered down, the inline `[n]` markers still referenced the *original* prompt numbering (e.g. `[5][40]` pointing into a 40-item list the reader never sees, when only 2 items are actually shown). `toView` now remaps each citation to its 1-based position in the displayed list (first-appearance order), so `[5][40]` renders as `[1][2]` — verified both by unit tests (`BriefingControllerTests`, new) and against a real generation.
2. **"Back to your topics" navigation.** Was a small, unstyled link at the bottom of a page that can run long — moved to a styled `← Back to your topics` link at the top, above the page header.
3. **Page title.** Was just "Onboarding briefing"/"Delta briefing," giving no indication which topic this was. Now "{topic name} #{ordinal}" (e.g. "War in Ukraine #3" — ordinal is that topic's Nth-ever generated briefing, computed from the already-fetched history list, no extra query); the Onboarding/Delta label moved into the subtitle next to the timestamp.
4. **Timestamp timezone.** Timestamps were rendered server-side, hardcoded to UTC — the server has no way to know the viewer's timezone. The server now emits the raw ISO instant into a `data-timestamp` attribute (UTC text stays as the no-JS fallback), and `app.js` converts it to the viewer's local timezone via `Date`/`toLocaleString` on page load.
5. **Generation feedback.** The synchronous generate-briefing POST blocked the UI with no visual indicator. Pico.css's built-in `[aria-busy=true]` spinner (a rotating circle icon, shipped with the CSS framework already in use) is now toggled via a `data-busy-text` opt-in attribute on the three buttons that trigger generation (topic list, briefing page, failure-retry page); the existing generic submit-disable in `app.js` already greyed the button out and blocked a second click, this only adds the visible spinner + "Generating…" label on top of that.
6. **Source relevance.** The original design fetches every source in the topic's *category* (e.g. all of "World News": BBC, Al Jazeera, Guardian World), with zero relevance filtering against the specific topic — "War in Ukraine" ingested whatever was trending across all of world news, most of it unrelated. Considered three options (discussed inline with the user, not pre-written in `research.md`): a keyword filter on the existing feeds, a topic-targeted search feed, or a paid semantic-search API (Tavily/Exa/Perplexity-style — rejected, new recurring cost + new paid dependency for an MVP already flagged for cost creep in `infrastructure.md`). Chose a topic-targeted search feed: free, reuses the existing `SourceContentFetcher`/Rome fetch-and-parse pipeline unchanged, added *alongside* (not replacing) the curated category feeds — keeps the curated feeds' publisher-quality/reliability anchor while fixing relevance.
7. **Delta-comparison prompt quality.** The other half of the same discussion — the original DELTA-mode instruction was one generic sentence ("Compare the numbered sources above against the prior briefing above. Classify..."), with two real gaps: nothing told the model to explicitly say "no genuine change" when that's the honest answer, and nothing stopped it from restating the prior briefing's own key changes as if they were new — both directly undermine what "delta" is supposed to mean for this product. `BriefingPromptBuilder.DELTA_COMPARISON_INSTRUCTION` replaces it with explicit anti-restatement guidance (key-changes must report only what's genuinely new) and explicit "no significant change" handling for a quiet-news cycle.
8. **Login-failure registration nudge (unrelated, bundled opportunistically).** Not part of the briefing-generation flow at all — flagged directly by the user as a small separate remark ("maybe add to label 'Invalid email or password' on login failure, sth like 'Don't have account yet? Register now!'") during the same working session, and shipped in the same PR since it was a one-line template change. `login.html`'s `param.error` notice now includes an inline "Don't have an account yet? Register now!" link to `/register`, matching the existing in-notice-link pattern already used for the `param.unverified` case (resend-verification link).

**Files touched:**
- `src/main/java/pl/tul/deltabrief/briefing/adapter/out/ai/BriefingPromptBuilder.java` (edit) — new `DELTA_COMPARISON_INSTRUCTION` constant replaces the original one-sentence delta instruction with explicit anti-restatement + "no change" guidance
- `src/main/java/pl/tul/deltabrief/briefing/application/port/out/TopicSearchFeedProvider.java` (new) — output port: `FeedSource searchFeedFor(String topicName)`
- `src/main/java/pl/tul/deltabrief/briefing/adapter/out/rss/GoogleNewsSearchFeedProvider.java` (new) — Google News RSS search adapter; base URL externalized via `app.google-news.base-url` so tests point it at WireMock instead of a real network call
- `src/main/resources/application.properties` (edit) — `app.google-news.base-url=https://news.google.com` default
- `src/main/java/pl/tul/deltabrief/briefing/application/BriefingService.java` (edit) — `ingestSources` also fetches the topic search feed, same skip-on-failure handling as any other source; `findOne` now returns a new `BriefingDetail(Briefing, topicName)` record instead of bare `Briefing`, reusing the ownership-check query already made rather than a second lookup
- `src/main/java/pl/tul/deltabrief/briefing/adapter/in/web/BriefingController.java` (edit) — citation-marker parsing for source filtering; title/ordinal computation; `generatedAtIso` field on both view records
- `src/main/resources/templates/briefing.html` (edit) — back-link repositioned; title/subtitle restructured; `data-timestamp` spans
- `src/main/resources/templates/topics.html`, `briefing-generation-failed.html` (edit) — `data-busy-text` attribute on generation-triggering buttons
- `src/main/resources/static/js/app.js` (edit) — timestamp-localization block; `aria-busy`/label-swap logic on submit
- `src/main/resources/static/css/app.css` (edit) — `.back-link` styles
- `src/main/resources/templates/login.html` (edit) — inline registration link in the `param.error` notice
- `src/main/java/pl/tul/deltabrief/briefing/adapter/in/web/BriefingController.java` (edit, found during impl-review triage) — citation renumbering (`citedNumbersInOrder`/`renumberCitations`/`renumbering`) so inline `[n]` markers match the filtered, displayed Sources list instead of the original prompt numbering; also added a fallback message ("No sources were explicitly cited in this briefing.") for the empty-citations case
- `src/main/resources/templates/briefing.html`, `src/main/resources/static/css/app.css` (edit, same triage) — `.briefing-sources__empty` fallback text/style

**Tests added/extended:**
- `src/test/java/pl/tul/deltabrief/briefing/adapter/out/ai/BriefingPromptBuilderTests.java` (edit) — `deltaPromptContainsGuardrailAndSourcesAndBaseline` now also asserts `DELTA_COMPARISON_INSTRUCTION` is present verbatim
- `src/test/java/pl/tul/deltabrief/briefing/adapter/in/web/BriefingControllerTests.java` (new, found during impl-review triage) — unit tests on `toView` covering citation renumbering, deduplication across sections, out-of-range citations, the empty-citations case, and title/ordinal formatting
- `src/test/java/pl/tul/deltabrief/briefing/adapter/out/rss/GoogleNewsSearchFeedProviderTests.java` (new) — pure unit test on URL construction/encoding, no network
- `src/test/java/pl/tul/deltabrief/briefing/application/BriefingServiceTests.java` (edit) — `app.google-news.base-url` overridden to WireMock in `@SpringBootTest`; new test `alsoIngestsFromTheTopicTargetedGoogleNewsSearchFeed` stubs and asserts the search feed is actually queried (by topic name) and its items ingested alongside the category feed's
- `src/test/java/pl/tul/deltabrief/briefing/BriefingFlowIntegrationTests.java` (edit) — same property override (so its unstubbed Google News request degrades gracefully via the existing `SourceUnavailableException` skip path, same as `aFailingSourceDoesNotBlockGeneration`, rather than attempting a real network call); chat-completion stub updated to include a `[1]` citation marker so the pre-existing "Test Headline" assertion still holds under the new cited-sources-only filtering

#### Automated Verification:

- [x] `./gradlew build` compiles — a8037fe
- [x] Full suite passes: `./gradlew test` — a8037fe

#### Manual Verification:

- [ ] Generate a briefing, confirm the Sources section shows only items actually cited via `[n]` in the text, not every ingested item
- [ ] Confirm "← Back to your topics" appears at the top of the briefing page, styled distinctly from body text
- [ ] Confirm the page title reads "{topic name} #{ordinal}" and the subtitle shows the type label + a timestamp in the viewer's own local timezone
- [ ] Click "Generate briefing" / "Generate new briefing" / "Try again" and confirm the button greys out, shows the spinning circle icon, and its label changes to "Generating…" while the request is in flight
- [ ] Generate a briefing for a topic with real-world news coverage and confirm at least one ingested item is sourced from "Google News: {topic name}"
- [ ] Generate two briefings in a row for the same topic (onboarding, then delta) and confirm the delta briefing's key-changes section reports only what's actually new — not a restatement of the onboarding briefing's content — and, on a quiet news cycle, says so explicitly rather than inventing a change

### Addendum 3: Source fetch parallelization and diversity improvements

Discovered from real usage of Addendum 2's Google News integration: generation latency grew from ~5s to ~20s once the search feed was added, and the rendered Sources list skewed almost entirely toward Google News, with the curated category feeds barely contributing. Both trace to the same root causes, discussed with the user before implementing.

1. **Latency — sequential-sum fetching.** `BriefingService.ingestSources` fetched every source (category feeds and the search feed) in one sequential `for` loop, each with its own 5s+5s timeout — total latency was the *sum* of every fetch, not the slowest one. Rewritten to fetch all sources concurrently via `Executors.newVirtualThreadPerTaskExecutor()` (Java 21 virtual threads — a natural fit since each fetch is blocking I/O), collecting results by iterating the futures in their original submission order rather than completion order, so `ingestedItems`' ordering (and therefore the prompt's `[n]` citation numbering) stays exactly as deterministic as the sequential version. An unexpected (non-`SourceUnavailableException`) failure still propagates and fails generation loudly, matching the sequential version's behavior — only the already-handled per-source failure mode is swallowed. Verified live: a real generation for "Ukraine war" dropped from ~8s (pre-parallelization baseline) to ~3-5s consistently across many subsequent live runs.
2. **Diversity — three iterations before landing on what actually worked.**
   - **Tried first: a local keyword pre-filter** (`TopicRelevanceFilter`, tokenized topic name vs. item title) applied to category-feed items only, paired with a soft "cite a mix when sources tie" prompt nudge. Live testing across several runs showed the tie-breaker essentially never fired — Google News items are almost always at least a little more specific than whatever a curated feed's current top-10 happens to contain, so a strict-equality bar was met basically never; **0 of 4 sampled runs cited any curated source.** The filter itself was also flagged directly by the user as too crude (misses paraphrases like "Kyiv" for a "Ukraine" topic) and was removed — live testing separately confirmed the model already judges relevance competently on the full unfiltered set, so the filter's actual job was redundant with what the LLM already does, while adding a real false-negative risk.
   - **Tried second: capping Google News's item count** (10 → 5) to shrink its structural volume advantage, kept alongside a stronger-but-still-conditional priority instruction. Reverted before shipping, per explicit user direction — the user's framing was sharper than "shrink Google News": **Google News should be a pure filler**, used only for claims no curated source addresses at all, not a competing candidate whose relative volume needs tuning.
   - **What shipped:** `BriefingPromptBuilder.GOOGLE_NEWS_FILLER_GUIDANCE` reframes the relationship entirely — for every claim, the model looks for a curated source first and cites it if it genuinely supports the claim *even when less specific than a Google News result*; a Google News citation is only used when no curated source addresses the claim in any way. Still gated on `ANTI_HALLUCINATION_INSTRUCTION` (never cite a curated source that doesn't actually support the claim just to avoid Google News). Paired with `V11__double_curated_sources_per_category.sql`, which doubles each category's curated feed count (World News 3→6, Technology 2→4, Business & Finance 2→4, Science 1→2 — new feeds live-verified as working RSS/Atom before adding, picked for editorial/geographic diversity, not just volume: NPR, DW, France24 alongside the existing BBC/Al Jazeera/Guardian). More curated coverage gives the filler framing more real chances to find a genuine curated match instead of falling through to Google News by default. **Follow-up**: V11 left category counts uneven (6/4/4/2) since it doubled each category's own existing count rather than targeting a common number; `V12__equalize_curated_sources_per_category.sql` brings every category to 6 (Technology +Engadget/The Register, Business & Finance +Business Insider/The Economist – Business, Science +NASA Breaking News/Live Science/Space.com/Phys.org — all live-verified before adding), so no category structurally has fewer curated fallback options than another.
   - **Verified live, dramatic reversal**: the same "Ukraine war"-style topic, run 3 more times after shipping — **0 of 3 successful runs cited a single Google News item**; every citation came from a curated outlet, with two runs citing 3-4 *different* curated outlets (BBC, Guardian, NPR, France24) corroborating the same story in one briefing.

**Files touched:**
- `src/main/java/pl/tul/deltabrief/briefing/application/BriefingService.java` (edit) — `ingestSources` parallelized via virtual threads; new `fetchOne`/`awaitResult` helpers (no per-source relevance filtering or item caps — tried both, reverted both)
- `src/main/java/pl/tul/deltabrief/briefing/adapter/out/ai/BriefingPromptBuilder.java` (edit) — `GOOGLE_NEWS_FILLER_GUIDANCE` constant (final name/wording, after two prior iterations: `SOURCE_DIVERSITY_GUIDANCE` → `CURATED_SOURCE_PRIORITY_GUIDANCE` → this); `numberedSources` now includes each item's `sourceName` in every entry — a hard prerequisite for any curated-vs-Google-News instruction to work at all, since the model can't act on a distinction it can't see
- `src/main/resources/db/migration/V11__double_curated_sources_per_category.sql` (new) — doubles curated sources per category
- `src/main/resources/db/migration/V12__equalize_curated_sources_per_category.sql` (new) — brings every category up to the same 6-source count

**Tests added/extended:**
- `src/test/java/pl/tul/deltabrief/briefing/application/BriefingServiceTests.java` (edit) — `ingestsEveryFetchedItemFromEveryConcurrentlyFetchedSourceWithNoLocalFiltering` (renamed/repurposed from the removed filter's test) proves no local filtering happens — an on-topic-looking, an off-topic-looking, and a search-feed item are all ingested unconditionally
- `src/test/java/pl/tul/deltabrief/briefing/adapter/out/ai/BriefingPromptBuilderTests.java` (edit) — asserts `GOOGLE_NEWS_FILLER_GUIDANCE` present in both onboarding and delta prompts; new test asserts each numbered entry includes its source name in the `[n] sourceName: title — link` format

#### Automated Verification:

- [x] `./gradlew build` compiles; full suite passes
- [x] `BriefingServiceTests` and `BriefingPromptBuilderTests` pass, including the new/renamed coverage above

#### Manual Verification:

- [x] Live generation latency measured — ~8s baseline down to ~3-5s consistently across many live runs
- [x] Live generation's Sources list confirmed as curated-first: 0 of 3 post-ship runs cited any Google News item; several runs cited 3-4 distinct curated outlets together

---

## Testing Strategy

### Unit Tests:

- `BriefingTests` — aggregate construction, `assignId`, section/ingested-items accessors
- `BriefingPromptBuilderTests` — prompt always includes the anti-hallucination instruction and numbered sources for both `BriefingType`s

### Integration Tests:

- `BriefingServiceTests`, `FeedSourceCatalogAdapterTests`, `BriefingRepositoryAdapterTests`, `TopicRepositoryAdapterTests` (extended) — all `@SpringBootTest` + Testcontainers, per this codebase's existing convention
- `RomeSourceContentFetcherTests`, `OpenAiBriefingContentGeneratorTests` — WireMock-stubbed, no live network calls
- `BriefingFlowIntegrationTests` — MockMvc, full flow including cross-user isolation

### Manual Testing Steps:

1. Generate an onboarding briefing for a fresh topic and read it for structure and plausibility
2. Generate a second, delta briefing for the same topic and confirm it reads as a genuine delta
3. Force a partial-source failure (e.g. temporarily break one source's URL) and confirm generation still succeeds with a note about what was skipped
4. Force an AI-call failure and confirm the error+retry UX works

## Performance Considerations

Ingested items are capped at 10 per source to bound prompt size, cost, and latency (see Critical Implementation Details). No caching layer for fetched feeds in this slice — every manual trigger re-fetches fresh, acceptable given manual-trigger-only scope and low expected call volume.

## Migration Notes

`V8`/`V9` are additive (new tables only, no changes to existing tables) — no backfill or existing-data migration needed.

## References

- Related research: `context/changes/first-onboarding-and-delta-briefing/research.md`
- Pattern to mirror: `src/main/java/pl/tul/deltabrief/topic/` (domain/application/adapter layering, MapStruct mapping, owner-scoped queries)
- Anti-hallucination guardrail: `AGENTS.md:7`
- Cost/infra constraints: `context/foundation/infrastructure.md`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Domain & Persistence Foundation

#### Automated

- [x] 1.1 `./gradlew build` compiles cleanly — 8470f61
- [x] 1.2 Domain unit tests pass — 8470f61
- [x] 1.3 `BriefingRepositoryAdapterTests` pass — 8470f61
- [x] 1.4 `TopicRepositoryAdapterTests` (extended) pass — 8470f61

#### Manual

- [x] 1.5 Confirm `briefings`/`ingested_items` schema via psql/DBeaver — 8470f61

### Phase 2: Content Ingestion

#### Automated

- [x] 2.1 `./gradlew build` compiles with Rome — 6a8e4e2
- [x] 2.2 `RomeSourceContentFetcherTests` pass — 6a8e4e2
- [x] 2.3 `FeedSourceCatalogAdapterTests` pass — 6a8e4e2

#### Manual

- [x] 2.4 Verify Rome against one real live source feed — 6a8e4e2

### Phase 3: AI Generation

#### Automated

- [x] 3.1 `./gradlew build` compiles — 291617b
- [x] 3.2 `OpenAiBriefingContentGeneratorTests` pass — 291617b
- [x] 3.3 Prompt-content test asserts anti-hallucination instruction + source list present — 291617b

#### Manual

- [x] 3.4 Real generation call against real OpenAI API — check for fabrication/garbled output — 291617b

### Phase 4: Orchestration & Web

#### Automated

- [x] 4.1 `./gradlew build` compiles — 23c9450
- [x] 4.2 `BriefingServiceTests` pass — 23c9450
- [x] 4.3 `BriefingFlowIntegrationTests` pass — 23c9450
- [x] 4.4 Full suite passes (`./gradlew test`) — 23c9450

#### Manual

- [x] 4.5 Generate onboarding briefing in running app, confirm rendering — 23c9450
- [x] 4.6 Generate delta briefing, confirm delta content + history list — 23c9450
- [x] 4.7 Simulate generation failure, confirm error+retry UX — 23c9450

### Phase 4 Addendum: Optional topic description (FR-004)

#### Automated

- [x] 4.8 `./gradlew build` compiles; full suite passes (76 tests) — 23c9450

#### Manual

- [x] 4.9 Create a topic with a description, generate a briefing, confirm it doesn't break anything and the description reads as considered — 23c9450

### Post-Merge Refinements (see Addendum 2)

#### Automated

- [x] 5.1 `./gradlew build` compiles; full suite passes — a8037fe

#### Manual

- [x] 5.2 Cited-sources-only filtering confirmed in a running briefing — live generation against topic "Ukraine war" ingested 40 items (30 category + 10 Google News) but the rendered Sources section showed only the 4 actually cited via `[n]`
- [x] 5.3 Back-link placement and title/subtitle restructure confirmed — rendered page showed `<h1>Ukraine war #1</h1>` (then `#2` on the second generation), back-link at top, subtitle `Onboarding briefing · <timestamp>`
- [x] 5.4 Generation spinner confirmed on all three trigger buttons — user-confirmed live in browser: button greys out, shows the spinning circle icon, label changes to "Generating…" — f787d4a
- [x] 5.5 Google News topic-search feed confirmed contributing ingested items — 10 of 40 ingested items came from "Google News: Ukraine war", all genuinely on-topic (vs. only ~2 of 30 category-feed items actually being Ukraine-related), directly demonstrating the relevance improvement this addendum was written for
- [x] 5.6 Delta-comparison prompt's anti-restatement + "no change" guidance confirmed against two real generations for the same topic — onboarding briefing's key-changes described a real event with citations; the delta briefing generated ~10s later (same underlying news pool, no genuine new development) read exactly "No significant change since the prior briefing." — the explicit no-change behavior working as designed, not a restatement of the onboarding content
- [x] 4.10 Create a topic without a description, confirm creation + generation both work exactly as before — 23c9450

### Addendum 3: Source fetch parallelization and diversity improvements

#### Automated

- [x] 6.1 `./gradlew build` compiles; full suite passes — includes `BriefingServiceTests`' no-local-filtering coverage and `BriefingPromptBuilderTests`' source-name/filler-guidance assertions — b650d12

#### Manual

- [x] 6.2 Live generation latency confirmed dropping from ~8s to ~3-5s consistently after parallelizing source fetches — b650d12
- [x] 6.3 Live generation's Sources list confirmed curated-first after landing on the filler framing + doubled curated sources: 0 of 3 post-ship runs cited Google News; several cited 3-4 distinct curated outlets together — b650d12
- [x] 6.4 All four categories confirmed at 6 curated sources each after V12 (`select count(*) ... group by category`) — 1c68a41
