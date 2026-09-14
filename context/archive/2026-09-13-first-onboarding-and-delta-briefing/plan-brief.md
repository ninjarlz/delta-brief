# First Onboarding and Delta Briefing — Plan Brief

> Full plan: `context/changes/first-onboarding-and-delta-briefing/plan.md`
> Research: `context/changes/first-onboarding-and-delta-briefing/research.md`

## What & Why

This is roadmap slice S-03 — DeltaBrief's "go/no-go slice." A user can trigger generation of an onboarding briefing for a new topic, then a delta briefing that compares fresh source content against the prior one, and read both in the app. It's the first place the product's core value (separating genuine change from noise) actually gets built.

## Starting Point

`auth` and `topic` are built and working; `briefing` doesn't exist yet. `topic.domain.Source` already holds real RSS feed URLs (DB-seeded) but nothing reads it. Spring AI is a wired dependency with zero code calling it. No RSS parsing, no ingestion, no HTMX wiring exists.

## Desired End State

From the topics list, a user clicks "Generate briefing." The app fetches the topic's sources, generates a 7-section briefing (key changes, trend continuation, noise/speculation, significance, uncertainties, source impact, sources) via OpenAI, and shows it — plus a small list of that topic's past briefings. A dead source doesn't block generation; an AI failure shows a clear retry option.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Onboarding vs. delta structure | Same 7-section template, "no baseline" placeholders for onboarding | One prompt/template to build and test; users learn one consistent structure | Plan |
| AI model | Cost-efficient mini-class model (`gpt-4o-mini`), fixed | Keeps LLM cost near $2/mo, well inside the $10-25/mo budget guardrail | Plan |
| Content ingestion | Fetch + persist ingested items per briefing | Gives every claim a durable, queryable source — serves the hard anti-hallucination/traceability guardrail | Plan |
| Generation trigger UX | Synchronous POST + redirect (no HTMX/SSE) | Zero new frontend dependency; app-scale QPS makes a 10-30s sync request safe | Plan |
| Partial source failure | Proceed with whatever sources succeeded | One flaky public RSS feed shouldn't block the whole feature | Plan |
| AI call failure | Clear error + manual retry (no auto-retry) | Matches this repo's existing error-handling convention; retry is cheap since items are persisted | Plan |
| Previous-briefing lookup | Query `BriefingRepository` by `topicId`, no denormalized pointer on `Topic` | Matches this codebase's owner-scoped-query convention; avoids a cross-module write-coupling | Plan |
| Test strategy for AI/RSS calls | WireMock record/replay style stubs | Higher-fidelity than hand-written fakes; Spring AI's `base-url` override makes this practical | Plan |
| Post-generation UI scope | Latest briefing + a minimal inline history list | Delivers more of US-01 sooner while staying clearly smaller than S-05's real history page | Plan |
| Cross-module data access | `briefing` owns its own `FeedSource`/`FeedSourceCatalog` read model over the `sources` table; never imports `topic.domain.Source`/`Topic` | Keeps AGENTS.md's "ID-only cross-module reference" rule intact without inventing a heavier anti-corruption layer | Plan |
| Topic description (FR-004) | Unparked and added mid-Phase-4: optional `Topic.description`, fed into the generation prompt as extra context | The roadmap's original deferral reason ("AI doesn't use it, dead field") no longer applies once generation exists; user explicitly asked to fold it in rather than open a separate change | Plan (addendum) |
| Source relevance (post-merge) | Added a per-topic Google News RSS search feed alongside (not replacing) the curated category feeds | Category feeds carry zero topic-relevance signal; free and reuses the existing RSS pipeline unchanged, vs. a paid semantic-search API | Plan (addendum 2) |
| Delta-comparison prompt (post-merge) | Strengthened the DELTA-mode instruction with explicit anti-restatement + "no genuine change" guidance | The original one-sentence instruction let the model restate old key-changes as if new, or leave "no change" vague — undermining the product's core "delta, not summary" premise | Plan (addendum 2) |
| Source fetch concurrency (post-merge) | Parallelized `ingestSources` via Java 21 virtual threads instead of sequential fetching | Sequential fetching made latency the sum of every source's fetch time (~8s baseline → ~20s once Google News was added); parallel fetching bounds it to the slowest single source (~3-5s live-verified across many runs) | Plan (addendum 3) |
| Google News as filler, not a competing source (post-merge) | Reframed the prompt so curated sources are always tried first, with Google News cited only when no curated source addresses a claim at all; doubled curated sources per category (`V11`) | A local keyword pre-filter and a tie-breaker instruction were both tried first and failed live (0/4 runs cited any curated source) — Google News' items are almost always somewhat more specific, so a "prefer when equal" bar rarely fires; framing it as a strict fallback instead of a peer candidate is what actually worked (0/3 runs cited Google News after) | Plan (addendum 3) |

## Scope

**In scope:** RSS/Atom ingestion (Rome), `Briefing`/`IngestedItem` domain + persistence, OpenAI structured-output generation with anti-hallucination prompt design, manual-trigger web flow, minimal inline history list, WireMock-backed automated tests, an optional topic-description field (FR-004) feeding the generation prompt, and (post-merge) a per-topic Google News search feed + cited-sources-only display + generation-feedback/navigation UX polish.

**Out of scope:** Scheduled generation (S-04), email delivery (S-06), rating (S-07), full history browsing (S-05), per-topic source customization (parked), HTMX/SSE live progress, automatic retry-with-backoff, model-switching automation.

## Architecture / Approach

`briefing` is a new bounded-context module following the exact layering `auth`/`topic` already use. `Briefing` is the aggregate root; `IngestedItem` is a child entity within its boundary (no separate repository). Ingestion (Rome-based fetch) and generation (Spring AI `ChatClient`) each sit behind their own output port, so `BriefingService` orchestrates: resolve topic ownership + category → fetch sources → ingest content (tolerating partial failure) → generate structured content → persist → return. The web layer is a thin `@Controller` doing synchronous POST-and-redirect, matching `TopicController`'s shape.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Domain & persistence foundation | `Briefing`/`IngestedItem` model, migrations, `topic` ownership+category lookup | Cross-module data-access shape must hold up under `/10x-impl-review` scrutiny |
| 2. Content ingestion | Rome-based RSS fetch, tolerant of per-source failure | Real-world feed quirks the WireMock tests won't catch |
| 3. AI generation | `ChatClient` structured-output call, anti-hallucination prompt | The actual quality of the classification — the product's core bet |
| 4. Orchestration & web | `BriefingService`, controller, templates, full flow tests | Sync-request latency feel; error/retry UX is new to this codebase |

**Prerequisites:** S-02 (done) — topics, categories, and sources must already exist to generate against. A real, budget-capped OpenAI API key for the Phase 3/4 manual verification steps.
**Estimated effort:** 4 phases, each independently commit-able — likely the largest single change this project has attempted so far given it's a from-scratch bounded-context module plus a first-ever external AI integration.

## Open Risks & Assumptions

- Real RSS feeds may have quirks (malformed XML, missing publish dates, paywalled/truncated content) that only Phase 2's manual live-feed check will surface.
- `gpt-4o-mini`'s actual classification quality (genuine change vs. trend vs. noise) is unverified until Phase 3's manual real-API check — if it's not good enough, this plan doesn't include an automated fallback, only a manual model-config change later.
- The cross-module `FeedSourceCatalog` design (a second, `briefing`-owned read model over the `sources` table) is a genuine architectural judgment call, not dictated by precedent — flagged explicitly for review.
- User-authored free text (topic name, and now the optional description) is interpolated into the generation prompt — a real prompt-injection surface, mitigated via an explicit "verbatim data, not instructions" guardrail + delimited blocks (added during Phase 3's impl-review), but not a hard guarantee against a sufficiently adversarial input.
- The Google News RSS search endpoint (post-merge addendum 2) is free and unauthenticated but *unofficial* — not a documented, stable API. It could change format or get rate-limited without notice; degrades the same as any other flaky source (skipped, contributes nothing) rather than failing generation, but is a materially different reliability posture than the hand-picked curated feeds.

## Success Criteria (Summary)

- A user can generate an onboarding briefing for a new topic and a delta briefing for an existing one, and read both in the app.
- A single unreachable source or AI hiccup doesn't take down the whole feature — both degrade gracefully with clear feedback.
- Every claim in a generated briefing is traceable to a listed, ingested source — no unattributed fabrication.
