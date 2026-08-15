package com.eldermoraes;

/**
 * Base URL of the current request. Every entry point that serializes entities
 * (REST filter, MCP tools) sets it before any read.
 */
public final class RequestBaseUrl {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private RequestBaseUrl() {
    }

    public static void set(String baseUrl) {
        CURRENT.set(baseUrl);
    }

    public static String get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
