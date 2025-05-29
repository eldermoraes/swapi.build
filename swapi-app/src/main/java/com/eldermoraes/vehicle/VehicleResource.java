package com.eldermoraes.vehicle;


import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

@Path("vehicles")
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
}
