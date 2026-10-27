package com.eldermoraes.seo.ssr;

import io.quarkus.qute.Engine;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fixtures the client tests bind to (2.11.0, slice E1): the {@code <main>}
 * the server draws for each server-rendered page, on preview.example, and the
 * {@code pill} tag. Compared byte for byte; updating them is an explicit act:
 * {@code SSR_FIXTURES_UPDATE=1 ./mvnw test -Dtest=SsrFixturesTest}.
 *
 * The app version the /docs shows is masked as {@code {{VERSION}}}, so a bump
 * does not break the fixture.
 */
@QuarkusTest
class SsrFixturesTest {

    private static final Path DIR = Path.of("src/test/resources/ssr");
    private static final Pattern MAIN = Pattern.compile("(?s)<main id=\"main-content\"[^>]*>.*?</main>");
    private static final boolean UPDATE = "1".equals(System.getenv("SSR_FIXTURES_UPDATE"));

    @Inject
    Engine engine;

    @ParameterizedTest
    @ValueSource(strings = {"home:/", "docs:/docs", "mcp:/docs/mcp", "about:/about"})
    void theMainOfEachPageMatchesItsFixture(String spec) throws IOException {
        String[] parts = spec.split(":", 2);
        String body = given().accept("text/html")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "preview.example")
                .when().get(parts[1]).then().statusCode(200).extract().asString();
        Matcher m = MAIN.matcher(body);
        assertTrue(m.find(), "no <main> on " + parts[1]);
        String version = ConfigProvider.getConfig().getValue("quarkus.application.version", String.class);
        String main = m.group().replace("version " + version + " ", "version {{VERSION}} ") + "\n";
        compare(parts[0] + ".html", main);
    }

    // 2.12.0 (slice E2): the record a client test adopts and navigates to, and
    // the body of the API for the same record, so the client can prove that the
    // <pre> of the page is JSON.stringify(apiBody, null, 2), byte for byte.
    @Test
    void theMainOfARecordAndItsApiBodyMatchTheirFixtures() throws IOException {
        String body = given().accept("text/html")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "preview.example")
                .when().get("/resource/films/1").then().statusCode(200).extract().asString();
        Matcher m = MAIN.matcher(body);
        assertTrue(m.find(), "no <main> on /resource/films/1");
        compare("record-films-1.html", m.group() + "\n");
        String api = given().accept("application/json")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "preview.example")
                .when().get("/api/films/1").then().statusCode(200).extract().asString();
        compare("record-films-1.api.json", api + "\n");
    }

    @Test
    void thePillTagMatchesItsFixture() throws IOException {
        String pill = engine.parse("{#pill label='Get started' href='/docs' kind='solid' /}").render() + "\n";
        compare("pill.html", pill);
    }

    private static void compare(String name, String actual) throws IOException {
        Path file = DIR.resolve(name);
        if (UPDATE) {
            Files.writeString(file, actual, StandardCharsets.UTF_8);
        }
        assertTrue(Files.exists(file), "missing fixture " + file + " (run with SSR_FIXTURES_UPDATE=1)");
        assertEquals(Files.readString(file, StandardCharsets.UTF_8), actual,
                name + " differs from the server; if on purpose, run with SSR_FIXTURES_UPDATE=1");
    }
}
