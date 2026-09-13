# Test Plan

> Phased test rollout for this project. Strategy is frozen at the top
> (§1–§5); cookbook patterns at the bottom (§6) fill in as phases ship.
> Read before writing any new test.
>
> Refresh: re-run `/10x-test-plan --refresh` when stale (see §8).
>
> Last updated: 2026-09-13 (rollout Phase 1 complete)

## 1. Strategy

Tests follow three non-negotiable principles for this project:

1. **Cost × signal.** The cheapest test that gives a real signal for the
   risk wins. Do not promote to e2e because e2e "feels safer." Do not put a
   vision model on top of a deterministic visual diff that already catches
   the regression.
2. **User concerns are first-class evidence.** Risks anchored in "the team
   is worried about X, and the failure would surface somewhere in area Y"
   carry the same weight as PRD lines or hot-spot data.
3. **Risks are scenarios, not code locations.** This plan documents *what
   could fail* and *why we believe it's likely* — drawn from documents,
   interview, and codebase *signal* (churn, structure, test base). It does
   NOT claim to know which line owns the failure. That knowledge is
   produced by `/10x-research` during each rollout phase. If the plan and
   research disagree about where the failure lives, research is the
   ground truth.

Hot-spot scope used for likelihood weighting: `src/main/java`,
`src/main/resources`, `src/test/java` (excl. `build/`, `.gradle/`).

## 2. Risk Map

The top failure scenarios this project must protect against, ordered by
risk = impact × likelihood. Risks are failure scenarios in user / business
terms, not test names. The Source column cites the *evidence that surfaced
this risk* — never a specific file as "where the failure lives" (that is
research's job, see §1 principle #3).

| # | Risk (failure scenario) | Impact | Likelihood | Source (evidence — not anchor) |
|---|---|---|---|---|
| 1 | A logged-out or failed-login session still grants access to an authenticated-only route | High | Medium | interview Q1 (auth boundary), Q3 (security-config low confidence); hot-spot dir `.../config` (3 commits/30d) |
| 2 | User A reads or mutates a resource (topic, briefing) owned by User B once per-user data exists | High | Medium | interview Q1 (cross-user leakage); `prd.md` Access Control ("no cross-user leakage"); `roadmap.md` S-02 (next slice, first per-user resource) |
| 3 | A deployed-environment config regression (health indicator, mail config) takes `/actuator/health` down or silently misconfigures production | High | Medium | interview Q2 (env/config drift), Q4 (deployed-env untested); `archive/2026-09-10-wire-database-connectivity/plan.md` Key Discoveries; a mail-health-503 regression already occurred once this project |
| 4 | Registration endpoint hit at volume (retry-loop or abuse) generates unbounded verification emails and DB rows with no throttle | Medium | Medium | abuse lens (resource abuse — public registration, no rate-limit evidence); `prd.md` Access Control (flat user model) |
| 5 | Registration/mail-failure error paths leak secrets (Resend API key) or PII into logs or responses | Medium | Low | abuse lens (secret/PII leakage); `AGENTS.md` never-commit-the-API-key discipline |

**Impact × Likelihood rubric** (coarse, by design — ordering, not false precision):

| Rating | Impact | Likelihood |
|---|---|---|
| High | user loses access, data, or money; failure is publicly visible | area changes weekly, or we have already been burned here |
| Medium | feature degrades, a workaround exists, only some users affected | touched occasionally, has been a source of bugs |
| Low | cosmetic, easily reverted, no data effect | stable code, rarely touched |

**Challenger findings:** Two candidate risks were dropped rather than padded into the map. *Testcontainers/CI infra fragility* (fixed local host-network port collision) was considered but has no phase that clears cost×signal on its own — it is instead carried forward as a documented convention in §6.2 rather than a tested risk. *Concurrent-update races on the `User` row* (optimistic locking) was considered on hot-spot evidence alone (no interview backing, no concurrent-write feature exists yet to make a race observable) and parked as negative space — revisit if a future slice adds concurrent edits to the same user row (e.g. profile settings).

### Risk Response Guidance

| Risk | What would prove protection | Must challenge | Context `/10x-research` must ground | Likely cheapest layer | Anti-pattern to avoid |
|---|---|---|---|---|---|
| #1 | A request carrying the *exact session captured before* a failed login, and before logout, is rejected afterward — not just a fresh, already-unauthenticated request | Corrected by `/10x-research` (2026-09-12): the existing suite's "subsequent request is unauthenticated" assertions are true trivially for *any* fresh MockMvc call, session or not — MockMvc does not carry session/cookie state across sequential `perform()` calls by default. No test currently threads a captured session across requests, so none of them yet prove real invalidation, despite a prior review believing one did | The real session-management/logout-handler config (already correct at the app level); confirm no test yet captures and replays a `MockHttpSession` explicitly | Integration test (MockMvc + Spring Security test support) — must explicitly capture the `MockHttpSession` from a successful login's `MvcResult` and pass that same object into the follow-up request(s) via `.session(...)`, not rely on sequential `perform()` calls alone | Assuming sequential `mockMvc.perform()` calls on the same instance share session state — they don't; an assertion that "passes" without explicit session-threading proves nothing about invalidation |
| #2 | User A's session cannot read/mutate User B's resource by guessing or incrementing its ID | "Checks who's logged in" is not the same as "checks the resource belongs to them" — ownership checks are forgotten because the happy path always looks correct | Once S-02 lands: whether repository queries are scoped by owner at the query level, or fetched raw then checked in application code | Integration test as two distinct authenticated users | Testing only the happy path (own resource) — the negative case (other user's ID) must be a first-class assertion |
| #3 | The health endpoint never reports DOWN due to an external dependency (SMTP) the app doesn't need to be healthy for; startup with placeholder mail credentials doesn't hang or throw | A one-time manual curl proves that moment, not that a future Actuator health-contributor change won't reintroduce the same regression | Where the mail-health-disable setting lives; which health contributors are active; whether any existing test covers this | Fast `@SpringBootTest`/slice test asserting UP with placeholder creds — not a live-Render e2e check | Reaching for a live-deploy smoke test as the primary signal when a local Spring context test reproduces the exact failure deterministically |
| #4 | Rapid repeated registration doesn't cause unbounded email sends or DB row creation | Happy-path correctness for one legitimate user says nothing about abuse-volume behavior; no throttle is known to exist today | Confirm no rate-limiting exists today; find the cheapest chokepoint to add one if the phase decides to | Test that first makes the current gap observable; a real fix (limiter) is a separate explicit decision | Skipping this as "pre-launch, no real abuser yet" — the cost (Resend quota, spam) is symmetric for malicious or buggy-retry-loop traffic |
| #5 | No log call anywhere in the codebase ever logs a raw exception/`Throwable` object for a mail-related failure — which the current `log4j2.xml` pattern layout would auto-print in full, cause chain included, if one ever did | Corrected by `/10x-research` (2026-09-12): the original leak mechanism (SMTP transcript via a nested exception cause) does **not** apply to current code — confirmed NOT actionable as originally framed. `EmailDeliveryException`'s message is a hardcoded string, never derived from the wrapped `MailException`; the one log call in the whole codebase (`RegistrationService`) passes only `.getMessage()`, never the exception object, so no stack trace ever prints. This risk is closed today, incidentally, by an unrelated port-boundary fix — the real remaining risk is regression, not an active leak | Confirmed exhaustively: only one log statement exists in `src/main/java`; no `MailException`/credential ever reaches it. Ground going forward: whether any future adapter change (e.g. debug logging added to `ResendSmtpEmailSender`) reintroduces a raw-exception log call | Unit/characterization test asserting no log call in the mail-failure path is ever passed a raw `Throwable` (a guardrail against regression, not a test against a currently-exploitable leak) | Writing a test against the plan's original literal wording (asserting no *currently-nonexistent* SMTP-transcript leak) instead of the structural guarantee that actually prevents it — a passing test against a non-existent bug gives false confidence without guarding the real (future, regression) risk |

## 3. Phased Rollout

Each row is a discrete rollout phase that will open its own change folder
via `/10x-new`. Status moves left-to-right through the values below; the
orchestrator updates Status as artifacts appear on disk.

| # | Phase name | Goal (one line) | Risks covered | Test types | Status | Change folder |
|---|---|---|---|---|---|---|
| 1 | Auth boundary & abuse-resistance coverage | Prove session integrity after logout/failed login, make the registration-throttle gap and log/error credential-leakage risk observable | #1, #4, #5 | unit + integration | complete | `context/changes/testing-auth-boundary-abuse-resistance/` |
| 2 | Deployed-environment regression net | Turn today's one-time manual health/mail verification into an automated, repeatable regression test | #3 | integration (fast Spring context test) | not started | — |
| 3 | Authorization foundation for per-user data | Establish an ownership-check testing pattern the moment the first per-user resource (topics) exists | #2 | integration (two-user) | not started | — |

**Status vocabulary** (fixed — parser literals): `not started` → `change opened` → `researched` → `planned` → `implementing` → `complete`.

Phase 3 is deliberately gated on roadmap S-02 opening — the code it protects (per-user topic ownership) does not exist yet. The response intent is recorded now (§2) so it is not forgotten when S-02 starts.

## 4. Stack

The classic test base for this project. AI-native tools (if any) carry a
`checked:` date so future readers can see which lines need re-verification.

| Layer | Tool | Version | Notes |
|---|---|---|---|
| unit + integration | JUnit 5 + Spring Boot Test (`spring-boot-starter-test`) | aligned to Spring Boot 4.1.1 BOM | Already wired; 5 test classes exist, all in `auth`/root |
| external-edge mocking | `FakeEmailSender` (hand-rolled test double) | n/a | Mocks only the true external edge (outbound SMTP); internal beans/repositories are never mocked |
| real-DB integration | Testcontainers (`postgres:17`) via `TestcontainersDatasourceConfig` | per archived F-01 plan | Dual-mode (CI bridge network / local fixed host-network port) — see §6.2 for the collision constraint |
| e2e (browser) | none yet | n/a | Server-rendered app, no browser automation MCP in this session; not yet justified — no UI complex enough to need it beyond what MockMvc integration tests already cover |
| accessibility | none | n/a | No real UI beyond a placeholder page |
| AI-native (any layer) | none | n/a — checked: 2026-09-12 | Not justified under cost × signal: no complex UI/vision surface exists yet. Revisit once S-03 ships real briefing-reading screens |

**Stack grounding tools (current session):**
- Docs: Context7 — available, not queried (no library-version question arose during discovery); checked: 2026-09-12
- Search: Exa.ai — available, not used (stack already settled in `tech-stack.md`); checked: 2026-09-12
- Runtime/browser: none available this session; checked: 2026-09-12
- Provider/platform: no MCP; Render CLI and `gh` CLI used via shell for deploy/log verification during S-01 — relevant to §5's deployed-env gate; checked: 2026-09-12

## 5. Quality Gates

The full set of gates that must pass before a change reaches production.
"Required after §3 Phase N" means the gate is enforced once that rollout
phase lands; before that, the gate is `planned`.

| Gate | Where | Required? | Catches |
|---|---|---|---|
| compile (`./gradlew build`) | local + CI | required — already wired in `ci-cd.yml` `build-and-test` | compile-time / type errors |
| lint + style | local | not configured | n/a — `AGENTS.md` states no linter/formatter is configured; out of scope for this rollout |
| unit + integration (`./gradlew test`) | local + CI | required — already wired in `ci-cd.yml` `build-and-test` | logic regressions |
| full-stack flow test (MockMvc, e.g. `AuthFlowIntegrationTests`) | local + CI | required after §3 Phase 1 | broken critical auth paths |
| deployed-env regression check (health/mail config) | CI or pre-deploy | required after §3 Phase 2 | environment-specific config failures (the mail-health-503 class of regression) |
| post-edit hook | local (agent loop) | not configured — Module 3 Lesson 3 scope | n/a |
| visual diff / multimodal review | CI on PR | not applicable yet | n/a — no complex UI surface exists |
| pre-prod smoke (manual curl against Render) | between merge and prod | optional, currently manual | environment-specific failures not caught by the automated gates above |

## 6. Cookbook Patterns

How to add new tests in this project. Each sub-section is filled in once
the relevant rollout phase ships; before that, the sub-section reads
"TBD — see §3 Phase N."

### 6.1 Adding a unit test (domain/application layer)

- **Location**: `src/test/java/pl/tul/deltabrief/<module>/domain/` or `/application/`, mirroring the `src/main` package structure.
- **Naming**: `<ClassUnderTest>Test.java`.
- **Reference test**: `src/test/java/pl/tul/deltabrief/auth/domain/UserTests.java` and `src/test/java/pl/tul/deltabrief/auth/application/RegistrationServiceTests.java`.
- **Run locally**: `./gradlew test --tests "pl.tul.deltabrief.<ClassName>"`.

### 6.2 Adding an integration test (controller / security flow)

- **Location**: `src/test/java/pl/tul/deltabrief/<module>/`, full `@SpringBootTest`.
- **Mocking policy**: only mock at the true external edge (e.g. `FakeEmailSender` for outbound SMTP). Use the real Testcontainers-backed Postgres; never mock internal Spring beans or repositories.
- **Reference test**: `src/test/java/pl/tul/deltabrief/auth/AuthFlowIntegrationTests.java`.
- **Run locally**: `./gradlew test --tests "pl.tul.deltabrief.auth.AuthFlowIntegrationTests"`.
- **Critical constraint**: any new `@SpringBootTest` class must match the existing annotation signature (manually-built `MockMvc` via `MockMvcBuilders`, not `@AutoConfigureMockMvc`) to share the cached Spring context. A differently-annotated class forces a second Testcontainers container onto the same fixed local host-network port and collides. This is an established convention, not a tested risk — see §2 Challenger findings.
- **Session-threading technique** (proving real session invalidation, not a vacuous "fresh request is unauthenticated" check): `MockMvc` does not carry session/cookie state across sequential `perform()` calls by default. To genuinely prove a session is dead after a failed login or after logout, explicitly capture the `MockHttpSession` from the triggering request's `MvcResult` — `(MockHttpSession) result.getRequest().getSession(false)` — and reuse that *same* object on the follow-up request via `.session(capturedSession)`. Reference: `AuthFlowIntegrationTests.wrongPasswordNeverEstablishesAnAuthenticatedSession()` and `.sessionCapturedBeforeLogoutCannotBeReplayedAfterLogout()` (§3 Phase 1, `testing-auth-boundary-abuse-resistance`).

### 6.3 Adding a persistence/adapter test

- **Location**: `src/test/java/pl/tul/deltabrief/<module>/adapter/out/persistence/`.
- **Reference test**: `src/test/java/pl/tul/deltabrief/auth/adapter/out/persistence/UserRepositoryAdapterTests.java`.
- **Run locally**: `./gradlew test --tests "pl.tul.deltabrief.auth.adapter.out.persistence.UserRepositoryAdapterTests"`.

### 6.4 Adding an ownership/authorization test for a new authenticated endpoint

- TBD — see §3 Phase 3.

### 6.5 Adding a deployed-environment regression test (health/mail config)

- TBD — see §3 Phase 2.

### 6.6 Adding rate-limiter coverage for an endpoint

Uses `bucket4j-spring-boot-starter` (`com.giffing.bucket4j.spring.boot.starter`, currently 0.14.0) — a hand-rolled `ConcurrentHashMap`-backed limiter class was tried first and replaced after code review; see `context/changes/testing-auth-boundary-abuse-resistance/plan.md` Phase 3's "Addendum: post-review redesign" for the full history and the two undocumented library constraints that shaped the final design.

- **Production wiring**: annotate the controller method with `@RateLimiting(name = "<bucket4j.methods[] config name>", cacheKey = "<SpEL expression>")` — no manual `if`/`tryConsume` code. **Two constraints to know before reaching for this**: (1) one `@RateLimiting` per method supports exactly one `cacheKey` — it cannot express two *independent* dimensions (e.g. per-email AND per-IP with different limits); a composite key (`"#email + ':' + #request.remoteAddr"`) is the only option, and it's weaker (rotating either value resets the other's budget). (2) the cache key is unconditionally scoped by the declaring method's name internally — two different methods can never share one bucket, even with the same `name=` config and cache key, no matter how they're configured. Rejections throw `RateLimitException` (not an HTTP response) — add an `@ControllerAdvice`/`@ExceptionHandler(RateLimitException.class)` once per app (not per endpoint) to map it to a response; reference: `RateLimitExceededAdvice` (returns the styled `too-many-requests` view + literal status `429` — `HttpServletResponse.SC_TOO_MANY_REQUESTS` is not defined in this project's Jakarta Servlet API version).
- **Config**: bandwidth (capacity/window) lives in `application.properties` as `bucket4j.methods[N].name`, `.cache-name`, `.rate-limit.bandwidths[0].{capacity,time,unit}` — not code constants. Storage: Caffeine via the JCache SPI (`bucket4j.cache-to-use=jcache`, `spring.cache.jcache.provider=com.github.benmanes.caffeine.jcache.spi.CaffeineCachingProvider`, `spring.cache.caffeine.spec=maximumSize=...,expireAfterAccess=...`) — the eviction spec is what bounds memory under sustained, identity-rotating abuse; size it to the rate-limit window. Requires `@EnableCaching` and `@EnableAspectJAutoProxy` on the `@SpringBootApplication` class (`DeltaBriefApplication`), and the `spring-boot-starter-aspectj` dependency — **not** `spring-boot-starter-aop`, which Spring Boot 4 has moved away from (confirmed via Maven Central's version history: `spring-boot-starter-aop`'s latest published release predates 4.0 GA, while `spring-boot-starter-aspectj` is the one still receiving releases against Boot 4).
- **Integration test layer** (proving the wiring, not re-testing the library's own bucket logic): use a `RequestPostProcessor` to set a unique simulated remote address per test method — `request.setRemoteAddr(ip)` — so a test's deliberate bucket exhaustion can't bleed into other tests sharing the same cached Spring context/Caffeine cache. Drive the endpoint past its configured capacity and assert the final response status is `429`. If two endpoints share a `name=` config, write one test per endpoint (per constraint 2 above, each has its own bucket). Reference: `AuthFlowIntegrationTests.rateLimiterRejectsRapidRepeatedResendForTheSameEmail()` and `.rateLimiterRejectsRapidRepeatedRegisterAttemptsForTheSameEmail()` (§3 Phase 1, `testing-auth-boundary-abuse-resistance`).
- **No dedicated unit-test layer**: the bucket algorithm itself is the library's, not this project's code — nothing to unit-test in isolation the way the earlier hand-rolled `RegistrationRateLimiterTests` did (now deleted).

### 6.7 Adding a log-output regression guardrail (no raw exception/PII ever logged)

- **Technique**: this project uses Log4j2 (`spring-boot-starter-log4j2`), not Logback, so there is no `ListAppender` available — attach a hand-rolled `AbstractAppender` directly to the target class's logger instead. Pattern:
  ```java
  Logger logger = (Logger) LogManager.getLogger(TargetClass.class);
  CapturingAppender appender = new CapturingAppender(); // extends AbstractAppender,
      // constructed via super("name", null, null, true, Property.EMPTY_ARRAY)
      // and overriding append(LogEvent event) to record events
  appender.start();
  logger.addAppender(appender);
  try {
      // ...trigger the code path under test...
      assertThat(appender.events).allSatisfy(event -> assertThat(event.getThrown()).isNull());
  } finally {
      logger.removeAppender(appender);
  }
  ```
- **What to assert**: that the captured `LogEvent`'s `getThrown()` is `null` (or, if a specific field/message is the leak vector, assert that field never appears in the rendered message) — not just that logging happened.
- **Simulating the failure path without touching real infra**: if the code path being tested depends on an external adapter (e.g. outbound email), give the test double a one-shot "fail next call" toggle rather than mocking with a framework — matches this project's mocking-only-at-the-true-external-edge policy (§6.2). Reference: `FakeEmailSender.failNextSendWith(...)`.
- **Reference test**: `RegistrationServiceTests.mailFailureLogNeverReceivesARawException()` (§3 Phase 1, `testing-auth-boundary-abuse-resistance`).

### 6.8 Per-rollout-phase notes

(Empty — fills in as each phase ships.)

## 7. What We Deliberately Don't Test

Exclusions agreed during the rollout (Phase 2 interview, Q5). Future
contributors should respect these unless the underlying assumption changes.

- **Spring Security's own defaults** (BCrypt, `hideUserNotFoundExceptions`, timing equalization) — framework-owned behavior, already covered by Spring Security's own test suite. Re-evaluate if we ever customize `AuthenticationProvider` behavior. (Source: Phase 2 interview Q5.)
- **Thymeleaf template rendering / HTML structure** — no real UI exists yet beyond a placeholder page; testing markup now tests scaffolding, not product behavior. Re-evaluate once S-03 ships real briefing-reading screens. (Source: Phase 2 interview Q5.)
- **AI-generated briefing content quality** — briefing generation (S-03+) isn't built yet; nothing to test. Re-evaluate once S-03 lands, likely via `--refresh` with its own quality strategy (e.g. hallucination-detection tests). (Source: Phase 2 interview Q5.)

## 8. Freshness Ledger

- Strategy (§1–§5) last reviewed: 2026-09-12
- Stack versions last verified: 2026-09-12
- AI-native tool references last verified: 2026-09-12

Refresh (`/10x-test-plan --refresh`) when:

- a new top-3 risk surfaces from the roadmap or archive,
- a recommended tool's `checked:` date is older than three months,
- the project's tech stack changes (new framework, new test runner),
- §7 negative-space no longer matches what the team believes.
