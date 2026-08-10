# Holonet Terminal — design system

The visual language of swapi.build: a full-width, black, terminal-flavoured shell
with a gold brand accent and a cyan data accent. Star Wars _inspired_ — no
trademarked assets, no official logos, no film stills.

**Source of truth is code, not this file.** Tokens live in
[`src/styles/tokens.css`](src/styles/tokens.css); components live in
[`src/ui/components.ts`](src/ui/components.ts) +
[`src/styles/components.css`](src/styles/components.css). When this document and
the code disagree, the code wins and this document is the bug.

---

## 1. Tokens

Every colour, face, radius, width and duration is a CSS custom property on
`:root`. **Page styles never contain raw hex.** Read `tokens.css` for the full
list; the roles below are the contract.

| Group          | Tokens                                                                                                                                                   |
| -------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Grounds        | `--sw-ink`, `--sw-nebula`, `--sw-hairline`, `--sw-hairline-soft`                                                                                         |
| Text           | `--sw-text`, `--sw-text-sub`, `--sw-text-dim`                                                                                                            |
| Brand / action | `--sw-gold`, `--sw-gold-hover`, `--sw-gold-dim`                                                                                                          |
| Data           | `--sw-cyan`, `--sw-cyan-greeting`                                                                                                                        |
| Terminal       | `--sw-term-bg`, `--sw-term-border`, `--sw-term-border-soft`, `--sw-term-text`, `--sw-term-dim`, `--sw-term-string`, `--sw-term-number`, `--sw-term-glow` |
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
  terminal → `--sw-width-terminal`, wide grids → `--sw-width-wide`, index rows →
  full-bleed.
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

Import from `src/ui/components.ts`. Each helper returns an HTML **string**
(pages render by assigning `innerHTML`), and every interpolated value is passed
through `escapeHtml`.

```ts
import { pill, sectionLabel, terminalMarkup, initTerminal, indexRows } from '../ui/components';
```

### Pill button — `pill()`

The only button shape on the site.

```ts
pill('Browse the API', '/resource/people'); // solid (default)
pill('Read the docs', '/docs', 'ghost'); // ghost
pill('Try it', '/docs', 'ghost', true); // small
```

### Section label — `sectionLabel()`

```ts
sectionLabel('The resources'); // <h2 class="sw-section-label">
```

### Terminal — `terminalMarkup()` + `initTerminal()`

The live try-it. Render the markup, then wire it up on the same container.

```ts
container.innerHTML = terminalMarkup({
  idPrefix: 'home',
  initialPath: '/api/people/1',
  suggestions: ['/api/people/1', '/api/films/1', '/api/planets/1'],
});
initTerminal(container, { idPrefix: 'home' });
```

`idPrefix` must be unique per terminal on the page. Output is `aria-live="polite"`,
suggestion chips are real `<button>`s, and JSON is coloured with the terminal
palette.

### Index rows — `indexRows()`

The full-width resource list. **Never poster cards on home.**

```ts
indexRows([{ title: 'People', endpoint: '/api/people', href: '/resource/people' }]);
```

### CSS-only components

| Component       | Classes                                     | Notes                                                                              |
| --------------- | ------------------------------------------- | ---------------------------------------------------------------------------------- |
| Framed wordmark | `.sw-wordmark`                              | White 3px frame, gold on hover (`index.html`)                                      |
| Header          | `.sw-header`, `.sw-menu`, `.nav-link`       | Keep `.nav-link` — `main.ts` targets it for active state                           |
| Hero            | `.sw-hero`, `.sw-greeting`, `.sw-starfield` | Markup is inline in `home.ts`; hero children sit above the starfield via `z-index` |
| Panel           | `.sw-panel`                                 | 8px radius, hairline border — endpoint blocks, schema details                      |
| Code block      | `.sw-code`, `.sw-copy`                      | Dark panel + copy button                                                           |
| Tabs            | `.sw-tabs`, `.sw-tab`                       | Gold underline on active                                                           |
| Table           | `.sw-table`, `.sw-table-wrap`               | Hairline rows, mono for identifiers; always wrapped                                |
| Footer          | `.sw-footer`                                | Hairline top, dim text                                                             |

## 7. New-page checklist

A new page or section is on-standard when:

1. It uses **only tokens** — no raw hex anywhere in page styles.
2. The display face is **uppercase + letter-spaced**, never bold.
3. Gold/cyan roles are respected (gold = action, cyan = data).
4. Actions are `.sw-pill` — via `pill()`.
5. Data views use the terminal palette.
6. It is **keyboard-operable with a visible gold focus ring**; interactive
   things are real `<a>`/`<button>`, never a `div` with `role="button"`.
7. It is **reduced-motion safe**.
8. It uses the **full-width shell** with per-band inner widths.
9. It ships **no trademarked assets**.

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
