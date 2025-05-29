package com.eldermoraes.people;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

@Path("people")
public class PeopleResource {

    private final PeopleService peopleService;

    PeopleResource(UriInfo uriInfo, PeopleService peopleService) {
        String hostUrl = uriInfo.getBaseUri().toString();
        this.peopleService = peopleService;
        this.peopleService.setBaseUrl(hostUrl);
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

}
