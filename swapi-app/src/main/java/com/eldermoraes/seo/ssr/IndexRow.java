package com.eldermoraes.seo.ssr;

import io.quarkus.qute.TemplateData;

/** One row of the tags/indexRows.html list: the same markup as indexRows() had in components.ts. */
@TemplateData
public record IndexRow(String title, String endpoint, String href) {
}
