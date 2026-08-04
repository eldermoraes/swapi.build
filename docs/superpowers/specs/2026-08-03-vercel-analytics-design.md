# Vercel Web Analytics no swapi.build

**Data:** 2026-08-03
**Status:** Aguardando aprovação

## Problema

O Web Analytics do projeto não coleta nada. O diagnóstico inicial desta sessão
("Analytics não está habilitado") estava **errado** e é corrigido aqui: a
consulta `GET` de dados devolve `404 {"code":"not_found","message":"Web
Analytics not found."}`, mas isso é ausência de *dados*, não de *feature*. O
estado real do projeto (`GET /v9/projects/swapi-build`, 2026-08-03) é:

```json
"webAnalytics":  {"id": "KQIJOupapJr2pTCOW9qoJurIb", "enabledAt": 1785796393615},
"speedInsights": {"id": "JPo9YoqHNQvNvVsea7dJ92Z9buh", "hasData": false}
```

`enabledAt` = **2026-08-03T22:33:13Z** — o clique no dashboard funcionou.
Web Analytics **e** Speed Insights estão ligados no lado do servidor.

O que falta é o outro lado: **nada no frontend envia pageview**. O Vercel só
conta o que o script do cliente reporta, e a SPA não carrega script nenhum.

Duas causas se somaram:

1. **O pacote foi instalado no lugar errado.** Existe um `/package.json`
   (untracked, na raiz do repo) com `@vercel/analytics@^2.0.1`, junto de
   `node_modules/` e `package-lock.json`. O frontend real é
   `swapi-app/src/main/webui` (Vite + TS vanilla), buildado pelo Quinoa dentro
   do `Dockerfile.vercel`. Nada na raiz entra no build — esses três artefatos
   são inertes.

2. **O fluxo do dashboard pediu GitHub porque queria abrir um PR.** O aviso
   "Missing Git Source" existia porque o projeto foi criado por `vercel deploy`
   (CLI), sem repositório conectado. A UI de setup do Analytics tenta adicionar
   o pacote via PR no repo, e é esse fluxo que travava — não a habilitação do
   Analytics, que é independente de Git e de fato já tinha ocorrido.

### O efeito colateral que a conexão do GitHub criou

O repositório foi conectado em **2026-08-03T22:34:38Z**:

```json
"link": {"type":"github","org":"eldermoraes","repo":"swapi.build",
         "productionBranch":"main", ...},
"rootDirectory": null
```

Isso liga o auto-deploy do Vercel Git com `main` como branch de produção, e
`rootDirectory` nulo significa **build a partir da raiz do repositório**. Esse é
exatamente o modo de falha já documentado no `docs/DEPLOY.md`:

> `Expected VCR image registry vcr.vercel.com: <detect>` — Deploy ran from the
> repo root. Re-run from `swapi-app/`.

Ou seja: no estado atual, o próximo `git push` para `main` dispara um build que
falha. E contraria diretamente um fato não-negociável do `CLAUDE.md` ("There is
no git-push auto-deploy. Deploys are CLI-only").

### O risco de entrega, que é o ponto mais importante

`quarkus.quinoa.enable-spa-routing=true` faz a aplicação responder o `index.html`
para qualquer caminho não reconhecido. Medido em produção hoje:

```
GET /_vercel/insights/script.js  → 200, content-type: text/html   (é o index.html)
GET /_vercel/bogus/xyz.js        → 200, content-type: text/html
```

O script do Analytics é servido pela borda do Vercel em `/_vercel/insights/*`.
Se a borda **não** interceptar esse caminho, o fallback da SPA devolve HTML com
status 200 e o Analytics falha silenciosamente — sem erro visível, sem dado.

A hipótese de trabalho é que a interceptação é injetada **por deployment**: o
deployment em produção é de 2026-08-03T20:02:44Z, **anterior** ao `enabledAt` das
22:33Z, o que explica o HTML. Isso é hipótese, não fato verificado — e vira o
critério de aceite do preview.

## Decisões

### 1. Instrumentar o frontend com `@vercel/analytics` em `webui`

Dependência em `swapi-app/src/main/webui/package.json` e `inject()` no
`src/main.ts`, com modo explícito para não poluir o dev server:

```ts
import { inject } from '@vercel/analytics';
inject({ mode: import.meta.env.PROD ? 'production' : 'development' });
```

Preferido à tag `<script>` crua no `index.html` porque fica versionado, passa
pelo `tsc` do `npm run build` e o modo dev/prod é explícito.

### 2. Instrumentar também o Speed Insights

`speedInsights` já está habilitado no projeto e, sem script, é config morta —
o mesmo bug que estamos corrigindo para o Analytics. `@vercel/speed-insights`
usa o mesmo mecanismo de borda (`/_vercel/speed-insights/*`), então não adiciona
risco novo: ou os dois funcionam, ou os dois falham no mesmo ponto.

Esta é uma inclusão deliberada além do pedido original, feita porque o custo é
uma linha e a alternativa é deixar uma feature ligada e quebrada.

### 3. Remover `/package.json`, `/package-lock.json` e `/node_modules` da raiz

São untracked e inertes. Deixá-los cria a impressão falsa de que a raiz do repo
é um projeto Node.

### 4. Manter o GitHub conectado, mas desligar o auto-deploy

`vercel.json` na raiz do repositório:

```json
{ "$schema": "https://openapi.vercel.sh/vercel.json",
  "git": { "deploymentEnabled": false } }
```

Racional:

- **Resolve o "Missing Git Source"** — que era o incômodo visual original. O
  repositório aparece no dashboard, com metadados de commit.
- **Neutraliza o risco** descrito acima: nenhum push dispara build.
- **Custo zero para o caminho que funciona.** O deploy roda de `swapi-app/`, e
  a CLI só envia o conteúdo desse diretório. Um `vercel.json` na raiz está fora
  dessa árvore e é inerte para o deploy por CLI. Com `rootDirectory: null`, é
  exatamente o arquivo que o Vercel lê para decidir o gatilho do Git.
- **É reversível** com uma linha, quando o CD de verdade for construído.

### 5. Não evoluir agora para deploy contínuo por Git

O usuário abriu essa porta e ela é legítima, mas não cabe neste ciclo:

- Exige trocar `rootDirectory` de `null` para `swapi-app`. A documentação do
  Vercel não esclarece como essa configuração interage com `vercel deploy`
  rodado de dentro de `swapi-app/` — o risco concreto é o caminho virar
  `swapi-app/swapi-app` e **quebrar o único caminho de deploy que funciona**.
- Validar isso custa um build de imagem nativa de 10–25 min por tentativa.
- O runbook atual é preview → verificação manual por curl → produção. Auto-deploy
  em push para `main` passaria por cima dessa disciplina; um CD honesto precisaria
  reproduzir as verificações do `docs/DEPLOY.md` como gate automatizado.

Isso é trabalho de spec própria, não um adendo. Fica registrado como próximo
passo possível.

## Sobre TDD

Este ciclo não tem superfície testável nas suítes existentes, e é melhor dizer
isso do que inventar um teste. As 16 suítes Java cobrem REST, MCP, OpenAPI,
cache e base-url; nenhuma toca o bundle da SPA, e o `webui` não tem
infraestrutura de teste (só `lint` e `format`). Montar vitest para uma chamada
de `inject()` seria desproporcional.

Foi considerado e **descartado** um guard via
`quarkus.quinoa.ignored-path-prefixes=/_vercel` — que faria a aplicação
devolver 404 em vez de HTML, tornando a falha ruidosa e testável. O padrão
dessa propriedade é derivado (`quarkus.rest.path`,
`quarkus.http.non-application-root-path`) e defini-la **substitui** esse padrão,
com risco de quebrar `/q/*` e o roteamento REST. Risco de regressão real, num
app afinado, por um guard que só importa num modo de falha que o preview detecta
de qualquer forma.

Os gates são, então:

1. `./mvnw test` verde (regressão: o build do Quinoa precisa continuar passando
   com a dependência nova).
2. `npm run build` e `npm run lint` no `webui`.
3. **Verificação no preview** (o gate que importa):

```bash
curl -s -o /dev/null -w '%{http_code} %{content_type}\n' -b jar.txt \
  "https://<preview-host>/_vercel/insights/script.js"
# esperado: 200 application/javascript  — se vier text/html, a borda não
# interceptou e o Analytics não funciona neste projeto container
```

4. Após produção: um pageview real precisa aparecer no dashboard.

## Critérios de aceite

- [ ] `/_vercel/insights/script.js` no preview responde JavaScript, não o `index.html`.
- [ ] `/_vercel/speed-insights/script.js` idem.
- [ ] Suíte Java verde; `npm run build` e `npm run lint` limpos.
- [ ] Raiz do repo sem `package.json`, `package-lock.json`, `node_modules/`.
- [ ] Push para `main` não dispara deployment.
- [ ] `docs/DEPLOY.md` com o passo de verificação do Analytics.
- [ ] `CLAUDE.md` registrando que o repo está conectado ao GitHub com auto-deploy
      desligado — o fato "deploy é só por CLI" continua verdadeiro, mas agora por
      configuração explícita, não por ausência de conexão.

## Se o critério 1 falhar

Se a borda não interceptar `/_vercel/*` nem depois de um deployment novo, então
Web Analytics não é entregável neste projeto `framework: container` pelo caminho
padrão. Nesse caso: reverter a instrumentação, desabilitar as duas features no
projeto e reportar a limitação — sem tentar contornar com endpoints customizados,
que trocariam um problema conhecido por um artesanato frágil.
