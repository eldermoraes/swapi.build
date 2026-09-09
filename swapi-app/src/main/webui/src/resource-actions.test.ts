import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { ResourceExplorer } from './resource-actions';

let container: HTMLElement;
let explorer: ResourceExplorer;
const luke = {
  name: 'Luke Skywalker',
  url: 'https://swapi.build/api/people/1',
  birth_year: '19BBY',
};
const leia = { name: 'Leia Organa', url: 'https://swapi.build/api/people/5', birth_year: '19BBY' };
beforeEach(() => {
  document.body.innerHTML = '<main tabindex="-1"></main>';
  container = document.querySelector('main')!;
  explorer = new ResourceExplorer(container, (path) => history.replaceState(null, '', path));
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

it('opens the exact randomly selected record without a second request', async () => {
  const fetch = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify(leia)));
  vi.stubGlobal('fetch', fetch);
  const result = await explorer.execute('sw_random', { resource: 'PEOPLE' });
  expect(result).toMatchObject({ ok: true, id: 5, data: leia, path: '/resource/people/5' });
  expect(container.querySelector('h1')!.textContent).toBe('Leia Organa');
  expect(fetch).toHaveBeenCalledTimes(1);
});

it('gets film record 1 rather than treating it as episode 1', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          title: 'A New Hope',
          episode_id: 4,
          url: 'https://swapi.build/api/films/1',
        }),
      ),
    ),
  );
  expect(await explorer.execute('sw_get', { resource: 'FILMS', id: 1 })).toMatchObject({
    ok: true,
    id: 1,
  });
  expect(container.querySelector('h1')!.textContent).toBe('A New Hope');
});

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

it.each([
  [404, 'NOT_FOUND'],
  [503, 'HTTP_ERROR'],
])('reports HTTP %s as %s', async (status, code) => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: Number(status) })));
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
