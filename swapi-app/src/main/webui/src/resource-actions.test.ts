import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import recordMain from '../../../test/resources/ssr/record-films-1.html?raw';
import { ResourceExplorer } from './resource-actions';
import { resetServerMain } from './server-main';
import { escapeHtml } from './utils';

let container: HTMLElement;
let explorer: ResourceExplorer;
const luke = {
  name: 'Luke Skywalker',
  url: 'https://swapi.build/api/people/1',
  birth_year: '19BBY',
};
const leia = { name: 'Leia Organa', url: 'https://swapi.build/api/people/5', birth_year: '19BBY' };

/**
 * The document of a record address as the server sends it since 2.12.0: the
 * <main> with the marker and the JSON of the record in its <pre>.
 */
function recordDocument(type: string, id: number, entity: Record<string, unknown>): Response {
  const name = String(entity[type === 'films' ? 'title' : 'name']);
  const main = `<main id="main-content" tabindex="-1" data-ssr-page="resource-detail" data-ssr-type="${type}" data-ssr-id="${id}">
    <div class="detail-view"><h1 class="detail-title">${escapeHtml(name)}</h1>
    <div class="sw-code result-panel"><pre class="result-body">${escapeHtml(JSON.stringify(entity, null, 2))}</pre></div></div></main>`;
  return html(`<!doctype html><html><head><title>t</title></head><body>${main}</body></html>`);
}

function html(body: string, status = 200): Response {
  return new Response(body, { status, headers: { 'Content-Type': 'text/html;charset=UTF-8' } });
}

beforeEach(() => {
  document.body.innerHTML = '<main tabindex="-1"></main>';
  container = document.querySelector('main')!;
  explorer = new ResourceExplorer(container, (path) => history.replaceState(null, '', path));
  // The tab loaded "/"; no full load in a test; no record kept from a case before.
  resetServerMain({ documentPath: '/', assign: () => {} });
});
afterEach(() => {
  explorer.dispose();
  vi.unstubAllGlobals();
});

it('lists complete records and clears an earlier search on the same route', async () => {
  vi.stubGlobal(
    'fetch',
    vi
      .fn()
      .mockResolvedValueOnce(new Response(JSON.stringify([luke])))
      .mockResolvedValueOnce(new Response(JSON.stringify([luke, leia]))),
  );
  await explorer.execute('sw_search', { resource: 'PEOPLE', query: 'Luke' });
  const result = await explorer.execute('sw_list', { resource: 'PEOPLE' });
  expect(result).toMatchObject({
    ok: true,
    data: [luke, leia],
    count: 2,
    path: '/resource/people',
  });
  expect(container.querySelectorAll('.item-card')).toHaveLength(2);
  expect(container.querySelector<HTMLInputElement>('#search-input')!.value).toBe('');
  expect(location.pathname).toBe('/resource/people');
});

// Changes on purpose in 2.12.0 (slice E2): the draw of /api/<type>/random says
// which record, then the document of that record (verify-webmcp.cjs counts one
// request to /api). Before, the JSON of the draw was drawn by the client.
it('opens the exact randomly selected record: one request to /api, one to its document', async () => {
  const fetch = vi
    .fn()
    .mockResolvedValueOnce(new Response(JSON.stringify(leia)))
    .mockResolvedValueOnce(recordDocument('people', 5, leia));
  vi.stubGlobal('fetch', fetch);
  const result = await explorer.execute('sw_random', { resource: 'PEOPLE' });
  expect(result).toMatchObject({ ok: true, id: 5, data: leia, path: '/resource/people/5' });
  expect(container.querySelector('h1')!.textContent).toBe('Leia Organa');
  expect(fetch.mock.calls.map((call) => String(call[0]))).toEqual([
    '/api/people/random',
    '/resource/people/5',
  ]);
});

// 2.12.0, slice E2: a record is one request, the HTML document; the record
// comes from its <pre>, with the validator of the API answers.
it('gets film record 1 from its document alone, rather than treating it as episode 1', async () => {
  const fetch = vi.fn().mockResolvedValue(html(`<!doctype html><body>${recordMain}</body>`));
  vi.stubGlobal('fetch', fetch);
  const result = await explorer.execute('sw_get', { resource: 'FILMS', id: 1 });
  expect(result).toMatchObject({ ok: true, id: 1, path: '/resource/films/1' });
  expect(result.ok && (result.data as Record<string, unknown>).title).toBe('A New Hope');
  expect(fetch).toHaveBeenCalledTimes(1);
  expect(String(fetch.mock.calls[0][0])).toBe('/resource/films/1');
  expect(fetch.mock.calls[0][1]).toMatchObject({ headers: { Accept: 'text/html' } });
  // execute resolves after the swap: the page is the record (verify-webmcp.cjs reads the h1).
  expect(container.querySelector('h1')!.textContent).toBe('A New Hope');
  expect(container.querySelector('th')?.textContent).toBe('Episode');
  expect(container.hasAttribute('data-ssr-page')).toBe(false);
  // The JSON is coloured and its text stays the server's.
  const pre = container.querySelector('pre.result-body')!;
  expect(pre.querySelector('.json-key')).not.toBeNull();
  expect(pre.textContent).toBe(JSON.stringify(JSON.parse(pre.textContent!), null, 2));
});

it('a record whose <pre> is not the record asked for is an invalid response, nothing swapped', async () => {
  container.textContent = 'Keep this view';
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(recordDocument('people', 1, leia)));
  expect(await explorer.execute('sw_get', { resource: 'PEOPLE', id: 1 })).toMatchObject({
    ok: false,
    error: { code: 'INVALID_RESPONSE' },
  });
  expect(container.textContent).toContain('Keep this view');
});

it('a newer action during the fetch of the document: the old answer is aborted and dropped', async () => {
  let finish!: (response: Response) => void;
  const signals: AbortSignal[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn((_url: string, init?: RequestInit) => {
      signals.push(init!.signal!);
      return new Promise<Response>((resolve) => {
        finish = resolve;
      });
    }),
  );
  const commits: string[] = [];
  const local = new ResourceExplorer(container, (path) => commits.push(path));
  const pending = local.execute('sw_get', { resource: 'PEOPLE', id: 1 });
  await vi.waitFor(() => expect(finish).toBeDefined());
  local.interrupt();
  expect(signals[0].aborted).toBe(true);
  finish(recordDocument('people', 1, luke));
  expect(await pending).toMatchObject({ ok: false, error: { code: 'SUPERSEDED' } });
  expect(commits).toEqual([]);
  expect(container.querySelector('h1')).toBeNull();
  local.dispose();
});

it.each([
  [503, 'HTTP_ERROR'],
  [0, 'NETWORK_ERROR'],
])(
  'a document that fails (%s): the code of today, an alert with a full load, the content intact',
  async (status, code) => {
    container.innerHTML = '<h1>The list</h1>';
    const fetch = vi.fn();
    if (status === 0) fetch.mockRejectedValue(new TypeError('offline'));
    else fetch.mockResolvedValue(html('down', Number(status)));
    vi.stubGlobal('fetch', fetch);
    expect(await explorer.execute('sw_get', { resource: 'PEOPLE', id: 1 })).toMatchObject({
      ok: false,
      error: { code },
    });
    expect(container.querySelector('h1')!.textContent).toBe('The list');
    const alert = container.querySelector('[role="alert"]')!;
    expect(alert.querySelector('a')?.getAttribute('href')).toBe('/resource/people/1');
    expect(container.hasAttribute('aria-busy')).toBe(false);
  },
);

it('rejects invalid tool arguments before requesting or changing the page', async () => {
  const fetch = vi.fn();
  vi.stubGlobal('fetch', fetch);
  container.textContent = 'Keep this view';
  for (const args of [
    { resource: '../secret' },
    { resource: 'people' },
    { resource: 'PEOPLE', unexpected: true },
  ]) {
    expect(await explorer.execute('sw_list', args)).toMatchObject({
      ok: false,
      error: { code: 'INVALID_ARGUMENT' },
    });
  }
  expect(fetch).not.toHaveBeenCalled();
  expect(container.textContent).toBe('Keep this view');
});

it('rejects records without a coherent resource ID rather than guessing an index', async () => {
  vi.stubGlobal(
    'fetch',
    vi
      .fn()
      .mockResolvedValue(new Response(JSON.stringify([{ name: 'Luke', url: '/api/films/1' }]))),
  );
  expect(await explorer.execute('sw_list', { resource: 'PEOPLE' })).toMatchObject({
    ok: false,
    error: { code: 'INVALID_RESPONSE' },
  });
});

it('lets typing supersede a pending agent action and rejects concurrent agent work', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(new Response(JSON.stringify([luke]))));
  await explorer.execute('sw_list', { resource: 'PEOPLE' });
  let finish!: (response: Response) => void;
  vi.stubGlobal(
    'fetch',
    vi.fn(
      () =>
        new Promise<Response>((resolve) => {
          finish = resolve;
        }),
    ),
  );
  const pending = explorer.execute('sw_list', { resource: 'PEOPLE' });
  expect(await explorer.execute('sw_random', { resource: 'PEOPLE' })).toMatchObject({
    ok: false,
    error: { code: 'BUSY' },
  });
  const input = container.querySelector<HTMLInputElement>('input')!;
  input.value = 'Leia';
  input.dispatchEvent(new Event('input', { bubbles: true }));
  finish(new Response(JSON.stringify([leia])));
  expect(await pending).toMatchObject({ ok: false, error: { code: 'SUPERSEDED' } });
  expect(input.value).toBe('Leia');
  expect(container.querySelector('.item-name')!.textContent).toBe('Luke Skywalker');
});

it.each(['PEOPLE', 'FILMS', 'PLANETS', 'SPECIES', 'STARSHIPS', 'VEHICLES'])(
  'lists %s without dropping fields',
  async (resource) => {
    const entity = {
      [resource === 'FILMS' ? 'title' : 'name']: 'Example',
      url: `/api/${resource.toLowerCase()}/9`,
      extra: ['retained'],
    };
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify([entity]))));
    expect(await explorer.execute('sw_list', { resource })).toMatchObject({
      ok: true,
      data: [entity],
    });
  },
);

it.each(['', ' ', '<script>'])('preserves search text %j safely', async (query) => {
  const fetch = vi.fn().mockResolvedValue(new Response('[]'));
  vi.stubGlobal('fetch', fetch);
  expect(await explorer.execute('sw_search', { resource: 'PEOPLE', query })).toMatchObject({
    ok: true,
    count: 0,
  });
  expect(container.querySelector<HTMLInputElement>('input')!.value).toBe(query);
  expect(container.querySelector('script')).toBeNull();
  expect(String(fetch.mock.calls[0][0])).toBe(`/api/people?search=${encodeURIComponent(query)}`);
});

// Since 2.12.0 the request of sw_get is the document of the record (HTML).
it.each([
  [404, 'NOT_FOUND'],
  [503, 'HTTP_ERROR'],
])('reports HTTP %s as %s', async (status, code) => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(html('', Number(status))));
  expect(await explorer.execute('sw_get', { resource: 'PEOPLE', id: 99 })).toMatchObject({
    ok: false,
    error: { code },
  });
  expect(container.querySelector('[role="alert"]')).not.toBeNull();
  expect(container.hasAttribute('aria-busy')).toBe(false);
});

it('cancels while the body is loading without changing the current page', async () => {
  let finish!: (value: unknown) => void;
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    }),
  );
  const controller = new AbortController();
  const pending = explorer.execute(
    'sw_list',
    { resource: 'PEOPLE' },
    { signal: controller.signal },
  );
  await vi.waitFor(() => expect(finish).toBeDefined());
  controller.abort();
  finish([leia]);
  expect(await pending).toMatchObject({ ok: false, error: { code: 'CANCELLED' } });
  expect(container.querySelector('.item-card')).toBeNull();
  expect(container.hasAttribute('aria-busy')).toBe(false);
});

it.each(['button', 'submit', 'enter'])(
  'gives an existing %s control priority over a pending agent action',
  async (action) => {
    container.innerHTML =
      '<form><input value="people/1"><button type="button">Run</button></form><output>Human result</output>';
    let finish!: (response: Response) => void;
    vi.stubGlobal(
      'fetch',
      vi.fn(
        () =>
          new Promise<Response>((resolve) => {
            finish = resolve;
          }),
      ),
    );
    const pending = explorer.execute('sw_list', { resource: 'PEOPLE' });
    if (action === 'button') container.querySelector('button')!.click();
    else if (action === 'enter')
      container
        .querySelector('input')!
        .dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    else
      container
        .querySelector('form')!
        .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    finish(new Response(JSON.stringify([luke])));
    expect(await pending).toMatchObject({ ok: false, error: { code: 'SUPERSEDED' } });
    expect(container.querySelector('output')!.textContent).toBe('Human result');
  },
);

// 2.10.0: the head of a record is written once, after the record arrives, from
// the record itself; lists have no record.
it('commits the path with the record for a detail and without it for a list', async () => {
  const commits: [string, unknown][] = [];
  const local = new ResourceExplorer(document.createElement('div'), (path, entity) => {
    commits.push([path, entity]);
  });
  vi.stubGlobal(
    'fetch',
    vi
      .fn()
      .mockResolvedValueOnce(recordDocument('people', 1, luke))
      .mockResolvedValueOnce(new Response(JSON.stringify(leia)))
      .mockResolvedValueOnce(recordDocument('people', 5, leia))
      .mockResolvedValueOnce(new Response(JSON.stringify([luke, leia])))
      .mockResolvedValueOnce(new Response(JSON.stringify([luke]))),
  );
  await local.execute('sw_get', { resource: 'PEOPLE', id: 1 });
  await local.execute('sw_random', { resource: 'PEOPLE' });
  await local.execute('sw_list', { resource: 'PEOPLE' });
  await local.execute('sw_search', { resource: 'PEOPLE', query: 'Luke' });
  local.dispose();
  expect(commits).toEqual([
    ['/resource/people/1', luke],
    ['/resource/people/5', leia],
    ['/resource/people', undefined],
    ['/resource/people', undefined],
  ]);
});
