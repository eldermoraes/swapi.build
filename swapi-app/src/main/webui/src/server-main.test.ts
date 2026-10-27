import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import aboutMain from '../../../test/resources/ssr/about.html?raw';
import homeMain from '../../../test/resources/ssr/home.html?raw';
import recordMain from '../../../test/resources/ssr/record-films-1.html?raw';
import {
  adoptServerMain,
  fetchServerMain,
  swapServerMain,
  loadServerMain,
  renderUnavailable,
  resetServerMain,
  showLoadFailure,
  CACHE_LIMIT,
} from './server-main';

type Assign = ReturnType<typeof vi.fn<(url: string) => void>>;

const SHELL_EMPTY = '<main id="main-content" tabindex="-1"></main>';

/** A whole document as the server sends it, with this <main>. */
function documentWith(main: string): string {
  return `<!doctype html><html lang="en"><head><title>t</title></head><body>
    <nav class="sw-menu"><a href="/">Home</a></nav>${main}<footer>f</footer></body></html>`;
}

function html(body: string, status = 200, type = 'text/html;charset=UTF-8'): Response {
  return new Response(body, { status, headers: { 'Content-Type': type } });
}

function mountShell(main: string): HTMLElement {
  document.body.innerHTML = main;
  return document.getElementById('main-content')!;
}

let assign: Assign;

beforeEach(() => {
  // The document loaded in this tab is "/" (jsdom cannot spy on location.assign).
  assign = vi.fn<(url: string) => void>();
  resetServerMain({ documentPath: '/', assign });
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('adoptServerMain', () => {
  it('adopts the main the server drew for this route without touching its children', () => {
    const container = mountShell(homeMain);
    const first = container.firstChild;
    expect(adoptServerMain(container, { page: 'home' })).toBe(true);
    expect(container.firstChild).toBe(first);
    expect(container.hasAttribute('data-ssr-page')).toBe(false);
  });

  it('does not adopt a marker of another page', () => {
    const container = mountShell(homeMain);
    expect(adoptServerMain(container, { page: 'about' })).toBe(false);
  });

  it('does not adopt an empty shell', () => {
    expect(adoptServerMain(mountShell(SHELL_EMPTY), { page: 'home' })).toBe(false);
  });

  // 2.12.0, slice E2: a record carries its type and id too; all three must match.
  it('adopts a record only with its own type and id, and removes the three attributes', () => {
    const film = { page: 'resource-detail', type: 'films', id: '1' } as const;
    expect(adoptServerMain(mountShell(recordMain), { ...film, id: '2' })).toBe(false);
    expect(adoptServerMain(mountShell(recordMain), { ...film, type: 'people' })).toBe(false);
    const container = mountShell(recordMain);
    const first = container.firstChild;
    expect(adoptServerMain(container, film)).toBe(true);
    expect(container.firstChild).toBe(first);
    for (const name of ['data-ssr-page', 'data-ssr-type', 'data-ssr-id']) {
      expect(container.hasAttribute(name)).toBe(false);
    }
  });
});

describe('fetchServerMain', () => {
  it('gives the <main> of a record without touching the page; the swap is the caller’s', async () => {
    const container = mountShell(SHELL_EMPTY);
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(html(documentWith(recordMain)));
    const fetched = await fetchServerMain('/resource/films/1', {
      page: 'resource-detail',
      type: 'films',
      id: '1',
    });
    expect(fetched.result).toBe('ok');
    expect(fetched.main?.querySelector('h1')?.textContent).toBe('A New Hope');
    expect(container.childNodes).toHaveLength(0);
    swapServerMain(container, fetched.main!);
    expect(container.querySelector('h1')?.textContent).toBe('A New Hope');
    expect(container.hasAttribute('data-ssr-id')).toBe(false);
  });

  it.each([
    [503, 503],
    [0, 0],
  ])('a failed fetch (%s) tells the status, 0 for the network', async (status, expected) => {
    const spy = vi.spyOn(globalThis, 'fetch');
    if (status === 0) spy.mockRejectedValue(new TypeError('offline'));
    else spy.mockResolvedValue(html('down', status));
    const fetched = await fetchServerMain('/about', { page: 'about' });
    expect(fetched).toEqual({ result: 'error', status: expected });
  });
});

describe('loadServerMain', () => {
  it('swaps the children for the fetched <main> and keeps the container', async () => {
    const container = mountShell(SHELL_EMPTY);
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(html(documentWith(aboutMain)));
    expect(await loadServerMain(container, '/about', { page: 'about' })).toBe('ok');
    expect(document.getElementById('main-content')).toBe(container);
    expect(container.querySelector('h1')?.textContent).toContain('About');
    expect(container.hasAttribute('data-ssr-page')).toBe(false);
    expect(document.querySelector('footer')).toBeNull();
  });

  it('asks for HTML on the path, with the signal', async () => {
    const container = mountShell(SHELL_EMPTY);
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(html(documentWith(aboutMain)));
    const controller = new AbortController();
    await loadServerMain(container, '/about', { page: 'about' }, { signal: controller.signal });
    expect(fetchSpy).toHaveBeenCalledTimes(1);
    const [url, init] = fetchSpy.mock.calls[0];
    expect(url).toBe('/about');
    expect(new Headers(init?.headers).get('Accept')).toBe('text/html');
    // The fetch signal follows the caller's (and the timeout, below).
    expect(init?.signal?.aborted).toBe(false);
    controller.abort();
    expect(init?.signal?.aborted).toBe(true);
  });

  it('without a marker on another path: one full load and "reload"', async () => {
    const container = mountShell(homeMain);
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(html(documentWith(SHELL_EMPTY)));
    expect(await loadServerMain(container, '/about', { page: 'about' })).toBe('reload');
    expect(assign).toHaveBeenCalledTimes(1);
    expect(assign).toHaveBeenCalledWith('/about');
  });

  it('the full load keeps the query of the target URL', async () => {
    const container = mountShell(homeMain);
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(html(documentWith(SHELL_EMPTY)));
    expect(
      await loadServerMain(container, '/about', { page: 'about' }, { search: '?ref=docs&x=1' }),
    ).toBe('reload');
    expect(assign).toHaveBeenCalledWith('/about?ref=docs&x=1');
  });

  it('a page that does not answer in time: "error", nothing swapped', async () => {
    resetServerMain({ documentPath: '/', assign, timeoutMs: 20 });
    const container = mountShell(homeMain);
    // A server that never answers; only the abort of its signal ends the request.
    vi.spyOn(globalThis, 'fetch').mockImplementation(
      (_input, init) =>
        new Promise((_resolve, reject) => {
          init?.signal?.addEventListener('abort', () => reject(init.signal!.reason), {
            once: true,
          });
        }),
    );
    expect(await loadServerMain(container, '/about', { page: 'about' })).toBe('error');
    expect(container.querySelector('h1')?.textContent).toBe('The Star Wars API');
    expect(assign).not.toHaveBeenCalled();
  });

  it.each(['any', 'timeout'] as const)(
    'a browser without AbortSignal.%s still loads the page, with the caller signal',
    async (missing) => {
      const original = Object.getOwnPropertyDescriptor(AbortSignal, missing)!;
      Object.defineProperty(AbortSignal, missing, { value: undefined, configurable: true });
      try {
        const container = mountShell(SHELL_EMPTY);
        const fetchSpy = vi
          .spyOn(globalThis, 'fetch')
          .mockResolvedValue(html(documentWith(aboutMain)));
        const controller = new AbortController();
        expect(
          await loadServerMain(
            container,
            '/about',
            { page: 'about' },
            { signal: controller.signal },
          ),
        ).toBe('ok');
        controller.abort();
        expect(fetchSpy.mock.calls[0][1]?.signal?.aborted).toBe(true);
      } finally {
        Object.defineProperty(AbortSignal, missing, original);
      }
    },
  );

  it.each([
    ['fetched', false],
    ['cached', true],
  ])(
    'no longer current when the answer is ready (%s): "aborted", nothing swapped',
    async (_name, cachedFirst) => {
      let container = mountShell(homeMain);
      vi.spyOn(globalThis, 'fetch').mockResolvedValue(html(documentWith(aboutMain)));
      if (cachedFirst) {
        await loadServerMain(container, '/about', { page: 'about' });
        container = mountShell(homeMain);
      }
      expect(
        await loadServerMain(container, '/about', { page: 'about' }, { isCurrent: () => false }),
      ).toBe('aborted');
      expect(container.querySelector('h1')?.textContent).toBe('The Star Wars API');
    },
  );

  it('without a marker on the path of the loaded document: "unavailable", never a reload', async () => {
    const container = mountShell(SHELL_EMPTY);
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(html(documentWith(SHELL_EMPTY)));
    expect(await loadServerMain(container, '/', { page: 'home' })).toBe('unavailable');
    expect(assign).not.toHaveBeenCalled();
  });

  it('a marker of another page counts as no marker', async () => {
    const container = mountShell(SHELL_EMPTY);
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(html(documentWith(homeMain)));
    expect(await loadServerMain(container, '/about', { page: 'about' })).toBe('reload');
  });

  it('404: "not-found"', async () => {
    const container = mountShell(homeMain);
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(html(documentWith(SHELL_EMPTY), 404));
    expect(await loadServerMain(container, '/about', { page: 'about' })).toBe('not-found');
    expect(container.querySelector('h1')?.textContent).toBe('The Star Wars API');
  });

  it.each([
    ['a 5xx', () => Promise.resolve(html('down', 503))],
    ['a network failure', () => Promise.reject(new TypeError('Failed to fetch'))],
    ['a JSON answer', () => Promise.resolve(html('{}', 200, 'application/json'))],
  ])('%s: "error", nothing swapped, nothing cached', async (_name, answer) => {
    const container = mountShell(homeMain);
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockImplementation(answer);
    expect(await loadServerMain(container, '/about', { page: 'about' })).toBe('error');
    expect(container.querySelector('h1')?.textContent).toBe('The Star Wars API');
    expect(assign).not.toHaveBeenCalled();
    fetchSpy.mockResolvedValue(html(documentWith(aboutMain)));
    await loadServerMain(container, '/about', { page: 'about' });
    expect(fetchSpy).toHaveBeenCalledTimes(2);
  });

  it('aborted: nothing swapped', async () => {
    const container = mountShell(homeMain);
    const controller = new AbortController();
    vi.spyOn(globalThis, 'fetch').mockImplementation(() => {
      controller.abort();
      return Promise.reject(new DOMException('aborted', 'AbortError'));
    });
    expect(
      await loadServerMain(container, '/about', { page: 'about' }, { signal: controller.signal }),
    ).toBe('aborted');
    expect(container.querySelector('h1')?.textContent).toBe('The Star Wars API');
  });

  it('aborted after the answer arrived: nothing swapped', async () => {
    const container = mountShell(homeMain);
    const controller = new AbortController();
    vi.spyOn(globalThis, 'fetch').mockImplementation(() => {
      controller.abort();
      return Promise.resolve(html(documentWith(aboutMain)));
    });
    expect(
      await loadServerMain(container, '/about', { page: 'about' }, { signal: controller.signal }),
    ).toBe('aborted');
    expect(container.querySelector('h1')?.textContent).toBe('The Star Wars API');
  });

  it('a second load of the same path makes no request (memory cache)', async () => {
    const container = mountShell(SHELL_EMPTY);
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(html(documentWith(aboutMain)));
    await loadServerMain(container, '/about', { page: 'about' });
    container.replaceChildren();
    expect(await loadServerMain(container, '/about', { page: 'about' })).toBe('ok');
    expect(fetchSpy).toHaveBeenCalledTimes(1);
    expect(container.querySelector('h1')?.textContent).toContain('About');
  });

  it(`keeps at most ${CACHE_LIMIT} paths`, async () => {
    const container = mountShell(SHELL_EMPTY);
    const fetchSpy = vi
      .spyOn(globalThis, 'fetch')
      .mockImplementation(() => Promise.resolve(html(documentWith(aboutMain))));
    for (let i = 0; i <= CACHE_LIMIT; i++) {
      await loadServerMain(container, `/about?n=${i}`, { page: 'about' });
    }
    expect(fetchSpy).toHaveBeenCalledTimes(CACHE_LIMIT + 1);
    // The newest is still there, the oldest left.
    await loadServerMain(container, `/about?n=${CACHE_LIMIT}`, { page: 'about' });
    expect(fetchSpy).toHaveBeenCalledTimes(CACHE_LIMIT + 1);
    await loadServerMain(container, '/about?n=0', { page: 'about' });
    expect(fetchSpy).toHaveBeenCalledTimes(CACHE_LIMIT + 2);
  });
});

describe('renderUnavailable', () => {
  it('shows a static error with a full-load Reload link to the same path', () => {
    const container = mountShell(SHELL_EMPTY);
    renderUnavailable(container, '/docs/mcp');
    expect(container.querySelector('h1')?.textContent).toBe('This page could not be loaded');
    const link = container.querySelector('a')!;
    expect(link.textContent).toBe('Reload');
    expect(link.getAttribute('href')).toBe('/docs/mcp');
    // target keeps the SPA click handler away: the link is a real load.
    expect(link.hasAttribute('target')).toBe(true);
  });
});

describe('showLoadFailure', () => {
  it('adds one alert with a full-load link and keeps the content', () => {
    const container = mountShell(homeMain);
    showLoadFailure(container, '/about');
    showLoadFailure(container, '/about');
    const alerts = container.querySelectorAll('[role="alert"]');
    expect(alerts).toHaveLength(1);
    const link = alerts[0].querySelector('a')!;
    expect(link.getAttribute('href')).toBe('/about');
    expect(link.hasAttribute('target')).toBe(true);
    expect(container.querySelector('h1')?.textContent).toBe('The Star Wars API');
  });

  it('escapes the path', () => {
    const container = mountShell(homeMain);
    showLoadFailure(container, '/about"><img src=x>');
    expect(container.querySelector('img')).toBeNull();
  });
});
