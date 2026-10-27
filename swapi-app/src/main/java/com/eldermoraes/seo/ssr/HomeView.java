package com.eldermoraes.seo.ssr;

import com.eldermoraes.seo.SeoSiteFile;
import io.quarkus.qute.TemplateData;

import java.util.List;

/**
 * The data of ssr/home.html: the terminal suggestions and the six resource rows
 * (title from resources[].label in seo/site.json). Nothing from the host.
 */
@TemplateData
public record HomeView(List<String> suggestions, List<IndexRow> rows) {

    static final List<String> SUGGESTIONS = List.of("people/1", "planets/1", "starships/9", "films/1");

    static HomeView of(SeoSiteFile site) {
        return new HomeView(SUGGESTIONS, site.resources().stream()
                .map(r -> new IndexRow(r.label(), "/api/" + r.type(), "/resource/" + r.type()))
                .toList());
    }
}
