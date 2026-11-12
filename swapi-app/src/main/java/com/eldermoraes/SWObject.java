package com.eldermoraes;

import jakarta.json.bind.annotation.JsonbTransient;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/**
 * Base of the six record classes. The data files hold relative addresses
 * ("/people/1"); the getters of a record give them with the base URL of the
 * current request in front, through the helpers below (issue #62: they were
 * 25 copies with three null rules).
 *
 * The one null rule: an address gets the base in front only when both the
 * address and the base exist; otherwise it is returned as stored. Every
 * response is produced with a base set, so the rule shows only where nothing
 * is serialized (the id lookup of the services, KnownEntities), which compare
 * the end of the address.
 *
 * The helpers are protected and not named as getters on purpose: JSON-B and
 * the OpenAPI scan must not read them as properties.
 */
public class SWObject {

    @JsonbTransient
    @Schema(hidden = true)
    public String getBaseUrl() {
        return RequestBaseUrl.get();
    }

    /** The address with the request base in front; as stored when either is missing. */
    protected String absolute(String address) {
        String base = getBaseUrl();
        return address == null || base == null ? address : base + address;
    }

    /** Each address with the request base in front; the list as stored when either is missing. */
    protected List<String> absolute(List<String> addresses) {
        String base = getBaseUrl();
        if (addresses == null || base == null) {
            return addresses;
        }
        return addresses.stream().map(address -> base + address).toList();
    }

    /**
     * A single relation that a record may lack (homeworld): absent, the text
     * "null" or empty is "", never null, so /api/species/2 keeps saying
     * "homeworld":"".
     */
    protected String absoluteOrEmpty(String address) {
        if (address == null || address.equals("null") || address.isEmpty()) {
            return "";
        }
        return absolute(address);
    }
}
