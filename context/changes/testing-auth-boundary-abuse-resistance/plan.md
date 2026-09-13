# Auth Boundary & Abuse-Resistance Coverage — Implementation Plan

## Overview

Rollout Phase 1 of `context/foundation/test-plan.md`. Closes risks #1, #4, and #5 from the risk map: prove session invalidation genuinely works (not just that a fresh unauthenticated request looks rejected), add a regression guardrail against a future credential/PII leak into logs, and close the confirmed-open rate-limiting gap on `/register` and `/resend-verification` with a real fix plus tests.

## Current State Analysis

Per `context/changes/testing-auth-boundary-abuse-resistance/research.md`:

- **Risk #1**: `SecurityConfig.java`'s logout config (`invalidateHttpSession(true)` + `deleteCookies("JSESSIONID")`) is already correct. The gap is entirely in test coverage: `AuthFlowIntegrationTests` issues sequential `mockMvc.perform(...)` calls with no `.session(...)` threading, so `MockMvc` never actually carries a session between calls — every "subsequent request is unauthenticated" assertion currently in the suite is trivially true regardless of whether real invalidation occurred.
- **Risk #4**: Confirmed exhaustively — no rate-limiting, throttling, or abuse-prevention exists anywhere in this codebase, at the application layer or the infra layer.
- **Risk #5**: The original threat model (SMTP transcript leak via a nested exception cause) does not apply to current code — `EmailDeliveryException`'s message is hardcoded, never derived from the wrapped `MailException`, and the single log call in the codebase (`RegistrationService.java:100`) never passes a raw `Throwable`. This risk is closed today incidentally by an unrelated port-boundary fix (`impl-review.md` F6). The actionable item is a regression guardrail, not a test against a current leak.

## Desired End State

- `AuthFlowIntegrationTests` has tests that genuinely prove a session is invalidated — both after a failed login attempt and after logout — by explicitly threading a captured `MockHttpSession` across requests, not relying on MockMvc's default (session-less) behavior.
- `RegistrationServiceTests` has a test proving no raw exception/`Throwable` object is ever passed to the mail-failure log call, guarding against a future regression even though no current leak exists.
- `/register` and `/resend-verification` are rate-limited per-email and per-IP via Bucket4j, returning HTTP 429 with a styled page when exceeded, verified by a unit test (the limiter's own logic) and an integration test (proving the wiring), and correctly functional behind Render's reverse proxy.
- `context/foundation/test-plan.md` §6 documents all three patterns shipped by this phase, and §3's Phase 1 row is marked `complete`.

Verification: `./gradlew test` passes with all new tests; manually hitting `/resend-verification` 6 times rapidly for the same email returns 429 on the 6th; the new session-boundary tests would fail if `SecurityConfig`'s `invalidateHttpSession(true)` were removed (a quick local sanity check).

### Key Discoveries:

- `MockMvc` does not carry session/cookie state across sequential `perform()` calls by default (`research.md` Detailed Findings — Risk #1) — this is the load-bearing fact the whole first phase depends on.
- Zero rate-limiting dependency or logic exists anywhere in this codebase (`research.md` Detailed Findings — Risk #4).
- `EmailDeliveryException`'s hardcoded message (not derived from the wrapped `MailException`) is what keeps risk #5 closed today, established for an unrelated reason during `user-registration-and-login`'s full-plan review (`context/changes/user-registration-and-login/reviews/impl-review.md` F6).
- Render proxies all traffic; `HttpServletRequest.getRemoteAddr()` returns the load balancer's IP, not the real client's, unless `server.forward-headers-strategy=native` is set (confirmed via Spring Boot's documented `RemoteIpValve` integration, current as of 2026-09-12 — see Critical Implementation Details).
- Bucket4j's current stable API (8.19.0, Java 17+ variant `bucket4j_jdk17-core`) uses `Bucket.builder().addLimit(limit -> limit.capacity(N).refillGreedy(N, Duration))` — verified against the library's own GitHub README, 2026-09-12 (Context7 was unavailable this session; grounded via direct web fetch instead).

## What We're NOT Doing

- No distributed rate-limiting backend (Redis/Hazelcast) — this app runs a single Render instance; in-memory buckets are sufficient and match the existing project convention of avoiding infrastructure not yet needed.
- No rate limiting on `/login` — out of scope for this phase's risk set (risk #1 covers session boundary correctness, not login-attempt throttling, which isn't a mapped risk in `test-plan.md` today).
- No change to risk #2 (cross-user authorization) or risk #3 (deployed-env regression) — separate rollout phases (3 and 2 respectively) in `test-plan.md`, gated on different preconditions.
- No IP-reputation, CAPTCHA, or blocklist system — a simple per-key token bucket, proportionate to this project's current scale and threat model.
- No eviction/TTL cleanup for the rate limiter's in-memory bucket maps — see Performance Considerations.
- No UI polish on the 429 page beyond matching the existing Pico.css auth-card layout.

## Implementation Approach

Bottom-up by cost × signal: the two purely test-only fixes (risk #1, risk #5) land first since they require zero production code changes and are cheapest to verify in isolation. Risk #4's fix — the only phase touching production code — lands third, once the test-writing conventions (session-threading, log-capture) are already established and available as reference patterns if needed. A final phase closes the loop by updating `test-plan.md`'s cookbook and marking the rollout phase complete.

## Critical Implementation Details

**MockMvc session semantics.** Every new session-boundary test must explicitly capture the `MockHttpSession` from a successful login's `MvcResult` (`(MockHttpSession) result.getRequest().getSession(false)`) and pass that *same* object into follow-up requests via `.session(capturedSession)`. Sequential `perform()` calls on the same `MockMvc` instance do NOT share session state by default — this is the exact mechanism Phase 1 exists to test correctly; using the technique incorrectly (or omitting it) produces a test that passes without proving anything, exactly as happened before.

**Render sits behind a reverse proxy.** `HttpServletRequest.getRemoteAddr()` returns Render's load-balancer IP for every request unless `server.forward-headers-strategy=native` is set in `application.properties`, which makes Tomcat's embedded `RemoteIpValve` rewrite `getRemoteAddr()` from the `X-Forwarded-For` header. Without this, the per-IP rate limiter would treat every production user as the same client and rate-limit the whole app after a handful of unrelated requests. This can't be fully verified locally (no proxy sits in front of local dev) — Phase 3's manual verification includes a post-deploy check.

**Rate-limiter test isolation.** `RegistrationRateLimiter`'s buckets live in a singleton bean's in-memory maps, shared across the *entire* test suite's cached Spring context — the same sharing invariant this project already handles carefully for the fixed Testcontainers port. Tests must use unique emails (already this project's convention, via `UUID.randomUUID()`) **and** unique simulated IPs (via a `RequestPostProcessor` setting `request.setRemoteAddr(...)`) per test method — otherwise a rate-limit test exhausting MockMvc's default shared IP bucket could break unrelated tests that happen to run afterward in the same context.

## Phase 1: Prove session invalidation genuinely works

### Overview

Add tests that genuinely prove a session is invalidated after a failed login attempt and after logout — not just that a fresh, already-unauthenticated request is rejected, which every existing test in the suite currently (and only accidentally) demonstrates.

### Changes Required:

#### 1. `src/test/java/pl/tul/deltabrief/auth/AuthFlowIntegrationTests.java`

**File**: `src/test/java/pl/tul/deltabrief/auth/AuthFlowIntegrationTests.java`

**Intent**: Prove that a session captured before a failed login attempt, and a session captured before logout, cannot be replayed to regain access afterward — closing the exact gap `test-plan.md`'s risk #1 response guidance calls out and research confirmed no existing test covers.

**Contract**: Add two new `@Test` methods:
- `wrongPasswordDoesNotLeakPriorAuthenticatedSession()`: perform a successful login and capture the `MockHttpSession` from that `MvcResult`; perform a wrong-password login attempt reusing that *same* session via `.session(capturedSession)`; issue a follow-up request also passing that same captured session, asserting it redirects to `/login` — proving the session itself (not merely a fresh request) is unauthenticated after the failed attempt.
- `sessionCapturedBeforeLogoutCannotBeReplayedAfterLogout()`: perform a successful login and capture the session; perform `POST /logout` reusing that captured session; issue a follow-up request passing that *same* captured session object again, asserting it redirects to `/login` — proving the specific session Spring Security invalidated is truly dead, not just that a fresh request is rejected.

Both methods share the session-capture technique:
```java
MockHttpSession session = (MockHttpSession) mockMvc.perform(post("/login").with(csrf())
        .param("username", email)
        .param("password", password))
    .andExpect(status().is3xxRedirection())
    .andReturn().getRequest().getSession(false);
```
then reuse `.session(session)` on every subsequent request in that test.

### Success Criteria:

#### Automated Verification:

- `./gradlew test --no-daemon` passes, including both new session-boundary tests
- `./gradlew build --no-daemon` passes end-to-end

#### Manual Verification:

- Temporarily comment out `.invalidateHttpSession(true)` in `SecurityConfig.java`, confirm the new tests actually fail, then restore it — a quick empirical sanity check that the tests genuinely exercise invalidation rather than passing vacuously (the exact failure mode this phase exists to close)

**Implementation Note**: After this phase's automated verification passes, pause for manual confirmation before proceeding to Phase 2.

---

## Phase 2: Regression guardrail against credential/PII leakage in logs

### Overview

Add a test proving the mail-failure log path never passes a raw exception object to Log4j2 — a guardrail against a *future* regression, since research confirmed no leak exists in current code (the original risk wording, an SMTP-transcript leak via nested exception cause, does not apply).

### Changes Required:

#### 1. `src/test/java/pl/tul/deltabrief/shared/adapter/out/email/FakeEmailSender.java`

**File**: `src/test/java/pl/tul/deltabrief/shared/adapter/out/email/FakeEmailSender.java`

**Intent**: Let tests simulate an email-delivery failure so the mail-failure log path can be exercised end-to-end, without touching the real Resend/SMTP adapter.

**Contract**: Add a method (e.g. `failNextSendWith(EmailDeliveryException exception)`) that makes the *next* `send(...)` call throw the given exception instead of recording it, then resets to normal recording behavior — a one-shot toggle so it doesn't affect other tests sharing this singleton bean.

#### 2. `src/test/java/pl/tul/deltabrief/auth/application/RegistrationServiceTests.java`

**File**: `src/test/java/pl/tul/deltabrief/auth/application/RegistrationServiceTests.java`

**Intent**: Prove the mail-failure log call never receives a raw `Throwable`/exception object — the structural guarantee that keeps risk #5 closed today, made explicit and regression-proof rather than implicit.

**Contract**: New test method that (1) configures `FakeEmailSender` to throw on the next send via the new toggle, (2) attaches a Log4j2 `ListAppender` to capture log events during the call (a new technique for this project — no existing precedent), (3) triggers `resendVerification(...)` so the failure path executes, (4) asserts the captured log event's `getThrown()` is `null`. Attach/detach the appender in the test method itself (no shared test infrastructure needed for a single test):
```java
ListAppender<LogEvent> appender = ...; // attach to the RegistrationService logger context
// ...trigger the failure path...
assertThat(appender.getEvents()).allSatisfy(event -> assertThat(event.getThrown()).isNull());
```

### Success Criteria:

#### Automated Verification:

- `./gradlew test --no-daemon` passes, including the new log-guardrail test
- `./gradlew build --no-daemon` passes end-to-end

#### Manual Verification:

- None needed — this is a pure regression guardrail, fully provable by the automated test alone.

**Implementation Note**: After this phase's automated verification passes, pause for manual confirmation before proceeding to Phase 3.

---

## Phase 3: Rate limit `/register` and `/resend-verification`

### Overview

Close the confirmed-open rate-limiting gap with a real fix: per-email and per-IP token buckets (Bucket4j, in-memory), wired into both endpoints, correctly functional behind Render's reverse proxy, with unit and integration test coverage.

### Changes Required:

#### 1. `build.gradle`

**File**: `build.gradle`

**Intent**: Add Bucket4j for in-memory, single-instance rate limiting.

**Contract**: Add to `dependencies`:
```gradle
implementation "com.bucket4j:bucket4j_jdk17-core:8.19.0"
```

#### 2. `src/main/resources/application.properties`

**File**: `src/main/resources/application.properties`

**Intent**: Make `HttpServletRequest.getRemoteAddr()` reflect the real client IP behind Render's proxy rather than the load balancer's own IP — required for the per-IP bucket to mean anything in production.

**Contract**: Add `server.forward-headers-strategy=native`.

#### 3. `src/main/java/pl/tul/deltabrief/auth/adapter/in/web/RegistrationRateLimiter.java` (new)

**File**: `src/main/java/pl/tul/deltabrief/auth/adapter/in/web/RegistrationRateLimiter.java`

**Intent**: A small, self-contained component maintaining independent per-email and per-IP token buckets, so one call gates both dimensions for a given request.

**Contract**: Package-private `@Component` (matches this package's existing visibility convention). Method `boolean tryConsume(String email, String clientIp)` — consumes one token from both the email-keyed and IP-keyed bucket (short-circuit evaluation: if either is exhausted, return `false` without checking further); each bucket created lazily per key via `Map.computeIfAbsent` on two separate `ConcurrentHashMap<String, Bucket>` fields. Limits: 5 tokens / 15 minutes per email (the tighter bound — directly targets the abuse scenario of spamming one victim), 20 tokens / 15 minutes per IP (looser, to avoid over-blocking shared/corporate networks while still capping a single-source flood). Bucket construction per Bucket4j 8.x's current fluent API:
```java
Bucket.builder()
    .addLimit(limit -> limit.capacity(tokens).refillGreedy(tokens, Duration.ofMinutes(15)))
    .build();
```

#### 4. `src/main/java/pl/tul/deltabrief/auth/adapter/in/web/RegistrationController.java`

**File**: `src/main/java/pl/tul/deltabrief/auth/adapter/in/web/RegistrationController.java`

**Intent**: Gate both `/register` and `/resend-verification` behind the rate limiter as the very first check, before validation or any service work, so even a flood of malformed requests is throttled.

**Contract**: Inject `RegistrationRateLimiter` via the constructor. At the top of both `register(...)` and `resendVerification(...)`, inject `HttpServletRequest` and `HttpServletResponse` as method parameters; call `rateLimiter.tryConsume(email, request.getRemoteAddr())` first — for `register`, the email comes from `form.getEmail()`, read before Bean Validation would otherwise run (so both valid and malformed submissions are throttled equally). If the check fails, set `response.setStatus(HttpServletResponse.SC_TOO_MANY_REQUESTS)` and return `"too-many-requests"` as the view name.

#### 5. `src/main/resources/templates/too-many-requests.html` (new)

**File**: `src/main/resources/templates/too-many-requests.html`

**Intent**: A minimal, styled page shown when the rate limit is hit, consistent with the existing auth-card layout.

**Contract**: Same structure as `check-email.html` — a short message ("Too many attempts. Please wait a few minutes and try again.") plus a link back to `/login`. References the existing `pico.min.css`/`app.css` stylesheets.

#### 6. `src/test/java/pl/tul/deltabrief/auth/adapter/in/web/RegistrationRateLimiterTests.java` (new)

**File**: `src/test/java/pl/tul/deltabrief/auth/adapter/in/web/RegistrationRateLimiterTests.java`

**Intent**: Cheapest-layer proof of the bucket logic itself — no Spring context needed, since `RegistrationRateLimiter` has no framework dependencies.

**Contract**: Plain JUnit 5 test, `new RegistrationRateLimiter()` instantiated directly (no mocks — nothing to mock). Test cases: (a) N consecutive `tryConsume(sameEmail, differentIpEachTime)` calls succeed, the (N+1)th fails — proves the email bound is enforced independent of IP; (b) a different email sharing an already-IP-exhausted address still gets rejected once the IP bound is hit, proving the IP bound is enforced independent of email; (c) two entirely distinct email+IP pairs don't interfere with each other's buckets. Refill-timing behavior (does the bucket recover after the window) is explicitly **not** tested — would require a fake clock with no existing precedent in this project, and capacity-exhaustion is the behavior this risk actually cares about.

#### 7. `src/test/java/pl/tul/deltabrief/auth/AuthFlowIntegrationTests.java`

**File**: `src/test/java/pl/tul/deltabrief/auth/AuthFlowIntegrationTests.java`

**Intent**: Prove the rate limiter is actually wired into the real HTTP path and returns 429 — the integration-level complement to the unit test above, which does not re-test the bucket algorithm's exhaustive edge cases.

**Contract**: New test method(s) using a `RequestPostProcessor` to set a unique simulated remote address per test (per the Critical Implementation Details note — avoids cross-test IP-bucket contamination):
```java
static RequestPostProcessor withRemoteAddr(String ip) {
    return request -> { request.setRemoteAddr(ip); return request; };
}
```
One test: repeat `POST /resend-verification` with the same email past the configured email limit (using a unique IP for this test method), assert the final request's status is `429`.

### Success Criteria:

#### Automated Verification:

- `./gradlew test --no-daemon` passes, including the new unit test (`RegistrationRateLimiterTests`) and integration test
- `./gradlew build --no-daemon` passes end-to-end
- GitHub Actions `build-and-test` passes with no `ci-cd.yml` changes

#### Manual Verification:

- Locally: `curl` `/resend-verification` 6 times rapidly for the same email, confirm the 6th returns `429` with the styled too-many-requests page
- After deploying: confirm normal registration/resend still works when tested from a real browser against the deployed app (proves `server.forward-headers-strategy=native` isn't causing every visitor to share one IP-bucket in production — the one thing that can't be verified locally, since local dev has no proxy in front)

**Implementation Note**: After this phase's automated verification passes (including the real CI run), pause for manual confirmation before proceeding to Phase 4.

---

## Phase 4: Close out the rollout phase

### Overview

Update `test-plan.md`'s cookbook with the three patterns this phase shipped, and mark the rollout phase complete.

### Changes Required:

#### 1. `context/foundation/test-plan.md`

**File**: `context/foundation/test-plan.md`

**Intent**: Fill in §6 with concrete, shipped reference patterns so a future contributor (or agent) writing a similar test doesn't have to rediscover the session-threading, rate-limit-testing, or log-capture techniques from scratch.

**Contract**:
- §6.2 (integration test): add the session-threading technique (capture `MockHttpSession` from `MvcResult`, reuse via `.session(...)`) as a named sub-pattern, pointing to Phase 1's two new test methods as the reference example.
- A new §6 sub-section for rate-limiter testing, pointing to `RegistrationRateLimiterTests` (unit) and Phase 3's `AuthFlowIntegrationTests` wiring test (integration) as reference — replacing whatever placeholder currently covers this ground.
- A new §6 sub-section for log-output regression testing, pointing to Phase 2's new `RegistrationServiceTests` method and the `ListAppender` attach/detach pattern.
- §3 Phased Rollout: Phase 1's row `Status` → `complete` (only after all manual verification across Phases 1-3 above is confirmed).

### Success Criteria:

#### Automated Verification:

- None — this is a documentation-only phase.

#### Manual Verification:

- Re-read the updated `test-plan.md` §6 entries and confirm they accurately describe what shipped (not a runtime check).

**Implementation Note**: This is the final phase — no further manual pause needed after its verification passes.

---

## Testing Strategy

### Unit Tests:

- `RegistrationRateLimiterTests`: bucket capacity/independence logic, no Spring context.

### Integration Tests:

- `AuthFlowIntegrationTests`: session-threading proofs (Phase 1), rate-limiter wiring proof (Phase 3) — both against the existing shared Testcontainers context.
- `RegistrationServiceTests`: log-output regression guardrail (Phase 2) — against the same shared context.

### Manual Testing Steps:

1. Phase 1: temporarily disable `invalidateHttpSession(true)`, confirm the new tests fail, then restore it.
2. Phase 3: `curl` `/resend-verification` 6 times locally for the same email, confirm the 6th returns 429.
3. Phase 3 (post-deploy): confirm normal registration/resend works from a real browser against the deployed Render app.

## Performance Considerations

`RegistrationRateLimiter`'s two `ConcurrentHashMap` fields grow by one entry per unique email/IP ever seen, with no eviction — acceptable at this project's current scale (low traffic, single instance) but would need a bounded cache (e.g. Caffeine with a TTL) if traffic grows meaningfully. Not addressed in this phase; noted as a known, accepted tradeoff (mirrors how other early-stage tradeoffs in this project — e.g. the email task executor's small fixed pool — were handled: proportionate to current scale, revisit later).

## Migration Notes

Not applicable — no schema changes in this phase.

## References

- Research: `context/changes/testing-auth-boundary-abuse-resistance/research.md`
- Foundation: `context/foundation/test-plan.md` §2 (risk map, backported response guidance), §3 (rollout Phase 1)
- Prior pattern this follows: `context/changes/user-registration-and-login/plan.md` (phase structure: cheapest/test-only first, production code last)
- Historical context: `context/changes/user-registration-and-login/reviews/impl-review-phase-3.md` F2 (the prior, incomplete attempt at this exact session-boundary proof); `context/changes/user-registration-and-login/reviews/impl-review.md` F6 (the `EmailDeliveryException` fix that incidentally closes risk #5)
- Bucket4j: https://github.com/bucket4j/bucket4j (README, fetched 2026-09-12 — Context7 unavailable this session)
- Spring Boot forwarded-headers documentation context, fetched via web search 2026-09-12 (Context7 unavailable this session)

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Prove session invalidation genuinely works

#### Automated

- [x] 1.1 `./gradlew test --no-daemon` passes, including both new session-boundary tests — c8b5ee8
- [x] 1.2 `./gradlew build --no-daemon` passes end-to-end — c8b5ee8

#### Manual

- [x] 1.3 Temporarily disabling `invalidateHttpSession(true)` makes the new tests fail; restoring it makes them pass again — c8b5ee8

### Phase 2: Regression guardrail against credential/PII leakage in logs

#### Automated

- [x] 2.1 `./gradlew test --no-daemon` passes, including the new log-guardrail test — c57186d
- [x] 2.2 `./gradlew build --no-daemon` passes end-to-end — c57186d

### Phase 3: Rate limit `/register` and `/resend-verification`

#### Automated

- [x] 3.1 `./gradlew test --no-daemon` passes, including `RegistrationRateLimiterTests` and the new integration test — d691cca
- [x] 3.2 `./gradlew build --no-daemon` passes end-to-end — d691cca
- [ ] 3.3 GitHub Actions `build-and-test` passes with no `ci-cd.yml` changes

#### Manual

- [x] 3.4 Local `curl` loop: 6th rapid `/resend-verification` request for the same email returns 429 with the styled page — d691cca
- [ ] 3.5 Post-deploy: normal registration/resend works from a real browser against the deployed app

### Phase 4: Close out the rollout phase

#### Manual

- [ ] 4.1 `test-plan.md` §6 entries accurately describe what shipped; §3 Phase 1 row marked `complete`
