package com.eldermoraes;

import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.HashMap;
import java.util.Map;

@Path("/")
@RunOnVirtualThread
@Tag(name = "Root", description = "API entry point")
public class ApiResource {

    @Inject
    UriInfo uriInfo;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "List all available resources",
            description = "Returns the URLs of every resource collection, built from the request's base URL.")
    @APIResponse(responseCode = "200", description = "Map of resource name to collection URL",
            content = @Content(schema = @Schema(type = SchemaType.OBJECT)))
    public Response get(){
        String uri = uriInfo.getAbsolutePath().toString();
        String cleanUrl = uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;

        Map<String, String> links = new HashMap<>();
        links.put("films", cleanUrl + "/films");
        links.put("people", cleanUrl + "/people");
        links.put("planets", cleanUrl + "/planets");
        links.put("species", cleanUrl + "/species");
        links.put("starships", cleanUrl + "/starships");
        links.put("vehicles", cleanUrl + "/vehicles");

        return Response.ok(links).build();

    }
}
