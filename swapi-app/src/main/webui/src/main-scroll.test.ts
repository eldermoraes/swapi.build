import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import aboutMain from '../../../test/resources/ssr/about.html?raw';
import homeMain from '../../../test/resources/ssr/home.html?raw';
import recordMain from '../../../test/resources/ssr/record-films-1.html?raw';

// Issue #71: Back and Forward put the reader where they were on that history
// entry; a link still opens its page at the top. jsdom has no layout, so the
// viewport is a number here: `scrollTo` writes it and `scrollY` reads it.
// One main.ts for the whole file; the cases run in order.

function settle(): Promise<void> {
  return new Promise((resolve) =>
    setTimeout(() => requestAnimationFrame(() => requestAnimationFrame(() => resolve())), 0),
  );
}

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
  return Promise.resolve(new Response('{}', { status: 503 }));
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

function heading(): string | undefined {
  return document.querySelector('#main-content h1')?.textContent ?? undefined;
}

describe('Back and Forward restore the scroll position', () => {
  /** Where the viewport is; the reader scrolls by writing it. */
  let viewport = 0;
  const jumps: (ScrollToOptions | undefined)[] = [];

  beforeAll(async () => {
    Object.defineProperty(Element.prototype, 'scrollIntoView', {
      configurable: true,
      value: () => {
        viewport = 640;
      },
    });
    Object.defineProperty(window, 'scrollY', { configurable: true, get: () => viewport });
    vi.spyOn(window, 'scrollTo').mockImplementation(((
      first?: number | ScrollToOptions,
      top?: number,
    ) => {
      jumps.push(typeof first === 'object' ? first : undefined);
      viewport = typeof first === 'object' ? (first.top ?? viewport) : (top ?? 0);
    }) as typeof window.scrollTo);
    vi.spyOn(globalThis, 'fetch').mockImplementation(serve);
    document.head.innerHTML = '<title>Star Wars Films API: JSON data | swapi.build</title>';
    document.body.innerHTML = `
      <nav class="sw-menu"><a href="/resource/films" class="nav-link">Films</a></nav>
      <main id="main-content" tabindex="-1"></main>
    `;
    history.replaceState(null, '', '/resource/films');
    const serverMain = await import('./server-main');
    serverMain.resetServerMain({ documentPath: '/resource/films', assign: vi.fn() });
    await import('./main');
    await settle();
  });

  beforeEach(async () => {
    jumps.length = 0;
    // An empty cache, so every page is asked; the document this tab loaded is the list.
    const serverMain = await import('./server-main');
    serverMain.resetServerMain({ documentPath: '/resource/films', assign: vi.fn() });
  });

  afterEach(() => {
    vi.mocked(globalThis.fetch).mockImplementation(serve);
  });

  afterAll(() => {
    vi.restoreAllMocks();
    Reflect.deleteProperty(Element.prototype, 'scrollIntoView');
    Reflect.deleteProperty(window, 'scrollY');
  });

  it('Back to the list puts the reader where they were; Forward too, on the record', async () => {
    expect(heading()).toBe('Films');
    // The page has just loaded: the browser is still the one that restores the
    // scroll (after a reload it does so while the page loads).
    expect(history.scrollRestoration).not.toBe('manual');
    viewport = 900;
    document.querySelector<HTMLAnchorElement>('#main-content a.item-card')!.click();
    await settle();
    expect(heading()).toBe('A New Hope');
    expect(viewport).toBe(0);
    // From the first navigation on, the client restores it.
    expect(history.scrollRestoration).toBe('manual');

    viewport = 120;
    await travel('back');
    expect(heading()).toBe('Films');
    expect(viewport).toBe(900);
    // At once: the smooth scroll of the stylesheet is for jumps inside a page.
    expect(jumps[jumps.length - 1]).toMatchObject({ top: 900, behavior: 'instant' });

    await travel('forward');
    expect(heading()).toBe('A New Hope');
    expect(viewport).toBe(120);
  });

  it('the browser gets the scroll back when the page is left, so it restores it after a reload', async () => {
    window.dispatchEvent(new Event('pagehide'));
    expect(history.scrollRestoration).toBe('auto');
    // The page was only put aside (back from the browser's cache): the next
    // navigation is the client's again.
    await follow('/about');
    expect(history.scrollRestoration).toBe('manual');
    await travel('back');
  });

  it('Back to an entry of the page shown with nothing kept and no fragment: the top', async () => {
    // As after a reload on /about#team: the entry before it is /about, the same
    // document, and this load kept no place for it. Here the address moves to
    // the fragment without the client seeing the reader leave (the address bar).
    await follow('/about');
    history.pushState(null, '', '/about#team');
    window.dispatchEvent(new HashChangeEvent('hashchange'));
    viewport = 480;

    await travel('back');
    expect(window.location.pathname + window.location.hash).toBe('/about');
    expect(viewport).toBe(0);
    await travel('back');
  });

  it('Back to a page the server drew puts the reader where they were', async () => {
    await follow('/about');
    expect(heading()).toContain('About');
    expect(viewport).toBe(0);
    viewport = 400;
    await follow('/');
    expect(heading()).toBe('The Star Wars API');
    expect(viewport).toBe(0);

    await travel('back');
    expect(heading()).toContain('About');
    expect(viewport).toBe(400);
  });

  it('a link opens its page at the top, also a page seen before', async () => {
    viewport = 400;
    await follow('/');
    expect(heading()).toBe('The Star Wars API');
    expect(viewport).toBe(0);
    viewport = 250;
    await follow('/about');
    expect(heading()).toContain('About');
    expect(viewport).toBe(0);
  });

  it('Back after Random puts the reader where they were on the list', async () => {
    await follow('/resource/films');
    viewport = 700;
    document.querySelector<HTMLButtonElement>('#random-btn')!.click();
    await settle();
    expect(heading()).toBe('A New Hope');
    expect(viewport).toBe(0);

    await travel('back');
    expect(heading()).toBe('Films');
    expect(viewport).toBe(700);
  });

  it('Back from a fragment of the page shown puts the reader where they were before the jump', async () => {
    await follow('/about');
    const target = document.createElement('p');
    target.id = 'far-down';
    document.getElementById('main-content')!.appendChild(target);
    viewport = 300;
    await follow('/about#far-down');
    expect(viewport).toBe(640);

    viewport = 660;
    await travel('back');
    expect(window.location.pathname + window.location.hash).toBe('/about');
    expect(viewport).toBe(300);
    await travel('forward');
    expect(window.location.hash).toBe('#far-down');
    expect(viewport).toBe(660);
    await travel('back');
  });

  it('Back from a "#…" link the browser follows itself puts the reader where they were', async () => {
    // The page is /about, with the target of the case before. The client leaves
    // such a link to the browser (the skip link moves the focus too). A browser
    // then adds an entry and fires hashchange, without popstate; jsdom fires
    // popstate too, so its own navigation is stopped and the browser's is played.
    const jump = document.createElement('a');
    jump.setAttribute('href', '#far-down');
    document.getElementById('main-content')!.appendChild(jump);
    viewport = 210;
    window.addEventListener('click', (event) => event.preventDefault(), { once: true });
    jump.click();
    history.pushState(null, '', '#far-down');
    window.dispatchEvent(new HashChangeEvent('hashchange'));
    // The browser scrolled to the target, and the reader a little further.
    viewport = 690;

    await travel('back');
    expect(window.location.pathname + window.location.hash).toBe('/about');
    expect(viewport).toBe(210);
    await travel('forward');
    expect(viewport).toBe(690);
    await travel('back');
  });

  it('a link that fails keeps the place: Back from the next page returns to it', async () => {
    await follow('/resource/films');
    expect(heading()).toBe('Films');
    viewport = 500;
    vi.mocked(globalThis.fetch).mockImplementation((input) =>
      String(input) === '/resource/films/1' ? Promise.resolve(html('down', 503)) : serve(input),
    );
    document.querySelector<HTMLAnchorElement>('#main-content a.item-card')!.click();
    await settle();
    expect(document.querySelectorAll('#main-content [role="alert"]')).toHaveLength(1);
    expect(viewport).toBe(500);

    await follow('/about');
    expect(viewport).toBe(0);
    await travel('back');
    expect(heading()).toBe('Films');
    expect(viewport).toBe(500);
  });

  it('while a page still waits, the place of the page on screen is not taken for the new entry', async () => {
    // On the list at 500. A link to the home waits; the reader scrolls the list
    // meanwhile; the home arrives at the top; Back restores the list where the
    // reader was when they left it.
    let release: () => void = () => {};
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    vi.mocked(globalThis.fetch).mockImplementation(async (input) => {
      if (String(input) === '/') await gate;
      return serve(input);
    });
    viewport = 500;
    link('/').click();
    await settle();
    viewport = 800;
    release();
    await settle();
    expect(heading()).toBe('The Star Wars API');
    expect(viewport).toBe(0);

    viewport = 50;
    await travel('back');
    expect(heading()).toBe('Films');
    expect(viewport).toBe(500);
    await travel('forward');
    expect(heading()).toBe('The Star Wars API');
    expect(viewport).toBe(50);
  });
});
