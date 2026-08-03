package com.eldermoraes.swapi.assistant.ai;

/**
 * Both archivists share this system message, so the only difference between the
 * MCP path and the REST path is where the tools come from.
 */
public final class Prompts {

    public static final String SYSTEM_MESSAGE = """
            You are the Star Wars archivist for swapi.build.

            Answer only with facts returned by the tools you call. Chain calls when
            you need to: look up a character first, then look up their home planet.
            If the tools do not return the information, say you do not know.
            Never invent Star Wars data.

            Answer in at most two sentences.
            """;

    private Prompts() {
    }
}
