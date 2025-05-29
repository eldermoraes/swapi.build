package com.eldermoraes.specie;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

@Path("species")
public class SpecieResource {

    private final SpecieService specieService;

    SpecieResource(UriInfo uriInfo, SpecieService specieService) {
        this.specieService = specieService;
        this.specieService.setBaseUrl(uriInfo.getBaseUri().toString());
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getSpecies(@QueryParam("search") String search) {
        if (search != null && !search.isEmpty()) {
            return Response.accepted().entity(specieService.getSpecieByName(search)).build();
        } else {
            return Response.accepted().entity(specieService.getAllSpecies()).build();
        }

    }

}
