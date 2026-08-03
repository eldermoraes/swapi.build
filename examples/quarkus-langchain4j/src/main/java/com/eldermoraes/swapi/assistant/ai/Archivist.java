package com.eldermoraes.swapi.assistant.ai;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.quarkiverse.langchain4j.mcp.runtime.McpToolBox;

/**
 * Answers questions using tools discovered from the remote swapi.build MCP
 * server. The tools are not declared here: the server describes its own
 * capabilities, and @McpToolBox("swapi") points at the client configured in
 * application.properties.
 */
@RegisterAiService
@SystemMessage(Prompts.SYSTEM_MESSAGE)
public interface Archivist {

    @McpToolBox("swapi")
    @UserMessage("{question}")
    String ask(String question);
}
