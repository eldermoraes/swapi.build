# swapi.build como MCP Server — avaliação (blindspot)

**Data:** 2026-07-31
**Pergunta:** faz sentido expor a API também como MCP Server, como porta de entrada para demos com LLMs?
**Veredito curto:** sim — mas pelo motivo de **distribuição e posicionamento**, não pelo ganho funcional. E o timing é excepcionalmente bom: a spec MCP acabou de estabilizar num modelo que favorece exatamente o seu stack, e o nicho "API de dados demo com MCP remoto oficial" está vago.

---

## Achados, por impacto

### 1. O ganho funcional é modesto; o ganho real é distribuição e posicionamento

Avaliação honesta do ecossistema: para uma API REST simples, sem auth, bem documentada, um agente com ferramenta de fetch já se vira — MCP não adiciona muito *funcionalmente* ([WorkOS: MCP vs REST](https://workos.com/blog/mcp-vs-rest)). O que o MCP adiciona de verdade:

- **Distribuição**: presença nos diretórios (MCP Registry oficial, PulseMCP, Glama, mcp.so, Smithery) e no Claude Connectors Directory — conexão de um clique em Claude/ChatGPT/Cursor, inclusive para usuários que não programam e não têm fetch tool.
- **Nicho vago**: não foi encontrada **nenhuma** API pequena de dados demo rodando MCP remoto *first-party*. Vendors grandes (GitHub, Stripe, Cloudflare, Context7) já são padrão; o análogo mais próximo (Open-Meteo) só tem wrappers de terceiros. swapi.build seria cedo, não atrasado.
- **Valor meta — o mais forte para você**: SWAPI já é tema comprovado de *ensino* de MCP (repo do John Papa é explicitamente demo didática; série "Star Wars Copilot" tem lição de MCP). O swapi.build existe para "demos que nunca quebram" — um MCP remoto oficial fecha o círculo: a API vira o próprio assunto de palestras "como construir/consumir MCP". Isso conecta direto com sua atuação como speaker/criador de conteúdo.

### 2. Concorrência: quatro wrappers, todos fracos — o espaço é seu

Verificado via GitHub API em 31/07/2026:

| Repo | Stars | Tipo | Backend |
|---|---|---|---|
| johnpapa/mcp-starwars | 10 | stdio local, demo didática | swapi.dev (parado desde abr/2025) |
| glaucia86/swapi-mcp-server-app | 15 | local, "study purposes" | SWAPI |
| pipeworx-io/mcp-swapi | 0 | parte de gateway | swapi.dev |
| vitormm/mcp-starwars | 0 | local | SWAPI |

Todos locais (stdio), nenhum hospedado, nenhum do dono da API, todos sobre o swapi.dev instável — exatamente a fragilidade que motivou o swapi.build. Um MCP remoto oficial em `swapi.build/mcp` domina esse nicho no dia 1.

### 3. Timing: a spec mudou há 3 dias — construir agora no modelo certo evita retrabalho

- Spec **2026-07-28** (publicada em 28/07/2026): maior revisão da história do protocolo — **Streamable HTTP stateless** (sem handshake, sem session id, cada request auto-contida). Transporte legado HTTP+SSE está deprecated com desligamento em 12 meses.
- Governança madura: MCP foi doado à Agentic AI Foundation (Linux Foundation) em dez/2025; adotado por OpenAI (o Apps SDK do ChatGPT é *construído sobre* MCP), Google e Microsoft. Não é aposta em protocolo de um vendor só.
- Contraponto real: há backlash em 2026 para *coding agents* (padrão CLI + Skills, crítica de token bloat). O consenso emergente é híbrido: Skills/CLI para dev workflow, **MCP para conectividade hosted/consumer — exatamente o caso do swapi.build**.
- Regra derivada: **construir direto no modelo stateless, jamais em SSE legacy ou padrões stateful.**

### 4. Implementação: meio dia de trabalho, zero mudança de infra (Opção A)

**Recomendação: `io.quarkiverse.mcp:quarkus-mcp-server-http` 1.13.1** (estável, 02/07/2026) no mesmo app.

- Tools declaradas com `@Tool`/`@ToolArg` sobre os services que já têm tudo em memória; schema JSON gerado em build time.
- Endpoint `/mcp` (Streamable HTTP) convive com o Jakarta REST `/api/*` no mesmo servidor HTTP — mesmo container, mesmo deploy Vercel, mesmo domínio: **`https://swapi.build/mcp`**.
- Native image suportado pela extensão (falta smoke test no build Mandrel/UBI9 do `Dockerfile.vercel`).
- Extensão ativa: 3 releases em 6 semanas; a linha **2.0.0 (beta)** já mira a spec 2026-07-28 — shipa em 1.13.x agora, bump para 2.0 GA quando sair.
- Cold start do container Vercel (scale-to-zero) é irrelevante: native image sobe em milissegundos e as chamadas stateless são POSTs curtos.

**Descartadas:**
- *Opção B — Worker Cloudflare com `createMcpHandler()`* (Agents SDK v0.20.0): viável e alinhada à spec nova, mas cria segundo codebase/toolchain/deploy e duplica schemas à mão. Fica como fallback se um dia quiser isolar a camada MCP. (Atenção: `McpAgent` virou caminho legado — não usar.)
- *Opção C — `mcp-handler` da Vercel*: é para rotas Next.js/Node; não se aplica a deploy de container customizado.

**Design das tools:** preferir poucas tools genéricas (~4: `list`, `get`, `random`, `search` com parâmetro `resource` enum de 6 valores) em vez de 24 específicas — a crítica de token bloat de 2026 pune servidores que inflam o contexto do agente.

### 5. Sem auth é ok; o risco operacional é tráfego, e a mitigação já está na sua frente

- A spec diz explicitamente: "Authorization is OPTIONAL". Servidor remoto authless para dados públicos read-only é padrão de primeira classe (template oficial da Cloudflare é authless).
- Risco real de servidor MCP público sem auth = abuso/tráfego, não vazamento (os incidentes de segurança de MCP são sobre dados privados/escrita — não se aplicam). Mitigação recomendada (OWASP): **rate limiting na borda** — o Cloudflare já está na frente do domínio; é criar uma rule para `/mcp*`.

### 6. Distribuição tem checklist barato, mas com pegadinhas

Para o **Claude Connectors Directory** (maior distribuição consumer):
- **Privacy policy pública — sem ela, rejeição imediata** (mesmo sem coletar nada);
- `readOnlyHint` correto em **todas** as tools;
- Página de docs pública + **3+ example prompts** + instruções de teste para o reviewer;
- "No authentication" é categoria explicitamente suportada.

Além disso: registrar no **MCP Registry oficial** (namespace verificado por DNS — swapi.build já resolve isso) e nos diretórios comunitários (PulseMCP, Glama, mcp.so, Smithery). O roadmap 2026 traz discoverability via `.well-known` — vale implementar cedo quando estabilizar.

### 7. Custo de manutenção real: churn da spec — absorvido pela extensão

Três revisões relevantes de transporte/auth em ~20 meses. Para quem mantém servidor MCP na mão, isso é custo recorrente; para quem usa a extensão Quarkiverse, o churn é absorvido pelo upgrade de dependência. É o argumento decisivo pela Opção A.

### 8. Bônus futuro: MCP Apps

A spec 2026-07-28 formalizou **MCP Apps** (UI renderizada pelo servidor dentro do chat, padrão cross-vendor Claude/ChatGPT). Abre a demo "card de personagem Star Wars dentro do Claude" — material de palestra de segunda geração, não necessário no v1.

---

## Plano de ação (impacto × esforço)

| # | Ação | Esforço | Impacto |
|---|---|---|---|
| 1 | Spike: extensão 1.13.1 + ~4 tools genéricas `@Tool`, validar em `quarkus:dev` e no build nativo Mandrel | meio dia | Alto — destrava tudo |
| 2 | Deploy no container existente + rate limit rule `/mcp*` no Cloudflare | 1-2h | Alto |
| 3 | Página de docs MCP no site + privacy policy + example prompts | 2-3h | Alto — pré-requisito de distribuição |
| 4 | Registrar: MCP Registry oficial (DNS), PulseMCP, Glama, mcp.so, Smithery; submeter ao Claude Connectors Directory | 2-3h | Alto — é onde mora o ganho |
| 5 | Conteúdo: palestra/artigo "da API que nunca cai ao MCP server oficial" | contínuo | Médio-alto — valor meta |
| 6 | Bump para extensão 2.0 GA (spec stateless) quando sair; `.well-known` discovery | futuro | Médio |
| 7 | MCP Apps (card visual no chat) | futuro | Médio — material de demo v2 |

## Pontos a verificar no spike (não confirmados na pesquisa)

1. Coexistência `/mcp` + `/api/*` (inferida da arquitetura Quarkus; confirmar em dev mode — minutos).
2. Build nativo da extensão no Mandrel/UBI9 do `Dockerfile.vercel`.
3. Qual revisão da spec a 1.13.x implementa (qualquer uma funciona com clients atuais).

## Brief reutilizável (como instruir Claude sobre este assunto daqui pra frente)

> swapi.build: API Star Wars read-only, Quarkus 3.23 + GraalVM native (Mandrel/UBI9), dados estáticos em memória, container único na Vercel atrás do Cloudflare. MCP server: extensão `quarkus-mcp-server-http` (Quarkiverse) no mesmo app, endpoint `https://swapi.build/mcp`, Streamable HTTP **stateless** (spec 2026-07-28), **sem auth**, rate limit no Cloudflare. Nunca: SSE legacy, padrões stateful de sessão, `McpAgent` da Cloudflare (legado), segundo codebase para o MCP. Tools: poucas e genéricas (resource como enum), todas com `readOnlyHint`. Distribuição: MCP Registry oficial + diretórios + Claude Connectors Directory (exige privacy policy pública).
