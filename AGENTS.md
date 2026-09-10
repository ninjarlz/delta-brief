# Repository Guidelines

DeltaBrief is a Spring Boot 4 web app (Java 21, Gradle) generating *delta briefings* — what **changed** about a news topic since the last briefing, not what's newest. Only the entrypoint exists so far; auth, generation, and scheduling are specified but unbuilt.

## Hard rules

- **Never let a briefing fabricate facts.** Every claim in generated briefing content must be traceable to an ingested source — hallucination is the product's #1 trust risk (guardrail in `@context/foundation/prd.md`). Any prompt, model call, or output-rendering code must preserve source attribution.
- **Do not modify anything under `context/`.** It is the source of truth for the PRD, the stack decision, and the bootstrap trail. Read it; never overwrite it. Start at `@context/foundation/prd.md`.
- **Keep Spring AI on the 2.0.x line.** Spring AI 2.0 targets Spring Boot 4; 1.1.x targets Boot 3. Mixing them breaks the build. The version is pinned as `springAiVersion` in `@build.gradle`.
- **Never commit the LLM API key.** It is read from the environment as `spring.ai.openai.api-key` — never hard-code it or commit it.
- **Server-rendered + session auth — not an API.** Thymeleaf pages + HTMX fragments, Spring Security session login; no `@RestController`/JWT. See `@context/foundation/tech-stack.md`.

## Project structure

- Modular monolith under `pl.tul.deltabrief`: one package per bounded context — `auth`, `topic`, `briefing` (core domain: delta classification), `delivery`, `feedback`; cross-cutting code in `shared`.
- Each module layers `domain` → `application` → `adapter.in.web` (controllers/HTMX) / `adapter.out.<concern>` (persistence, AI, email); dependencies point inward only.
- Two sanctioned exceptions to "every package is a bounded context": `config` (cross-cutting technical wiring, e.g. `SecurityConfig`) and flat, explicitly-temporary scaffolding like `placeholder` (no sublayers — delete once superseded).
- Entry point (root package, not a module): `@src/main/java/pl/tul/deltabrief/DeltaBriefApplication.java`.
- Runtime config: `src/main/resources/application.properties`.
- Build: `@build.gradle` / `@settings.gradle`. Product requirements live in `context/foundation/`.

## Build, test, and run

- `./gradlew bootRun` — run locally (devtools live-reload is on).
- `./gradlew test` — run the JUnit 5 suite.
- `./gradlew test --tests "pl.tul.deltabrief.<ClassName>"` — run one test class.
- `./gradlew build` — full build plus tests.

Always use the wrapper (`./gradlew`), not a system `gradle`.

## Coding style

- Java 21; tab indentation (match the existing scaffold files).
- New code goes in its module/layer (e.g. `briefing.domain`, `topic.adapter.in.web`), never the root package; modules reference each other by ID only (e.g. `UserId`), never by importing another module's `domain` aggregate.
- No linter or formatter is configured; there is no automated style gate.

## Testing

- Follow the sample test at `@src/test/java/pl/tul/deltabrief/DeltaBriefApplicationTests.java`; name test classes `<Unit>Tests`.

## Configuration & secrets

- LLM access uses the Spring AI OpenAI starter. Swap the model starter in `@build.gradle` to change providers (each provider has its own `spring.ai.<provider>.api-key`).

## Commit & PR

- Trunk-based: `main` always deployable (merges auto-deploy to Render, `@context/foundation/infrastructure.md`); short-lived `feature/<name>` branches, no `develop`.
- Commit with `git commit --no-verify` — a global hook on this machine demands a Jira key that doesn't apply here.
- Repo `github.com/ninjarlz/delta-brief` (public); commit author is repo-scoped to `ninjarlz`, separate from this machine's work git identity — don't overwrite it.

## Roadmap & backlog

- `context/foundation/roadmap.md` is the sequencing source of truth; every F-NN/S-NN has a linked GitHub issue and a card in the public GitHub Project (both referenced in the roadmap doc).
- When starting or finishing work on a roadmap item, update **both** the issue (comment/close) and the Project's `Roadmap Status` field (Ready → In Progress → Done) — keep the external backlog in sync with the roadmap; don't let it drift.
