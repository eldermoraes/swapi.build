package com.eldermoraes.seo.ssr;

import io.quarkus.qute.TemplateData;

import java.util.List;

/**
 * The data of ssr/record.html, the template of detail (2.12.0, slice E2), built
 * by RecordViews. Only Strings and lists of records: every getter that reads
 * RequestBaseUrl was called inside the block that sets and restores it, so the
 * render never calls one. Code samples and the JSON are Strings, escaped like
 * any value.
 *
 * @param openingCrawl the opening crawl of a film, empty for the other types
 * @param json         the record as GET /api/{type}/{id} returns it, as
 *                     JSON.stringify(value, null, 2) writes it (JsonText)
 */
@TemplateData
public record RecordView(String type, String id, String typeLabel, String name, List<Attribute> attributes,
                         String openingCrawl, List<Related> relations, String apiPath, String curl, String fetch,
                         String python, String mcpAdd, String mcpArgs, String json) {

    /** A row of the attribute table. */
    @TemplateData
    public record Attribute(String label, String value) {
    }

    /** A block of related records; a relation without records has no block. */
    @TemplateData
    public record Related(String label, List<Link> links) {
    }

    /** A related record: its name and address, or the bare URL, unlinked, when it does not exist. */
    @TemplateData
    public record Link(String name, String href, boolean linked) {
    }
}
