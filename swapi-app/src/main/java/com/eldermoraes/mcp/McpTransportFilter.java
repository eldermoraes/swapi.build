package com.eldermoraes.mcp;

import io.quarkiverse.mcp.server.McpProtocolVersion;
import io.quarkiverse.mcp.server.ResourceManager;
import io.quarkus.runtime.configuration.MemorySize;
import io.quarkus.vertx.http.runtime.BodyConfig;
import io.quarkus.vertx.http.runtime.VertxHttpConfig;
import io.quarkus.vertx.web.RouteFilter;
import io.smallrye.config.SmallRyeConfig;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RequestBody;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;

import java.util.List;
import java.util.Optional;

/**
 * HTTP edges of the MCP endpoint that the extension does not cover, or covers
 * in a way that costs this server memory for nothing.
 *
 * An MCP session lives in one process's heap. Auto-init makes POST -- which is
 * where the tool calls happen -- survive an Mcp-Session-Id the process does not
 * know, by creating a throwaway session. GET and DELETE would still answer 404,
 * and a 404 on a GET is read by the client as "the session died", tearing down
 * the whole connection because of a stream that was optional.
 *
 * This server sends no notifications: the data never changes. So nothing that
 * opens or feeds a server->client stream has anything to deliver, and the
 * answers below do not depend on the session existing. Two requests are
 * answered here because the extension would otherwise keep state per request
 * (2.12.1):
 * - subscriptions/listen (spec 2026-07-28, served by the extension in every
 *   protocol version): the extension opens an SSE stream with no time limit
 *   and no count, so anyone could hold hundreds open. Answered at once with
 *   -32601, the code the extension uses for a method that is not available.
 * - resources/subscribe and resources/unsubscribe with a session (up to
 *   2025-11-25): the extension appends one subscriber per request to a list
 *   per URI that is never cleaned when the session dies, and auto-init gives
 *   every request a new session. Answered without keeping anything, with or
 *   without Mcp-Session-Id, but only when the extension would have handled
 *   them (supported protocol version, Accept); otherwise its 400 stands. The stateless era
 *   goes on to the extension, which already refuses them with -32601 without
 *   keeping anything.
 *
 * The method in the body wins over the Mcp-Method header; the header alone
 * counts only when the body has no method (or is not JSON). Either way a
 * listen is answered here, so neither path opens a stream.
 *
 * The body is decoded here and again by the extension, on the event loop;
 * quarkus.http.limits.max-body-size=256K keeps that bounded.
 *
 * To see the JSON-RPC method this filter reads the body -- the listen in a
 * session comes without the Mcp-Method header -- with a body handler built
 * like the one Quarkus hands the extension's route (same limit, same
 * settings). Vert.x marks the body as read on the routing context, and the
 * body handler on the extension's route passes it on without reading again
 * (McpListenLimitTest.toolCallsStillWorkAfterTheFilterReadTheBody,
 * bodyLimitIsTheSameAsBefore).
 */
public class McpTransportFilter {

    private static final String MCP_PATH = "/mcp";

    private static final String LEGACY_SSE_PATH = "/mcp/sse";
    private static final String LEGACY_MESSAGES_PREFIX = "/mcp/messages/";

    private static final String LISTEN = "subscriptions/listen";
    private static final String SUBSCRIBE = "resources/subscribe";
    private static final String UNSUBSCRIBE = "resources/unsubscribe";
    private static final String SESSION_HEADER = "Mcp-Session-Id";
    private static final String PROTOCOL_HEADER = "MCP-Protocol-Version";
    private static final String META_PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion";

    static final String LISTEN_NOT_OFFERED = "subscriptions/listen is not offered: swapi.build data never"
            + " changes, so this server sends no notifications.";
    static final String BATCH_WITH_LISTEN = "batch with subscriptions/listen is not supported";

    // JSON-RPC error codes, the same the extension uses (JsonRpcErrorCodes).
    private static final int INVALID_REQUEST = -32600;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;
    private static final int RESOURCE_NOT_FOUND = -32002;

    // Legacy 2024-11-05 transport: the SSE stream and the POSTs to
    // /mcp/messages/<id> must land on the same instance, and in this topology
    // they do not. Rejecting explicitly beats doing the handshake and dying silently.
    private static final String LEGACY_GONE = """
            {"error":"The legacy HTTP+SSE transport (spec 2024-11-05) is not \
            supported. Use the Streamable HTTP endpoint at /mcp."}""";

    @Inject
    ResourceManager resources;

    private final Handler<RoutingContext> bodyHandler = quarkusBodyHandler();

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
        } else if (HttpMethod.POST.equals(method)) {
            // Reads the body (or fails with 413 over the limit, as the
            // extension's route did) and then calls next(): answerWithoutState.
            bodyHandler.handle(rc);
        } else {
            rc.next();
        }
    }

    // Right after filter(), on the body it read: the body handler ends by
    // calling next(), so the decision lives in the next filter. Still after
    // CORS (300) and RouteCountingFilter (400): a listen is counted once in
    // the /mcp group, with a time of milliseconds.
    @RouteFilter(249)
    void answerWithoutState(RoutingContext rc) {
        if (!HttpMethod.POST.equals(rc.request().method())
                || !MCP_PATH.equals(withoutTrailingSlash(rc.normalizedPath()))) {
            rc.next();
            return;
        }
        RequestBody body = rc.body();
        Object message = parse(body == null ? null : body.buffer());
        String headerMethod = rc.request().getHeader("Mcp-Method");
        if (message instanceof JsonArray batch) {
            if (batchContainsListen(batch)) {
                end(rc, 400, error(null, INVALID_REQUEST, BATCH_WITH_LISTEN));
                return;
            }
        } else if (message instanceof JsonObject request) {
            Object id = request.getValue("id");
            String method = request.getValue("method") instanceof String s ? s : null;
            if (LISTEN.equals(method) || (method == null && LISTEN.equals(headerMethod))) {
                end(rc, 200, error(id, METHOD_NOT_FOUND, LISTEN_NOT_OFFERED));
                return;
            }
            if ((SUBSCRIBE.equals(method) || UNSUBSCRIBE.equals(method)) && isSessionRequest(rc, request)) {
                end(rc, 200, subscribeAnswer(id, request));
                return;
            }
        } else if (LISTEN.equals(headerMethod)) {
            // Not JSON, but the header says listen: never open a stream.
            end(rc, 200, error(null, METHOD_NOT_FOUND, LISTEN_NOT_OFFERED));
            return;
        }
        rc.next();
    }

    // A resource this server registered (none in 2.12.1) -> empty result, the
    // same the extension sends, without recording a subscriber; anything else
    // -> -32002, as the extension answers a client of the session era.
    private JsonObject subscribeAnswer(Object id, JsonObject request) {
        JsonObject params = request.getValue("params") instanceof JsonObject p ? p : null;
        String uri = params != null && params.getValue("uri") instanceof String s ? s : null;
        if (uri == null) {
            return error(id, INVALID_PARAMS, "Resource URI not defined");
        }
        if (resources.getResource(uri) == null) {
            return error(id, RESOURCE_NOT_FOUND, "Resource not found: " + uri);
        }
        return new JsonObject().put("jsonrpc", "2.0").put("id", id).put("result", new JsonObject());
    }

    // Only the subscribe the extension itself would handle in the session era:
    // a supported (or absent) MCP-Protocol-Version and the Accept the
    // transport requires. With a bad version or Accept the extension answers
    // 400 before it creates any session, and in the stateless era -32601;
    // neither keeps anything nor opens a stream, so those go on to it.
    // Without Mcp-Session-Id the extension does NOT answer 400: auto-init
    // creates a session for the request and would record the subscriber like
    // any other, so that case is answered here too.
    private static boolean isSessionRequest(RoutingContext rc, JsonObject request) {
        if (isStateless(rc, request)) {
            return false;
        }
        String version = rc.request().getHeader(PROTOCOL_HEADER);
        if (version != null && !McpProtocolVersion.SUPPORTED_VERSIONS.contains(version)) {
            return false;
        }
        List<String> accepts = rc.request().headers().getAll("Accept");
        return accepts(accepts, "application/json") && accepts(accepts, "text/event-stream");
    }

    // The extension's check, as it is (StreamableHttpMcpMessageHandler.accepts).
    private static boolean accepts(List<String> accepts, String type) {
        for (String header : accepts) {
            if (header.contains(type)) {
                return true;
            }
        }
        return false;
    }

    // The extension's rule (StreamableHttpMcpMessageHandler.isStatelessRequest):
    // a stateless MCP-Protocol-Version header, or a stateless version in _meta.
    private static boolean isStateless(RoutingContext rc, JsonObject request) {
        String header = rc.request().getHeader(PROTOCOL_HEADER);
        if (header != null && McpProtocolVersion.isStateless(header)) {
            return true;
        }
        JsonObject params = request.getValue("params") instanceof JsonObject p ? p : null;
        JsonObject meta = params != null && params.getValue("_meta") instanceof JsonObject m ? m : null;
        String version = meta != null && meta.getValue(META_PROTOCOL_VERSION) instanceof String s ? s : null;
        return version != null && McpProtocolVersion.isStateless(version);
    }

    private static boolean batchContainsListen(JsonArray batch) {
        for (Object item : batch) {
            if (item instanceof JsonObject o && LISTEN.equals(o.getValue("method"))) {
                return true;
            }
        }
        return false;
    }

    // Parsed with the same decoder the extension uses, so an escaped method
    // name ("subscriptions\/listen") is the same method here and there.
    private static Object parse(Buffer buffer) {
        if (buffer == null || buffer.length() == 0) {
            return null;
        }
        try {
            return Json.decodeValue(buffer);
        } catch (RuntimeException e) {
            return null; // the extension answers the parse error
        }
    }

    private static JsonObject error(Object id, int code, String message) {
        return new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("error", new JsonObject().put("code", code).put("message", message));
    }

    private static void end(RoutingContext rc, int status, JsonObject payload) {
        String session = rc.request().getHeader(SESSION_HEADER);
        if (session != null) {
            rc.response().putHeader(SESSION_HEADER, session);
        }
        rc.response()
                .setStatusCode(status)
                .putHeader("Content-Type", "application/json")
                .end(payload.toBuffer());
    }

    // Built like VertxHttpRecorder.configureAndGetBody builds the handler that
    // Quarkus gives the extension's /mcp route (BodyHandlerBuildItem): the same
    // quarkus.http.limits.max-body-size and quarkus.http.body.* settings.
    private static Handler<RoutingContext> quarkusBodyHandler() {
        VertxHttpConfig http = ConfigProvider.getConfig().unwrap(SmallRyeConfig.class)
                .getConfigMapping(VertxHttpConfig.class);
        Optional<MemorySize> maxBodySize = http.limits().maxBodySize();
        BodyConfig body = http.body();
        BodyHandler handler = BodyHandler.create();
        maxBodySize.ifPresent(size -> handler.setBodyLimit(size.asLongValue()));
        handler.setHandleFileUploads(body.handleFileUploads());
        handler.setUploadsDirectory(body.uploadsDirectory());
        handler.setDeleteUploadedFilesOnEnd(body.deleteUploadedFilesOnEnd());
        handler.setMergeFormAttributes(body.mergeFormAttributes());
        handler.setPreallocateBodyBuffer(body.preallocateBodyBuffer());
        return rc -> {
            if (!rc.request().isEnded()) {
                rc.request().resume();
            }
            handler.handle(rc);
        };
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
