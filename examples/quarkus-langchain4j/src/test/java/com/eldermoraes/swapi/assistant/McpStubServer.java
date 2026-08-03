package com.eldermoraes.swapi.assistant;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal in-process MCP server for offline tests. The Quarkus MCP client
 * connects during startup, so without this the test suite would depend on the
 * public swapi.build server being reachable.
 *
 * <p>Wire it with
 * {@code @QuarkusTestResource(value = McpStubServer.class, restrictToAnnotatedClass = true)}:
 * unrestricted, its URL override would also apply to {@code McpConnectivityTest}
 * and silently retarget that live production gate at this stub.
 */
public class McpStubServer implements QuarkusTestResourceLifecycleManager {

    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");
    private static final Pattern METHOD = Pattern.compile("\"method\"\\s*:\\s*\"([^\"]+)\"");

    /**
     * Every JSON-RPC method this stub answered, so a test can prove the client
     * really handshook here instead of passing for some unrelated reason.
     */
    private static final List<String> SERVED_METHODS = new CopyOnWriteArrayList<>();

    private HttpServer server;

    public static List<String> servedMethods() {
        return List.copyOf(SERVED_METHODS);
    }

    @Override
    public Map<String, String> start() {
        SERVED_METHODS.clear();
        try {
            server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        server.createContext("/mcp", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8);

            Matcher method = METHOD.matcher(body);
            if (method.find()) {
                SERVED_METHODS.add(method.group(1));
            }

            if (body.contains("\"notifications/")) {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }

            Matcher matcher = ID.matcher(body);
            String id = matcher.find() ? matcher.group(1) : "1";
            String payload;

            if (body.contains("\"initialize\"")) {
                exchange.getResponseHeaders().add("Mcp-Session-Id", "stub-session");
                payload = """
                        {"jsonrpc":"2.0","id":%s,"result":{"protocolVersion":"2025-11-25",\
                        "capabilities":{"tools":{}},\
                        "serverInfo":{"name":"stub","version":"1.0"}}}""".formatted(id);
            } else if (body.contains("\"tools/list\"")) {
                payload = """
                        {"jsonrpc":"2.0","id":%s,"result":{"tools":[{"name":"sw_get",\
                        "description":"Gets one Star Wars entity by numeric id",\
                        "inputSchema":{"type":"object","properties":{\
                        "resource":{"type":"string"},"id":{"type":"integer"}},\
                        "required":["resource","id"]}}]}}""".formatted(id);
            } else {
                payload = """
                        {"jsonrpc":"2.0","id":%s,"error":{"code":-32601,\
                        "message":"stub does not implement this method"}}""".formatted(id);
            }

            byte[] out = payload.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });

        server.start();
        return Map.of("quarkus.langchain4j.mcp.swapi.url",
                "http://localhost:" + server.getAddress().getPort() + "/mcp");
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }
}
