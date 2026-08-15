package com.eldermoraes.mcp;

import io.quarkus.vertx.web.RouteFilter;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.RoutingContext;

/**
 * HTTP edges of the MCP endpoint that the extension does not cover in this
 * topology.
 *
 * The MCP session lives in one instance's heap and Vercel has no session
 * affinity. Auto-init solves POST -- which is where the tool calls happen --
 * by creating a throwaway session when the Mcp-Session-Id is unknown. GET and
 * DELETE would still answer 404, and a 404 on a GET is legitimately read by
 * the client as "the session died", tearing down the whole connection because
 * of a stream that was optional.
 *
 * Since this server emits NO server->client messages (no sampling,
 * elicitation, roots, progress, subscriptions), the responses below are
 * unconditionally correct -- they do not depend on the session existing.
 */
public class McpTransportFilter {

    private static final String MCP_PATH = "/mcp";

    // NEW -- MCP_PATH already exists, from Task 2
    private static final String LEGACY_SSE_PATH = "/mcp/sse";
    private static final String LEGACY_MESSAGES_PREFIX = "/mcp/messages/";

    // Legacy 2024-11-05 transport: the SSE stream and the POSTs to
    // /mcp/messages/<id> must land on the same instance, and in this topology
    // they do not. Rejecting explicitly beats doing the handshake and dying silently.
    private static final String LEGACY_GONE = """
            {"error":"The legacy HTTP+SSE transport (spec 2024-11-05) is not \
            supported. Use the Streamable HTTP endpoint at /mcp."}""";

    // The filter ends the response (405, 204, 404), so it must run AFTER the
    // Quarkus CORS handler, which registers at priority 300 (see
    // VertxHttpProcessor.cors -> FilterBuildItem(handler, 300); order = -1 *
    // priority in VertxHttpRecorder.finalizeRouter, so a higher priority runs
    // earlier). At 400 this filter ran BEFORE CORS and ended the response
    // without Access-Control-Allow-Origin; a browser client saw that as a CORS
    // error instead of a clean 405. Do NOT raise this number back above 300.
    @RouteFilter(250)
    void filter(RoutingContext rc) {
        // startsWith needs the RAW path: /mcp/messages/ (the trailing slash is
        // part of the prefix) must keep matching. The equals below instead uses
        // the path without the trailing slash, to match both /mcp/sse and /mcp/sse/.
        String rawPath = rc.normalizedPath();
        String path = withoutTrailingSlash(rawPath);
        if (LEGACY_SSE_PATH.equals(path) || rawPath.startsWith(LEGACY_MESSAGES_PREFIX)) {
            rc.response()
                    .setStatusCode(404)
                    .putHeader("Content-Type", "application/json")
                    .end(LEGACY_GONE);
            return;
        }
        if (!MCP_PATH.equals(path)) {
            rc.next();
            return;
        }
        HttpMethod method = rc.request().method();
        if (HttpMethod.GET.equals(method)) {
            // 405 is what the 2025-03-26 spec prescribes for "I offer no
            // server->client stream", and every client treats it as "carry on".
            // Allow lists everything this resource actually answers: POST (tool
            // calls) and DELETE (teardown), plus the CORS preflight OPTIONS.
            rc.response().setStatusCode(405).putHeader("Allow", "POST, DELETE").end();
        } else if (HttpMethod.DELETE.equals(method)) {
            // Session teardown: there is no real session to destroy, so always ok.
            rc.response().setStatusCode(204).end();
        } else {
            rc.next();
        }
    }

    // The Vert.x router ignores a trailing slash when matching an exact path;
    // the equals here does not. Without this, a client configured with /mcp/
    // escapes the filter. /mcp/messages/ (prefix) is unaffected: its trailing
    // slash belongs to the prefix compared with startsWith, not to the path
    // normalized here.
    private static String withoutTrailingSlash(String path) {
        return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }
}
