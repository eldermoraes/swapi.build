package com.eldermoraes.stats;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestResponse;

@Path("stats")
@Tag(name = "Stats", description = "Public, anonymous usage counters")
public class StatsResource {

    /**
     * The counters move with every request, so the deny-list CacheControlFilter
     * (edge TTL of a year) must not apply: setting the header here opts out of it.
     */
    static final String CACHE_CONTROL = "public, max-age=60";

    @Inject
    RouteStats stats;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Request counts per route for the last 7 days",
            description = "Totals per route template (for example /api/people/{id} or /mcp) and per UTC day, "
                    + "for today and the six days before it. Counted in memory, with no IP address, no User-Agent "
                    + "and no other data about the client. The counters restart from zero when the server "
                    + "restarts; countingSince says when that was.")
    @APIResponse(responseCode = "200", description = "Request counts per route and per UTC day",
            content = @Content(schema = @Schema(implementation = RouteStats.Snapshot.class)))
    public RestResponse<RouteStats.Snapshot> get() {
        return RestResponse.ResponseBuilder.ok(stats.lastDays())
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .build();
    }
}
