# Auth Boundary & Abuse-Resistance Coverage — Plan Brief

> Full plan: `context/changes/testing-auth-boundary-abuse-resistance/plan.md`
> Research: `context/changes/testing-auth-boundary-abuse-resistance/research.md`

## What & Why

Rollout Phase 1 of `context/foundation/test-plan.md`: close risks #1 (session boundary), #4 (no rate limiting), and #5 (credential/PII log leakage) with tests — and, for risk #4, a real fix. Research found the picture was more precise than the risk map originally assumed: risk #1's "proof" in the existing test suite is illusory (MockMvc doesn't carry sessions across calls), risk #5's original threat doesn't apply to current code at all, and risk #4 is confirmed wide open with certainty.

## Starting Point

`AuthFlowIntegrationTests` exists and covers register→verify→login→logout, but every "unauthenticated afterward" assertion in it is trivially true regardless of whether real session invalidation happened — no test threads a session across requests. `RegistrationService` has exactly one log statement in the whole codebase, already safe by construction but with no test proving it stays that way. `/register` and `/resend-verification` have zero throttling of any kind, at the app or infra layer.

## Desired End State

Session invalidation is genuinely proven (not just asserted against a request that was never authenticated to begin with) for both a failed login and a logout. A regression test guards the one log call in the codebase against ever leaking a raw exception. Both auth endpoints reject a 6th rapid request from the same email (or the same IP) with a proper 429 page, working correctly both locally and behind Render's proxy in production.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
|---|---|---|---|
| Risk #1 test location | Extend existing `AuthFlowIntegrationTests` | Reuses the exact proven shared-context signature; avoids the fixed-Testcontainers-port risk a new class could trigger | Plan |
| Risk #5 test location & technique | Extend `RegistrationServiceTests`, capture logs via a Log4j2 `ListAppender` | Matches this project's preference for real components over mocks; establishes a reusable log-testing pattern | Plan |
| Risk #4 scope | Add a real fix, not just an observability test | User explicitly chose to close the gap now rather than defer it | Plan (user decision) |
| Risk #4 mechanism | Bucket4j (library), not a hand-rolled counter | User explicitly preferred the battle-tested library over a minimal custom implementation | Plan (user decision) |
| Risk #4 limit keys | Both per-email AND per-IP | User explicitly chose the more complete protection over the simpler single-key option | Plan (user decision) |
| Risk #4 endpoint scope | Both `/register` and `/resend-verification` | Limiting only one endpoint leaves the other fully open to the same abuse | Plan (user decision) |
| Client IP behind Render's proxy | `server.forward-headers-strategy=native` | Without it, every production user shares one IP-bucket via the load balancer's own IP — the per-IP limit would be silently broken | Research + web grounding |
| Rate-limiter test isolation | Unique simulated IP per test via `RequestPostProcessor` | The limiter's buckets live in a shared singleton across the whole test suite's cached context — same sharing risk this project already handles for Testcontainers | Plan |

## Scope

**In scope:**
- Session-boundary tests proving real invalidation (failed login, logout)
- Log-output regression guardrail
- Bucket4j-based rate limiting on `/register` and `/resend-verification`, per-email and per-IP
- `test-plan.md` §6 cookbook updates

**Out of scope:**
- Distributed rate-limiting backend (Redis/Hazelcast) — single instance doesn't need it
- Rate limiting on `/login`
- Risks #2 and #3 — separate rollout phases
- Bucket eviction/TTL cleanup — accepted tradeoff at current scale

## Architecture / Approach

Test-only fixes first (risk #1, risk #5 — zero production code), then the one phase with real production code (risk #4's `RegistrationRateLimiter`, a package-private component in the same web-adapter package as `RegistrationController`, gating both endpoints before any validation or service work). A final phase updates the cookbook and closes the rollout phase.

## Phases at a Glance

| Phase | What it delivers | Key risk |
|---|---|---|
| 1. Session invalidation proof | Two new tests genuinely proving session invalidation | None — test-only, well-understood mechanism |
| 2. Log-leak regression guardrail | One new test + a `FakeEmailSender` toggle | Establishing a new (but standard) log-capture testing technique |
| 3. Rate limiting | Bucket4j-based limiter, wired into both endpoints, unit + integration tests | Getting the real client IP right behind Render's proxy — verified post-deploy |
| 4. Close out | `test-plan.md` §6 updated, rollout Phase 1 marked complete | None — docs only |

**Prerequisites:** None beyond what's already merged (`user-registration-and-login`, fully shipped).
**Estimated effort:** ~1 session across 4 phases; Phase 3 is the largest (new dependency, new component, new tests at two layers).

## Open Risks & Assumptions

- Render's exact proxy header behavior (whether it reliably sets `X-Forwarded-For`, and strips any client-supplied value) wasn't verified against Render's own docs — grounded via general Spring Boot/reverse-proxy documentation instead, since Context7 was unavailable this session. Standard PaaS behavior strongly suggests this works correctly; Phase 3's manual post-deploy check is the real-world confirmation.
- Bucket4j's exact current dependency coordinates and API were grounded via a direct GitHub README fetch, not Context7 (disconnected this session) — worth a quick sanity check against `build.gradle` once the dependency actually resolves in Phase 3.

## Success Criteria (Summary)

- `./gradlew test` passes with every new test, across all three risks
- A 6th rapid request (same email, or same IP) to either auth endpoint returns 429 with a styled page
- The session-boundary tests would fail if `SecurityConfig`'s session invalidation were removed — proving they test something real
