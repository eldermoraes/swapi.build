# SWAPI.build

A free, open-source Star Wars API serving data about People, Films, Planets, Species, Starships, and Vehicles. Built with [Quarkus](https://quarkus.io/) and [GraalVM](https://www.graalvm.org/) for instant startup, minimal memory footprint, and out-of-the-box performance.

Inspired by the original [SWAPI](https://swapi.dev/) (created by Paul Hallett, maintained by Juriy Bura), this project was born out of the need for a Star Wars API that **never goes offline**. If you've ever had a live demo break because a third-party API went down, you know why this exists.

## Quick Start

**Prerequisites:** Java 21+ and Maven (or use the included Maven Wrapper).

```bash
cd swapi-app
./mvnw quarkus:dev
```

The API and frontend will be available at **http://localhost:5432**.

## API Endpoints

Base path: `/api`

| Resource | List All | By ID | Random | Search |
|----------|----------|-------|--------|--------|
| People | `GET /api/people` | `GET /api/people/:id` | `GET /api/people/random` | `GET /api/people?search=name` |
| Films | `GET /api/films` | `GET /api/films/:id` | `GET /api/films/random` | `GET /api/films?search=title` |
| Planets | `GET /api/planets` | `GET /api/planets/:id` | `GET /api/planets/random` | `GET /api/planets?search=name` |
| Species | `GET /api/species` | `GET /api/species/:id` | `GET /api/species/random` | `GET /api/species?search=name` |
| Starships | `GET /api/starships` | `GET /api/starships/:id` | `GET /api/starships/random` | `GET /api/starships?search=name` |
| Vehicles | `GET /api/vehicles` | `GET /api/vehicles/:id` | `GET /api/vehicles/random` | `GET /api/vehicles?search=name` |

All responses are JSON. Example:

```bash
curl http://localhost:5432/api/people/1
```

## Project Structure

```
swapi-app/
  src/main/
    java/com/eldermoraes/     # Backend (Quarkus + Jakarta REST)
      film/                   # Film model, service, resource
      people/                 # People model, service, resource
      planet/                 # Planet model, service, resource
      specie/                 # Specie model, service, resource
      starship/               # Starship model, service, resource
      vehicle/                # Vehicle model, service, resource
      SWObject.java           # Base model class
      SWService.java          # Service interface
      ApiResource.java        # Root /api endpoint
    resources/
      data/                   # Static JSON data files
      application.properties  # Quarkus configuration
    webui/                    # Frontend (TypeScript + Vite)
      src/
        api.ts                # API client with request management
        main.ts               # SPA router
        pages/                # Page renderers (home, resource, docs, about)
        types.ts              # TypeScript interfaces for API resources
        constants.ts          # Shared resource metadata
        utils.ts              # Shared utilities (escapeHtml)
```

Each backend domain (film, people, planet, etc.) follows the same pattern:

- **Model** (e.g., `Film.java`): POJO with `@RegisterForReflection` for native image support
- **Service** (e.g., `FilmService.java`): loads data from JSON at startup, caches in memory
- **Resource** (e.g., `FilmResource.java`): Jakarta REST controller with `@RunOnVirtualThread`

## Build

```bash
cd swapi-app

# Development mode (live reload)
./mvnw quarkus:dev

# Production JAR
./mvnw package
java -jar target/quarkus-app/quarkus-run.jar

# Native executable (requires GraalVM or container build)
./mvnw package -Dnative
./mvnw package -Dnative -Dquarkus.native.container-build=true
```

The frontend is automatically built and bundled by [Quinoa](https://docs.quarkiverse.io/quarkus-quinoa/dev/index.html) during the Maven build. No separate `npm` step is needed.

## Tech Stack

- **Runtime:** [Quarkus 3.23](https://quarkus.io/) on Java 21+ with Virtual Threads
- **Serialization:** Jakarta REST + JSON-B
- **Native image:** GraalVM via Mandrel builder
- **Frontend:** TypeScript + [Vite](https://vite.dev/), served by Quinoa

## Contributing

Pull requests are always welcome. Whether it's fixing a bug, improving the docs, or adding new features, jump in and help make the best Star Wars API in the galaxy even better.

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/my-change`)
3. Make your changes and verify: `cd swapi-app && ./mvnw quarkus:dev`
4. Commit and push
5. Open a Pull Request

## Credits

- Original [SWAPI](https://swapi.dev/) by **Paul Hallett**, maintained by **Juriy Bura**
- Star Wars data from community-driven sources such as [Wookieepedia](https://starwars.fandom.com/)
- All Star Wars content and imagery are property of **Lucasfilm Ltd.** and **Disney**. This project is not affiliated with or endorsed by Lucasfilm or Disney.

## License

This project is open source. See the repository for license details.
