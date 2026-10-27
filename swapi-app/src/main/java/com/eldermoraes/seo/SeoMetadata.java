package com.eldermoraes.seo;

/**
 * @param canonicalPath null for the 404 document, which has no canonical
 */
public record SeoMetadata(
        String path,
        String canonicalPath,
        String title,
        String description,
        String robots,
        boolean sitemap,
        String changeFrequency,
        String priority) {

    public static final String INDEX_FOLLOW = "index,follow";
    public static final String NOINDEX_FOLLOW = "noindex,follow";
    public static final String NOINDEX = "noindex";
}
