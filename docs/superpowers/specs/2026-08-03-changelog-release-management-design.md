# Changelog e gestão de releases (retroativo)

**Data:** 2026-08-03
**Status:** Aguardando aprovação
**Issue:** [#2 — Changelog and release management (retroactive)](https://github.com/eldermoraes/swapi.build/issues/2)

## Problema

O swapi.build tem 110 commits, 13 valores de versão distintos já escritos no
`pom.xml`, uma API pública consumida por terceiros — e **zero tags git, zero
GitHub Releases e nenhum changelog**. Um consumidor que veja `info.version:
2.1.0` em `/openapi.json` não tem como saber o que mudou entre a 2.0.2 e a
2.1.0, nem que a 2.0.0 quebrou o contrato de propósito (202 → 200, ids de
registro, 404 em id inexistente). O histórico existe, mas só em mensagens de
commit e nos specs de `docs/superpowers/`.

Não existe também processo definido: o próximo bump de versão depende de alguém
lembrar de tudo que precisa acontecer junto.

## Estado verificado (2026-08-03)

Levantado no worktree, não presumido:

- `git tag -l` → vazio. `gh release list` → vazio.
- Versão corrente: **2.1.0**; produção serve `info.version: 2.1.0`.
- `info.version` do OpenAPI **deriva do pom automaticamente** — não há
  `quarkus.smallrye-openapi.info-version` no `application.properties`, só
  title/description/license.
- `.github/` **não existe**: nenhum workflow no repo hoje.
- Nenhuma referência restante a Docker Hub (era da fase DigitalOcean).
- `swapi-app/Dockerfile.vercel` builda com `-DskipTests` e copia apenas `mvnw`,
  `.mvn`, `pom.xml` e `src` — um teste que leia um arquivo da raiz do repo
  **não roda no build do container** e portanto não pode quebrar o deploy.
- A issue #2 cita `2cf5dfb` como bump da 1.9.0; o commit real é **`c7c878f`**.
  A issue também foi escrita quando a versão corrente era 2.0.2 — a 2.1.0
  nasceu depois e também precisa de tag.

### Histórico de versões reconstruído

Bump = primeiro commit com aquele valor no pom. `tag_at` = último commit da
linha daquela versão, em ordem first-parent. As 13 linhas foram verificadas com
`git show <tag_at>:swapi-app/pom.xml`, e em todas a versão do pom bate com a
versão da linha.

| Versão | bump | `tag_at` | Data de `tag_at` |
|---|---|---|---|
| `1.0.0-SNAPSHOT` | `3c63851` | `f31bfc8` | 2025-05-29 |
| 1.1 | `3491418` | `3491418` | 2025-05-29 |
| 1.2 | `0f293de` | `0f293de` | 2025-06-02 |
| 1.3 | `059db1c` | `059db1c` | 2025-06-03 |
| 1.7 | `f3673ab` | `42f062e` | 2025-08-05 |
| 1.8 | `56e1bb3` | `0815cd1` | 2026-03-09 |
| 1.8.1 | `2371d5f` | `a0c43f5` | 2026-07-31 |
| 1.9.0 | `c7c878f` | `de60e16` | 2026-08-01 |
| 1.9.1 | `0e906b4` | `9fe7a68` | 2026-08-02 |
| 2.0.0 | `4009710` | `c9f4e0b` | 2026-08-02 |
| 2.0.1 | `2beb46c` | `2beb46c` | 2026-08-02 |
| 2.0.2 | `9ec33a3` | `de2192f` | 2026-08-03 |
| 2.1.0 | `00a8e53` | `3fa39e0` | 2026-08-03 |

As versões 1.4, 1.5 e 1.6 nunca existiram. `1.0.0-SNAPSHOT` **não recebe tag**:
snapshot não é release.

A coluna `bump` está em ordem first-parent de `main`, por isso a 2.1.0 aparece
como `00a8e53` (o commit de merge) e não como `3d13db1` — este último é o bump
real, mas vive dentro da branch mergeada e não é alcançável por first-parent. O
`tag_at` é o mesmo nos dois casos (`3fa39e0`), então a decisão não muda.

## Decisões

### 1. Profundidade: histórico completo até 1.1 — 12 tags

`v1.1`, `v1.2`, `v1.3`, `v1.7`, `v1.8`, `v1.8.1`, `v1.9.0`, `v1.9.1`, `v2.0.0`,
`v2.0.1`, `v2.0.2`, `v2.1.0`.

Ressalva registrada e aceita: `1.1`–`1.8` são anteriores à existência pública
do projeto e vários daqueles commits são bring-up de native image ("Trying to
make the native image works"). As entradas dessas versões serão curtas e
descritivas do que o commit realmente fez, sem inventar significado de release
que elas não tiveram.

### 2. Nome das tags: literal ao pom

`v1.1`, não `v1.1.0`. A tag reproduz exatamente o que o `pom.xml` dizia naquele
commit, o que mantém `git show <tag>:swapi-app/pom.xml` coerente com o nome da
tag e o changelog auditável contra o histórico. Consequência aceita: as cinco
tags de dois dígitos não são SemVer estrito, então ferramentas que assumem
`X.Y.Z` as ignorariam — irrelevante, porque nenhuma ferramenta desse tipo entra
no escopo.

### 3. Alvo da tag: último commit da versão

Cada tag aponta em `tag_at` (coluna acima), não no commit do bump — o estado
final que aquela versão alcançou, que é o que de fato rodou com aquele número.
`git checkout v2.0.0` entrega a 2.0.0 completa.

**Consequência contra-intuitiva, explicitada de propósito:** a data de cada
seção do changelog é a data em que a linha da versão **fechou**, não a do bump.
Isso coloca a migração DigitalOcean → Vercel/Cloudflare (2026-07-23) dentro da
seção **1.8.1** — o pom dizia `1.8.1` quando ela aconteceu — e datam a seção
1.8.1 em 2026-07-31, não em 2026-03-10 (data do bump). É a leitura correta:
o que foi entregue sob o número 1.8.1 inclui a migração.

### 4. Tags anotadas, com data retroagida

`git tag -a` com `GIT_COMMITTER_DATE` igual à data do commit apontado. Sem
isso, as 12 tags nasceriam todas com taggerdate de hoje e
`git tag --sort=taggerdate` mostraria uma ordem falsa. A mensagem da tag é a
versão mais o resumo de uma linha da seção correspondente do changelog.

**Limite conhecido:** GitHub Releases não aceitam data retroativa
(`gh release create` não tem parâmetro de `created_at`). As 12 releases vão
aparecer como criadas em 2026-08-03, com as tags datadas corretamente. Não há
contorno; fica documentado em `docs/RELEASE.md`.

### 5. Todas as 12 tags viram GitHub Release

Notas extraídas da seção correspondente do `CHANGELOG.md`, via
`gh release create <tag> --notes-file -`. A `v2.1.0` fica como *Latest*.

### 6. Automação: runbook + teste-guarda, sem CI

Nada de GitHub Actions, nada de Release Please. O processo vive em
`docs/RELEASE.md` e o único modo de falha real — bumpar a versão e esquecer o
changelog — é pego por um teste JUnit que o ciclo de desenvolvimento já executa
antes de todo commit (`cd swapi-app && ./mvnw test`).

Release Please foi descartado por três motivos concretos: exigiria conventional
commits estritos (o histórico só é conventional nos commits recentes), tomaria
posse do `CHANGELOG.md` e do `pom.xml` — destruindo o texto editorial
retroativo que é justamente o valor deste trabalho — e ainda assim não deploya
nada, porque deploy aqui é CLI-only por design.

### 7. 2.1.0 fica no tip de main; o trabalho desta branch vai para Unreleased

`v2.1.0` aponta em `3fa39e0`, exatamente o estado que produção serve como
2.1.0. Este trabalho (changelog, runbook, teste) entra em `## [Unreleased]` e
acumula até o próximo bump real. **Sem bump e sem deploy:** o artefato não muda
de comportamento, a versão do pom permanece `2.1.0`, e portanto `info.version`
da spec pública continua sincronizado.

## Arquitetura

Quatro unidades independentes, cada uma verificável sozinha:

### `CHANGELOG.md` (raiz do repo)

Formato [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/), em
**inglês** — é artefato público, como o README e a spec OpenAPI. Estrutura:

```
# Changelog
<preâmbulo: formato, SemVer, nota sobre a reconstrução retroativa>

## [Unreleased]
### Added
- Changelog, retroactive tags/releases, release runbook, version guard test.

## [2.1.0] - 2026-08-03
### Added / Changed / Fixed / Security / Removed  (só as categorias usadas)
...
<... 12 seções, mais nova primeiro ...>

[Unreleased]: https://github.com/eldermoraes/swapi.build/compare/v2.1.0...HEAD
[2.1.0]: https://github.com/eldermoraes/swapi.build/compare/v2.0.2...v2.1.0
...
[1.1]: https://github.com/eldermoraes/swapi.build/releases/tag/v1.1
```

Categorias: apenas as seis do Keep a Changelog. Itens de infraestrutura
(migração de plataforma, cache de borda, timeout de função) vão em `Changed`;
patches de dependência de desenvolvimento vão em `Security`. Não se inventa
categoria nova.

**Fontes por faixa, nesta ordem de precedência:** `git log <tag_anterior>..<tag>`
como fonte bruta dos fatos; os specs e planos de `docs/superpowers/` como fonte
de intenção (existem para a era 1.9+); `docs/DEPLOY.md` para o histórico de
infraestrutura; a lista de marcos da issue #2 como checklist de cobertura. Onde
as fontes não bastarem — as versões de 2025 — a entrada é curta e factual em
vez de detalhada e especulativa.

Marcos que a issue exige que apareçam, e onde cada um cai pela regra da decisão 3:

| Marco | Data | Seção |
|---|---|---|
| Migração DigitalOcean → Vercel/Cloudflare | 2026-07-23 | 1.8.1 |
| MCP server (primeira versão) | 2026-07-31 | 1.9.0 |
| Contrato público: 202 → 200, ids de registro, 404 | 2026-08-02 | 2.0.0 |
| OpenAPI como fonte única + `/docs` renderizado da spec | 2026-08-02 | 2.0.x |
| Descoberta de base-url por request | 2026-08-02 | 2.0.x |
| MCP stateful + stateless no mesmo endpoint | 2026-08-03 | 2.1.0 |

A alocação exata dos três itens marcados "2.0.x" sai do `git log` de cada faixa
durante a implementação — não se chuta aqui.

### `docs/RELEASE.md`

Runbook do processo daqui pra frente, no mesmo tom de `docs/DEPLOY.md`
(sequência numerada + tabela de troubleshooting). Sequência:

1. Decidir a versão por SemVer — **mudança de contrato público é major**, e o
   precedente é a 2.0.0 (202 → 200/404).
2. Bump em `swapi-app/pom.xml`.
3. Escrever a seção no `CHANGELOG.md`, promovendo o conteúdo de `[Unreleased]`,
   e adicionar o link de comparação no rodapé.
4. `cd swapi-app && ./mvnw test` — o teste-guarda falha se os passos 2 e 3
   divergirem.
5. Commit, merge em `main`.
6. Tag anotada em `main` e push da tag.
7. `gh release create` com as notas da seção do changelog.
8. Deploy conforme `docs/DEPLOY.md` (preview → verificar → produção), porque o
   bump muda `info.version` da spec pública.
9. Verificação pós-deploy: `curl -s https://swapi.build/openapi.json` tem que
   devolver a versão nova.

O documento registra também por que não há automação de CI, e o limite de data
das releases (decisão 4).

### Teste-guarda — `ChangelogVersionTest`

Em `swapi-app/src/test/java/com/eldermoraes/`, sem dependência nova (JUnit 5 e
o que a suíte já usa). Três asserções:

1. `CHANGELOG.md` é localizado subindo diretórios a partir de `user.dir` (a
   suíte roda de `swapi-app/`, o arquivo está um nível acima). Se não achar,
   falha com mensagem dizendo onde procurou — não passa por omissão.
2. A versão corrente da aplicação tem seção `## [<versão>] - <data ISO>` no
   changelog, com ao menos um item de conteúdo abaixo. Uma seção vazia falha.
3. A versão é lida de `quarkus.application.version` via `ConfigProvider`. **Se
   essa propriedade não estiver disponível no runtime de teste, o fallback é
   parsear o `<version>` do `pom.xml`** — qual dos dois vale se decide no
   primeiro ciclo de TDD, medindo, não presumindo.

### Teste de sincronia da spec — `OpenApiVersionTest`

`GET /openapi.json` → `info.version` igual à versão da aplicação. Hoje isso é
automático no Quarkus; o teste existe para impedir que alguém fixe
`quarkus.smallrye-openapi.info-version` à mão e desincronize a spec pública das
releases — exatamente a preocupação levantada na issue #2. Cabe em ~10 linhas
com RestAssured, que a suíte já usa.

### Ligações

- `README.md`: link para o `CHANGELOG.md` e para a página de Releases.
- `CLAUDE.md`: o ciclo de desenvolvimento ganha o passo de release entre
  **Merge** e **Deploy**, apontando para `docs/RELEASE.md`.
- `docs/DEPLOY.md`: nos pré-requisitos, nota de que um deploy que carrega bump
  de versão pressupõe o `docs/RELEASE.md` cumprido.

## Fluxo de execução

Tags e releases **não podem sair antes do merge**: as notas vêm do
`CHANGELOG.md`, que nasce nesta branch.

1. Branch: `CHANGELOG.md`, `docs/RELEASE.md`, os dois testes, as ligações.
2. Suíte completa verde.
3. Merge em `main` (forma decidida com o usuário no fim da implementação).
4. Suíte verde de novo no resultado do merge.
5. **Confirmação explícita do usuário** → criar as 12 tags anotadas e
   `git push --tags`.
6. **Confirmação explícita do usuário** → criar as 12 GitHub Releases.
7. Fechar a issue #2 referenciando o merge.

Sem deploy: nada no artefato muda.

## Verificação

- `cd swapi-app && ./mvnw test` verde, incluindo os dois testes novos.
- Loop nas 12 tags: `git show <tag>:swapi-app/pom.xml` contém a versão da tag.
  Já validado para os 13 alvos antes de escrever este spec; roda de novo depois
  de criar as tags de verdade.
- `git tag -l | wc -l` → 12; e depois do push,
  `git ls-remote --tags origin | grep -vc '\^{}'` → 12. O `grep -v` é
  obrigatório: tag anotada aparece no `ls-remote` duas vezes (a ref e o
  `^{}` desreferenciado), então um `wc -l` cru diria 24 e pareceria erro.
- `gh release list` → 12 entradas, `v2.1.0` marcada como Latest.
- `gh release view v2.0.0` → notas idênticas à seção 2.0.0 do changelog.
- Teste negativo do guarda: bumpar o pom localmente sem tocar no changelog tem
  que falhar a suíte; reverter em seguida.

## Riscos

| Risco | Mitigação |
|---|---|
| Push de 12 tags é público e chato de reverter | Confirmação explícita antes do push, mesmo com plano aprovado (passo 5) |
| 12 releases notificam quem observa o repo | Confirmação explícita antes da primeira (passo 6) |
| Reconstrução de 2025 é interpretativa | Entrada curta e factual em vez de detalhe inventado; fonte é o `git log` da faixa |
| Teste-guarda quebrar o build do container | Verificado: `Dockerfile.vercel` usa `-DskipTests` e não copia a raiz do repo |
| Teste-guarda passar por omissão (arquivo não encontrado) | Falha explícita com os caminhos procurados, nunca skip |

## Fora de escopo

Nenhum workflow em `.github/`; Release Please e semantic-release descartados
(decisão 6); nenhum gerador de changelog como dependência; nenhum bump de
versão nesta branch; nenhum deploy; nada de Docker Hub (morto no repo); tags
não assinadas; nenhuma tag para `1.0.0-SNAPSHOT`.
