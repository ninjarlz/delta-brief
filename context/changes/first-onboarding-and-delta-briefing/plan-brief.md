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

## Scope

**In scope:** RSS/Atom ingestion (Rome), `Briefing`/`IngestedItem` domain + persistence, OpenAI structured-output generation with anti-hallucination prompt design, manual-trigger web flow, minimal inline history list, WireMock-backed automated tests.

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

## Success Criteria (Summary)

- A user can generate an onboarding briefing for a new topic and a delta briefing for an existing one, and read both in the app.
- A single unreachable source or AI hiccup doesn't take down the whole feature — both degrade gracefully with clear feedback.
- Every claim in a generated briefing is traceable to a listed, ingested source — no unattributed fabrication.
