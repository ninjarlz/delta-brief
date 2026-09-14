<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: First Onboarding and Delta Briefing — Addendum 2

- **Plan**: context/changes/first-onboarding-and-delta-briefing/plan.md
- **Scope**: Addendum 2 ("Post-Merge Refinements") — PR #42, commit `db2bdb7..f77cfb6`. Phases 1-4 and Addendum 1 were already reviewed in prior reports (`reviews/impl-review-phase-1.md`, `-phase-2.md`, `-phase-3.md`, `impl-review.md`) and are not re-reviewed here.
- **Date**: 2026-09-14
- **Verdict**: NEEDS ATTENTION
- **Findings**: 0 critical, 3 warnings, 1 observation

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | WARNING |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | WARNING |

## Findings

### F1 — Empty "Sources" section when the model cites nothing

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/tul/deltabrief/briefing/adapter/in/web/BriefingController.java:140-153 (`citedSources`), src/main/resources/templates/briefing.html:48-56 (Sources section)
- **Detail**: `citedSources()` filters to only items referenced via `[n]` markers in the six generated sections. Nothing in `BriefingPromptBuilder` *guarantees* the model emits at least one citation — it's instructed to, not enforced. If a real generation produces zero citations, `citedSources()` returns an empty list and the "Sources" heading renders an empty `<ol>` with no fallback text — looks broken to a user. Before this addendum, every ingested item was always shown, so this is a new failure mode introduced by the citation-filtering change (Addendum 2, item 1). No automated test exercises the zero-citation case; `BriefingFlowIntegrationTests`'s stub was specifically updated to include a `[1]` marker to keep its assertion passing, which means this exact gap was worked around rather than covered.
- **Fix A ⭐ Recommended**: Add a fallback message ("No sources were explicitly cited in this briefing.") when the cited list is empty.
  - Strength: Cheap, honest about what happened, preserves the whole rationale for filtering (showing only what's actually grounding the claims) rather than undermining it.
  - Tradeoff: A user who wanted to see everything ingested has no way to get that from this page anymore, though that was already true after Addendum 2 shipped.
  - Confidence: HIGH — one template conditional, no behavior change to the filtering logic itself.
  - Blind spot: Haven't measured how often `gpt-4o-mini` actually omits citations in practice — if it's rare, this is low-priority polish; if common, it's a real recurring blank state.
- **Fix B**: Fall back to showing all ingested items when zero citations are found.
  - Strength: Never shows a blank Sources section.
  - Tradeoff: Reintroduces exactly the "signal drowned in noise" problem Addendum 2 item 1 was written to fix, silently and inconsistently (works differently depending on model output).
  - Confidence: MEDIUM — technically simple, but works against the addendum's own stated intent.
  - Blind spot: None significant.
- **Decision**: FIXED via Fix A — `briefing.html` now shows "No sources were explicitly cited in this briefing." when the cited list is empty (`.briefing-sources__empty` style added to `app.css`). Verified via `BriefingFlowIntegrationTests` still passing; a live generation during F3's triage also confirmed the citation filter genuinely produces a small subset (4 of 40 items), making this a real, not theoretical, code path.

### F2 — Undocumented login.html change bundled into this PR

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Scope Discipline
- **Location**: src/main/resources/templates/login.html:19-22
- **Detail**: Adds a "Don't have an account yet? Register now!" link inside the login-error notice. This is a real, deliberate, low-risk change, but it is not mentioned anywhere in Addendum 2's seven numbered items or its "Files touched" list — both review sub-agents independently flagged it as present in the diff but absent from the plan's documentation. Same pattern as F2 in this change's original full-plan review (an undocumented `/api`-adjacent addition), which was resolved by documenting it in the plan rather than reverting it.
- **Fix**: Add an 8th item to Addendum 2 documenting the login-failure registration nudge, matching the existing addendum format (what changed, why, file touched).
- **Decision**: FIXED — Addendum 2 item 8 added to plan.md documenting the change and its rationale; `login.html` added to the "Files touched" list.

### F3 — Manual verification never completed despite merge to main

- **Severity**: ⚠️ WARNING
- **Impact**: 🔬 HIGH — architectural stakes; think carefully before deciding
- **Dimension**: Success Criteria
- **Location**: context/changes/first-onboarding-and-delta-briefing/plan.md, "Post-Merge Refinements" Progress section, items 5.2-5.6
- **Detail**: Automated checks (5.1) are verified and checked off. All five manual-verification items — cited-sources rendering, back-link/title placement, generation spinner, Google News feed actually contributing items, and delta-prompt behavior against two real generations — are still `[ ]` unchecked, and the code is already live on `main` (merged as PR #42). This project's own established convention treats manual verification as load-bearing specifically because it catches real-world behavior automated tests can't (this exact plan's Phase 2 BBC-redirect bug and Phase 3 hallucination check were both manual-only catches). The two riskiest unverified items are 5.5 (does the Google News RSS endpoint actually return usable, on-topic results for real topic names — this app has zero automated coverage of Google's actual response shape, only a WireMock stub) and 5.6 (does the strengthened delta-comparison prompt actually change model behavior in a real call, or does `gpt-4o-mini` ignore the added instruction).
- **Fix**: Perform 5.2-5.6 against the live app now that it's deployed, and check off (or revise) each item based on what's actually observed — particularly 5.5 and 5.6, which are the two items most likely to reveal something the code-level review can't (real Google News response quality; real model compliance with the new prompt instruction).
- **Decision**: FIXED (mostly) — drove a live end-to-end check against the real running app, real OpenAI, and real Google News (topic "Ukraine war", World News category). Results:
  - 5.2 CONFIRMED: 40 items ingested, only 4 actually cited and shown.
  - 5.3 CONFIRMED: title rendered "Ukraine war #1" → "Ukraine war #2" across two generations; back-link and subtitle format as designed.
  - 5.4 STILL PENDING: not verifiable via curl — requires an actual browser to see the CSS spinner animation. HTML/JS wiring itself was independently confirmed present by the plan-drift review agent, but the visual behavior still needs a human glance.
  - 5.5 CONFIRMED, and strikingly so: 10 of the 40 ingested items came from "Google News: Ukraine war," essentially all genuinely on-topic — versus only ~2 of the 30 category-feed items being Ukraine-related. Directly demonstrates the relevance problem this addendum set out to fix.
  - 5.6 CONFIRMED with a concrete example: the delta briefing (generated ~10s after the onboarding one, same underlying news pool) produced key-changes text reading exactly "No significant change since the prior briefing." — the explicit no-change instruction working in a real call, not just passing a unit-test assertion.

## Observations

### O1 — One more synchronous, blocking fetch added to the generation request path

- **Severity**: OBSERVATION
- **Dimension**: Safety & Quality (Performance)
- **Location**: src/main/java/pl/tul/deltabrief/briefing/application/BriefingService.java:88 (`ingestSources`)
- **Detail**: The Google News search feed is appended to the same source list and fetched through the same sequential loop as the curated category feeds, extending the already-synchronous generation request by up to ~5s worst-case (the existing connect/read timeout) in the failure case. This is consistent in kind with the existing accepted tradeoff (plan.md's "Synchronous generation and request threads"), not a new category of risk — noted for awareness as the source count per generation grows, not as something requiring action now.

## What went right (no findings)

- **Security**: `GoogleNewsSearchFeedProvider` correctly URL-encodes the user-entered topic name (`URLEncoder.encode`, UTF-8) before interpolating it into the search URL — no injection/SSRF vector. The citation-parsing regex (`\[(\d+)]`) is a simple bounded pattern, not ReDoS-susceptible even against adversarial LLM output.
- **Correctness**: The ordinal computation (`history.size() - descendingIndex`) was independently verified against both boundary cases (oldest briefing → 1, newest → size) with no off-by-one; duplicate and out-of-range citation numbers are both handled correctly by the citation filter.
- **Reliability**: The new external source reuses the exact same `SourceContentFetcher`/timeout/skip-on-failure machinery as every other source — no bespoke HTTP client, no new failure mode.
- **Data safety**: No migration files in this diff.
- **Pattern compliance**: The new port/adapter pair matches sibling conventions (public port interface, package-private `@Component` adapter); the adapter's explicit-constructor-with-`@Value` style matches existing precedent (`RegistrationService`, `ResendSmtpEmailSender`) rather than deviating from it.
