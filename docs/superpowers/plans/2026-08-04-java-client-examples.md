# Java Client Examples Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the over-built `examples/quarkus-langchain4j` with two deliberately minimal, single-subject examples: `examples/java/quarkus-rest-client` (a typed REST client against `/api`, no AI) and `examples/java/langchain4j-mcp-client` (LangChain4j consuming `/mcp`).

**Architecture:** Two standalone Maven projects, each two Java files plus `application.properties`. Neither is part of the `swapi-app` build or the Vercel deploy. No tests, no records, no agent config files, no comparison between them — each example teaches one thing.

**Tech Stack:** Quarkus 3.33.3, Java 25. REST example: `quarkus-rest`, `quarkus-rest-client`. MCP example: `quarkus-rest`, `quarkus-langchain4j-ollama`, `quarkus-langchain4j-mcp`, Ollama model `gemma4:31b-cloud`.

**Spec:** `docs/superpowers/specs/2026-08-04-java-client-examples-design.md`

## Global Constraints

- **Everything inside `examples/` is in English** — code, comments, identifiers, README. This plan and the spec stay in Portuguese.
- **Never modify `swapi-app/`, `vercel.json`, `docs/DEPLOY.md` or `CLAUDE.md`.** Outside `examples/`, only `README.md` (links) and `CHANGELOG.md` (one entry) change, plus deleting one stale spec — all in Task 3.
- **No deploy.** Neither example is part of the Vercel container; the deploy still builds from `swapi-app/`.
- **Project creation goes through the Quarkus Agents MCP** (`quarkus_create`) — never `mvn`, `gradle` or the Quarkus CLI.
- **Quarkus 3.33.3 / Java 25** (`maven.compiler.release=25`), matching `swapi-app`.
- **No port configuration.** Both examples run on the Quarkus default `8080`. Do not set `quarkus.http.port`.
- **No tests.** Verification is running the app and calling it with curl. Do not add `@QuarkusTest`, JUnit, Surefire configuration or test-scoped dependencies.
- **Delete these `quarkus_create` artifacts from every generated project:** `AGENTS.md`, `CLAUDE.md`, `.mcp.json`, `src/main/docker/`, `.dockerignore`, the generated `README.md` (replaced by yours), and any generated sample code or test. Keep `mvnw`, `.mvn/`, `.gitignore`, `pom.xml`.
- **No records or DTOs.** Text in, text out; raw JSON passes through as `String`.
- **Branch:** all work on `feature/java-client-examples`, which already exists and is checked out at `d07784c`. Never commit to `main`.

## File Structure

```
examples/java/quarkus-rest-client/
  pom.xml · .gitignore · mvnw · mvnw.cmd · .mvn/
  README.md
  src/main/java/com/eldermoraes/swapi/restclient/
    SwapiClient.java     # @RegisterRestClient — the remote API as a Java interface
    PeopleResource.java  # GET /people/{id} and GET /people?search= — passes through
  src/main/resources/application.properties

examples/java/langchain4j-mcp-client/
  pom.xml · .gitignore · mvnw · mvnw.cmd · .mvn/
  README.md
  src/main/java/com/eldermoraes/swapi/mcpclient/
    Archivist.java       # @RegisterAiService + @SystemMessage + @McpToolBox("swapi")
    AskResource.java     # POST /ask — text/plain in, text/plain out
  src/main/resources/application.properties
```

Two Java files each. If a third file appears in either project, something went wrong.

---

### Task 1: `quarkus-rest-client` — the plain Java way

Built first because it needs no model: if the environment is fine, this example works immediately.

**Files:**
- Create: `examples/java/quarkus-rest-client/` (whole project, via `quarkus_create`)
- Create: `examples/java/quarkus-rest-client/src/main/java/com/eldermoraes/swapi/restclient/SwapiClient.java`
- Create: `examples/java/quarkus-rest-client/src/main/java/com/eldermoraes/swapi/restclient/PeopleResource.java`
- Create: `examples/java/quarkus-rest-client/README.md`
- Modify: `examples/java/quarkus-rest-client/src/main/resources/application.properties`
- Delete: the generated artifacts listed in Global Constraints

**Interfaces:**
- Consumes: nothing.
- Produces: an app on `8080` where `GET /people/{id}` and `GET /people?search=<name>` return swapi.build JSON verbatim.

- [ ] **Step 1: Generate the project**

Call `quarkus_create` with exactly these arguments:

```json
{
  "outputDir": "/Users/eldermoraes/git/eldermoraes/swapi.build/examples/java/quarkus-rest-client",
  "createInCurrentDir": true,
  "groupId": "com.eldermoraes.swapi",
  "artifactId": "quarkus-rest-client-example",
  "buildTool": "maven",
  "quarkusVersion": "3.33.3",
  "extensions": "rest,rest-client",
  "noCode": true,
  "noWrapper": false
}
```

`rest-client` without `-jackson`: the example passes JSON through as `String`, so no mapping layer is needed. `createInCurrentDir: true` because the directory name differs from the artifactId.

- [ ] **Step 2: Strip the generated project down**

```bash
cd /Users/eldermoraes/git/eldermoraes/swapi.build/examples/java/quarkus-rest-client
rm -rf AGENTS.md CLAUDE.md .mcp.json src/main/docker .dockerignore README.md
rm -rf src/test
ls -a
```

Expected remaining: `.gitignore`, `.mvn`, `mvnw`, `mvnw.cmd`, `pom.xml`, `src/main/java`, `src/main/resources`. If `quarkus_create` emitted sample code under `src/main/java`, delete it too — the tree must contain no Java file you did not write.

- [ ] **Step 3: Verify the pom**

```bash
grep -E "maven.compiler.release|quarkus.platform.version" pom.xml
```

Expected `25` and `3.33.3`. If the release is lower, edit it to `25`. Do not add or remove anything else in the pom.

- [ ] **Step 4: Write `application.properties`**

Replace `src/main/resources/application.properties` with exactly:

```properties
# The remote API this example calls. Nothing else to configure: the app runs on
# Quarkus' default port 8080.
quarkus.rest-client.swapi-api.url=https://swapi.build/api

# Point at a locally running swapi.build instead:
# quarkus.rest-client.swapi-api.url=http://localhost:5432/api
```

- [ ] **Step 5: Write the typed client**

`src/main/java/com/eldermoraes/swapi/restclient/SwapiClient.java`:

```java
package com.eldermoraes.swapi.restclient;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * The swapi.build REST API as a Java interface. Quarkus generates the HTTP calls;
 * the base URL comes from application.properties, keyed by configKey.
 *
 * The methods return String because this example hands the JSON straight back.
 * Return a record instead and Quarkus will deserialize into it.
 */
@RegisterRestClient(configKey = "swapi-api")
public interface SwapiClient {

    @GET
    @Path("/people/{id}")
    String person(@PathParam("id") int id);

    @GET
    @Path("/people")
    String searchPeople(@QueryParam("search") String name);
}
```

- [ ] **Step 6: Write the endpoint**

`src/main/java/com/eldermoraes/swapi/restclient/PeopleResource.java`:

```java
package com.eldermoraes.swapi.restclient;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RestClient;

@Path("/people")
@Produces(MediaType.APPLICATION_JSON)
public class PeopleResource {

    @Inject
    @RestClient
    SwapiClient swapi;

    @GET
    @Path("/{id}")
    public String person(@PathParam("id") int id) {
        return swapi.person(id);
    }

    @GET
    public String search(@QueryParam("search") String name) {
        return swapi.searchPeople(name);
    }
}
```

- [ ] **Step 7: Run it and call it**

Start dev mode with the Quarkus Agents MCP (`quarkus_start`, `projectDir` = this project), then:

```bash
curl -s http://localhost:8080/people/1
curl -s 'http://localhost:8080/people?search=Luke'
```

Expected: the first returns Luke Skywalker's record, the second a JSON array containing him. Capture both outputs verbatim — the README quotes them. Then `quarkus_stop`.

If dev mode fails to start, read `quarkus_logs` and report rather than guessing.

- [ ] **Step 8: Write the README**

`examples/java/quarkus-rest-client/README.md`, in English, short, with these sections:

1. **Title and one sentence** — calling the swapi.build REST API from Java with a typed client.
2. **Run** — `./mvnw quarkus:dev`, then the two curl commands, each followed by its captured output (truncate long JSON with `…` and say you truncated it).
3. **How it works** — `SwapiClient` is the remote API declared as an interface; `@RegisterRestClient(configKey = "swapi-api")` binds it to the URL in `application.properties`; Quarkus generates the client at build time. `PeopleResource` injects it with `@RestClient` and passes the JSON through.
4. **Returning objects instead of JSON** — two sentences: change the return type to a record and add `quarkus-rest-client-jackson`.
5. **Pointing at a local server** — the commented property, `http://localhost:5432/api`.
6. **Links** — swapi.build, `https://swapi.build/openapi.json`, the Quarkus REST Client guide.

No tables, no benchmarks, no mention of the other example beyond a one-line "see also".

- [ ] **Step 9: Commit**

```bash
cd /Users/eldermoraes/git/eldermoraes/swapi.build
git add examples/java/quarkus-rest-client
git commit -m "feat(examples): a typed REST client example for swapi.build"
```

---

### Task 2: `langchain4j-mcp-client` — the tools come from the server

**Files:**
- Create: `examples/java/langchain4j-mcp-client/` (whole project, via `quarkus_create`)
- Create: `examples/java/langchain4j-mcp-client/src/main/java/com/eldermoraes/swapi/mcpclient/Archivist.java`
- Create: `examples/java/langchain4j-mcp-client/src/main/java/com/eldermoraes/swapi/mcpclient/AskResource.java`
- Create: `examples/java/langchain4j-mcp-client/README.md`
- Modify: `examples/java/langchain4j-mcp-client/src/main/resources/application.properties`, `pom.xml`
- Delete: the generated artifacts listed in Global Constraints

**Interfaces:**
- Consumes: nothing from Task 1 — the two examples share no code.
- Produces: an app on `8080` where `POST /ask` takes a plain-text question and returns a plain-text answer, with tools discovered from `https://swapi.build/mcp`.

- [ ] **Step 1: Generate the project**

```json
{
  "outputDir": "/Users/eldermoraes/git/eldermoraes/swapi.build/examples/java/langchain4j-mcp-client",
  "createInCurrentDir": true,
  "groupId": "com.eldermoraes.swapi",
  "artifactId": "langchain4j-mcp-client-example",
  "buildTool": "maven",
  "quarkusVersion": "3.33.3",
  "extensions": "rest",
  "noCode": true,
  "noWrapper": false
}
```

Only `rest` here. **Known issue, already diagnosed on the previous attempt:** `quarkus_create` cannot resolve `langchain4j-ollama` or `langchain4j-mcp` by short name, GACT or GACTV in this environment, even though both are registered for 3.33.3. They are added by hand in Step 2. Do not spend time re-diagnosing this; do not fall back to the Quarkus CLI or `mvn`.

- [ ] **Step 2: Add the LangChain4j platform BOM and the two extensions to `pom.xml`**

In `<properties>`, next to the existing `quarkus.platform.*` entries, add:

```xml
        <quarkus.langchain4j.platform.artifact-id>quarkus-langchain4j-bom</quarkus.langchain4j.platform.artifact-id>
        <quarkus.langchain4j.platform.group-id>io.quarkus.platform</quarkus.langchain4j.platform.group-id>
        <quarkus.langchain4j.platform.version>3.33.3</quarkus.langchain4j.platform.version>
```

In `<dependencyManagement><dependencies>`, after the existing `quarkus-bom` import, add:

```xml
            <dependency>
                <groupId>${quarkus.langchain4j.platform.group-id}</groupId>
                <artifactId>${quarkus.langchain4j.platform.artifact-id}</artifactId>
                <version>${quarkus.langchain4j.platform.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
```

In `<dependencies>`, add (no `<version>` — the BOM manages them):

```xml
        <dependency>
            <groupId>io.quarkiverse.langchain4j</groupId>
            <artifactId>quarkus-langchain4j-ollama</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkiverse.langchain4j</groupId>
            <artifactId>quarkus-langchain4j-mcp</artifactId>
        </dependency>
```

XML comments cannot contain a literal `--`; that hard-failed a build on the previous attempt.

- [ ] **Step 3: Strip the generated project down**

```bash
cd /Users/eldermoraes/git/eldermoraes/swapi.build/examples/java/langchain4j-mcp-client
rm -rf AGENTS.md CLAUDE.md .mcp.json src/main/docker .dockerignore README.md
rm -rf src/test
grep -E "maven.compiler.release|quarkus.platform.version" pom.xml
```

Expected `25` and `3.33.3`; fix the release if it is lower. The tree must contain no Java file you did not write.

- [ ] **Step 4: Write `application.properties`**

Replace `src/main/resources/application.properties` with exactly:

```properties
# The MCP client. These two lines are the entire integration: no client code,
# no tool definitions, no JSON schemas. The server describes its own tools.
quarkus.langchain4j.mcp.swapi.transport-type=streamable-http
quarkus.langchain4j.mcp.swapi.url=https://swapi.build/mcp

# Point at a locally running swapi.build instead:
# quarkus.langchain4j.mcp.swapi.url=http://localhost:5432/mcp

# The model. A "-cloud" model runs on Ollama's hosted infrastructure, so it needs
# `ollama signin` and network access. Any model with tool calling works here.
quarkus.langchain4j.ollama.chat-model.model-name=gemma4:31b-cloud
quarkus.langchain4j.ollama.chat-model.temperature=0
quarkus.langchain4j.timeout=120s
```

- [ ] **Step 5: Write the AI service**

`src/main/java/com/eldermoraes/swapi/mcpclient/Archivist.java`:

```java
package com.eldermoraes.swapi.mcpclient;

import io.quarkiverse.langchain4j.RegisterAiService;
import io.quarkiverse.langchain4j.mcp.runtime.McpToolBox;
import dev.langchain4j.service.SystemMessage;

/**
 * Answers questions using tools discovered from the swapi.build MCP server.
 *
 * There are no tools in this project. @McpToolBox names the MCP client declared
 * in application.properties; the server advertises what it can do, and the model
 * picks from that list — chaining calls when it needs to, for example looking up
 * a character and then that character's home planet.
 */
@RegisterAiService
@SystemMessage("""
        You are the Star Wars archivist for swapi.build.

        Answer only with facts returned by the tools you call, and call as many as
        you need. If the tools do not have the answer, say you do not know.
        Never invent Star Wars data. Answer in at most two sentences.
        """)
public interface Archivist {

    @McpToolBox("swapi")
    String ask(String question);
}
```

A single unannotated `String` parameter is taken as the user message, so no `@UserMessage` is needed. If the build rejects that, add `@UserMessage("{question}")` on the method and note it in your report.

- [ ] **Step 6: Write the endpoint**

`src/main/java/com/eldermoraes/swapi/mcpclient/AskResource.java`:

```java
package com.eldermoraes.swapi.mcpclient;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/ask")
public class AskResource {

    @Inject
    Archivist archivist;

    @POST
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.TEXT_PLAIN)
    public String ask(String question) {
        return archivist.ask(question);
    }
}
```

- [ ] **Step 7: Run it and ask it something**

Start dev mode with `quarkus_start`, then:

```bash
curl -s -X POST http://localhost:8080/ask \
  -H 'Content-Type: text/plain' \
  -d 'Which planet is Luke Skywalker from, and what is its climate?'
```

Expected: a sentence naming Tatooine and describing its climate as arid. Capture the answer verbatim for the README.

Then confirm from `quarkus_logs` **how many tool calls the model made** — the question needs two (find the character, then the planet). If the log does not show tool calls, restart dev mode with `-Dquarkus.langchain4j.ollama.log-requests=true -Dquarkus.langchain4j.ollama.log-responses=true` passed as `extraArgs` rather than editing `application.properties`.

If the model answers without calling tools, or says it does not know, report it with the log. Do not tune the prompt to force it — that decision is the human's.

Then `quarkus_stop`.

- [ ] **Step 8: Write the README**

`examples/java/langchain4j-mcp-client/README.md`, in English, short, with these sections:

1. **Title and one sentence** — asking swapi.build questions in natural language, with the tools coming from its MCP server.
2. **Prerequisites** — Java 25; Ollama with `gemma4:31b-cloud` (a `-cloud` model runs on Ollama's hosted infrastructure and needs `ollama signin` plus network); a model without tool calling cannot run this.
3. **Run** — `./mvnw quarkus:dev`, then the curl, followed by the captured answer, labelled as one run's output and not something to expect verbatim (the model is not deterministic even at `temperature=0`).
4. **How it works** — the two properties are the whole integration; `@McpToolBox("swapi")` hands the server's advertised tools to the model; the example question needs two chained calls; the client connects the first time its bean is used, not at startup.
5. **Switching models** — one property; any tool-calling model works.
6. **Pointing at a local server** — the commented property, `http://localhost:5432/mcp`.
7. **What is not here** — no tool definitions, no schemas, no client code; that is the point.
8. **Links** — swapi.build, `https://swapi.build/docs/mcp`, the Quarkus LangChain4j MCP guide.

No tables, no token counts, no comparison with the REST example beyond a one-line "see also".

- [ ] **Step 9: Commit**

```bash
cd /Users/eldermoraes/git/eldermoraes/swapi.build
git add examples/java/langchain4j-mcp-client
git commit -m "feat(examples): an MCP client example built with LangChain4j"
```

---

### Task 3: Retire the old example and wire the docs

**Files:**
- Delete: `examples/quarkus-langchain4j/` (whole directory, including untracked leftovers on disk)
- Delete: `docs/superpowers/specs/2026-08-03-mcp-client-example-design.md`
- Delete: `docs/superpowers/plans/2026-08-03-mcp-client-example.md`
- Modify: `README.md` (repo root), `CHANGELOG.md`

**Interfaces:**
- Consumes: both examples from Tasks 1-2, and the verbatim outputs captured there.
- Produces: a repo where the only client examples are the two new ones, both linked and changelogged.

- [ ] **Step 1: Delete the old example**

```bash
cd /Users/eldermoraes/git/eldermoraes/swapi.build
git rm -r --cached examples/quarkus-langchain4j 2>/dev/null || true
rm -rf examples/quarkus-langchain4j
ls examples/
```

Expected: `examples/` contains only `java`. Nothing tracked is lost — the directory's files are not in this branch's HEAD (they lived on the closed PR #5 branch); what remains on disk are ignored build outputs and empty directories.

- [ ] **Step 2: Delete the superseded spec and plan**

```bash
git rm docs/superpowers/specs/2026-08-03-mcp-client-example-design.md
git rm docs/superpowers/plans/2026-08-03-mcp-client-example.md
```

The spec describes an abandoned design and states a conclusion that is now false (that a custom `McpTransport` is required). The plan implements that abandoned design. Both stay in git history.

- [ ] **Step 3: Link both examples from the root README**

In `README.md`, at the end of the "MCP Server" section (after the client-setup `<details>` blocks, before `## Project Structure`), add:

```markdown
### Client examples

- [`examples/java/langchain4j-mcp-client`](examples/java/langchain4j-mcp-client) — ask
  questions in natural language; the tools come from the MCP server above, so the example
  defines none.
- [`examples/java/quarkus-rest-client`](examples/java/quarkus-rest-client) — call the REST
  API from Java with a typed client.
```

- [ ] **Step 4: Add the changelog entry**

In `CHANGELOG.md`, under `## [Unreleased]` → `### Added`, append as the last bullet:

```markdown
- Two client examples under `examples/java/`: `langchain4j-mcp-client`, which answers
  natural-language questions with tools discovered from the MCP server — two properties and
  one annotation, no tool code — and `quarkus-rest-client`, which calls the REST API from
  Java with a typed client. Each is two Java files, has one subject, and is deliberately
  free of tests and agent configuration; neither is part of the deployed container, which
  still builds from `swapi-app/`.
```

- [ ] **Step 5: Verify nothing else moved**

```bash
git diff --name-only main HEAD -- ':!examples/'
cd swapi-app && ./mvnw test 2>&1 | grep -E "Tests run:.*Failures|BUILD" | tail -2
```

Expected: the file list is exactly `CHANGELOG.md`, `README.md`, the two deleted docs, and the new spec and plan. The `swapi-app` suite must be green — 75 tests, including the changelog guard.

- [ ] **Step 6: Confirm both examples still start from a clean tree**

For each project, `quarkus_start` then `quarkus_stop`, confirming a clean boot after the deletions. The REST example must answer its two curls; the MCP example needs no second model run if Task 2 captured it.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "docs(examples): retire the combined example, link and changelog the new ones"
```

---

## Self-Review

**Spec coverage.** Layout `examples/java/{langchain4j-mcp-client,quarkus-rest-client}` → Tasks 1-2. Two Java files each → File Structure and the code steps. MCP example contents and the two properties → Task 2 Steps 4-6. REST example contents and its property → Task 1 Steps 4-6. Text in / text out → Task 2 Step 6 (`text/plain` both ways, `String` parameter). No records → no DTO appears in any step. Port 8080 with no configuration → Global Constraints; no `quarkus.http.port` in either properties file. Deliberately absent (tests, `.mcp.json`, `AGENTS.md`, `CLAUDE.md`, Dockerfiles, CI) → Global Constraints plus the explicit `rm` in Task 1 Step 2 and Task 2 Step 3. Verification by running instead of testing → Task 1 Step 7, Task 2 Step 7, Task 3 Step 6. README per example → Task 1 Step 8, Task 2 Step 8. Root README link and changelog entry → Task 3 Steps 3-4. Old example, spec and plan deleted → Task 3 Steps 1-2.

**No placeholders:** every code step carries its code; the two README steps carry their section lists rather than "write a README".

**Consistency:** package `com.eldermoraes.swapi.restclient` (Task 1) matches its two files; `com.eldermoraes.swapi.mcpclient` (Task 2) matches its two. `configKey = "swapi-api"` matches `quarkus.rest-client.swapi-api.url`. `@McpToolBox("swapi")` matches `quarkus.langchain4j.mcp.swapi.*`. `SwapiClient.person`/`searchPeople` are the only methods `PeopleResource` calls. Neither example imports from the other.

**Deliberate omission carried from the spec:** with no tests, nothing detects a break in either example after an API change or an extension upgrade. Accepted; recorded in the spec.
