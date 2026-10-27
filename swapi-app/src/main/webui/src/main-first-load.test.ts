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

// The head the server sends for /resource/films/1 (SeoGoldenTest checks it).
const SERVER_HEAD = `
  <title>A New Hope — Star Wars film JSON | swapi.build</title>
  <meta name="description" content="A New Hope, Star Wars Episode 4, directed by George Lucas and released 1977-05-25. Its JSON record from the free swapi.build REST API and MCP server." />
  <meta name="robots" content="index,follow" />
  <link rel="canonical" href="http://localhost:3000/resource/films/1" />
  <meta property="og:title" content="A New Hope — Star Wars film JSON | swapi.build" />
  <meta property="og:url" content="http://localhost:3000/resource/films/1" />
`;

/** The SEO part of the head (Vitest also injects the stylesheet there). */
function seoHead(): string[] {
  return Array.from(document.head.querySelectorAll('title, meta, link'), (el) => el.outerHTML);
}

// 2.10.0, the end of the overwrite: on the first load the head is the server's,
// and the client writes nothing to it, not before the fetch and not after.
describe('first load of an indexable record address', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('keeps the server head byte for byte and announces its title', async () => {
    document.head.innerHTML = SERVER_HEAD;
    const before = seoHead();
    expect(before).toHaveLength(6);
    document.body.innerHTML = `
      <nav class="sw-menu"><a href="/resource/films" class="nav-link">Films</a></nav>
      <main id="main-content" tabindex="-1"></main>
    `;
    history.replaceState(null, '', '/resource/films/1');
    const fetchSpy = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValue(new Response(JSON.stringify(film), { status: 200 }));

    await import('./main');
    await settle();

    expect(fetchSpy).toHaveBeenCalledTimes(1);
    expect(document.querySelector('#main-content h1')?.textContent).toBe('A New Hope');
    expect(seo.apply).not.toHaveBeenCalled();
    expect(seoHead()).toEqual(before);
    expect(document.querySelector('[aria-live="polite"].sr-only')?.textContent).toBe(
      'A New Hope — Star Wars film JSON',
    );
  });
});
