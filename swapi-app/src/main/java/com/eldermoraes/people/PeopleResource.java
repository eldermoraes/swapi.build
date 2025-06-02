package com.eldermoraes.people;

import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

@Path("people")
@RunOnVirtualThread
public class PeopleResource {

    private final PeopleService peopleService;

    PeopleResource(UriInfo uriInfo, PeopleService peopleService) {
        this.peopleService = peopleService;
        this.peopleService.setBaseUrl(uriInfo.getBaseUri().toString());
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAllPeople(@QueryParam("search") String search) {
        if (search != null && !search.isEmpty()) {
            return Response.accepted().entity(peopleService.getPeopleByName(search)).build();
        } else {
            return Response.accepted().entity(peopleService.getAllPeople()).build();
        }
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}")
    public Response getPeopleById(@PathParam("id") String id) {
        if (id != null && !id.isEmpty()) {
            return Response.accepted().entity(peopleService.getPeopleById(Integer.parseInt(id))).build();
        } else {
            return Response.status(Response.Status.BAD_REQUEST).entity("ID parameter is required").build();
        }
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("random")
    public Response getRandomPeople() {
        return Response.accepted().entity(peopleService.getRandomPeople()).build();
    }
}
