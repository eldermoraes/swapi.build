package com.eldermoraes.seo.ssr;

import io.quarkus.qute.TemplateData;

import java.util.List;

/**
 * The data of ssr/docs.html, built by OpenApiDocs from the app's own OpenAPI
 * document with the rules webui/src/pages/documentation.ts had in 2.10.0: Root
 * first, the other tags in the declared order, the operations of a tag by path
 * (localeCompare order), the search example, the "200 / 404" note, the field
 * table of the tag's schema and the "Try it" forms.
 */
@TemplateData
public record DocsView(String openapi, String version, String description, List<Tag> tags) {

    @TemplateData
    public record Tag(String name, String description, List<Operation> operations, boolean hasSchema,
                      String schemaName, List<Field> fields) {
    }

    /**
     * @param description summary and description joined with " — "
     * @param notFound    the operation documents a 404
     */
    @TemplateData
    public record Operation(String path, String displayPath, String description, boolean notFound,
                            boolean hasId, String idExample, String idLabel, boolean hasSearch, String searchLabel) {
    }

    @TemplateData
    public record Field(String name, String type, String description) {
    }
}
