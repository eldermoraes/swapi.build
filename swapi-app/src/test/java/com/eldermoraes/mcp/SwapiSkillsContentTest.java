package com.eldermoraes.mcp;

import com.eldermoraes.seo.KnownEntities;
import com.eldermoraes.seo.ssr.RecordFields;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2.17.0: what the files of the skill star-wars-research may say, read from
 * the classpath as the server reads them.
 *
 * The skill is text that enters the model of whoever connects to a public
 * server, and its bytes are fixed by the digest every client checks: no URL
 * or domain (the binary carries none, and the host changes per request), no
 * count of records written as a literal (the FAQ rule), only the four tools.
 * references/relations.md is checked against the code: a relation or a field
 * the tools accept without a line in the file fails here.
 *
 * Also the refusals of the loader, which fail the boot.
 */
@QuarkusTest
class SwapiSkillsContentTest {

    private static final List<String> TOOLS = List.of("sw_search", "sw_get", "sw_list", "sw_random");

    // The FAQ's rule (FaqFile.LITERAL_COUNT), with the singular names too:
    // "82 people", "6 films", "1 planet".
    private static final Pattern LITERAL_COUNT = Pattern.compile("(?i)\\b\\d+[\\s\\u00A0]+(people|persons?|"
            + "films?|planets?|species|starships?|vehicles?|characters?|records?|entities|entity)\\b");

    @Inject
    RecordExpansion expansion;

    private static Map<String, byte[]> shipped() {
        return SwapiSkills.readClasspath(SwapiSkills.NAME, SwapiSkills.FILES);
    }

    private static String text(String path) {
        return new String(shipped().get(path), StandardCharsets.UTF_8);
    }

    @Test
    void theShippedSkillLoads() {
        SwapiSkills.Skill skill = SwapiSkills.parse(SwapiSkills.NAME, shipped());
        assertEquals("skill://star-wars-research/SKILL.md", skill.uri());
        assertEquals(List.of("name", "description"), List.copyOf(skill.frontmatter().keySet()));
        assertEquals(List.of("SKILL.md", "references/relations.md"),
                skill.files().stream().map(SwapiSkills.SkillFile::path).toList());
    }

    @Test
    void noUrlNoDomain() {
        for (String path : SwapiSkills.FILES) {
            String text = text(path).toLowerCase(Locale.ROOT);
            for (String forbidden : List.of("http://", "https://", "swapi.build", "localhost", "www.")) {
                assertFalse(text.contains(forbidden), path + " contains " + forbidden);
            }
        }
    }

    @Test
    void noCountOfRecordsAsALiteral() {
        for (String path : SwapiSkills.FILES) {
            Matcher m = LITERAL_COUNT.matcher(text(path));
            if (m.find()) {
                org.junit.jupiter.api.Assertions.fail(path + ": \"" + m.group() + "\" is a count of records written "
                        + "as a literal; the data can change, the digest of the file cannot");
            }
        }
    }

    @Test
    void eachFileIsUnderTheClientLimit() {
        for (Map.Entry<String, byte[]> file : shipped().entrySet()) {
            assertTrue(file.getValue().length < SwapiSkills.MAX_FILE_BYTES, file.getKey());
        }
    }

    @Test
    void skillBodyIsShortAndNamesTheToolsAndTheReference() {
        String skill = text("SKILL.md");
        int end = skill.indexOf("\n---\n", 4);
        String body = skill.substring(end + "\n---\n".length());
        long lines = body.lines().count();
        assertTrue(lines < 100, "SKILL.md body has " + lines + " lines");
        for (String tool : TOOLS) {
            assertTrue(skill.contains(tool), "SKILL.md does not name " + tool);
        }
        assertTrue(skill.contains("references/relations.md"), "SKILL.md does not point at references/relations.md");
        // The skill speaks of this server's tools only: no grant of tools, no
        // script, nothing to run on the client.
        for (String forbidden : List.of("allowed-tools", "```bash", "```sh", "```python", "scripts/")) {
            assertFalse(skill.contains(forbidden), "SKILL.md contains " + forbidden);
        }
    }

    // ---- drift: references/relations.md against the code ----

    /** Type -> the names of one "- key: ..." line of its section, with the target type of each, if any. */
    private static Map<String, Map<String, String>> section(String text, String key) {
        Map<String, Map<String, String>> byType = new LinkedHashMap<>();
        Pattern item = Pattern.compile("`([A-Za-z_]+)`(?: \\(([A-Z]+)\\))?");
        String type = null;
        for (String line : text.lines().toList()) {
            if (line.startsWith("## ")) {
                type = line.substring(3).strip();
                continue;
            }
            String prefix = "- " + key + ": ";
            if (type != null && line.startsWith(prefix)) {
                Map<String, String> names = new LinkedHashMap<>();
                for (String part : line.substring(prefix.length()).split(", ")) {
                    Matcher m = item.matcher(part.strip());
                    assertTrue(m.matches(), "references/relations.md, " + type + ", " + key + ": cannot read '"
                            + part + "'");
                    names.put(m.group(1), m.group(2));
                }
                assertFalse(byType.containsKey(type), "references/relations.md: two " + key + " lines for " + type);
                byType.put(type, names);
            }
        }
        return byType;
    }

    private static Set<String> typesInTheFile(String text) {
        Set<String> types = new LinkedHashSet<>();
        text.lines().filter(l -> l.startsWith("## ")).forEach(l -> types.add(l.substring(3).strip()));
        return types;
    }

    @Test
    void relationsFileHasOneSectionPerType() {
        Set<String> expected = new LinkedHashSet<>();
        for (SwapiTools.SwResource resource : SwapiTools.SwResource.values()) {
            expected.add(resource.name());
        }
        assertEquals(expected, typesInTheFile(text("references/relations.md")));
    }

    @Test
    void relationsFileListsExactlyTheRelationsExpandAccepts() {
        Map<String, Map<String, String>> file = section(text("references/relations.md"), "expand");
        List<String> problems = new ArrayList<>();
        for (SwapiTools.SwResource resource : SwapiTools.SwResource.values()) {
            Map<String, String> listed = file.getOrDefault(resource.name(), Map.of());
            Map<String, String> code = new LinkedHashMap<>();
            for (RecordFields.Relation relation : RecordFields.relations(resource.type())) {
                code.put(relation.key(), relation.type().toUpperCase(Locale.ROOT));
            }
            for (Map.Entry<String, String> relation : code.entrySet()) {
                if (!listed.containsKey(relation.getKey())) {
                    problems.add(resource.name() + ": missing `" + relation.getKey() + "` (" + relation.getValue()
                            + ") on its \"- expand:\" line");
                } else if (!relation.getValue().equals(listed.get(relation.getKey()))) {
                    problems.add(resource.name() + ": `" + relation.getKey() + "` points to " + relation.getValue()
                            + ", the file says " + listed.get(relation.getKey()));
                }
            }
            for (String name : listed.keySet()) {
                if (!code.containsKey(name)) {
                    problems.add(resource.name() + ": `" + name + "` is not a relation expand accepts");
                }
            }
            // The order of RecordFields, which is also the order of the
            // "expanded" object a call returns.
            if (code.keySet().equals(listed.keySet())
                    && !List.copyOf(code.keySet()).equals(List.copyOf(listed.keySet()))) {
                problems.add(resource.name() + ": list the expand names in the order " + code.keySet());
            }
        }
        assertTrue(problems.isEmpty(), "references/relations.md drifted from RecordFields.relations:\n  "
                + String.join("\n  ", problems));
    }

    @Test
    void relationsFileListsExactlyTheFieldsFieldsAccepts() {
        Map<String, Map<String, String>> file = section(text("references/relations.md"), "fields");
        List<String> problems = new ArrayList<>();
        for (SwapiTools.SwResource resource : SwapiTools.SwResource.values()) {
            Set<String> listed = file.getOrDefault(resource.name(), Map.of()).keySet();
            Set<String> code = expansion.validFields(resource.type());
            for (String field : new TreeSet<>(code)) {
                if (!listed.contains(field)) {
                    problems.add(resource.name() + ": missing `" + field + "` on its \"- fields:\" line");
                }
            }
            for (String field : listed) {
                if (!code.contains(field)) {
                    problems.add(resource.name() + ": `" + field + "` is not a field fields accepts");
                }
            }
        }
        assertTrue(problems.isEmpty(), "references/relations.md drifted from RecordExpansion.validFields:\n  "
                + String.join("\n  ", problems));
        assertEquals(Set.copyOf(KnownEntities.TYPES), Set.copyOf(
                java.util.Arrays.stream(SwapiTools.SwResource.values()).map(SwapiTools.SwResource::type).toList()));
    }

    // ---- refusals of the loader (each one fails the boot) ----

    private static Map<String, byte[]> skill(String frontmatter) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", ("---\n" + frontmatter + "---\n\n# Body\n").getBytes(StandardCharsets.UTF_8));
        return files;
    }

    private static void refused(String why, String directory, Map<String, byte[]> files) {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> SwapiSkills.parse(directory, files), why);
        assertTrue(e.getMessage().contains("skill " + directory), why + ": " + e.getMessage());
    }

    @Test
    void aMinimalSkillLoads() {
        SwapiSkills.Skill skill = SwapiSkills.parse("my-skill", skill("name: my-skill\ndescription: Does a thing.\n"));
        assertEquals("skill://my-skill/SKILL.md", skill.uri());
        assertEquals(Map.of("name", "my-skill", "description", "Does a thing."), skill.frontmatter());
        SwapiSkills.SkillFile file = skill.files().getFirst();
        assertEquals("skill://my-skill/SKILL.md", file.uri());
        assertEquals(file.bytes().length, file.size());
        assertTrue(file.digest().matches("sha256:[0-9a-f]{64}"), file.digest());
        assertEquals("---\nname: my-skill\ndescription: Does a thing.\n---\n\n# Body\n", file.text());
    }

    @Test
    void refusesAMissingNameOrDescription() {
        refused("no name", "my-skill", skill("description: Does a thing.\n"));
        refused("no description", "my-skill", skill("name: my-skill\n"));
        refused("empty description", "my-skill", skill("name: my-skill\ndescription:\n"));
        refused("blank description", "my-skill", skill("name: my-skill\ndescription:    \n"));
    }

    @Test
    void refusesAnExtraKey() {
        refused("allowed-tools", "my-skill", skill("name: my-skill\ndescription: Does a thing.\nallowed-tools: Bash\n"));
        refused("license", "my-skill", skill("name: my-skill\ndescription: Does a thing.\nlicense: MIT\n"));
        refused("repeated key", "my-skill", skill("name: my-skill\nname: my-skill\ndescription: Does a thing.\n"));
    }

    @Test
    void refusesANameThatIsNotTheDirectory() {
        refused("other name", "my-skill", skill("name: other-skill\ndescription: Does a thing.\n"));
    }

    @Test
    void refusesANameAgainstTheAgentSkillsRules() {
        for (String name : List.of("My-skill", "my_skill", "-my-skill", "my-skill-", "my--skill", "my skill",
                "a".repeat(65))) {
            refused("name '" + name + "'", name, skill("name: " + name + "\ndescription: Does a thing.\n"));
        }
        SwapiSkills.parse("a".repeat(64), skill("name: " + "a".repeat(64) + "\ndescription: Does a thing.\n"));
    }

    @Test
    void refusesADescriptionOver1024Characters() {
        refused("1025", "my-skill", skill("name: my-skill\ndescription: " + "a".repeat(1025) + "\n"));
        SwapiSkills.parse("my-skill", skill("name: my-skill\ndescription: " + "a".repeat(1024) + "\n"));
    }

    // The client parses the frontmatter as YAML and compares it with the
    // entry: a value a YAML parser would read differently from this reader
    // (a quote, a comment, a second colon, a value that is not a string) is
    // refused rather than served as a skill no client can verify.
    @Test
    void refusesAValueYamlWouldReadDifferently() {
        for (String description : List.of("\"Quoted.\"", "'Quoted.'", "Has a # comment", "Has: a colon",
                "Ends with a colon:", "[a, list]", "{a: map}", "> folded", "| literal", "&anchor x", "*alias",
                "!tag x", "@reserved", "`reserved", "%directive", "true", "null", "~", "42", "3.5",
                "Trailing space ", "- dash")) {
            refused("description '" + description + "'", "my-skill",
                    skill("name: my-skill\ndescription: " + description + "\n"));
        }
    }

    @Test
    void refusesAFileOver100KB() {
        Map<String, byte[]> files = skill("name: my-skill\ndescription: Does a thing.\n");
        files.put("references/big.md", ("x".repeat(SwapiSkills.MAX_FILE_BYTES) + "\n")
                .getBytes(StandardCharsets.UTF_8));
        refused("big supporting file", "my-skill", files);
        Map<String, byte[]> big = new LinkedHashMap<>();
        big.put("SKILL.md", ("---\nname: my-skill\ndescription: Does a thing.\n---\n"
                + "x".repeat(SwapiSkills.MAX_FILE_BYTES) + "\n").getBytes(StandardCharsets.UTF_8));
        refused("big SKILL.md", "my-skill", big);
    }

    @Test
    void refusesAMalformedFile() {
        refused("no frontmatter", "my-skill", Map.of("SKILL.md", "# Body\n".getBytes(StandardCharsets.UTF_8)));
        refused("unclosed frontmatter", "my-skill",
                Map.of("SKILL.md", "---\nname: my-skill\ndescription: x\n".getBytes(StandardCharsets.UTF_8)));
        refused("no SKILL.md", "my-skill", Map.of("references/a.md", "a\n".getBytes(StandardCharsets.UTF_8)));
        Map<String, byte[]> bom = new LinkedHashMap<>();
        bom.put("SKILL.md", ("﻿---\nname: my-skill\ndescription: x\n---\n").getBytes(StandardCharsets.UTF_8));
        refused("BOM", "my-skill", bom);
        Map<String, byte[]> crlf = skill("name: my-skill\r\ndescription: x\r\n");
        refused("CRLF", "my-skill", crlf);
        Map<String, byte[]> noNewline = new LinkedHashMap<>();
        noNewline.put("SKILL.md", "---\nname: my-skill\ndescription: x\n---\n# Body".getBytes(StandardCharsets.UTF_8));
        refused("no final newline", "my-skill", noNewline);
        Map<String, byte[]> invalid = skill("name: my-skill\ndescription: x\n");
        invalid.put("references/a.md", new byte[]{'a', (byte) 0xC3, '\n'});
        refused("not UTF-8", "my-skill", invalid);
        Map<String, byte[]> missing = skill("name: my-skill\ndescription: x\n");
        missing.put("references/a.md", null);
        refused("missing file", "my-skill", missing);
    }
}
