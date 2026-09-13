<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Create Topic and Select Sources (S-02)

- **Plan**: context/changes/create-topic-and-select-sources/plan.md
- **Scope**: Full plan (Phases 1-3)
- **Date**: 2026-09-13
- **Verdict**: APPROVED
- **Findings**: 0 critical, 2 warnings, 0 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

## Findings

### F1 — Redundant `categoryRepository.findAll()` call in `TopicController.listTopics`

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality (Performance)
- **Location**: `src/main/java/pl/tul/deltabrief/topic/adapter/in/web/TopicController.java:48`
- **Detail**: The `@ModelAttribute("categories")` method (lines 41-43) already runs once before every handler in this controller, populating `"categories"` in the `Model`. `listTopics` (line 47) additionally calls `categories()` directly at line 48 to build a name-lookup map, so `categoryRepository.findAll()` executes twice per `GET /`. Real-world cost is negligible (4 rows today), but it's a redundant query and a pattern that would compound if the categories table ever grows or gains a more expensive query.
- **Fix**: Inject the already-populated model attribute as a method parameter instead of calling `categories()` again — `listTopics(Authentication authentication, Model model, @ModelAttribute("categories") List<Category> categories)` — and use that parameter at line 48 instead of a fresh `categories()` call.
- **Decision**: FIXED

### F2 — Per-user topic cap enforced via check-then-act, not backed by a DB constraint

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality (Reliability)
- **Location**: `src/main/java/pl/tul/deltabrief/topic/application/TopicService.java:44` (method starts line 37)
- **Detail**: `createTopic` checks `topicRepository.countByUserId(userId) >= MAX_TOPICS_PER_USER` then saves — a classic check-then-act race. Two concurrent `createTopic` calls for the same user could both pass the count check before either commits, allowing the 20-topic cap to be exceeded. Unlike the duplicate-name case, which is backed by a real DB unique index (`topics_user_id_lower_name_uk` in `V7__create_topics_table.sql`), there's no DB-level backstop for the cap.
- **Fix A ⭐ Recommended**: Accept as a soft/best-effort limit; add a one-line code comment at the check documenting the known race and that it's an accepted tradeoff.
  - Strength: Zero additional complexity; matches this project's already-demonstrated pattern of proportionate-to-scale tradeoffs (e.g. the email task executor's small fixed pool, the rate limiter's in-memory-only design, both explicitly documented as "revisit later" in this project's plans) — a hard guarantee here would need pessimistic locking or a trigger, disproportionate for a soft UX guardrail rather than a security or data-integrity boundary.
  - Tradeoff: A user issuing rapid concurrent requests (a buggy client retry loop, or someone deliberately racing it) could exceed 20 topics by a small amount. No data corruption, no cross-user impact — worst case is one user's list running slightly over cap.
  - Confidence: HIGH — this is exactly the class of tradeoff this codebase already makes explicitly elsewhere.
  - Blind spot: Haven't measured how much real concurrent-request risk exists in practice today (single browser tab vs. scripted abuse) — if this project later exposes a public API beyond server-rendered pages, this tradeoff should be revisited.
- **Fix B**: Enforce atomically — e.g. a `SELECT ... FOR UPDATE` on a per-user lock row wrapping check+insert, or a DB-level trigger/check constraint.
  - Strength: Closes the race entirely, matching the rigor already applied to the duplicate-name case.
  - Tradeoff: More implementation complexity (a lock strategy or trigger) for a low-severity, low-likelihood race on a non-security-critical soft cap.
  - Confidence: MEDIUM — a `SELECT FOR UPDATE` approach is straightforward in shape but needs a concrete lock target (no natural row to lock for "count of rows" today).
  - Blind spot: Haven't checked whether Postgres's default isolation level (READ_COMMITTED, per this project's Hibernate config) would need broader changes elsewhere to support a simpler serializable-transaction fix cleanly.
- **Decision**: ACCEPTED (Fix A) — documented the known race with a code comment at the check site; no functional change.

## Notes

Both review sub-agents (plan-drift detection and safety/quality/pattern compliance) independently confirmed:
- **Plan Adherence**: all 28 files across Phases 1-3 match the plan's stated Intent/Contract; owner-scoping happens at the query level as required; business-rule ordering (category-exists → duplicate-name → cap) matches the plan exactly.
- **Scope Discipline**: no scope creep found; "What We're NOT Doing" (no per-source selection, no topic editing, no admin UI, no source ingestion) confirmed respected.
- **Pattern Consistency**: the new `topic` module mirrors `auth`'s layering, MapStruct mapper conventions, visibility choices, and test signatures (shared Testcontainers context) with no substantive mismatches.
- **Security**: no SQL injection surface (derived queries only, no string-concatenated SQL); CSRF handled the same way as existing auth forms; delete-by-unowned-id behaves identically to delete-by-unknown-id (no existence oracle).

Files touched by this plan's own commits (`15b82cc`, `dcd53a3`) have since received separate, already-approved follow-up work (Lombok annotations, navbar/UI redesign) — both agents were briefed to distinguish that from genuine drift and did so; no findings here are artifacts of that later work.
