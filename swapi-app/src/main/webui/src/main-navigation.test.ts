import { afterEach, describe, expect, it, vi } from 'vitest';

const seo = vi.hoisted(() => ({ apply: vi.fn() }));

vi.mock('./seo', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./seo')>();
  seo.apply.mockImplementation(actual.applySeoMetadata);
  return { ...actual, applySeoMetadata: seo.apply };
});

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
