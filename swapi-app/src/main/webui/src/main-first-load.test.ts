import { afterEach, describe, expect, it, vi } from 'vitest';
import mcpMain from '../../../test/resources/ssr/mcp.html?raw';
import recordMain from '../../../test/resources/ssr/record-films-1.html?raw';

const seo = vi.hoisted(() => ({ apply: vi.fn() }));

vi.mock('./seo', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./seo')>();
  seo.apply.mockImplementation(actual.applySeoMetadata);
  return { ...actual, applySeoMetadata: seo.apply };
});

type Assign = ReturnType<typeof vi.fn<(url: string) => void>>;

function settle(): Promise<void> {
  return new Promise((resolve) =>
    setTimeout(() => requestAnimationFrame(() => requestAnimationFrame(() => resolve())), 0),
  );
}

// The head the server sends for /resource/films/1 (SeoGoldenTest checks it).
const SERVER_HEAD = `
  <title>A New Hope — Star Wars film JSON | swapi.build</title>
  <meta name="description" content="A New Hope, Star Wars Episode 4, directed by George Lucas and released 1977-05-25. Its JSON record from the free swapi.build REST API and MCP server." />
  <meta name="robots" content="index,follow" />
  <link rel="canonical" href="http://localhost:3000/resource/films/1" />
  <meta property="og:title" content="A New Hope — Star Wars film JSON | swapi.build" />
  <meta property="og:url" content="http://localhost:3000/resource/films/1" />
`;

/** The SEO part of the head (Vitest also injects the stylesheet there). */
function seoHead(): string[] {
  return Array.from(document.head.querySelectorAll('title, meta, link'), (el) => el.outerHTML);
}

// 2.10.0, the end of the overwrite: on the first load the head is the server's,
// and the client writes nothing to it. Changes on purpose in 2.12.0 (slice E2):
// the server draws the record, so the first load makes no request at all (it
// asked /api/films/1 until 2.11.0) and never swaps the <main>.
describe('first load of an indexable record address', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('keeps the server head and <main>, asks nothing, colours the JSON without changing it', async () => {
    document.head.innerHTML = SERVER_HEAD;
    const before = seoHead();
    expect(before).toHaveLength(6);
    document.body.innerHTML = `
      <nav class="sw-menu"><a href="/resource/films" class="nav-link">Films</a></nav>
      ${recordMain}
    `;
    history.replaceState(null, '', '/resource/films/1');
    const container = document.getElementById('main-content')!;
    const text = container.querySelector('pre.result-body')!.textContent!;
    const mutations: MutationRecord[] = [];
    const observer = new MutationObserver((records) => mutations.push(...records));
    observer.observe(container, { childList: true });
    const fetchSpy = vi.spyOn(globalThis, 'fetch');

    await import('./main');
    await settle();
    observer.disconnect();

    expect(fetchSpy).not.toHaveBeenCalled();
    expect(mutations).toHaveLength(0);
    expect(container.hasAttribute('data-ssr-page')).toBe(false);
    expect(container.querySelector('h1')?.textContent).toBe('A New Hope');
    const pre = container.querySelector('pre.result-body')!;
    expect(pre.querySelector('.json-key')).not.toBeNull();
    expect(pre.textContent).toBe(text);
    expect(pre.textContent).toBe(JSON.stringify(JSON.parse(text), null, 2));
    expect(seo.apply).not.toHaveBeenCalled();
    expect(seoHead()).toEqual(before);
    expect(document.querySelector('[aria-live="polite"].sr-only')?.textContent).toBe(
      'A New Hope — Star Wars film JSON',
    );
  });
});

// 2.12.0: a record address whose <main> the server failed to draw.
describe('first load of a record address without the marker', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('shows "Resource unavailable" with a Reload link; no request, no reload', async () => {
    vi.resetModules();
    document.head.innerHTML = SERVER_HEAD;
    document.body.innerHTML = `<nav class="sw-menu"></nav><main id="main-content" tabindex="-1"></main>`;
    history.replaceState(null, '', '/resource/films/1');
    const assign = vi.fn<(url: string) => void>();
    (await import('./server-main')).resetServerMain({ assign });
    const fetchSpy = vi.spyOn(globalThis, 'fetch');

    await import('./main');
    await settle();

    expect(fetchSpy).not.toHaveBeenCalled();
    expect(assign).not.toHaveBeenCalled();
    const container = document.getElementById('main-content')!;
    expect(container.querySelector('h1')?.textContent).toBe('Resource unavailable');
    expect(container.querySelector('a')?.getAttribute('href')).toBe('/resource/films/1');
  });
});

// 2.11.0, slice E1: the server drew the <main>; the first load adopts it as it
// is (no request, no redraw, no head write) and only binds the behaviours.
describe('first load of a server-rendered page', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    seo.apply.mockClear();
  });

  /** A fresh main.ts on a fresh document at `url`; returns the full-load spy. */
  async function load(url: string, main: string): Promise<Assign> {
    vi.resetModules();
    document.head.innerHTML = '<title>MCP Server — swapi.build</title>';
    document.body.innerHTML = `<nav class="sw-menu"><a href="/docs" class="nav-link">Docs</a></nav>${main}`;
    history.replaceState(null, '', url);
    const assign = vi.fn<(url: string) => void>();
    const serverMain = await import('./server-main');
    serverMain.resetServerMain({ assign });
    return assign;
  }

  it('/docs/mcp: no request, no change to the <main>, no head write; the tabs work', async () => {
    await load('/docs/mcp', mcpMain);
    const container = document.getElementById('main-content')!;
    const mutations: MutationRecord[] = [];
    const observer = new MutationObserver((records) => mutations.push(...records));
    observer.observe(container, { childList: true });
    const fetchSpy = vi.spyOn(globalThis, 'fetch');

    await import('./main');
    await settle();
    observer.disconnect();

    expect(mutations.filter((m) => m.type === 'childList')).toHaveLength(0);
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(seo.apply).not.toHaveBeenCalled();
    expect(container.hasAttribute('data-ssr-page')).toBe(false);

    container.querySelectorAll<HTMLButtonElement>('.tab-btn')[1].click();
    const panels = container.querySelectorAll('.tab-panel');
    expect(panels[1].hasAttribute('data-inactive')).toBe(false);
    expect(panels[0].hasAttribute('data-inactive')).toBe(true);
  });

  it('without the marker (the server failed to draw it): no request, no reload, a Reload link', async () => {
    const assign = await load('/docs/mcp', '<main id="main-content" tabindex="-1"></main>');
    const fetchSpy = vi.spyOn(globalThis, 'fetch');

    await import('./main');
    await settle();

    expect(fetchSpy).not.toHaveBeenCalled();
    expect(assign).not.toHaveBeenCalled();
    const container = document.getElementById('main-content')!;
    expect(container.querySelector('h1')?.textContent).toBe('This page could not be loaded');
    const link = container.querySelector('a')!;
    expect(link.textContent).toBe('Reload');
    expect(link.getAttribute('href')).toBe('/docs/mcp');
  });

  it('with #troubleshooting: neither scrolls to the top nor moves the focus', async () => {
    await load('/docs/mcp#troubleshooting', mcpMain);
    const container = document.getElementById('main-content')!;
    const scrollSpy = vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
    const focusSpy = vi.spyOn(container, 'focus');
    vi.spyOn(globalThis, 'fetch');

    await import('./main');
    await settle();

    expect(window.location.hash).toBe('#troubleshooting');
    expect(scrollSpy).not.toHaveBeenCalled();
    expect(focusSpy).not.toHaveBeenCalled();
  });
});
