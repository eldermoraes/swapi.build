import { afterEach, describe, expect, it, vi } from 'vitest';

function settle(): Promise<void> {
  return new Promise((resolve) =>
    setTimeout(() => requestAnimationFrame(() => requestAnimationFrame(() => resolve())), 0),
  );
}

const film = { title: 'A New Hope', url: 'http://localhost:3000/api/films/1' };

// 2.10.0: the head of a record waits for the record. When it never comes (5xx,
// network), the head gets the fallback of the type, so it stops describing the
// page the visitor left.
describe('a record that fails to load after a client navigation', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('gets the fallback head of its type', async () => {
    document.head.innerHTML = `
      <title>Star Wars Films API: JSON data | swapi.build</title>
      <meta name="robots" content="index,follow" />
      <link rel="canonical" href="http://localhost:3000/resource/films" />
    `;
    document.body.innerHTML = `
      <nav class="sw-menu"><a href="/resource/films/1" id="to-film">A New Hope</a></nav>
      <main id="main-content" tabindex="-1"></main>
    `;
    history.replaceState(null, '', '/resource/films');
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) =>
      Promise.resolve(
        String(input).endsWith('/api/films/1')
          ? new Response('{}', { status: 503 })
          : new Response(JSON.stringify([film]), { status: 200 }),
      ),
    );

    await import('./main');
    await settle();
    document.getElementById('to-film')!.click();
    await settle();

    expect(document.querySelector('#main-content h1')?.textContent).toBe('Resource unavailable');
    expect(document.title).toBe('Films #1 | swapi.build');
    expect(document.querySelector('link[rel="canonical"]')?.getAttribute('href')).toBe(
      `${window.location.origin}/resource/films/1`,
    );
  });
});
