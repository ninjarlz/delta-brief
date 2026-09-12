<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: User Registration and Login Implementation Plan

- **Plan**: context/changes/user-registration-and-login/plan.md
- **Scope**: Full plan (Phases 1-4 of 4)
- **Date**: 2026-09-12
- **Verdict**: REJECTED
- **Findings**: 1 critical, 5 warnings, 3 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | WARNING |
| Safety & Quality | FAIL |
| Architecture | WARNING |
| Pattern Consistency | WARNING |
| Success Criteria | PASS |

## Investigated and dismissed (not a finding)

A sub-agent flagged missing CSRF tokens on all POST forms (`register`, `login`, `resend-verification`) as CRITICAL, reasoning that `thymeleaf-extras-springsecurity6` was required and absent. This is a **false positive** — verified directly: `curl http://localhost:8080/login` renders `<input type="hidden" name="_csrf" value="...">` in the actual HTML. `thymeleaf-extras-springsecurity6` is for `sec:*` template tags (a different concern); CSRF token injection into `th:action` forms is handled automatically by Spring's `RequestDataValueProcessor` mechanism once `spring-security-web` is on the classpath — exactly what `research.md:124` already documented during planning. No code change needed.

## Findings

### F1 — Login-gating reveals "unverified" status regardless of password correctness

- **Severity**: ❌ CRITICAL
- **Impact**: 🔬 HIGH — architectural stakes; think carefully before deciding
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/tul/deltabrief/auth/adapter/out/security/JpaUserDetailsService.java:29, src/main/java/pl/tul/deltabrief/config/SecurityConfig.java:30-31
- **Detail**: The discussed design (confirmed with the user before implementing) was: reveal "account exists but unverified" only when the password is *also* correct, keeping wrong-password attempts indistinguishable regardless of verification state. The actual behavior is broader. Spring Security's `DaoAuthenticationProvider` runs its pre-authentication checks (`.disabled(...)` → `DisabledException`) *before* password comparison. Verified directly: registering a fresh account, then POSTing `/login` with a **wrong** password against that unverified account still redirects to `/login?unverified`, not `/login?error`. This lets an attacker enumerate registered-but-unverified emails with zero valid credentials — a materially bigger leak than what was discussed and than what `plan.md`'s Phase 4 addendum currently claims ("reveals that an account exists (for a *correct* password against an unverified account)").
- **Fix A ⭐ Recommended**: Match the originally-discussed design. Remove `.disabled(...)` from `JpaUserDetailsService` (so Spring Security's pre-checks never short-circuit before password verification). Add a custom `AuthenticationSuccessHandler` that runs *after* a successful password match: if the authenticated user's `emailVerified` is false, invalidate the session/`SecurityContext` immediately and redirect to `/login?unverified`; otherwise proceed to `defaultSuccessUrl`. This way, `/login?unverified` only ever appears after the password was actually correct.
  - Strength: Delivers exactly the tradeoff that was discussed and agreed with the user — wrong password stays indistinguishable in every case.
  - Tradeoff: A bit more code than the built-in `disabled` flag; must carefully invalidate the session in the success handler so an unverified user is never left with a live authenticated session, even momentarily.
  - Confidence: HIGH — standard Spring Security pattern for "gate after auth, not before."
  - Blind spot: Needs a test asserting no authenticated session survives the unverified-redirect path (the session-invalidation timing is the one thing to get right).
- **Fix B**: Keep the current (broader) behavior, but correct `plan.md`'s addendum to state it accurately, and get the user's explicit sign-off that revealing "registered but unverified" (not a password, not full account access) is an acceptable tradeoff at this stage.
  - Strength: Zero code risk, fastest path, and honest about what the system actually does instead of overselling privacy it doesn't provide.
  - Tradeoff: A real, standing enumeration surface ships to production.
  - Confidence: MEDIUM — depends entirely on the user's risk tolerance for this pre-launch stage.
  - Blind spot: No compliance/regulatory review has been done, though unlikely to matter at this project's current scale.
- **Decision**: PENDING

### F2 — Timing side-channel on `/resend-verification`

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/tul/deltabrief/auth/application/RegistrationService.java:69-78
- **Detail**: `resendVerification` sends the verification email synchronously, inline with the request, only on the "known email + not yet verified" branch. The "unknown email" and "already verified" branches return near-instantly. Response content and status are uniform across all three cases (correctly avoiding a *content*-level leak), but the latency difference (an SMTP round-trip vs. an in-memory no-op) is a timing side-channel an attacker could use to enumerate unverified registered emails.
- **Fix A ⭐ Recommended**: Accept for now and document as a known limitation, mirroring the precedent already set in `impl-review-phase-3.md` F3 (login's own timing-based enumeration risk was reviewed and accepted as Spring Security's unmodified default behavior). Closing this properly needs async infrastructure this project doesn't have yet.
  - Strength: No new infrastructure; consistent with how the nearly-identical login-timing question was already handled in this same plan.
  - Tradeoff: The timing side-channel remains real, if noisy in practice.
  - Confidence: MEDIUM — real in principle; reliable exploitation depends on network conditions.
  - Blind spot: Actual latency delta in the deployed environment hasn't been measured.
- **Fix B**: Dispatch the email send asynchronously (`@Async` + a `ThreadPoolTaskExecutor` bean) so the endpoint always returns near-instantly regardless of branch.
  - Strength: Directly closes the side channel.
  - Tradeoff: Introduces this project's first async cross-cutting concern (config, and losing synchronous failure logging) for a WARNING-level finding.
  - Confidence: MEDIUM — straightforward Spring feature, but non-trivial scope growth for the severity involved.
  - Blind spot: Async failure observability isn't designed yet.
- **Decision**: PENDING

### F3 — Optimistic locking (`@Version`) and its migrations never documented in plan.md

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Scope Discipline
- **Location**: context/changes/user-registration-and-login/plan.md (Phase 1 section) vs. src/main/java/pl/tul/deltabrief/auth/domain/User.java:14,71-77, src/main/java/pl/tul/deltabrief/auth/adapter/out/persistence/UserJpaEntity.java:25-26, src/main/resources/db/migration/V2__index_verification_token.sql, V3__add_users_version_column.sql
- **Detail**: The `@Version`/optimistic-locking field (plus the `V2` partial-index and `V3` version-column migrations) is real, tested, and was already reviewed and approved in `impl-review-phase-2.md` (findings F4/F5) — but that approval was never backfilled into `plan.md` itself as an addendum, unlike every other post-implementation addition in this same plan (the MapStruct switch, the Phase 2-4 addenda). A reader of `plan.md` alone would have no idea `version` exists.
- **Fix**: Add a short addendum to Phase 1's plan.md section documenting the `@Version` addition and the `V2`/`V3` migrations, referencing `impl-review-phase-2.md` F4/F5 for full rationale — mirrors the addendum pattern already used everywhere else in this plan.
- **Decision**: PENDING

### F4 — Pico.css visual restyling never documented in plan.md

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Scope Discipline
- **Location**: context/changes/user-registration-and-login/plan.md (Phase 4 section) vs. src/main/resources/static/css/pico.min.css, src/main/resources/static/css/app.css, all 5 templates, src/main/java/pl/tul/deltabrief/config/SecurityConfig.java:24
- **Detail**: A full, real, deployed restyling (vendored Pico.css, custom `app.css`, all five templates updated, `/css/**` added to `SecurityConfig`'s permitAll) shipped as part of this plan with zero trace anywhere in `plan.md` — not even a passing mention, unlike every other Phase 4 addition, which each got a numbered "Changes Required" addendum.
- **Fix**: Add a numbered item to Phase 4's "Changes Required" section documenting the styling work, mirroring the format of the other Phase 4 addenda.
- **Decision**: PENDING

### F5 — Test classes violate AGENTS.md's `<Unit>Tests` naming convention

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/test/java/pl/tul/deltabrief/auth/AuthFlowIntegrationTest.java, .../adapter/out/persistence/UserRepositoryAdapterTest.java, .../application/RegistrationServiceTest.java, .../domain/UserTest.java
- **Detail**: `AGENTS.md` explicitly states: "name test classes `<Unit>Tests`" and its own referenced sample is `DeltaBriefApplicationTests` (plural). All four new test classes in this slice use the singular `Test` suffix instead — a clean, consistent violation of a written project rule (self-consistent across the slice, just consistently non-compliant with the doc).
- **Fix**: Rename all four classes to the `...Tests` suffix (`AuthFlowIntegrationTests`, `UserRepositoryAdapterTests`, `RegistrationServiceTests`, `UserTests`), and update any `--tests` filter references in plan.md/commit history-adjacent docs that cite the old names.
- **Decision**: PENDING

### F6 — Application layer catches adapter-specific exception types across port boundaries

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Architecture
- **Location**: src/main/java/pl/tul/deltabrief/auth/application/RegistrationService.java:57 (`DataIntegrityViolationException`), :91 (`MailException`)
- **Detail**: `RegistrationService` (application layer) catches `org.springframework.mail.MailException` — specific to `ResendSmtpEmailSender`'s use of `JavaMailSender` — even though the port it depends on, `shared.application.EmailSender`, declares no exception contract at all. If a future `EmailSender` implementation (the class is explicitly designed for reuse by S-06) throws something else, this catch becomes dead code and `register()`'s "email failure is non-fatal" guarantee silently breaks — a 500 on an already-persisted account. Catching `DataIntegrityViolationException` directly is a milder version of the same issue (Spring's generic Data Access exception, at least shared across all Spring Data modules rather than provider-specific).
- **Fix A ⭐ Recommended**: Have `EmailSender` declare its own unchecked exception (e.g. `EmailDeliveryException`); `ResendSmtpEmailSender` wraps any `MailException` into it; `RegistrationService` catches only the port-declared type. Leave the `DataIntegrityViolationException` catch as-is — it's a reasonably stable, cross-module abstraction, not tied to one adapter.
  - Strength: Closes the concrete risk (silent guarantee break on a future `EmailSender` swap) with a small, contained change.
  - Tradeoff: One new exception type; `EmailSender`'s contract grows slightly.
  - Confidence: HIGH — standard port/adapter exception-translation pattern.
  - Blind spot: None significant.
- **Fix B**: Leave both as-is; the coupling is proportionate for a single-implementation-per-port codebase, revisit when S-06 actually adds a second `EmailSender`.
  - Strength: Zero effort now; avoids speculative abstraction before it's needed.
  - Tradeoff: The risk stays latent and undetected until S-06 lands.
  - Confidence: MEDIUM — reasonable YAGNI call, but the reuse intent is already documented, not hypothetical.
  - Blind spot: Timeline for S-06 is unclear.
- **Decision**: PENDING

### F7 — `resend-verification`'s email parameter has no format/length validation

- **Severity**: 🔵 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/main/java/pl/tul/deltabrief/auth/adapter/in/web/RegistrationController.java:64
- **Detail**: `resendVerification(@RequestParam("email") String email)` has zero validation, while `register`'s equivalent field goes through `@Valid RegistrationRequest` with `@NotBlank @Email @Size(max = 255)`. No injection risk (parameterized JPA lookup either way), just inconsistent rigor between two sibling endpoints in the same controller.
- **Fix**: Add `@Email @Size(max = 255)` to the parameter (or route it through a tiny DTO reusing `RegistrationRequest.email`'s constraints).
- **Decision**: PENDING

### F8 — Minor persistence-layer hardening opportunities

- **Severity**: 🔵 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/tul/deltabrief/auth/domain/User.java:51 (token comparison), src/main/java/pl/tul/deltabrief/auth/adapter/out/persistence/UserRepositoryAdapter.java:20 (`save`), src/main/resources/db/migration/V1__create_users_table.sql (timestamp columns)
- **Detail**: Three small, independent, non-urgent items: (1) `User.verify()`'s `!verificationToken.equals(token)` is a non-constant-time comparison for a security-sensitive token (low practical risk — single-use UUID, real-world network jitter). (2) `UserRepositoryAdapter.save()` always builds a fresh transient entity via the mapper, so update paths (`verify`, `resendVerification`) go through JPA `merge()`, costing an extra internal `SELECT` versus mutating an already-managed entity. (3) `verification_token_expires_at`/`created_at` are `TIMESTAMP` (no time zone) backing `java.time.Instant` fields — correct only as long as the JVM/DB session time zone stays consistently UTC; `TIMESTAMPTZ` removes that assumption entirely.
- **Fix**: Optional, low-priority: constant-time comparison via `MessageDigest.isEqual(...)`; fetch-then-mutate the managed entity on update paths instead of always going through the mapper; a future migration to `TIMESTAMPTZ` if timezone assumptions ever become non-uniform. None urgent at current scale.
- **Decision**: PENDING

### F9 — Soft assertion in `defaultViewShowsPlaceholderForAuthenticatedVisitors`

- **Severity**: 🔵 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria
- **Location**: src/test/java/pl/tul/deltabrief/auth/AuthFlowIntegrationTest.java:127-130
- **Detail**: This test only asserts `status().isOk()` for an authenticated `GET /`. It would pass for any 200 response, not specifically the `placeholder` view — a slightly weaker proof than "shows placeholder" implies, though it does exercise the correct code path.
- **Fix**: Strengthen to `.andExpect(view().name("placeholder"))` or assert body content.
- **Decision**: PENDING

## Success Criteria Verification

**Automated** (re-run during this review, 2026-09-12): `./gradlew clean build --no-daemon` → BUILD SUCCESSFUL, 17 tests across 5 classes, 0 failures/errors. `curl -i https://delta-brief.onrender.com/actuator/health` → `200`, `{"status":"UP"}`. GitHub Actions `build-and-test` passed on PRs #23-#28.

**Manual** (cross-checked, not rubber-stamped): All Phase 1-4 manual criteria were independently verified with real evidence during this session — direct DB queries, real HTTP round-trips, real browser testing by the user, and (for 4.2/4.3) the user's own confirmation plus a Resend-dashboard delivery-log check that resolved an apparent failure into "delivered but spam-filed." Agent 1 additionally confirmed every plan.md addendum's specific behavioral claims against the actual code (session invalidation, token replacement, redirect targets) — no discrepancies found between what addenda claim and what the code does.

No missing or falsely-checked manual items found. The REJECTED verdict is driven entirely by F1 (a real behavioral gap between the discussed design and the implementation), not by any success-criteria failure.
