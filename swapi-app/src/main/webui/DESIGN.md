# Holonet Terminal — design system

The visual language of swapi.build: a full-width, black, terminal-flavoured shell
with a gold brand accent and a cyan data accent. Star Wars _inspired_ — no
trademarked assets, no official logos, no film stills.

**Source of truth is code, not this file.** Tokens live in
[`src/styles/tokens.css`](src/styles/tokens.css); components live in
[`src/ui/components.ts`](src/ui/components.ts) +
[`src/styles/components.css`](src/styles/components.css). Components the server
draws (2.11.0+) live in the Qute tags of
[`../resources/templates/tags/`](../resources/templates/tags/), with the same
classes as `components.ts`. When this document and the code disagree, the code
wins and this document is the bug.

---

## 1. Tokens

Every colour, face, radius, width and duration is a CSS custom property on
`:root`. **Page styles never contain raw hex.** Read `tokens.css` for the full
list; the roles below are the contract.

This is enforced, not merely asked for: `src/ui/tokens.test.ts` fails the suite if
a raw hex value appears in `base.css`, `components.css` or `style.css`. The one
sanctioned exception is the starfield, whose twelve stars differ only by the alpha
of pure white — depth, not a colour role.

| Group          | Tokens                                                                                                                                                   |
| -------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Grounds        | `--sw-ink`, `--sw-nebula`, `--sw-hairline`, `--sw-hairline-soft`, `--sw-surface-raised`                                                                  |
| Text           | `--sw-text`, `--sw-text-sub`, `--sw-text-dim`                                                                                                            |
| Brand / action | `--sw-gold`, `--sw-gold-hover`, `--sw-gold-dim`                                                                                                          |
| Data           | `--sw-cyan`, `--sw-cyan-greeting`                                                                                                                        |
| Terminal       | `--sw-term-bg`, `--sw-term-border`, `--sw-term-border-soft`, `--sw-term-text`, `--sw-term-dim`, `--sw-term-string`, `--sw-term-number`, `--sw-term-glow` |
| Chart          | `--sw-chart-rest`, `--sw-chart-mcp`, `--sw-chart-pages`, `--sw-chart-seo`, `--sw-chart-other`, `--sw-chart-track` (one series per route group on `/stats`; never gold) |
| Status         | `--sw-ok`, `--sw-error`, `--sw-error-bg`, `--sw-error-border`                                                                                            |
| Type           | `--sw-font-display`, `--sw-font-body`, `--sw-font-mono`                                                                                                  |
| Shape          | `--sw-radius-pill`, `--sw-radius-panel`, `--sw-radius-chip`                                                                                              |
| Widths         | `--sw-width-prose` (800px), `--sw-width-terminal` (860px), `--sw-width-wide` (1200px)                                                                    |
| Motion         | `--sw-transition` (0.18s)                                                                                                                                |

## 2. Typography scale

| Role            | Face               | Size                       | Case / spacing                  | Use                                    |
| --------------- | ------------------ | -------------------------- | ------------------------------- | -------------------------------------- |
| `display-xl`    | Pathway Gothic One | `clamp(2.6rem, 7vw, 5rem)` | UPPER, `letter-spacing: .1em`   | Home hero `h1` only                    |
| `wordmark`      | Pathway Gothic One | `2rem`                     | UPPER, `.28em`, 3px white frame | Header logo                            |
| `page-title`    | Pathway Gothic One | `1.75rem`                  | UPPER, `.08em`                  | `h1` of inner pages (`.sw-page-title`) |
| `section-label` | Pathway Gothic One | `1rem`                     | UPPER, `.16em`                  | Section headings (`.sw-section-label`) |
| `nav-link`      | Pathway Gothic One | `.85rem`                   | UPPER, `.14em`                  | Header menu                            |
| body            | Inter              | `1rem` / `.9rem`           | normal                          | Prose                                  |
| `micro-label`   | JetBrains Mono     | `.68–.72rem`               | UPPER, `.14–.2em`               | Terminal head, statuses, row metadata  |
| code            | JetBrains Mono     | `.8–.85rem`                | normal                          | Code, JSON, endpoints                  |

Pathway Gothic One ships **a single 400 weight**. Emphasis comes from size and
letter-spacing — never `font-weight: bold`, because the browser's faux-bold
mangles a condensed face.

Each font token lists a metric-matched fallback face right after the web font
(`'Pathway Gothic One Fallback'`, `'Inter Fallback'`, `'JetBrains Mono Fallback'`,
defined in `tokens.css`): a local font scaled to the web font's widths and
metrics, so the page does not move when the Google Fonts swap in
(`font-fallbacks.test.ts`). A new web font gets its own fallback face.

## 3. Colour roles

These are rules, not suggestions. The two accents mean different things and
swapping them destroys the signal.

- **Gold** `--sw-gold` — brand and _action_: solid pill background, ghost pill
  border/text, link hover, `:focus-visible` outline, active nav underline, row
  hover accent bar, spinner. Text on gold is always black. **Never colour JSON
  or data with gold.**
- **Cyan** `--sw-cyan` — _data_: terminal prompt, JSON keys, the greeting line
  (`--sw-cyan-greeting`), inline links inside prose. **Never put cyan on a
  button.**
- **Status** — `--sw-ok` for success dots and HTTP 200, `--sw-error` for
  failures. Status colours are semantic, never decorative.

### Contrast

All pairs in use clear WCAG AA (4.5:1 for small text), measured against the
real token values:

| Pair                        | Ratio   |
| --------------------------- | ------- |
| `--sw-text-sub` on ink      | 11.05:1 |
| `--sw-text-dim` on ink      | 6.08:1  |
| `--sw-term-dim` on term-bg  | 5.62:1  |
| `--sw-cyan` on term-bg      | 12.94:1 |
| `--sw-term-text` on term-bg | 16.19:1 |
| gold on ink / ink on gold   | 16.81:1 |
| `--sw-error` on ink         | 6.26:1  |

`--sw-term-dim` at 5.62:1 is the tightest pair in the system — darken it and it
falls below AA.

## 4. Layout rules

- **Full-width shell**: `#main-content` has **no max-width**. Bands span
  edge-to-edge, separated by 1px `--sw-hairline` dividers.
- **Inner content constrains itself per band**: prose → `--sw-width-prose`,
  terminal and index rows → `--sw-width-terminal`, wide grids →
  `--sw-width-wide`. Only the hero and its backdrop actually run edge-to-edge;
  everything that carries text is constrained, so a single full-bleed list reads
  as a mistake next to it. Wrap such a band in `.sw-band`, which centres it and
  adds no padding of its own — children keep their `2rem`, which is what makes
  row text line up with terminal text.
- Horizontal padding: `2rem` desktop, `1rem` under 640px.
- Spacing comes from flex/grid `gap`, not per-element margins.
- **Wide content (tables, code, JSON) always sits in its own `overflow-x: auto`
  container** — use `.sw-table-wrap` for tables. The body itself must never
  scroll sideways at any viewport from 360px up.

## 5. Motion rules

- Transitions animate colour/border/background only, over `--sw-transition`. No
  transform animations on load.
- The starfield is **static CSS** — no JS, no animation.
- `prefers-reduced-motion: reduce` zeroes every animation and transition
  globally. The rule lives in `base.css`; keep it.

## 6. Component inventory

Two places draw components. Pages drawn by the client import from
`src/ui/components.ts`; each helper returns an HTML **string** (pages render by
assigning `innerHTML`), and every interpolated value is passed through
`escapeHtml`. The four pages drawn by the server (home, `/docs`, `/docs/mcp`,
`/about`, 2.11.0+) use the Qute tags in `src/main/resources/templates/tags/`,
which Qute escapes (templates never use `raw` or `safe`); the client only binds
behaviour to what the server drew.

```ts
import { pill, initTerminal } from '../ui/components';
```

### Pill button — `pill()` / `pill.html`

The only button shape on the site.

```ts
pill('Browse the API', '/resource/people'); // solid (default)
pill('Read the docs', '/docs', 'ghost'); // ghost
pill('Try it', '/docs', 'ghost', true); // small
```

On the server:

```html
{#pill label="MCP for agents" href="/docs/mcp" kind="ghost" /}
```

`components.test.ts` keeps the two equal: it compares `pill()` with the output
of the tag (fixture `src/test/resources/ssr/pill.html`, written by
`SsrFixturesTest`).

### Section label — `sectionLabel.html`

```html
{#sectionLabel text="The resources" /}  <!-- <h2 class="sw-section-label"> -->
```

### Terminal — markup in `ssr/home.html` + `initTerminal()`

The live try-it. The server draws the markup (`[data-term="home"]`, with the
suggestion chips) inside `templates/ssr/home.html`; `initTerminal()` binds EXEC,
Enter and the chips on the same container, reading the chips from the DOM:

```ts
initTerminal(container, 'home');
```

`idPrefix` must be unique per terminal on the page. Output is `aria-live="polite"`,
suggestion chips are real `<button>`s, and JSON is coloured with the terminal
palette.

### Index rows — `indexRows.html`

The resource list. **Never poster cards on home.** Wrap it — together with its
section label — in a `.sw-band` so it stays aligned with the terminal above
instead of running edge-to-edge:

```html
<div class="sw-band">
  {#sectionLabel text="The resources" /}
  {#indexRows rows=view.rows /}
</div>
```

Each row is an `IndexRow` (`title`, `endpoint`, `href`), for example
`People`, `/api/people`, `/resource/people`.

### CSS-only components

| Component       | Classes                                     | Notes                                                                              |
| --------------- | ------------------------------------------- | ---------------------------------------------------------------------------------- |
| Framed wordmark | `.sw-wordmark`                              | White 3px frame, gold on hover (`index.html`)                                      |
| Header          | `.sw-header`, `.sw-menu`, `.nav-link`       | Keep `.nav-link` — `main.ts` targets it for active state                           |
| Hero            | `.sw-hero`, `.sw-greeting`, `.sw-starfield` | Markup is in `templates/ssr/home.html`; hero children sit above the starfield via `z-index` |
| Panel           | `.sw-panel`                                 | 8px radius, hairline border — endpoint blocks, schema details                      |
| Code block      | `.sw-code`, `.sw-copy`                      | Dark panel + copy button                                                           |
| Tabs            | `.sw-tabs`, `.sw-tab`                       | Gold underline on active                                                           |
| Table           | `.sw-table`, `.sw-table-wrap`               | Hairline rows, mono for identifiers; always wrapped                                |
| Footer          | `.sw-footer`                                | Hairline top, dim text                                                             |

### Record detail — `templates/ssr/record.html` (2.12.0)

Every `/resource/{type}/{id}` page is drawn by the server with one template;
the client adopts it (`bindRecord`) and only colours the JSON (and binds any
`.copy-btn` the markup carries).

| Part          | Classes                                         | Notes                                                                                     |
| ------------- | ----------------------------------------------- | ----------------------------------------------------------------------------------------- |
| Section       | `.record-section`, `.record-subtitle`           | Flex column, `min-width: 0` so no child widens the page; `.detail-view > *` does the same |
| Attributes    | `.sw-table-wrap` > `.sw-table.record-table`     | Always wrapped; cells break long values (`overflow-wrap: anywhere`)                       |
| Opening crawl | `.record-crawl`                                 | `white-space: pre-line`: keeps the crawl's line breaks, wraps at phone width              |
| Relations     | `.record-relation`, `.record-links`             | Wrapping list of links to the related records; never a horizontal scroll                  |
| API sample    | `.sw-code.result-panel`, `pre.result-body`      | Body is `JSON.stringify(x, null, 2)` text; the client colours it after load               |

## 7. New-page checklist

A new page or section is on-standard when:

1. It uses **only tokens** — no raw hex anywhere in page styles.
2. The display face is **uppercase + letter-spaced**, never bold.
3. Gold/cyan roles are respected (gold = action, cyan = data).
4. Actions are `.sw-pill` — via `pill()` on the client or the `pill.html` tag
   on the server.
5. Data views use the terminal palette.
6. It is **keyboard-operable with a visible gold focus ring**; interactive
   things are real `<a>`/`<button>`, never a `div` with `role="button"`.
7. It is **reduced-motion safe**.
8. It uses the **full-width shell** with per-band inner widths.
9. It ships **no trademarked assets**.
10. A page drawn on the server carries the `data-ssr-page` marker on its
    `<main>`; its client code only binds behaviour and never redraws the
    `<main>`; and what it hides with JavaScript stays visible without it (tab
    panels hide only under `.js`).

## 8. Voice & copy

UI copy is **English**, short, and functional. In-universe flavour is welcome in
microcopy — `● LIVE`, `GALACTIC TERMINAL`, "querying the galaxy" — but it must
never obscure what a control does. A button says what it does; the flavour lives
around it, not in it.

## Testing

Component markup is covered by Vitest (jsdom):

```bash
cd swapi-app/src/main/webui && npm test
```

`vitest.config.ts` sets `css: true` on purpose — without it Vitest stubs CSS
imports and `tokens.css?raw` resolves to an empty string, so the token tests
would silently assert nothing.
