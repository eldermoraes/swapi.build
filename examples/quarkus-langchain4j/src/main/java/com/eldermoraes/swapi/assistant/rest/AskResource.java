package com.eldermoraes.swapi.assistant.rest;

import com.eldermoraes.swapi.assistant.ai.Archivist;
import com.eldermoraes.swapi.assistant.ai.RestArchivist;
import com.eldermoraes.swapi.assistant.dto.Answer;
import com.eldermoraes.swapi.assistant.dto.Question;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/ask")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class AskResource {

    @Inject
    Archivist archivist;

    @Inject
    RestArchivist restArchivist;

    @POST
    @Path("/mcp")
    public Answer viaMcp(Question question) {
        return new Answer("mcp", archivist.ask(question.question()));
    }

    @POST
    @Path("/api")
    public Answer viaApi(Question question) {
        return new Answer("api", restArchivist.ask(question.question()));
    }
}
