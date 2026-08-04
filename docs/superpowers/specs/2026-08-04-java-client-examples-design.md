# Exemplos de Client em Java — Design

**Data:** 2026-08-04 · **Status:** aguardando aprovação
**Substitui** `2026-08-03-mcp-client-example-design.md`, que deve ser **apagado**: descreve um
desenho abandonado e contém uma conclusão técnica falsa (que seria necessário um `McpTransport`
custom — o `/mcp` passou a atender clientes com sessão, então não é).

## Por que refazer

O exemplo anterior (`examples/quarkus-langchain4j`, PR #5, fechado sem merge) ficou complexo demais
para o que é. A causa foi uma decisão de design minha: colocar o caminho MCP e o caminho REST no
**mesmo projeto, lado a lado para comparação**. Isso arrastou tudo o mais — dois AI services, uma
constante de prompt compartilhada só para provar que os prompts eram iguais, um campo `path` na
resposta para dizer qual caminho respondeu, records envolvendo uma string, um stub de servidor MCP,
uma suíte de testes e uma tabela de tokens no README. Aquilo ensina a **comparar** duas coisas; é
artigo, não exemplo.

Um exemplo tem um assunto só. Então: dois projetos, um assunto cada.

## Escopo

```
examples/java/langchain4j-mcp-client/    LangChain4j consumindo https://swapi.build/mcp
examples/java/quarkus-rest-client/       REST client tipado contra https://swapi.build/api
```

O diretório `java/` abre espaço para exemplos em outras linguagens depois. `examples/quarkus-langchain4j`
é apagado.

Cada projeto é Maven standalone, Quarkus 3.33.3 / Java 25, fora do build e do deploy do `swapi-app`
(o container continua sendo construído de `swapi-app/`). Tudo em inglês — código, comentários,
README, nomes.

## `langchain4j-mcp-client`

Assunto: **as tools vêm do servidor MCP; você não escreve tool nenhuma.**

```
pom.xml · README.md · .gitignore
src/main/java/com/eldermoraes/swapi/mcpclient/
  Archivist.java     # @RegisterAiService + @SystemMessage + @McpToolBox("swapi")
  AskResource.java   # POST /ask — text/plain entra, text/plain sai
src/main/resources/application.properties
```

Extensões: `rest`, `langchain4j-ollama`, `langchain4j-mcp`. Duas properties ligam o cliente MCP
(`transport-type=streamable-http`, `url=https://swapi.build/mcp`), com override comentado para
`http://localhost:5432/mcp`. Modelo `gemma4:31b-cloud` (`quarkus.langchain4j.ollama.chat-model.model-name`);
modelo `-cloud` exige `ollama signin` e rede, e qualquer modelo com tool calling serve no lugar.

```bash
curl -d 'Which planet is Luke Skywalker from, and what is its climate?' localhost:8080/ask
```

O prompt fica inline no `@SystemMessage` da interface — sem classe de prompts. A pergunta chega como
`String` e a resposta sai como `String`; sem record, sem JSON, sem contrato.

## `quarkus-rest-client`

Assunto: **chamar uma API externa do jeito que sempre se fez em Java.** Sem LangChain4j, sem modelo.

```
pom.xml · README.md · .gitignore
src/main/java/com/eldermoraes/swapi/restclient/
  SwapiClient.java     # @RegisterRestClient(configKey = "swapi-api")
  PeopleResource.java  # GET /people/{id} e GET /people?search=name — repassa e devolve
src/main/resources/application.properties
```

Extensões: `rest`, `rest-client-jackson`. Uma property aponta o client
(`quarkus.rest-client.swapi-api.url=https://swapi.build/api`).

```bash
curl localhost:8080/people/1
curl 'localhost:8080/people?search=Luke'
```

Dois métodos porque são as duas formas que todo mundo precisa: path param e query param. Os métodos
devolvem `String` (o JSON cru) — mapear DTOs seria um segundo assunto e não é o deste exemplo.

## Portas

Nenhuma configuração de porta: os dois rodam na default do Quarkus, `8080`. São exemplos distintos e
ninguém sobe os dois ao mesmo tempo, então numerar portas seria uma decisão a mais para o leitor
carregar sem ganho nenhum. (Se subir junto com o `swapi-app`, que usa `5432`, também não colide.)

## Deliberadamente ausente

Sem testes, sem `.mcp.json`, sem `AGENTS.md`, sem `CLAUDE.md`, sem records/DTOs, sem comparação entre
os dois exemplos, sem observabilidade, RAG, agentic, guardrails, OpenAPI, UI, auth, native build,
Dockerfile, CI. Nenhum dos dois vai para produção.

**Consequência aceita:** sem teste, nada avisa se um exemplo quebrar depois — nem uma mudança na
API, nem um upgrade de extensão. Em troca, o leitor abre o projeto e vê dois arquivos.

## Verificação (no lugar dos testes)

Cada exemplo é verificado **rodando**: `./mvnw quarkus:dev`, os curls acima, e a resposta capturada
verbatim. O do MCP precisa de Ollama logado e rede; o do REST precisa só de rede. Uma resposta
verbatim de cada um entra no seu README como "o que saiu numa execução", nunca como saída esperada —
o modelo não é determinístico nem com `temperature=0`.

## Documentação

README curto em cada exemplo: pré-requisitos, como rodar, o curl, a resposta capturada, e o ponto que
ele ensina. Sem tabelas, sem números medidos, sem comparação. O `README.md` da raiz linka os dois, e
o `CHANGELOG.md` ganha uma entrada em `[Unreleased] / ### Added` cobrindo ambos.
