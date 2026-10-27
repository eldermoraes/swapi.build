import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import aboutMain from '../../../test/resources/ssr/about.html?raw';
import homeMain from '../../../test/resources/ssr/home.html?raw';

const seo = vi.hoisted(() => ({
  apply: vi.fn(),
  actual: undefined as unknown as typeof import('./seo').applySeoMetadata,
}));

vi.mock('./seo', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./seo')>();
  seo.actual = actual.applySeoMetadata;
  seo.apply.mockImplementation(actual.applySeoMetadata);
  return { ...actual, applySeoMetadata: seo.apply };
});

type Assign = ReturnType<typeof vi.fn<(url: string) => void>>;

function settle(): Promise<void> {
  return new Promise((resolve) =>
    setTimeout(() => requestAnimationFrame(() => requestAnimationFrame(() => resolve())), 0),
  );
}

const film = {
  title: 'A New Hope',
  episode_id: 4,
  director: 'George Lucas',
  release_date: '1977-05-25',
  url: 'http://localhost:3000/api/films/1',
};

function respond(input: RequestInfo | URL): Promise<Response> {
  const url = String(input);
  const body = url.endsWith('/api/films/1') ? film : [film];
  return Promise.resolve(new Response(JSON.stringify(body), { status: 200 }));
}

// 2.10.0: from the list (first load, no write), a click on a record writes the
// head once, after the record arrives, already with its name: never "Films #1".
describe('client navigation from the list to a record', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('writes the head once, with the record, and the title never passes through Films #1', async () => {
    document.head.innerHTML = `
      <title>Star Wars Films API: JSON data | swapi.build</title>
      <meta name="robots" content="index,follow" />
      <link rel="canonical" href="http://localhost:3000/resource/films" />
    `;
    document.body.innerHTML = `
      <nav class="sw-menu">
        <a href="/resource/films" class="nav-link">Films</a>
        <a href="/resource/films/1" id="to-film">A New Hope</a>
      </nav>
      <main id="main-content" tabindex="-1"></main>
    `;
    history.replaceState(null, '', '/resource/films');
    vi.spyOn(globalThis, 'fetch').mockImplementation(respond);

    await import('./main');
    await settle();
    expect(seo.apply).not.toHaveBeenCalled();

    const titles: string[] = [];
    const observer = new MutationObserver(() => titles.push(document.title));
    observer.observe(document.head, { subtree: true, childList: true, characterData: true });

    document.getElementById('to-film')!.click();
    await settle();
    observer.disconnect();

    expect(window.location.pathname).toBe('/resource/films/1');
    expect(seo.apply).toHaveBeenCalledTimes(1);
    expect(seo.apply).toHaveBeenCalledWith('/resource/films/1', window.location.origin, {
      entity: film,
    });
    expect(document.title).toBe('A New Hope — Star Wars film JSON | swapi.build');
    expect(titles).not.toContain('Films #1 | swapi.build');
    expect(new Set(titles)).toEqual(new Set(['A New Hope — Star Wars film JSON | swapi.build']));
    expect(document.querySelector('link[rel="canonical"]')?.getAttribute('href')).toBe(
      `${window.location.origin}/resource/films/1`,
    );
    expect(document.querySelector('meta[name="robots"]')?.getAttribute('content')).toBe(
      'index,follow',
    );
  });
});

/** A whole document as the server sends it, with this <main>. */
function documentWith(main: string): string {
  return `<!doctype html><html lang="en"><head><title>x</title></head><body>${main}</body></html>`;
}

function html(body: string, status = 200): Response {
  return new Response(body, { status, headers: { 'Content-Type': 'text/html;charset=UTF-8' } });
}

function serveDocuments(input: RequestInfo | URL): Promise<Response> {
  const url = String(input);
  if (url === '/') return Promise.resolve(html(documentWith(homeMain)));
  if (url === '/about') return Promise.resolve(html(documentWith(aboutMain)));
  // The live line of the home asks /api/stats; the test does not need it.
  return Promise.resolve(new Response('{}', { status: 503 }));
}

function link(href: string): HTMLAnchorElement {
  const a = document.createElement('a');
  a.href = href;
  a.textContent = href;
  document.querySelector('nav')!.appendChild(a);
  return a;
}

function mainHeading(): string | undefined {
  return document.querySelector('#main-content h1')?.textContent ?? undefined;
}

// 2.11.0, slice E1: a later navigation to a server-rendered page fetches its
// document and takes its <main>; the head is written once, after the swap.
// The cases run in order on the same main.ts (one per file, like the case above).
describe('client navigation between server-rendered pages', () => {
  let assign: Assign;

  beforeEach(async () => {
    seo.apply.mockClear();
    assign = vi.fn<(url: string) => void>();
    const serverMain = await import('./server-main');
    // The document this tab loaded is /resource/films (the case above).
    serverMain.resetServerMain({ documentPath: '/resource/films', assign });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('home → /about: one fetch, the <main> swapped once, the head written once after it', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(serveDocuments);
    link('/').click();
    await settle();
    expect(mainHeading()).toBe('The Star Wars API');
    seo.apply.mockClear();

    const container = document.getElementById('main-content')!;
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockImplementation(serveDocuments);
    fetchSpy.mockClear();
    const swaps: number[] = [];
    const observer = new MutationObserver((records) =>
      swaps.push(records.filter((r) => r.removedNodes.length > 0).length),
    );
    observer.observe(container, { childList: true });
    const headingWhenWritten: (string | undefined)[] = [];
    seo.apply.mockImplementationOnce((...args: unknown[]) => {
      headingWhenWritten.push(mainHeading());
      return seo.actual(...(args as Parameters<typeof seo.actual>));
    });

    link('/about').click();
    await settle();
    observer.disconnect();

    expect(fetchSpy.mock.calls.filter(([url]) => String(url) === '/about')).toHaveLength(1);
    expect(swaps.reduce((a, b) => a + b, 0)).toBe(1);
    expect(mainHeading()).toContain('About');
    expect(seo.apply).toHaveBeenCalledTimes(1);
    expect(seo.apply.mock.calls[0][0]).toBe('/about');
    expect(headingWhenWritten).toEqual([mainHeading()]);
    expect(container.hasAttribute('aria-busy')).toBe(false);
  });

  it('back to the home: no new fetch (memory cache)', async () => {
    // The cache was reset by beforeEach; fill it again with one visit each way.
    vi.spyOn(globalThis, 'fetch').mockImplementation(serveDocuments);
    link('/').click();
    await settle();
    link('/about').click();
    await settle();

    // The same spy as above (spyOn returns it): forget its calls so far.
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockImplementation(serveDocuments);
    fetchSpy.mockClear();
    const popped = new Promise((resolve) =>
      window.addEventListener('popstate', resolve, { once: true }),
    );
    history.back();
    await popped;
    await settle();
    expect(window.location.pathname).toBe('/');
    expect(mainHeading()).toBe('The Star Wars API');
    expect(fetchSpy.mock.calls.filter(([url]) => String(url) === '/')).toHaveLength(0);
  });

  it.each([
    ['a 503', () => Promise.resolve(html('down', 503))],
    ['a rejected fetch', () => Promise.reject(new TypeError('Failed to fetch'))],
  ])(
    '%s: the home stays, an alert links /about, the head and the URL stay the home',
    async (_name, answer) => {
      expect(window.location.pathname).toBe('/');
      const titleBefore = document.title;
      vi.spyOn(globalThis, 'fetch').mockImplementation((input) =>
        String(input) === '/about' ? answer() : serveDocuments(input),
      );
      link('/about').click();
      await settle();

      const container = document.getElementById('main-content')!;
      expect(mainHeading()).toBe('The Star Wars API');
      expect(container.hasAttribute('aria-busy')).toBe(false);
      const alerts = container.querySelectorAll('[role="alert"]');
      expect(alerts).toHaveLength(1);
      expect(alerts[0].querySelector('a')!.getAttribute('href')).toBe('/about');
      expect(seo.apply).not.toHaveBeenCalled();
      expect(document.title).toBe(titleBefore);
      expect(window.location.pathname).toBe('/');
      expect(assign).not.toHaveBeenCalled();
    },
  );

  it('an answer without the marker: one full load of /about', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) =>
      String(input) === '/about'
        ? Promise.resolve(html(documentWith('<main id="main-content" tabindex="-1"></main>')))
        : serveDocuments(input),
    );
    link('/about').click();
    await settle();
    expect(assign).toHaveBeenCalledTimes(1);
    expect(assign).toHaveBeenCalledWith('/about');
  });

  it('the full load keeps the query of the link', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) =>
      String(input) === '/about'
        ? Promise.resolve(html(documentWith('<main id="main-content" tabindex="-1"></main>')))
        : serveDocuments(input),
    );
    link('/about?ref=docs').click();
    await settle();
    expect(assign).toHaveBeenCalledTimes(1);
    expect(assign).toHaveBeenCalledWith('/about?ref=docs');
  });

  it('an explorer action while a page is on its way: the late answer swaps nothing', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(serveDocuments);
    link('/').click();
    await settle();
    expect(mainHeading()).toBe('The Star Wars API');

    let release: () => void = () => {};
    let pageSignal: AbortSignal | null | undefined;
    vi.spyOn(globalThis, 'fetch').mockImplementation((input, init) => {
      if (String(input) !== '/about') return serveDocuments(input);
      pageSignal = init?.signal;
      return new Promise((resolve) => {
        release = () => resolve(html(documentWith(aboutMain)));
      });
    });
    link('/about').click();
    await settle();
    // A human action on the page still shown: EXEC of the home terminal.
    document.querySelector<HTMLButtonElement>('#main-content .sw-term-exec')!.click();
    release();
    await settle();

    expect(pageSignal?.aborted).toBe(true);
    expect(mainHeading()).toBe('The Star Wars API');
    expect(seo.apply).not.toHaveBeenCalledWith('/about', expect.anything(), expect.anything());
  });

  it('a page that does not answer in time: the alert, the content stays', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(serveDocuments);
    link('/').click();
    await settle();
    const serverMain = await import('./server-main');
    serverMain.resetServerMain({ documentPath: '/resource/films', assign, timeoutMs: 20 });
    vi.spyOn(globalThis, 'fetch').mockImplementation((input, init) =>
      String(input) === '/about'
        ? new Promise((_resolve, reject) => {
            init?.signal?.addEventListener('abort', () => reject(init.signal!.reason), {
              once: true,
            });
          })
        : serveDocuments(input),
    );
    link('/about').click();
    await new Promise((resolve) => setTimeout(resolve, 60));
    await settle();

    const container = document.getElementById('main-content')!;
    expect(mainHeading()).toBe('The Star Wars API');
    expect(container.querySelectorAll('[role="alert"]')).toHaveLength(1);
    expect(container.hasAttribute('aria-busy')).toBe(false);
    expect(window.location.pathname).toBe('/');
  });

  it('a link that fails: the address goes back to the full URL, query included', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(serveDocuments);
    link('/?from=test').click();
    await settle();
    expect(window.location.pathname + window.location.search).toBe('/?from=test');
    expect(mainHeading()).toBe('The Star Wars API');

    vi.spyOn(globalThis, 'fetch').mockImplementation((input) =>
      String(input) === '/about' ? Promise.resolve(html('down', 503)) : serveDocuments(input),
    );
    link('/about').click();
    await settle();
    expect(document.querySelectorAll('#main-content [role="alert"]')).toHaveLength(1);
    expect(window.location.pathname + window.location.search).toBe('/?from=test');
  });

  it('Back to a page that fails: the alert, and history stays where Back took it', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(serveDocuments);
    link('/').click();
    await settle();
    link('/about').click();
    await settle();
    expect(mainHeading()).toContain('About');

    // An empty cache, so Back has to ask; the home now fails.
    const serverMain = await import('./server-main');
    serverMain.resetServerMain({ documentPath: '/resource/films', assign });
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) =>
      String(input) === '/' ? Promise.resolve(html('down', 503)) : serveDocuments(input),
    );
    const replaceState = vi.spyOn(history, 'replaceState');
    const pushState = vi.spyOn(history, 'pushState');
    const popped = new Promise((resolve) =>
      window.addEventListener('popstate', resolve, { once: true }),
    );
    history.back();
    await popped;
    await settle();

    expect(window.location.pathname).toBe('/');
    expect(replaceState).not.toHaveBeenCalled();
    expect(pushState).not.toHaveBeenCalled();
    expect(mainHeading()).toContain('About');
    expect(document.querySelectorAll('#main-content [role="alert"]')).toHaveLength(1);
  });
});
