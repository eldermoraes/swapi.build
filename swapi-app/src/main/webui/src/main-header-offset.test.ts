import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';
import aboutMain from '../../../test/resources/ssr/about.html?raw';
import baseCss from './styles/base.css?raw';

// Issue #71: a #fragment target lands below the sticky header, not under it.
// The stylesheet pads the scroll of the root by the header's height, which the
// client measures (the menu wraps on a phone, so no fixed number is right).
// jsdom has no layout: the header's box and ResizeObserver are faked.

function settle(): Promise<void> {
  return new Promise((resolve) =>
    setTimeout(() => requestAnimationFrame(() => requestAnimationFrame(() => resolve())), 0),
  );
}

function headerHeight(): string {
  return document.documentElement.style.getPropertyValue('--sw-header-height');
}

describe('a fragment target is visible below the header', () => {
  let height = 122.4;
  const resized: (() => void)[] = [];
  const observed: Element[] = [];

  beforeAll(async () => {
    vi.stubGlobal(
      'ResizeObserver',
      class {
        constructor(callback: () => void) {
          resized.push(callback);
        }
        observe(target: Element) {
          observed.push(target);
        }
        disconnect() {}
      },
    );
    vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
    vi.spyOn(globalThis, 'fetch').mockImplementation(() =>
      Promise.resolve(new Response('{}', { status: 503 })),
    );
    document.head.innerHTML = '<title>About | swapi.build</title>';
    document.body.innerHTML = `
      <header class="sw-header"><nav class="sw-menu"></nav></header>${aboutMain}`;
    const header = document.querySelector<HTMLElement>('.sw-header')!;
    vi.spyOn(header, 'getBoundingClientRect').mockImplementation(
      () => ({ height }) as unknown as DOMRect,
    );
    history.replaceState(null, '', '/about');
    await import('./main');
    await settle();
  });

  afterAll(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    document.documentElement.style.removeProperty('--sw-header-height');
  });

  it('the root pads its scroll by the height of the header', () => {
    const root = baseCss.match(/(?:^|\n)html\s*\{([^}]*)\}/)?.[1] ?? '';
    expect(root).toMatch(/scroll-padding-top:[^;]*var\(--sw-header-height,\s*[\d.]+rem\)/);
  });

  it('the height is the one the header has, rounded up', () => {
    expect(headerHeight()).toBe('123px');
  });

  it('follows the header when it grows (the menu wraps on a phone)', () => {
    expect(observed).toEqual([document.querySelector('.sw-header')]);
    height = 151;
    resized.forEach((notify) => notify());
    expect(headerHeight()).toBe('151px');
  });
});
