package com.eldermoraes.starship;

import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

@Path("starships")
@RunOnVirtualThread
public class StarshipResources {

    private final StarshipService starshipService;

    StarshipResources(UriInfo uriInfo, StarshipService starshipService) {
        this.starshipService = starshipService;
        this.starshipService.setBaseUrl(uriInfo.getBaseUri().toString());
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getStarships(@QueryParam("search") String search) {
        if (search != null && !search.isEmpty()) {
            return Response.accepted().entity(starshipService.getStarshipByName(search)).build();
        } else {
            return Response.accepted().entity(starshipService.getAllStarships()).build();
        }
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}")
    public Response getStarshipById(@PathParam("id") String id) {
        if (id != null && !id.isEmpty()) {
            return Response.accepted().entity(starshipService.getStarshipById(Integer.parseInt(id))).build();
        } else {
            return Response.status(Response.Status.BAD_REQUEST).entity("ID parameter is required").build();
        }
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("random")
    public Response getRandomStarship() {
        return Response.accepted().entity(starshipService.getRandomStarship()).build();
    }

}
