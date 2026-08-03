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

    @RouteFilter(400)
    void filter(RoutingContext rc) {
        String path = rc.normalizedPath();
        if (LEGACY_SSE_PATH.equals(path) || path.startsWith(LEGACY_MESSAGES_PREFIX)) {
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
            rc.response().setStatusCode(405).putHeader("Allow", "POST").end();
        } else if (HttpMethod.DELETE.equals(method)) {
            // Teardown de sessao: nao ha sessao real a destruir, entao sempre ok.
            rc.response().setStatusCode(204).end();
        } else {
            rc.next();
        }
    }
}
