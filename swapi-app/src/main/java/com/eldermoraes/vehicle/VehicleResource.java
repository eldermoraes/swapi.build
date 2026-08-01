package com.eldermoraes.vehicle;


import io.quarkus.logging.Log;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

@RequestScoped
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
            return Response.ok().entity(vehicleService.getVehicleByName(search)).build();
        } else {
            return Response.ok().entity(vehicleService.getAllVehicles()).build();
        }

    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}")
    public Response getVehicleById(@PathParam("id") String id) {
        if (id != null && !id.isEmpty()) {
            Vehicle vehicle = vehicleService.getVehicleById(Integer.parseInt(id));
            if (vehicle == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .type(MediaType.TEXT_PLAIN)
                        .entity("No vehicle found with id " + id).build();
            }
            return Response.ok().entity(vehicle).build();
        } else {
            return Response.status(Response.Status.BAD_REQUEST).entity("ID parameter is required").build();
        }
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("random")
    public Response getRandomVehicle() {
        Log.info("Thread name: " + Thread.currentThread().getName());
        return Response.ok().entity(vehicleService.getRandomVehicle()).build();
    }
}
