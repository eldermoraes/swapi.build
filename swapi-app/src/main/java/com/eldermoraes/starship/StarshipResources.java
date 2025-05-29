package com.eldermoraes.starship;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

@Path("starships")
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

}
