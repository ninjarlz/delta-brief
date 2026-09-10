# First Deployment: DeltaBrief on Render (Placeholder Content)

## Context

DeltaBrief has a settled tech stack (`context/foundation/tech-stack.md`) and a researched deployment decision (`context/foundation/infrastructure.md`: Render + Supabase + GitHub Actions), but nothing has been deployed yet — the repo contains only the bare Spring Boot scaffold (one `@SpringBootApplication` class, no controllers, no Dockerfile, no CI). The goal is to prove the deployment pipeline end-to-end before building real features: a public Render URL serving placeholder content, with `git push` → GitHub Actions → Render auto-deploy wired up. Database (Supabase) integration is explicitly **out of scope** for this pass — zero DB dependency by design, to keep this a true walking skeleton.

**Render tier — revised from Starter to Free during execution.** Starter ($7/mo, always-on) was the original choice, to remove OOM/spin-down risk from this milestone. During Phase 1 validation, `render blueprints validate` surfaced `need_payment_info`: the Render workspace is typed **`team`**, and Render's 2026 pricing restructure added a separate flat **workspace-plan fee** on top of compute cost — Team's "Pro" plan is $25/month flat, independent of and in addition to the $7/month Starter compute tier (a Hobby/Individual workspace has no such fee, only compute cost — confirmed via Render's docs/changelog). Rather than resolve the Team-vs-Hobby workspace question, the decision was made to use Render's **Free** compute tier instead, which carries no `need_payment_info` gate on any workspace type. Trade-off accepted: the free tier spins down after 15 minutes of inactivity (30–50s cold start on the next request) and shares the same 512 MB risk profile our own risk register flagged — both acceptable for a temporary, placeholder-only proof-of-pipeline deployment, not for the eventual production instance.

Verified against the live repo, the installed `render` (v2.26.0) and `gh` (v2.45.0) CLIs, and decompiled Spring AI 2.0.0 autoconfiguration. Key findings baked into this plan:
- `build.gradle` is missing `spring-boot-starter-actuator` and `spring-boot-starter-thymeleaf` (both unrenamed in Boot 4 — safe to add as-is).
- Spring Security is already a dependency — without an explicit permit-list, its default deny-all would block both the placeholder page and Render's health check.
- The Spring Boot Gradle plugin emits **two** jars (`bootJar` + a `-plain` jar) — a wildcard `COPY *.jar` in the Dockerfile would break; the boot jar's name must be pinned.
- Render's `render.yaml` Blueprint can only be applied for the first time via the dashboard (`render blueprints` CLI only has `validate`, confirmed via `--help`); the Deploy Hook URL is dashboard-only too (no CLI/API surface). Everything else is CLI/git-automatable.
- Rolling the GitHub Actions deploy job out in the same PR as everything else would guarantee one red run (the secret and Render service can't exist yet on the very first push to `main`) — the workflow ships in two small phases to avoid this.

## Files to create/modify

1. **`build.gradle`** (modify) — add `spring-boot-starter-thymeleaf`, `spring-boot-starter-actuator`; pin `bootJar { archiveFileName = 'app.jar' }` so the Dockerfile `COPY` is unambiguous.
2. **`src/main/resources/application.properties`** (modify) — add `server.port=${PORT:8080}` (Render injects `PORT`, default 10000; 8080 is the local-dev fallback) and explicit (self-documenting, matches current Boot defaults) `management.endpoints.web.exposure.include=health` / `management.endpoint.health.show-details=never`.
3. **`src/main/java/pl/tul/deltabrief/config/SecurityConfig.java`** (new) — a `SecurityFilterChain` bean permitting `/` and `/actuator/health`, `anyRequest().authenticated()` otherwise. No `.formLogin()`/`.httpBasic()` yet — other paths 403 rather than redirect, which is correct until the `auth` module ships a real login flow. Lives in a new `pl.tul.deltabrief.config` package (cross-cutting *technical* wiring — distinct from `shared`, which per AGENTS.md is for cross-cutting *domain* code).
4. **`src/main/java/pl/tul/deltabrief/placeholder/PlaceholderController.java`** (new) — maps `/` to a `placeholder` view. Flat package, no `domain/application/adapter` sublayers (it's zero business logic, deleted once `topic` ships a real home page) — Javadoc marks it temporary.
5. **`src/main/resources/templates/placeholder.html`** (new) — plain static markup ("DeltaBrief — Coming soon"), no `th:` attributes needed, no external CSS (unearned scope for a one-line page).
6. **`Dockerfile`** (new, repo root) — multi-stage: `eclipse-temurin:21-jdk-jammy` build stage running `./gradlew bootJar --no-daemon` (executable bit already committed, no `chmod` needed), `eclipse-temurin:21-jre-jammy` runtime stage copying only `app.jar`.
7. **`.dockerignore`** (new, repo root) — excludes `.git`, `.gradle`, `.idea`, `build/`, `context/`, `.claude/`, `*.md` from the build context (faster builds; the `COPY` lines are targeted so this isn't a correctness fix, just a speed one).
8. **`render.yaml`** (new, repo root) — one `type: web`, `runtime: docker` service, `plan: free` (revised from `starter` — see Context), `healthCheckPath: /actuator/health`, **`autoDeploy: false`** (GitHub Actions is the sole deploy trigger — prevents Render's native push-deploy from racing/bypassing the CI gate), `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75` (headroom on the 512 MB tier, same RAM ceiling on free as on Starter). No DB/LLM env vars (true zero-dependency), no `previewsEnabled`.
9. **`.github/workflows/ci-cd.yml`** (new, shipped in two phases — see Execution sequence): `pull_request→main` runs build+test only; `push→main` runs build+test then (phase 2) triggers the Render deploy hook only on success.
10. **`AGENTS.md`** (modify, small addition) — one line noting `config` (cross-cutting technical wiring) and `placeholder`-style temporary scaffolding are the two sanctioned exceptions to "every package is a bounded context or the root."

## Execution sequence

### Phase 1 — automated (agent): code, git, PR
1. `git checkout -b feature/render-first-deploy`.
2. Create/modify files #1–8, #10 above (workflow file gets **only** the `pull_request`/`push`→build-test job for now, no `deploy` job).
3. `render blueprints validate ./render.yaml` and `./gradlew build` locally to confirm the Blueprint and the build (with Security/Actuator/Thymeleaf added) both work before pushing.
4. Commit (`git commit --no-verify` — required by this machine's Jira hook, doesn't apply to this repo) and push the branch.
5. `gh pr create --base main`, wait for `build-and-test` to go green (`gh pr checks`), then `gh pr merge --squash`.

### Phase 2 — manual gate (human): Render dashboard, browser-only
6. Log into the Render dashboard (already-authenticated identity, separate from the `ninjarlz` GitHub account — expected). No payment method needed — the free tier has no `need_payment_info` gate on any workspace type.
7. **New → Blueprint**, point at `github.com/ninjarlz/delta-brief` / `main`. If prompted, authorize Render's GitHub App as **`ninjarlz`** specifically, granting access to `delta-brief`.
8. Render detects `render.yaml`, shows the one `delta-brief` service on `free`. No env-var prompts appear. Click **Apply/Create** — Render performs the **initial deploy automatically** as part of Blueprint creation (this happens regardless of `autoDeploy: false`, which only governs later deploys). This produces the first public URL.
9. Once live, service **Settings → Deploy Hook** → copy the URL (no CLI/API path exists for this — confirmed dashboard-only).

### Phase 3 — automated again (agent): wire the deploy hook
10. `gh secret set RENDER_DEPLOY_HOOK_URL -b "<hook-url-from-step-9>" --repo ninjarlz/delta-brief`.
11. New branch, append the `deploy` job to `ci-cd.yml` (runs only on `push` to `main`, `needs: build-and-test`, `curl -fsS -X POST` the hook secret).
12. PR → green check → `gh pr merge --squash`. This merge is the first real end-to-end proof of `auto-deploy-on-merge`: build+test runs, then the hook fires, Render redeploys the same commit.

**Handoff point:** the agent runs Phases 1 and 3; steps 6–9 (Phase 2) need the human's browser — execution pauses there, waiting for the Deploy Hook URL before continuing.

## Verification

- **After Phase 1 merge:** `gh pr checks` shows `build-and-test` green on `main`.
- **After Phase 2 (Blueprint applied):**
  - `curl -i https://<service>.onrender.com/` → `200`, body contains "DeltaBrief" / "Coming soon." **On the free tier, the first request after 15 minutes of inactivity takes 30–50s to wake the instance — that delay is expected, not a failure.**
  - `curl -i https://<service>.onrender.com/actuator/health` → `200`, `{"status":"UP"}` — **not** 302/401/403 (that would mean the security permit-list is wrong).
  - `curl -i https://<service>.onrender.com/some-random-path` → `403` is *expected* (default-deny, no login flow yet) — the one endpoint that should **not** be 200.
  - Render dashboard shows the deploy event green, plan `free`, status `available`.
- **After Phase 3 merge:** `gh run list --workflow ci-cd.yml -L 3` shows both jobs succeeded; Render dashboard shows a second deploy event timestamped right after, attributed to the deploy hook (not manual) — concrete proof of the automated pipeline; re-run the three `curl` checks to confirm the app is still healthy.

## Notes / deliberately deferred

- **No branch protection exists on `main` today** (confirmed via GitHub API) — the PR gate is currently a convention, not enforced. Not blocking for this deploy; worth adding a required-status-check rule for `build-and-test` before a second contributor or an unattended agent starts merging.
- **`render services create` CLI path** could create the service non-interactively but was rejected as primary: it disconnects config from the checked-in `render.yaml` (drift risk) and still can't avoid the one-time GitHub App browser authorization either way.
- Supabase, real auth (login UI), and the DDD feature modules (`auth`/`topic`/`briefing`/`delivery`/`feedback`) are all out of scope here by design — next deployment's concern.

## Status

Plan written and saved. **Execution (Phases 1–3 above) has not started** — this document is the reviewed artifact; run it (or ask the agent to run it) when ready to actually deploy.
