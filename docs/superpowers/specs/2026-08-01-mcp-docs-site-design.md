# MCP Docs — README + Site — Design

**Data:** 2026-08-01 · **Aprovado pelo usuário** (com abas no setup por client)

## Objetivo

Tornar README e site (swapi.build) informativos sobre o MCP server lançado em 01/08/2026, enfatizando a spec stateless 2026-07-28 e ensinando o setup em Claude (Code e Desktop/claude.ai), OpenAI Codex, GitHub Copilot (VS Code) e IBM Bob.

## Decisões (com o usuário)

1. **Página `/mcp` dedicada** no site + item "MCP" no nav + callout no hero da home. `/docs` continua só REST.
2. **Guias completos nos dois lugares**: README com snippets copy-paste dos clients (em `<details>` colapsáveis) e página `/mcp` com o mesmo conteúdo mais contexto. Duplicação aceita.
3. **Abas** para o setup por client na página `/mcp` (não seções empilhadas).
4. **Escopo carona**: commit "MCP polish" do backlog no mesmo ciclo/deploy — `destructiveHint = false` nas 4 `@Tool.Annotations`, asserts de hints das 4 tools, `%test.swapi.public-base-url`.

## Página /mcp — seções

1. Hero: "Use the Star Wars API from your AI agent" + endpoint com botão copy.
2. Callout da spec: stateless 2026-07-28 explicada em 2-3 linhas (sem handshake, sem sessão, requests auto-contidas, ideal p/ serverless), first-party, sem auth, read-only.
3. Tabela das 4 tools (`sw_list`, `sw_get`, `sw_random`, `sw_search`) com enum `resource` e nota FILMS = episode id.
4. Setup por client em **abas**: Claude Code · Claude Desktop/claude.ai · Codex · Copilot (VS Code) · IBM Bob — snippet verbatim (pesquisa verificada 01/08/2026 em docs oficiais) + passo de verificação.
5. Example prompts (3) — também preparam a futura submissão ao Claude Directory.
6. Troubleshooting: retry no 1º connect (cold start serverless).

## Implementação

- `pages/mcp.ts` novo, seguindo padrão das páginas existentes (innerHTML template, escapeHtml, classes do style.css; novas classes CSS mínimas para abas/callout/copy).
- Rota `/mcp` em `main.ts` + link no nav de `index.html`.
- Abas em vanilla TS (botões `role="tab"`, painéis mostrados/ocultos; teclado ← → opcional simples).
- README: seção MCP expandida (ênfase na spec, tabela tools, `<details>` por client, verificação, cold-start note).
- Site sem infra de testes: verificação visual via dev mode/preview; suíte Java cobre o polish.
- Copy em inglês, tom do site atual.

## Fora de escopo

Submissão a diretórios/registries, privacy policy, MCP Apps, refactor arquitetural do baseUrl, bump 2.0 GA (não saiu).

## Fontes dos snippets (verificadas 01/08/2026)

code.claude.com/docs/en/mcp · support.claude.com (custom connectors) · learn.chatgpt.com/docs/extend/mcp + codex-cli 0.146.0 local · code.visualstudio.com/docs/copilot/customization/mcp-servers · bob.ibm.com/docs/ide/configuration/mcp. Flags de precisão: label do menu Claude Desktop pode variar por superfície; IBM Bob não tem CLI de add confirmada (arquivo/UI apenas).
