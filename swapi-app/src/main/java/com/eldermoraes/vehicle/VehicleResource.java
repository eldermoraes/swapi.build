package com.eldermoraes.vehicle;


import io.quarkus.logging.Log;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@RequestScoped
@Path("vehicles")
@RunOnVirtualThread
@Tag(name = "Vehicles", description = "Vehicles in the Star Wars universe")
public class VehicleResource {

    private final VehicleService vehicleService;

    VehicleResource(VehicleService vehicleService) {
        this.vehicleService = vehicleService;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "List all vehicles",
            description = "Returns every vehicle, or only those whose name matches the search query.")
    @APIResponse(responseCode = "200", description = "List of vehicles",
            content = @Content(schema = @Schema(type = SchemaType.ARRAY, implementation = Vehicle.class)))
    public Response getAllVehicles(
            @Parameter(description = "Filter by name (case-insensitive contains)", example = "speeder")
            @QueryParam("search") String search) {
        if (search != null && !search.isEmpty()) {
            return Response.ok().entity(vehicleService.getVehicleByName(search)).build();
        } else {
            return Response.ok().entity(vehicleService.getAllVehicles()).build();
        }

    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}")
    @Operation(summary = "Get a vehicle by id")
    @APIResponse(responseCode = "200", description = "The vehicle with the given id",
            content = @Content(schema = @Schema(implementation = Vehicle.class)))
    @APIResponse(responseCode = "404", description = "No vehicle exists with the given id",
            content = @Content(mediaType = "text/plain"))
    public Response getVehicleById(
            @Parameter(description = "Numeric id of the vehicle", example = "4")
            @PathParam("id") int id) {
        Vehicle vehicle = vehicleService.getVehicleById(id);
        if (vehicle == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .type(MediaType.TEXT_PLAIN)
                    .entity("No vehicle found with id " + id).build();
        }
        return Response.ok().entity(vehicle).build();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("random")
    @Operation(summary = "Get a random vehicle")
    @APIResponse(responseCode = "200", description = "A randomly selected vehicle",
            content = @Content(schema = @Schema(implementation = Vehicle.class)))
    public Response getRandomVehicle() {
        Log.info("Thread name: " + Thread.currentThread().getName());
        return Response.ok().entity(vehicleService.getRandomVehicle()).build();
    }
}
