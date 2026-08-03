package com.eldermoraes.swapi.assistant.dto;

/**
 * @param path which integration answered: "mcp" or "api"
 */
public record Answer(String path, String answer) {
}
