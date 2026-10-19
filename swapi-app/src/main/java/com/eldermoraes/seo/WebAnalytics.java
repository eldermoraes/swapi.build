package com.eldermoraes.seo;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The Cloudflare Web Analytics beacon, the one third-party script the site loads.
 *
 * It sets no cookie and uses no local storage; it reports the page URL, the
 * referrer and load timings to Cloudflare. It is injected by {@link SeoRoutes}
 * into the SPA HTML only when {@code swapi.analytics.cloudflare-token} is set
 * (environment variable {@code CF_WEB_ANALYTICS_TOKEN} on the host, read at
 * runtime — see docs/DEPLOY.md). Without a token nothing is injected.
 *
 * The token is not a secret (it is visible in every page), but it is still
 * validated: it is written into the HTML, so anything other than a plain
 * alphanumeric token is refused instead of escaped.
 */
@ApplicationScoped
public class WebAnalytics {

    static final String BEACON_URL = "https://static.cloudflareinsights.com/beacon.min.js";

    private static final Logger LOG = Logger.getLogger(WebAnalytics.class);
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9]{16,64}");

    private final Optional<String> snippet;
    private final boolean tokenSet;

    WebAnalytics(@ConfigProperty(name = "swapi.analytics.cloudflare-token") Optional<String> token) {
        this.snippet = snippet(token);
        this.tokenSet = token.isPresent();
    }

    void logState(@Observes StartupEvent event) {
        if (snippet.isPresent()) {
            LOG.info("Cloudflare Web Analytics beacon enabled");
        } else if (tokenSet) {
            LOG.warn("Cloudflare Web Analytics beacon disabled: CF_WEB_ANALYTICS_TOKEN is not 16-64 letters and digits");
        } else {
            LOG.info("Cloudflare Web Analytics beacon disabled (CF_WEB_ANALYTICS_TOKEN not set)");
        }
    }

    /** The script tag to place before {@code </body>}, or empty when disabled. */
    public Optional<String> beacon() {
        return snippet;
    }

    static Optional<String> snippet(Optional<String> token) {
        return token.map(String::trim)
                .filter(t -> TOKEN.matcher(t).matches())
                .map(t -> "<script defer src=\"" + BEACON_URL + "\" data-cf-beacon='{\"token\": \"" + t
                        + "\"}'></script>");
    }
}
