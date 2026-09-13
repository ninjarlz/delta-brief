# Create Topic and Select Sources — Plan Brief

> Full plan: `context/changes/create-topic-and-select-sources/plan.md`

## What & Why

Adds the `topic` bounded context (roadmap slice S-02): a user can create a watched topic with a preset category, browse their topic list, and delete a topic. This is the first per-user resource in the app beyond auth itself, and it retires the temporary placeholder home page.

## Starting Point

Only `auth` exists as a real module today. `PlaceholderController` serves `/` as a stand-in, explicitly documented to be deleted once `topic` ships a real home page. `AppUserDetails` (the Spring Security principal) currently carries only email — no user ID — so this plan extends it.

## Desired End State

An authenticated user lands on `/` and sees their topics (or an empty state with a "Create topic" prompt). They can create a topic by name + category (picked from 4 DB-seeded categories with real, verified RSS sources), and delete topics they own. A user can never see or delete another user's topic.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
|---|---|---|---|
| Preset source content | 4 categories (World News, Technology, Business & Finance, Science), real RSS feeds, verified live via curl | Unblocked the roadmap's only blocking unknown for this slice | Plan (user decision) |
| Topic-source data model | Category-level selection (topic → one category); individual per-source picking deferred | Simplest v1 that satisfies FR-006 without premature flexibility | Plan (user decision) |
| Category/source storage | Real DB tables (`categories`, `sources`), seeded by migration — not code constants | Future "customize sources" work becomes a data change, not a redeploy | Plan (user decision) |
| Home page takeover | Topic list becomes `/` now; placeholder deleted | Matches the placeholder's own documented intent; no lingering dead code | Plan (user decision) |
| Name uniqueness | Enforced per-user, case-insensitive | Prevents confusing duplicate topics in one user's list | Plan (user decision) |
| Category/source editability | Fixed at creation for v1 | Matches this project's pattern of deferring non-essential capability | Plan (user decision) |
| Topic cap | 20 per user | Cheap guardrail against runaway topic creation | Plan (user decision) |
| Delete | In scope for this slice | Closes the "oops, wrong category" gap left by fixed-at-creation | Plan (user decision) |
| Testing approach | Mirror `auth` exactly — real Testcontainers Postgres everywhere, no mocks | Consistency with the established, already-documented testing convention | Plan (user decision) |

## Scope

**In scope:** create topic (name + category), browse topic list (new `/`), delete topic, DB-seeded categories/sources, per-user name uniqueness, 20-topic cap, cross-user isolation test.

**Out of scope:** individual per-source selection (deferred, future roadmap item — needs a separate `/10x-roadmap` addition), editing a topic after creation, admin UI for categories/sources, actual source ingestion (comes with briefing generation, S-03+).

## Architecture / Approach

New `topic` module following `auth`'s exact hexagonal layering: `domain` (`Topic`/`TopicId`, `Category`/`CategoryId`, `Source`/`SourceId`) → `application` (`TopicService` + `port.out` interfaces) → `adapter.out.persistence` (JPA + MapStruct, mirroring `UserRepositoryAdapter`) → `adapter.in.web` (`TopicController`, Thymeleaf templates). Cross-user safety comes from owner-scoped queries at the repository level (`WHERE user_id = ?`), not fetch-then-check in application code.

## Phases at a Glance

| Phase | What it delivers | Key risk |
|---|---|---|
| 1. Domain + persistence | `topic` domain types, DB-seeded categories/sources, `topics` table with owner-scoped queries | Getting the case-insensitive uniqueness index and owner-scoping right from the start |
| 2. Application layer | `TopicService` business rules (uniqueness, cap, cross-user isolation); `AppUserDetails` gains `UserId` | The `AppUserDetails` change touching shared auth code — mitigated by re-running the full `AuthFlowIntegrationTests` suite unchanged |
| 3. Web layer + placeholder retirement | `TopicController`, templates, `SecurityConfig` simplification, full-flow + cross-user integration test | First real cross-user authorization test in the codebase — must prove query-level isolation, not just UI-level hiding |

**Prerequisites:** S-01 (auth) — already merged and deployed.
**Estimated effort:** ~3 sessions across 3 phases, similar scope to the `user-registration-and-login` slice.

## Open Risks & Assumptions

- The verified RSS feeds (BBC, Al Jazeera, Guardian, TechCrunch, Ars Technica, CNBC, MarketWatch, ScienceDaily) were live at plan time (2026-09-13) but could change or go down before this ships — low risk, easy to fix via a follow-up migration if so.
- A future "source customization" capability isn't yet a tracked roadmap item — needs a separate `/10x-roadmap` addition (flagged, not done as part of this plan).

## Success Criteria (Summary)

- A user can create, browse, and delete topics end-to-end, with the 4 real preset categories available.
- No user can ever see or delete another user's topic, proven at the query level, not just hidden in the UI.
- The placeholder home page is fully retired with no dead code left behind.
