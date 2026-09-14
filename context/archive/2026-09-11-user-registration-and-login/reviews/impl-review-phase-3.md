<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: User Registration and Login Implementation Plan

- **Plan**: context/changes/user-registration-and-login/plan.md
- **Scope**: Phase 3 of 4
- **Date**: 2026-09-11
- **Verdict**: APPROVED
- **Findings**: 0 critical, 1 warning, 2 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | WARNING |
| Scope Discipline | WARNING |
| Safety & Quality | PASS |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

## Findings

### F1 — Mail health indicator fix (and the MockMvc deviation) not reflected as a plan.md addendum

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: context/changes/user-registration-and-login/plan.md (Phase 3 section) vs. src/main/resources/application.properties, src/test/java/pl/tul/deltabrief/auth/AuthFlowIntegrationTest.java
- **Detail**: Two deviations from Phase 3's plan text, both well-explained in the `cc5a7fe` commit message but neither backfilled into plan.md, unlike Phase 2's established addendum precedent: (1) `management.health.mail.enabled=false` was added after manual testing found Actuator's auto-added mail health indicator returning `503 DOWN` on `/actuator/health` due to live SMTP connectivity checks — a real production risk since Render uses this endpoint for routing decisions. (2) `AuthFlowIntegrationTest` uses manually-built `MockMvc` instead of the plan's specified `@AutoConfigureMockMvc`, because that annotation changes the Spring test-context cache key, which would force a second Testcontainers container onto the same fixed host-network port `TestcontainersDatasourceConfig` uses locally — verified sound by both review agents.
- **Fix**: Add a short addendum to Phase 3's plan.md section (mirroring Phase 2's) documenting both discoveries and pointing to `cc5a7fe`'s commit message for full detail.
- **Decision**: FIXED — addendum added to plan.md's Phase 3 section.

### F2 — Integration test doesn't assert unauthenticated state after a failed login

- **Severity**: 🔵 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria
- **Location**: src/test/java/pl/tul/deltabrief/auth/AuthFlowIntegrationTest.java (wrong-password case)
- **Detail**: The test posts a wrong password and asserts the `302 → /login?error` redirect, but doesn't follow up with an assertion that a subsequent authenticated-only request is still rejected (i.e., that the failed attempt didn't somehow leave a session authenticated). Minor test-depth gap, not a known defect — Spring Security's default behavior makes this vanishingly unlikely to be wrong.
- **Fix**: Optional — add a follow-up request in the wrong-password branch asserting a 3xx redirect-to-login for a protected path, for extra confidence.
- **Decision**: FIXED — added a follow-up `GET /some-protected-path` assertion expecting `302 → /login`. This genuinely exercised the MockMvc session-continuity behavior (had to correct the expected redirect URL from an absolute to a relative form after seeing the actual assertion failure) — confirms the wrong-password attempt correctly left no authenticated session behind.

### F3 — Timing-based user-enumeration mitigation not independently live-verified

- **Severity**: 🔵 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/tul/deltabrief/auth/adapter/out/security/JpaUserDetailsService.java
- **Detail**: `UsernameNotFoundException`'s internal message is never exposed to the client — confirmed both review agents independently that Spring Security's `AbstractUserDetailsAuthenticationProvider` (default `hideUserNotFoundExceptions=true`) normalizes unknown-email and wrong-password into the same generic `BadCredentialsException`, and that it runs a dummy password comparison to equalize timing when the user isn't found. This is Spring Security's documented default behavior, not custom code in this project, so it wasn't independently re-verified with a live timing test.
- **Fix**: None needed — this is Spring Security's own documented, unmodified default. Purely informational.
- **Decision**: SKIPPED — documents a verification boundary, not a defect.

## Success Criteria Verification

**Automated** (re-run during this review, 2026-09-11): `./gradlew clean build --no-daemon` → BUILD SUCCESSFUL, all tests passing across 5 classes (11 test methods: `UserRepositoryAdapterTest`, `RegistrationServiceTest`, `AuthFlowIntegrationTest`, `UserTest`, `DeltaBriefApplicationTests`). PR #23's GitHub Actions `build-and-test` passed on both Phase 3 commits (`cc5a7fe`, `bd8dee3`); `ci-cd.yml` confirmed unmodified on this branch.

**Manual** (cross-checked against evidence in this session, not rubber-stamped): 3.4/3.5 were verified via a clean, fully-reported end-to-end HTTP sequence (health check → register → verify with DB confirmation → login correct → login wrong → logout → health check) on a freshly-restarted app instance, including catching and fixing the real `503` health-check regression before it was checked off.

No missing or falsely-checked manual items found.
