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

    @RouteFilter(400)
    void filter(RoutingContext rc) {
        if (!MCP_PATH.equals(rc.normalizedPath())) {
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
