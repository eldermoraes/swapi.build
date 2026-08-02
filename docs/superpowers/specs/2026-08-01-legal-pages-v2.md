# Spec: v2.0.0 + Privacy Policy e Terms of Use

Origem: decisões do Elder em 01/08/2026 — bump de versão pela mudança de contrato
público (202→200, ids de filmes, 404s) e criação das páginas legais (pré-requisito,
entre outros, para submeter o MCP ao Claude Connectors Directory).

## Item 1 — Versão 2.0.0

- `swapi-app/pom.xml` linha 6: `1.9.1` → `2.0.0`.
- `swapi-app/src/main/webui/package.json`: `1.7.0` → `2.0.0` (alinhar as duas).

## Item 2 — Páginas /privacy e /terms

**Fatos verificados no código (base da honestidade das páginas):**
- Sem contas, sem login, sem cookies próprios, sem analytics/trackers no frontend
  (grep por analytics/gtag/plausible/cookie: zero).
- Backend stateless: nenhuma request é persistida; dataset estático em memória.
- Hospedagem Vercel (logs operacionais de edge — IP, user agent, path — regidos
  pela política da Vercel); DNS Cloudflare em modo DNS-only (sem proxy).
- Código Apache 2.0; dados de fontes comunitárias; disclaimer Lucasfilm/Disney já
  existe no About.

**Estrutura (padrão das páginas existentes):**
- Novos arquivos `pages/privacy.ts` e `pages/terms.ts` (mesmo estilo dos demais).
- Rotas `/privacy` e `/terms` em `main.ts` (getRoute + getPageTitle + switch).
- Links no footer do `index.html`: "Privacy Policy · Terms of Use".

**Conteúdo (inglês, tom claro e curto, sem juridiquês):**

*Privacy Policy:* o que NÃO coletamos (contas, cookies, trackers, dados pessoais
armazenados pela aplicação); o que é processado tecnicamente (logs operacionais do
provedor de hospedagem — Vercel — com IP/user agent/path, para operação e abuso,
retenção conforme política deles); MCP stateless (conteúdo das requests não é
armazenado); sem venda/compartilhamento de dados; mudanças na política (data no
topo); contato via GitHub issues do repositório.

*Terms of Use:* serviço gratuito fornecido "as is", sem garantia nem SLA
(scale-to-zero documentado); uso justo (sem abuso/flooding; rate limiting pode ser
aplicado); dados são conteúdo de fãs de fontes comunitárias, sem garantia de
exatidão; projeto não afiliado à Lucasfilm/Disney (Star Wars é marca deles);
código Apache 2.0 no GitHub; os termos podem mudar (data no topo); contato via
GitHub issues.

Nota: são documentos em linguagem simples redigidos por engenharia, não parecer
jurídico.

## Restrições

- Branch `feat/legal-pages-v2`; TDD onde couber (páginas são conteúdo estático —
  verificação = `npm run build` + lint + suíte backend via Quinoa).
- Implementação por subagentes Opus; plano e validação pelo controller (Fable).
- Deploy único ao final via `docs/DEPLOY.md` (leva junto o 2.0.0).
