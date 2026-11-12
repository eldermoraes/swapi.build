package com.eldermoraes;

import com.eldermoraes.people.People;
import com.eldermoraes.people.PeopleService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the six services share through RecordService (issue #62) and no other
 * test names: one load per type, and a type whose file cannot be loaded never
 * gets a service (#49).
 */
class RecordServiceTest {

    /** A type with no data file: data/droids.json does not exist. */
    private static final class DroidService extends RecordService<People> {
        DroidService() {
            super("droids", new ArrayList<People>(){}.getClass().getGenericSuperclass(), People::getName, People::getUrl);
        }
    }

    @Test
    void everyServiceOfATypeSharesOneLoad() {
        assertSame(new PeopleService().all(), new PeopleService().all());
    }

    // Twice: the failure is not remembered as an empty type, the second
    // service fails for the same reason.
    @Test
    void aTypeWhoseFileCannotBeLoadedHasNoService() {
        for (int attempt = 0; attempt < 2; attempt++) {
            IllegalStateException error = assertThrows(IllegalStateException.class, DroidService::new);
            assertTrue(error.getMessage().startsWith("data/droids.json"), error.getMessage());
        }
    }
}
