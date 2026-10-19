package com.eldermoraes.seo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebAnalyticsTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    @Test
    void noTokenNoBeacon() {
        assertEquals(Optional.empty(), WebAnalytics.snippet(Optional.empty()));
    }

    @Test
    void validTokenGivesTheCloudflareBeaconOnly() {
        String beacon = WebAnalytics.snippet(Optional.of(TOKEN)).orElseThrow();
        assertEquals("<script defer src=\"https://static.cloudflareinsights.com/beacon.min.js\" "
                + "data-cf-beacon='{\"token\": \"" + TOKEN + "\"}'></script>", beacon);
        assertFalse(beacon.contains("cookie"));
    }

    @Test
    void surroundingWhitespaceIsTrimmed() {
        assertTrue(WebAnalytics.snippet(Optional.of("  " + TOKEN + "\n")).orElseThrow().contains(TOKEN + "\""));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "short",
            "0123456789abcdef'><script>alert(1)</script>",
            "0123456789abcdef\"}' onload='x",
            "0123456789abcdef 0123456789abcdef",
    })
    void anythingButAPlainTokenIsRefused(String token) {
        assertEquals(Optional.empty(), WebAnalytics.snippet(Optional.of(token)));
    }
}
