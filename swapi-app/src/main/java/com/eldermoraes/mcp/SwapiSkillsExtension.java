package com.eldermoraes.mcp;

import io.quarkiverse.mcp.server.JsonRpcErrorCodes;
import io.quarkiverse.mcp.server.McpException;
import io.quarkiverse.mcp.server.McpExtension;
import io.quarkiverse.mcp.server.McpExtensionMethod;
import io.quarkiverse.mcp.server.RawMessage;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;

/**
 * The Skills over MCP extension (io.modelcontextprotocol/skills, 2.17.0):
 * skills/list and skills/get for the one skill of SwapiSkills. The files are
 * read with the standard resources/read; this class only describes them.
 *
 * Advertised with an empty settings object: no directoryRead, because the
 * manifest of each entry already lists every file of the skill.
 *
 * The extension (quarkus-mcp-server 2.1.0) adds resultType "complete" and the
 * _meta of the result; ttlMs and cacheScope come from here. Five minutes and
 * public, as in the examples of the specification: the files change only with
 * a release, and a client checks the digest of every file it reads anyway.
 *
 * Both methods answer in the stateless era and in a session; McpTransportFilter
 * does not touch them (a batch that carries them still gets its 400).
 */
@McpExtension(id = "io.modelcontextprotocol/skills")
public class SwapiSkillsExtension {

    static final long TTL_MS = 300_000;
    static final String CACHE_SCOPE = "public";

    @Inject SwapiSkills skills;

    /**
     * One page with the one skill. A cursor can only come from a nextCursor,
     * which is never sent; a null cursor is the first page (a client may write
     * its optional fields as null).
     *
     * params is read without a cast, as McpTransportFilter reads it. Extension
     * 2.1.0 does not pass on a message whose params is not an object (it fails
     * on it before any method, SwapiSkillsTest), but should that change, the
     * answer here is -32602, not a ClassCastException.
     */
    @McpExtensionMethod("skills/list")
    public JsonObject list(RawMessage message) {
        Object params = message.asJsonObject().getValue("params");
        if (params != null && !(params instanceof JsonObject)) {
            throw new McpException("Invalid params: skills/list takes an object", JsonRpcErrorCodes.INVALID_PARAMS);
        }
        if (params instanceof JsonObject p && p.getValue("cursor") != null) {
            throw new McpException("Invalid cursor: skills/list has a single page", JsonRpcErrorCodes.INVALID_PARAMS);
        }
        return cacheable(new JsonObject().put("skills", new JsonArray().add(entry(skills.skill()))));
    }

    /** The entry for the URI of the skill's SKILL.md; any other URI is not a skill of this server. */
    @McpExtensionMethod("skills/get")
    public JsonObject get(String uri) {
        SwapiSkills.Skill skill = skills.skill();
        if (!skill.uri().equals(uri)) {
            throw new McpException("No skill is served at " + uri, JsonRpcErrorCodes.INVALID_PARAMS);
        }
        return cacheable(new JsonObject().put("skill", entry(skill)));
    }

    private static JsonObject cacheable(JsonObject result) {
        return result.put("ttlMs", TTL_MS).put("cacheScope", CACHE_SCOPE);
    }

    /** uri, frontmatter (as the file has it) and the complete manifest, SKILL.md first. */
    static JsonObject entry(SwapiSkills.Skill skill) {
        JsonObject frontmatter = new JsonObject();
        skill.frontmatter().forEach(frontmatter::put);
        JsonArray resources = new JsonArray();
        for (SwapiSkills.SkillFile file : skill.files()) {
            resources.add(new JsonObject()
                    .put("uri", file.uri())
                    .put("digest", file.digest())
                    .put("size", file.size()));
        }
        return new JsonObject()
                .put("uri", skill.uri())
                .put("frontmatter", frontmatter)
                .put("resources", resources);
    }
}
