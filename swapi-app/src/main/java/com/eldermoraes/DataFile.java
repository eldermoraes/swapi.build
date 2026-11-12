package com.eldermoraes;

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.List;

/**
 * Reads one of the data files (data/*.json) into its records. A file that is
 * missing, cannot be read as its type or holds no records is an error naming
 * the file, never an empty list (#49): the services load in their constructor
 * and KnownEntities.warm touches them at startup, so the application does not
 * start with a type it cannot serve. The message begins with the resource, as
 * in SeoSiteFile.load; unlike it, the error is also logged here, because the
 * old code reported a failed load on stderr only.
 */
public final class DataFile {

    private static final Logger LOG = Logger.getLogger(DataFile.class);

    private DataFile() {
    }

    /**
     * The records of a classpath resource, as a list of listType; throws
     * IllegalStateException naming the resource, after logging the same
     * message, when it is missing, invalid or empty.
     */
    public static <T> List<T> load(String resource, Type listType) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = DataFile.class.getClassLoader();
        }
        if (cl.getResource(resource) == null) {
            throw failure(resource + " is missing from the classpath"
                    + " (for the native binary: quarkus.native.resources.includes)", null);
        }
        List<T> records;
        try (Jsonb jsonb = JsonbBuilder.create();
             InputStream in = cl.getResourceAsStream(resource)) {
            records = jsonb.fromJson(in, listType);
        } catch (Exception e) {
            throw failure(resource + " could not be read: " + e.getMessage(), e);
        }
        if (records == null || records.isEmpty()) {
            throw failure(resource + " holds no records", null);
        }
        return records;
    }

    private static IllegalStateException failure(String message, Throwable cause) {
        LOG.error(message, cause);
        return new IllegalStateException(message, cause);
    }
}
