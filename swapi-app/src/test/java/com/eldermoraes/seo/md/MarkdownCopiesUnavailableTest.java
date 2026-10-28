package com.eldermoraes.seo.md;

import com.eldermoraes.seo.ssr.SsrPages;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * Without the OpenAPI document, /docs has no {@code <main>} (SsrPages.docs()
 * is empty, OpenApiDocsUnavailableTest): its copy is 503, never an empty
 * Markdown, and the other copies still answer.
 */
@QuarkusTest
@TestProfile(MarkdownCopiesUnavailableTest.NoOpenApiProfile.class)
class MarkdownCopiesUnavailableTest {

    public static class NoOpenApiProfile implements QuarkusTestProfile {
        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(NoDocsPages.class);
        }
    }

    // No @Priority: enabled only by this profile.
    @Alternative
    @ApplicationScoped
    public static class NoDocsPages extends SsrPages {
        @Override
        public Optional<String> docs() {
            return Optional.empty();
        }
    }

    @Test
    void docsCopyIs503WithoutOpenApi() {
        Response response = given().when().get("/docs.md");
        Assertions.assertEquals(503, response.statusCode());
        Assertions.assertEquals(List.of("no-cache"), response.headers().getValues("Cache-Control"));
        Assertions.assertEquals("Markdown copy unavailable\n", response.body().asString());
    }

    @Test
    void theOtherCopiesStillAnswer() {
        given().when().get("/about.md").then().statusCode(200).body(containsString("Why build another one?"));
    }
}
