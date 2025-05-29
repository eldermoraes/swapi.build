package com.eldermoraes;

import jakarta.json.bind.annotation.JsonbTransient;

public class SWObject {

    @JsonbTransient
    String baseUrl;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }
}
