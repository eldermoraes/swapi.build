package com.eldermoraes.seo.ssr;

import com.eldermoraes.seo.FaqFile;
import io.quarkus.runtime.Startup;
import jakarta.inject.Singleton;

/**
 * seo/faq.json, read once at startup (2.15.0): a missing or invalid file stops
 * the app with the file and the key in the message, instead of serving /faq
 * without its content.
 */
@Startup
@Singleton
public class FaqContent {

    private final FaqFile file = FaqFile.load();

    public FaqFile file() {
        return file;
    }
}
