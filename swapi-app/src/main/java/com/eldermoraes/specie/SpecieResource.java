package com.eldermoraes.specie;

import io.quarkus.logging.Log;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

@RequestScoped
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
            return Response.ok().entity(specieService.getSpecieByName(search)).build();
        } else {
            return Response.ok().entity(specieService.getAllSpecies()).build();
        }

    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}")
    public Response getSpecieById(@PathParam("id") int id) {
        Specie specie = specieService.getSpecieById(id);
        if (specie == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .type(MediaType.TEXT_PLAIN)
                    .entity("No specie found with id " + id).build();
        }
        return Response.ok().entity(specie).build();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("random")
    public Response getRandomSpecie() {
        Log.info("Thread name: " + Thread.currentThread().getName());
        return Response.ok().entity(specieService.getRandomSpecie()).build();
    }

}
