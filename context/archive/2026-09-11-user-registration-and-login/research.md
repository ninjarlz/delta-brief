---
date: 2026-09-11T19:54:09+02:00
researcher: ninjarlz
git_commit: 1779f1585f332a580a1bd0f761228eedf5642f88
branch: main
repository: ninjarlz/delta-brief
topic: "How to implement user registration and login (S-01) — email/password, session-based"
tags: [research, codebase, auth, spring-security, ddd, flyway]
status: complete
last_updated: 2026-09-11
last_updated_by: ninjarlz
last_updated_note: "Added follow-up research for OAuth2 social login (Google + Facebook) feasibility"
---

# Research: How to implement user registration and login (S-01)

**Date**: 2026-09-11T19:54:09+02:00
**Researcher**: ninjarlz
**Git Commit**: 1779f1585f332a580a1bd0f761228eedf5642f88
**Branch**: main
**Repository**: ninjarlz/delta-brief

## Research Question

How should DeltaBrief implement S-01 (user registration and login) — email/password only for this slice (OAuth explicitly deferred/out of scope), session-based per the project's settled architecture — given the existing codebase, prior F-01 decisions, and current (Spring Boot 4.1.1 / Spring Security 7.1.1) best practices?

## Summary

`auth` will be this codebase's **first real bounded-context module** — nothing under `pl.tul.deltabrief` beyond `config` and the temporary `placeholder` package exists yet. The architecture is already settled (not up for debate in planning): Spring Security session-based form login, BCrypt-family password hashing via `PasswordEncoderFactories.createDelegatingPasswordEncoder()`, a JPA-backed `UserDetailsService`, no JWT/OAuth2/REST API. Two Gradle dependencies are missing and should be added: `spring-boot-starter-validation` (for `@Valid` registration-form validation) and, only if template-level role-conditional rendering is needed later, `thymeleaf-extras-springsecurity6` (CSRF token injection into Thymeleaf forms needs **neither** — it already works via `spring-boot-starter-thymeleaf` + `spring-boot-starter-security` alone). The existing `SecurityConfig.java` needs `formLogin`/`logout` wiring plus `/register` and `/login` added to the public matcher list. The `users` table will be the first real Flyway migration (`V1__*.sql`) and must use `BIGSERIAL`/`IDENTITY`, matching F-01's already-made key-strategy decision — not UUID. A new auth integration test should reuse the existing `TestcontainersDatasourceConfig` test-support class rather than reintroducing `@Testcontainers`/`@Container`/`@ServiceConnection`, and must be aware of an unresolved, undocumented-in-code caveat: its fixed local port assumes exactly one Spring test context.

## Detailed Findings

### Current codebase state — nothing to build on yet

- `pl.tul.deltabrief` has exactly three classes today: the `@SpringBootApplication` entrypoint, `config/SecurityConfig.java` ([SecurityConfig.java](https://github.com/ninjarlz/delta-brief/blob/1779f1585f332a580a1bd0f761228eedf5642f88/src/main/java/pl/tul/deltabrief/config/SecurityConfig.java)), and the temporary `placeholder/PlaceholderController.java` (deleted once `topic` ships a real home page). No `auth`, `user`, `topic`, `briefing`, `delivery`, `feedback`, or `shared` package exists anywhere under `src/main` or `src/test` — confirmed via directory search, zero matches.
- `src/main/resources/templates/` has exactly one file, `placeholder.html` ([placeholder.html](https://github.com/ninjarlz/delta-brief/blob/1779f1585f332a580a1bd0f761228eedf5642f88/src/main/resources/templates/placeholder.html)) — plain HTML5, no `xmlns:th` Thymeleaf namespace declared, no CSS framework, no HTMX script tag, no shared layout/fragment convention. A new `auth` module's login/register templates establish these conventions from scratch; there is no existing form-based template to mirror.
- `src/main/resources/db/migration/` contains only `.gitkeep` — no Flyway migrations exist yet.
- `src/test/java/pl/tul/deltabrief/` has two files: `DeltaBriefApplicationTests.java` (Spring context-load smoke test) and `config/TestcontainersDatasourceConfig.java` (test-support Postgres wiring) — see "Test infrastructure to reuse" below.

### `SecurityConfig.java` — exact current state and required change

Current content ([SecurityConfig.java:14-24](https://github.com/ninjarlz/delta-brief/blob/1779f1585f332a580a1bd0f761228eedf5642f88/src/main/java/pl/tul/deltabrief/config/SecurityConfig.java#L14-L24)):

```java
@Configuration
public class SecurityConfig {

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http.authorizeHttpRequests(authorize -> authorize
				.requestMatchers("/", "/actuator/health").permitAll()
				.anyRequest().authenticated());
		return http.build();
	}
}
```

The class Javadoc explicitly documents the current secure-by-default posture as intentional — "even though no login flow exists yet" — meaning this is the exact seam S-01 fills in. Minimal required change: add `/register` and `/login` to the `permitAll()` matcher list, and add `.formLogin(...)`/`.logout(...)` blocks. `requestMatchers(String...)` matches by path regardless of HTTP method, so one `/register` entry covers both the GET (show form) and POST (submit form) cases.

### Spring Security version and current idiomatic patterns

Spring Boot 4.1.1's BOM pins **Spring Security 7.1.1** (verified against `spring-boot-dependencies-4.1.1.pom` on Maven Central) — `docs.spring.io/spring-security/reference` (currently versioned 7.1.x) is the correct doc set.

**UserDetailsService** — a custom class implementing `UserDetailsService`, wrapping the JPA `User`/`AppUser` entity via `org.springframework.security.core.userdetails.User.withUsername(...)`:

```java
@Service
public class JpaUserDetailsService implements UserDetailsService {

	private final UserRepository userRepository;

	public JpaUserDetailsService(UserRepository userRepository) {
		this.userRepository = userRepository;
	}

	@Override
	public UserDetails loadUserByUsername(String email) {
		AppUser user = userRepository.findByEmail(email)
				.orElseThrow(() -> new UsernameNotFoundException("No user: " + email));
		return org.springframework.security.core.userdetails.User
				.withUsername(user.getEmail())
				.password(user.getPasswordHash())
				.authorities("ROLE_USER")
				.build();
	}
}
```

No `UserDetailsManager` needed (that adds built-in create/update/delete, unnecessary here since registration is a plain application-level save). Registering any `UserDetailsService` bean also disables Spring Boot's `UserDetailsServiceAutoConfiguration` random dev-user/password generation, same as pre-Boot-4 behavior — no Boot 4 quirk found here (unlike `@ServiceConnection`'s documented Boot 4 issue from the F-01 research).

**Password hashing** — `PasswordEncoderFactories.createDelegatingPasswordEncoder()` is the documented current recommendation (not raw `BCryptPasswordEncoder` directly), since it prefixes the stored hash with the algorithm id (`{bcrypt}`), allowing future re-encoding without breaking existing hashes:

```java
@Bean
public PasswordEncoder passwordEncoder() {
	return PasswordEncoderFactories.createDelegatingPasswordEncoder();
}
```

Source: https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html

**Form login + logout wiring** (source: https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/form.html, https://docs.spring.io/spring-security/reference/servlet/authentication/logout.html):

```java
http.authorizeHttpRequests(authorize -> authorize
		.requestMatchers("/", "/actuator/health", "/register", "/login").permitAll()
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

`failureUrl("/login?error")` supplies the query param a Thymeleaf template checks via `th:if="${param.error}"` to show a login-failed message.

**CSRF + Thymeleaf** — confirmed no extra dependency is needed for CSRF token injection into forms. Any `<form th:action="@{/login}" method="post">` automatically gets the hidden `_csrf` input via Spring Security's `CsrfRequestDataValueProcessor` integrating with Thymeleaf's `RequestDataValueProcessor` support — this works purely off `spring-boot-starter-thymeleaf` + `spring-boot-starter-security`, both already present. `thymeleaf-extras-springsecurity6` is a **separate** concern (the `sec:*` dialect, e.g. `sec:authorize="isAuthenticated()"`), not currently in `build.gradle` — add it only if/when templates need conditional rendering by auth state.

**Registration flow** — confirmed there is no Spring Security registration API. It's purely application-level: a `@Controller` accepting `@Valid RegistrationForm` (needs `spring-boot-starter-validation`, **not currently present** in `build.gradle` — must be added), a `@Service` checking email uniqueness (`userRepository.existsByEmail(...)`), encoding the password via the `PasswordEncoder` bean, and saving via `userRepository.save(...)`.

**Boot 4 / Security 7 gotchas** — no form-login-specific regression found. Community migration notes confirm `WebSecurityConfigurerAdapter` is fully removed (this project already uses `SecurityFilterChain`, unaffected) and that CSRF/endpoint-security defaults are enforced more strictly than pre-Boot-4 — worth re-checking `spring-security.version` in the BOM if the Boot patch version is bumped later (it moved 7.0.5 → 7.0.7 → 7.1.0 → 7.1.1 across recent Boot patch releases). Sources: https://docs.spring.io/spring-boot/reference/web/spring-security.html, https://www.herodevs.com/blog-posts/spring-boot-4-0-breaking-changes-migration-guide

### Prior F-01 decisions this slice must follow

**Primary key strategy: `BIGSERIAL`/`IDENTITY`, not UUID.** [plan.md](https://github.com/ninjarlz/delta-brief/blob/1779f1585f332a580a1bd0f761228eedf5642f88/context/archive/2026-09-10-wire-database-connectivity/plan.md) explicitly states: "No UUID primary-key infrastructure — the project uses `BIGSERIAL`/`IDENTITY` columns declared per-table, so no baseline migration or extension setup is needed for key generation" (line 29). The `db/migration` directory was deliberately left empty rather than seeding a baseline/extension migration, "given the `BIGSERIAL`/`IDENTITY` primary-key decision" (line 97). **The `users` table's PK must be `BIGSERIAL`/`IDENTITY`, not a UUID with `pgcrypto`/`uuid-ossp`.**

**Flyway migration location and naming.** `src/main/resources/db/migration/` is Flyway's default `classpath:db/migration` location (plan.md:93-99), "ready for S-01's first real `V1__...sql` migration" (plan.md:96). This slice's migration should be `src/main/resources/db/migration/V1__<description>.sql`.

**Test infrastructure to reuse.** The plan originally specified `@Testcontainers`/`@Container`/`@ServiceConnection` directly on the test class (plan.md:131), but this was abandoned mid-implementation — a VPN on the implementer's machine broke Docker's default bridge networking, and `@ServiceConnection`'s automatic container-type detection doesn't support the `GenericContainer`/host-network mode the fix required ([change.md:14-18](https://github.com/ninjarlz/delta-brief/blob/1779f1585f332a580a1bd0f761228eedf5642f88/context/archive/2026-09-10-wire-database-connectivity/change.md#L14-L18)). What actually exists — and what a new auth integration test should **reuse rather than reinvent** — is [`TestcontainersDatasourceConfig`](https://github.com/ninjarlz/delta-brief/blob/1779f1585f332a580a1bd0f761228eedf5642f88/src/test/java/pl/tul/deltabrief/config/TestcontainersDatasourceConfig.java), a `@TestConfiguration` with manually-managed `HikariDataSource` beans gated by a `CI`/`!CI` Spring profile set in `build.gradle`'s `test` task ([build.gradle:70-84](https://github.com/ninjarlz/delta-brief/blob/1779f1585f332a580a1bd0f761228eedf5642f88/build.gradle#L70-L84)):
- **CI**: standard `PostgreSQLContainer`, bridge networking, dynamic port, Ryuk enabled.
- **Local**: `GenericContainer`, host-network mode, **fixed port 32785**, Ryuk disabled, a self-healing `removeStaleLocalContainer()` step (added during `/10x-impl-review` triage, F3) that force-removes any stale container from a prior hard-killed JVM before starting a new one.

**Known, unresolved caveat (F4, explicitly skipped during review — not fixed in code):** the fixed local port assumes exactly **one** Spring test context exists in the whole test suite. A new auth integration test class should reuse the *same* `@Import(TestcontainersDatasourceConfig.class)` + `@SpringBootTest` context as `DeltaBriefApplicationTests` (same profile set, no extra mocked beans) rather than forcing a distinct Spring context — a second, different context would attempt to rebind the same fixed port while the first is still running, with no guard against this in code. See [impl-review.md](https://github.com/ninjarlz/delta-brief/blob/1779f1585f332a580a1bd0f761228eedf5642f88/context/archive/2026-09-10-wire-database-connectivity/reviews/impl-review.md) findings F3/F4 for the full detail.

**Local dev Postgres connection details** (for manually verifying the new migration): host `localhost`, **port 5433** (not 5432 — taken by a native system Postgres on the implementer's machine), database/user/password all `deltabrief` ([docker-compose.yml](https://github.com/ninjarlz/delta-brief/blob/1779f1585f332a580a1bd0f761228eedf5642f88/docker-compose.yml), [application.properties](https://github.com/ninjarlz/delta-brief/blob/1779f1585f332a580a1bd0f761228eedf5642f88/src/main/resources/application.properties)). `network_mode: host` is used to route around a local VPN/Docker bridge-networking conflict — a consciously accepted tradeoff that also means the container listens on all host interfaces, not just loopback (impl-review.md F5, skipped as low-severity/dev-only). HikariCP pool is capped at `maximum-pool-size=5` — applies to any datasource, no override needed.

**Logging** — Log4j2 + Lombok are already wired into the project (`spring-boot-starter-log4j2`, `log4j2.xml`, `compileOnly/annotationProcessor 'org.projectlombok:lombok'` in [build.gradle](https://github.com/ninjarlz/delta-brief/blob/1779f1585f332a580a1bd0f761228eedf5642f88/build.gradle)). New auth code should use `@Log4j2` directly rather than introducing a different logging approach.

### DDD module layout for `auth`

Per [AGENTS.md](https://github.com/ninjarlz/delta-brief/blob/1779f1585f332a580a1bd0f761228eedf5642f88/AGENTS.md):
- `pl.tul.deltabrief.auth.domain` — the `User`/`AppUser` aggregate, `UserId` value object.
- `pl.tul.deltabrief.auth.application` — registration/authentication application services.
- `pl.tul.deltabrief.auth.adapter.in.web` — registration/login controllers.
- `pl.tul.deltabrief.auth.adapter.out.persistence` — JPA repository + entity mapping for the `users` table.
- Dependencies point inward only (AGENTS.md:16).
- `SecurityConfig` stays in the sanctioned `config` exception package — it is cross-cutting technical wiring, not part of the `auth` bounded context itself (AGENTS.md:17).
- **Critical rule**: other modules (`topic`, etc.) must reference a user only via `UserId`, never by importing `auth.domain.User`/the aggregate directly (AGENTS.md:34) — this is what S-02 (`topic`) will need to respect once it needs topic ownership.

### PRD / roadmap framing

- FR-001 (must-have): "User can create an account (email + password or OAuth)" — the PRD's own Socratic note explicitly considered and rejected a frictionless no-account demo path: "briefings are personal (topic choices, history), auth-first is the right trade" ([prd.md:56-57](https://github.com/ninjarlz/delta-brief/blob/1779f1585f332a580a1bd0f761228eedf5642f88/context/foundation/prd.md#L56-L57)).
- FR-002 (must-have): "User can log in and log out" (prd.md:58).
- Access Control section (prd.md:114-116): "Login via email + password or OAuth... Flat user model: all users have the same capabilities. No admin panel, no role separation in MVP." — confirms a single `ROLE_USER` authority is sufficient; no role/permission modeling needed for S-01.
- [tech-stack.md](https://github.com/ninjarlz/delta-brief/blob/1779f1585f332a580a1bd0f761228eedf5642f88/context/foundation/tech-stack.md) settled (2026-09-09, before F-01 was even implemented): "Spring Security, session-based form login (BCrypt passwords, user table in Supabase Postgres). The app does **not** emit JWTs and is **not** an OAuth2 authorization server. Optional social login would make the app an OAuth2 *client* of the provider... still session-backed" — OAuth is explicitly optional/deferred, matching this research's scope decision to focus on email/password only.
- [roadmap.md S-01 block](https://github.com/ninjarlz/delta-brief/blob/1779f1585f332a580a1bd0f761228eedf5642f88/context/foundation/roadmap.md#L96-L107): Outcome — "User can create an account (email/password or OAuth) and log in and out." Prerequisites: F-01 (done). No blockers, no unknowns. Risk: sequenced before topics/briefings specifically because the PRD's access-control model makes all downstream data per-user from day one, avoiding retrofitting ownership later. Status: `ready`.

## Code References

- `src/main/java/pl/tul/deltabrief/config/SecurityConfig.java:14-24` — current `SecurityFilterChain` bean; needs `/register`+`/login` permitAll, `.formLogin()`, `.logout()`.
- `build.gradle:37-68` — dependencies block; missing `spring-boot-starter-validation` (needed), `thymeleaf-extras-springsecurity6` (only if `sec:*` tags are needed later).
- `build.gradle:70-84` — `tasks.named('test')`, the CI/local Spring-profile mechanism `TestcontainersDatasourceConfig` relies on.
- `src/main/resources/application.properties:1-18` — local datasource defaults (port 5433), Hikari pool cap, Actuator exposure.
- `src/main/resources/db/migration/.gitkeep` — Flyway's empty migration directory; S-01 adds the first `V1__*.sql` here.
- `src/test/java/pl/tul/deltabrief/config/TestcontainersDatasourceConfig.java:1-129` — reusable Testcontainers test-support pattern (CI/local dual-mode, fixed-port caveat).
- `src/test/java/pl/tul/deltabrief/DeltaBriefApplicationTests.java:1-23` — existing pattern for `@Import(TestcontainersDatasourceConfig.class)` usage.
- `docker-compose.yml:1-20` — local Postgres 17, host-network mode, port 5433.
- `AGENTS.md:15-17,34` — DDD module layering convention and the cross-module `UserId`-only reference rule.
- `context/foundation/prd.md:56-59,114-116` — FR-001/FR-002 and Access Control section.
- `context/foundation/tech-stack.md:33` — settled session-based Spring Security decision, OAuth framed as optional.
- `context/foundation/roadmap.md:96-107` — S-01's roadmap item body.

## Architecture Insights

- **This is a genuinely greenfield module.** No prior auth-adjacent code, no template conventions, no form-handling patterns exist in this codebase yet — S-01 sets the precedent other slices (especially anything needing per-user data ownership) will follow.
- **The architecture question is already answered**, deliberately, before this research: session-based Spring Security form login, no JWT, no public API. Planning should not re-litigate this; it should focus on the concrete `auth` module shape, the `users` table migration, and the registration/login controller+service+template flow.
- **Test infrastructure has a real, load-bearing precedent** (`TestcontainersDatasourceConfig` + CI/local profile split) that must be reused, not reinvented, for any new integration test involving the database — and its one known limitation (single-Spring-context assumption) needs to be respected by construction (reuse the same context) rather than worked around.
- **DDD boundary discipline matters immediately**: since S-02 (`topic`) is next and depends on F-01 → S-01, the `UserId`-only cross-module reference rule (AGENTS.md:34) needs to be followed correctly from the start of `auth`, or S-02 will face a choice between violating the rule or a costly refactor.

## Historical Context (from prior changes)

- [`context/archive/2026-09-10-wire-database-connectivity/plan.md`](https://github.com/ninjarlz/delta-brief/blob/1779f1585f332a580a1bd0f761228eedf5642f88/context/archive/2026-09-10-wire-database-connectivity/plan.md) — F-01's implementation plan: establishes `BIGSERIAL`/`IDENTITY` key strategy, Flyway migration location/naming, HikariCP pool cap rationale (Supavisor pooler connection limits), and documents (via post-implementation addenda) the pivot away from `@ServiceConnection` to the custom `TestcontainersDatasourceConfig` pattern.
- [`context/archive/2026-09-10-wire-database-connectivity/change.md`](https://github.com/ninjarlz/delta-brief/blob/1779f1585f332a580a1bd0f761228eedf5642f88/context/archive/2026-09-10-wire-database-connectivity/change.md) — Notes section documenting the local VPN/Docker networking deviation and its consequences for local test setup.
- [`context/archive/2026-09-10-wire-database-connectivity/reviews/impl-review.md`](https://github.com/ninjarlz/delta-brief/blob/1779f1585f332a580a1bd0f761228eedf5642f88/context/archive/2026-09-10-wire-database-connectivity/reviews/impl-review.md) — implementation review findings F1-F5; F3 (fixed, self-healing container cleanup) and F4 (skipped, single-context port assumption) are directly relevant to any new auth integration test.

## Related Research

None yet — this is the first research document for this project beyond F-01's plan (F-01 itself skipped `/10x-research`/`/10x-frame` per the project's lesson-boundary convention, per `context/changes/wire-database-connectivity/` — now archived).

## Open Questions

1. **Registration form fields beyond email/password** — the PRD doesn't specify a display name, terms-of-service acceptance, or email verification step. FR-001 says only "email + password or OAuth." Needs a planning-time decision: minimal (email + password + confirm-password) vs. richer (add display name now to avoid a later migration).
2. **Email verification** — not mentioned anywhere in the PRD or NFRs. Given FR-012 (email delivery of briefings) is must-have later, an unverified email could bounce silently. Worth a planning-time call: skip verification for MVP (accept the risk) or add a minimal verification-link flow now.
3. **"Remember me" / session duration** — not specified by the PRD or tech-stack.md. Spring Security supports `.rememberMe()` easily if wanted; needs an explicit MVP-scope decision (default session timeout is likely fine, but worth confirming against NFR "readable in under 3 minutes" — no direct relation, this is a UX question for return visits).
4. **Unique-email constraint enforcement** — should uniqueness be enforced only at the application layer (`existsByEmail` check before save) or also as a DB-level unique constraint in the `V1__*.sql` migration (recommended, to close the race-condition window)? Not addressed by prior decisions; a planning-time detail.
5. **OAuth account-linking policy** (see Follow-up Research below) — should Google/Facebook login auto-link to an existing email/password account by verified email, or always require explicit confirmation? Needs a planning-time decision before the `V1__*.sql` migration is finalized, since it affects the schema (nullable `password_hash`, a `user_oauth_connections` table).
6. **Facebook `email` permission review status** — flagged as uncertain by follow-up research (conflicting sources on whether Meta's App Review is required for the `email` permission specifically). Needs a direct, current check against `developers.facebook.com/docs/permissions/` before committing to a Facebook-login timeline.

## Follow-up Research 2026-09-11T20:05:10+02:00

**Question**: OAuth2 integration — would it be easy to integrate Google and Facebook login? (Follow-up to the "Open Questions" scope note above; this was previously out of scope for the initial research pass per an explicit MVP-scope decision to focus on email/password first.)

### Spring Security OAuth2 Client — code-side integration

Researched via Context7 (Spring Security 7.0 reference docs, `/websites/spring_io_spring-security_reference_7_0` — good coverage, no WebSearch fallback needed for this part).

**Correction to an initial assumption**: Spring Security's `CommonOAuth2Provider` enum ships built-in presets for **both** Google and Facebook (also GitHub, X, Okta) — "pre-defines a set of default client properties for a number of well known providers" ([Spring Security 7.0 OAuth2 Login Core](https://docs.spring.io/spring-security/reference/7.0/servlet/oauth2/login/core.html)). Facebook does **not** need manual `authorization-uri`/`token-uri`/`user-info-uri` configuration (an older Spring Security limitation that no longer applies) — both providers need only `client-id`/`client-secret`:

```properties
spring.security.oauth2.client.registration.google.client-id=${GOOGLE_CLIENT_ID}
spring.security.oauth2.client.registration.google.client-secret=${GOOGLE_CLIENT_SECRET}
spring.security.oauth2.client.registration.facebook.client-id=${FACEBOOK_CLIENT_ID}
spring.security.oauth2.client.registration.facebook.client-secret=${FACEBOOK_CLIENT_SECRET}
```

**Coexists cleanly with the existing form-login setup** — `.formLogin(...)` and `.oauth2Login(...)` are independent `HttpSecurity` customizers chained on the same `SecurityFilterChain` bean ([advanced OAuth2 login config](https://docs.spring.io/spring-security/reference/7.0/servlet/oauth2/login/advanced.html)):

```java
http
	.authorizeHttpRequests(auth -> auth
		.requestMatchers("/", "/login", "/register").permitAll()
		.anyRequest().authenticated())
	.formLogin(form -> form.loginPage("/login").permitAll())
	.oauth2Login(oauth2 -> oauth2
		.loginPage("/login")
		.userInfoEndpoint(userInfo -> userInfo.userService(customOAuth2UserService)))
	.logout(Customizer.withDefaults());
```

**Google vs. Facebook data shape differs**: Google is OIDC (an `id_token`, `OidcUser`, attributes include `email`, `email_verified`, `name`, `picture` via the UserInfo endpoint). Facebook is plain OAuth2, not OIDC — no `id_token`, just a Graph API `OAuth2User` whose `user-name-attribute` is `id`, and whose attribute map (`id`, `name`, `email`) depends on the `fields` query param Spring's preset requests. Code that reads these attributes must branch on `registrationId`, not assume a uniform shape.

**Provisioning/account-linking pattern**: extend `DefaultOAuth2UserService` (Facebook/non-OIDC) or delegate inside a custom `OidcUserService` (Google) — look up-or-create the local `User` inside that service before returning the principal. Recommended linking policy: **auto-link by email only when the provider marks it verified** (Google sets `email_verified=true`; Facebook's email is lower-trust since it isn't asserted the same way) — silent auto-linking on an unverified email is an account-takeover vector (an attacker could register an OAuth account using a victim's email at a provider that doesn't verify it). Otherwise require explicit confirmation (e.g. "an account with this email exists — log in with your password, then link Google").

**Schema implication**: keep one `users` table with `password_hash` made **nullable** (OAuth-only users have none), plus a separate `user_oauth_connections` table — `(user_id, provider, provider_user_id, linked_at)` with a unique constraint on `(provider, provider_user_id)` — supporting a user with a password and/or multiple linked providers.

### Google / Facebook developer-platform setup friction

Researched via WebSearch against developers.google.com, support.google.com, developers.facebook.com, and (for one unofficial-but-directionally-useful data point) third-party blogs — flagged explicitly below where a claim isn't from an official source.

**Google**: Create a Cloud Console project → configure the OAuth consent screen (External audience, basic scopes) → create a Web-application OAuth Client ID → register the redirect URI (`{baseUrl}/login/oauth2/code/google`). Basic/non-sensitive scopes (`email`, `profile`, `openid`) **do not require Google's app-verification review** — verification is only triggered by sensitive/restricted scopes (Gmail, Drive, Calendar content, etc.) ([support.google.com/cloud/answer/13464323](https://support.google.com/cloud/answer/13464323?hl=en)). "Testing" publishing status caps the app at 100 test users with 7-day token expiry; flipping to "In production" removes this cap and needs no formal review for basic scopes ([support.google.com/cloud/answer/15549945](https://support.google.com/cloud/answer/15549945?hl=en)). **Estimated effort: minutes to a couple of hours.**

**Facebook**: Create a Meta for Developers app → add the Facebook Login product → set Valid OAuth Redirect URIs → set App Domains and a Privacy Policy URL (required to go live). `public_profile` is a default permission granted with no review; the balance of sourced evidence indicates `email` is also usable without App Review for basic login (Meta's own longstanding policy), though **one fetch gave an ambiguous signal on this — flagged as not 100% certain, worth a direct check against [developers.facebook.com/docs/permissions/](https://developers.facebook.com/docs/permissions/) before implementation**. Reaching real (non-tester) public users requires switching the app from Development to **Live Mode**, which — even using only default permissions — increasingly nudges toward completing Business Verification in Meta Business Suite. App Review (2–7 business days per Meta's official historical guidance; **~20 days per unofficial 2026 third-party sources, unverified** — https://bundle.social/blog/meta-app-review-20-days) is required only if requesting permissions beyond the two defaults. **Estimated effort: still same-day for a minimal flow, but with more incidental setup friction (privacy policy, business verification nudges) than Google.**

### Verdict

At the Spring Security code level, **Google and Facebook are equally easy** — identical `CommonOAuth2Provider` presets, same `.oauth2Login()` wiring, same account-linking/provisioning service pattern, differing only in attribute-map shape (OIDC vs. plain OAuth2) which a thin per-provider mapping function handles. The real difference is on each provider's own console: **Google has less incidental friction** (no privacy-policy/business-verification nudge to reach "In production" for basic scopes), while **Facebook's path to Live Mode carries more setup overhead** even though its core permissions are also review-free. Recommendation for planning: **implement Google first** (lowest end-to-end friction, broadest user trust), and treat Facebook as a near-free follow-on afterward — the OAuth2-client abstraction, `OAuth2UserService` provisioning, and schema work (`user_oauth_connections`) are shared between both, so adding Facebook once Google works is mostly a config-and-mapping-function exercise, not a redesign. Both remain genuinely optional per `tech-stack.md`'s existing framing — this research doesn't argue for including them in S-01's initial scope, just confirms the cost of adding them (now or as a fast-follow) is low.
