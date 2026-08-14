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
 * Garante que todo 404 da familia /api honra o contrato publicado no
 * openapi.json: text/plain com uma mensagem legivel (issue #12).
 *
 * Sem este mapper, um id que nao parseia como int Java ("abc", "1.5",
 * "2147483648") falha na conversao do @PathParam antes do corpo do resource
 * rodar, e o 404 default do framework sai sem content-type e sem corpo.
 * Os 404 construidos a mao pelos resources ("No people found with id 9999")
 * nao passam por aqui — este mapper so ve NotFoundException do framework.
 */
@Provider
public class ApiNotFoundMapper implements ExceptionMapper<NotFoundException> {

    // Plural da rota -> forma singular usada nas mensagens dos resources.
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
        return Response.status(Response.Status.NOT_FOUND)
                .type(MediaType.TEXT_PLAIN)
                .entity(message())
                .build();
    }

    private String message() {
        List<String> segments = uriInfo.getPathSegments().stream()
                .map(s -> s.getPath())
                .filter(s -> !s.isEmpty())
                .toList();
        if (segments.size() >= 2) {
            String form = MESSAGE_FORMS.get(segments.get(0));
            if (form != null) {
                return "No " + form + " found with id " + segments.get(1);
            }
        }
        return "No resource found at /api/" + String.join("/", segments);
    }
}
