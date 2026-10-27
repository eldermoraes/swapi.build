package com.eldermoraes.seo.ssr;

import com.eldermoraes.seo.SeoSiteFile;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules of the server-rendered templates (slice E, 2.11.0), checked on the
 * files themselves: a template for every ssr page, nothing that turns escaping
 * off, and tags that declare their parameters so the build validates the calls.
 */
class SsrTemplatesTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates");
    private static final Path JAVA = Path.of("src/main/java");

    // .raw |raw .safe |safe (with or without spaces) and the {| |} literal block.
    private static final Pattern UNESCAPED = Pattern.compile("(?:[.|]\\s*(?:raw|safe)\\b)|\\{\\|");
    private static final Pattern PARAM = Pattern.compile("\\{@[\\w.$<>, ]+ (\\w+)}");
    private static final Pattern USE = Pattern.compile("\\{\\s*(\\w+)[^}]*}");

    @Test
    void everySsrPageHasItsTemplate() {
        for (SeoSiteFile.Page page : SeoSiteFile.load().pages()) {
            if (page.ssr()) {
                assertTrue(Files.isRegularFile(TEMPLATES.resolve("ssr/" + page.page() + ".html")),
                        "seo/site.json marks " + page.page() + " ssr but templates/ssr/" + page.page() + ".html is missing");
            }
        }
    }

    @Test
    void noTemplateTurnsEscapingOff() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path file : files(TEMPLATES)) {
            Matcher m = UNESCAPED.matcher(Files.readString(file));
            while (m.find()) {
                found.add(TEMPLATES.relativize(file) + ": " + m.group());
            }
        }
        assertEquals(List.of(), found);
    }

    @Test
    void theRuleCatchesEveryForm() {
        for (String bad : List.of("{x.raw}", "{x|raw}", "{x.safe}", "{x | safe}", "{| literal |}")) {
            assertTrue(UNESCAPED.matcher(bad).find(), bad);
        }
        assertFalse(UNESCAPED.matcher("{view.rawSize} draws a safety net").find());
    }

    @Test
    void everyTagDeclaresTheParametersItUses() throws IOException {
        List<Path> tags = files(TEMPLATES.resolve("tags"));
        assertFalse(tags.isEmpty(), "no tags in templates/tags");
        for (Path tag : tags) {
            String text = Files.readString(tag);
            List<String> declared = new ArrayList<>();
            Matcher p = PARAM.matcher(text);
            while (p.find()) {
                declared.add(p.group(1));
            }
            assertFalse(declared.isEmpty(), tag + " declares no parameter");
            Matcher u = USE.matcher(text.replaceAll("\\{@[^}]*}", ""));
            while (u.find()) {
                String name = u.group(1);
                if (!name.equals("for") && !name.equals("if") && !name.equals("else") && !name.equals("each")) {
                    assertTrue(declared.contains(name) || name.equals("row"), tag + " uses {" + name + "} without declaring it");
                }
            }
        }
    }

    @Test
    void noJavaCodeBuildsARawString() throws IOException {
        for (Path file : files(JAVA)) {
            assertFalse(Files.readString(file).contains("RawString"), file + " mentions io.quarkus.qute.RawString");
        }
    }

    private static List<Path> files(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).sorted().toList();
        }
    }
}
