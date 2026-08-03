# AGENTS.md -- Quarkus Project Instructions

This is a Quarkus application. Follow these rules when working on this project.

## CRITICAL -- Extension-First Rule (NEVER skip this)

**STOP before writing ANY code.** For every feature or capability the user requests:

1. **Search for Quarkus extensions** that provide the capability using `quarkus_searchDocs` and `quarkus_searchTools query='extension'`.
   Do NOT rely on a fixed list of extensions -- always search dynamically, as available extensions change across Quarkus versions and platform BOMs.
2. **Present ALL matching extensions to the user** with a recommended default marked. Wait for the user to choose before proceeding.
   Do NOT silently pick an extension when multiple options exist.
3. **Load skills** with `quarkus_skills` for the chosen extension BEFORE writing any code.

Skipping any of these steps is a violation. NEVER implement a feature by hand-coding HTML, JavaScript, REST endpoints, or other functionality when a Quarkus extension exists for it.

## Required Workflow

1. **Use quarkus_skills BEFORE writing any code or tests** -- it contains extension-specific patterns, testing approaches, and common pitfalls that prevent mistakes. Skills may also list **Available Dev MCP Tools** specific to each extension (e.g. OpenAPI schema retrieval, scheduler job management) -- use these via `quarkus_callTool`. Call this EVERY time you are about to add or modify a feature, not just at project creation. When returning to an existing project, query for the `quarkus-update` skill to check if the Quarkus version is up-to-date.
2. **Use quarkus_searchDocs for Quarkus documentation** -- do NOT use generic documentation tools (Context7, web search). The Quarkus doc search is version-aware and more accurate.
3. **Use quarkus_searchTools to discover Dev MCP tools** on the running app for testing, config changes, and extension management. The tool list is **dynamic** -- it changes when extensions are added or removed. Re-call `quarkus_searchTools` after any extension change to discover newly available tools. Note: some extension-specific tools are also documented in the skills output (see step 1).
4. **Use quarkus_callTool to invoke Dev MCP tools** -- add extensions, update configuration, inspect the running app. Exception: run tests with `./mvnw` (see [Testing](#testing)).
5. **After code changes, trigger a reload** via `quarkus_callTool` with toolName `devui-logstream_forceRestart`. Do NOT restart the app manually.
6. **After pom.xml / build.gradle changes** (adding dependencies or extensions), you MUST do a full `quarkus_stop` + `quarkus_start` cycle. A `forceRestart` only recompiles source files -- it does NOT re-resolve dependencies.

## Rules

- NEVER implement features manually when a Quarkus extension exists -- search for and add the right extension first.
- NEVER silently pick an extension when multiple options exist -- ALWAYS present options to the user and wait for their choice.
- NEVER write code for a feature without first loading its skill via `quarkus_skills`.
- ALWAYS write tests for every feature -- no exceptions.
- ALWAYS keep README.md updated with app description, features, endpoints, and Quarkus guide links.
- ALWAYS summarize after completing work -- when you finish building an app, adding a feature, or completing a task, provide a clear summary of what was done (files created/modified, endpoints added, extensions used, etc.) and suggest logical next steps the user might want to take (e.g. adding security, observability, persistence, testing improvements, deployment).
- Use `@QuarkusTest` for integration tests -- Dev Services auto-starts backing services (databases, messaging, etc.).
- Use `%dev.` and `%test.` profile prefixes for dev/test configuration -- never hardcode connection URLs without a profile prefix.

## Testing

This project uses the Maven wrapper directly, because the suite is split by JUnit tag and
that split is configured in `pom.xml` (surefire `excludedGroups`, cleared by the
`explicit-groups` profile whenever `-Dgroups` is passed). The Dev MCP test runner does not
honour `-Dgroups`, so it cannot run the `live` half.

```bash
./mvnw test                 # offline suite: no network, no model
./mvnw test -Dgroups=live   # live suite: hits swapi.build (MCP server + REST API)
```

- The default suite must stay offline. Anything that needs `swapi.build` or a model goes
  behind `@Tag("live")`.
- Any test resource that overrides `quarkus.langchain4j.mcp.swapi.url` must use
  `restrictToAnnotatedClass = true`, or it retargets the live gate at the stub. See the
  comments in `application.properties` and `ArchivistWiringTest`.
- After fixing test failures, re-run both suites to verify the fix.
- **NEVER run `mvn clean` or `gradle clean` while dev mode is running** -- it deletes `target/test-classes` and breaks the test runner with no automatic recovery.
- If the test runner gets stuck returning "Tests already in progress", do a full `quarkus_stop` + `quarkus_start` cycle to reset the test runner state.

## Error Handling

When something goes wrong (compilation error, deployment failure, runtime exception):

1. Use `quarkus_callTool` with toolName `devui-exceptions_getLastException` to get structured exception details (class, message, stack trace, user code location).
2. Fix the issue based on the exception details.
3. Call `devui-exceptions_clearLastException` to clear the recorded exception.
4. Use `quarkus_logs` only when you need broader log context beyond the exception itself.

**Note:** If the app fails on its very first deploy (before the Dev MCP handler is registered), the exception endpoint won't exist yet -- fall back to `quarkus_logs` in that case. For hot-reload failures (the common case), the endpoint is always available from the prior successful deploy.

## Customizing Skills

**Global customizations** (`~/.quarkus/skills/`) apply to all projects. Use `quarkus_updateSkill`
to create or update global customizations. Ask the user whether to ENHANCE (append to the base)
or OVERRIDE (fully replace the base).

**Project-level skills** (`.agent/skills/`) are standalone files readable by any agent.
Use `quarkus_saveSkill` to materialize the full composed skill into `.agent/skills/`,
then edit the file directly to customize it for the project.
