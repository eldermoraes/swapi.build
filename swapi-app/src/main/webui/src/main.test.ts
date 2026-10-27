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

  // The server sent "Page not found" with the swapi:status marker. The client must
  // not rewrite the head with "People #9999" and the canonical of the list before
  // a fetch fails: the route is resolved first, and the head is applied once.
  it('applies the not-found metadata once, announces it and does not call the API', async () => {
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

    expect(seo.apply).toHaveBeenCalledTimes(1);
    expect(seo.apply).toHaveBeenCalledWith('/resource/people/9999', window.location.origin, {
      notFound: true,
    });
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
