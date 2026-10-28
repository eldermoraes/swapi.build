package com.eldermoraes.mcp;

import io.quarkiverse.mcp.server.JsonRpcErrorCodes;
import io.quarkiverse.mcp.server.McpException;
import io.quarkiverse.mcp.server.Prompt;
import io.quarkiverse.mcp.server.PromptArg;
import io.quarkiverse.mcp.server.PromptMessage;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The one server prompt (2.13.0, slice F1): character_profile. Claude Code
 * shows it as /mcp__swapi-build__character_profile. It only writes a user
 * message that names the tools; it reads no data.
 *
 * The name is text from the user that lands inside the prompt: at most 80
 * characters after trim, and no control, format or separator character nor
 * double quote (a newline would let it start a new instruction, a quote would
 * close the quotes around it); otherwise invalid params (-32602).
 */
@ApplicationScoped
public class SwapiPrompts {

    static final int MAX_NAME = 80;

    @Prompt(name = "character_profile", title = "Star Wars character profile",
            description = "Builds a short profile of a Star Wars character (Episodes I-VI) with the swapi.build "
                    + "tools: search by name, then sw_get with expand for homeworld, species and films.")
    PromptMessage characterProfile(
            @PromptArg(name = "name", description = "The character's name, e.g. Vader or Luke (up to 80 "
                    + "characters). A name of two words works only in clients that send prompt arguments as "
                    + "JSON; Claude Code splits a slash command on spaces.") String name) {
        // Checked on the raw name: trim() removes every character up to
        // U+0020, so "Luke\u0000" would pass as "Luke".
        if (name != null && name.chars().anyMatch(SwapiPrompts::forbidden)) {
            throw new McpException("name must not contain control, format or separator characters or double quotes",
                    JsonRpcErrorCodes.INVALID_PARAMS);
        }
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty()) {
            throw new McpException("name must not be empty", JsonRpcErrorCodes.INVALID_PARAMS);
        }
        if (clean.length() > MAX_NAME) {
            throw new McpException("name has at most " + MAX_NAME + " characters", JsonRpcErrorCodes.INVALID_PARAMS);
        }
        return PromptMessage.withUserRole("Build a short profile of the Star Wars character \"" + clean
                + "\" with the swapi.build tools: call sw_search with resource PEOPLE and that name, pick the "
                + "matching entity, then call sw_get with its id and expand [\"homeworld\", \"species\", "
                + "\"films\"]. Report the name, homeworld, species and the films in release order. If no "
                + "character matches, say so. Use only data returned by the tools.");
    }

    /**
     * Control characters, the line and paragraph separators (U+2028/U+2029, a
     * new line to a model), the format characters (bidi overrides such as
     * U+202E, zero-width U+200B, U+FEFF, the soft hyphen) and the double quote,
     * which would close the quotes around the name in the message.
     */
    static boolean forbidden(int c) {
        if (Character.isISOControl(c) || c == '"') {
            return true;
        }
        int type = Character.getType(c);
        return type == Character.FORMAT || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR;
    }
}
