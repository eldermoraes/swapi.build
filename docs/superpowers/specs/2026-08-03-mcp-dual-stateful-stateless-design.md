# MCP: suporte simultâneo a clientes stateful e stateless

**Data:** 2026-08-03
**Status:** Aguardando aprovação
**Revisada em 2026-08-03**, depois do trabalho de cache de borda
(`d70faf2..edc98d5`). O diagnóstico e as decisões 1–3 não mudaram — o cache vive
em outra camada (`CacheControlFilter` é JAX-RS sob `@ApplicationPath("/api")` e
não alcança a rota Vert.x do `/mcp`, que é POST e nunca cacheado). A decisão de
CORS foi reescrita: a versão original afirmava que `origins=*` emite um `*`
literal, o que é falso, e essa premissa virou risco de envenenamento de cache
depois que `/api` passou a ter `s-maxage=31536000`. A decisão 5 (timeout) é nova.

## Problema

O `/mcp` do swapi.build atende clientes stateless (spec `2026-07-28`) com
confiabilidade total e clientes stateful (Streamable HTTP `2025-03-26` a
`2025-11-25`) **de forma intermitente e quebrada**. A causa não é a spec nem a
extensão: é a topologia de deploy.

A sessão MCP vive num `ConcurrentMap` dentro de
`io.quarkiverse.mcp.server.runtime.ConnectionManager`, na heap de **uma**
instância (confirmado por `javap` no jar 2.0.0.Beta3). O Vercel escala o
container horizontalmente e não oferece sticky sessions. Um `Mcp-Session-Id`
emitido pela instância A, quando roteado para a instância B, produz
`404 Mcp session not found`.

Medições em produção (2026-08-03, antes de qualquer mudança):

| Cenário | Resultado |
|---|---|
| Cliente stateless, 12 e 24 requests concorrentes | 100% HTTP 200 |
| `initialize` stateful (`2025-06-18`) | 200 + `Mcp-Session-Id` |
| 5 `tools/call` sequenciais na mesma sessão | 5× 200 (instância quente) |
| 12 `tools/call` concorrentes, mesma sessão — rodada 1 | 7× 200, **5× 404** |
| 12 `tools/call` concorrentes, mesma sessão — rodada 2 | 4× 200, **8× 404** |
| `GET /mcp/sse` (legado `2024-11-05`) → `POST` no endpoint anunciado | **404** |

O mesmo transporte legado funciona perfeitamente em instância única (validado em
dev mode local), o que confirma que o defeito é exclusivamente topológico.

Isso corrige duas afirmações existentes no repositório:

- `docs/DEPLOY.md` atribui o sintoma a cold start (*"Transient 404 on first
  stateful MCP connect — serverless cold start. Retry resolves it."*). Não é
  cold start: é ausência de afinidade de instância. Piora sob concorrência, e
  agentes chamam tools em paralelo.
- `CLAUDE.md` e a avaliação de 2026-07-31 registram "stateless; nunca padrões
  stateful". A extensão nunca implementou stateless como modo exclusivo — o
  servidor aceita stateful hoje, e o faz mal. A regra descrevia uma intenção,
  não o comportamento.

## Decisão

Habilitar o modo `auto-init` da extensão e sanear as bordas do endpoint, de
forma que **todo** cliente Streamable HTTP — de `2025-03-26` a `2026-07-28` —
funcione de verdade, e que clientes do transporte legado falhem de imediato e
com clareza em vez de travar.

Esta é a solução recomendada pelo próprio mantenedor da extensão. A issue
[#876](https://github.com/quarkiverse/quarkus-mcp-server/issues/876) pede
exatamente uma flag `stateless=true` (como o `Stateless = true` do SDK C#);
mkouba preferiu corrigir o `auto-init` (PR #878) a criar config nova, e o autor
da issue confirmou que resolve — *"allows us to scale beyond a single replica"*,
testado com Claude Code. Citação do mantenedor: *"Just
`quarkus.mcp.server.http.streamable.auto-init=true` should be enough."*

O custo declarado é criar e destruir uma sessão descartável por request, que o
mantenedor chama de workaround ineficiente. **No swapi.build esse custo é
irrelevante:** as quatro tools são read-only puras e não usam nenhum recurso de
sessão (sem sampling, elicitation, roots, progress, subscriptions). Não existe
estado a preservar, logo não existe estado a perder.

### 1. `auto-init`

```properties
quarkus.mcp.server.http.streamable.auto-init=true
```

Efeito validado localmente na nossa app (dev mode, sem alterar arquivos):

| Cenário | Default | Com `auto-init` |
|---|---|---|
| `POST` com session id desconhecido | 404 | **200** |
| `POST` sem session id nenhum | 404 | **200** |
| 12 concorrentes, 12 session ids desconhecidos distintos | — | **12× 200** |
| `initialize` continua devolvendo `Mcp-Session-Id` | sim | **sim** |
| Handshake stateful completo (`initialize` → `initialized` → `tools/list`) | ok | **ok**, negocia `2025-06-18` |
| Cliente stateless `2026-07-28` | ok | **ok** |

Um cliente stateful bem-comportado não percebe diferença alguma; um cliente que
cai em outra instância passa a funcionar em vez de receber 404.

### 2. `GET /mcp` → 405, `DELETE /mcp` → 204

Com `auto-init` ligado, `GET` e `DELETE` com sessão estrangeira **continuam
respondendo 404** (medido). Isso é pior que inútil: pela spec `2025-03-26` o
servidor **pode** responder `405` a um `GET` quando não oferece stream
server→client, e todo cliente trata 405 como "sem stream, siga em frente". Um
404, ao contrário, é legitimamente interpretável como "a sessão morreu" — e
pode derrubar a conexão inteira de um cliente que só queria abrir o stream
opcional.

Como não emitimos nenhuma mensagem server→client, `405` é a resposta correta e
incondicional. `DELETE` (teardown de sessão) passa a `204`: idempotente, sempre
bem-sucedido, porque não há sessão real a destruir.

Implementação: um `@RouteFilter` Vert.x que curto-circuita esses dois métodos em
`/mcp` antes das rotas da extensão.

### 3. `/mcp/sse` legado: rejeição explícita

O transporte HTTP+SSE (`2024-11-05`) é irrecuperável nesta topologia: exige que
o stream SSE aberto e os `POST` subsequentes em `/mcp/messages/<id>` caiam na
mesma instância. Hoje o endpoint está exposto, faz o handshake e **morre em
silêncio** — o pior comportamento possível.

O mesmo filtro passa a responder `404` com corpo JSON informativo em
`GET /mcp/sse` e `POST /mcp/messages/*`, apontando para `/mcp`. Um cliente
legado falha na conexão, e um humano depurando lê o motivo.

Escolha de `404` em vez de `501`/`410`: é o código que os clientes MCP já
tratam como "transporte indisponível" nos seus caminhos de fallback. `410 Gone`
seria semanticamente mais preciso, mas nenhum cliente o trata de forma especial.

### 4. CORS, com `Vary: Origin` obrigatório

O log de startup avisa hoje:

```
WARN  Cross-Origin Resource Sharing (CORS) filter must be enabled for
Streamable HTTP MCP server endpoints with `quarkus.http.cors.enabled=true`
```

Sem isso, nenhum cliente MCP que rode em browser consegue conectar. O filtro
CORS do Quarkus é global, então habilitá-lo alcança também `/api/*` e
`/openapi.json` — o que é desejável por si só (apps de demo em browser passam a
poder chamar o swapi.build via `fetch`), mas colide com o cache de borda
introduzido em 2026-08-03.

**Medido em 2026-08-03**, com `quarkus.http.cors.origins=*`:

```
$ curl -H 'Origin: https://evil.example' localhost:5432/api/people/1
access-control-allow-origin: https://evil.example
access-control-allow-credentials: false
```

O Quarkus **ecoa o `Origin` da request** — não emite um `*` literal — e **não
emite `Vary: Origin`**. Com `/api` e `/openapi.json` agora cacheados na borda
com `s-maxage=31536000`, e `Vary` fazendo parte da chave de cache da Vercel,
isso produz duas falhas reais:

1. Uma request com `Origin: evil.example` congela
   `Access-Control-Allow-Origin: evil.example` na borda por até um ano.
2. Pior no dia a dia: um `curl` sem `Origin` popula a entrada **sem** header
   CORS, e o próximo cliente browser recebe essa cópia e é bloqueado. CORS
   quebrado de forma intermitente e não-determinística — exatamente o defeito
   que esta spec existe para eliminar.

O impacto de confidencialidade é próximo de nulo (dados públicos, read-only,
`Access-Control-Allow-Credentials: false` — o atacante não obtém nada que um
request server-side já não devolva). O impacto funcional é que CORS não
funciona de forma confiável. É a mesma classe de bug do `X-Forwarded-Host` para
a qual o `docs/DEPLOY.md` já tem probe, por outro header.

Decisão:

```properties
quarkus.http.cors.enabled=true
quarkus.http.cors.origins=*
```

mais `Vary: Origin` em **toda** resposta cacheável na borda:

- `/api/*` → o `CacheControlFilter` existente passa a gravar `Vary: Origin`
  junto com o `Cache-Control`, no mesmo `if`. É a mudança mínima e mantém a
  decisão de "definição única" que o filtro já respeita.
- `/openapi.json` → `quarkus.http.filter.openapi.header."Vary"=Origin`.
- `/assets/*` → o filtro de assets também marca cache longo. Browsers não
  mandam `Origin` em GET same-origin de script/CSS, mas mandam em fontes com
  `crossorigin`. **Verificar na implementação** se a borda de fato cacheia essas
  respostas (elas têm `max-age` sem `s-maxage`) e, se sim, aplicar `Vary` ali
  também.

Custo aceito: a borda passa a fragmentar por `Origin`. É leve — requests sem
`Origin`, que são a maioria (todo consumo server-side), compartilham uma única
entrada, e os poucos origins de browser somam poucas entradas a mais.

`/mcp` não precisa de `Vary`: é POST, nunca cacheado na borda.

### 5. Subir o `functionDefaultTimeout`

O cache de borda de 2026-08-03 baixou o `functionDefaultTimeout` de 300s para
15s. A spec daquele trabalho estimou o cold start em ~0,38s; o runbook, no
commit seguinte, mediu **10,9s** e documentou um `504
FUNCTION_INVOCATION_TIMEOUT`. A margem real é de ~4s.

Isso atinge o `/mcp` mais que qualquer outra rota, por um efeito de segunda
ordem do próprio cache: `/mcp` é POST e **nunca** é servido da borda, então toda
chamada MCP invoca a função — enquanto `/api`, agora cacheado, deixa a função
ociosa por muito mais tempo. Resultado: as chamadas MCP passam a ser justamente
as que mais pegam cold start, contra um teto de 15s. Numa demo ao vivo, a
primeira chamada de tool é a que importa.

Subir o timeout para um valor com margem real (60s), pelo `PATCH` de
`resourceConfig` que o `docs/DEPLOY.md` já documenta. Aplica na hora, sem
deploy, e é revertível na hora.

Vale registrar a interação positiva: com `auto-init`, um `initialize` que toma
504 e é retentado pelo cliente passa a funcionar mesmo caindo em outra
instância. Sem `auto-init`, esse retry é 404 garantido. A correção do MCP cobre
parcialmente um risco criado pelo cache — mas subir o timeout ataca a causa.

## Alternativas rejeitadas

- **Só `auto-init`, sem saneamento de bordas.** Resolve o caminho `POST`, que é
  onde as tool calls acontecem, com uma linha. Rejeitada porque deixa o `GET`
  com 404 ambíguo (risco de o cliente abortar a sessão) e deixa o `/mcp/sse`
  enganando clientes legados. O incremento de esforço é um único filtro.

- **Host stateful dedicado para o transporte legado.** Segundo deploy
  single-instance (Fly.io / Railway / Cloud Run com `min-instances=1`) em
  `mcp-legacy.swapi.build`, servindo `2024-11-05` com sessões reais. É a única
  coisa que entregaria compatibilidade máxima *literal*. Rejeitada: cria segunda
  topologia e segundo pipeline de deploy, contraria o princípio "um container, um
  deploy" do `CLAUDE.md`, e serve um transporte deprecated com desligamento
  previsto e público próximo de zero. Se algum dia um cliente legado real
  aparecer, esta é a saída — e o `404` explícito da decisão 3 é o que vai fazer
  esse cliente aparecer, em vez de falhar em silêncio.

- **Estado de sessão compartilhado (Redis / Infinispan).** A issue
  [#510](https://github.com/quarkiverse/quarkus-mcp-server/issues/510) (SPI de
  `ConnectionManager`) foi fechada sem solução; o mantenedor recusou o PR #614
  por incompletude — `McpConnectionBase` guarda objetos não-serializáveis
  (`HttpServerResponse` para SSE) e `ResponseHandlers`/cancelamento precisariam
  de SPI própria. Não existe caminho suportado, e para nós não haveria o que
  compartilhar.

- **Sticky sessions na borda.** Vercel não oferece afinidade de sessão para
  functions, e o Cloudflare está DNS-only (nuvem cinza) — não há edge onde
  aplicar a regra.

- **CORS escopado só no `/mcp`, sem filtro global.** Atrai por ter raio de
  alcance zero sobre o cache: headers CORS estáticos via
  `quarkus.http.filter`, `/api` intocado, nenhum `Vary` necessário. Medido em
  2026-08-03 e **rejeitado por falhar o preflight**:

  ```
  POST    /mcp  → Access-Control-Allow-Origin: *   ok (estático)
  GET     /api  → 0 headers CORS                   ok (borda intocada)
  OPTIONS /mcp  → 405 Method Not Allowed           falha
  ```

  A extensão não trata `OPTIONS` no `/mcp`, e todo request MCP de browser é
  preflightado (por causa de `mcp-session-id` e `content-type:
  application/json`). Um preflight que responde 405 faz o browser bloquear o
  POST seguinte. Seria preciso implementar o preflight à mão dentro do route
  filter — mais lógica, para não ganhar CORS no `/api`, que é um benefício
  desejado. O `Vary: Origin` resolve o problema do cache com uma linha no filtro
  que já existe.

## Escopo

**Muda:**
- `swapi-app/src/main/resources/application.properties` — `auto-init`, CORS,
  `Vary` no filtro do `/openapi.json`, e atualização do comentário que hoje
  afirma "stateless auto-detectado".
- Novo: `swapi-app/src/main/java/com/eldermoraes/mcp/McpTransportFilter.java` —
  o `@RouteFilter` das decisões 2 e 3.
- `swapi-app/src/main/java/com/eldermoraes/CacheControlFilter.java` — gravar
  `Vary: Origin` junto com o `Cache-Control`, no mesmo `if`. Única mudança fora
  do pacote `mcp`, e é consequência direta de habilitar CORS.
- Setting de projeto na Vercel: `functionDefaultTimeout` 15s → 60s, pelo `PATCH`
  documentado no `docs/DEPLOY.md`. Não é arquivo e não exige deploy.
- `CLAUDE.md` — o fato não-negociável "MCP server is stateless Streamable HTTP.
  Never use legacy SSE or stateful patterns" passa a descrever o comportamento
  real: os dois paradigmas no mesmo endpoint, sessões descartáveis por request,
  transporte legado rejeitado de propósito.
- `docs/DEPLOY.md` — corrigir a linha de troubleshooting que culpa cold start, e
  acrescentar um probe stateful à verificação pós-deploy (hoje só existe probe
  stateless).
- `README.md` — a seção MCP diz "stateless (spec 2026-07-28)"; passa a declarar
  que qualquer cliente Streamable HTTP funciona.
- **Texto público do site** (`swapi-app/src/main/webui/src/pages/mcp.ts`,
  `privacy.ts`, `home.ts`) — acrescentado em 2026-08-03, ao escrever o plano.
  Quatro afirmações de que o servidor é stateless-only e que "não há session
  ids", incluindo a **política de privacidade**: *"There are no sessions and no
  server-side state tied to you or your agent."* É falso hoje (o servidor emite
  `Mcp-Session-Id`) e continua falso depois do `auto-init` — o que muda é que a
  sessão passa a ser descartável, não que ela deixe de existir. A redação nova
  precisa ser exata: sessão só em memória, guardando apenas versão de protocolo
  e nome do cliente que o próprio cliente mandou, nunca escrita em disco,
  descartada quando ociosa. Uma imprecisão em política de privacidade não é
  detalhe de copy — e a avaliação de 2026-07-31 registra que política de
  privacidade pública é pré-requisito do Claude Connectors Directory.

**Não muda:** `SwapiTools.java` e as quatro tools; os services; o `Dockerfile.vercel`;
o pipeline de deploy.

**Fora de escopo:**
- Rate limiting / Vercel Firewall. O `docs/DEPLOY.md` já documenta a mitigação
  automática por IP (`x-vercel-mitigated: deny`) e como diagnosticá-la sem
  redeploy. O que fica registrado aqui é o ângulo MCP: `/mcp` nunca é servido da
  borda, então o cache não protege essa rota de rajada nenhuma — e um agente
  fazendo tool calls em paralelo é a rajada. Decisão operacional separada.
- Contribuição upstream propondo que `GET`/`DELETE` respondam 405/204 quando
  `auto-init` está ligado, em vez de 404. Patch pequeno e defensável, bom
  encaixe com atuação na comunidade Quarkus, mas não é caminho crítico —
  o filtro local resolve independentemente.
- Publicação em diretórios/registries e MCP Apps, já fora de escopo desde a
  avaliação de 2026-07-31.

## Riscos e incertezas

1. **O `@RouteFilter` é a única peça não validada.** Todo o resto desta spec foi
   medido. Filtros Vert.x no Quarkus rodam antes das rotas regulares, e as rotas
   da extensão são registradas via `HttpMcpServerRecorder` — a expectativa é que
   o filtro consiga curto-circuitar. Se não conseguir, o plano de implementação
   precisa de um passo de investigação antes de escrever o filtro, e o fallback é
   entregar só a config (`auto-init` + CORS), que já resolve o caminho crítico.
2. **`auto-init` é oficialmente um workaround**, com deprecação prometida "quando
   o modo stateless for codificado na spec". Na prática ele é a ponte para
   clientes pre-`2026-07-28`: o dia em que ele desaparecer é o dia em que não
   precisamos mais dele. Se desaparecer antes disso, voltamos ao estado atual —
   que é o comportamento de hoje, não uma regressão nova.
3. **Continuamos em beta.** Não há release mais recente que a `2.0.0.Beta3`
   (10/07/2026). Checar por Beta4/CR/GA no momento da implementação.
4. **Custo por request.** Criar e destruir uma sessão por request tem custo
   diferente de zero. Não medimos o impacto em latência; o plano deve incluir uma
   comparação de `time_total` antes/depois no preview, para que a decisão fique
   baseada em número e não em suposição.
5. **Fragmentação de cache por `Origin`.** Consequência aceita do `Vary`. A
   estimativa de que é leve não foi medida — o plano deve conferir a taxa de
   `x-vercel-cache: HIT` no `/api` depois do deploy, e comparar com o número que
   o trabalho de cache registrar antes.
6. **`/assets/*` sob CORS.** Não confirmei se a borda cacheia essas respostas
   (têm `max-age` sem `s-maxage`). Se cachear, precisam de `Vary: Origin` como as
   demais. Item de verificação, não de suposição.

## Testes

TDD, teste falhando primeiro. O ponto central é que o teste que hoje **não
existe** é justamente o que pega a regressão: um `POST` com session id
desconhecido.

- **Instância estrangeira (o teste que faltava):** `POST /mcp` com
  `Mcp-Session-Id: <valor inventado>` e um `tools/call` válido → 200 com Luke
  Skywalker no corpo. Falha com 404 na config atual. Via rest-assured, não
  McpAssured — o McpAssured gerencia a sessão e por isso nunca reproduz o bug.
- **Sem session id nenhum:** mesmo `POST` sem o header → 200.
- **Handshake stateful completo não regride:** `initialize` devolve
  `Mcp-Session-Id` e negocia a versão pedida; `notifications/initialized` → 202;
  `tools/list` → 200 com as 4 tools.
- **Bordas:** `GET /mcp` → 405 (com e sem sessão válida); `DELETE /mcp` → 204.
- **Legado rejeitado:** `GET /mcp/sse` → 404 com corpo JSON mencionando `/mcp`;
  `POST /mcp/messages/qualquer-coisa` → 404.
- **CORS:** `POST /mcp` e `GET /api/people/1` com `Origin` → header
  `Access-Control-Allow-Origin` presente; `OPTIONS /mcp` com
  `Access-Control-Request-Method: POST` → 2xx (não 405).
- **`Vary: Origin` nas respostas cacheáveis:** `GET /api/people/1` (200),
  `GET /api/people/9999` (404) e `GET /openapi.json` → `Vary` contém `Origin`.
  Este é o teste que impede a regressão de envenenamento de cache; deve falhar
  antes da mudança no `CacheControlFilter`.
- **`/api/people/random` continua sem `s-maxage`** e portanto não precisa de
  `Vary` — garante que a mudança no `CacheControlFilter` não vazou para o ramo
  não-cacheável.
- **Regressão:** `SwapiToolsTest`, `SwapiStatelessTest`,
  `SwapiBaseUrlDiscoveryTest`, `SwapiBaseUrlOverrideTest`, `OpenApiSpecTest`,
  `CacheHeadersTest` e a suíte REST seguem verdes. Suíte completa antes de cada
  commit.

## Verificação pós-deploy

Além dos checks atuais do `docs/DEPLOY.md`, no preview e em produção:

1. **Probe stateful de ponta a ponta:** `initialize`, capturar o
   `Mcp-Session-Id`, e disparar 12 `tools/call` **concorrentes** com ele.
   Esperado: 12× 200. Hoje esse mesmo teste dá 33–58% de 404 — é a prova direta
   da correção.
2. **Probe com sessão inventada:** `POST` com `Mcp-Session-Id` que nunca
   existiu → 200.
3. **Bordas:** `GET /mcp` → 405; `GET /mcp/sse` → 404.
4. **Stateless não regrediu:** o probe do `DEPLOY.md` (com
   `io.modelcontextprotocol/clientCapabilities`, que é obrigatório na Beta3 —
   omitir devolve 400).
5. **Cliente real:** `claude mcp add --transport http swapi-build
   https://swapi.build/mcp`, listar e chamar uma tool.
6. **Latência:** `time_total` do `tools/call` antes e depois, para dimensionar o
   custo da sessão por request.
7. **Envenenamento de cache por `Origin`** — novo probe para o `docs/DEPLOY.md`,
   no mesmo espírito do probe de `X-Forwarded-Host` que já existe:

   ```bash
   curl -sI -H 'Origin: https://evil.example' https://swapi.build/api/people/1 | grep -i 'vary'
   # deve conter Origin

   curl -s -o /dev/null -w '%header{access-control-allow-origin}\n' https://swapi.build/api/people/1
   # sem Origin na request: deve vir vazio, e a entrada de cache dessa variante
   # nunca deve conter ACAO de terceiros
   ```

   Se o `Vary` não aparecer, purgar o cache antes de seguir.
8. **Cache não regrediu:** `x-vercel-cache` ainda vai de `MISS` para `HIT` no
   mesmo path, conforme o check que o `docs/DEPLOY.md` já descreve. Com `Vary`, a
   segunda request precisa repetir o mesmo `Origin` (ou a ausência dele) para dar
   `HIT`.
9. **Timeout aplicado:** confirmar `functionDefaultTimeout: 60` e medir o cold
   start de um `tools/call` após ociosidade, para saber a margem real.

Rodar a rajada concorrente **contra o preview**, não contra produção — 24
requests paralelos são suficientes para chamar atenção do firewall da Vercel.
