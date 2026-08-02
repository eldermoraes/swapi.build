package com.eldermoraes;

/**
 * Base URL da request corrente. Todo entry point que serializa entidades
 * (filtro REST, tools MCP) seta antes de qualquer leitura.
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
