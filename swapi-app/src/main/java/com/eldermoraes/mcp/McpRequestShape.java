package com.eldermoraes.mcp;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The request shapes the MCP extension (quarkus-mcp-server 2.1.0) cannot
 * handle, checked before the request reaches it (McpTransportFilter).
 *
 * The extension reads request fields with unchecked casts and passes some of
 * them to constructors that refuse null. Where that happens inside its route
 * handler, the client got HTTP 500 and the log a stack trace; where it happens
 * inside the callback that runs a method, the client never got an answer and
 * the extension kept, until a restart, the request among those in progress,
 * the throwaway session of auto-init and an open request context.
 *
 * The list comes from reading the extension's source, not from trying
 * requests: every getJsonObject, getJsonArray and constructor that refuses
 * null applied to what the client sent, on the paths this server reaches
 * (StreamableHttpMcpMessageHandler.handle and autoInitialRequest,
 * McpMessageHandler.findMeta, decodeInitializeRequest,
 * buildStatelessInitialRequest, operation and complete, Messages
 * .decodeImplementation and decodeIcons, ToolMessageHandler, PromptMessageHandler,
 * CompletionMessageHandler, ServerRequests.handleResponse). Re-derive it from
 * the source at every upgrade of the extension: McpExtensionShapeGuardTest
 * fails the day the extension handles one of these shapes itself, but only a
 * new reading finds a new one.
 *
 * Each check is on the type of a field (or on its presence, where every
 * protocol revision this server supports requires the field and the extension
 * fails without it), never on extra members. The one check on a value is the
 * theme of an icon, which the extension turns into an enum and refuses
 * outside light and dark (in any case, as it compares them).
 */
final class McpRequestShape {

    static final String CLIENT_INFO = "io.modelcontextprotocol/clientInfo";
    static final String CLIENT_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities";
    static final String PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion";

    private McpRequestShape() {
    }

    /**
     * Why a request is refused. kind is a fixed label, the only part that
     * goes to the log; message names the field and goes to the client.
     * envelope: a defect of params or _meta, which the stateless era answers
     * with HTTP 400 as the extension answers a _meta it refuses.
     */
    record Refusal(String kind, String message, boolean envelope) {
    }

    /**
     * The id of a request, when present, must be a string or a number, as
     * every revision requires (null is read as no id: a notification). The
     * extension runs a method with any id, answers, and then fails while it
     * cleans up after the method (RequestId refuses other types, in
     * CancellationRequests.remove, McpMessageHandler.operation): an ERROR
     * with a stack trace in the log, and the throwaway session of auto-init
     * left behind. Found by McpRequestMutationTest.
     */
    static boolean validId(Object id) {
        return id == null || id instanceof String || id instanceof Number;
    }

    /** params and params._meta, when present, must be objects (McpMessageHandler.findMeta). */
    static Refusal envelope(JsonObject request) {
        Object params = request.getValue("params");
        if (params == null) {
            return null;
        }
        if (!(params instanceof JsonObject p)) {
            return new Refusal("params", "Invalid params: params must be an object", true);
        }
        Object meta = p.getValue("_meta");
        if (meta != null && !(meta instanceof JsonObject)) {
            return new Refusal("_meta", "Invalid params: params._meta must be an object", true);
        }
        return null;
    }

    /**
     * What _meta says about the client, decoded by the extension for a
     * stateless request (buildStatelessInitialRequest) and for a session it
     * creates on the fly (autoInitialRequest). The stateless era refuses a
     * clientInfo that is not an object itself, before this.
     */
    static Refusal metaContent(JsonObject meta) {
        if (meta == null) {
            return null;
        }
        Object capabilities = meta.getValue(CLIENT_CAPABILITIES);
        if (capabilities != null && !(capabilities instanceof JsonObject)) {
            return new Refusal("_meta clientCapabilities",
                    "Invalid params: _meta " + CLIENT_CAPABILITIES + " must be an object", true);
        }
        Object info = meta.getValue(CLIENT_INFO);
        if (info == null) {
            return null;
        }
        if (!(info instanceof JsonObject implementation)) {
            return new Refusal("_meta clientInfo", "Invalid params: _meta " + CLIENT_INFO + " must be an object", true);
        }
        String defect = implementationDefect(implementation, "_meta " + CLIENT_INFO);
        return defect == null ? null : new Refusal("_meta clientInfo", "Invalid params: " + defect, true);
    }

    /**
     * The fields of the methods whose handler fails on them. params is the
     * request's params, an object (envelope() ran first); a method without
     * params is left to the extension, which refuses it cleanly.
     * sessionInitialize: an initialize the extension decodes, which it does
     * only outside the stateless era.
     */
    static Refusal methodFields(String method, JsonObject params, boolean sessionInitialize) {
        if (method == null || params == null) {
            return null;
        }
        switch (method) {
            case "initialize" -> {
                return sessionInitialize ? initialize(params) : null;
            }
            case "tools/call", "prompts/get" -> {
                // The name becomes a key that refuses null (FeatureKey).
                if (!(params.getValue("name") instanceof String)) {
                    return new Refusal(method, "Invalid params: " + method + " requires name as a string", false);
                }
                // Read with a cast; the extension answered -32603.
                if (!objectOrAbsent(params.getValue("arguments"))) {
                    return new Refusal(method, "Invalid params: arguments must be an object", false);
                }
                return null;
            }
            case "completion/complete" -> {
                // Read with casts, and name and value go to Map.of, which
                // refuses null (McpMessageHandler.complete,
                // CompletionMessageHandler). Without ref or argument the
                // extension answers on its own.
                if (!objectOrAbsent(params.getValue("ref"))) {
                    return new Refusal(method, "Invalid params: ref must be an object", false);
                }
                Object argument = params.getValue("argument");
                if (!objectOrAbsent(argument)) {
                    return new Refusal(method, "Invalid params: argument must be an object", false);
                }
                if (argument instanceof JsonObject a) {
                    if (!(a.getValue("name") instanceof String)) {
                        return new Refusal(method, "Invalid params: argument.name must be a string", false);
                    }
                    if (!(a.getValue("value") instanceof String)) {
                        return new Refusal(method, "Invalid params: argument.value must be a string", false);
                    }
                }
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    // clientInfo is required by every revision from 2024-11-05 to 2025-11-25
    // and the extension fails without it. capabilities is required too, but
    // the extension reads a missing one as "no capabilities" and answers, so
    // only a capabilities that is not an object is refused.
    private static Refusal initialize(JsonObject params) {
        Object info = params.getValue("clientInfo");
        if (!(info instanceof JsonObject implementation)) {
            return new Refusal("initialize clientInfo", "Invalid params: initialize requires clientInfo as an object",
                    false);
        }
        String defect = implementationDefect(implementation, "clientInfo");
        if (defect != null) {
            return new Refusal("initialize clientInfo", "Invalid params: " + defect, false);
        }
        if (!objectOrAbsent(params.getValue("capabilities"))) {
            return new Refusal("initialize capabilities", "Invalid params: capabilities must be an object", false);
        }
        return null;
    }

    // An Implementation (Messages.decodeImplementation, decodeIcons): name
    // and version are required in every revision and the extension's record
    // refuses null; an icon (2025-11-25+) requires src, sizes is an array of
    // strings, theme is light or dark. A null icon is skipped by the
    // extension, so it is left alone here.
    static String implementationDefect(JsonObject implementation, String where) {
        if (!(implementation.getValue("name") instanceof String)) {
            return where + ".name must be a string";
        }
        if (!(implementation.getValue("version") instanceof String)) {
            return where + ".version must be a string";
        }
        Object icons = implementation.getValue("icons");
        if (icons == null) {
            return null;
        }
        if (!(icons instanceof JsonArray list)) {
            return where + ".icons must be an array";
        }
        for (int i = 0; i < list.size(); i++) {
            Object icon = list.getValue(i);
            if (icon == null) {
                continue;
            }
            String at = where + ".icons[" + i + "]";
            if (!(icon instanceof JsonObject o)) {
                return at + " must be an object";
            }
            if (!(o.getValue("src") instanceof String)) {
                return at + ".src must be a string";
            }
            Object sizes = o.getValue("sizes");
            if (sizes != null) {
                if (!(sizes instanceof JsonArray s)) {
                    return at + ".sizes must be an array";
                }
                for (int j = 0; j < s.size(); j++) {
                    if (!(s.getValue(j) instanceof String)) {
                        return at + ".sizes must hold strings";
                    }
                }
            }
            if (o.containsKey("theme")
                    && !(o.getValue("theme") instanceof String theme
                    && (theme.equalsIgnoreCase("light") || theme.equalsIgnoreCase("dark")))) {
                return at + ".theme must be \"light\" or \"dark\"";
            }
        }
        return null;
    }

    private static boolean objectOrAbsent(Object value) {
        return value == null || value instanceof JsonObject;
    }
}
