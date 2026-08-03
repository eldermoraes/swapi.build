package com.eldermoraes.mcp;

import io.quarkus.vertx.web.RouteFilter;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.RoutingContext;

/**
 * Bordas HTTP do endpoint MCP que a extensao nao cobre nesta topologia.
 *
 * A sessao MCP vive na heap de uma instancia e o Vercel nao tem afinidade de
 * sessao. O auto-init resolve o POST -- que e onde as tool calls acontecem --
 * criando uma sessao descartavel quando o Mcp-Session-Id e desconhecido. GET e
 * DELETE continuariam respondendo 404, e um 404 num GET e legitimamente lido
 * pelo cliente como "a sessao morreu", derrubando a conexao inteira por causa
 * de um stream que era opcional.
 *
 * Como este servidor nao emite NENHUMA mensagem server->client (sem sampling,
 * elicitation, roots, progress, subscriptions), as respostas abaixo sao
 * incondicionalmente corretas -- nao dependem de a sessao existir ou nao.
 */
public class McpTransportFilter {

    private static final String MCP_PATH = "/mcp";

    // NOVAS -- MCP_PATH ja existe, vindo da Task 2
    private static final String LEGACY_SSE_PATH = "/mcp/sse";
    private static final String LEGACY_MESSAGES_PREFIX = "/mcp/messages/";

    // Transporte legado 2024-11-05: o stream SSE e os POSTs em /mcp/messages/<id>
    // precisam cair na mesma instancia, e nesta topologia isso nao acontece.
    // Rejeitar explicitamente e melhor que fazer o handshake e morrer em silencio.
    private static final String LEGACY_GONE = """
            {"error":"The legacy HTTP+SSE transport (spec 2024-11-05) is not \
            supported. Use the Streamable HTTP endpoint at /mcp."}""";

    // O filtro encerra a resposta (405, 204, 404), entao precisa rodar DEPOIS do
    // handler de CORS do Quarkus, que registra na prioridade 300 (ver
    // VertxHttpProcessor.cors -> FilterBuildItem(handler, 300); order = -1 *
    // prioridade em VertxHttpRecorder.finalizeRouter, entao prioridade maior =
    // executa antes). Em 400 este filtro rodava ANTES do CORS e terminava a
    // resposta sem Access-Control-Allow-Origin; um cliente de browser via isso
    // como erro de CORS em vez de um 405 limpo. NAO subir este numero de volta
    // para cima de 300.
    @RouteFilter(250)
    void filter(RoutingContext rc) {
        // startsWith precisa do path CRU: /mcp/messages/ (a barra final e parte
        // do prefixo) tem que continuar batendo. Ja o equals abaixo usa o path
        // sem a barra final, para casar tanto /mcp/sse quanto /mcp/sse/.
        String rawPath = rc.normalizedPath();
        String path = withoutTrailingSlash(rawPath);
        if (LEGACY_SSE_PATH.equals(path) || rawPath.startsWith(LEGACY_MESSAGES_PREFIX)) {
            rc.response()
                    .setStatusCode(404)
                    .putHeader("Content-Type", "application/json")
                    .end(LEGACY_GONE);
            return;
        }
        if (!MCP_PATH.equals(path)) {
            rc.next();
            return;
        }
        HttpMethod method = rc.request().method();
        if (HttpMethod.GET.equals(method)) {
            // 405 e o que a spec 2025-03-26 prescreve para "nao ofereco stream
            // server->client", e todo cliente trata como "siga em frente".
            // Allow lista tudo que este recurso de fato responde: POST (tool
            // calls) e DELETE (teardown), alem do OPTIONS do preflight de CORS.
            rc.response().setStatusCode(405).putHeader("Allow", "POST, DELETE").end();
        } else if (HttpMethod.DELETE.equals(method)) {
            // Teardown de sessao: nao ha sessao real a destruir, entao sempre ok.
            rc.response().setStatusCode(204).end();
        } else {
            rc.next();
        }
    }

    // O router do Vert.x ignora uma barra final ao casar path exato; o equals
    // daqui nao. Sem isto, um cliente configurado com /mcp/ escapa do filtro.
    // /mcp/messages/ (prefixo) nao e afetado: sua barra final faz parte do
    // proprio prefixo comparado com startsWith, nao do path normalizado aqui.
    private static String withoutTrailingSlash(String path) {
        return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }
}
