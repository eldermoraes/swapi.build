import { afterEach, describe, expect, it, vi } from 'vitest';

const seo = vi.hoisted(() => ({ apply: vi.fn() }));

vi.mock('./seo', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./seo')>();
  seo.apply.mockImplementation(actual.applySeoMetadata);
  return { ...actual, applySeoMetadata: seo.apply };
});

function nextFrames(): Promise<void> {
  return new Promise((resolve) =>
    requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
  );
}

describe('first load of a page the server answered with 404', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  // The server sent "Page not found" with the swapi:status marker. Since 2.10.0
  // the client never rewrites the head on the first load (the server's head is
  // the truth): no "People #9999", no canonical of the list, not even the same
  // not-found head written again. It announces the title the server sent.
  it('leaves the head alone, announces it and does not call the API', async () => {
    document.head.innerHTML = `
      <title>Page not found | swapi.build</title>
      <meta name="robots" content="noindex" />
      <meta name="swapi:status" content="404" />
    `;
    document.body.innerHTML = `
      <nav class="sw-menu"><a href="/" class="nav-link">Home</a></nav>
      <main id="main-content" tabindex="-1"></main>
    `;
    history.replaceState(null, '', '/resource/people/9999');
    const fetchSpy = vi.spyOn(globalThis, 'fetch');

    await import('./main');
    await nextFrames();

    expect(seo.apply).not.toHaveBeenCalled();
    expect(document.title).toBe('Page not found | swapi.build');
    expect(document.querySelector('meta[name="swapi:status"]')).toBeNull();
    expect(document.querySelector('[aria-live="polite"].sr-only')?.textContent).toBe(
      'Page not found',
    );
    expect(document.querySelector('#main-content h1.sw-page-title')?.textContent).toBe(
      'Page not found',
    );
    expect(document.querySelector('.nav-link.active')).toBeNull();
    expect(fetchSpy).not.toHaveBeenCalled();
  });
});
