import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';
import aboutMain from '../../../test/resources/ssr/about.html?raw';
import homeMain from '../../../test/resources/ssr/home.html?raw';

// Issue #71: the active item of the header menu says so to a screen reader
// (aria-current="page"), not only with a class and an underline.

function settle(): Promise<void> {
  return new Promise((resolve) =>
    setTimeout(() => requestAnimationFrame(() => requestAnimationFrame(() => resolve())), 0),
  );
}

function serve(input: RequestInfo | URL): Promise<Response> {
  const url = String(input);
  if (url === '/') {
    return Promise.resolve(
      new Response(
        `<!doctype html><html><head><title>x</title></head><body>${homeMain}</body></html>`,
        {
          status: 200,
          headers: { 'Content-Type': 'text/html;charset=UTF-8' },
        },
      ),
    );
  }
  if (url.includes('/films')) return Promise.resolve(new Response('[]', { status: 200 }));
  return Promise.resolve(new Response('{}', { status: 503 }));
}

/** The href of every menu item that says it is the current page. */
function current(): (string | null)[] {
  return Array.from(document.querySelectorAll('.nav-link[aria-current]'), (item) => {
    expect(item.getAttribute('aria-current')).toBe('page');
    return item.getAttribute('href');
  });
}

describe('the active item of the header menu', () => {
  beforeAll(async () => {
    vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
    vi.spyOn(globalThis, 'fetch').mockImplementation(serve);
    document.head.innerHTML = '<title>About | swapi.build</title>';
    document.body.innerHTML = `
      <nav class="sw-menu">
        <a href="/" class="nav-link">Home</a>
        <a href="/about" class="nav-link">About</a>
        <a href="/resource/films" id="to-films">Films</a>
      </nav>${aboutMain}`;
    history.replaceState(null, '', '/about');
    const serverMain = await import('./server-main');
    serverMain.resetServerMain({ assign: vi.fn() });
    await import('./main');
    await settle();
  });

  afterAll(() => {
    vi.restoreAllMocks();
  });

  it('carries aria-current="page" on the first load', () => {
    expect(current()).toEqual(['/about']);
  });

  it('moves with a navigation: only the new page is current', async () => {
    document.querySelector<HTMLAnchorElement>('.nav-link[href="/"]')!.click();
    await settle();
    expect(document.querySelector('#main-content h1')?.textContent).toBe('The Star Wars API');
    expect(current()).toEqual(['/']);
  });

  it('is on no item when the page has no menu item', async () => {
    document.getElementById('to-films')!.click();
    await settle();
    expect(window.location.pathname).toBe('/resource/films');
    expect(current()).toEqual([]);
  });
});
