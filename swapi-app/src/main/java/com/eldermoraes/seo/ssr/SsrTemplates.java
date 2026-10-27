package com.eldermoraes.seo.ssr;

import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;

/**
 * The typed templates of the server-rendered pages (slice E, 2.11.0), in
 * src/main/resources/templates/ssr/. Typed so the build validates every
 * expression against the view records; the default escaping of .html templates
 * applies to every value (raw and safe are banned, SsrTemplatesTest).
 */
@CheckedTemplate(basePath = "ssr")
public final class SsrTemplates {

    private SsrTemplates() {
    }

    public static native TemplateInstance home(HomeView view);

    public static native TemplateInstance docs(DocsView view);

    public static native TemplateInstance mcp(McpView view);

    public static native TemplateInstance about();
}
