package com.eldermoraes.mcp;

import io.quarkiverse.mcp.server.ResourceManager;
import io.quarkiverse.mcp.server.ResourceResponse;
import io.quarkiverse.mcp.server.TextResourceContents;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The skill this server publishes (2.17.0), star-wars-research, as the Skills
 * over MCP extension serves it (SwapiSkillsExtension): its files read once at
 * startup from mcp/skills/star-wars-research/ on the classpath, kept as bytes,
 * and registered as concrete resources skill://star-wars-research/SKILL.md and
 * skill://star-wars-research/references/relations.md (text/markdown).
 *
 * Everything a client checks comes from those bytes: the size and SHA-256 of
 * the manifest, and the text resources/read serves. The text is the bytes
 * decoded as strict UTF-8, so encoding it again gives the same bytes; a file
 * that is not valid UTF-8, starts with a BOM or uses CRLF is refused.
 *
 * The files are a fixed list, not a directory listing: a native image cannot
 * list a classpath directory (quarkus.native.resources.includes carries
 * mcp/skills/**, NativeResourcesTest).
 *
 * The frontmatter is read by a small reader of its own, one "key: value" per
 * line, no new dependency. A client reads it with a YAML parser and compares
 * it with the entry field by field, so a value YAML would read differently
 * (quoted, with a comment, a second ": ", a boolean, a number or a
 * timestamp, or with a character YAML takes as a line break or does not
 * print) is refused rather than published as a skill nobody can verify. Any refusal fails the
 * boot: name and description present, no other key (no allowed-tools), the
 * name equal to the directory and within the Agent Skills naming rules, a
 * description of at most 1024 characters, every file under 100 KB (the limit
 * of Claude Code per text file).
 *
 * Changing one byte of a file changes its digest, and every client that
 * approved the skill asks its user to approve it again.
 */
@ApplicationScoped
public class SwapiSkills {

    public static final String NAME = "star-wars-research";
    static final String SCHEME = "skill://";
    static final String MIME = "text/markdown";
    static final String ROOT = "mcp/skills/";
    static final String SKILL_FILE = "SKILL.md";
    /** Relative to the skill's directory, SKILL.md first. */
    static final List<String> FILES = List.of(SKILL_FILE, "references/relations.md");

    static final int MAX_FILE_BYTES = 100_000;
    static final int MAX_DESCRIPTION = 1024;
    static final int MAX_NAME = 64;

    private static final Set<String> KEYS = Set.of("name", "description");
    private static final Pattern NAME_RULE = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern KEY_LINE = Pattern.compile("([a-z][a-z0-9-]*):(?: (.*))?");
    // The words a YAML 1.1 or 1.2 resolver reads as a boolean or null, in any case.
    private static final Pattern NOT_A_STRING_WORD = Pattern.compile("(?i)~|null|true|false|yes|no|on|off|y|n");
    // A date opens every YAML timestamp, also the ones with spaces.
    private static final Pattern DATE_FIRST = Pattern.compile("[0-9]{4}-[0-9]{1,2}-[0-9]{1,2}(?:[^0-9].*)?");
    private static final String INDICATORS = "-?:,[]{}#&*!|>'\"%@`";

    /** One file of a skill: its path in the directory, its URI, its bytes and what derives from them. */
    public record SkillFile(String path, String uri, byte[] bytes, String text, String digest) {
        public int size() {
            return bytes.length;
        }
    }

    /** A skill: the URI of its SKILL.md, the frontmatter in file order, its files, SKILL.md first. */
    public record Skill(String uri, Map<String, String> frontmatter, List<SkillFile> files) {
    }

    @Inject ResourceManager resources;

    // Written once on the main thread by the StartupEvent observer, read by
    // request threads. Quarkus opens the HTTP socket only after the
    // StartupEvent observers return (VertxHttpProcessor.openSocket consumes
    // the ApplicationStartBuildItem of the startup event), so no request finds
    // it null; volatile publishes it to those threads without relying on that.
    private volatile Skill skill;

    void register(@Observes StartupEvent event) {
        Skill loaded = parse(NAME, readClasspath(NAME, FILES));
        skill = loaded;
        for (SkillFile file : loaded.files()) {
            boolean main = file.path().equals(SKILL_FILE);
            ResourceManager.ResourceDefinition resource = resources
                    .newResource(main ? loaded.frontmatter().get("name") : NAME + "/" + file.path())
                    .setUri(file.uri())
                    .setMimeType(MIME)
                    .setSize(file.size())
                    .setHandler(args -> new ResourceResponse(new TextResourceContents(file.uri(), file.text(), MIME)));
            if (main) {
                resource.setDescription(loaded.frontmatter().get("description"));
            } else {
                resource.setTitle("Star Wars research skill: " + file.path())
                        .setDescription("Supporting file of the skill " + NAME + ": the relation names expand "
                                + "accepts and the field names fields accepts, per resource type.");
            }
            resource.register();
        }
    }

    /** The skill, loaded at startup. */
    public Skill skill() {
        Skill loaded = skill;
        if (loaded == null) {
            throw new IllegalStateException("the skill is read at startup");
        }
        return loaded;
    }

    /** The bytes of each file, by its path in the skill's directory; null for a file that is missing. */
    static Map<String, byte[]> readClasspath(String directory, List<String> paths) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        for (String path : paths) {
            try (InputStream in = loader.getResourceAsStream(ROOT + directory + "/" + path)) {
                files.put(path, in == null ? null : in.readNBytes(MAX_FILE_BYTES + 1));
            } catch (IOException e) {
                throw new UncheckedIOException("skill " + directory + ": cannot read " + path, e);
            }
        }
        return files;
    }

    /**
     * Validates the files of the skill in directory and derives the skill
     * from their bytes. IllegalStateException, naming the skill, for any
     * refusal.
     */
    static Skill parse(String directory, Map<String, byte[]> files) {
        if (directory == null || directory.length() > MAX_NAME || !NAME_RULE.matcher(directory).matches()) {
            throw invalid(directory, "the directory name breaks the Agent Skills naming rules (1 to "
                    + MAX_NAME + " characters, a-z, 0-9 and single hyphens inside)");
        }
        if (!files.containsKey(SKILL_FILE)) {
            throw invalid(directory, "has no " + SKILL_FILE);
        }
        List<SkillFile> out = new ArrayList<>();
        Map<String, String> frontmatter = null;
        List<String> order = new ArrayList<>(files.keySet());
        order.remove(SKILL_FILE);
        order.addFirst(SKILL_FILE);
        for (String path : order) {
            byte[] bytes = files.get(path);
            if (bytes == null) {
                throw invalid(directory, path + " is missing from the classpath");
            }
            if (bytes.length >= MAX_FILE_BYTES) {
                throw invalid(directory, path + " has " + bytes.length + " bytes or more; the limit is under "
                        + MAX_FILE_BYTES);
            }
            String text = utf8(directory, path, bytes);
            if (text.startsWith("﻿")) {
                throw invalid(directory, path + " starts with a byte order mark");
            }
            if (text.indexOf('\r') >= 0) {
                throw invalid(directory, path + " has a carriage return; use LF line ends");
            }
            if (!text.endsWith("\n")) {
                throw invalid(directory, path + " does not end with a newline");
            }
            if (path.equals(SKILL_FILE)) {
                frontmatter = frontmatter(directory, text);
            }
            out.add(new SkillFile(path, SCHEME + directory + "/" + path, bytes, text, sha256(bytes)));
        }
        return new Skill(SCHEME + directory + "/" + SKILL_FILE, frontmatter, List.copyOf(out));
    }

    private static String utf8(String directory, String path, byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw invalid(directory, path + " is not valid UTF-8");
        }
    }

    private static Map<String, String> frontmatter(String directory, String text) {
        if (!text.startsWith("---\n")) {
            throw invalid(directory, SKILL_FILE + " does not start with a --- frontmatter");
        }
        int end = text.indexOf("\n---\n", 3);
        if (end < 0) {
            throw invalid(directory, SKILL_FILE + " frontmatter is not closed by ---");
        }
        String block = text.substring(4, end + 1);
        plainCharacters(directory, block);
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : block.split("\n")) {
            var m = KEY_LINE.matcher(line);
            if (!m.matches()) {
                throw invalid(directory, "frontmatter line '" + line + "' is not 'key: value'");
            }
            String key = m.group(1);
            String value = m.group(2) == null ? "" : m.group(2);
            if (!KEYS.contains(key)) {
                throw invalid(directory, "frontmatter key '" + key + "' is not allowed; only name and description");
            }
            if (values.containsKey(key)) {
                throw invalid(directory, "frontmatter key '" + key + "' is repeated");
            }
            if (value.isBlank()) {
                throw invalid(directory, "frontmatter " + key + " is empty");
            }
            plainScalar(directory, key, value);
            values.put(key, value);
        }
        for (String key : List.of("name", "description")) {
            if (!values.containsKey(key)) {
                throw invalid(directory, "frontmatter has no " + key);
            }
        }
        String name = values.get("name");
        if (!name.equals(directory)) {
            throw invalid(directory, "frontmatter name '" + name + "' is not the directory name");
        }
        int length = values.get("description").codePointCount(0, values.get("description").length());
        if (length > MAX_DESCRIPTION) {
            throw invalid(directory, "description has " + length + " characters; the limit is " + MAX_DESCRIPTION);
        }
        Map<String, String> ordered = new LinkedHashMap<>();
        ordered.put("name", name);
        ordered.put("description", values.get("description"));
        return Collections.unmodifiableMap(ordered);
    }

    /**
     * A value a YAML parser reads as exactly this string. Not a list of the
     * forms YAML resolves (numbers alone come in bases, separators, signs and
     * sexagesimal), but the shapes they share: every implicit non-string form
     * of YAML 1.1 and 1.2 is either one of a few words (booleans, null), or a
     * single token that does not start with a letter (numbers in any base,
     * .inf, .nan, ~, <<, =, a date or a timestamp with a t or T), or a
     * timestamp with spaces, which starts with a date. A value of one token
     * that does not start with a letter is refused even when YAML would read
     * it as a string ("3d"): erring on the side of refusal fails the boot,
     * never a client's check.
     */
    private static void plainScalar(String directory, String key, String value) {
        boolean oneToken = value.indexOf(' ') < 0;
        boolean ok = !Character.isWhitespace(value.charAt(0))
                && !Character.isWhitespace(value.charAt(value.length() - 1))
                && INDICATORS.indexOf(value.charAt(0)) < 0
                && !value.contains(": ")
                && !value.contains(" #")
                && !value.endsWith(":")
                && !NOT_A_STRING_WORD.matcher(value).matches()
                && !(oneToken && !Character.isLetter(value.codePointAt(0)))
                && !DATE_FIRST.matcher(value).matches();
        if (!ok) {
            throw invalid(directory, "frontmatter " + key + " must be a plain YAML string (no quotes, no leading "
                    + "indicator, no ': ', no ' #', not a boolean, null, number or timestamp, and one word only "
                    + "if it starts with a letter): '" + value + "'");
        }
    }

    /**
     * Every character of the frontmatter must be one YAML reads as itself
     * inside a line. NEL (U+0085) and U+2028 / U+2029 are line breaks to a
     * YAML parser and not to the split on LF here; tabs and the other control
     * characters (C0, DEL, C1) are separators or not printable to YAML; U+FEFF
     * is a byte order mark to it, and U+FFFE / U+FFFF are not printable.
     */
    private static void plainCharacters(String directory, String frontmatter) {
        frontmatter.codePoints().forEach(c -> {
            int type = Character.getType(c);
            boolean breaksOrHides = (c != '\n' && type == Character.CONTROL)
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR
                    || type == Character.SURROGATE
                    || c == 0xFEFF || c == 0xFFFE || c == 0xFFFF;
            if (breaksOrHides) {
                throw invalid(directory, SKILL_FILE + " frontmatter has the character "
                        + String.format("U+%04X", c) + "; only printable characters on LF-ended lines");
            }
        });
    }

    private static String sha256(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static IllegalStateException invalid(String directory, String why) {
        return new IllegalStateException("skill " + directory + ": " + why);
    }
}
