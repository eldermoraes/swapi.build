# Cache de borda e resiliência da API pública — Design

**Data:** 2026-08-03
**Status:** aprovado (design validado em conversa; aguardando plano de implementação)

## Objetivo

Fazer a Vercel CDN servir as respostas determinísticas da API a partir da borda, em vez
de invocar a função em toda requisição, e reduzir o teto de duração da função de 300s
para 15s.

## Motivação

Em 03/08/2026 um teste de carga contra `https://swapi.build` fez a mitigação automática
da Vercel bloquear o IP de origem: 403 em todos os paths do domínio público, com
`x-vercel-mitigated: deny`, enquanto a aplicação seguia no ar (o alias `*.vercel.app`
respondia 200 normalmente). A mitigação expirou sozinha.

A investigação apontou a causa da fragilidade:

```
cache-control: public, max-age=0, must-revalidate
x-vercel-cache: MISS
```

Nenhuma resposta da API é cacheada na borda — **toda** requisição invoca a função em
`iad1`. Esse `Cache-Control` não vem do código (não há nenhum `Cache-Control` no projeto
fora do filtro de assets do Vite): é o default que a Vercel preenche quando a função não
manda nada.

Os dados são imutáveis — seis JSONs embutidos no binário nativo
(`src/main/resources/data/*.json`) — e cada resposta depende apenas de path, query string
e host. É o caso canônico de cache de CDN, e hoje ele não é usado.

Ganhos esperados: rajadas absorvidas na borda (sem disparar mitigação), menos invocações
no plano Hobby, e latência menor para quem está longe de `iad1`.

## Fatos verificados na plataforma

Levantados durante o diagnóstico; sustentam as decisões abaixo.

| Fato | Consequência |
|---|---|
| A borda cacheia respostas de função com `s-maxage`, em todos os planos | O mecanismo está disponível no Hobby |
| Status cacheáveis: 200, 404, 410, 301, 302, 307, 308 | 404 pode ser cacheado; **5xx nunca é** — erro não fica preso na borda |
| A chave de cache inclui método, URL, **host domain**, deployment URL e scheme | `swapi.build`, `www.swapi.build` e `*.vercel.app` têm entradas separadas: a URL embutida na resposta nunca vaza de um host para outro |
| Cada deployment tem chave de cache própria | Todo deploy invalida o cache: TTL longo é seguro |
| TTL máximo aceito: 1 ano | `s-maxage=31536000` é o teto |
| 300s é o **default do plano Hobby** com Fluid Compute | Não é config do projeto: `resourceConfig` não tem `functionDefaultTimeout` |
| `maxDuration` em `vercel.json` indexa **arquivos de função** | Não serve para `framework: container`; usar o default de projeto |
| Regras de IP `bypass` têm limite 0 no Hobby | Não há como isentar um IP da mitigação; resta remover a causa |

## Decisões de design

| Decisão | Escolha |
|---|---|
| Onde aplicar o header em `/api` | `ContainerResponseFilter` JAX-RS novo, no padrão do `BaseUrlFilter` existente |
| Onde aplicar em `/openapi.json` | `quarkus.http.filter` no `application.properties` — a rota é servida pela extensão smallrye-openapi, **fora** do `@ApplicationPath("/api")`, e não passa por filtro JAX-RS |
| Métodos cacheados | GET e HEAD |
| Status cacheados | 200 e 404 |
| Endpoints `/random` | **Não cacheados** — os seis recursos expõem `/random`, que não é determinístico |
| Header | `public, max-age=300, s-maxage=31536000, stale-while-revalidate=86400` |
| HTML do site (`/`, `/docs`) | Fora do escopo — o `index.html` do SPA precisa revalidar para que um deploy chegue rápido ao browser; os assets do Vite já têm `immutable` por hash |
| Timeout | `functionDefaultTimeout` 300s → 15s, como default de projeto |

**TTL assimétrico, de propósito:** `s-maxage` (borda) alto porque o deploy invalida a
entrada; `max-age` (browser) de 5 minutos porque o cache do browser **não** é invalidado
por deploy, e uma correção precisa chegar rápido a quem já visitou.

Abordagens descartadas:

- **Header via `quarkus.http.filter` também para `/api`**: exigiria regex com lookahead
  negativo para excluir `/random`, e o filtro não distingue status.
- **Header via `vercel.json`**: header devolvido pela função sobrepõe o `vercel.json`,
  então não teria efeito sem mudar a app de qualquer jeito.
- **Mover a função para `gru1`**: a API é global; `iad1` é o compromisso melhor, e com o
  cache em pé a região só pesa em MISS e cold start.
- **Proxy da Cloudflare (hoje DNS-only)**: daria cache e rate limit, mas mexe em
  terminação TLS e nos `X-Forwarded-*` de que o base-url discovery depende.

## Arquitetura

```
GET /api/people/1
  └─ Vercel CDN (chave: método + URL + host + deployment + scheme)
       ├─ HIT  → resposta da borda, função intocada
       └─ MISS → função (iad1)
                   └─ CacheControlFilter → Cache-Control: public, max-age=300,
                                            s-maxage=31536000, stale-while-revalidate=86400

GET /api/people/random
  └─ CDN sempre MISS (sem s-maxage) → função sorteia de verdade

GET /openapi.json
  └─ quarkus.http.filter.openapi → mesmo header → cacheável
```

### `CacheControlFilter`

`ContainerResponseFilter` em `com.eldermoraes`, ao lado do `BaseUrlFilter`. Uma única
responsabilidade: decidir se a resposta é cacheável na borda e, em caso positivo, gravar
o header.

Aplica o header quando **todas** valem:

1. método é GET ou HEAD;
2. status é 200 ou 404;
3. o path não termina em `/random`.

Caso contrário, não escreve nada — a resposta segue com o default da Vercel.

## Testes

TDD: cada teste falha antes da implementação. Arquivo novo
`src/test/java/com/eldermoraes/CacheHeadersTest.java`, no padrão RestAssured dos 13
testes existentes.

| Teste | Espera |
|---|---|
| `GET /api/people/1` | 200 com `s-maxage=31536000` e `max-age=300` |
| `GET /api/people/9999` | 404 com o mesmo `Cache-Control` |
| `GET /api/people/random` | 200 **sem** `s-maxage` |
| `GET /openapi.json` | 200 com o `Cache-Control` |
| Suíte atual | Continua verde — nenhum teste existente afirma nada sobre `Cache-Control` |

## Deploy e verificação

O timeout é setting de projeto: aplica sem deploy e é revertido na hora. Pode ir antes,
independente do build nativo.

O cache exige deploy (build nativo, 10–25 min), seguindo `docs/DEPLOY.md`: preview →
verificação → produção.

Verificação de que o cache está de fato ativo — duas requisições ao mesmo path:

```bash
curl -sI <host>/api/people/1 | grep -i 'x-vercel-cache\|cache-control'   # MISS
curl -sI <host>/api/people/1 | grep -i 'x-vercel-cache'                  # HIT
curl -sI <host>/api/people/random | grep -i 'x-vercel-cache'             # sempre MISS
```

Mais as verificações padrão de `docs/DEPLOY.md` (URLs embutidas no host correto, contrato
OpenAPI, probe MCP).

## Riscos

| Risco | Mitigação |
|---|---|
| Cold start de container acima de 15s derruba a primeira requisição | Cold start medido no projeto: ~0,38s. Rollback do setting é imediato e não exige deploy. |
| Teste de carga mirando só os `/random` ainda bate na função | Aceito: são 6 paths de uma API com centenas; cachear quebraria a demo. |
| Correção de dado precisando chegar antes do TTL do browser | `max-age` de 5 min limita a janela; a borda é invalidada pelo deploy. |
