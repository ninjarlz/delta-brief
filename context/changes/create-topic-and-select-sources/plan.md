# Create Topic and Select Sources (S-02) Implementation Plan

## Overview

Adds the `topic` bounded context: a user can create a watched topic (name + a preset category), browse their topic list (which becomes the new authenticated home page, replacing the placeholder), and delete a topic. Closes FR-003, FR-005, FR-006.

## Current State Analysis

- No `topic` module exists yet (`src/main/java/pl/tul/deltabrief/` has `auth`, `config`, `shared`, `placeholder` only).
- `PlaceholderController` (`src/main/java/pl/tul/deltabrief/placeholder/PlaceholderController.java`) renders a static landing page for authenticated visitors and redirects unauthenticated ones to `/login`; its own doc comment says to delete it once `topic` ships a real home page.
- `SecurityConfig` (`src/main/java/pl/tul/deltabrief/config/SecurityConfig.java`) currently `permitAll()`s `/` specifically so `PlaceholderController` can do its own manual authenticated/unauthenticated branching; everything else defaults to `anyRequest().authenticated()`.
- `AppUserDetails` (`src/main/java/pl/tul/deltabrief/auth/adapter/out/security/AppUserDetails.java`) carries only `email`, `passwordHash`, `emailVerified` — no user ID. `JpaUserDetailsService` already loads the full `User` domain object (which has `.id()`) but discards it when constructing `AppUserDetails`.
- The roadmap's only blocking unknown for this slice — the actual preset source lists per category — is now resolved (see Key Discoveries).
- `auth` is the only existing module; it establishes the pattern this plan mirrors exactly: `domain` (plain aggregate + ID record, e.g. `User`/`UserId`) → `application` (service + `port.out` interface) → `adapter.out.persistence` (JPA entity + MapStruct mapper + Spring Data repository + adapter implementing the port) → `adapter.in.web` (Thymeleaf-rendering `@Controller`).

## Desired End State

- An authenticated user visiting `/` sees their topic list (or an empty-state with a "Create topic" call to action if they have none).
- `GET /topics/new` shows a creation form: topic name + a category picker (populated from the DB) + a disabled "Customize sources — coming soon" button.
- `POST /topics` creates the topic, rejecting a blank name, a name duplicating one of the same user's existing topics (case-insensitive), or a 21st topic for the same user — each with an inline, field-level error matching the `auth` module's form-error convention.
- `POST /topics/{id}/delete` removes a topic the requester owns; a request for a topic ID the requester doesn't own behaves identically to a not-found ID (no information leak).
- `PlaceholderController`/`placeholder.html` are deleted; `SecurityConfig` no longer `permitAll()`s `/`.
- Categories and their preset sources are real, DB-seeded rows (`categories`, `sources` tables), not code constants — so a future "customize sources" capability is a data change, not a redeploy.

Verification: `./gradlew test` passes with all new tests; manually creating a topic, seeing it in the list, and deleting it works end-to-end against a locally running instance; a second browser session (or a second test user) cannot see or delete the first user's topic.

### Key Discoveries:

- Preset source lists (previously the roadmap's blocking unknown) are now resolved — verified live via `curl` on 2026-09-13:
  - **World News**: BBC News World (`http://feeds.bbci.co.uk/news/world/rss.xml`), Al Jazeera (`https://www.aljazeera.com/xml/rss/all.xml`), The Guardian World (`https://www.theguardian.com/world/rss`)
  - **Technology**: TechCrunch (`https://techcrunch.com/feed/`), Ars Technica (`https://feeds.arstechnica.com/arstechnica/index`)
  - **Business & Finance**: CNBC Top News (`https://www.cnbc.com/id/100003114/device/rss/rss.html`), MarketWatch Top Stories (`http://feeds.marketwatch.com/marketwatch/topstories/`)
  - **Science**: ScienceDaily (`https://www.sciencedaily.com/rss/all.xml`)
- `AppUserDetails` needs a `UserId` field added — `JpaUserDetailsService.loadUserByUsername` already has the loaded `User` (and thus `user.id()`) in hand; this is a zero-extra-query addition (`auth.adapter.out.security.JpaUserDetailsService:23-27`).
- `V4__timestamptz_for_instant_columns.sql` establishes that `Instant`-backed columns must be `TIMESTAMPTZ`, not `TIMESTAMP` — new tables must use `TIMESTAMPTZ` from the start rather than repeat that migration.
- Every Spring-context test class in this project (`RegistrationServiceTests`, `UserRepositoryAdapterTests`, `AuthFlowIntegrationTests`) uses the identical `@SpringBootTest(properties = "app.async.email.enabled=false") @Import({TestcontainersDatasourceConfig.class, SynchronousAsyncConfig.class})` signature so Spring's test context cache is shared — a differently-annotated test class forces a second Testcontainers container onto the same fixed host-network port and collides (`test-plan.md` §6.2). New test classes for this slice must match this signature exactly, `app.async.email.enabled=false` included even though this slice sends no email — same context, same rule.
- No mocks anywhere in this codebase for internal collaborators — `RegistrationServiceTests` exercises `TopicService`-equivalent logic against the *real* repository/DB via the shared Testcontainers context, not a fake or `@MockBean`. This slice's application-layer tests follow the same convention.
- `UserId` is a plain `record UserId(Long value)` in `auth.domain`, documented as the sanctioned way other modules reference a user ("Other modules reference a user only by this value — never by importing `User` itself") — this plan's web/application layers depend on `auth.domain.UserId` directly for exactly this purpose, not on `auth.domain.User`.

## What We're NOT Doing

- No per-source (only per-category) selection — a user picks one category per topic; picking individual sources within/across categories is deferred to a future roadmap item ("source customization," post-MVP, to be added via `/10x-roadmap` separately — not part of this plan). The creation form's disabled "Customize sources" button signals this is coming.
- No editing a topic's category after creation — fixed at creation for v1, matching this project's existing pattern of deferring non-essential capability (e.g. FR-004's description field, deferred to v2). Deleting and recreating is the only way to change it.
- No admin UI for managing categories/sources — they're DB rows seeded by migration; adding an admin UI is out of scope for this slice.
- No actual source ingestion/ID reads of `sources` rows beyond the topic-creation category picker — the `sources` table exists now so future slices (briefing generation) have real data to query, not because this slice reads individual source rows itself.
- No rate limiting on topic creation (distinct from the per-user cap, which is a simple count check, not a token bucket) — the abuse profile is different from unauthenticated registration (this is an already-authenticated user), and nothing in `test-plan.md`'s risk map calls for it.

## Implementation Approach

Bottom-up, mirroring `auth`'s own phase pattern: persistence/domain first (nothing to build on top of otherwise), then the application service enforcing business rules, then the web layer wiring it to HTTP and retiring the placeholder. Each phase includes its own tests, matching how the most recent `auth` rollout phases were structured (test code lands in the same phase as the production code it verifies, not a separate final "testing phase").

## Critical Implementation Details

**Owner-scoped queries, not fetch-then-check.** `test-plan.md`'s risk #2 ("User A reads or mutates a resource owned by User B") explicitly names this as the risk to guard against once the first per-user resource exists — this slice is that resource. `TopicRepository`'s `findAllByUserId`/`deleteByIdAndUserId` must filter by owner *in the query itself* (e.g. a repository method whose SQL/JPQL includes `WHERE user_id = ?`), not fetch a topic by ID alone and check ownership in application code afterward. `deleteByIdAndUserId` returning zero rows affected for a topic the caller doesn't own must be treated identically to the ID not existing at all — never a distinguishable error.

**Case-insensitive per-user uniqueness via a functional unique index**, not an application-only check-then-insert (which would race under concurrent requests): `CREATE UNIQUE INDEX topics_user_id_lower_name_uk ON topics (user_id, LOWER(name));`. The service still performs an explicit existence check first for a clean validation error message, but the index is the actual correctness guarantee.

## Phase 1: Domain, categories/sources reference data, and topic persistence

### Overview

Establishes the `topic` module's domain types, the DB-seeded category/source reference data, and the `topics` table with owner-scoped persistence.

### Changes Required:

#### 1. `src/main/resources/db/migration/V5__create_categories_and_sources_tables.sql` (new)

**Intent**: Schema for preset categories and their sources, DB-configurable rather than code constants.

**Contract**: `categories` (id `BIGSERIAL PRIMARY KEY`, `name VARCHAR(255) NOT NULL UNIQUE`). `sources` (id `BIGSERIAL PRIMARY KEY`, `category_id BIGINT NOT NULL REFERENCES categories(id)`, `name VARCHAR(255) NOT NULL`, `feed_url VARCHAR(2048) NOT NULL`).

#### 2. `src/main/resources/db/migration/V6__seed_categories_and_sources.sql` (new)

**Intent**: Insert the 4 resolved preset categories and their verified sources (see Key Discoveries) as data, not code.

**Contract**: One `INSERT` per category, then one `INSERT` per source referencing its category by name lookup (or by the known sequential ID, whichever is more robust against reordering — prefer a `(SELECT id FROM categories WHERE name = '...')` subquery per source row so insert order doesn't matter).

#### 3. `src/main/resources/db/migration/V7__create_topics_table.sql` (new)

**Intent**: The `topics` table itself, owner-scoped and category-referencing.

**Contract**: id `BIGSERIAL PRIMARY KEY`, `user_id BIGINT NOT NULL REFERENCES users(id)`, `name VARCHAR(255) NOT NULL`, `category_id BIGINT NOT NULL REFERENCES categories(id)`, `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`. Plus the functional unique index from Critical Implementation Details.

#### 4. `src/main/java/pl/tul/deltabrief/topic/domain/CategoryId.java`, `Category.java`, `SourceId.java`, `Source.java` (new)

**Intent**: Plain domain types for the reference data, mirroring `UserId`/`User`'s style (records for IDs, plain classes for anything with fields beyond an ID).

**Contract**: `CategoryId(Long value)`, `SourceId(Long value)` as records. `Category` holds `id`, `name`. `Source` holds `id`, `categoryId`, `name`, `feedUrl`. No behavior beyond field access — these are reference data, not aggregates with invariants.

#### 5. `src/main/java/pl/tul/deltabrief/topic/domain/TopicId.java`, `Topic.java` (new)

**Intent**: The `Topic` aggregate itself.

**Contract**: `TopicId(Long value)` record. `Topic` holds `id`, `userId` (`auth.domain.UserId`), `name`, `categoryId`, `createdAt`; a static factory `Topic.create(UserId userId, String name, CategoryId categoryId, Instant createdAt)` mirroring `User.register(...)`'s style (id/version null until persisted), plus `assignId`/accessor methods mirroring `User`'s pattern exactly.

#### 6. `src/main/java/pl/tul/deltabrief/topic/application/port/out/CategoryRepository.java`, `TopicRepository.java` (new)

**Intent**: Ports the application layer depends on, kept free of JPA imports (mirrors `auth.application.port.out.UserRepository`).

**Contract**: `CategoryRepository`: `List<Category> findAll()`, `boolean existsById(CategoryId id)`. `TopicRepository`: `Topic save(Topic topic)`, `List<Topic> findAllByUserId(UserId userId)`, `boolean existsByUserIdAndNameIgnoreCase(UserId userId, String name)`, `long countByUserId(UserId userId)`, `boolean deleteByIdAndUserId(TopicId id, UserId userId)` (returns whether a row was actually deleted, so the service can distinguish "deleted" from "not found/not owned" without a separate existence query).

#### 7. `src/main/java/pl/tul/deltabrief/topic/adapter/out/persistence/*` (new)

**Intent**: JPA entities, MapStruct mappers, Spring Data repositories, and adapters for `Category` and `Topic`, mirroring `UserJpaEntity`/`UserEntityMapper`/`UserJpaRepository`/`UserRepositoryAdapter` exactly (protected no-arg constructor + full constructor on entities, `@Mapper(componentModel = "spring")` with explicit `@Mapping` expressions for domain→entity since `Topic`'s accessors are fluent). `TopicJpaRepository`'s owner-scoped methods (`findAllByUserId`, a `deleteByIdAndUserId` returning the affected row count or using `existsByIdAndUserId` + `deleteById`) implement the Critical Implementation Details constraint directly in the query, not in adapter-layer Java logic. A `SourceJpaEntity` exists for completeness (the table has rows) but no `SourceRepositoryAdapter`/port is needed yet — nothing in this slice reads individual sources (see What We're NOT Doing).

#### 8. `src/test/java/pl/tul/deltabrief/topic/domain/TopicTests.java` (new)

**Intent**: Minimal domain-level proof that `Topic.create(...)` assigns fields correctly — `Topic` has no business-rule behavior beyond construction (uniqueness/cap live in the application service, since they need repository access).

**Contract**: Plain JUnit, no Spring context, mirrors `UserTests.java`'s style.

#### 9. `src/test/java/pl/tul/deltabrief/topic/adapter/out/persistence/TopicRepositoryAdapterTests.java`, `CategoryRepositoryAdapterTests.java` (new)

**Intent**: Prove persistence round-trips and the owner-scoping/uniqueness constraints actually work against real Postgres.

**Contract**: `@SpringBootTest(properties = "app.async.email.enabled=false") @Import({TestcontainersDatasourceConfig.class, SynchronousAsyncConfig.class})`, mirroring `UserRepositoryAdapterTests` exactly. Cover: save-and-find-by-user, `findAllByUserId` excludes another user's topics, the functional unique index rejects a case-variant duplicate name for the same user (expect `DataIntegrityViolationException`, same pattern as `UserRepositoryAdapterTests.rejectsDuplicateEmail`), `deleteByIdAndUserId` returns false for a topic owned by a different user. `CategoryRepositoryAdapterTests` proves the seed migration actually loaded 4 categories with their expected source counts (a regression guard on `V6__seed_categories_and_sources.sql`).

### Success Criteria:

#### Automated Verification:

- `./gradlew test --no-daemon` passes, including all new domain and persistence-adapter tests
- `./gradlew build --no-daemon` passes end-to-end
- Flyway migrations apply cleanly against a fresh local Postgres (`./gradlew bootRun` starts without migration errors)

#### Manual Verification:

- None needed — fully covered by automated persistence tests against real Postgres.

**Implementation Note**: After this phase's automated verification passes, pause for manual confirmation before proceeding to Phase 2.

---

## Phase 2: Application layer

### Overview

`TopicService` enforcing the business rules (per-user name uniqueness, 20-topic cap, owner-scoped delete), plus the `AppUserDetails` extension needed for controllers to know the authenticated user's ID.

### Changes Required:

#### 1. `src/main/java/pl/tul/deltabrief/auth/adapter/out/security/AppUserDetails.java`, `JpaUserDetailsService.java`

**Intent**: Carry the authenticated user's `UserId` alongside the existing fields, so any controller can resolve "who is making this request" without an extra repository lookup.

**Contract**: Add a `UserId userId` field to the `AppUserDetails` record; `JpaUserDetailsService.loadUserByUsername` passes `user.id()` (already in hand) into the constructor. No behavior change to existing login/verification logic.

#### 2. `src/main/java/pl/tul/deltabrief/topic/application/TopicService.java` (new)

**Intent**: The application-layer business rules for creating, listing, and deleting topics.

**Contract**: `createTopic(UserId userId, String name, CategoryId categoryId)` — validates the category exists (`CategoryRepository.existsById`, throwing `CategoryNotFoundException` if not — this can only happen via a tampered form value, not normal UI use, since the picker is DB-populated), checks `existsByUserIdAndNameIgnoreCase` first for a clean error (throwing `DuplicateTopicNameException`), checks `countByUserId < 20` (throwing `TopicLimitReachedException`), then `Topic.create(...)` + `save`. `listTopics(UserId userId)` — delegates to `findAllByUserId`. `deleteTopic(UserId userId, TopicId topicId)` — delegates to `deleteByIdAndUserId`; the boolean result is intentionally not surfaced as an error to the caller (a delete of a nonexistent/not-owned topic is a silent no-op from the controller's perspective — see Phase 3).

#### 3. `src/test/java/pl/tul/deltabrief/topic/application/TopicServiceTests.java` (new)

**Intent**: Proves the business rules against the real repository/DB, mirroring `RegistrationServiceTests`'s convention (no mocks/fakes for `TopicRepository`/`CategoryRepository`).

**Contract**: Same `@SpringBootTest`/`@Import` signature as Phase 1's tests. Cover: successful creation, duplicate-name rejection (case-insensitive), cap rejection (create 20, assert the 21st throws), cross-user isolation (`listTopics` for user A never returns user B's topics, `deleteTopic` for user A against user B's topic ID is a no-op and user B's topic still exists afterward).

### Success Criteria:

#### Automated Verification:

- `./gradlew test --no-daemon` passes, including the new `TopicServiceTests`
- `./gradlew build --no-daemon` passes end-to-end
- Existing `AuthFlowIntegrationTests` still passes unchanged (proves the `AppUserDetails` field addition doesn't disturb login/verification)

#### Manual Verification:

- None needed — fully covered by automated tests against real Postgres.

**Implementation Note**: After this phase's automated verification passes, pause for manual confirmation before proceeding to Phase 3.

---

## Phase 3: Web layer, placeholder retirement, and end-to-end coverage

### Overview

`TopicController` wired to `/`, the create-topic form, delete action, `PlaceholderController` retirement, and the full-flow integration test.

### Changes Required:

#### 1. `src/main/java/pl/tul/deltabrief/config/SecurityConfig.java`

**Intent**: Drop `/` from `permitAll()` now that it requires authentication like every other real page — Spring Security's own unauthenticated-request redirect to `/login` replaces `PlaceholderController`'s manual `HttpServletRequest.getUserPrincipal()` check.

**Contract**: Remove `"/"` from the `requestMatchers(...).permitAll()` list; it falls through to `anyRequest().authenticated()`.

#### 2. `src/main/java/pl/tul/deltabrief/topic/adapter/in/web/TopicController.java` (new)

**Intent**: The four HTTP actions for this slice.

**Contract**: `GET /` — resolves `UserId` from `((AppUserDetails) authentication.getPrincipal()).userId()`, calls `listTopics`, renders `topics` view (empty-state markup handled in the template via an `#lists.isEmpty(...)` check, not a separate route). `GET /topics/new` — renders `topic-form` with a model attribute populated from `CategoryRepository.findAll()` for the picker. `POST /topics` — binds a `CreateTopicRequest` DTO (name, categoryId), calls `createTopic`; catches `DuplicateTopicNameException`/`TopicLimitReachedException`/`CategoryNotFoundException` and rejects the relevant `BindingResult` field (mirroring `RegistrationController`'s `EmailAlreadyRegisteredException` handling), returning to `topic-form` on error or `redirect:/` on success. `POST /topics/{id}/delete` — calls `deleteTopic` and always redirects to `/` regardless of whether a row was actually deleted (no information leak per Critical Implementation Details).

#### 3. `src/main/resources/templates/topics.html`, `topic-form.html` (new)

**Intent**: List/empty-state page and creation form, styled consistently with the existing auth pages (Pico.css, same stylesheet links).

**Contract**: `topics.html` — a topic list (name + category) each with a delete button/form, or an empty-state message + "Create topic" link when empty. `topic-form.html` — name input + category `<select>` populated from the model, a disabled button labeled "Customize sources — coming soon" with explanatory helper text next to the category picker, and the same `BindingResult`-driven inline field-error pattern as `register.html`.

#### 4. Delete `src/main/java/pl/tul/deltabrief/placeholder/PlaceholderController.java`, `src/main/resources/templates/placeholder.html`

**Intent**: Retire the placeholder now that `topic` ships the real home page, per its own doc comment.

#### 5. `src/test/java/pl/tul/deltabrief/topic/TopicFlowIntegrationTests.java` (new)

**Intent**: Full HTTP-level proof of the create → browse → delete flow and cross-user isolation, mirroring `AuthFlowIntegrationTests`'s structure and shared-context signature exactly.

**Contract**: Register + verify two distinct users (reusing `AuthFlowIntegrationTests`'s helper pattern) for the isolation test. Cover: unauthenticated `GET /` redirects to `/login` (replaces the now-deleted `defaultViewRedirectsAnonymousVisitorsToLogin`/`defaultViewShowsPlaceholderForAuthenticatedVisitors` pair — assert the new empty-state and populated-list cases instead); create → appears in `GET /`; duplicate name → inline form error, no redirect; delete → no longer appears in `GET /`; user A's `POST /topics/{userBsTopicId}/delete` redirects normally but user B's topic still exists afterward (proves the query-level owner-scoping from Phase 1, not just that the UI doesn't show a delete button for someone else's topic).

### Success Criteria:

#### Automated Verification:

- `./gradlew test --no-daemon` passes, including the new `TopicFlowIntegrationTests`
- `./gradlew build --no-daemon` passes end-to-end
- GitHub Actions `build-and-test` passes with no `ci-cd.yml` changes

#### Manual Verification:

- Locally: register/log in, land on `/` with the empty state, create a topic in each of the 4 categories to confirm the picker and creation flow work, delete one, confirm it disappears
- After deploying: same flow against the deployed app, confirming the DB-seeded categories/sources actually loaded via the real migration run on Render's Postgres

**Implementation Note**: This is the final phase — no further manual pause needed after its verification passes.

---

## Testing Strategy

### Unit Tests:

- `TopicTests`: minimal factory-field-assignment proof, no Spring context.

### Integration Tests (all against the shared Testcontainers-backed context):

- `TopicRepositoryAdapterTests`, `CategoryRepositoryAdapterTests`: persistence round-trips, owner-scoping, uniqueness constraint, seed-data regression guard.
- `TopicServiceTests`: business rules (uniqueness, cap, cross-user isolation) against the real repository.
- `TopicFlowIntegrationTests`: full HTTP flow, including the first cross-user authorization test in this codebase (test-plan.md risk #2's concern, seeded here rather than deferred entirely to its dedicated rollout phase).

### Manual Testing Steps:

1. Phase 3: full local create/browse/delete flow across all 4 categories.
2. Phase 3 (post-deploy): same flow against the deployed app, confirming the seed migration ran correctly against Render's real Postgres.

## Performance Considerations

The 20-topic-per-user cap is the only guard against unbounded growth; no other performance concerns at this scale (a handful of users, a handful of topics each, four categories of reference data).

## Migration Notes

Three new Flyway migrations (`V5`–`V7`), continuing directly from `V4__timestamptz_for_instant_columns.sql`. No existing data to migrate — this is new schema only.

## References

- PRD: `context/foundation/prd.md` FR-003, FR-005, FR-006
- Roadmap: `context/foundation/roadmap.md` S-02
- Test-plan risk guidance: `context/foundation/test-plan.md` §2 risk #2 (cross-user authorization), §3 Phase 3 (dedicated future rollout phase — this plan seeds a baseline test now, not a substitute for that phase)
- Pattern reference (every layer): `auth` module — `User`/`UserId` (`src/main/java/pl/tul/deltabrief/auth/domain/`), `UserRepositoryAdapter`/`UserEntityMapper`/`UserJpaEntity` (`src/main/java/pl/tul/deltabrief/auth/adapter/out/persistence/`), `RegistrationController` (`src/main/java/pl/tul/deltabrief/auth/adapter/in/web/`), `RegistrationServiceTests`/`UserRepositoryAdapterTests`/`AuthFlowIntegrationTests` (`src/test/java/pl/tul/deltabrief/auth/`)
- Preset sources verified live via `curl`, 2026-09-13 (see Key Discoveries)

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Domain, categories/sources reference data, and topic persistence

#### Automated

- [x] 1.1 `./gradlew test --no-daemon` passes, including all new domain and persistence-adapter tests — 3fbe41c
- [x] 1.2 `./gradlew build --no-daemon` passes end-to-end — 3fbe41c
- [x] 1.3 Flyway migrations apply cleanly against a fresh local Postgres — 3fbe41c

### Phase 2: Application layer

#### Automated

- [x] 2.1 `./gradlew test --no-daemon` passes, including the new `TopicServiceTests`
- [x] 2.2 `./gradlew build --no-daemon` passes end-to-end
- [x] 2.3 Existing `AuthFlowIntegrationTests` still passes unchanged

### Phase 3: Web layer, placeholder retirement, and end-to-end coverage

#### Automated

- [ ] 3.1 `./gradlew test --no-daemon` passes, including the new `TopicFlowIntegrationTests`
- [ ] 3.2 `./gradlew build --no-daemon` passes end-to-end
- [ ] 3.3 GitHub Actions `build-and-test` passes with no `ci-cd.yml` changes

#### Manual

- [ ] 3.4 Local: full create/browse/delete flow across all 4 categories
- [ ] 3.5 Post-deploy: same flow against the deployed app, confirming the seed migration ran correctly
