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

- [x] 1.1 `./gradlew build` compiles cleanly
- [x] 1.2 Domain unit tests pass
- [x] 1.3 `BriefingRepositoryAdapterTests` pass
- [x] 1.4 `TopicRepositoryAdapterTests` (extended) pass

#### Manual

- [x] 1.5 Confirm `briefings`/`ingested_items` schema via psql/DBeaver

### Phase 2: Content Ingestion

#### Automated

- [ ] 2.1 `./gradlew build` compiles with Rome
- [ ] 2.2 `RomeSourceContentFetcherTests` pass
- [ ] 2.3 `FeedSourceCatalogAdapterTests` pass

#### Manual

- [ ] 2.4 Verify Rome against one real live source feed

### Phase 3: AI Generation

#### Automated

- [ ] 3.1 `./gradlew build` compiles
- [ ] 3.2 `OpenAiBriefingContentGeneratorTests` pass
- [ ] 3.3 Prompt-content test asserts anti-hallucination instruction + source list present

#### Manual

- [ ] 3.4 Real generation call against real OpenAI API — check for fabrication/garbled output

### Phase 4: Orchestration & Web

#### Automated

- [ ] 4.1 `./gradlew build` compiles
- [ ] 4.2 `BriefingServiceTests` pass
- [ ] 4.3 `BriefingFlowIntegrationTests` pass
- [ ] 4.4 Full suite passes (`./gradlew test`)

#### Manual

- [ ] 4.5 Generate onboarding briefing in running app, confirm rendering
- [ ] 4.6 Generate delta briefing, confirm delta content + history list
- [ ] 4.7 Simulate generation failure, confirm error+retry UX
