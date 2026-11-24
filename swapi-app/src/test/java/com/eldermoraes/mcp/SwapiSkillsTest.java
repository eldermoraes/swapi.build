package com.eldermoraes.mcp;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2.17.0: the skill star-wars-research, served through the Skills over MCP
 * extension (io.modelcontextprotocol/skills): skills/list, skills/get and its
 * two files as skill:// resources. At the HTTP boundary of /mcp, in both eras.
 *
 * The check a client makes before it loads the skill is made here the same
 * way: each file of the manifest is read with resources/read, and the UTF-8
 * bytes of the text must have the manifest's size and SHA-256; the
 * frontmatter of the served SKILL.md, parsed by a YAML parser (SnakeYAML,
 * not the server's own reader), must equal the entry's field by field.
 */
@QuarkusTest
class SwapiSkillsTest {

    static final String EXTENSION = "io.modelcontextprotocol/skills";
    static final String SKILL_URI = "skill://star-wars-research/SKILL.md";
    static final String RELATIONS_URI = "skill://star-wars-research/references/relations.md";

    private static final String STATELESS = "2026-07-28";
    private static final String SESSION_ERA = "2025-06-18";

    @TestHTTPResource("/mcp")
    URI mcp;

    // ---- requests ----

    private static String meta() {
        return """
                "_meta":{"io.modelcontextprotocol/protocolVersion":"%s",
                  "io.modelcontextprotocol/clientInfo":{"name":"skills-test","version":"1.0"},
                  "io.modelcontextprotocol/clientCapabilities":{}}""".formatted(STATELESS);
    }

    private HttpRequest.Builder post(String body) {
        return HttpRequest.newBuilder(mcp)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    /** A stateless request (2026-07-28): the _meta, the version header and Mcp-Method. */
    private HttpResponse<String> stateless(String method, String params) throws Exception {
        String body = """
                {"jsonrpc":"2.0","id":1,"method":"%s","params":{%s%s}}
                """.formatted(method, params.isEmpty() ? "" : params + ",", meta());
        HttpRequest.Builder request = post(body)
                .header("MCP-Protocol-Version", STATELESS)
                .header("Mcp-Method", method);
        return send(request.build());
    }

    private JsonObject statelessResult(String method, String params) throws Exception {
        HttpResponse<String> r = stateless(method, params);
        assertEquals(200, r.statusCode(), method + ": " + r.body());
        JsonObject body = new JsonObject(r.body());
        assertNotNull(body.getJsonObject("result"), method + ": " + r.body());
        return body.getJsonObject("result");
    }

    private JsonObject statelessError(String method, String params) throws Exception {
        HttpResponse<String> r = stateless(method, params);
        JsonObject body = new JsonObject(r.body());
        assertFalse(body.containsKey("result"), method + ": " + r.body());
        assertNotNull(body.getJsonObject("error"), method + ": " + r.body());
        return body.getJsonObject("error");
    }

    /** A stateless resources/read names the URI in Mcp-Name too, as the extension requires. */
    private JsonObject statelessRead(String uri) throws Exception {
        HttpResponse<String> r = send(post("""
                {"jsonrpc":"2.0","id":2,"method":"resources/read","params":{"uri":"%s",%s}}
                """.formatted(uri, meta()))
                .header("MCP-Protocol-Version", STATELESS)
                .header("Mcp-Method", "resources/read")
                .header("Mcp-Name", uri).build());
        assertEquals(200, r.statusCode(), uri + ": " + r.body());
        JsonObject result = new JsonObject(r.body()).getJsonObject("result");
        assertNotNull(result, uri + ": " + r.body());
        return result;
    }

    private String openSession() throws Exception {
        HttpResponse<String> init = send(post("""
                {"jsonrpc":"2.0","id":1,"method":"initialize",
                 "params":{"protocolVersion":"%s","capabilities":{},
                           "clientInfo":{"name":"skills-test","version":"1.0"}}}
                """.formatted(SESSION_ERA)).build());
        assertEquals(200, init.statusCode(), init.body());
        JsonObject capabilities = new JsonObject(init.body()).getJsonObject("result").getJsonObject("capabilities");
        assertTrue(capabilities.containsKey("resources"), "" + capabilities);
        assertEquals(new JsonObject(), capabilities.getJsonObject("extensions").getJsonObject(EXTENSION),
                "initialize: " + capabilities);
        String session = init.headers().firstValue("Mcp-Session-Id").orElse(null);
        assertNotNull(session, "initialize must hand out a session");
        return session;
    }

    private JsonObject sessionCall(String session, String method, String params) throws Exception {
        HttpResponse<String> r = send(post("""
                {"jsonrpc":"2.0","id":3,"method":"%s","params":{%s}}
                """.formatted(method, params))
                .header("Mcp-Session-Id", session)
                .header("MCP-Protocol-Version", SESSION_ERA).build());
        assertEquals(200, r.statusCode(), method + ": " + r.body());
        return new JsonObject(r.body());
    }

    // ---- checks a client makes ----

    private static String sha256(byte[] bytes) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** The frontmatter of a SKILL.md text, as a YAML parser reads it. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> yamlFrontmatter(String text) {
        assertTrue(text.startsWith("---\n"), "SKILL.md must start with the frontmatter");
        int end = text.indexOf("\n---\n", 4);
        assertTrue(end > 0, "SKILL.md frontmatter is not closed");
        Object parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(text.substring(4, end + 1));
        assertInstanceOf(Map.class, parsed, "the frontmatter is not a mapping");
        return (Map<String, Object>) parsed;
    }

    private static JsonObject theEntry(JsonObject listResult) {
        JsonArray skills = listResult.getJsonArray("skills");
        assertEquals(1, skills.size(), "" + listResult);
        return skills.getJsonObject(0);
    }

    /** The entry's shape, the one skill this server serves. */
    private static void assertEntry(JsonObject entry) {
        assertEquals(List.of("uri", "frontmatter", "resources"), List.copyOf(entry.fieldNames()), "" + entry);
        assertEquals(SKILL_URI, entry.getString("uri"));
        JsonObject frontmatter = entry.getJsonObject("frontmatter");
        assertEquals(List.of("name", "description"), List.copyOf(frontmatter.fieldNames()), "" + frontmatter);
        assertEquals("star-wars-research", frontmatter.getString("name"));
        assertFalse(frontmatter.getString("description").isBlank());
        JsonArray resources = entry.getJsonArray("resources");
        assertEquals(2, resources.size(), "" + resources);
        assertEquals(SKILL_URI, resources.getJsonObject(0).getString("uri"));
        assertEquals(RELATIONS_URI, resources.getJsonObject(1).getString("uri"));
        for (int i = 0; i < resources.size(); i++) {
            JsonObject file = resources.getJsonObject(i);
            assertEquals(List.of("uri", "digest", "size"), List.copyOf(file.fieldNames()), "" + file);
            assertTrue(file.getString("digest").matches("sha256:[0-9a-f]{64}"), "" + file);
            assertTrue(file.getInteger("size") > 0, "" + file);
        }
    }

    private static void assertCacheable(JsonObject result) {
        assertEquals("complete", result.getString("resultType"), "" + result);
        assertEquals(300000, result.getInteger("ttlMs"), "" + result);
        assertEquals("public", result.getString("cacheScope"), "" + result);
    }

    // ---- capability ----

    @Test
    void discoverAdvertisesTheExtensionNextToResources() throws Exception {
        JsonObject capabilities = statelessResult("server/discover", "").getJsonObject("capabilities");
        assertTrue(capabilities.containsKey("resources"), "" + capabilities);
        assertTrue(capabilities.containsKey("tools"), "" + capabilities);
        // No directoryRead: the manifest lists every file, so no setting.
        assertEquals(new JsonObject(), capabilities.getJsonObject("extensions").getJsonObject(EXTENSION),
                "" + capabilities);
    }

    @Test
    void initializeAdvertisesTheExtensionNextToResources() throws Exception {
        openSession();
    }

    // ---- skills/list ----

    @Test
    void statelessListHasTheOneSkill() throws Exception {
        JsonObject result = statelessResult("skills/list", "");
        assertCacheable(result);
        assertFalse(result.containsKey("nextCursor"), "" + result);
        assertEntry(theEntry(result));
    }

    @Test
    void sessionListHasTheSameSkill() throws Exception {
        String session = openSession();
        JsonObject body = sessionCall(session, "skills/list", "");
        JsonObject result = body.getJsonObject("result");
        assertNotNull(result, "" + body);
        assertCacheable(result);
        assertFalse(result.containsKey("nextCursor"), "" + result);
        assertEntry(theEntry(result));
        assertEquals(theEntry(statelessResult("skills/list", "")), theEntry(result));
    }

    // One page only: a cursor can only come from a nextCursor this server
    // never sends.
    @Test
    void listWithACursorIsInvalidParams() throws Exception {
        assertEquals(-32602, statelessError("skills/list", "\"cursor\":\"abc\"").getInteger("code"));
        assertEquals(-32602, statelessError("skills/list", "\"cursor\":null").getInteger("code"));
        // Pair: the same request without the cursor is served.
        assertEquals(1, statelessResult("skills/list", "").getJsonArray("skills").size());
    }

    // ---- what a client verifies before it loads the skill ----

    @Test
    void everyManifestFileReadsWithItsSizeAndDigest() throws Exception {
        JsonArray resources = theEntry(statelessResult("skills/list", "")).getJsonArray("resources");
        for (int i = 0; i < resources.size(); i++) {
            JsonObject file = resources.getJsonObject(i);
            String uri = file.getString("uri");
            JsonArray contents = statelessRead(uri).getJsonArray("contents");
            assertEquals(1, contents.size(), uri);
            JsonObject content = contents.getJsonObject(0);
            assertEquals(uri, content.getString("uri"));
            assertEquals("text/markdown", content.getString("mimeType"), uri);
            byte[] bytes = content.getString("text").getBytes(StandardCharsets.UTF_8);
            assertEquals(file.getInteger("size"), bytes.length, uri + ": size");
            assertEquals(file.getString("digest"), sha256(bytes), uri + ": digest");
        }
    }

    @Test
    void servedFrontmatterEqualsTheEntryFieldByField() throws Exception {
        JsonObject entry = theEntry(statelessResult("skills/list", ""));
        String text = statelessRead(SKILL_URI).getJsonArray("contents").getJsonObject(0).getString("text");
        Map<String, Object> served = yamlFrontmatter(text);
        JsonObject frontmatter = entry.getJsonObject("frontmatter");
        assertEquals(frontmatter.fieldNames(), served.keySet());
        for (String key : frontmatter.fieldNames()) {
            assertEquals(frontmatter.getValue(key), served.get(key), key);
        }
    }

    @Test
    void sessionReadServesTheSameBytes() throws Exception {
        String session = openSession();
        for (String uri : List.of(SKILL_URI, RELATIONS_URI)) {
            JsonObject body = sessionCall(session, "resources/read", "\"uri\":\"" + uri + "\"");
            String sessionText = body.getJsonObject("result").getJsonArray("contents").getJsonObject(0)
                    .getString("text");
            String statelessText = statelessRead(uri).getJsonArray("contents").getJsonObject(0).getString("text");
            assertEquals(statelessText, sessionText, uri);
        }
    }

    // The last segment of the skill's path is its name, and the name follows
    // the Agent Skills rules.
    @Test
    void skillPathEndsWithItsName() throws Exception {
        JsonObject entry = theEntry(statelessResult("skills/list", ""));
        String uri = entry.getString("uri");
        assertTrue(uri.startsWith("skill://") && uri.endsWith("/SKILL.md"), uri);
        String path = uri.substring("skill://".length(), uri.length() - "/SKILL.md".length());
        String name = path.substring(path.lastIndexOf('/') + 1);
        assertEquals(entry.getJsonObject("frontmatter").getString("name"), name);
        assertTrue(name.matches("[a-z0-9]+(-[a-z0-9]+)*") && name.length() <= 64, name);
    }

    // The resource of SKILL.md carries the name and description of its
    // frontmatter, as the extension recommends; both appear in resources/list.
    @Test
    void resourcesListCarriesBothFiles() throws Exception {
        JsonObject entry = theEntry(statelessResult("skills/list", ""));
        JsonArray listed = statelessResult("resources/list", "").getJsonArray("resources");
        JsonObject skill = null;
        JsonObject relations = null;
        for (int i = 0; i < listed.size(); i++) {
            JsonObject resource = listed.getJsonObject(i);
            if (SKILL_URI.equals(resource.getString("uri"))) {
                skill = resource;
            } else if (RELATIONS_URI.equals(resource.getString("uri"))) {
                relations = resource;
            }
        }
        assertNotNull(skill, "resources/list has no " + SKILL_URI);
        assertNotNull(relations, "resources/list has no " + RELATIONS_URI);
        assertEquals("star-wars-research", skill.getString("name"));
        assertEquals(entry.getJsonObject("frontmatter").getString("description"), skill.getString("description"));
        assertEquals("text/markdown", skill.getString("mimeType"));
        assertEquals("text/markdown", relations.getString("mimeType"));
        JsonArray manifest = entry.getJsonArray("resources");
        assertEquals(manifest.getJsonObject(0).getInteger("size"), skill.getInteger("size"));
        assertEquals(manifest.getJsonObject(1).getInteger("size"), relations.getInteger("size"));
    }

    // ---- skills/get ----

    @Test
    void getReturnsTheSameEntryAsTheList() throws Exception {
        JsonObject result = statelessResult("skills/get", "\"uri\":\"" + SKILL_URI + "\"");
        assertCacheable(result);
        assertFalse(result.containsKey("nextCursor"), "" + result);
        assertEntry(result.getJsonObject("skill"));
        assertEquals(theEntry(statelessResult("skills/list", "")), result.getJsonObject("skill"));
    }

    @Test
    void sessionGetReturnsTheSameEntry() throws Exception {
        String session = openSession();
        JsonObject body = sessionCall(session, "skills/get", "\"uri\":\"" + SKILL_URI + "\"");
        assertEquals(theEntry(statelessResult("skills/list", "")), body.getJsonObject("result").getJsonObject("skill"),
                "" + body);
    }

    // Only the URI of a SKILL.md names a skill: an unknown one, the
    // supporting file and a missing uri are invalid params. Pair: the right
    // URI is served (getReturnsTheSameEntryAsTheList).
    @Test
    void getOfAnythingButTheSkillIsInvalidParams() throws Exception {
        for (String params : List.of(
                "\"uri\":\"skill://nope/SKILL.md\"",
                "\"uri\":\"" + RELATIONS_URI + "\"",
                "\"uri\":\"skill://star-wars-research\"",
                "\"uri\":\"swapi://people/1\"",
                "\"uri\":\"SKILL://star-wars-research/SKILL.md\"",
                "")) {
            JsonObject error = statelessError("skills/get", params);
            assertEquals(-32602, error.getInteger("code"), params + ": " + error);
        }
        assertTrue(statelessError("skills/get", "\"uri\":\"skill://nope/SKILL.md\"").getString("message")
                .contains("skill://nope/SKILL.md"));
        String session = openSession();
        JsonObject body = sessionCall(session, "skills/get", "\"uri\":\"skill://nope/SKILL.md\"");
        assertEquals(-32602, body.getJsonObject("error").getInteger("code"), "" + body);
    }

    // ---- the filter ----

    // McpTransportFilter answers every JSON array itself; skills/* is no
    // exception.
    @Test
    void batchWithSkillsListGetsTheFilterAnswer() throws Exception {
        HttpResponse<String> r = send(post("""
                [{"jsonrpc":"2.0","id":1,"method":"skills/list"},
                 {"jsonrpc":"2.0","id":2,"method":"skills/get","params":{"uri":"%s"}}]
                """.formatted(SKILL_URI)).build());
        assertEquals(400, r.statusCode(), r.body());
        JsonObject body = new JsonObject(r.body());
        assertEquals(-32600, body.getJsonObject("error").getInteger("code"), r.body());
        assertTrue(body.containsKey("id") && body.getValue("id") == null, r.body());
    }
}
