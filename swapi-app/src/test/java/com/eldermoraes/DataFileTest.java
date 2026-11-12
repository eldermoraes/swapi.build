package com.eldermoraes;

import com.eldermoraes.film.Film;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A data file that cannot be loaded is an error naming the file, never an
 * empty list (#49): the six services load through DataFile, and
 * KnownEntities.warm touches them at startup, so the error stops the
 * application instead of serving a type with no records.
 */
class DataFileTest {

    private static final Type FILMS = new ArrayList<Film>() { }.getClass().getGenericSuperclass();

    @Test
    void theShippedFilmsLoad() {
        List<Film> films = DataFile.load("data/films.json", FILMS);
        assertEquals(6, films.size());
        assertEquals("A New Hope", films.get(0).getTitle());
    }

    @Test
    void aMissingFileFailsWithItsName() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> DataFile.load("datafile/absent.json", FILMS));
        assertTrue(error.getMessage().contains("datafile/absent.json"), error.getMessage());
        assertTrue(error.getMessage().contains("missing from the classpath"), error.getMessage());
    }

    @Test
    void anUnparsableFileFailsWithItsName() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> DataFile.load("datafile/unparsable.json", FILMS));
        assertTrue(error.getMessage().contains("datafile/unparsable.json"), error.getMessage());
        assertTrue(error.getMessage().contains("could not be read"), error.getMessage());
    }

    @Test
    void aFileWithoutRecordsFailsWithItsName() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> DataFile.load("datafile/empty.json", FILMS));
        assertTrue(error.getMessage().contains("datafile/empty.json"), error.getMessage());
        assertTrue(error.getMessage().contains("holds no records"), error.getMessage());
    }
}
