import { describe, expect, it } from 'vitest';
import { renderNotFound } from './not-found';

function render(path: string): HTMLElement {
  const container = document.createElement('main');
  renderNotFound(container, path);
  return container;
}

describe('not-found page', () => {
  it('has the page title in the display face', () => {
    const title = render('/nope').querySelector('h1.sw-page-title');
    expect(title?.textContent).toBe('Page not found');
  });

  it('uses the inner prose layout of the text pages', () => {
    expect(render('/nope').querySelector('.about.sw-inner-prose.not-found')).not.toBeNull();
  });

  it('shows the requested path escaped, never as markup', () => {
    const container = render('/<img src=x>');
    expect(container.querySelector('img')).toBeNull();
    expect(container.querySelector('code')?.textContent).toBe('/<img src=x>');
  });

  it('offers three pill actions: home, the API and the docs', () => {
    const pills = [...render('/nope').querySelectorAll<HTMLAnchorElement>('.sw-cta-row a.sw-pill')];
    expect(pills.map((a) => a.getAttribute('href'))).toEqual(['/', '/resource/people', '/docs']);
    expect(pills.map((a) => a.textContent)).toEqual([
      'Go to the home page',
      'Browse the API',
      'Read the docs',
    ]);
  });

  it('has no fake buttons', () => {
    expect(render('/nope').querySelector('[role="button"]')).toBeNull();
  });
});
