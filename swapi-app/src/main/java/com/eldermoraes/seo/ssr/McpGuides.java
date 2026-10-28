package com.eldermoraes.seo.ssr;

import java.util.List;

/**
 * The client guides and prompts of /docs/mcp (moved from webui/src/pages/mcp.ts
 * in 2.11.0). The endpoint is the one of the request (siteRoot + "/mcp"), never
 * a fixed domain: the CLAUDE.md rule "public base URL is discovered per request".
 */
public final class McpGuides {

    static final List<McpView.Guide> GUIDES = List.of(
            new McpView.Guide("claude-code", "Claude Code", true),
            new McpView.Guide("claude-desktop", "Claude Desktop & claude.ai", false),
            new McpView.Guide("codex", "OpenAI Codex", false),
            new McpView.Guide("copilot", "GitHub Copilot", false),
            new McpView.Guide("ibm-bob", "IBM Bob", false));

    static final List<String> PROMPTS = List.of(
            "Using swapi-build, list every film Luke Skywalker appears in.",
            "Who is Luke Skywalker? Include his homeworld and species.",
            "Pick a random starship from swapi-build and compare its specs with the Millennium Falcon.",
            "Search swapi-build for planets matching \"Tatooine\" and summarize their climate and population.");

    private McpGuides() {
    }

    public static McpView view(String endpoint) {
        return new McpView(endpoint, GUIDES,
                "claude mcp add --transport http swapi-build " + endpoint,
                """
                {
                  "mcpServers": {
                    "swapi-build": {
                      "type": "http",
                      "url": "%s"
                    }
                  }
                }""".formatted(endpoint),
                "codex mcp add swapi-build --url " + endpoint,
                """
                [mcp_servers.swapi-build]
                url = "%s\"""".formatted(endpoint),
                """
                {
                  "servers": {
                    "swapi-build": {
                      "type": "http",
                      "url": "%s"
                    }
                  }
                }""".formatted(endpoint),
                """
                {
                  "mcpServers": {
                    "swapi-build": {
                      "type": "streamable-http",
                      "url": "%s",
                      "disabled": false
                    }
                  }
                }""".formatted(endpoint),
                PROMPTS);
    }
}
