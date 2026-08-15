package com.eldermoraes;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
public class ConcurrentBaseUrlTest {

    // Concurrent requests with different hosts must not contaminate each
    // other's URLs — each response carries only the host of its own requester.
    @Test
    public void concurrentRequestsKeepTheirOwnHost() throws Exception {
        int rounds = 200;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < rounds; i++) {
                final String host = (i % 2 == 0) ? "a.example" : "b.example";
                final String other = (i % 2 == 0) ? "b.example" : "a.example";
                results.add(pool.submit(() -> {
                    String body = given()
                            .header("X-Forwarded-Proto", "https")
                            .header("X-Forwarded-Host", host)
                            .when().get("/api/people/1")
                            .then().statusCode(200)
                            .extract().asString();
                    return body.contains("https://" + host + "/api/people/1")
                            && !body.contains(other);
                }));
            }
            for (Future<Boolean> f : results) {
                assertTrue(f.get(30, TimeUnit.SECONDS),
                        "response contaminated with another request's host");
            }
        } finally {
            pool.shutdown();
        }
    }
}
