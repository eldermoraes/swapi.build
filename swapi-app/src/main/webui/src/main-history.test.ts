import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import aboutMain from '../../../test/resources/ssr/about.html?raw';
import homeMain from '../../../test/resources/ssr/home.html?raw';
import recordMain from '../../../test/resources/ssr/record-films-1.html?raw';
import type { BrowserTool, ModelContext } from './webmcp';

// Issue #50: the address and the page shown always agree. A page the explorer
// opens on its own (Random, a WebMCP tool) is a page like any other for Back
// and Forward, and a link navigation that does not show its page (it failed,
// or the reader acted on the page still shown) puts the address back.
// One main.ts for the whole file; the cases run in order.

function settle(): Promise<void> {
  return new Promise((resolve) =>
    setTimeout(() => requestAnimationFrame(() => requestAnimationFrame(() => resolve())), 0),
  );
}

const LIST_TITLE = 'Star Wars Films API: JSON data | swapi.build';
const film = { title: 'A New Hope', url: 'http://localhost:3000/api/films/1' };

function html(main: string, status = 200): Response {
  return new Response(
    `<!doctype html><html lang="en"><head><title>x</title></head><body>${main}</body></html>`,
    { status, headers: { 'Content-Type': 'text/html;charset=UTF-8' } },
  );
}

function serve(input: RequestInfo | URL): Promise<Response> {
  const url = String(input);
  if (url === '/') return Promise.resolve(html(homeMain));
  if (url === '/about') return Promise.resolve(html(aboutMain));
  if (url === '/resource/films/1') return Promise.resolve(html(recordMain));
  if (url.includes('/films/random')) {
    return Promise.resolve(new Response(JSON.stringify(film), { status: 200 }));
  }
  if (url.includes('/films')) {
    return Promise.resolve(new Response(JSON.stringify([film]), { status: 200 }));
  }
  // The live line of the home and its terminal ask /api; the cases do not need them.
  return Promise.resolve(new Response('{}', { status: 503 }));
}

/** Answers `path` only when released; every other request as `serve`. */
function hold(path: string, answer: () => Response): () => void {
  let release: () => void = () => {};
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
    if (String(input) !== path) return serve(input);
    await gate;
    return answer();
  });
  return release;
}

/** Answers `path` with a 503; every other request as `serve`. */
function fail(path: string): void {
  vi.spyOn(globalThis, 'fetch').mockImplementation((input) =>
    String(input) === path ? Promise.resolve(html('down', 503)) : serve(input),
  );
}

function link(href: string): HTMLAnchorElement {
  const a = document.createElement('a');
  a.href = href;
  a.textContent = href;
  document.querySelector('nav')!.appendChild(a);
  return a;
}

async function follow(href: string): Promise<void> {
  link(href).click();
  await settle();
}

async function travel(direction: 'back' | 'forward'): Promise<void> {
  const popped = new Promise((resolve) =>
    window.addEventListener('popstate', resolve, { once: true }),
  );
  history[direction]();
  await popped;
  await settle();
}

function address(): string {
  return window.location.pathname + window.location.search + window.location.hash;
}

function heading(): string | undefined {
  return document.querySelector('#main-content h1')?.textContent ?? undefined;
}

function alertCount(): number {
  return document.querySelectorAll('#main-content [role="alert"]').length;
}

describe('the address and the page shown agree', () => {
  const tools = new Map<string, BrowserTool>();

  beforeAll(async () => {
    // jsdom has no scrollIntoView; a fragment link would throw on it.
    Object.defineProperty(Element.prototype, 'scrollIntoView', {
      configurable: true,
      value: () => {},
    });
    const modelContext: ModelContext = {
      registerTool: (tool) => {
        tools.set(tool.name, tool);
        return Promise.resolve();
      },
    };
    Object.defineProperty(document, 'modelContext', { configurable: true, value: modelContext });
    document.head.innerHTML = `
      <title>${LIST_TITLE}</title>
      <meta name="robots" content="index,follow" />
      <link rel="canonical" href="http://localhost:3000/resource/films" />
    `;
    document.body.innerHTML = `
      <nav class="sw-menu"><a href="/resource/films" class="nav-link">Films</a></nav>
      <main id="main-content" tabindex="-1"></main>
    `;
    history.replaceState(null, '', '/resource/films');
    vi.spyOn(globalThis, 'fetch').mockImplementation(serve);
    await import('./main');
    await settle();
  });

  beforeEach(async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(serve);
    // An empty cache, so every page is asked; the document this tab loaded is the list.
    const serverMain = await import('./server-main');
    serverMain.resetServerMain({ documentPath: '/resource/films', assign: vi.fn() });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  afterAll(() => {
    Reflect.deleteProperty(document, 'modelContext');
    Reflect.deleteProperty(Element.prototype, 'scrollIntoView');
  });

  it('Back after Random shows the list again, with its address and head; Forward the record', async () => {
    expect(heading()).toBe('Films');
    document.querySelector<HTMLButtonElement>('#random-btn')!.click();
    await settle();
    expect(address()).toBe('/resource/films/1');
    expect(heading()).toBe('A New Hope');

    await travel('back');
    expect(address()).toBe('/resource/films');
    expect(heading()).toBe('Films');
    expect(document.querySelector('#main-content .item-card')).not.toBeNull();
    expect(document.title).toBe(LIST_TITLE);
    expect(document.querySelector('link[rel="canonical"]')?.getAttribute('href')).toBe(
      `${window.location.origin}/resource/films`,
    );

    await travel('forward');
    expect(address()).toBe('/resource/films/1');
    expect(heading()).toBe('A New Hope');
    expect(document.title).toBe('A New Hope — Star Wars film JSON | swapi.build');
  });

  it('Back after a WebMCP tool opened a record returns to the page before', async () => {
    await follow('/about');
    expect(heading()).toContain('About');
    const aboutTitle = document.title;

    const answer = JSON.parse(await tools.get('sw_get')!.execute({ resource: 'FILMS', id: 1 }));
    await settle();
    expect(answer.ok).toBe(true);
    expect(address()).toBe('/resource/films/1');
    expect(heading()).toBe('A New Hope');

    await travel('back');
    expect(address()).toBe('/about');
    expect(heading()).toContain('About');
    expect(document.title).toBe(aboutTitle);

    // A list too: the tool pushes its address, and Back undoes it.
    const list = JSON.parse(await tools.get('sw_list')!.execute({ resource: 'FILMS' }));
    await settle();
    expect(list.ok).toBe(true);
    expect(address()).toBe('/resource/films');
    expect(heading()).toBe('Films');

    await travel('back');
    expect(address()).toBe('/about');
    expect(heading()).toContain('About');
  });

  it('a link interrupted by an action on the page: the address goes back to the page shown', async () => {
    await follow('/');
    expect(heading()).toBe('The Star Wars API');

    const release = hold('/about', () => html(aboutMain));
    link('/about').click();
    await settle();
    expect(address()).toBe('/about');
    // A human action on the page still shown: EXEC of the home terminal.
    document.querySelector<HTMLButtonElement>('#main-content .sw-term-exec')!.click();
    await settle();
    expect(address()).toBe('/');

    release();
    await settle();
    expect(address()).toBe('/');
    expect(heading()).toBe('The Star Wars API');
  });

  it('a record link interrupted by typing in the search box: the list keeps its address and the search pushes nothing', async () => {
    await follow('/resource/films');
    expect(heading()).toBe('Films');

    const release = hold('/resource/films/1', () => html(recordMain));
    document.querySelector<HTMLAnchorElement>('#main-content a.item-card')!.click();
    await settle();
    expect(address()).toBe('/resource/films/1');
    const input = document.querySelector<HTMLInputElement>('#search-input')!;
    input.value = 'hope';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    expect(address()).toBe('/resource/films');

    const pushState = vi.spyOn(history, 'pushState');
    document.querySelector<HTMLButtonElement>('#search-btn')!.click();
    await settle();
    release();
    await settle();
    expect(pushState).not.toHaveBeenCalled();
    expect(address()).toBe('/resource/films');
    expect(heading()).toBe('Films');
  });

  it('two quick clicks, the second fails: the address goes back to the page shown, not to the first link', async () => {
    await follow('/');
    expect(heading()).toBe('The Star Wars API');

    const release = hold('/about', () => html(aboutMain));
    link('/about').click();
    await settle();
    fail('/resource/films/1');
    link('/resource/films/1').click();
    await settle();
    release();
    await settle();

    expect(address()).toBe('/');
    expect(heading()).toBe('The Star Wars API');
    expect(alertCount()).toBe(1);
  });

  it('a record link that failed is not taken for the page shown: its fragment link loads it', async () => {
    await follow('/resource/films');
    expect(heading()).toBe('Films');

    fail('/resource/films/1');
    document.querySelector<HTMLAnchorElement>('#main-content a.item-card')!.click();
    await settle();
    expect(address()).toBe('/resource/films');
    expect(alertCount()).toBe(1);

    vi.spyOn(globalThis, 'fetch').mockImplementation(serve);
    await follow('/resource/films/1#detail-content');
    expect(address()).toBe('/resource/films/1#detail-content');
    expect(heading()).toBe('A New Hope');
  });

  it('a link that fails while a Back still waits: the address Back took is not taken for the page shown', async () => {
    await follow('/');
    await follow('/about');
    expect(heading()).toContain('About');

    // An empty cache, so Back has to ask; the home does not answer yet.
    const serverMain = await import('./server-main');
    serverMain.resetServerMain({ documentPath: '/resource/films', assign: vi.fn() });
    const release = hold('/', () => html(homeMain));
    const popped = new Promise((resolve) =>
      window.addEventListener('popstate', resolve, { once: true }),
    );
    history.back();
    await popped;
    await settle();
    expect(address()).toBe('/');
    expect(heading()).toContain('About');

    fail('/resource/films/1');
    link('/resource/films/1').click();
    await settle();
    release();
    await settle();
    expect(heading()).toContain('About');
    expect(alertCount()).toBe(1);

    // The home was never shown: a link to one of its fragments loads it.
    vi.spyOn(globalThis, 'fetch').mockImplementation(serve);
    await follow('/#top');
    expect(address()).toBe('/#top');
    expect(heading()).toBe('The Star Wars API');
  });
});
