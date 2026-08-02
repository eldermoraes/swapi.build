# OpenAPI como fonte única de documentação — Design

**Data:** 2026-08-02
**Status:** aprovado (design validado em conversa; aguardando plano de implementação)

## Objetivo

Adotar OpenAPI como contrato canônico da API pública do swapi.build, gerado do código
e servido em `/openapi.json`, e fazer a página `/documentation` do site renderizar a
partir dele — eliminando a duplicação hardcoded de `documentation.ts`.

## Motivação (valor de produto)

Hoje a documentação do site é um array mantido à mão
(`swapi-app/src/main/webui/src/pages/documentation.ts`), que duplica o que o backend
define e pode divergir silenciosamente. Não existe contrato machine-readable da API.

Beneficiários (todos confirmados pelo Elder):

1. **Devs consumidores** — spec pública em URL estável + docs interativas com "try it".
2. **O próprio site** — `/documentation` gerada da spec; docs nunca desatualizam.
3. **Geração de clients** — SDKs via openapi-generator/Postman a partir da spec.
4. **Agentes/LLMs** — descoberta REST via OpenAPI, complementando o MCP server em `/mcp`.

## Decisões de design

| Decisão | Escolha |
|---|---|
| Fonte da spec | Gerada do código em build time (`quarkus-smallrye-openapi` + annotations MicroProfile OpenAPI). Nada escrito à mão. |
| Onde vive o conteúdo | 100% no backend: descrições de endpoints, parâmetros e campos são annotations no Java. Frontend só renderiza. |
| URL da spec | `/openapi.json` (`quarkus.smallrye-openapi.path`). |
| UI de docs | Página própria do site, com a identidade visual atual — sem Swagger UI em produção (fica dev-only, default Quarkus). |
| Consumo pelo frontend | Fetch em runtime de `/openapi.json` (sem bake no build do Vite). |
| `servers:` na spec | Ausente — spec relativa ao host que a serve, coerente com o base-url por request. Nenhum domínio hardcoded (invariante do projeto). |

Abordagens descartadas: spec estática à mão (recria a duplicação); spec bakeada no
frontend em build time (acoplamento de builds sem ganho — o fetch é um GET local pequeno).

## Arquitetura

```
Código Java (annotations) ──build──▶ /openapi.json ──▶ /documentation (fetch + render + try-it)
                                          ├──▶ devs (client generation)
                                          └──▶ agentes/LLMs
```

### Backend

- Extensão `quarkus-smallrye-openapi` gerenciada pelo BOM (sem versão pinada).
- Info global: título "swapi.build — Star Wars API", versão herdada do `pom.xml`,
  descrição, licença Apache 2.0, link para o site e menção ao endpoint MCP (`/mcp`).
- Por resource de entidade (6, mais o root `ApiResource`): `@Tag`; `@Operation` (summary/description) em list, by-id, random
  e search; `@Parameter` para `:id` e `?search`; `@APIResponse` explicitando o
  contrato **200/404** (o 202 histórico está aposentado e não aparece); exemplos de
  resposta nos endpoints by-id.
- Entidades (6 + `SWObject`): `@Schema(description=...)` na classe e em cada campo.
  É o grosso do trabalho e o que dá qualidade real à spec (tipos e semântica de
  campos como `mass`, `homeworld` etc.).
- `auto-add-server` desligado se a extensão tentar injetar um server absoluto.

### Config / roteamento

- `quarkus.smallrye-openapi.path=/openapi.json`.
- O SPA routing do Quinoa (`enable-spa-routing=true`) não pode engolir
  `/openapi.json`; se necessário, `quarkus.quinoa.ignored-path-prefixes`. Coberto
  por teste, não por esperança.
- Medir binário nativo e cold start antes/depois no deploy preview.

### Frontend (`/documentation`)

- `documentation.ts` reescrita: fetch de `/openapi.json` → agrupar operações por
  `tag` → renderizar os blocos visuais atuais (method badge, path, descrição) a
  partir da spec.
- Novo: tabela de campos por resource (nome, tipo, descrição) derivada de
  `components.schemas`.
- Novo: "try it" por endpoint — inputs para `id`/`search`, fetch contra a própria
  API, resposta renderizada com o `json-highlight` existente; 404 exibido como
  resposta legítima do contrato.
- Link para baixar `/openapi.json` + exemplo de client generation.
- Tipos do subconjunto OpenAPI consumido em `types.ts`. Sem dependência JS nova.

## Erros e casos de borda

- Falha no fetch da spec → estado de erro amigável com link direto para
  `/openapi.json` (nunca página em branco).
- "Try it" com id inexistente → 404 exibido normalmente.
- Não existe caso "spec ausente" em runtime (gerada em build).

## Testes (TDD, porta 8081)

Backend (`@QuarkusTest` + REST Assured):

- `GET /openapi.json` → 200, JSON válido, `openapi: 3.x`.
- Todos os paths esperados presentes (6 resources × 3 paths + root = 19; list e
  search compartilham o mesmo path com query param).
- Contrato 200/404 documentado em cada operação by-id.
- Nenhuma entrada `servers` com domínio absoluto.
- Schemas das 6 entidades presentes, com descrições não vazias.
- Suíte de regressão atual continua verde (rotas intactas).

Frontend: sem infra de teste JS no repo — não será criada para isso (YAGNI).
Validação da página via checklist manual no deploy preview, como nas features
anteriores do site.

## Processo de execução (estabelecido pelo Elder)

Spec aprovada → plano (`docs/superpowers/plans/`) → aprovação → branch →
implementação por **subagente Opus** (Agent tool, `model: opus`) seguindo o plano com
TDD, enquanto a sessão principal monitora, revisa diffs, roda a suíte completa e
valida cada etapa. Merge e deploy pelo ciclo do repo (`docs/DEPLOY.md`,
preview → produção → verificação por curl).

## Fora de escopo

- Swagger UI em produção.
- Mudança de serializador (JSON-B permanece).
- Observabilidade (avaliada e adiada em decisão separada).
- Qualquer mudança de comportamento dos endpoints — a spec documenta o contrato
  existente, não o altera.
