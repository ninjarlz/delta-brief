<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: First Onboarding and Delta Briefing

- **Plan**: context/changes/first-onboarding-and-delta-briefing/plan.md
- **Scope**: Phase 2 of 4
- **Date**: 2026-09-13
- **Verdict**: APPROVED
- **Findings**: 0 critical, 0 warnings, 2 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | PASS |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS (automated: 3/3 pass; manual 2.4 still pending confirmation — expected mid-flow, not a defect) |

## Findings

### F1 — `feedUrl` is fetched without an SSRF-relevant note

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/tul/deltabrief/briefing/adapter/out/rss/RomeSourceContentFetcher.java:37
- **Detail**: `URI.create(source.feedUrl()).toURL().openConnection()` opens an arbitrary URL from `sources.feed_url`. Not exploitable today — that column is migration-seeded reference data with no write path anywhere in this diff or the app — but the assumption ("trusted, curated URLs only") isn't written down anywhere, so a future phase that ever lets users submit custom feed URLs could silently invalidate it.
- **Fix**: Add a one-line comment on `SourceContentFetcher`/`RomeSourceContentFetcher` recording the trust assumption, so it's visible to whoever touches this later.
- **Decision**: FIXED — added a "Trust assumption" javadoc note to `SourceContentFetcher`.

### F2 — `FeedSourceCatalogAdapter` maps inline instead of via a MapStruct mapper

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/main/java/pl/tul/deltabrief/briefing/adapter/out/persistence/FeedSourceCatalogAdapter.java:18-20
- **Detail**: Every other persistence adapter in this codebase (`CategoryRepositoryAdapter`, `BriefingRepositoryAdapter`) injects a dedicated MapStruct `@Mapper`. `FeedSourceCatalogAdapter` maps inline (`new FeedSource(entity.getName(), entity.getFeedUrl())`) instead — a deliberate simplification given it's a trivial two-field construction with no ID-record conversion, not an oversight, but it does diverge from the established "always use a mapper" pattern.
- **Fix**: Add a one-line comment on the inline mapping explaining why it skips a dedicated mapper class, so a future reviewer doesn't mistake it for a gap.
- **Decision**: FIXED (Fix differently) — added `FeedSourceEntityMapper` (MapStruct), matching every other adapter's pattern exactly. Verified via full test suite re-run, still green.
