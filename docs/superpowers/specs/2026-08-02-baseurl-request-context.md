# Spec: base URL por contexto de request (fim do estado mutável compartilhado)

Origem: débito arquitetural registrado desde 01/08/2026 (race do `baseUrl`);
Opção B aprovada pelo Elder em 02/08/2026.

## Problema

Entidades são singletons compartilhados (listas estáticas nos services) e cada
request muta o campo `baseUrl` de TODAS elas (`SWObject.baseUrl`, nem volatile)
antes da serialização. Duas requests concorrentes com hosts distintos podem se
intercalar (resposta de A com URLs do host de B), há risco de visibilidade (JMM)
e a classe de bugs `"null/..."` existe por disciplina, não por construção.

## Solução (Opção B — contexto por request)

- `RequestBaseUrl`: holder estático com `ThreadLocal<String>` (`set/get/clear`).
- `SWObject`: perde o campo e o setter; `getBaseUrl()` delega para
  `RequestBaseUrl.get()` e leva o `@JsonbTransient` (no getter — obrigatório para
  o JSON-B não criar uma propriedade `baseUrl` nas respostas).
- REST: `ContainerRequestFilter` (`@Provider`) seta o holder a partir de
  `uriInfo.getBaseUri()` (sem barra final) em toda request. Serialização roda na
  mesma virtual thread (`@RunOnVirtualThread`, thread nova por request) — sem
  residue possível.
- MCP: `SwapiTools.applyBaseUrl()` vira `RequestBaseUrl.set(resolveBaseUrl())`
  (a resolução config-override → HttpServerRequest não muda). Serialização é o
  `jsonb.toJson` dentro da própria tool, mesma thread. Threads de pool podem
  reter residue inofensivo — todo entry point seta antes de ler; sem `clear`
  espalhado (KISS).
- Remoções em cascata: `setBaseUrl` sai de `SWService` e dos 6 services (o loop
  de mutação), e os construtores dos 6 resources param de receber `UriInfo` para
  isso. Escopo `@RequestScoped` dos resources fica como está (mudança de escopo
  é fora deste refactor).

## Teste de aceitação (o RED que prova o race)

Teste de concorrência REST: N requests paralelas alternando
`X-Forwarded-Host` entre dois hosts, cada resposta deve conter apenas as URLs do
seu próprio host. Falha contra o código atual; passa por construção depois.
Suíte existente (28 testes, URLs absolutas em REST e MCP) é a rede de regressão.

## Restrições

- Comportamento externo idêntico: mesmas URLs absolutas, 200/404, MCP igual.
- Branch própria; TDD; suíte completa antes de cada commit; implementador Opus;
  validação do controller; deploy via docs/DEPLOY.md após merge aprovado.
