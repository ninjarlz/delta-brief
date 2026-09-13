---
date: 2026-09-12T16:07:05+02:00
researcher: Claude Sonnet 5
git_commit: e311cc6
branch: feature/testing-auth-boundary-abuse-resistance
repository: delta-brief
topic: "Auth boundary & abuse-resistance coverage — grounding test-plan.md rollout Phase 1 (risks #1, #4, #5)"
tags: [research, codebase, auth, security, testing, rate-limiting, session-management, logging]
status: complete
last_updated: 2026-09-12
last_updated_by: Claude Sonnet 5
---

# Research: Auth boundary & abuse-resistance coverage (test-plan.md rollout Phase 1)

**Date**: 2026-09-12T16:07:05+02:00
**Researcher**: Claude Sonnet 5
**Git Commit**: e311cc6
**Branch**: feature/testing-auth-boundary-abuse-resistance
**Repository**: delta-brief

## Research Question

Ground rollout Phase 1 of `context/foundation/test-plan.md` ("Auth boundary & abuse-resistance coverage") against current code for risks #1, #4, and #5: verify or correct the plan's Risk Response Guidance, locate existing tests, identify the cheapest useful test layer per risk, and flag anything speculative or misleading in the plan's evidence.

## Summary

All three risks are real and confirmed against current code, but the picture is more precise — and in one case more serious — than `test-plan.md` assumed:

- **Risk #1 (session boundary)**: The plan's own "must challenge" note ("redirect to /login proves the response, not that the server-side session was invalidated") turns out to be *exactly right, and not yet resolved* — despite a prior review (`impl-review-phase-3.md` F2) believing it had fixed this. **`MockMvc` does not carry session state across sequential `perform()` calls by default**, so every "unauthenticated afterward" assertion in the current test suite is testing a *trivially fresh, already-unauthenticated request* — not a genuine proof that a real session was invalidated. No test in the codebase actually threads a captured session across requests to prove invalidation, for either the wrong-password case or the logout case. This is the single highest-value finding of this research pass.
- **Risk #4 (no rate limiting)**: Confirmed with certainty — there is no rate-limiting, throttling, or abuse-prevention mechanism anywhere in this codebase, at the application layer or the infra layer (`render.yaml` has none either). `/register` and `/resend-verification` are both completely open to unlimited repeated calls.
- **Risk #5 (credential/PII leakage into logs)**: The specific mechanism the plan worried about ("some JavaMail exceptions can echo the SMTP session transcript, including AUTH") does **not** apply to the current code — `EmailDeliveryException`'s message is a hardcoded string, never derived from the underlying `MailException`, and the one log call in the entire codebase never logs a `Throwable` object (so no stack trace prints). Risk #5 is **not actionable as originally framed** against current code. A narrower, still-real latent risk was found instead: `log4j2.xml`'s `PatternLayout` auto-prints stack traces by default, so a *future* log call that does pass a raw exception object would leak whatever that exception's cause chain contains — worth a guardrail test, not the exploit the plan described.

## Detailed Findings

### Risk #1 — Session boundary after logout / failed login

**Current code** (`src/main/java/pl/tul/deltabrief/config/SecurityConfig.java:46-51`) configures logout correctly at the framework level:
```java
.logout(logout -> logout
    .logoutUrl("/logout")
    .logoutSuccessUrl("/login?logout")
    .invalidateHttpSession(true)
    .deleteCookies("JSESSIONID")
    .permitAll());
```
`invalidateHttpSession(true)` + `deleteCookies("JSESSIONID")` is the correct, standard Spring Security configuration for session invalidation on logout. There is no evidence in the code that this is misconfigured.

**The gap is entirely in test coverage, not app code.** `src/test/java/pl/tul/deltabrief/auth/AuthFlowIntegrationTests.java` builds `MockMvc` via `MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build()` (lines 53-55) and issues multiple sequential `mockMvc.perform(...)` calls per test, with **no `.session(...)` or `.cookie(...)` passed between calls anywhere in the file**. `MockMvc` does not perform real HTTP and has no cookie-jar concept — each `perform()` call is a fresh `MockHttpServletRequest` with no session attached unless the caller explicitly threads one via `.session(capturedSession)`. This is standard, well-documented MockMvc behavior, not a project-specific bug.

Consequence: every "subsequent request is unauthenticated" assertion currently in the suite is true *trivially* — a brand-new request has no session regardless of what happened earlier in the test. Specifically:
- `registerVerifyLoginLogout()` (lines 59-96): after a successful login (77-81) and a wrong-password attempt (83-87), `GET /some-protected-path` (89-91) redirects to `/login` — but this would be true even if the app never invalidated anything, because the request was never carrying a session to begin with.
- The test ends at `POST /logout` → `/login?logout` (93-95) with **no follow-up request at all** — so post-logout session-replay is not tested even trivially.
- `unverifiedAccountCannotLogIn()` (98-119) and `wrongPasswordOnUnverifiedAccountStaysGeneric()` (121-138) have the same structural gap.

`impl-review-phase-3.md` F2 (lines 33-41) added the `GET /some-protected-path` follow-up specifically believing it "genuinely exercised the MockMvc session-continuity behavior" and "confirms the wrong-password attempt correctly left no authenticated session behind." Given the mechanism above, that conclusion does not hold — the assertion passes for a reason unrelated to what F2 believed it was proving.

**What would actually prove risk #1 is protected** (the "must challenge" from `test-plan.md` §2, restated precisely): a test must (1) perform a successful login, (2) capture the real `MockHttpSession` from that `MvcResult`, (3) explicitly pass that *same* session object into a subsequent request (e.g., a wrong-password login attempt, or a `POST /logout`), and (4) issue one more request *also passing that same captured session object* to prove it is now genuinely rejected. Nothing in the current suite does this.

### Risk #4 — No rate limiting on registration/resend

**Confirmed with certainty: zero rate-limiting or abuse-prevention exists anywhere in this codebase.**

- `build.gradle` has no bucket4j, resilience4j, Guava, or any throttling dependency — only standard Spring Boot starters, Spring AI, MapStruct, Postgres driver, Lombok, Testcontainers.
- `SecurityConfig.java` is the only `SecurityFilterChain`/`HttpSecurity` configuration in the app; `/register` and `/resend-verification` are listed under plain `permitAll()` (lines 24-26) with no additional filter, interceptor, or gate.
- `RegistrationController.java` (35-73) and `RegistrationService.java` (46-83) have no counters, no per-IP/per-email tracking, no cooldown logic. The only time-bound state is the verification token's 24-hour TTL (`RegistrationService.java:26`), which is unrelated to rate limiting.
- `render.yaml` is 8 lines (service type, plan, health check path, `autoDeploy`, one JVM heap env var) — no Cloudflare, WAF, or infra-level rate limit of any kind.
- A recursive grep for `ratelimit|throttl|bucket4j|resilience4j|RateLimiter|abuse|cooldown|lastAttempt|attemptCount|maxAttempts|requestCount` across `src/`, `build.gradle`, `render.yaml`, `docker-compose.yml`, and `.github/workflows/` returned zero matches.

This matches `test-plan.md`'s risk exactly, with no correction needed — it was an assumption from planning ("no throttle is known to exist today") and is now independently confirmed, not merely assumed.

### Risk #5 — Credential/PII leakage in mail-failure logs

**The specific mechanism `test-plan.md` described does not apply to current code — this risk needs reframing, not a test written against the original wording.**

The entire `src/main/java` tree contains exactly **one** logging statement:
```java
// RegistrationService.java:100
log.warn(">>> Failed to send verification email to {}: {}", email, emailDeliveryFailed.getMessage());
```
Tracing the exception chain:
- `ResendSmtpEmailSender.java:33-37` catches `MailException` and wraps it: `throw new EmailDeliveryException("Failed to send email to " + to, smtpFailure);` — the message is a **hardcoded string**, never derived from `smtpFailure.getMessage()` or any SMTP transcript content. The real `MailException` is preserved only as the `cause`, never read anywhere.
- `EmailDeliveryException.getMessage()` therefore always returns `"Failed to send email to " + to` — an email address (low-sensitivity PII, already known to the actor who submitted the form) with no credential content possible.
- The log call passes `emailDeliveryFailed.getMessage()` (a `String`), not the exception object itself — so Log4j2 never prints a stack trace or cause chain for this call, regardless of `log4j2.xml`'s pattern layout.

No other class in the codebase (`SecurityConfig`, `RegistrationController`, `VerificationController`, `JpaUserDetailsService`, `AppUserDetails`, any persistence/mapper class) logs anything at all — confirmed via a recursive grep for `Logger|LogManager|@Slf4j|@Log4j2|\.debug\(|\.info\(|\.warn\(|\.error\(|\.trace\(`, which returned only the `@Log4j2` annotation and the one `log.warn` call above. No `System.out`/`System.err`/`printStackTrace` usage exists anywhere in `src/main`.

**A narrower, still-real latent risk**: `log4j2.xml`'s `PatternLayout` (pattern `%m%n`, no explicit `alwaysWriteExceptions="false"`) auto-prints a full stack trace by default whenever a `Throwable` object is logged directly. Today nothing does that — but the current design (hardcoded exception messages, cause chains never read) is what prevents the leak, not an explicit safeguard against it. If a future log call anywhere passed a raw exception object (e.g. `log.error("failed", someException)`), and that exception's cause chain ever included SMTP AUTH transcript content, it would print in full. This is a guardrail worth a regression test, not evidence of a current, exploitable leak.

## Code References

- `src/main/java/pl/tul/deltabrief/config/SecurityConfig.java:22-53` — the only `SecurityFilterChain` bean; logout config at 46-51; permitAll list at 24-26.
- `src/test/java/pl/tul/deltabrief/auth/AuthFlowIntegrationTests.java:37-56` — test class setup; MockMvc built with no session-sharing configuration.
- `src/test/java/pl/tul/deltabrief/auth/AuthFlowIntegrationTests.java:59-96` — `registerVerifyLoginLogout()`, ends at the logout assertion with no follow-up request.
- `src/main/java/pl/tul/deltabrief/auth/application/RegistrationService.java:73-83` — `resendVerification`, no throttle of any kind, `@Async` dispatch.
- `src/main/java/pl/tul/deltabrief/auth/adapter/in/web/RegistrationController.java:35-73` — `/register` and `/resend-verification` endpoints, no rate limiting.
- `src/main/java/pl/tul/deltabrief/auth/application/RegistrationService.java:91-102` — the only log call in the codebase, and the exception-message chain that keeps it safe today.
- `src/main/java/pl/tul/deltabrief/shared/adapter/out/email/ResendSmtpEmailSender.java:33-37` — where `MailException` is wrapped into `EmailDeliveryException` with a hardcoded message.
- `src/main/resources/log4j2.xml:5` — `PatternLayout` with no `alwaysWriteExceptions="false"`, the latent trap for any *future* raw-exception log call.
- `build.gradle` (full file) — confirms no rate-limiting dependency exists.
- `render.yaml` (full file, 8 lines) — confirms no infra-level rate limiting.

## Architecture Insights

- **MockMvc session semantics are an unwritten project convention gap, not a one-off bug.** Every test in `AuthFlowIntegrationTests` that currently "proves" post-action unauthenticated state is structurally incapable of proving it, because none of them carry a session across calls. This isn't specific to risk #1's scenario — it would affect any future test written the same way (e.g., a Phase 3 ownership/authorization test for per-user resources would need the same explicit-session-threading pattern to be a genuine proof, not just a syntactically similar one). Worth capturing as a cookbook pattern (`test-plan.md` §6.2) once Phase 1 lands a real example.
- **The port/adapter boundary already protects risk #5 by design**, not by accident: `EmailSender`'s contract (`EmailDeliveryException`, hardcoded message) was hardened during the `user-registration-and-login` full-plan review (F6, see `context/changes/user-registration-and-login/reviews/impl-review.md`) for an unrelated reason (avoiding adapter-specific exception leakage across the port boundary) — and that same fix happens to be exactly what keeps risk #5 non-exploitable today. This is worth noting in the response guidance as *why* the risk is currently closed, so a future change to `ResendSmtpEmailSender` (e.g., logging the raw `MailException` for debugging) doesn't silently reopen it.
- **Abuse resistance (risk #4) and session-boundary testing (risk #1) are independent of each other** — they don't share a chokepoint. A rate limiter would sit at the `SecurityConfig`/filter level or inside `RegistrationController`; session-boundary tests are purely test-code additions with no production code change required (the app-side logout config is already correct).

## Historical Context (from prior changes)

- `context/changes/user-registration-and-login/reviews/impl-review-phase-3.md` F2 (lines 23-41) — the prior fix that added the wrong-password follow-up assertion, believing it proved session non-leakage. This research found that belief to be incorrect given MockMvc's actual session semantics — not a regression of F2's fix (the assertion itself is harmless and worth keeping), but its stated justification needs correcting when Phase 1's plan is written.
- `context/changes/user-registration-and-login/reviews/impl-review.md` F1 — the login-gating-on-verification fix (unrelated to risk #1's specific session-replay concern, but in the same file/area — `SecurityConfig.java`'s `formLogin` success handler). No interaction with risk #1's finding; the login success handler and the logout config are independent code paths.
- `context/changes/user-registration-and-login/reviews/impl-review.md` F6 — the `EmailDeliveryException` port-boundary fix, which (per this research) is what makes risk #5 currently non-exploitable. Documented above under Architecture Insights.
- `context/foundation/test-plan.md` §2 Challenger findings — already correctly excluded Testcontainers/CI fragility and concurrent-update races as risks for this rollout; nothing in this research contradicts those exclusions.

## Related Research

- None yet — this is the first research document for this change.

## Open Questions

- None blocking `/10x-plan` for this phase. One forward-looking note: risk #1's real fix (explicit session-threading in tests) is a **test-only change** — no production code needs to change for this risk. Worth confirming during planning that Phase 1's scope stays test-only for risk #1, versus risk #4 which likely *does* need a small production code change (a rate limiter or at minimum a chokepoint for one) if the plan decides to close the gap rather than just make it observable, per `test-plan.md`'s own risk-#4 response guidance ("a real fix is a separate explicit decision").
