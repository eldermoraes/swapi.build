import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { APP_LOAD_TIMEOUT, loadAppModules } from './test-support/app-modules';
import aboutMain from '../../../test/resources/ssr/about.html?raw';
import type { BrowserTool, ModelContext } from './webmcp';

// Issue #71: a search from the search box of a list changes only the results.
// The focus stays where the reader has it, the page does not scroll, and the
// number of results is announced (not the title, which did not change).
// One main.ts for the whole file; the cases run in order.

function settle(): Promise<void> {
  return new Promise((resolve) =>
    setTimeout(() => requestAnimationFrame(() => requestAnimationFrame(() => resolve())), 0),
  );
}

const hope = { title: 'A New Hope', url: 'http://localhost:3000/api/films/1' };
const empire = { title: 'The Empire Strikes Back', url: 'http://localhost:3000/api/films/2' };
const jedi = { title: 'Return of the Jedi', url: 'http://localhost:3000/api/films/3' };

const FOUND: Record<string, unknown[]> = { hope: [hope], the: [empire, jedi], zzz: [] };

/** While true, /about answers 503. */
let aboutDown = false;

function serve(input: RequestInfo | URL): Promise<Response> {
  const url = String(input);
  if (url === '/about') {
    return Promise.resolve(
      new Response(
        `<!doctype html><html><head><title>x</title></head><body>${aboutMain}</body></html>`,
        {
          status: aboutDown ? 503 : 200,
          headers: { 'Content-Type': 'text/html;charset=UTF-8' },
        },
      ),
    );
  }
  const query = url.match(/^\/api\/films\?search=(.*)$/)?.[1];
  if (query !== undefined) {
    return Promise.resolve(new Response(JSON.stringify(FOUND[query] ?? []), { status: 200 }));
  }
  if (url === '/api/films') {
    return Promise.resolve(new Response(JSON.stringify([hope, empire, jedi]), { status: 200 }));
  }
  return Promise.resolve(new Response('{}', { status: 503 }));
}

function searchInput(): HTMLInputElement {
  return document.querySelector<HTMLInputElement>('#search-input')!;
}

function announced(): string | null | undefined {
  return document.querySelector('[aria-live="polite"].sr-only')?.textContent;
}

function names(): string[] {
  return Array.from(document.querySelectorAll('#main-content .item-name'), (n) => n.textContent!);
}

/** Types in the search box and presses Enter, as the reader does. */
async function search(query: string): Promise<void> {
  const input = searchInput();
  input.focus();
  input.value = query;
  input.dispatchEvent(new Event('input', { bubbles: true }));
  input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
  await settle();
}

beforeAll(loadAppModules, APP_LOAD_TIMEOUT);

describe('a search on a resource list', () => {
  const scrollTo = vi.fn();
  const tools = new Map<string, BrowserTool>();

  beforeAll(async () => {
    const modelContext: ModelContext = {
      registerTool: (tool) => {
        tools.set(tool.name, tool);
        return Promise.resolve();
      },
    };
    Object.defineProperty(document, 'modelContext', { configurable: true, value: modelContext });
    vi.spyOn(window, 'scrollTo').mockImplementation(scrollTo);
    vi.spyOn(globalThis, 'fetch').mockImplementation(serve);
    document.head.innerHTML = '<title>Star Wars Films API: JSON data | swapi.build</title>';
    document.body.innerHTML = `
      <nav class="sw-menu">
        <a href="/about" class="nav-link">About</a>
        <a href="/resource/films" id="to-films">Films</a>
      </nav>
      <main id="main-content" tabindex="-1"></main>
    `;
    history.replaceState(null, '', '/resource/films');
    const serverMain = await import('./server-main');
    serverMain.resetServerMain({ documentPath: '/resource/films', assign: vi.fn() });
    await import('./main');
    await settle();
  });

  beforeEach(() => {
    scrollTo.mockClear();
    vi.mocked(globalThis.fetch).mockClear();
  });

  afterAll(() => {
    vi.restoreAllMocks();
    Reflect.deleteProperty(document, 'modelContext');
  });

  it('keeps the focus in the search box, does not scroll, and announces the count', async () => {
    expect(names()).toHaveLength(3);
    const input = searchInput();
    const title = document.querySelector('#main-content h1');

    await search('hope');

    expect(vi.mocked(globalThis.fetch).mock.calls.map(([url]) => String(url))).toEqual([
      '/api/films?search=hope',
    ]);
    expect(names()).toEqual(['A New Hope']);
    // The same box, with what the reader typed, still focused: nothing around
    // the results was drawn again.
    expect(searchInput()).toBe(input);
    expect(document.activeElement).toBe(input);
    expect(input.value).toBe('hope');
    expect(document.querySelector('#main-content h1')).toBe(title);
    expect(scrollTo).not.toHaveBeenCalled();
    expect(announced()).toBe('1 result found.');
    expect(window.location.pathname + window.location.search).toBe('/resource/films');
  });

  it('announces a plural count, and no results', async () => {
    await search('the');
    expect(names()).toEqual(['The Empire Strikes Back', 'Return of the Jedi']);
    expect(announced()).toBe('2 results found.');

    await search('zzz');
    expect(names()).toEqual([]);
    expect(document.querySelector('#main-content .no-results')?.textContent).toBe(
      'No results found.',
    );
    expect(announced()).toBe('No results found.');
    expect(document.activeElement).toBe(searchInput());
  });

  it('the Search button: the focus stays on the button the reader pressed', async () => {
    const input = searchInput();
    input.value = 'hope';
    const button = document.querySelector<HTMLButtonElement>('#search-btn')!;
    button.focus();
    button.click();
    await settle();

    expect(names()).toEqual(['A New Hope']);
    expect(document.activeElement).toBe(button);
    expect(announced()).toBe('1 result found.');
    expect(scrollTo).not.toHaveBeenCalled();
  });

  it('the results are announced once: the region itself is not a live region', () => {
    expect(document.querySelector('#resource-content')?.closest('[aria-live]')).toBeNull();
  });

  it('takes away the alert of a link that failed, as the redraw of the page did', async () => {
    aboutDown = true;
    document.querySelector<HTMLAnchorElement>('.nav-link[href="/about"]')!.click();
    await settle();
    aboutDown = false;
    expect(window.location.pathname).toBe('/resource/films');
    expect(document.querySelectorAll('#main-content [role="alert"]')).toHaveLength(1);

    await search('the');
    expect(names()).toEqual(['The Empire Strikes Back', 'Return of the Jedi']);
    expect(document.querySelectorAll('#main-content [role="alert"]')).toHaveLength(0);
  });

  it('a search by a browser tool is a page change: the list drawn again, the title announced', async () => {
    const input = searchInput();
    const answer = JSON.parse(
      await tools.get('sw_search')!.execute({ resource: 'FILMS', query: 'hope' }),
    );
    await settle();

    expect(answer.ok).toBe(true);
    expect(names()).toEqual(['A New Hope']);
    expect(searchInput()).not.toBe(input);
    expect(searchInput().value).toBe('hope');
    expect(document.activeElement).toBe(document.getElementById('main-content'));
    expect(announced()).toContain('Films');
    expect(announced()).not.toContain('found');
  });

  it('a link to the list is still a page change: focus on the main region, the title announced', async () => {
    document.querySelector<HTMLAnchorElement>('.nav-link[href="/about"]')!.click();
    await settle();
    document.getElementById('to-films')!.click();
    await settle();

    expect(names()).toHaveLength(3);
    expect(searchInput().value).toBe('');
    expect(document.activeElement).toBe(document.getElementById('main-content'));
    expect(announced()).toContain('Films');
    expect(announced()).not.toContain('found');
    expect(scrollTo).toHaveBeenCalledWith(0, 0);
  });
});
