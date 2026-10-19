package com.eldermoraes;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every /random call used to log "Thread name: ..." at INFO. On the self-hosted
 * origin that is one journal line per request: pure debug noise that fills the
 * journal during a crawler spike. A successful request must log nothing at INFO
 * or above from the application's own packages.
 */
@QuarkusTest
public class RequestLogNoiseRegressionTest {

    private final List<LogRecord> records = new CopyOnWriteArrayList<>();
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    @BeforeEach
    void attach() {
        capture.setLevel(Level.ALL);
        Logger.getLogger("").addHandler(capture);
    }

    @AfterEach
    void detach() {
        Logger.getLogger("").removeHandler(capture);
    }

    @ParameterizedTest
    @ValueSource(strings = {"people", "films", "planets", "species", "starships", "vehicles"})
    void randomEndpointLogsNothingOnSuccess(String resource) {
        records.clear();

        given().when().get("/api/" + resource + "/random").then().statusCode(200);

        List<String> appLines = records.stream()
                .filter(r -> r.getLevel().intValue() >= Level.INFO.intValue())
                .filter(r -> r.getLoggerName() != null && r.getLoggerName().startsWith("com.eldermoraes"))
                .map(r -> r.getLoggerName() + ": " + r.getMessage())
                .toList();
        assertEquals(List.of(), appLines, "a successful /api/" + resource + "/random must not log at INFO");
    }
}
