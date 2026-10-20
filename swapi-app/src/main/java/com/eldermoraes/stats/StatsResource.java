package com.eldermoraes.stats;

import com.eldermoraes.stats.StatsPeriod.Granularity;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
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
    @Operation(summary = "Request counts per route for the last 24 hours, 7 days or 30 days",
            description = "Totals per route template (for example /api/people/{id} or /mcp) and per group "
                    + "(rest, mcp, pages, seo, other), a trend per hour or per UTC day, and the same totals for "
                    + "the window right before, for comparison. Counted with no IP address, no User-Agent and "
                    + "no other data about the client. Without parameters: the last 7 days, per day.")
    @APIResponse(responseCode = "200", description = "Request counts for the window",
            content = @Content(schema = @Schema(implementation = RouteStats.StatsView.class)))
    @APIResponse(responseCode = "400", description = "Unknown period or granularity, or a combination the "
            + "period does not accept (24h is hourly only, 30d is daily only)")
    public RestResponse<RouteStats.StatsView> get(
            @Parameter(description = "Window: 24h, 7d (default) or 30d",
                    schema = @Schema(type = SchemaType.STRING, enumeration = {"24h", "7d", "30d"}))
            @QueryParam("period") String period,
            @Parameter(description = "Trend buckets: hour or day. Default: hour for 24h, day for 7d and 30d; "
                    + "7d accepts both",
                    schema = @Schema(type = SchemaType.STRING, enumeration = {"hour", "day"}))
            @QueryParam("granularity") String granularity) {
        StatsPeriod p = period == null || period.isEmpty()
                ? StatsPeriod.LAST_7_DAYS
                : StatsPeriod.parse(period).orElseThrow(() -> badRequest("period must be 24h, 7d or 30d"));
        Granularity g = granularity == null || granularity.isEmpty()
                ? p.defaultGranularity()
                : Granularity.parse(granularity).orElseThrow(() -> badRequest("granularity must be hour or day"));
        if (!p.accepts(g)) {
            throw badRequest("period " + p.id() + " does not accept granularity " + g.id());
        }
        return RestResponse.ResponseBuilder.ok(stats.view(p, g))
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .build();
    }

    /** A fixed message: the rejected value is never echoed back. */
    private static BadRequestException badRequest(String message) {
        return new BadRequestException(Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.TEXT_PLAIN_TYPE)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .entity(message)
                .build());
    }
}
