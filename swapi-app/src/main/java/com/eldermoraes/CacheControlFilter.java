package com.eldermoraes;

import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.PathSegment;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;

/**
 * Marca como cacheavel na borda tudo que e deterministico em /api.
 *
 * Os dados sao JSONs estaticos embutidos no binario, entao a resposta so muda
 * num deploy novo — e a chave de cache da Vercel inclui a deployment URL, o que
 * invalida a entrada automaticamente. Por isso o TTL da borda e alto e o do
 * browser e curto: o cache do browser NAO e invalidado por deploy.
 *
 * Politica deny-list: todo endpoint novo em /api nasce cacheavel na borda;
 * endpoint nao-deterministico precisa entrar na exclusao. O header so e gravado
 * se a resposta ainda nao tiver um Cache-Control, entao um resource pode optar
 * por sair (ex.: no-store) setando o header ele mesmo.
 */
@Provider
public class CacheControlFilter implements ContainerResponseFilter {

    private static final String RANDOM = "random";

    // Definicao unica em application.properties, compartilhada com o filtro HTTP
    // que cobre /openapi.json. Package-private: o padrao Quarkus para injecao
    // de campo sem reflexao.
    @ConfigProperty(name = "swapi.cache-control.public")
    String cacheControl;

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        // Nao sobrescreve um Cache-Control que o resource tenha setado de proposito.
        if (isCacheable(request, response)
                && !response.getHeaders().containsKey(HttpHeaders.CACHE_CONTROL)) {
            response.getHeaders().putSingle(HttpHeaders.CACHE_CONTROL, cacheControl);
            // O filtro CORS ecoa o Origin da request e nao emite Vary. Sem isto a
            // borda serviria o Access-Control-Allow-Origin de um origin para
            // outro — e a variante sem Origin para um cliente de browser.
            // add, nao putSingle: preserva um Vary que ja exista (ex.: Accept-Encoding).
            response.getHeaders().add(HttpHeaders.VARY, "Origin");
        }
    }

    private boolean isCacheable(ContainerRequestContext request, ContainerResponseContext response) {
        if (!HttpMethod.GET.equals(request.getMethod())
                && !HttpMethod.HEAD.equals(request.getMethod())) {
            return false;
        }
        // A borda so cacheia 200/404 (5xx nunca) — nao adianta marcar o resto.
        if (response.getStatus() != 200 && response.getStatus() != 404) {
            return false;
        }
        return !isRandom(request);
    }

    private boolean isRandom(ContainerRequestContext request) {
        List<PathSegment> segments = request.getUriInfo().getPathSegments();
        return !segments.isEmpty()
                && RANDOM.equals(segments.get(segments.size() - 1).getPath());
    }
}
