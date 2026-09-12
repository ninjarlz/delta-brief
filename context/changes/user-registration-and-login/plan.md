# User Registration and Login Implementation Plan

## Overview

Build DeltaBrief's first bounded-context module, `auth`: email/password registration with a minimal email-verification link (sent via Resend), session-based login/logout via Spring Security, and the `users` table backing it all. This is S-01 on the roadmap — Foundation F-01 (database connectivity) is already done, and OAuth/social login is explicitly out of scope here (tracked separately as `S-08`).

## Current State Analysis

The codebase has zero auth code today: `pl.tul.deltabrief` has exactly three classes (`DeltaBriefApplication`, `config/SecurityConfig` — a bare `SecurityFilterChain` permitting only `/` and `/actuator/health`, and the temporary `placeholder/PlaceholderController`). No `auth`, `user`, or `shared` package exists. `src/main/resources/db/migration/` contains only `.gitkeep` — no Flyway migrations exist yet. `src/main/resources/templates/` has one file (`placeholder.html`) with no Thymeleaf namespace, no form-handling convention, no shared layout. The architecture itself (session-based Spring Security form login, BCrypt-family hashing, JPA-backed `UserDetailsService`, no JWT/OAuth2/REST API) is already settled per `context/foundation/tech-stack.md` and confirmed current via `context/changes/user-registration-and-login/research.md`.

### Key Discoveries:

- **Primary key strategy is already decided**: `BIGSERIAL`/`IDENTITY`, not UUID — established during F-01 (`context/archive/2026-09-10-wire-database-connectivity/plan.md:29`), no baseline/extension migration needed.
- **Flyway migration convention**: `src/main/resources/db/migration/V1__*.sql` is the first real migration in this project.
- **CSRF + Thymeleaf already works with zero extra dependencies** — confirmed via Context7-sourced Spring Security 7.1 docs (`research.md:124`); do not add `thymeleaf-extras-springsecurity6` unless template-level `sec:*` tags are needed later (they aren't, for this plan).
- **`spring-boot-starter-validation` is missing** from `build.gradle` and is needed for `@Valid` on the registration form.
- **A reusable Testcontainers test-support class already exists**: `src/test/java/pl/tul/deltabrief/config/TestcontainersDatasourceConfig.java`, gated by a `CI`/`!CI` Spring profile set in `build.gradle`'s `test` task. It has a **known, unresolved caveat**: its local mode uses a *fixed* Docker host-network port, which assumes exactly one Spring test context exists in the whole suite (`context/archive/2026-09-10-wire-database-connectivity/reviews/impl-review.md`, finding F4, explicitly skipped — not fixed in code). Any new test importing this config **must** reuse the same context shape as `DeltaBriefApplicationTests` (same `@Import`, no extra mocked beans that would force a distinct context).
- **Resend's free-tier testing address (`onboarding@resend.dev`) can only deliver to the developer's own account email** — confirmed directly against Resend's docs during planning. Real end users cannot receive verification emails until a custom domain is verified with Resend. This is why Phase 4 exists as a separate, manual phase (mirroring F-01's Phase 3 pattern for Supabase).

## Desired End State

A visitor can register with email + password, receives a verification-link email (deliverable to real recipients once Phase 4's domain is verified; deliverable only to the developer's own address before that), can log in and out via a session-backed form, and — once Phase 4 lands — the deployed app on Render sends real verification emails from a verified domain.

Verification: `./gradlew test` proves the full register → verify → login → logout flow against a real, ephemeral Postgres; manually registering through the running app (locally, then on Render) produces a working verification email and a working login session.

## What We're NOT Doing

- **OAuth/social login** (Google, Facebook) — tracked separately as roadmap slice `S-08` (`context/changes/user-registration-and-login/frame.md` confirmed this is a clean, additive extension to build later, not now).
- **Password reset / "forgot password" flow** — not required by FR-001/FR-002; a natural follow-on but out of scope for this slice.
- ~~**Resend-verification-email flow**~~ **Added during Phase 4** — see Phase 4's addendum. Originally scoped out ("no UI to request a new one for this minimal slice"); the user asked for it after the deployed-app manual test surfaced exactly this gap (a `localhost`-pointing link from before `APP_BASE_URL` was set left an account permanently unverifiable without it, since duplicate-email rejection blocks re-registration).
- ~~**Gating login on email verification**~~ **Added during Phase 4** — see Phase 4's addendum. Originally: verification tracked `email_verified` for future use but did not block login. The user asked for login to actually require a verified account.
- **"Remember me" / persistent login** — the default Spring Security session timeout is used as-is; no extended timeout, no persistent-token table.
- **Display name or any profile field beyond email/password** — FR-001 requires only email + password; no personalization fields are added preemptively.
- **Rate-limiting registration or login attempts** — not required by the PRD's NFRs; a future hardening concern, not this slice's.
- **Email HTML templates** — the verification email is plain text with a link; no Thymeleaf email templating layer.

## Implementation Approach

Build `auth` as a proper DDD module (`domain` → `application` → `adapter.in.web` / `adapter.out.persistence` / `adapter.out.security`, per `AGENTS.md`), introducing a new `shared` package for the first time to hold a generic `EmailSender` port — deliberately generic (not auth-specific) since S-06 (email-briefing-delivery) will reuse the same abstraction and Resend account later. Phases build bottom-up: data model and persistence first (provable in isolation via the existing Testcontainers pattern), then registration/verification (introducing email-sending), then login/logout (wiring `SecurityConfig`, provable end-to-end), then the deployed-environment domain verification — mirroring F-01's proven "wire local → prove with tests → wire deployed" shape.

## Critical Implementation Details

**Resend recipient restriction until Phase 4.** Before a custom domain is verified with Resend, the `onboarding@resend.dev` sender can only deliver to the *developer's own* Resend account email — not arbitrary registered users. Phase 2 and Phase 3's manual verification steps must use the developer's own email address as the test registrant; this is expected, not a bug.

**Testcontainers single-context constraint.** Phase 3's new integration test must `@Import(TestcontainersDatasourceConfig.class)` into the *same* Spring context shape as `DeltaBriefApplicationTests` — no additional `@MockBean`/different active profiles that would force Spring to stand up a second context, since the local test container is bound to a fixed host-network port that only one context can hold at a time.

## Phase 1: Data model and persistence skeleton

### Overview

Establish the `users` table and the `auth` module's domain/persistence layers — provable in isolation via a repository-level test, with no web-facing behavior yet.

### Changes Required:

#### 1. `src/main/resources/db/migration/V1__create_users_table.sql`

**File**: `src/main/resources/db/migration/V1__create_users_table.sql`

**Intent**: Create the `users` table — the first real Flyway migration in this project.

**Contract**:
```sql
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    verification_token VARCHAR(255),
    verification_token_expires_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
```
The `UNIQUE` constraint on `email` is the correctness guarantee for duplicate-registration prevention (per the user's decision to pair it with an application-layer check for UX). Verification token fields live directly on `users` (not a separate table) — deliberately minimal, since only one active token per user is ever needed for this slice.

#### 2. `src/main/java/pl/tul/deltabrief/auth/domain/UserId.java`

**File**: `src/main/java/pl/tul/deltabrief/auth/domain/UserId.java`

**Intent**: The opaque identifier other modules (e.g. future `topic`) will reference — never the `User` aggregate itself, per `AGENTS.md`'s cross-module reference rule.

**Contract**: A small immutable value type wrapping the `Long` primary key (record or final class with `equals`/`hashCode`).

#### 3. `src/main/java/pl/tul/deltabrief/auth/domain/User.java`

**File**: `src/main/java/pl/tul/deltabrief/auth/domain/User.java`

**Intent**: The auth aggregate — email, password hash, verification state — with behavior for issuing and consuming a verification token.

**Contract**: Fields: `UserId id`, `String email`, `String passwordHash`, `boolean emailVerified`, `String verificationToken` (nullable), `Instant verificationTokenExpiresAt` (nullable), `Instant createdAt`. Methods: `issueVerificationToken(String token, Instant expiresAt)` (sets the two fields), `verify(String token, Instant now)` (returns whether verification succeeded — token matches and hasn't expired — and if so, sets `emailVerified = true` and clears the token fields). No password hashing logic lives here — that's the application layer's concern (this aggregate stores an already-hashed value).

#### 4. `src/main/java/pl/tul/deltabrief/auth/application/port/out/UserRepository.java`

**File**: `src/main/java/pl/tul/deltabrief/auth/application/port/out/UserRepository.java`

**Intent**: The port the application layer depends on, implemented by the persistence adapter — keeps `application` free of JPA imports.

**Contract**: `User save(User user)`, `Optional<User> findByEmail(String email)`, `Optional<User> findByVerificationToken(String token)`, `boolean existsByEmail(String email)`.

#### 5. `src/main/java/pl/tul/deltabrief/auth/adapter/out/persistence/UserJpaEntity.java`

**File**: `src/main/java/pl/tul/deltabrief/auth/adapter/out/persistence/UserJpaEntity.java`

**Intent**: The JPA mapping for the `users` table, kept separate from the domain `User` so the domain stays a plain object.

**Contract**: `@Entity` mapped to `users`, mirroring the migration's columns 1:1 (`@Id @GeneratedValue(strategy = GenerationType.IDENTITY)` for `id`, matching the `BIGSERIAL` column).

#### 6. `src/main/java/pl/tul/deltabrief/auth/adapter/out/persistence/UserJpaRepository.java`

**File**: `src/main/java/pl/tul/deltabrief/auth/adapter/out/persistence/UserJpaRepository.java`

**Intent**: Spring Data JPA plumbing.

**Contract**: `interface UserJpaRepository extends JpaRepository<UserJpaEntity, Long>` with derived query methods `findByEmail(String)`, `findByVerificationToken(String)`, `existsByEmail(String)`.

#### 7. `src/main/java/pl/tul/deltabrief/auth/adapter/out/persistence/UserRepositoryAdapter.java`

**File**: `src/main/java/pl/tul/deltabrief/auth/adapter/out/persistence/UserRepositoryAdapter.java`

**Intent**: Implements the `UserRepository` port, translating between `User` (domain) and `UserJpaEntity` (persistence).

**Contract**: `@Component implements UserRepository`, delegating to `UserJpaRepository` and mapping entity ↔ domain in both directions.

**Addendum (post-implementation)**: Entity↔domain mapping was switched from hand-written constructor calls to a MapStruct-generated mapper (`UserEntityMapper`, `org.mapstruct:mapstruct` added to `build.gradle`), at the user's request, to establish the project's mapping convention going forward. `toDomain` maps implicitly (entity's JavaBean getters match `User`'s constructor parameter names, with a `toUserId(Long)` helper for the `Long -> UserId` conversion); `toEntity` uses explicit `@Mapping(..., expression = ...)` per field since `User`'s accessors are fluent (no get/is prefix) and aren't auto-detected as JavaBean properties by MapStruct's default naming strategy. The adapter's own logic (assigning the generated id/version back onto the domain object post-save) is not a pure mapping concern and stays manual in `UserRepositoryAdapter`.

#### 8. Optimistic locking (`@Version`) and an index on `verification_token` (post-implementation additions)

**Addendum (added during full-plan `/10x-impl-review`, backfilling `impl-review-phase-2.md` findings F4/F5)**: Two Phase 2 review findings were fixed at the time but never backfilled into this plan document (see `reviews/impl-review.md` F3). (1) **F4 — no index on `verification_token`**: `findByVerificationToken` did a full-table-scan equality lookup with no index. Fixed via `V2__index_verification_token.sql` — a partial index (`WHERE verification_token IS NOT NULL`, since the column is null once verified). (2) **F5 — no optimistic locking on `UserJpaEntity`**: a concurrent double-submit of the same verification link could perform two independent merges instead of detecting a conflict (harmless today since both converge on the same idempotent end state, but worth having as project convention). Fixed by adding `@Version private Long version` to `UserJpaEntity`, threading a `version` field through `User` (domain, via `version()`/`assignVersion(...)`) and `UserRepositoryAdapter` (read in `toDomain()`, written back after `save()`), and `V3__add_users_version_column.sql` (`ALTER TABLE users ADD COLUMN version BIGINT NOT NULL DEFAULT 0` — additive, non-locking on Postgres, pre-existing rows backfilled to `0`). Both verified against a fresh Testcontainers Postgres and the existing local dev DB at the time.

#### 9. Constant-time token comparison and `TIMESTAMPTZ` columns (post-implementation additions)

**Addendum (added during full-plan `/10x-impl-review`, see `reviews/impl-review.md` F8)**: Two of three minor persistence-hardening observations from the review were applied (the third — fetching-then-mutating the managed entity on update paths to avoid an extra `merge()`-triggered `SELECT` — was deliberately **not** applied: it would silently weaken optimistic-locking correctness, since the current `merge()`-based `save()` compares the version the *original caller* read against the DB at save time, catching races across the full read-modify-write span; a naive fetch-then-mutate inside `save()` would instead re-read a fresh version and drop that check). (1) `User.verify()`'s token comparison switched from `String.equals()` to a constant-time `MessageDigest.isEqual(...)` comparison — low practical risk (single-use UUID, real-world network jitter) but the textbook-correct approach for a security-sensitive token compare. (2) `verification_token_expires_at` and `created_at` converted from `TIMESTAMP` (no time zone) to `TIMESTAMPTZ` via `V4__timestamptz_for_instant_columns.sql`, removing the implicit assumption that the JVM/DB session time zone stays consistently UTC. Verified against both a fresh Testcontainers Postgres and the existing local dev DB (incrementally, already at V3).

### Success Criteria:

#### Automated Verification:

- Build compiles cleanly: `./gradlew build --no-daemon`
- A repository-level test (new, reusing `TestcontainersDatasourceConfig`) proves: saving a `User` persists it, `findByEmail` returns it, and attempting to save two users with the same email surfaces the DB's `UNIQUE` constraint violation (not a silent duplicate)
- `./gradlew test --no-daemon` passes

#### Manual Verification:

- `docker compose up -d && ./gradlew bootRun` boots cleanly and Flyway reports `V1__create_users_table` applied successfully against the local Postgres

**Implementation Note**: After completing this phase and automated verification passes, pause here for manual confirmation before proceeding to Phase 2.

---

## Phase 2: Registration and email verification (dev-scoped)

### Overview

Add the registration flow and a minimal email-verification link, using Resend's free-tier testing address — deliverable to the developer's own email only until Phase 4's domain verification.

### Changes Required:

#### 1. `build.gradle`

**File**: `build.gradle`

**Intent**: Add the two missing dependencies this phase needs.

**Contract**: Add to `dependencies`:
```gradle
implementation 'org.springframework.boot:spring-boot-starter-validation'
implementation 'org.springframework.boot:spring-boot-starter-mail'
```

#### 2. `src/main/java/pl/tul/deltabrief/shared/application/EmailSender.java`

**File**: `src/main/java/pl/tul/deltabrief/shared/application/EmailSender.java`

**Intent**: A generic, auth-independent port for sending a plain-text email — deliberately placed in the new `shared` package (not `auth`) since S-06 (email-briefing-delivery) will reuse this same abstraction and Resend account later.

**Contract**: `void send(String to, String subject, String body)`.

**Addendum (post-implementation, added during full-plan `/10x-impl-review`)**: The port originally declared no failure contract at all, so `RegistrationService` (application layer) caught `org.springframework.mail.MailException` directly — an adapter-specific type leaking across the port boundary. If a future `EmailSender` implementation (the class is explicitly meant for reuse by S-06) threw something else, that catch would silently become dead code and break the documented "email failure is non-fatal" guarantee (see `reviews/impl-review.md` F6). Fixed by adding `EmailDeliveryException` (a new unchecked exception declared on this port) and having `RegistrationService` catch only that.

#### 3. `src/main/java/pl/tul/deltabrief/shared/adapter/out/email/ResendSmtpEmailSender.java`

**File**: `src/main/java/pl/tul/deltabrief/shared/adapter/out/email/ResendSmtpEmailSender.java`

**Intent**: Implements `EmailSender` using Spring Boot's auto-configured `JavaMailSender`, talking to Resend's SMTP relay.

**Contract**: `@Component implements EmailSender`, wraps a `SimpleMailMessage` built from `to`/`subject`/`body` plus a configured from-address, sent via the injected `JavaMailSender`.

**Addendum (post-implementation, added during full-plan `/10x-impl-review`)**: Wraps any `MailException` from `JavaMailSender.send(...)` into `EmailDeliveryException` — see the `EmailSender` addendum above.

#### 4. `src/main/resources/application.properties`

**File**: `src/main/resources/application.properties`

**Intent**: Configure Spring Mail against Resend's SMTP relay, and the from-address, both env-var-overridable per this project's existing `${VAR:default}` convention.

**Contract**: Add:
```properties
spring.mail.host=smtp.resend.com
spring.mail.port=587
spring.mail.username=resend
spring.mail.password=${RESEND_API_KEY:placeholder-not-a-real-key}
spring.mail.properties.mail.smtp.auth=true
spring.mail.properties.mail.smtp.starttls.enable=true
app.mail.from=${MAIL_FROM_ADDRESS:onboarding@resend.dev}
```
The developer needs a free Resend account and API key for local testing (no domain verification required for `onboarding@resend.dev` — but recall it only delivers to that account's own email).

#### 5. `src/main/java/pl/tul/deltabrief/auth/application/dto/RegistrationRequest.java`

**File**: `src/main/java/pl/tul/deltabrief/auth/application/dto/RegistrationRequest.java`

**Intent**: The validated registration form shape.

**Contract**: `email` (`@NotBlank @Email`), `password` (`@NotBlank @Size(min = 8)`), `confirmPassword` (`@NotBlank`) — cross-field password-match validation happens in the service, not via a Bean Validation annotation (simplest path for a two-field match).

#### 6. `src/main/java/pl/tul/deltabrief/auth/application/RegistrationService.java`

**File**: `src/main/java/pl/tul/deltabrief/auth/application/RegistrationService.java`

**Intent**: Orchestrates registration: uniqueness check, password hashing, verification token issuance, email dispatch.

**Contract**: `register(RegistrationRequest request)` — checks `userRepository.existsByEmail(...)` first (friendly rejection path), hashes the password via the `PasswordEncoder` bean, builds a `User` with a freshly generated token (`UUID.randomUUID().toString()`) expiring 24 hours out, saves it, then calls `EmailSender.send(...)` with a link of the form `{baseUrl}/verify?token={token}`. If the save itself throws `DataIntegrityViolationException` (the rare race the DB constraint catches), translates it into the same "email already registered" outcome as the upfront check.

#### 7. `src/main/java/pl/tul/deltabrief/auth/application/EmailVerificationService.java`

**File**: `src/main/java/pl/tul/deltabrief/auth/application/EmailVerificationService.java`

**Intent**: Handles a verification-link click.

**Contract**: `boolean verify(String token)` — looks up the user by token via `userRepository.findByVerificationToken(...)`, calls `User.verify(token, Instant.now())`, saves if successful, returns whether it succeeded (false for unknown/expired token, letting the controller decide the user-facing message).

#### 8. `src/main/java/pl/tul/deltabrief/auth/adapter/in/web/RegistrationController.java`

**File**: `src/main/java/pl/tul/deltabrief/auth/adapter/in/web/RegistrationController.java`

**Intent**: `GET /register` renders the form; `POST /register` validates and delegates to `RegistrationService`.

**Contract**: `POST /register` accepts `@Valid RegistrationRequest`, re-renders the form with field errors on validation failure (standard `BindingResult` + `th:errors` pattern), redirects to `/check-email` on success.

#### 9. `src/main/java/pl/tul/deltabrief/auth/adapter/in/web/VerificationController.java`

**File**: `src/main/java/pl/tul/deltabrief/auth/adapter/in/web/VerificationController.java`

**Intent**: `GET /verify?token=...` consumes a verification link.

**Contract**: Calls `EmailVerificationService.verify(token)`; redirects to `/login?verified` on success, `/login?verification_error` on failure — no dedicated result template, consistent with the existing `failureUrl`-style pattern.

#### 10. Templates: `register.html`, `check-email.html`

**File**: `src/main/resources/templates/register.html`, `src/main/resources/templates/check-email.html`

**Intent**: The registration form (establishing this project's first `xmlns:th` Thymeleaf form convention) and a simple "check your email" landing page after successful registration.

**Contract**: `register.html` posts to `/register` with `th:object`/`th:field` bindings for the three `RegistrationRequest` fields and `th:errors` blocks. `check-email.html` is static informational content.

**Addendum (post-implementation, added during `/10x-impl-review`)**: Manual testing during this phase surfaced four fixes beyond the contract above, all landed in commit `dd5c20f` — (1) `/register`, `/check-email`, `/verify` had to be added to `SecurityConfig`'s `permitAll` list now rather than in Phase 3, since Phase 2 can't be manually tested at all while they 403; the `PasswordEncoder` bean moved here for the same reason (`RegistrationService` needs it immediately, not in Phase 3). (2) SMTP connect/read/write timeouts (5s) were added after a real hang of 120+ seconds was observed with none configured. (3) `RegistrationService.register()` wraps the email-send call in a `try/catch (MailException)` so a delivery failure no longer 500s the registration — the account is still created and the failure is logged, consistent with verification never gating login. (4) A gitignored `.env` mechanism (auto-loaded by `build.gradle`'s `bootRun` task) plus `.env.example` were added so a developer's Resend API key is picked up with no extra flags and never risks being committed.

### Success Criteria:

#### Automated Verification:

- Build compiles cleanly: `./gradlew build --no-daemon`
- A new test-support `FakeEmailSender` (in `src/test/java/pl/tul/deltabrief/shared/adapter/out/email/`, `@Primary` `@Component` recording sent messages instead of calling Resend) is wired into the test context, so no test depends on real network access or a real API key
- A `RegistrationService` test proves: a new registration succeeds and issues a token; a duplicate email is rejected; a valid token verifies the user; an expired or unknown token does not
- `./gradlew test --no-daemon` passes

#### Manual Verification:

- Using the developer's own Resend account/API key locally, registering through the running app with the developer's own email address results in a real email arriving with a working verification link
- Clicking the link flips `email_verified` to true (confirm via a direct DB query against local Postgres) and redirects to `/login?verified`
- Registering with an already-used email shows a friendly inline error, not a stack trace

**Implementation Note**: After this phase's automated verification passes, pause for manual confirmation before proceeding to Phase 3.

---

## Phase 3: Login and logout

### Overview

Wire session-based login/logout via Spring Security, and prove the full register → verify → login → logout flow end-to-end against a real, ephemeral Postgres.

### Changes Required:

#### 1. `src/main/java/pl/tul/deltabrief/auth/adapter/out/security/JpaUserDetailsService.java`

**File**: `src/main/java/pl/tul/deltabrief/auth/adapter/out/security/JpaUserDetailsService.java`

**Intent**: Backs Spring Security's authentication with the `users` table.

**Contract**: `@Service implements UserDetailsService` — `loadUserByUsername(String email)` looks up via `UserRepository.findByEmail(...)`, throwing `UsernameNotFoundException` if absent, otherwise returning `org.springframework.security.core.userdetails.User.withUsername(email).password(passwordHash).authorities("ROLE_USER").build()`.

#### 2. `src/main/java/pl/tul/deltabrief/config/SecurityConfig.java`

**File**: `src/main/java/pl/tul/deltabrief/config/SecurityConfig.java`

**Intent**: Add form login, logout, a `PasswordEncoder` bean, and permit the new public auth pages.

**Contract**:
```java
http.authorizeHttpRequests(authorize -> authorize
		.requestMatchers("/", "/actuator/health", "/register", "/check-email", "/login", "/verify").permitAll()
		.anyRequest().authenticated())
	.formLogin(form -> form
		.loginPage("/login")
		.loginProcessingUrl("/login")
		.failureUrl("/login?error")
		.defaultSuccessUrl("/", false)
		.permitAll())
	.logout(logout -> logout
		.logoutUrl("/logout")
		.logoutSuccessUrl("/login?logout")
		.invalidateHttpSession(true)
		.deleteCookies("JSESSIONID")
		.permitAll());
```
Add a `PasswordEncoder` bean: `PasswordEncoderFactories.createDelegatingPasswordEncoder()`.

#### 3. `src/main/java/pl/tul/deltabrief/auth/adapter/in/web/AuthPageController.java`

**File**: `src/main/java/pl/tul/deltabrief/auth/adapter/in/web/AuthPageController.java`

**Intent**: Renders the `GET /login` page (Spring Security handles the `POST /login` submission itself; no controller method needed for that).

**Contract**: `@GetMapping("/login")` returns the `login` view.

#### 4. `src/main/resources/templates/login.html`

**File**: `src/main/resources/templates/login.html`

**Intent**: The login form.

**Contract**: Posts to `/login`, shows an error message when `${param.error}` is present, a "verified, please log in" message when `${param.verified}` is present, and a logout confirmation when `${param.logout}` is present.

#### 5. `src/test/java/pl/tul/deltabrief/auth/AuthFlowIntegrationTests.java`

**File**: `src/test/java/pl/tul/deltabrief/auth/AuthFlowIntegrationTests.java`

**Intent**: Proves the full flow against a real Postgres. **Must** `@Import(TestcontainersDatasourceConfig.class)` into the same context shape as `DeltaBriefApplicationTests` (per the Critical Implementation Details note above — no extra mocked beans that would force a second Spring context).

**Contract**: `@SpringBootTest @AutoConfigureMockMvc`, using `MockMvc` to: register a user, extract the verification token from the `FakeEmailSender`'s recorded message, hit `/verify?token=...`, then log in via `/login` with the registered credentials and confirm a redirect to `/` with an authenticated session, then log out and confirm the session is invalidated.

**Addendum (post-implementation, added during `/10x-impl-review`)**: Two deviations from the contract above, both landed in commit `cc5a7fe`. (1) The test does **not** use `@AutoConfigureMockMvc` — that annotation changes Spring's test-context cache key, which would force a second `ApplicationContext` (and a second Testcontainers container competing for the same fixed host-network port `TestcontainersDatasourceConfig` uses locally). `MockMvc` is instead built manually via `MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build()`, which applies the real Spring Security filter chain (CSRF and authentication genuinely enforced) while reusing the exact same shared context — confirmed only one container started across all tests. (2) Manual testing surfaced that Spring Boot Actuator auto-adds a mail health indicator that tests live SMTP connectivity to Resend on every `/actuator/health` call — this returned `503 DOWN` in an environment without outbound SMTP access, and the same risk applies in production, where Render uses this exact endpoint to decide whether to keep routing traffic. Fixed via `management.health.mail.enabled=false` in `application.properties`; `RegistrationService` already handles mail failures gracefully on its own, so this indicator isn't needed for the app's health signal.

### Success Criteria:

#### Automated Verification:

- `./gradlew test --no-daemon` passes, including `AuthFlowIntegrationTests`'s full register→verify→login→logout sequence against a real Testcontainers Postgres
- `./gradlew build --no-daemon` passes end-to-end
- Pushing through the existing PR flow: `build-and-test` passes in GitHub Actions with no `ci-cd.yml` changes

#### Manual Verification:

- Locally: register, verify via the emailed link, log in with the correct password (succeeds), log in with a wrong password (fails with an inline error, not a stack trace), log out (session ends, revisiting a page that would require auth — once any exists — would redirect to login)
- `curl -i http://localhost:8080/actuator/health` still returns `200` (unaffected by the security changes)

**Implementation Note**: After this phase's automated verification passes (including the real CI run), pause for confirmation before proceeding to Phase 4 — Phase 4 touches the deployed environment.

---

## Phase 4: Wire the deployed environment

### Overview

Verify a real domain with Resend and wire production credentials into Render, so deployed verification emails reach real users — mirroring F-01's Phase 3 pattern for Supabase.

### Changes Required:

#### 1. Resend domain verification (manual, browser-only)

**Intent**: Verify a domain the user owns with Resend so production emails aren't limited to `onboarding@resend.dev`'s single-recipient restriction.

**Contract**: No repo file changes. Add the domain in the Resend dashboard, add the DNS records it provides (SPF/DKIM) at the domain's DNS provider, wait for verification to complete.

#### 2. Render environment variables (manual, browser-only)

**Intent**: Point the deployed app at the real Resend account and verified sending address.

**Contract**: On the `delta-brief` Render service, set `RESEND_API_KEY` (the account's real API key) and `MAIL_FROM_ADDRESS` (e.g. `noreply@<verified-domain>`). Trigger a redeploy (or wait for the next merge to `main`).

**Addendum (post-implementation)**: Three deviations from the contract above, discovered during this phase's manual verification. (1) A domain was not purchased/verified yet; the user opted to test Phase 4 for now using their own Resend-account email address (the free-tier `onboarding@resend.dev` sender, which can only deliver to that one address) rather than blocking on a domain purchase. This means 4.2 below is verified only for the developer's own address, not an arbitrary non-developer-owned recipient — full arbitrary-recipient delivery still requires a verified domain, which remains a follow-up (not abandoned). (2) The contract above omitted `APP_BASE_URL` — `RegistrationService` builds the verification link from `app.base-url` (`application.properties`), which defaults to `http://localhost:8080` when unset. The first real-deploy registration test produced a verification link pointing at `localhost:8080` instead of the deployed app. Fixed by also setting `APP_BASE_URL=https://delta-brief.onrender.com` on Render and redeploying. (3) A verification email sent from `onboarding@resend.dev` landed in the recipient's spam folder — confirmed via the Resend dashboard's own delivery log (the send succeeded; our SMTP call threw no exception, which is also why it produced no application log line — a real logging gap, since success is currently never logged, only `MailException` failures). This is an expected consequence of the shared, unauthenticated testing domain having no sender reputation or SPF/DKIM alignment with the recipient — not a code defect. Retrieving the email from spam and clicking through verified correctly. Reinforces that reliable inbox delivery (not just successful sending) still requires the deferred verified-domain follow-up.

#### 3. Resend-verification-email flow (code change, added during this phase)

**Intent**: Surfaced during manual verification — an account whose verification link pointed at `localhost:8080` (before `APP_BASE_URL` was fixed) could never be verified afterward, since re-registering the same email is rejected as a duplicate and there was no other way to get a fresh token. The user asked for this as a real feature, not just a one-off workaround.

**Contract**: `RegistrationService.resendVerification(email)` — looks up the account, no-ops silently for an unknown email or an already-verified one (so the caller can't use it to enumerate registered addresses), otherwise reissues a new token (invalidating the old one, since `User.issueVerificationToken` overwrites the stored value) and resends the email. `RegistrationController` adds `GET`/`POST /resend-verification`; both the success and no-op paths redirect to the same generic `/check-email` page. New template `resend-verification.html`, linked from `check-email.html`. `SecurityConfig` permits `/resend-verification`. Covered by three new `RegistrationServiceTests` cases (fresh token replaces and invalidates the old one; no-op for an already-verified account; no-op for an unknown email) plus a full manual DB-level smoke test locally (register → resend → confirm old token rejected, new token verifies, `email_verified` flips to true).

**Addendum (post-implementation, added during full-plan `/10x-impl-review`)**: The `email` parameter had no format/length validation, unlike `/register`'s `@Valid RegistrationRequest` (see `reviews/impl-review.md` F7) — inconsistent rigor, though no injection risk either way (parameterized lookup). Fixed: `RegistrationController` is now `@Validated`, and the parameter carries `@Email @Size(max = 255)`; a `ConstraintViolationException` handler redirects to the same generic `/check-email` outcome as every other input (malformed or otherwise), so this adds no new information leak. Verified: both a malformed email and an over-255-character email now redirect cleanly with no error page.

**Addendum (post-implementation, added during full-plan `/10x-impl-review`)**: The known-unverified branch did a real synchronous SMTP round-trip while the unknown/already-verified branches returned near-instantly — a timing side-channel for email enumeration even though response content was uniform (see `reviews/impl-review.md` F2). Fixed by making `resendVerification` run off the request thread: `@Async("emailTaskExecutor")`, backed by a new `AsyncConfig` (`@EnableAsync` + a small `ThreadPoolTaskExecutor` bean, gated by `app.async.email.enabled` so tests can substitute a same-thread executor deterministically via a new `SynchronousAsyncConfig` test config). Verified: both branches now return in ~13ms regardless of which path is taken, and the async work still completes correctly (new token issued, DB updated).

#### 4. Gate login on email verification (code change, added during this phase)

**Intent**: The user asked for login to actually require a verified account, reversing the original "verification never gates login" decision.

**Contract**: `JpaUserDetailsService` returns a custom `AppUserDetails` (`email`, `passwordHash`, `emailVerified`) instead of Spring Security's built-in `User`. `SecurityConfig`'s `formLogin` uses a custom `successHandler`: only *after* `DaoAuthenticationProvider` has already matched the password does the handler check `emailVerified` — if false, it immediately invalidates the session (`SecurityContextLogoutHandler`) and redirects to `/login?unverified` (a distinct message with a resend-verification link); if true, redirects to `/`. A wrong password (against a verified or unverified account, or an unknown email) always falls through to the unchanged generic `/login?error` — verification status is never checked before the password, so it can never be revealed by a password-guessing attempt. Covered by `AuthFlowIntegrationTests` cases `unverifiedAccountCannotLogIn` and `wrongPasswordOnUnverifiedAccountStaysGeneric`, plus a full manual smoke test locally covering all four combinations (unverified/verified × correct/wrong password).

**Addendum (post-implementation, added during full-plan `/10x-impl-review`)**: The first implementation of this item used Spring Security's built-in `UserDetails.disabled(...)` flag, checked by `DaoAuthenticationProvider`'s *pre*-authentication checks — which run *before* password verification. This meant `/login?unverified` was returned for *any* password (right or wrong) against a registered-but-unverified account, not just a correct one as intended and originally documented above — a materially broader email-enumeration leak. Found and fixed during the full-plan review (see `reviews/impl-review.md` F1): switched to the `AppUserDetails` + `successHandler` design described in the contract above, which gates on verification only after a successful password match. Verified directly (both automated and manual): a wrong password against an unverified account now returns the same generic `/login?error` as every other wrong-password case.

#### 5. Fix "prepared statement already exists" against Supavisor (code change, added during this phase)

**Intent**: A manual test of the newly-deployed resend-verification flow (item #3 above) silently failed to send an email. Render logs showed `PSQLException: prepared statement "S_2" already exists` — the classic JDBC + PgBouncer/Supavisor **transaction-mode** pooling failure mode: the pooler hands a different physical server connection to each transaction, but the PostgreSQL JDBC driver's server-side prepared-statement cache assumes a stable one, so statement names collide across transactions. Unlike the already-handled `MailException` path, this exception was uncaught and silently aborted the request before any email-send attempt.

**Contract**: `spring.datasource.hikari.data-source-properties.prepareThreshold=0` added to `application.properties` — disables the JDBC driver's server-side prepared statements (falls back to the simple/extended query protocol), the standard fix for this exact pooling mode. Applies uniformly to both the deployed Supavisor connection and local's non-pooled Postgres (a no-op there, but harmless). Not something `/10x-research`/`/10x-plan` could have anticipated — it only reproduces under `infrastructure.md`'s already-chosen Supavisor transaction-pooler in real deployed traffic, not against the local Testcontainers/Postgres setup this project's whole test suite runs against.

#### 6. Default view for unauthenticated visitors (code change, added during this phase)

**Intent**: Surfaced during manual verification — visiting `/` gave every visitor the placeholder page regardless of auth state, with no path into `/login`/`/register` from the root URL.

**Contract**: `PlaceholderController.home()` now takes an `HttpServletRequest` and returns `redirect:/login` when `request.getUserPrincipal() == null` (unauthenticated), otherwise still renders `placeholder` (until a real home page ships). `login.html` already links to `/register`, so this gives unauthenticated visitors a path into both flows from `/`. Covered by two new `AuthFlowIntegrationTests` cases: `defaultViewRedirectsAnonymousVisitorsToLogin`, `defaultViewShowsPlaceholderForAuthenticatedVisitors` (the latter using `SecurityMockMvcRequestPostProcessors.user(...)` rather than a full login round-trip).

**Addendum (post-implementation, added during full-plan `/10x-impl-review`)**: `defaultViewShowsPlaceholderForAuthenticatedVisitors` originally only asserted `status().isOk()` — a soft assertion that would pass for any 200 response, not specifically the `placeholder` view (see `reviews/impl-review.md` F9). Strengthened to also assert `view().name("placeholder")`.

#### 7. Visual restyling with Pico.css (code change, added during this phase)

**Intent**: The user asked for the auth pages to be visually beautified — a clean, concise, modern look — rather than the unstyled default HTML the plan otherwise produced.

**Contract**: Pico.css v2.1.1 vendored locally (`src/main/resources/static/css/pico.min.css`, no CDN dependency) plus a small custom `app.css` giving a centered auth-card layout, notice/error banners, and `aria-invalid` validation styling — matching the styling approach already named in `tech-stack.md` ("Responsive CSS (e.g. Pico.css)"). All five templates (`login.html`, `register.html`, `check-email.html`, `placeholder.html`, `resend-verification.html`) reference both stylesheets. `SecurityConfig` permits `/css/**` so the stylesheet loads for unauthenticated visitors. Verified locally: pages and CSS return `200` unauthenticated, no regressions to redirect/rendering behavior, full test suite unaffected. No automated test coverage (purely visual — no behavior to assert).

### Success Criteria:

#### Automated Verification:

- `curl -i https://delta-brief.onrender.com/actuator/health` still returns `200`

#### Manual Verification:

- Registering through the deployed app with a real (non-developer-owned) email address results in a verification email actually arriving in that inbox
- Render deploy logs show no mail-configuration errors on startup

**Implementation Note**: This is the final phase — no further manual pause needed after its verification passes.

---

## Testing Strategy

### Unit Tests:

- `RegistrationService`: duplicate-email rejection (both the upfront check and the `DataIntegrityViolationException` fallback path), successful registration issuing a token, password hashing delegated to the `PasswordEncoder` bean (mocked or real — real is cheap here).
- `User` domain aggregate: `verify(token, now)` returns false for a wrong token and for an expired token, true and clears state for a valid one.

### Integration Tests:

- Phase 1's repository-level test (JPA mapping + unique constraint) and Phase 3's `AuthFlowIntegrationTests` (full register→verify→login→logout) — both against a real Testcontainers Postgres, reusing the existing CI/local dual-mode pattern.

### Manual Testing Steps:

1. Phase 1: `docker compose up -d && ./gradlew bootRun` — confirm the migration applies.
2. Phase 2: register with the developer's own email, confirm a real verification email arrives and the link works.
3. Phase 3: full register → verify → login → logout cycle through the running app, plus a wrong-password attempt.
4. Phase 4: register through the deployed app with a real (non-developer) email address, confirm delivery.

## Performance Considerations

None beyond what's already established project-wide (HikariCP pool cap at 5, per F-01). Password hashing (BCrypt via the delegating encoder) is deliberately slow by design — no concern at this scale.

## Migration Notes

Not applicable — `V1__create_users_table.sql` is a brand-new table with no existing data.

## References

- Related research: `context/changes/user-registration-and-login/research.md`
- Related framing: `context/changes/user-registration-and-login/frame.md` (confirms OAuth is out of scope; the promoted follow-on slice is `S-08`)
- Foundation: `context/foundation/roadmap.md` § S-01 (user-registration-and-login)
- Prior pattern this follows: `context/archive/2026-09-10-wire-database-connectivity/plan.md` (Phase structure: wire local → prove with tests → wire deployed)
- Spring Security 7.1 form login / OAuth2 reference: https://docs.spring.io/spring-security/reference/7.0/servlet/authentication/passwords/form.html
- Resend SMTP docs: https://resend.com/docs/send-with-smtp

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Data model and persistence skeleton

#### Automated

- [x] 1.1 Build compiles cleanly: `./gradlew build --no-daemon`
- [x] 1.2 Repository-level test proves save/find/unique-constraint behavior
- [x] 1.3 `./gradlew test --no-daemon` passes

#### Manual

- [x] 1.4 Local `bootRun` boots cleanly with `V1__create_users_table` applied

### Phase 2: Registration and email verification (dev-scoped)

#### Automated

- [x] 2.1 Build compiles cleanly: `./gradlew build --no-daemon` — dd5c20f
- [x] 2.2 `FakeEmailSender` wired into test context, no real network/API key needed — dd5c20f
- [x] 2.3 `RegistrationService` test proves registration, duplicate-rejection, and token verification behavior — dd5c20f
- [x] 2.4 `./gradlew test --no-daemon` passes — dd5c20f

#### Manual

- [x] 2.5 A real verification email arrives at the developer's own address and its link works — dd5c20f
- [x] 2.6 Clicking the link flips `email_verified` and redirects to `/login?verified` — dd5c20f
- [x] 2.7 Duplicate-email registration shows a friendly inline error — dd5c20f

### Phase 3: Login and logout

#### Automated

- [x] 3.1 `./gradlew test --no-daemon` passes, including the full register→verify→login→logout integration test — cc5a7fe
- [x] 3.2 `./gradlew build --no-daemon` passes end-to-end — cc5a7fe
- [x] 3.3 GitHub Actions `build-and-test` passes with no `ci-cd.yml` changes — bd8dee3

#### Manual

- [x] 3.4 Full manual register→verify→login→logout cycle succeeds; wrong password fails cleanly — cc5a7fe
- [x] 3.5 `curl -i http://localhost:8080/actuator/health` still returns 200 — cc5a7fe

### Phase 4: Wire the deployed environment

#### Automated

- [x] 4.1 `curl -i https://delta-brief.onrender.com/actuator/health` returns 200

#### Manual

- [x] 4.2 A real (non-developer-owned) email address receives a verification email through the deployed app — f118042
- [x] 4.3 Render deploy logs show no mail-configuration errors — f118042
