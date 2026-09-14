# Frame Brief: Should OAuth (Google/Facebook) move into S-01's scope now?

> Framing step before /10x-plan. This document captures what is *actually*
> at issue, separated from what was initially assumed.

## Reported Observation

`context/changes/user-registration-and-login/research.md`'s follow-up research found that Google + Facebook OAuth2 login is low-friction at the Spring Security code level (hours, identical `CommonOAuth2Provider` presets, shared account-linking/provisioning pattern) — while the project's existing scope decision (PRD FR-001, `tech-stack.md`, and the research document's own initial framing) treats OAuth as "optional"/deferred, with S-01 planned as email/password only.

## Initial Framing (preserved)

- **User's stated cause or approach**: the existing "OAuth is optional, defer it" framing was set (in `tech-stack.md`, 2026-09-09) before anyone knew how cheap OAuth actually is at the code level — the new friction data might mean that framing is now outdated.
- **User's proposed direction**: check whether "email/password now, OAuth deferred" is still the right scope for S-01, now that the friction data has changed, before locking it into a plan.
- **Pre-dispatch narrowing**: treat Google and Facebook as one bundled scope decision (both in, or both deferred, together) — not evaluated separately.

## Dimension Map

The observation could originate at any of these dimensions:

1. **PRD/roadmap definition-of-done** — does FR-001 actually require OAuth, or is email/password alone a complete implementation of "email + password or OAuth"? Tests whether the framing is even in question at all.
2. **Timeline/sequencing risk** — roadmap.md notes a "push through F-01 → S-01 → S-02 in one push" window; does OAuth's console-side friction (Facebook Live Mode, Business Verification) threaten that, even though the code itself is cheap?
3. **Schema/migration evolution risk** — is deferring OAuth a clean, additive change later (nullable column + new table), or would deferring force a costly retrofit against the DDD `UserId`-only rule S-02 will rely on?
4. **Testing/operational complexity** — does adding OAuth in this slice require new test infrastructure (mocking Google/Facebook token endpoints) beyond what `TestcontainersDatasourceConfig` already covers (DB only, not external HTTP)?

## Hypothesis Investigation

| Hypothesis | Evidence | Verdict |
| --- | --- | --- |
| 1. OAuth is required for FR-001/S-01 "done" | `prd.md:56`: "email + password **or** OAuth" (must-have); `tech-stack.md:33`: social login explicitly called "optional"; `roadmap.md:98,105-106`: S-01's Outcome mirrors the either-or phrasing, Unknowns/Blockers both empty. No success criterion, NFR, or roadmap field ties OAuth to "done." | NONE — OAuth is genuinely optional, not a hidden requirement |
| 2. Adding OAuth now threatens the timeline | MVP deadline is 2026-10-14 (~33 days out) — not itself tight; the "one push" language applies to near-term capacity, not a hard constraint (roadmap.md's own Open Question #5 resolution: "recorded as fact, not a scheduling rule"). Google needs no review (minutes-to-hours). Facebook's worst case (App Review, days-to-weeks) applies only if requesting permissions beyond the two review-free defaults. F-01's own Phase 3 (the closest precedent for manual browser-only external-service setup — Supabase + Render) shows zero recorded friction in `change.md`'s Notes, unlike Phases 1-2 which do document friction. | WEAK — manageable, especially with Google sequenced first and Facebook as an isolable fast-follow |
| 3. Deferring OAuth forces a costly retrofit | `UserId`/PK strategy unaffected either way (BIGSERIAL regardless of auth method). `password_hash` nullable is a backward-compatible ALTER (no existing NOT NULL data at risk). `user_oauth_connections` is a purely additive `V2__*.sql` migration — Flyway's own model never touches `V1`. Spring Security's `.formLogin()`/`.oauth2Login()` are independent customizers on the same `SecurityFilterChain` bean signature (confirmed via Context7-sourced Spring Security 7.0 docs in research.md) — adding OAuth later doesn't restructure anything already built. | NONE/WEAK — deferring is cleanly additive, not a retrofit |
| 4. Adding OAuth now needs new test infrastructure | `spring-security-test` (already a dependency) ships `SecurityMockMvcRequestPostProcessors.oauth2Login()` out of the box — covers "is access control correct for a logged-in OAuth2 user" with zero new dependencies. The actual redirect→token-exchange flow would need an HTTP-stubbing library (WireMock/MockWebServer) that doesn't exist in this repo yet — and neither does one exist for the already-present OpenAI/Spring AI dependency, so this gap isn't new or OAuth-specific. | WEAK — some new work, but modest, with existing precedent to model it on |

## Narrowing Signals

- FR-001's exact wording uses "or", not "and both required" — decisive on its own for hypothesis 1.
- `change.md`'s Notes document friction for F-01 Phases 1-2 but record nothing for Phase 3 (the manual browser-only step) — the closest available signal that manual external-service setup in this project doesn't reliably cause delay.
- Every dimension checked for "deferring is costly" (hypothesis 3) came back additive/backward-compatible with no exception found.
- Cross-system check (see below): this project has an established, actively-used convention for tracking exactly this kind of optional/deferred scope — and OAuth isn't in it.

## Cross-System Convention

This project already tracks every other explicitly-deferred or optional piece of scope in a dedicated `## Parked` section in `context/foundation/roadmap.md` (lines ~211-217), each with a "Why parked" rationale: the optional topic-description field (FR-004), the nice-to-have "stop watching a topic" (FR-014), and all four PRD Non-Goals (custom RSS feeds, real-time alerts, multi-user collaboration, source bias scoring).

**OAuth/social login — explicitly called "optional" in `tech-stack.md:33` and phrased as either-or in `prd.md:56` and `roadmap.md:98` — is the one exception.** It has no `## Parked` entry. It exists only as scattered prose across three documents, none of which is the place this project actually looks to know what's deferred and why.

## Reframed (or Confirmed) Problem Statement

> **The actual problem to plan around is**: S-01's scope is correct as originally framed — the initial framing held up. The real, evidenced gap this investigation surfaced is a small documentation-hygiene one: OAuth has no `## Parked` entry in `roadmap.md`, unlike every other deferred scope item in this project.

The scope question itself resolved cleanly: OAuth is genuinely optional (not a hidden requirement), deferring it costs nothing structurally (additive migrations, no filter-chain restructuring, unaffected `UserId`/PK strategy), and the timeline risk of adding it now is only weak/manageable at best — so there's no evidence-backed case for either pulling it into S-01 now *or* treating deferral as risky. What would change if the gap above is addressed: a future reader of `roadmap.md` (including a future `/10x-roadmap` or `/10x-plan` invocation) would see OAuth's deferred status and rationale in the one place this project's own convention says to look, instead of having to piece it together from `tech-stack.md` prose — exactly the kind of thing `## Parked` exists to prevent.

## Confidence

**HIGH** — strong evidence on all four dimensions, no contradicting signal found, the evidence converges with an existing, actively-used project convention (the `## Parked` section pattern) rather than against it, and the narrowing signal (FR-001's literal "or") is decisive on its own.

## What Changes for /10x-plan

Nothing changes for `/10x-plan user-registration-and-login`'s scope — proceed with email/password-only per the existing research.md. The one recommended action is a small, separate housekeeping edit to `context/foundation/roadmap.md`'s `## Parked` section adding an OAuth/social-login entry (e.g. "Why parked: optional per `tech-stack.md`; Google is a near-free fast-follow once S-01 ships, confirmed cheap by research — no rush to build now"), consistent with how every other deferred item in this project is tracked. This is a documentation fix, not a plan phase — it can be done directly, independent of S-01's implementation plan.

## References

- Source files: `context/foundation/prd.md:56-58,114-116`; `context/foundation/tech-stack.md:33`; `context/foundation/roadmap.md:96-107,209-217`; `context/changes/user-registration-and-login/research.md` (full document, especially the OAuth follow-up section).
- Related research: `context/changes/user-registration-and-login/research.md`
- Investigation tasks: 4 parallel sub-agents (FR-001 definition-of-done; timeline/sequencing risk; schema/migration evolution risk; testing/operational complexity) — see the Hypothesis Investigation table above for their individual verdicts.
