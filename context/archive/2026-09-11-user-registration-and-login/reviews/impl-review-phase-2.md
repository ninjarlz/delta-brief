<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: User Registration and Login Implementation Plan

- **Plan**: context/changes/user-registration-and-login/plan.md
- **Scope**: Phase 2 of 4
- **Date**: 2026-09-11
- **Verdict**: NEEDS ATTENTION
- **Findings**: 0 critical, 2 warnings, 3 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | WARNING |
| Scope Discipline | WARNING |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

## Findings

### F1 — Manual-testing-driven fixes not reflected as a plan.md addendum

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: context/changes/user-registration-and-login/plan.md (Phase 2 vs. Phase 3 sections) vs. src/main/java/pl/tul/deltabrief/config/SecurityConfig.java, src/main/resources/application.properties, build.gradle, .gitignore, .env.example
- **Detail**: Phase 2's actual commit (`dd5c20f`) brought forward work originally assigned to Phase 3 (the `PasswordEncoder` bean and `permitAll` for `/register`/`/check-email`/`/verify`), added SMTP timeout properties, `MailException` handling, and an entirely new `.env`/`.env.example`/`bootRun`-loading mechanism — none of which appear in Phase 2's "Changes Required" text. All of these are clearly explained in the `dd5c20f` commit message itself ("Two fixes discovered via manual testing, not in the original plan..."), so nothing is silently undocumented — but the plan document itself (the source of truth future readers and `/10x-plan-review`/`/10x-archive` will consult) doesn't carry the same explanation, unlike the addendum pattern already established for this exact plan during earlier OAuth-scope framing work.
- **Fix**: Add a short addendum to Phase 2's plan.md section (or a note above `## Progress`) summarizing these four discoveries and pointing to the `dd5c20f` commit message for full detail — mirrors the addendum pattern already used elsewhere in this project's plans.
- **Decision**: FIXED — addendum added to plan.md's Phase 2 section.

### F2 — No max-length validation on email/password; broad exception catch could misclassify a length violation as a duplicate email

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/tul/deltabrief/auth/application/dto/RegistrationRequest.java; src/main/java/pl/tul/deltabrief/auth/application/RegistrationService.java (the `DataIntegrityViolationException` catch)
- **Detail**: `RegistrationRequest` has `@NotBlank`/`@Email`/`@Size(min = 8)` but no upper bound on either `email` or `password`. The `users.email` column is `VARCHAR(255)` with no matching Bean Validation max, so an oversized email would fail at the DB layer rather than with a friendly form error — and `RegistrationService.register()`'s catch around `save()` treats *any* `DataIntegrityViolationException` as "email already registered" (`EmailAlreadyRegisteredException`), so a length-violation would incorrectly tell a user their email is already taken. An unbounded password length is also a minor DoS surface against the password hasher (BCrypt-family hashing cost scales with input, though bounded practically by request size limits).
- **Fix A ⭐ Recommended**: Add `@Size(max = 255)` to `email` and a reasonable `@Size(max = 100)` to `password` on `RegistrationRequest`, closing the gap at the form-validation layer before it ever reaches the DB constraint.
  - Strength: Simple, matches the existing validation style on the same class; user gets a proper inline error instead of a misclassified one.
  - Tradeoff: An arbitrary max (100) needs picking; not a real design cost, just a number to commit to.
  - Confidence: HIGH — straightforward Bean Validation addition, no interaction with other logic.
  - Blind spot: None significant.
- **Fix B**: Narrow the `DataIntegrityViolationException` catch to only the email-uniqueness constraint (e.g. inspect the exception's constraint name) rather than treating any DB integrity violation as a duplicate email.
  - Strength: Fixes the misclassification at its actual source, regardless of what causes a future integrity violation.
  - Tradeoff: More fragile (depends on matching a DB-specific constraint name string) and doesn't address the DoS-sized-payload angle Fix A also closes.
  - Confidence: MEDIUM — works, but Fix A is the more direct and simpler fix for both underlying issues.
  - Blind spot: Haven't confirmed the exact PostgreSQL constraint-name format Hibernate surfaces in the exception for pattern-matching.
- **Decision**: FIXED via Fix A — added `@Size(max = 255)` to `email` and `@Size(max = 100)` to `password`/`confirmPassword` on `RegistrationRequest`. Build and full test suite still pass.

### F3 — Plaintext password echoed back into the form on a validation error

- **Severity**: 🔵 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/tul/deltabrief/auth/adapter/in/web/RegistrationController.java; src/main/resources/templates/register.html
- **Detail**: On a validation failure (e.g. duplicate email, password mismatch), the controller re-renders `register` with the same `RegistrationRequest`, including the submitted plaintext password bound via `th:field="*{password}"` — so it's echoed back into the response HTML. Minor exposure surface (browser autofill/history caching a page containing the password) rather than an active vulnerability, since this is same-origin, HTTPS-only content.
- **Fix**: Clear `form.setPassword(null)` and `form.setConfirmPassword(null)` in `RegistrationController` before returning the `register` view on any validation failure.
- **Decision**: FIXED — extracted a `clearPasswordsAndReturnToForm()` helper used on both error paths (validation failure and duplicate email). Build and tests still pass.

### F4 — No index on `verification_token`

- **Severity**: 🔵 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/resources/db/migration/V1__create_users_table.sql
- **Detail**: `findByVerificationToken` does a plain equality lookup against `verification_token`, which has no index — a full table scan at current scale (negligible), but would degrade as the `users` table grows.
- **Fix**: Add an index on `verification_token` in a future migration (`CREATE INDEX ... ON users (verification_token) WHERE verification_token IS NOT NULL` — partial index, since the column is null once verified) — not urgent at this table size.
- **Decision**: FIXED — added `V2__index_verification_token.sql`. Verified it applies cleanly both against a fresh Testcontainers Postgres (full test suite) and incrementally against the existing local dev DB (already at V1); confirmed the index exists via `pg_indexes`.

### F5 — No optimistic locking on `UserJpaEntity`

- **Severity**: 🔵 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/main/java/pl/tul/deltabrief/auth/adapter/out/persistence/UserJpaEntity.java
- **Detail**: No `@Version` field, so a concurrent double-submit of the same verification link performs two independent merges rather than detecting a conflict. Currently harmless — both merges converge on the same idempotent end state (`emailVerified = true`, token cleared) — but worth knowing if optimistic locking becomes expected convention elsewhere in the codebase later.
- **Fix**: Optional — add `@Version private Long version;` if/when this pattern is adopted project-wide; not needed for this slice's correctness today.
- **Decision**: FIXED — added `@Version` to `UserJpaEntity`, threaded a `version` field through `User` (domain) and `UserRepositoryAdapter` (both directions: read on `toDomain()`, written back via `assignVersion()` after `save()`), and added `V3__add_users_version_column.sql` (the `@Version` field required an actual DB column that didn't exist — caught this immediately via a full test-suite failure, fixed before proceeding). Verified against both a fresh Testcontainers Postgres and the existing local dev DB (pre-existing rows correctly backfilled to `version=0`).

## Supplementary Fix (found during triage, not an original finding)

While applying F3's fix, noticed `RegistrationController.register()`'s `form.getPassword().equals(form.getConfirmPassword())` would NPE if `password` were entirely absent from the request (not reachable via `register.html`'s form, but reachable via a raw POST omitting the field). Fixed to `Objects.equals(...)` at the user's request. Build and tests still pass.

## Success Criteria Verification

**Automated** (re-run during this review, 2026-09-11): `./gradlew clean build --no-daemon` → BUILD SUCCESSFUL, all 10 tests passing (`UserRepositoryAdapterTest`, `RegistrationServiceTest`, `UserTest`, `DeltaBriefApplicationTests`). PR #23's GitHub Actions `build-and-test` also passed.

**Manual** (cross-checked against evidence in this session, not rubber-stamped): 2.5 (real verification email via the user's own Resend account) confirmed by the user directly; 2.6 (link flips `email_verified`, redirects to `/login?verified`) and 2.7 (duplicate-email shows a friendly inline error) were independently verified with real HTTP requests against the running app before being checked off, not just assumed.

No missing or falsely-checked manual items found.
