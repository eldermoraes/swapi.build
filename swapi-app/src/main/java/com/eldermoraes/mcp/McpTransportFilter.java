package com.eldermoraes.mcp;

import io.quarkiverse.mcp.server.McpConnection;
import io.quarkiverse.mcp.server.McpLog;
import io.quarkiverse.mcp.server.McpProtocolVersion;
import io.quarkiverse.mcp.server.McpServer;
import io.quarkiverse.mcp.server.ResourceManager;
import io.quarkiverse.mcp.server.http.runtime.config.McpHttpServerRuntimeConfig;
import io.quarkiverse.mcp.server.http.runtime.config.McpHttpServersRuntimeConfig;
import io.quarkiverse.mcp.server.runtime.ConnectionManager;
import io.quarkiverse.mcp.server.runtime.McpConnectionBase;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.quarkus.arc.Arc;
import io.quarkus.runtime.configuration.MemorySize;
import io.quarkus.security.AuthenticationException;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.vertx.http.runtime.BodyConfig;
import io.quarkus.vertx.http.runtime.VertxHttpConfig;
import io.quarkus.vertx.web.RouteFilter;
import io.smallrye.config.SmallRyeConfig;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RequestBody;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
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
 * answered here because the extension would otherwise keep state for nothing
 * (2.12.1):
 * - subscriptions/listen (spec 2026-07-28, served by the extension in every
 *   protocol version): the extension (still in 2.1.0) opens an SSE stream
 *   with no time limit and no count, so anyone could hold hundreds open.
 *   Answered at once with -32601, the code the extension uses for a method
 *   that is not available, under HTTP 200 in both eras. For a stateless
 *   request this departs on purpose from the 2026-07-28 spec, which gives a
 *   method the server does not implement HTTP 404 (the extension does so
 *   since 2.1.0, quarkus-mcp-server #1081). 2.16.1 alone followed it for the
 *   listen; 2.17.0 went back to 200 because Claude Code 2.1.331 sent the
 *   listen on connect and reads a 404 on it as a dropped connection, closing
 *   "with errors", where it reads the 200 as "not offered" and closes
 *   cleanly. It sent it because the server advertised resources.listChanged,
 *   which it no longer does since 2.17.2 (SwapiResources); the answer stays
 *   for a client that sends the listen anyway, and for a rollback. A listen
 *   whose protocol version headers are wrong still gets the extension's own
 *   400 (wrongVersionHeaders).
 * - resources/subscribe and resources/unsubscribe with a session (up to
 *   2025-11-25): the extension records a subscriber that will never get a
 *   notification. Since 2.1.0 (#1154) it keeps one entry per connection and
 *   URI and drops a connection's entries when the connection closes, so a
 *   throwaway auto-init session leaves none behind; up to 2.0.x it appended
 *   one per request to a list never cleaned. What is left is a genuine
 *   session's entries, kept for nothing until its idle timeout or DELETE.
 *   Answered without keeping anything, with or
 *   without Mcp-Session-Id: {} for a registered resource (2.13.0+, the records
 *   of SwapiResources), -32002 for any other URI, as the extension answers.
 *   Only when the extension would have handled them (supported protocol
 *   version, Accept); otherwise its 400 stands. The stateless era
 *   goes on to the extension, which refuses them with -32601 (HTTP 404 since
 *   2.1.0) without keeping anything.
 *
 * A JSON array (a JSON-RPC batch) is answered here too, always the same way:
 * 400 with -32600, without looking inside it (issue #70). The extension
 * (2.0.3, unchanged in 2.1.0) refuses an array on its own, but only as a side
 * effect: it casts the decoded body to a JSON object inside a try and answers
 * the failed cast as a parse error (200, -32700), before any session or stream
 * (or, without the Accept the transport requires, a bare 400 before that).
 * That is not a contract. A version that accepted batches would serve a
 * subscriptions/listen inside one with an open stream, so no array goes on to
 * the extension, and
 * McpListenLimitTest.theExtensionItselfStillRefusesABatch fails the day this
 * guard stops being a second lock and becomes the only one.
 *
 * A malformed request the extension cannot handle is answered here too
 * (2.17.1, refuseMalformed and McpRequestShape): with -32602, or a 202 for a
 * body shaped like a response. In the extension (2.1.0, unchanged in its
 * main branch when this was written) such a request got HTTP 500 and a stack
 * trace in the log, and a few never got an answer and left state behind
 * until a restart. McpExtensionShapeGuardTest pins what the extension does
 * with each shape, and fails the day it handles one itself.
 *
 * Some refusals the extension makes cleanly but logs at every request, the
 * client's text unescaped in some of them (2.17.2, #126): an Accept without
 * both types of the transport and a body that is not a JSON object
 * (StreamableHttpMcpMessageHandler.handle), a cursor that does not decode
 * (Messages.getCursor, Cursor.decode) and a notifications/cancelled that
 * matches no request in progress (McpMessageHandler.cancelRequest). The
 * filter writes the extension's answer itself, at the point the extension
 * would, and logs a fixed label once per kind per minute (the cancel, which
 * is not an error, not at all); McpExtensionLogGuardTest compares the two
 * answers and fails the day the extension stops logging one. At every
 * upgrade of the extension, read those four places again. A POST whose body
 * never arrives whole is logged the same way by the failure handler, not by
 * Quarkus's error handler (answerFailure). No malformed request to /mcp
 * makes the server log a line per request; only a defect of the server
 * does.
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
    private static final String META_LOG_LEVEL = "io.modelcontextprotocol/logLevel";

    static final String LISTEN_NOT_OFFERED = "subscriptions/listen is not offered: swapi.build data never"
            + " changes, so this server sends no notifications.";
    static final String BATCH_NOT_SUPPORTED = "JSON-RPC batches are not supported: send one request per POST.";

    // JSON-RPC error codes, the same the extension uses (JsonRpcErrorCodes).
    private static final int INVALID_REQUEST = -32600;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;
    private static final int INTERNAL_ERROR = -32603;
    private static final int PARSE_ERROR = -32700;
    private static final int RESOURCE_NOT_FOUND = -32002;
    // The extension's spelling of the header in its error messages.
    private static final String PROTOCOL_HEADER_AS_THE_EXTENSION_WRITES_IT = "Mcp-Protocol-Version";
    private static final int HEADER_MISMATCH = -32020;
    private static final int UNSUPPORTED_PROTOCOL_VERSION = -32022;

    // Legacy 2024-11-05 transport: the SSE stream and the POSTs to
    // /mcp/messages/<id> need session-affine state, which this server never
    // keeps (a restart drops it). Rejecting explicitly beats doing the
    // handshake and dying silently.
    private static final String LEGACY_GONE = """
            {"error":"The legacy HTTP+SSE transport (spec 2024-11-05) is not \
            supported. Use the Streamable HTTP endpoint at /mcp."}""";

    // Looked up once, when the filter is built (before the first request),
    // not injected, not even as Instance<>: an injection point of a manager
    // makes the extension advertise listChanged (SwapiResources). A
    // @Singleton of the extension, so keeping the reference is right.
    private final ResourceManager resources = Arc.container().instance(ResourceManager.class).get();

    @Inject
    ConnectionManager sessions;

    @Inject
    Vertx vertx;

    @ConfigProperty(name = "swapi.mcp.response-deadline")
    Duration responseDeadline;

    private static final Object NOT_JSON = new Object();
    // The id of the request passed on to the extension, for the answers of
    // the deadline and the failure handler (the body is not decoded again).
    private static final String REQUEST_ID = "swapi.mcp.request-id";

    private final OncePerMinute warnings = new OncePerMinute(Logger.getLogger(McpTransportFilter.class));

    private final Handler<RoutingContext> bodyHandler = quarkusBodyHandler();

    // quarkus.mcp.server.http.streamable.auto-init, read from the mapping the
    // extension reads: whether a request without a session the process holds
    // gets one (extensionReachesTheMethod).
    private final boolean autoInit = autoInit();

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
            // Session teardown: always 204. A genuine initialize does leave a
            // session in this process, and without this it stayed until the
            // idle timeout (issue #46), so the one the request names is freed
            // here. remove() does nothing for an id the process does not hold
            // (unknown, malformed). For one it holds, it takes the entry out
            // of the map, closes the connection (a status flip), cancels its
            // ping timer if any and drops its pending cancellations: all in
            // memory, no I/O, fine on the event loop.
            // The extension's own DELETE (terminateSession, the same in 2.0.3
            // and 2.1.0) is not used on purpose: it answers 200 for a known
            // id, 404 for any other and 405 with a stateless
            // MCP-Protocol-Version, and it logs the id the client sent.
            // Nothing is logged here.
            // ConnectionManager is an internal of the extension:
            // McpDeleteSessionTest fails if an upgrade changes what this does.
            String session = rc.request().getHeader(SESSION_HEADER);
            if (session != null) {
                sessions.remove(session);
            }
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
        if (message instanceof JsonArray) {
            // Every batch, whatever it carries: see the class comment.
            end(rc, 400, error(null, INVALID_REQUEST, BATCH_NOT_SUPPORTED));
            return;
        }
        if (message instanceof JsonObject request) {
            Object id = request.getValue("id");
            String method = request.getValue("method") instanceof String s ? s : null;
            if (LISTEN.equals(method) || (method == null && LISTEN.equals(headerMethod))) {
                JsonObject refused = wrongVersionHeaders(rc, request, id, isStateless(rc, request));
                if (refused != null) {
                    end(rc, 400, refused);
                } else {
                    end(rc, 200, error(id, METHOD_NOT_FOUND, LISTEN_NOT_OFFERED));
                }
                return;
            }
            if ((SUBSCRIBE.equals(method) || UNSUBSCRIBE.equals(method)) && isSessionRequest(rc, request)) {
                end(rc, 200, subscribeAnswer(id, request));
                return;
            }
            if (refuseMalformed(rc, request, id)) {
                return;
            }
        } else if (LISTEN.equals(headerMethod)) {
            // Not JSON, but the header says listen: never open a stream.
            end(rc, 200, error(null, METHOD_NOT_FOUND, LISTEN_NOT_OFFERED));
            return;
        } else if (message == null && acceptsTransport(rc)) {
            // The JSON literal null: the extension dereferences it (HTTP 500).
            // Answered as a request that is not one, -32600 with id null.
            refused(rc, "body null");
            end(rc, hasStatelessHeader(rc) ? 400 : 200,
                    error(null, INVALID_REQUEST, "Invalid request: the body must be a JSON-RPC object"));
            return;
        }
        // 2.17.2 (#126): the two refusals the extension makes before anything
        // else (StreamableHttpMcpMessageHandler.handle, in this order) and
        // logs at every request, the Accept with the header the client sent,
        // the parse with a stack trace. The filter makes them itself, with
        // the extension's answer, and logs them once per kind per minute.
        // The Accept first, as the extension checks it first: a body that is
        // not an object with the wrong Accept still gets the Accept's 400.
        if (!acceptsTransport(rc)) {
            // The extension's ctx.fail(400): the same Quarkus error handler
            // answers it, so the answer is the same (a bare 400).
            refused(rc, "Accept");
            rc.fail(400);
            return;
        }
        if (!(message instanceof JsonObject request)) {
            // Not JSON, or JSON that is not an object (the extension casts
            // it): 200 with -32700 and id null in every era, before any
            // version or session is read, and without echoing Mcp-Session-Id,
            // as the extension answers it.
            refused(rc, "body not a JSON object");
            endWithoutSession(rc, 200, error(null, PARSE_ERROR, "Unable to parse the JSON message"));
            return;
        }
        forward(rc, request.getValue("id"));
    }

    // 2.17.1: the net under the shape checks. A shape nobody has found yet
    // could still make the extension fail where it never answers, and then
    // the connection stayed open until quarkus.http.idle-timeout (30 minutes
    // by default). Every POST passed on to the extension gets a deadline
    // (swapi.mcp.response-deadline, 10 s): one timer, armed here on the
    // request's event loop and cancelled when the response ends (the end
    // handler also runs when the connection closes). No answer of this
    // server takes more than milliseconds and none is a stream (the listen
    // is answered above). A future operation that legitimately takes longer
    // (a task, a long-running tool) needs an explicit exception here, not a
    // larger value.
    //
    // At the deadline: if nothing was written, -32603 with the request's id,
    // under HTTP 200 as the extension sends its own "Internal error"; if the
    // head was already written, the response cannot become an error any
    // more, so it is reset (HTTP/2: the stream; HTTP/1.1: the connection).
    // This frees the connection, not what the extension kept for the request
    // (only the shape checks prevent that). If the extension writes later,
    // the write is dropped without a log line, the extension releases what
    // it kept for the request once the method returns, and the server goes
    // on serving (McpResponseDeadlineTest, with a late tool of the real
    // extension in both eras).
    private void forward(RoutingContext rc, Object id) {
        rc.put(REQUEST_ID, id);
        long timer = vertx.setTimer(responseDeadline.toMillis(), t -> deadlineReached(rc, id));
        rc.addEndHandler(done -> vertx.cancelTimer(timer));
        rc.next();
    }

    private void deadlineReached(RoutingContext rc, Object id) {
        HttpServerResponse response = rc.response();
        if (response.ended() || response.closed()) {
            return;
        }
        warnings.warn("An MCP request got no answer within swapi.mcp.response-deadline");
        if (response.headWritten()) {
            response.reset();
            return;
        }
        end(rc, 200, error(id, INTERNAL_ERROR, "Internal error: no answer within the server's deadline"));
    }

    // 2.17.1: an exception thrown while the extension handles a POST (its
    // route handler casts request fields without checking them) became
    // Quarkus's error page, HTTP 500 with the stack trace in the log. It is
    // answered here as JSON-RPC, -32603 with the request's id under HTTP
    // 200, like the extension's own "Internal error", and logged as one WARN
    // line a minute per exception class, without the trace. Only an
    // exception (status 500): the extension's own refusals through
    // ctx.fail(status) -- 400 for the Accept, 404 for an unknown session
    // when auto-init is off -- and the 413 of the body handler go on to
    // Quarkus as before, and so do the security failures the extension
    // answers in its own failure handler on /mcp. Registered on the router
    // at startup, so it comes before Quarkus's error handler (the last
    // route); the request was counted and passed CORS on the way in.
    void registerFailureHandler(@Observes Router router) {
        router.post(MCP_PATH).failureHandler(this::answerFailure);
    }

    void answerFailure(RoutingContext rc) {
        Throwable failure = rc.failure();
        HttpServerResponse response = rc.response();
        // 2.17.2 (#126): a body that never arrived whole. The body handler of
        // filter() fails the request when the connection closes mid-body or
        // a chunk is malformed (BodyHandlerImpl: context.fail(200, t), before
        // the request has ended; a malformed chunk fails twice, the codec's
        // exception and then the close). Quarkus's error handler logged an
        // ERROR for each, at every request. Ended here: the connection is
        // closed without a byte (the client got none before either) and one
        // fixed line a minute is logged. Left alone, as before: a failure
        // without an exception (the 413 of the body handler, the Accept's
        // 400), a form body that arrived but that the form decoder refuses
        // (HttpPostRequestDecoder's exceptions: the body handler's 400, or
        // the 500 answered below), anything that comes once the body has
        // ended (the extension's 500, below) and the security failures.
        if (failure != null && !rc.request().isEnded()
                && !(failure instanceof HttpPostRequestDecoder.ErrorDataDecoderException)
                && !(failure instanceof HttpPostRequestDecoder.NotEnoughDataDecoderException)
                && !(failure instanceof HttpPostRequestDecoder.EndOfDataDecoderException)
                && !(failure instanceof AuthenticationException)
                && !(failure instanceof UnauthorizedException)
                && !(failure instanceof ForbiddenException)) {
            warnings.warn("An MCP request body did not arrive whole");
            if (!response.closed()) {
                rc.request().connection().close();
            }
            return;
        }
        if (rc.statusCode() != 500 || response.ended() || response.headWritten()
                || failure instanceof AuthenticationException
                || failure instanceof UnauthorizedException
                || failure instanceof ForbiddenException) {
            rc.next();
            return;
        }
        warnings.warn("An MCP request failed in the extension: "
                + (failure == null ? "status 500" : failure.getClass().getName()));
        end(rc, 200, error(rc.get(REQUEST_ID), INTERNAL_ERROR, "Internal error"));
    }

    // 2.17.1: the shapes the extension cannot handle (McpRequestShape) are
    // answered here, so they never reach it. Before, some got HTTP 500 and a
    // stack trace in the log, and a few never got an answer and left state
    // in the extension until a restart.
    //
    // Only requests the extension would get that far with: a request it
    // refuses first, and cleanly, keeps the extension's own answer (written
    // by the filter where the extension would log it at every request: the
    // cursor and the cancel at the end of this method, the Accept and a body
    // that is not an object in answerWithoutState). In its
    // order (StreamableHttpMcpMessageHandler.handle, handleStateless,
    // McpMessageHandler.handle): the Accept of the transport (a bare 400);
    // an unsupported protocol version (400); then it reads params and _meta,
    // so their type is checked here at that point; a stateless request
    // without the MCP-Protocol-Version header or with one that differs from
    // _meta, the Mcp-Method and Mcp-Name headers and the required members of
    // _meta (400); then it decodes what _meta says about the client
    // (metaContent) and, stateless, reads its log level (400); the jsonrpc
    // member and the method (200, -32600 and -32601). So a request with wrong
    // version headers keeps the answer it had.
    //
    // HTTP status, as the extension answers the nearest error it handles
    // itself: 200 in the session era; in the stateless era 400 for params
    // and _meta (it answers 400 for a _meta it refuses) and 200 for a
    // method's own fields (it answers 200 for an unknown tool name). A body
    // shaped like a response gets 202 without a body, as the extension
    // answers one, but without the session it created for it: this server
    // never sends a request to a client, so no response is awaited.
    private boolean refuseMalformed(RoutingContext rc, JsonObject request, Object id) {
        if (!acceptsTransport(rc)) {
            return false;
        }
        String version = rc.request().getHeader(PROTOCOL_HEADER);
        if (version != null && !McpProtocolVersion.SUPPORTED_VERSIONS.contains(version)) {
            return false;
        }
        String method = request.getString("method");
        boolean stateless = extensionTakesAsStateless(rc, request, method);
        McpRequestShape.Refusal refusal = McpRequestShape.envelope(request);
        if (refusal != null) {
            refuse(rc, id, refusal, stateless);
            return true;
        }
        // A missing or mismatched header is refused after params and _meta
        // are read (findMeta), so only here.
        if (wrongVersionHeaders(rc, request, id, stateless) != null) {
            return false;
        }
        boolean validJsonRpc = "2.0".equals(request.getString("jsonrpc"));
        if (request.containsKey("result") || request.containsKey("error")) {
            if (!validJsonRpc) {
                return false; // -32600 from the extension, which drops its session
            }
            refused(rc, "response");
            String session = rc.request().getHeader(SESSION_HEADER);
            if (session != null) {
                rc.response().putHeader(SESSION_HEADER, session);
            }
            rc.response().setStatusCode(202).end();
            return true;
        }
        JsonObject params = request.getValue("params") instanceof JsonObject p ? p : null;
        JsonObject meta = params != null && params.getValue("_meta") instanceof JsonObject m ? m : null;
        if (stateless && extensionRefusesStatelessEnvelope(rc, request, method, params, meta)) {
            return false;
        }
        refusal = McpRequestShape.metaContent(meta);
        if (refusal != null) {
            refuse(rc, id, refusal, stateless);
            return true;
        }
        // 2.17.2: then, for a stateless request, the log level of _meta
        // (applyMetaLogLevel, right after buildStatelessInitialRequest
        // decodes what metaContent checks): one it does not know is its own
        // 400, before the jsonrpc member, the method and anything below.
        if (stateless && extensionRefusesLogLevel(meta)) {
            return false;
        }
        if (!validJsonRpc || method == null) {
            return false;
        }
        // An id the extension cannot hold: a request that is not one, as
        // JSON-RPC answers it, -32600 with id null; the status of a defect
        // of the envelope, as for a body that is null.
        if (!McpRequestShape.validId(id)) {
            refused(rc, "id");
            end(rc, stateless ? 400 : 200,
                    error(null, INVALID_REQUEST, "Invalid request: id must be a string or a number"));
            return true;
        }
        String knownMethod = request.getValue("method") instanceof String s ? s : null;
        refusal = McpRequestShape.methodFields(knownMethod, params, !stateless);
        if (refusal != null) {
            refuse(rc, id, refusal, stateless);
            return true;
        }
        // 2.17.2 (#126): last, what the extension answers only once the
        // method runs (McpMessageHandler.operation), and logs at every
        // request with the client's text unescaped. Only when the extension
        // would get that far: every check above passed, and the connection
        // the extension would use is in operation.
        // (knownMethod is null for a method that is not a string: Set.of
        // refuses to look null up.)
        if (knownMethod != null && (CURSOR_LISTS.contains(knownMethod) || CANCELLED.equals(knownMethod))) {
            if (!extensionReachesTheMethod(rc, stateless)) {
                return false;
            }
            if (CANCELLED.equals(knownMethod)) {
                // McpMessageHandler.cancelRequest: a cancel that matches no
                // request in progress is ignored, with a WARN. In the
                // stateless era and with auto-init it never matches (a
                // connection per request); in a genuine session only during
                // the milliseconds a request runs, and no feature of this
                // server reads a cancellation (no Cancellation is injected),
                // so a cancel that matches changes nothing either. The
                // extension's answer: 202, or 200 without a body when the
                // cancel carries an id (it scans it as a request). Not a
                // refusal, so nothing is logged. A future long-running
                // operation (Tasks, a slow tool) needs a cancel that reaches
                // the extension: an explicit exception here, as for the
                // deadline.
                rc.response().setStatusCode(id == null ? 202 : 200).end();
                return true;
            }
            // Messages.getCursor: the cursor read with getString, as the
            // extension reads it (a number or an object becomes text), and
            // refused when Cursor.decode would throw.
            String cursor = params == null ? null : params.getString("cursor");
            if (cursor != null && !extensionDecodesCursor(cursor)) {
                refused(rc, "cursor");
                endWithoutSession(rc, 200, error(id, INVALID_PARAMS, "Invalid cursor detected: " + cursor));
                return true;
            }
        }
        return false;
    }

    // The four lists of the extension that read a cursor (ToolMessageHandler,
    // ResourceMessageHandler, ResourceTemplateMessageHandler,
    // PromptMessageHandler). Not skills/list: SwapiSkillsExtension answers
    // any cursor itself, without a log line.
    private static final java.util.Set<String> CURSOR_LISTS = java.util.Set.of("tools/list", "resources/list",
            "resources/templates/list", "prompts/list");
    private static final String CANCELLED = "notifications/cancelled";

    // Cursor.decode of extension 2.1.0 (package-private there), copied: not
    // blank, basic Base64, 24 bytes, and the two Instant.ofEpochSecond(long,
    // int) it builds, which throw outside the range of Instant. Any exception
    // is a cursor the extension refuses. McpExtensionLogGuardTest compares
    // the two on both sides of the rule.
    static boolean extensionDecodesCursor(String value) {
        try {
            if (value.isBlank()) {
                return false;
            }
            byte[] bytes = Base64.getDecoder().decode(value);
            if (bytes.length != 24) {
                return false;
            }
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            Instant.ofEpochSecond(buffer.getLong(), buffer.getInt());
            Instant.ofEpochSecond(buffer.getLong(), buffer.getInt());
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // Whether the extension would run the method (McpMessageHandler
    // .doHandleRequest) once every check above passed: a stateless request
    // gets a connection in operation; a genuine session is used when it is
    // initializing or in operation; a request without a session the process
    // holds gets one only with auto-init (quarkus.mcp.server.http.streamable
    // .auto-init), and otherwise the extension answers it with its own error.
    private boolean extensionReachesTheMethod(RoutingContext rc, boolean stateless) {
        if (stateless) {
            return true;
        }
        String session = rc.request().getHeader(SESSION_HEADER);
        McpConnectionBase connection = session == null ? null : sessions.get(session);
        // Every session this process holds is a Streamable HTTP one: the
        // legacy SSE transport is refused above, and no listen reaches the
        // extension to register a stateless connection.
        if (connection != null) {
            return connection.status() == McpConnection.Status.INITIALIZING
                    || connection.status() == McpConnection.Status.IN_OPERATION;
        }
        return autoInit;
    }

    // applyMetaLogLevel: a log level present in _meta that McpLog.LogLevel
    // does not know (read with getString, as the extension reads it).
    private static boolean extensionRefusesLogLevel(JsonObject meta) {
        if (meta == null) {
            return false;
        }
        String level = meta.getString(META_LOG_LEVEL);
        return level != null && McpLog.LogLevel.from(level) == null;
    }

    // What the extension answers itself, with 400, for a stateless request
    // before it decodes anything this filter checks: validateStatelessHeaders
    // (Mcp-Method, Mcp-Name; the version headers are wrongVersionHeaders) and
    // validateStatelessMeta (a _meta, its protocol version and client
    // capabilities, a clientInfo that is an object). The values are compared
    // as the extension compares them, through getString.
    private static boolean extensionRefusesStatelessEnvelope(RoutingContext rc, JsonObject request, String method,
                                                             JsonObject params, JsonObject meta) {
        String methodHeader = rc.request().getHeader("Mcp-Method");
        if (methodHeader == null || (method != null && !methodHeader.equals(method))) {
            return true;
        }
        if ("tools/call".equals(method) || "prompts/get".equals(method) || "resources/read".equals(method)) {
            String nameHeader = rc.request().getHeader("Mcp-Name");
            String name = params == null ? null : params.getString("resources/read".equals(method) ? "uri" : "name");
            if (nameHeader == null || (name != null && !nameHeader.equals(name))) {
                return true;
            }
        }
        // 2.17.2: a protocol version in _meta that is not a string. The
        // extension reads it with getString too, as text ("1", "{}"), which
        // never equals a supported MCP-Protocol-Version header: its 400
        // "Header mismatch" (validateStatelessHeaders). wrongVersionHeaders
        // reads only a string, as the listen always has.
        if (meta != null && meta.getValue(McpRequestShape.PROTOCOL_VERSION) != null
                && !(meta.getValue(McpRequestShape.PROTOCOL_VERSION) instanceof String)) {
            return true;
        }
        return meta == null
                || meta.getString(McpRequestShape.PROTOCOL_VERSION) == null
                || (meta.containsKey(McpRequestShape.CLIENT_INFO)
                        && !(meta.getValue(McpRequestShape.CLIENT_INFO) instanceof JsonObject))
                || meta.getValue(McpRequestShape.CLIENT_CAPABILITIES) == null;
    }

    private void refuse(RoutingContext rc, Object id, McpRequestShape.Refusal refusal, boolean stateless) {
        refused(rc, refusal.kind());
        end(rc, refusal.envelope() && stateless ? 400 : 200, error(id, INVALID_PARAMS, refusal.message()));
    }

    private void refused(RoutingContext rc, String kind) {
        warnings.warn("Refused a malformed MCP request: " + kind);
    }

    // The extension's rule (StreamableHttpMcpMessageHandler.isStatelessRequest
    // and McpMessageHandler.isStatelessMessage): a stateless MCP-Protocol-Version
    // header; or server/discover, always; or, except for initialize, a
    // stateless version in _meta.
    private static boolean extensionTakesAsStateless(RoutingContext rc, JsonObject request, String method) {
        if (hasStatelessHeader(rc) || "server/discover".equals(method)) {
            return true;
        }
        return !"initialize".equals(method) && isStateless(rc, request);
    }

    private static boolean acceptsTransport(RoutingContext rc) {
        List<String> accepts = rc.request().headers().getAll("Accept");
        return accepts(accepts, "application/json") && accepts(accepts, "text/event-stream");
    }

    // 2.16.1: the answer extension 2.1.0 gives any method whose protocol
    // version headers are wrong, or null when they are right. The listen
    // never reaches the extension, so without this it answered "method not
    // found" where every other method answers 400. In the extension's order
    // (StreamableHttpMcpMessageHandler.handle, validateStatelessHeaders):
    // a version it does not support; a stateless request without the
    // MCP-Protocol-Version header (#1083); a header that differs from the
    // version in _meta. McpListenLimitTest compares each with the extension's
    // own answer, status and body, so an upgrade that changes one fails there.
    // The extension's Mcp-Method checks are not mirrored: here the body
    // method wins over that header, on purpose.
    // stateless: whether the extension takes the request as stateless (for
    // the listen, isStateless; 2.17.1, the shape checks pass the extension's
    // own rule, extensionTakesAsStateless).
    private static JsonObject wrongVersionHeaders(RoutingContext rc, JsonObject request, Object id,
                                                  boolean stateless) {
        String header = rc.request().getHeader(PROTOCOL_HEADER);
        if (header != null && !McpProtocolVersion.SUPPORTED_VERSIONS.contains(header)) {
            JsonObject unsupported = error(id, UNSUPPORTED_PROTOCOL_VERSION, "Unsupported protocol version");
            unsupported.getJsonObject("error").put("data", new JsonObject()
                    .put("supported", new JsonArray(List.copyOf(McpProtocolVersion.SUPPORTED_VERSIONS)))
                    .put("requested", header));
            return unsupported;
        }
        if (!stateless) {
            return null;
        }
        if (header == null) {
            return error(id, HEADER_MISMATCH, "Missing required header: " + PROTOCOL_HEADER_AS_THE_EXTENSION_WRITES_IT);
        }
        String meta = metaVersion(request);
        if (meta != null && !header.equals(meta)) {
            return error(id, HEADER_MISMATCH, "Header mismatch: " + PROTOCOL_HEADER_AS_THE_EXTENSION_WRITES_IT
                    + " header value '" + header
                    + "' does not match _meta value '" + meta + "'");
        }
        return null;
    }

    // A resource this server registered (none in 2.12.1) -> empty result, the
    // same the extension sends, without recording a subscriber; anything else
    // -> -32002, as the extension answers a client of the session era, with
    // the URI in data.uri as extension 2.1.0 sends it (2.16.1).
    private JsonObject subscribeAnswer(Object id, JsonObject request) {
        JsonObject params = request.getValue("params") instanceof JsonObject p ? p : null;
        String uri = params != null && params.getValue("uri") instanceof String s ? s : null;
        if (uri == null) {
            return error(id, INVALID_PARAMS, "Resource URI not defined");
        }
        if (resources.getResource(uri) == null) {
            JsonObject notFound = error(id, RESOURCE_NOT_FOUND, "Resource not found: " + uri);
            notFound.getJsonObject("error").put("data", new JsonObject().put("uri", uri));
            return notFound;
        }
        return new JsonObject().put("jsonrpc", "2.0").put("id", id).put("result", new JsonObject());
    }

    // Only the subscribe the extension itself would handle in the session era:
    // a supported (or absent) MCP-Protocol-Version and the Accept the
    // transport requires. With a bad version or Accept the extension answers
    // 400, and in the stateless era -32601; none records a subscriber or opens
    // a stream, so those go on to it. Both 400s come before any session
    // exists: since extension 2.1.0 the version is checked before the session
    // lookup (up to 2.0.3 a request without a known session id got a session
    // first, left to quarkus.mcp.server.connection-idle-timeout, issue #45,
    // McpSessionIdleTimeoutTest).
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
        return acceptsTransport(rc);
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

    // The extension's rule (StreamableHttpMcpMessageHandler.isStatelessRequest,
    // the same in 2.0.3 and 2.1.0): a stateless MCP-Protocol-Version header, or
    // a stateless version in _meta. The extension also takes server/discover
    // as stateless and initialize as not, whatever the version; the filter
    // asks only about listen and subscribe, where neither applies.
    private static boolean isStateless(RoutingContext rc, JsonObject request) {
        if (hasStatelessHeader(rc)) {
            return true;
        }
        String version = metaVersion(request);
        return version != null && McpProtocolVersion.isStateless(version);
    }

    private static String metaVersion(JsonObject request) {
        JsonObject params = request.getValue("params") instanceof JsonObject p ? p : null;
        JsonObject meta = params != null && params.getValue("_meta") instanceof JsonObject m ? m : null;
        return meta != null && meta.getValue(META_PROTOCOL_VERSION) instanceof String s ? s : null;
    }

    private static boolean hasStatelessHeader(RoutingContext rc) {
        String header = rc.request().getHeader(PROTOCOL_HEADER);
        return header != null && McpProtocolVersion.isStateless(header);
    }

    // Parsed with the same decoder the extension uses, so an escaped method
    // name ("subscriptions\/listen") is the same method here and there.
    // null is the JSON literal null; a body that is not JSON is NOT_JSON.
    private static Object parse(Buffer buffer) {
        if (buffer == null || buffer.length() == 0) {
            return NOT_JSON;
        }
        try {
            return Json.decodeValue(buffer);
        } catch (RuntimeException e) {
            return NOT_JSON; // the extension answers the parse error
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
        endWithoutSession(rc, status, payload);
    }

    // 2.17.2: an answer the filter writes in the extension's place, as the
    // extension writes it: without echoing Mcp-Session-Id.
    private static void endWithoutSession(RoutingContext rc, int status, JsonObject payload) {
        rc.response()
                .setStatusCode(status)
                .putHeader("Content-Type", "application/json")
                .end(payload.toBuffer());
    }

    private static boolean autoInit() {
        McpHttpServerRuntimeConfig server = ConfigProvider.getConfig().unwrap(SmallRyeConfig.class)
                .getConfigMapping(McpHttpServersRuntimeConfig.class).servers().get(McpServer.DEFAULT);
        return server != null && server.http().streamable().autoInit();
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
