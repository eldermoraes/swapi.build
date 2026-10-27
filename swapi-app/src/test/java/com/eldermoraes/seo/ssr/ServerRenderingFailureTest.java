package com.eldermoraes.seo.ssr;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import org.junit.jupiter.api.AfterEach;
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
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A render that throws in production never takes the site down (slice E,
 * 2.11.0): the page leaves as the shell without the marker and with the empty
 * <main> (the client shows its static error block, the content probe fails,
 * which is a rollback), the log has an ERROR with the page name, and /api and
 * /mcp keep answering.
 */
@QuarkusTest
@TestProfile(ServerRenderingFailureTest.ThrowingProfile.class)
class ServerRenderingFailureTest {

    public static class ThrowingProfile implements QuarkusTestProfile {
        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(ThrowingPages.class);
        }
    }

    /** The MCP page throws in its render; the others are the real ones. */
    // No @Priority: enabled only by this profile, never in the other tests.
    @Alternative
    @ApplicationScoped
    public static class ThrowingPages extends SsrPages {
        @Override
        public String mcp(String siteRoot) {
            throw new IllegalStateException("broken on purpose");
        }
    }

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
    }

    @AfterEach
    void stop() {
        Logger.getLogger(ServerRendering.class.getName()).removeHandler(capture);
    }

    @Test
    void aFailedRenderServesTheShellWithoutTheMarkerAndLogsThePage() {
        given().accept("text/html").when().get("/docs/mcp").then()
                .statusCode(200)
                .contentType(containsString("text/html"))
                .body(containsString(ServerRendering.EMPTY_MAIN))
                .body(not(containsString("data-ssr-")))
                .body(containsString("<title>"));
        assertTrue(errors.stream().anyMatch(r -> String.valueOf(r.getMessage()).contains("mcp")
                        || String.valueOf(r.getParameters() == null ? "" : List.of(r.getParameters())).contains("mcp")),
                "no ERROR naming the page: " + errors.stream().map(LogRecord::getMessage).toList());
    }

    @Test
    void theOtherPagesStillRender() {
        given().accept("text/html").when().get("/about").then()
                .statusCode(200)
                .body(containsString("data-ssr-page=\"about\""));
    }

    @Test
    void theApiAndTheMcpServerKeepAnswering() {
        given().when().get("/api/people/1").then()
                .statusCode(200)
                .body(containsString("Luke Skywalker"));
        given()
                .contentType("application/json")
                .accept("application/json, text/event-stream")
                .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\","
                        + "\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}")
        .when()
                .post("/mcp")
        .then()
                .statusCode(200)
                .body(containsString("swapi.build"));
    }
}
