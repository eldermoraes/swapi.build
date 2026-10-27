import { afterEach, describe, expect, it, vi } from 'vitest';

function settle(): Promise<void> {
  return new Promise((resolve) =>
    setTimeout(() => requestAnimationFrame(() => requestAnimationFrame(() => resolve())), 0),
  );
}

const film = { title: 'A New Hope', url: 'http://localhost:3000/api/films/1' };

// Changes on purpose in 2.12.0 (slice E2). Until 2.11.0 the record came from
// /api and its failure drew "Resource unavailable" with the fallback head of
// the type. Now the record is its document: when it does not come (5xx,
// network), the page that was there stays, with its head, and an alert offers
// the full load. "Resource unavailable" is only for a first load without the
// marker (main-first-load.test.ts).
describe('a record that fails to load after a client navigation', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('keeps the list and its head, shows an alert with a full load, and puts the address back', async () => {
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
        String(input) === '/resource/films/1'
          ? new Response('down', { status: 503, headers: { 'Content-Type': 'text/html' } })
          : new Response(JSON.stringify([film]), { status: 200 }),
      ),
    );

    await import('./main');
    await settle();
    expect(document.querySelector('#main-content h1')?.textContent).toBe('Films');
    document.getElementById('to-film')!.click();
    await settle();

    const main = document.getElementById('main-content')!;
    expect(main.querySelector('h1')?.textContent).toBe('Films');
    expect(main.querySelector('.item-card')).not.toBeNull();
    const alert = main.querySelector('[role="alert"]')!;
    expect(alert.querySelector('a')?.getAttribute('href')).toBe('/resource/films/1');
    expect(main.hasAttribute('aria-busy')).toBe(false);
    expect(window.location.pathname).toBe('/resource/films');
    expect(document.title).toBe('Star Wars Films API: JSON data | swapi.build');
    expect(document.querySelector('link[rel="canonical"]')?.getAttribute('href')).toBe(
      'http://localhost:3000/resource/films',
    );
  });
});
