import { afterEach, describe, expect, it, vi } from 'vitest';

function settle(): Promise<void> {
  return new Promise((resolve) =>
    setTimeout(() => requestAnimationFrame(() => requestAnimationFrame(() => resolve())), 0),
  );
}

const film = { title: 'A New Hope', url: 'http://localhost:3000/api/films/1' };

// 2.12.0 review: a second click on the same link while its navigation is in
// flight must not take the address it just pushed as the one to go back to.
// Otherwise a failure puts back the record's URL over the list's content, and
// the URL and the content disagree.
describe('a double click on a record link whose document then fails', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('ignores the second click: one request, and the URL goes back to the list', async () => {
    document.head.innerHTML = `
      <title>Star Wars Films API: JSON data | swapi.build</title>
      <link rel="canonical" href="http://localhost:3000/resource/films" />
    `;
    document.body.innerHTML = `
      <nav class="sw-menu"><a href="/resource/films/1" id="to-film">A New Hope</a></nav>
      <main id="main-content" tabindex="-1"></main>
    `;
    history.replaceState(null, '', '/resource/films');
    let release!: () => void;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    const fetch = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      if (String(input) === '/resource/films/1') {
        await gate;
        return new Response('down', { status: 503, headers: { 'Content-Type': 'text/html' } });
      }
      return new Response(JSON.stringify([film]), { status: 200 });
    });

    await import('./main');
    await settle();
    expect(document.querySelector('#main-content h1')?.textContent).toBe('Films');

    const link = document.getElementById('to-film')!;
    link.click();
    await settle();
    link.click();
    await settle();
    release();
    await settle();
    await settle();

    const main = document.getElementById('main-content')!;
    expect(fetch.mock.calls.filter((call) => String(call[0]) === '/resource/films/1')).toHaveLength(
      1,
    );
    expect(main.querySelector('h1')?.textContent).toBe('Films');
    expect(main.querySelectorAll('[role="alert"]')).toHaveLength(1);
    expect(window.location.pathname).toBe('/resource/films');
  });
});
