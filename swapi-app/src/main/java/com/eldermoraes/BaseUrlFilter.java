package com.eldermoraes;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;

@Provider
public class BaseUrlFilter implements ContainerRequestFilter {

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String base = requestContext.getUriInfo().getBaseUri().toString();
        RequestBaseUrl.set(base.endsWith("/") ? base.substring(0, base.length() - 1) : base);
    }
}
