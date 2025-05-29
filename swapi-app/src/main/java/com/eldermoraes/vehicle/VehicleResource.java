package com.eldermoraes.vehicle;


import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

@Path("vehicles")
@RunOnVirtualThread
public class VehicleResource {

    private final VehicleService vehicleService;

    VehicleResource(UriInfo uriInfo, VehicleService vehicleService) {
        this.vehicleService = vehicleService;
        this.vehicleService.setBaseUrl(uriInfo.getBaseUri().toString());
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAllVehicles(@QueryParam("search") String search) {
        if (search != null && !search.isEmpty()) {
            return Response.accepted().entity(vehicleService.getVehicleByName(search)).build();
        } else {
            return Response.accepted().entity(vehicleService.getAllVehicles()).build();
        }

    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}")
    public Response getVehicleById(@PathParam("id") String id) {
        if (id != null && !id.isEmpty()) {
            return Response.accepted().entity(vehicleService.getVehicleById(Integer.parseInt(id))).build();
        } else {
            return Response.status(Response.Status.BAD_REQUEST).entity("ID parameter is required").build();
        }
    }
}
