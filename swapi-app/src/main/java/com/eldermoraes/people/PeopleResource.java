package com.eldermoraes.people;

import io.quarkus.logging.Log;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@RequestScoped
@Path("people")
@RunOnVirtualThread
public class PeopleResource {

    private final PeopleService peopleService;

    PeopleResource(PeopleService peopleService) {
        this.peopleService = peopleService;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAllPeople(@QueryParam("search") String search) {
        if (search != null && !search.isEmpty()) {
            return Response.ok().entity(peopleService.getPeopleByName(search)).build();
        } else {
            return Response.ok().entity(peopleService.getAllPeople()).build();
        }
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}")
    public Response getPeopleById(@PathParam("id") int id) {
        People people = peopleService.getPeopleById(id);
        if (people == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .type(MediaType.TEXT_PLAIN)
                    .entity("No people found with id " + id).build();
        }
        return Response.ok().entity(people).build();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("random")
    public Response getRandomPeople() {
        Log.info("Thread name: " + Thread.currentThread().getName());
        return Response.ok().entity(peopleService.getRandomPeople()).build();
    }
}
