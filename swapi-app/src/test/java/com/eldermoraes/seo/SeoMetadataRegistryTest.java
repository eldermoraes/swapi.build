package com.eldermoraes.seo;

import com.eldermoraes.film.Film;
import com.eldermoraes.film.FilmService;
import com.eldermoraes.people.People;
import com.eldermoraes.people.PeopleService;
import com.eldermoraes.planet.PlanetService;
import com.eldermoraes.specie.SpecieService;
import com.eldermoraes.starship.StarshipService;
import com.eldermoraes.vehicle.VehicleService;
import io.quarkus.runtime.Startup;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The batches are a rule per type (seo/site.json, detail.indexable), never a list
 * of records: there is one detail template and the sitemap walks the data of each
 * enabled type. Plain JUnit, with the real data plus records the data does not
 * have, to prove that a new record needs no code change.
 */
class SeoMetadataRegistryTest {

    private static List<String> sitemapPaths(SeoMetadataRegistry registry) {
        return registry.sitemapRoutes().stream().map(SeoMetadata::canonicalPath).toList();
    }

    @Test
    void theSitemapHasThePagesTheListsAndTheSixFilms() {
        SeoMetadataRegistry registry = new SeoMetadataRegistry(realEntities());
        List<String> paths = sitemapPaths(registry);
        assertEquals(20, paths.size(), paths.toString());
        for (int id = 1; id <= 6; id++) {
            assertTrue(paths.contains("/resource/films/" + id), paths.toString());
        }
        assertTrue(paths.stream().noneMatch(path -> path.matches("/resource/(people|planets|species|starships|vehicles)/\\d+")),
                paths.toString());
    }

    // Elder's rule for the batches (26/10/2026): a new record of an enabled type
    // enters the sitemap and gets its title and index,follow with no code change.
    @Test
    void aNewRecordOfAnEnabledTypeEntersTheSitemapWithNoCodeChange() {
        Film seventh = new Film();
        seventh.setTitle("The Force Awakens");
        seventh.setEpisode_id(7);
        seventh.setDirector("J. J. Abrams");
        seventh.setRelease_date("2015-12-18");
        seventh.setUrl("/films/7");
        SeoMetadataRegistry registry = new SeoMetadataRegistry(new KnownEntities(new PeopleService(),
                new FilmsPlus(seventh), new PlanetService(), new SpecieService(), new StarshipService(), new VehicleService()));

        List<String> paths = sitemapPaths(registry);
        assertEquals(21, paths.size(), paths.toString());
        assertTrue(paths.contains("/resource/films/7"), paths.toString());

        SeoMetadata page = registry.forPath("/resource/films/7");
        assertEquals("The Force Awakens — Star Wars film JSON | swapi.build", page.title());
        assertEquals("The Force Awakens, Star Wars Episode 7, directed by J. J. Abrams and released 2015-12-18. "
                + "Its JSON record from the free swapi.build REST API and MCP server.", page.description());
        assertEquals(SeoMetadata.INDEX_FOLLOW, page.robots());
        assertEquals("/resource/films/7", page.canonicalPath());
    }

    // And a new record of a type not yet enabled gets its title with the name,
    // noindex,follow and the list's canonical, and stays out of the sitemap.
    @Test
    void aNewRecordOfATypeNotYetEnabledStaysOutOfTheSitemap() {
        People newcomer = new People();
        newcomer.setName("Rey");
        newcomer.setUrl("/people/90");
        SeoMetadataRegistry registry = new SeoMetadataRegistry(new KnownEntities(new PeoplePlus(newcomer),
                new FilmService(), new PlanetService(), new SpecieService(), new StarshipService(), new VehicleService()));

        assertFalse(sitemapPaths(registry).contains("/resource/people/90"));
        SeoMetadata page = registry.forPath("/resource/people/90");
        assertEquals("Rey — Star Wars character JSON | swapi.build", page.title());
        assertEquals(SeoMetadata.NOINDEX_FOLLOW, page.robots());
        assertEquals("/resource/people", page.canonicalPath());
    }

    // A record with an empty field gets the fallback title, never "null".
    @Test
    void aRecordWithAMissingFieldGetsTheFallbackTitle() {
        Film untitled = new Film();
        untitled.setEpisode_id(8);
        untitled.setUrl("/films/8");
        SeoMetadataRegistry registry = new SeoMetadataRegistry(new KnownEntities(new PeopleService(),
                new FilmsPlus(untitled), new PlanetService(), new SpecieService(), new StarshipService(), new VehicleService()));

        SeoMetadata page = registry.forPath("/resource/films/8");
        assertEquals("Films #8 | swapi.build", page.title());
        assertEquals("Star Wars film record #8 from the swapi.build REST API.", page.description());
        assertEquals(SeoMetadata.INDEX_FOLLOW, page.robots());
    }

    // seo/site.json is read when the app starts, not on the first page: an invalid
    // or missing file stops the startup with its message (and in production
    // swapi-update reports "is not answering") instead of serving untitled pages.
    @Test
    void theRegistryIsBuiltAtStartup() {
        assertTrue(SeoMetadataRegistry.class.isAnnotationPresent(Startup.class));
    }

    private static KnownEntities realEntities() {
        return new KnownEntities(new PeopleService(), new FilmService(), new PlanetService(), new SpecieService(),
                new StarshipService(), new VehicleService());
    }

    /** The real films plus one more, as if the data file had gained a record. */
    private static final class FilmsPlus extends FilmService {
        private final Film extra;

        FilmsPlus(Film extra) {
            this.extra = extra;
        }

        @Override
        public List<Film> getAllFilms() {
            List<Film> all = new ArrayList<>(super.getAllFilms());
            all.add(extra);
            return all;
        }

        @Override
        public Film getFilmById(int id) {
            return extra.getUrl().endsWith("/films/" + id) ? extra : super.getFilmById(id);
        }
    }

    private static final class PeoplePlus extends PeopleService {
        private final People extra;

        PeoplePlus(People extra) {
            this.extra = extra;
        }

        @Override
        public List<People> getAllPeople() {
            List<People> all = new ArrayList<>(super.getAllPeople());
            all.add(extra);
            return all;
        }

        @Override
        public People getPeopleById(int id) {
            return extra.getUrl().endsWith("/people/" + id) ? extra : super.getPeopleById(id);
        }
    }
}
