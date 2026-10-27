package com.eldermoraes;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guard: the native binary carries the JSON the app reads at runtime. The suite
 * runs on the JVM, where every resource is on the classpath, so no @QuarkusTest
 * sees a file missing from the native image; this reads the key itself. Without
 * seo/*.json the binary does not start (SeoMetadataRegistry fails at startup,
 * and swapi-update reports "is not answering").
 */
class NativeResourcesTest {

    @Test
    void nativeImageIncludesTheDataAndTheSeoFile() throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(
                Path.of("src/main/resources/application.properties"), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        String includes = properties.getProperty("quarkus.native.resources.includes", "");
        List<String> globs = Arrays.stream(includes.split(",")).map(String::trim).toList();
        assertTrue(globs.contains("data/*.json"), includes);
        assertTrue(globs.contains("seo/*.json"), includes);
    }
}
