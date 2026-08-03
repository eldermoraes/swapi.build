package com.eldermoraes.swapi.assistant.ai;

import com.eldermoraes.swapi.assistant.tools.SwapiTools;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

/**
 * Same prompt and same job as {@link Archivist}, but the tools are local beans
 * calling the public REST API instead of being discovered from the MCP server.
 */
@RegisterAiService(tools = SwapiTools.class)
@SystemMessage(Prompts.SYSTEM_MESSAGE)
public interface RestArchivist {

    @UserMessage("{question}")
    String ask(String question);
}
