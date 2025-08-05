package com.eldermoraes.specie;

import io.quarkus.logging.Log;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

@Path("species")
@RunOnVirtualThread
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

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}")
    public Response getSpecieById(@PathParam("id") String id) {
        if (id != null && !id.isEmpty()) {
            return Response.accepted().entity(specieService.getSpecieById(Integer.parseInt(id))).build();
        } else {
            return Response.status(Response.Status.BAD_REQUEST).entity("ID parameter is required").build();
        }
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("random")
    public Response getRandomSpecie() {
        Log.info("Thread name: " + Thread.currentThread().getName());
        return Response.accepted().entity(specieService.getRandomSpecie()).build();
    }

}
