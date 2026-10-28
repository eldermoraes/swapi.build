package com.eldermoraes.seo.md;

import com.eldermoraes.seo.ssr.ServerRendering;
import com.eldermoraes.seo.ssr.SsrPages;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * An empty render or a converter that throws gives 503, never an empty copy
 * (2.14.0, review item 3), in the mold of ServerRenderingFailureTest: the
 * render of /docs/mcp throws (ServerRendering logs it and gives nothing) and
 * the converter throws on /about (MarkdownCopies logs it). /llms-full.txt is
 * 503 as a whole, since a "full" file without a page misleads the agent. The
 * HTML of the pages is not touched, and a 503 never enters the cache.
 */
@QuarkusTest
@TestProfile(MarkdownCopiesFailureTest.FailingProfile.class)
class MarkdownCopiesFailureTest {

    public static class FailingProfile implements QuarkusTestProfile {
        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(ThrowingPages.class, ThrowingCopies.class);
        }
    }

    // No @Priority: enabled only by this profile, never in the other tests.
    @Alternative
    @ApplicationScoped
    public static class ThrowingPages extends SsrPages {
        @Override
        public String mcp(String siteRoot) {
            throw new IllegalStateException("broken on purpose");
        }
    }

    @Alternative
    @ApplicationScoped
    public static class ThrowingCopies extends MarkdownCopies {
        @Override
        protected String convert(String main, String siteRoot) {
            if (main.contains("data-ssr-page=\"about\"")
                    || main.contains("data-ssr-type=\"people\" data-ssr-id=\"2\"")) {
                throw new IllegalStateException("converter broken on purpose");
            }
            return super.convert(main, siteRoot);
        }
    }

    @Inject
    MarkdownCopies copies;

    private final List<LogRecord> errors = new CopyOnWriteArrayList<>();
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.SEVERE.intValue()) {
                errors.add(record);
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    @BeforeEach
    void listen() {
        Logger.getLogger(ServerRendering.class.getName()).addHandler(capture);
        Logger.getLogger(MarkdownCopies.class.getName()).addHandler(capture);
    }

    @AfterEach
    void stop() {
        Logger.getLogger(ServerRendering.class.getName()).removeHandler(capture);
        Logger.getLogger(MarkdownCopies.class.getName()).removeHandler(capture);
    }

    private static void assertUnavailable(Response response, String path) {
        Assertions.assertEquals(503, response.statusCode(), path);
        Assertions.assertTrue(response.contentType().startsWith("text/plain"), path + ": " + response.contentType());
        Assertions.assertEquals(List.of("no-cache"), response.headers().getValues("Cache-Control"), path);
    }

    /** The message as written to the log (errorf: a printf format and its arguments). */
    private static String text(LogRecord record) {
        Object[] args = record.getParameters();
        return args == null || args.length == 0 ? record.getMessage() : String.format(record.getMessage(), args);
    }

    /** An ERROR with exactly this message, carrying the exception thrown on purpose. */
    private void assertLogged(String message, String cause) {
        Assertions.assertTrue(errors.stream().anyMatch(r -> message.equals(text(r))
                        && r.getThrown() != null && cause.equals(r.getThrown().getMessage())),
                "no ERROR \"" + message + "\" caused by \"" + cause + "\": "
                        + errors.stream().map(MarkdownCopiesFailureTest::text).toList());
    }

    @Test
    void copyIs503WhenTheRenderFails() {
        Response response = given().when().get("/docs/mcp.md");
        assertUnavailable(response, "/docs/mcp.md");
        Assertions.assertEquals("Markdown copy unavailable\n", response.body().asString());
        assertLogged("Server render of page mcp failed: serving the shell without content", "broken on purpose");
    }

    @Test
    void copyIs503WhenTheConverterThrows() {
        assertUnavailable(given().when().get("/about.md"), "/about.md");
        assertLogged("Markdown copy of /about failed: answering 503", "converter broken on purpose");
    }

    // The spa filter stamps the HTML Cache-Control on /resource/{type}/{id}...:
    // a failed record copy must still carry exactly one, no-cache.
    @Test
    void recordCopyIs503WithOneNoCacheWhenTheConverterThrows() {
        Response response = given().when().get("/resource/people/2.md");
        assertUnavailable(response, "/resource/people/2.md");
        Assertions.assertEquals("Markdown copy unavailable\n", response.body().asString());
        assertLogged("Markdown copy of /resource/people/2 failed: answering 503", "converter broken on purpose");
        // HEAD takes the same road.
        Assertions.assertEquals(List.of("no-cache"),
                given().when().head("/resource/people/2.md").headers().getValues("Cache-Control"));
    }

    @Test
    void is503WhenACopyFails() {
        assertUnavailable(given().when().get("/llms-full.txt"), "/llms-full.txt");
    }

    @Test
    void the503IsNoCacheAndNeverCached() {
        String root = "https://failing.example";
        given().header("X-Forwarded-Proto", "https").header("X-Forwarded-Host", "failing.example")
                .when().get("/docs/mcp.md").then().statusCode(503);
        given().header("X-Forwarded-Proto", "https").header("X-Forwarded-Host", "failing.example")
                .when().get("/llms-full.txt").then().statusCode(503);
        Assertions.assertFalse(copies.isCached(root, "/docs/mcp"));
        Assertions.assertFalse(copies.isCached(root, MarkdownCopies.LLMS_FULL));
        // A copy that works on the same root is cached as usual.
        given().header("X-Forwarded-Proto", "https").header("X-Forwarded-Host", "failing.example")
                .when().get("/docs.md").then().statusCode(200);
        Assertions.assertTrue(copies.isCached(root, "/docs"));
    }

    @Test
    void theOtherCopiesStillWork() {
        given().when().get("/docs.md").then().statusCode(200).body(containsString("/api/people/{id}"));
        given().when().get("/resource/films/1.md").then().statusCode(200).body(containsString("George Lucas"));
    }

    @Test
    void htmlStillWorksWhenTheCopyFails() {
        given().accept("text/html").when().get("/about").then()
                .statusCode(200)
                .body(containsString("data-ssr-page=\"about\""));
        given().accept("text/html").when().get("/docs/mcp").then()
                .statusCode(200)
                .body(containsString(ServerRendering.EMPTY_MAIN));
    }
}
