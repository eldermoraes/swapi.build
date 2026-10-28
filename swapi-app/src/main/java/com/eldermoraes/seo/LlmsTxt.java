package com.eldermoraes.seo;

/**
 * The text of the llmstxt.org file for agents (moved out of SeoRoutes in
 * 2.14.0, so /llms-full.txt starts with the same summary). Since 2.11.0 /docs
 * and /docs/mcp come with their content in the HTML (ServerRendering), and the
 * file still carries the essentials itself (how to connect, the list format,
 * the id trap): an agent that reads only this file gets them in one short
 * request, without parsing a page. Since 2.14.0 its links go to the Markdown
 * copies (the llmstxt.org convention) where a page has one. Only {base} is
 * replaced, with the root of this request; {resource}, {id} and {text} are
 * literal URL parameters.
 */
public final class LlmsTxt {

    private static final String TEXT = """
            # swapi.build

            > Free, read-only Star Wars API (a SWAPI alternative) with a REST JSON interface and a
            > remote MCP server (Streamable HTTP). Data covers the six films of Episodes I-VI:
            > people, films, planets, species, starships and vehicles. No API key, no sign-up.

            - Connect: `claude mcp add --transport http swapi-build {base}/mcp`; any other MCP client:
              Streamable HTTP URL `{base}/mcp`, no auth. Tools: sw_search, sw_get, sw_list, sw_random;
              sw_get takes an optional `expand` (related records in the same call, e.g. ["homeworld"]),
              sw_list and sw_search an optional `fields` (e.g. ["name"] for a compact index).
            - MCP resources: `swapi://people` (index) and `swapi://people/1` (one record), the same for
              films, planets, species, starships and vehicles. MCP prompt: `character_profile` (a name).
            - REST: `{base}/api/{resource}`, `{base}/api/{resource}/{id}`, `{base}/api/{resource}/random`
              and `{base}/api/{resource}?search={text}`; resources: people, films, planets, species,
              starships, vehicles.
            - Lists and searches return a plain JSON array of entities (no count/next/results
              pagination, unlike swapi.dev).
            - Ids are record ids, not episode numbers: `{base}/api/films/1` is A New Hope (Episode IV).
            - Successful GETs return 200 with JSON; an unknown id returns 404.
            - Relations (homeworld, films, characters...) are absolute URLs on the same API.
            - Every page whose content the server draws is also plain Markdown at the same URL plus `.md`
              (the home is `{base}/index.html.md`); everything in one file: `{base}/llms-full.txt`.

            ## Docs

            - [OpenAPI contract]({base}/openapi.json): machine-readable, always matches the deployed version
            - [REST documentation]({base}/docs.md): endpoints with live examples
            - [MCP server guide]({base}/docs/mcp.md): setup for Claude Code, Claude Desktop, OpenAI Codex, GitHub Copilot and IBM Bob
            - [WebMCP guide]({base}/docs/webmcp): experimental browser tools

            ## Examples

            - [Luke Skywalker]({base}/api/people/1)
            - [A New Hope]({base}/api/films/1)
            - [Search people by name]({base}/api/people?search=sky)

            ## Optional

            - [About]({base}/about.md)
            - [Usage stats]({base}/stats), also as JSON at {base}/api/stats
            - [Source code](https://github.com/eldermoraes/swapi.build) (Apache 2.0)
            """;

    private LlmsTxt() {
    }

    /** The whole file, on the root of this request (no trailing slash). */
    public static String text(String base) {
        return TEXT.replace("{base}", base);
    }

    /** The title, the quote and the list of points, without the link sections. */
    public static String summary(String base) {
        String text = text(base);
        return text.substring(0, text.indexOf("\n## ")).strip() + "\n";
    }
}
