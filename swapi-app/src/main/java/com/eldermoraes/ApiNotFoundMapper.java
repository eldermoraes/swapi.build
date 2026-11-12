package com.eldermoraes;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.List;
import java.util.Map;

/**
 * Guarantees that every 404 in the /api family honors the contract published
 * in openapi.json: text/plain with a readable message (issue #12).
 *
 * Without this mapper, an id that does not parse as a Java int ("abc", "1.5",
 * "2147483648") fails @PathParam conversion before the resource body runs,
 * and the framework's default 404 goes out with no content type and no body.
 * The 404s hand-built by the resources ("No people found with id 9999")
 * do not pass through here — this mapper only sees the framework's
 * NotFoundException.
 *
 * Since issue #55 an id that is not canonical never reaches the conversion:
 * CanonicalIdFilter answers it first, with the same response built here. The
 * mapper still serves every other 404 of /api (an unknown route, for one).
 */
@Provider
public class ApiNotFoundMapper implements ExceptionMapper<NotFoundException> {

    // Route plural -> singular form used in the resources' messages.
    private static final Map<String, String> MESSAGE_FORMS = Map.of(
            "people", "people",
            "films", "film",
            "planets", "planet",
            "species", "specie",
            "starships", "starship",
            "vehicles", "vehicle");

    @Context
    UriInfo uriInfo;

    @Override
    public Response toResponse(NotFoundException exception) {
        return notFound(uriInfo, null);
    }

    /**
     * The contract 404 for the path of this request. The message names the id
     * as the client wrote it when the caller has it (CanonicalIdFilter, issue
     * #55: "%31", "1;x=y"), and the decoded second segment otherwise.
     */
    static Response notFound(UriInfo uriInfo, String idAsWritten) {
        return Response.status(Response.Status.NOT_FOUND)
                .type(MediaType.TEXT_PLAIN)
                .entity(message(uriInfo, idAsWritten))
                .build();
    }

    private static String message(UriInfo uriInfo, String idAsWritten) {
        List<String> segments = uriInfo.getPathSegments().stream()
                .map(s -> s.getPath())
                .filter(s -> !s.isEmpty())
                .toList();
        if (segments.size() >= 2) {
            String form = MESSAGE_FORMS.get(segments.get(0));
            if (form != null) {
                return "No " + form + " found with id " + (idAsWritten != null ? idAsWritten : segments.get(1));
            }
        }
        return "No resource found at /api/" + String.join("/", segments);
    }
}
