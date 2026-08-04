package com.eldermoraes.swapi.mcpclient;

import io.quarkiverse.langchain4j.RegisterAiService;
import io.quarkiverse.langchain4j.mcp.runtime.McpToolBox;
import dev.langchain4j.service.SystemMessage;

/**
 * Answers questions using tools discovered from the swapi.build MCP server.
 *
 * There are no tools in this project. @McpToolBox names the MCP client declared
 * in application.properties; the server advertises what it can do, and the model
 * picks from that list — chaining calls when it needs to, for example looking up
 * a character and then that character's home planet.
 */
@RegisterAiService
@SystemMessage("""
        You are the Star Wars archivist for swapi.build.

        Answer only with facts returned by the tools you call, and call as many as
        you need. If the tools do not have the answer, say you do not know.
        Never invent Star Wars data. Answer in at most two sentences.
        """)
public interface Archivist {

    @McpToolBox("swapi")
    String ask(String question);
}
