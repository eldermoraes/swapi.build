import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';
import aboutMain from '../../../test/resources/ssr/about.html?raw';
import faqMain from '../../../test/resources/ssr/faq.html?raw';

// 2.15.0 (review of slice G2): /faq#compare. A link from another page fetches
// /faq and then lands on the comparison, not on the top; a link or Back and
// Forward that only changes the fragment of the page shown fetches nothing and
// redraws nothing. One main.ts for the whole file; the cases run in order.

function settle(): Promise<void> {
  return new Promise((resolve) =>
    setTimeout(() => requestAnimationFrame(() => requestAnimationFrame(() => resolve())), 0),
  );
}

function documentWith(main: string): string {
  return `<!doctype html><html lang="en"><head><title>x</title></head><body>${main}</body></html>`;
}

function serve(input: RequestInfo | URL): Promise<Response> {
  const url = String(input);
  const main = url === '/faq' ? faqMain : url === '/about' ? aboutMain : null;
  if (main === null) return Promise.resolve(new Response('{}', { status: 503 }));
  return Promise.resolve(
    new Response(documentWith(main), {
      status: 200,
      headers: { 'Content-Type': 'text/html;charset=UTF-8' },
    }),
  );
}

describe('/faq#compare on the client', () => {
  const scrolledTo: string[] = [];
  const scrollTo = vi.fn();
  const fetchSpy = vi.fn(serve);
  const swaps: number[] = [];
  let observer: MutationObserver;

  beforeAll(async () => {
    Object.defineProperty(Element.prototype, 'scrollIntoView', {
      configurable: true,
      value: function (this: Element) {
        scrolledTo.push(this.id);
      },
    });
    vi.spyOn(window, 'scrollTo').mockImplementation(scrollTo);
    vi.spyOn(globalThis, 'fetch').mockImplementation(fetchSpy);
    document.head.innerHTML = '<title>About: a SWAPI alternative built with Quarkus | swapi.build</title>';
    document.body.innerHTML = `<nav class="sw-menu"><a href="/faq" class="nav-link">FAQ</a></nav>${aboutMain}`;
    history.replaceState(null, '', '/about');
    const serverMain = await import('./server-main');
    serverMain.resetServerMain({ assign: vi.fn() });
    await import('./main');
    await settle();
    observer = new MutationObserver((records) =>
      swaps.push(records.filter((r) => r.removedNodes.length > 0).length),
    );
    observer.observe(document.getElementById('main-content')!, { childList: true });
  });

  afterAll(() => {
    observer.disconnect();
    vi.restoreAllMocks();
  });

  function clear(): void {
    scrolledTo.length = 0;
    scrollTo.mockClear();
    fetchSpy.mockClear();
    swaps.length = 0;
  }

  it('from /about, the credits link fetches /faq once and lands on the comparison', async () => {
    clear();
    const link = document.querySelector<HTMLAnchorElement>('#main-content a[href="/faq#compare"]');
    expect(link).not.toBeNull();
    link!.click();
    await settle();

    expect(window.location.pathname + window.location.hash).toBe('/faq#compare');
    expect(fetchSpy.mock.calls.map(([url]) => String(url))).toEqual(['/faq']);
    expect(document.querySelector('#main-content h1')?.textContent).toBe('FAQ');
    expect(scrolledTo).toEqual(['compare']);
    expect(scrollTo).not.toHaveBeenCalled();
  });

  it('a link to another fragment of /faq: no fetch, no redraw, the page scrolls to it', async () => {
    clear();
    const link = document.createElement('a');
    link.href = '/faq#is-it-up';
    link.textContent = 'up?';
    document.getElementById('main-content')!.appendChild(link);
    swaps.length = 0;
    link.click();
    await settle();

    expect(window.location.pathname + window.location.hash).toBe('/faq#is-it-up');
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(swaps.reduce((a, b) => a + b, 0)).toBe(0);
    expect(scrolledTo).toEqual(['is-it-up']);
    link.remove();
  });

  it('the "Comparison" link inside /faq: no fetch, no redraw', async () => {
    clear();
    const inPage = document.querySelector<HTMLAnchorElement>(
      '#main-content .faq-question a[href="/faq#compare"]',
    );
    expect(inPage).not.toBeNull();
    inPage!.click();
    await settle();

    expect(window.location.hash).toBe('#compare');
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(swaps.reduce((a, b) => a + b, 0)).toBe(0);
    expect(scrolledTo).toEqual(['compare']);
  });

  it('Back to the other fragment: no fetch, no redraw', async () => {
    clear();
    const popped = new Promise((resolve) =>
      window.addEventListener('popstate', resolve, { once: true }),
    );
    history.back();
    await popped;
    await settle();

    expect(window.location.pathname + window.location.hash).toBe('/faq#is-it-up');
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(swaps.reduce((a, b) => a + b, 0)).toBe(0);
  });

  it('a fragment that names nothing: no fetch, no error, the page stays', async () => {
    clear();
    const link = document.createElement('a');
    link.href = '/faq#nothing-here';
    document.getElementById('main-content')!.appendChild(link);
    swaps.length = 0;
    link.click();
    await settle();

    expect(window.location.hash).toBe('#nothing-here');
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(swaps.reduce((a, b) => a + b, 0)).toBe(0);
    expect(scrolledTo).toEqual([]);
    link.remove();
  });

  it('Back to /about and forward to /faq#compare: the comparison again, from the cache', async () => {
    // Back past the fragments of /faq to /about (a different document).
    while (window.location.pathname !== '/about') {
      const popped = new Promise((resolve) =>
        window.addEventListener('popstate', resolve, { once: true }),
      );
      history.back();
      await popped;
      await settle();
    }
    expect(document.querySelector('#main-content h1')?.textContent).toBe('About');
    clear();
    const popped = new Promise((resolve) =>
      window.addEventListener('popstate', resolve, { once: true }),
    );
    history.forward();
    await popped;
    await settle();

    expect(window.location.pathname + window.location.hash).toBe('/faq#compare');
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(document.querySelector('#main-content h1')?.textContent).toBe('FAQ');
    expect(scrolledTo).toEqual(['compare']);
  });
});
