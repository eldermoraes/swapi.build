package com.eldermoraes.seo.ssr;

import io.quarkus.qute.TemplateData;

import java.util.List;

/**
 * The data of ssr/mcp.html (McpGuides builds it). Every code sample is a String
 * here, never text of the template: no brace of a JSON sample is read as an
 * expression, and the escaping applies to it like to any value.
 */
@TemplateData
public record McpView(String endpoint, List<Guide> guides, String claudeCodeAdd, String claudeCodeJson,
                      String codexAdd, String codexToml, String copilotJson, String bobJson, List<String> prompts) {

    /** A tab of "Connect your client"; the first is the active one. */
    @TemplateData
    public record Guide(String id, String label, boolean first) {
    }
}
