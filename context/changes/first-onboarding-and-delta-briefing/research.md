---
date: 2026-09-13T22:58:57+02:00
researcher: Claude Sonnet 5
git_commit: 53b534a
branch: main
repository: ninjarlz/delta-brief
topic: "S-03: First onboarding and delta briefing — implementation groundwork"
tags: [research, codebase, briefing, topic, spring-ai, htmx]
status: complete
last_updated: 2026-09-13
last_updated_by: Claude Sonnet 5
---

# Research: First onboarding and delta briefing (S-03)

**Date**: 2026-09-13T22:58:57+02:00
**Researcher**: Claude Sonnet 5
**Git Commit**: 53b534a
**Branch**: main
**Repository**: ninjarlz/delta-brief

## Research Question

What does the codebase already have in place — and what's genuinely missing — to implement roadmap slice S-03 ("User can trigger generation of an onboarding briefing for a new topic, then trigger a delta briefing that compares new content against it, and read both in the app")? Covers: the data model available to feed generation, Spring AI wiring status, module-layering conventions to replicate for a new `briefing` module, prior decisions recorded in `context/changes/**` / `context/archive/**`, and the frontend live-feedback story.

## Summary

This is a **greenfield slice** — no `briefing` package exists yet, and none of the five things it needs (source ingestion, an AI call, a `briefing` module, HTMX wiring, a prompt/output template) exist in the codebase today. But the groundwork the previous slice (S-02) laid is more complete than the roadmap's own risk note suggests:

- **Sources are real, fetchable RSS/Atom feeds**, DB-seeded with live URLs (`sources.feed_url`), not placeholder labels — confirmed via migration `V6__seed_categories_and_sources.sql` and the S-02 plan's own live-`curl` verification note. However, **`Source` is entirely unwired at the application layer** — the JPA entity and table exist, but there is no repository port, no adapter, no Spring bean anywhere that reads a `sources` row. The `topic` module only ever reads `Category`; `Source` rows are inert data waiting for this slice.
- **Spring AI is dependency-wired but code-untouched**: `spring-ai-starter-model-openai` 2.0.0 is on the classpath, `spring.ai.openai.api-key` has a placeholder stub, and **zero Java code anywhere references `ChatClient`/`ChatModel`/any Spring AI type**. No structured-output pattern, no prompt template, no provider-specific config exists to build on — this slice starts from the Spring AI starter's raw defaults.
- **No content-ingestion code exists at all** — no RSS/Atom parsing library on the classpath (no Rome, no Jsoup), no HTTP client wired for feed fetching, no `@Scheduled` job anywhere, no "content item"/"article" domain concept. "New content since the last briefing" is an undecided mechanic — nothing in `context/changes/**` or `context/archive/**` addresses fetch timing, dedup, or a since-timestamp strategy.
- **HTMX is aspirational only** — `tech-stack.md` describes it as the plan for live generation feedback, but there's no htmx script include, no dependency, no `hx-*` attribute, and no loading/progress CSS anywhere in the repo. This slice would be the first to actually wire it in, or could defer to a simpler pattern (see Open Questions).
- **Infrastructure steers toward synchronous, in-process generation, not a background job queue**: `infrastructure.md` recommends keeping `@Scheduled` in-process on the always-on Render Standard instance (Render Cron Jobs run in an isolated container that can't share the Spring context/Hikari pool/AI client). No hard Render request-timeout figure is documented. Given this slice is scoped to **manual trigger only** (S-04 owns scheduling), the simplest first cut is a synchronous controller-triggered generation call — no job queue needed yet.
- **Cost is a real constraint, not just a nice-to-have**: LLM API calls are the one strictly-metered budget line (`infrastructure.md`: ≤$25/mo target, "$10–15 OpenAI usage cap" recommended, "a small/`mini` model keeps it near $2/mo; a flagship model at ~600 briefings/mo lands ~$25-40"). Model choice belongs in this slice's planning questions.
- **A latent operational risk carries over from S-01's mail-health-indicator incident**: Spring AI's OpenAI autoconfiguration registers its own Actuator health indicator (like `spring-boot-starter-mail` did), which will start making live upstream calls once a real API key is set — and nothing in the codebase or docs has flagged this yet, unlike the mail case which was caught and fixed (`management.health.mail.enabled=false`). Same fix pattern (`management.health.ai.enabled=false` or equivalent) will likely be needed.
- **Module layering conventions are unambiguous and consistent** across `auth` and `topic` — a new `briefing` module can follow them mechanically: `domain` → `application` (+ `application/port/out`) → `adapter.in.web` / `adapter.out.persistence` (+ a new `adapter.out.ai` or similar concern folder for the Spring AI call), MapStruct mappers, Lombok fluent accessors on aggregates, `@Controller` (never `@RestController`), redirect-after-POST, `<Unit>Tests` naming, `@SpringBootTest` + Testcontainers for service/adapter tests, a `*FlowIntegrationTests` MockMvc suite per module.
- **No `lessons.md` exists yet** — no accepted recurring-rule priors to apply here (this would be a natural moment to seed one, e.g. around external-API health indicators, given the mail-indicator precedent).
- **`test-plan.md` already exists** and explicitly defers AI-generation-quality testing to this slice: "briefing generation (S-03+) isn't built yet; nothing to test. Re-evaluate once S-03 lands." This slice's plan should loop back to `/10x-test-plan` once implemented.

## Detailed Findings

### Data model: Topic → Category → Source

- `Topic` (`src/main/java/pl/tul/deltabrief/topic/domain/Topic.java:16-30`) — `id: TopicId`, `userId: UserId`, `name: String`, `categoryId: CategoryId` (fixed at creation, no behavior to change it), `createdAt: Instant`.
- `Category` (`.../topic/domain/Category.java:14-19`) — `id`, `name`. Plain seeded reference data.
- `Source` (`.../topic/domain/Source.java:18-25`) — `id: SourceId`, `categoryId: CategoryId`, `name: String`, **`feedUrl: String`** — a real RSS/Atom URL. Javadoc on the class states plainly: *"Not yet read anywhere in the app ... this slice only needs categories for topic creation; source ingestion arrives with briefing generation."* That "arrives with" is this change.
- Granularity: `Topic → categoryId → (implicit list of Sources via category_id FK)`. No per-topic source selection (`topic_sources` join table) — a topic draws from every source in its category. This is an intentional, documented v1 simplification (`roadmap.md` Parked section).
- Seed data (`src/main/resources/db/migration/V6__seed_categories_and_sources.sql:1-15`): 4 categories (World News, Technology, Business & Finance, Science), 8 sources with real feed URLs, e.g. BBC News World (`http://feeds.bbci.co.uk/news/world/rss.xml`), Al Jazeera, The Guardian World, TechCrunch, Ars Technica, CNBC, MarketWatch, ScienceDaily.
- **Missing persistence wiring**: `SourceJpaEntity` exists (`.../topic/adapter/out/persistence/SourceJpaEntity.java:19-38`, table `sources`) but there is no `SourceJpaRepository`, no `SourceRepositoryAdapter`, and no `application/port/out/SourceRepository` — this slice needs to build that port+adapter (mirroring `CategoryRepository`'s shape) as its first data-access step, most naturally exposing a `findAllByCategoryId(CategoryId)` query.
- Value objects: `TopicId`, `CategoryId`, `SourceId` are all `record`s with a single `Long value` field, each documented as the cross-module reference boundary (`TopicId.java:7`, etc.). A future `BriefingId` should follow the identical shape.

### Spring AI: wired as a dependency, untouched by code

- `build.gradle:27` — `springAiVersion = '2.0.0'`; `build.gradle:35` — BOM `org.springframework.ai:spring-ai-bom:2.0.0`; `build.gradle:50` — `implementation 'org.springframework.ai:spring-ai-starter-model-openai'` (current Boot-4-era starter naming, not the legacy `spring-ai-openai-spring-boot-starter`). Spring Boot `4.1.1` (`build.gradle:3`).
- Only one AI property exists: `spring.ai.openai.api-key=placeholder-not-a-real-key` (`application.properties:42`), explicitly a boot-time stub with a comment explaining Spring AI's autoconfiguration validates *some* credential is present even though nothing calls the client yet (`application.properties:38-41`).
- **Zero Java references** to `ChatClient`, `ChatModel`, `OpenAiChatModel`, or any `org.springframework.ai.*` type anywhere in `src/main` or `src/test` — this slice is the first to actually call the model.
- No `spring.ai.openai.chat.options.*` model-name override exists — model choice (e.g. `gpt-4o-mini` vs. a flagship model) is an open decision with direct budget consequences (see cost figures above).
- `AGENTS.md:7` sets the hard guardrail that governs prompt/output design here: *"Never let a briefing fabricate facts. Every claim in generated briefing content must be traceable to an ingested source ... Any prompt, model call, or output-rendering code must preserve source attribution."*
- No structured-output pattern (`BeanOutputConverter`, JSON-schema-constrained output, `ChatClient.entity()`) is referenced anywhere in code or docs — this slice needs to choose one to reliably produce the PRD's 7-section template (FR-010).
- **Undocumented operational risk**: Spring AI's OpenAI autoconfiguration typically registers its own Actuator health indicator, exactly analogous to the `spring-boot-starter-mail` health indicator this codebase already got burned by and fixed (`application.properties:31-37`, also documented as a caught deviation in `context/changes/user-registration-and-login/plan.md:342`). Nobody has flagged the AI-indicator equivalent yet in any doc. Worth a proactive `management.health.ai.enabled=false` (or equivalent property — verify exact key during implementation) alongside the real API key going live, so a transient OpenAI hiccup can't fail Render's health check and pull the instance out of rotation.

### Content ingestion: does not exist, nothing decided

- No RSS/Atom parsing library on the classpath (checked for Rome, Jsoup, and generic `feed`/`rss` dependency names — none present).
- No HTTP client wired for feed fetching, no `@Scheduled` job anywhere in `src/main/java`.
- No "content item" / "article" domain concept exists.
- **Nothing in `context/changes/**` or `context/archive/**` decides**: which RSS parsing approach to use, whether ingestion happens on-demand at generation time (simplest for a manual-trigger-only slice) or is pre-fetched/cached, or how "new content since the last briefing" is determined (a per-topic `lastBriefingAt` timestamp compared against each feed item's publish date is the most obvious mechanic, but it's undecided).
- Given S-03 is scoped to **manual trigger only** (scheduling is S-04's job per the roadmap), on-demand fetch-at-generation-time is the natural minimal-scope choice — no caching/staleness logic needed yet.

### Module-layering conventions to replicate for `briefing`

Both `auth` and `topic` follow one consistent shape (see full detail in the sub-agent's structured report, condensed here):

```
briefing/
  domain/                          — Briefing aggregate, BriefingId, section value objects
  application/
    dto/                           — (if any form-bound requests are needed — likely minimal, generation is a POST with no body)
    port/out/                      — BriefingRepository, SourceRepository (see below)
  adapter/
    in/web/                        — BriefingController (@Controller, not @RestController)
    out/persistence/                — BriefingJpaEntity + mapper + adapter
    out/ai/                         — the Spring AI call boundary (new concern folder, mirrors auth's adapter/out/security precedent for a non-persistence external integration)
```

Concrete conventions confirmed by direct file evidence in `auth`/`topic`:

- **Aggregates**: `@Getter @Accessors(fluent = true)`; static factory (`Topic.create(...)`, `User.register(...)`) constructs with `id=null`; a dedicated `assignId(XxxId)` (called only from the persistence adapter's `save()`) sets it post-persist; `Instant` timestamps passed in by the caller, never `Instant.now()` inside the aggregate; `@Version`/`assignVersion` is opt-in per aggregate (User has it, Topic doesn't) — a `Briefing` likely doesn't need it (no concurrent-edit scenario).
- **Application services**: `@Service @RequiredArgsConstructor`; custom exceptions as `public static class` nested inside the service, extending `RuntimeException`; Javadoc documents invariants/tradeoffs explicitly, not just what the code does.
- **Repository port+adapter**: port interface in `application/port/out/`, JPA-import-free; adapter is a package-private `@Component @RequiredArgsConstructor` class delegating to a Spring Data `JpaRepository` + a **MapStruct** `@Mapper(componentModel = "spring")` mapper (not hand-written mapping) — `toEntity(...)` needs explicit `@Mapping(target=..., expression="java(...)")` per field because aggregates use fluent (non-JavaBean) accessors.
- **JPA entities**: uniform `@Getter @NoArgsConstructor(access = AccessLevel.PROTECTED) @AllArgsConstructor`, `@Id @GeneratedValue(strategy = GenerationType.IDENTITY)`.
- **Web adapter**: `@Controller` only (confirmed zero `@RestController` usage repo-wide); redirect-after-POST on success, re-render the form view (never redirect) on validation/business-rule failure via `BindingResult`; current-user resolution via `((AppUserDetails) authentication.getPrincipal()).userId()`.
- **Templates**: flat under `src/main/resources/templates/*.html`, name matches the returned view string.
- **Cross-module reference rule holds firmly**: `topic` imports only `auth.domain.UserId`, never `auth.domain.User`, in any production code path (test fixtures are the sole exception, for persisting a real owning user row). A `briefing` module should import only `TopicId` from `topic`, never `Topic` itself.
- **Testing**: `<Unit>Tests` naming; domain aggregate tests are plain JUnit5/AssertJ, no Spring context; service/adapter tests are `@SpringBootTest` with `@Import({TestcontainersDatasourceConfig.class, SynchronousAsyncConfig.class})` against a real Testcontainers Postgres (no mocking framework in use anywhere in this codebase); one `*FlowIntegrationTests` MockMvc suite per module, built manually via `MockMvcBuilders.webAppContextSetup(...).apply(springSecurity())` rather than `@AutoConfigureMockMvc`, authenticating through the real register→verify→login flow rather than MockMvc's `user()` shortcut.

### Frontend: HTMX is not wired in yet — it's aspirational

- `tech-stack.md` names HTMX for "the one dynamic area — live feedback while a briefing generates," but this is forward-looking, not built: no htmx script include (CDN or local) in any of the 8 existing templates, no HTMX dependency in `build.gradle`, no `hx-*` attribute anywhere, no loading/spinner/progress CSS class in `app.css`.
- `static/js/app.js` (12 lines, unchanged since the Thread-3 UI work) only disables the submit button on form submit to block double-submits — nothing HTMX-related.
- No SSE/WebSocket code exists either (`SseEmitter`/`EventSource`/`websocket` — all absent).
- Given generation is scoped to a synchronous, on-demand, manually-triggered call for this slice (see Infrastructure below), the simplest first cut may not need HTMX/SSE at all — a plain POST-and-redirect, with the "please wait" experience handled by the existing submit-button-disable script plus a simple loading state, could satisfy the NFR ("continuous visible feedback ... never left wondering") without introducing a new frontend dependency. Whether to introduce real HTMX polling/streaming now or defer it is a planning-stage tradeoff, not a research-stage fact.

### Infrastructure constraints bounding the generation architecture

- `infrastructure.md:21` — the app is architected as "a persistent JVM server with in-process `@Scheduled` briefing jobs," ruling out serverless/edge hosting for this feature.
- `infrastructure.md:59,89` — Render Cron Jobs run in an isolated container that can't share the Spring context, Hikari pool, or (implicitly) any warmed-up AI client — "Keep `@Scheduled` in-process on the always-on instance for MVP." Not directly this slice's concern (manual trigger only, no scheduling), but shapes how S-04 will later reuse whatever generation code this slice produces — the generation logic should live in a plain `@Service` method callable both from a controller (this slice) and, later, from a `@Scheduled` job (S-04), not baked into the controller.
- No documented hard Render request-timeout number — but a synchronous LLM call inside one HTTP request-response cycle is still a latency risk worth flagging in planning (model choice affects both cost and response time).
- `infrastructure.md:99,108,115,127` — LLM cost is the one metered/runaway-risk budget line: target ≤$25/mo total, "$10-15" OpenAI usage cap recommended, a small/`mini` model keeps monthly cost near $2, a flagship model at ~600 briefings/mo could run $25-40. Model selection is a real planning decision, not a triviality.
- `infrastructure.md:73,92` — HikariCP pool is already capped (`maximum-pool-size=5`) against Supabase's Supavisor pooler; a generation flow that does DB + external RSS HTTP calls + an LLM call within one request should be mindful of not holding a DB connection open across the slow external calls.

## Code References

- `src/main/java/pl/tul/deltabrief/topic/domain/Source.java:18-25` — Source aggregate, carries `feedUrl`, explicitly documented as unread until this slice.
- `src/main/java/pl/tul/deltabrief/topic/adapter/out/persistence/SourceJpaEntity.java:19-38` — `SourceJpaEntity`, no repository/adapter wired.
- `src/main/resources/db/migration/V5__create_categories_and_sources_tables.sql` — schema for `categories`/`sources` (`feed_url VARCHAR(2048)`).
- `src/main/resources/db/migration/V6__seed_categories_and_sources.sql:1-15` — 4 categories, 8 real RSS-URL sources.
- `build.gradle:27,35,50` — `springAiVersion = '2.0.0'`, BOM import, `spring-ai-starter-model-openai` dependency.
- `src/main/resources/application.properties:38-42` — Spring AI OpenAI placeholder key + explanatory comment.
- `src/main/resources/application.properties:31-37` — the mail-health-indicator precedent this slice's AI equivalent should learn from.
- `AGENTS.md:7` — anti-hallucination / source-attribution guardrail governing all prompt/model/output-rendering code.
- `context/foundation/infrastructure.md` (full file) — deploy/cost/timeout constraints; see especially lines ~21, 34, 58-59, 71, 73, 87-99, 108, 115, 124-127, 138.
- `context/foundation/test-plan.md` — notes AI-generation-quality testing explicitly deferred to S-03.
- `src/main/java/pl/tul/deltabrief/topic/domain/Topic.java`, `TopicService.java`, `TopicController.java`, `TopicRepositoryAdapter.java`, `TopicEntityMapper.java` — the template to mirror for `briefing`'s equivalents.
- `src/main/java/pl/tul/deltabrief/auth/adapter/out/security/` — precedent for a non-persistence `adapter.out.<concern>` folder (a `briefing/adapter/out/ai/` folder would follow this shape).
- `src/test/java/pl/tul/deltabrief/topic/TopicFlowIntegrationTests.java` — template for a future `BriefingFlowIntegrationTests`.

## Architecture Insights

- The codebase's DDD-modular-monolith discipline (ID-only cross-module references, `domain`/`application`/`adapter.in.web`/`adapter.out.*` layering, MapStruct mapping between fluent-accessor aggregates and JavaBean JPA entities) has held with zero drift across two prior modules (`auth`, `topic`) — S-03 can and should follow it mechanically rather than reinventing structure.
- The team's established pattern for an external, non-persistence integration boundary (`auth/adapter/out/security/` for Spring Security's `UserDetailsService`) generalizes cleanly to an `adapter/out/ai/` (or similar) folder for the Spring AI call in `briefing` — keeping the LLM client isolated behind a port the same way persistence is isolated behind a repository port.
- Generation logic (source lookup → ingestion → prompt construction → LLM call → briefing persistence) belongs in a single `@Service` method reachable by both this slice's manual-trigger controller endpoint and, later, S-04's `@Scheduled` job — designing the controller to be a thin caller of that service now avoids rework when scheduling arrives.
- The project has now hit two "seed now, wire later" deferrals in a row (S-01 seeded a `sources` table nobody reads; S-03 is where it finally gets read) — this is a deliberate, working pattern in this codebase (confirmed by `Source`'s own javadoc saying as much), not scope creep to worry about.

## Historical Context (from prior changes)

- `context/archive/2026-09-13-create-topic-and-select-sources/plan.md:23,29-33,45,70` — confirms sources are real DB-seeded RSS URLs verified live via `curl` on 2026-09-13, and explicitly defers "actual source ingestion" to "briefing generation, S-03+."
- `context/archive/2026-09-13-create-topic-and-select-sources/plan-brief.md:35` — same deferral, listed under "Out of scope."
- `context/foundation/roadmap.md:136` — S-03's own risk note: "This is the go/no-go slice for the whole product — if the delta-classification (genuine change vs. trend vs. noise/speculation) doesn't hold up, later slices would be automating and distributing something unproven."
- `context/foundation/roadmap.md:135,224` — the open, non-blocking roadmap question: how the onboarding briefing differs from the delta briefing in structure/length — owner: user, doesn't block planning but should get an answer during `/10x-plan`'s interview.
- `context/changes/user-registration-and-login/plan.md:342` — the mail-health-indicator incident this slice's AI-health-indicator risk directly parallels.
- No `context/foundation/lessons.md` exists yet — nothing to apply as a prior here, but this slice (external API health indicator, external HTTP ingestion, LLM cost/latency) is a strong candidate to seed the file's first entries from.

## Related Research

- `context/archive/2026-09-13-create-topic-and-select-sources/plan.md` / `plan-brief.md` — S-02's plan, the direct predecessor whose "out of scope" list hands off exactly to this slice.
- No prior `research.md` exists for S-02 (it went straight from `change.md` to `plan.md`) — this is the first `/10x-research` invocation in the project's history.

## Open Questions

1. **Onboarding vs. delta briefing structure/length** (PRD Open Question 2, roadmap non-blocking) — needs a user decision during `/10x-plan`'s interview; PRD only says the onboarding briefing is "longer" and an "initial state summary."
2. **RSS/Atom parsing approach** — no library chosen yet (Rome is the standard JVM choice but nothing has evaluated it against this repo's needs).
3. **"New content since last briefing" mechanic** — needs a concrete rule (e.g. compare each feed item's published-date against the topic's `lastBriefingAt`) — undecided anywhere in existing docs.
4. **Structured-output approach for the LLM call** — `BeanOutputConverter`/JSON-schema output vs. a prompt-instructed format the app parses itself; nothing in the codebase precedents this yet.
5. **Model choice** (cost vs. quality) — directly trades off against the documented $10-25/mo budget guardrail; needs to be an explicit planning decision, not a default.
6. **HTMX now or defer** — whether this slice introduces real HTMX/SSE live-feedback wiring, or ships a simpler synchronous-POST experience first and defers the "continuous visible feedback" NFR's fuller treatment to a later pass.
7. **AI health-indicator risk** — needs a concrete property-name check during implementation (verify Spring AI 2.0's actual Actuator health-indicator key) and a decision on whether to disable it proactively, mirroring the mail-indicator fix.
