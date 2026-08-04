# quarkus-rest-client

Calling the [swapi.build](https://swapi.build) REST API from Java with a typed
client: you declare the remote API as an interface, and Quarkus writes the HTTP
calls for you.

## Prerequisites

- Java 25

## Run

```bash
./mvnw quarkus:dev
```

Then, in another terminal:

```bash
curl -s http://localhost:8080/people/1
```

```json
{"birth_year":"19BBY","created":"2014-12-09T13:50:51.644000Z","edited":"2014-12-20T21:17:56.891000Z","eye_color":"blue","films":["https://swapi.build/api/films/1","https://swapi.build/api/films/2",…],"gender":"male","hair_color":"blond","height":"172","homeworld":"https://swapi.build/api/planets/1","mass":"77","name":"Luke Skywalker","skin_color":"fair","species":[],"starships":[…],"url":"https://swapi.build/api/people/1"}
```

```bash
curl -s 'http://localhost:8080/people?search=Luke'
```

```json
[{"birth_year":"19BBY","eye_color":"blue","gender":"male","hair_color":"blond","height":"172","homeworld":"https://swapi.build/api/planets/1","mass":"77","name":"Luke Skywalker",…,"url":"https://swapi.build/api/people/1"}]
```

Both responses above are real output from this example, shortened with `…` — the
API returns every field on one line.

## How it works

`SwapiClient` is the remote API declared as a Java interface: one method per
endpoint, with `@Path`, `@PathParam` and `@QueryParam` describing the request.
`@RegisterRestClient(configKey = "swapi-api")` binds it to the URL configured as
`quarkus.rest-client.swapi-api.url` in `application.properties`. Quarkus
generates the client implementation at build time — there is no runtime proxy
magic and nothing to wire up by hand.

`PeopleResource` injects that interface with `@RestClient` and returns the
client's `Response` as-is, so both the body and the status code you get from
`localhost:8080` are what swapi.build returned. An id the API does not have —
`/people/999` — is a `404` upstream and stays a `404` here. Two things buy that,
with no error-mapping code: the `Response` return type carries the status along
with the body, and one line in `application.properties`
(`microprofile.rest.client.disable.default.mapper=true`) stops the client from
turning error statuses into exceptions.

The interface is annotated `@Produces(MediaType.APPLICATION_JSON)`, which sets
the `Accept` header of the outgoing request and states on the interface that
swapi.build serves JSON.

## Returning objects instead of JSON

Replace the `Response` return types with a record (or a `List<Record>`) that
mirrors the fields you care about, and add the
`quarkus-rest-client-jackson` dependency. Quarkus then deserializes the
response for you and the interface becomes fully typed.

## Pointing at a local server

`application.properties` carries a commented-out line for a locally running
swapi.build. Uncomment it and comment the public one:

```properties
quarkus.rest-client.swapi-api.url=http://localhost:5432/api
```

## Links

- [swapi.build](https://swapi.build)
- [OpenAPI contract](https://swapi.build/openapi.json)
- [Quarkus REST Client guide](https://quarkus.io/guides/rest-client)

See also: `../langchain4j-mcp-client`, which reaches the same data through the
swapi.build MCP server instead.
