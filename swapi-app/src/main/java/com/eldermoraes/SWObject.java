package com.eldermoraes;

import jakarta.json.bind.annotation.JsonbTransient;

public class SWObject {

    @JsonbTransient
    public String getBaseUrl() {
        return RequestBaseUrl.get();
    }
}
