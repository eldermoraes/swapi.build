package com.eldermoraes;

import jakarta.json.bind.annotation.JsonbTransient;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

public class SWObject {

    @JsonbTransient
    @Schema(hidden = true)
    public String getBaseUrl() {
        return RequestBaseUrl.get();
    }
}
