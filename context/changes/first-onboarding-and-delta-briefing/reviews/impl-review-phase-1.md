<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: First Onboarding and Delta Briefing

- **Plan**: context/changes/first-onboarding-and-delta-briefing/plan.md
- **Scope**: Phase 1 of 4
- **Date**: 2026-09-13
- **Verdict**: APPROVED
- **Findings**: 0 critical, 0 warnings, 3 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | PASS |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS (automated: 4/4 pass; manual 1.5 still pending confirmation — expected mid-flow, not a defect) |

## Findings

### F1 — `BriefingSummary` landed in a different package than planned

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: src/main/java/pl/tul/deltabrief/briefing/application/port/out/BriefingSummary.java
- **Detail**: The plan's file list put `BriefingSummary.java` under `adapter/out/persistence/`. It actually landed under `application/port/out/`, alongside the `BriefingRepository` port whose `findSummariesByTopicId` signature returns it. This is arguably the better location — it keeps `application` free of adapter-package imports, consistent with how this repo's other ports (e.g. `FeedSource` alongside `FeedSourceCatalog`) already colocate a port's return-type records with the port itself.
- **Fix**: None needed — accept the actual location as correct; the plan's file list was slightly off, not the code.
- **Decision**: ACCEPTED — actual location kept, no code change.

### F2 — `briefings.topic_id` has no `ON DELETE` cascade

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/resources/db/migration/V8__create_briefings_table.sql:3
- **Detail**: Deleting a topic that already has briefings will fail on this FK constraint (no cascade, no restrict/set-null behavior defined beyond Postgres's default `NO ACTION`). This exactly matches the existing convention in this repo (`topics.user_id → users(id)` in V7 also has no cascade), so it's not a new risk this phase introduced — but it's worth having an answer ready for whenever topic deletion is built (parked in the roadmap as FR-014, "nice-to-have").
- **Fix**: None needed now — track it against the eventual topic-deletion slice, not this phase.
- **Decision**: FIXED — added `ON DELETE CASCADE` to `briefings.topic_id` in V8. Reopened during triage: unlike `topics.user_id` (no live user-deletion feature), topic deletion is already a shipped feature (`TopicController.deleteTopic`), so a topic with existing briefings must remain deletable — verified via full test suite re-run, still green.

### F3 — Per-item save loop in `BriefingRepositoryAdapter.save()`

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/tul/deltabrief/briefing/adapter/out/persistence/BriefingRepositoryAdapter.java:36-38
- **Detail**: `save()` calls `ingestedItemJpaRepository.save(...)` once per ingested item rather than a batched `saveAll(...)`. N+1-shaped, but the list is capped to a small number of items per source per plan.md's "Performance Considerations" — not a real hotspot at this scale, and the whole method is `@Transactional` so there's no partial-write risk.
- **Fix**: None needed — `saveAll(...)` would be a trivial swap later if this ever shows up as a real cost, but isn't worth doing preemptively.
- **Decision**: FIXED — switched to `ingestedItemJpaRepository.saveAll(...)`. Verified via full test suite re-run, still green.
