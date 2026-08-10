import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../api', () => ({
  fetchEndpoint: vi.fn().mockResolvedValue({ data: { name: 'Luke Skywalker' }, status: 200 }),
  ApiError: class ApiError extends Error {},
}));

import { pill, sectionLabel, terminalMarkup, initTerminal, indexRows } from './components';
import { fetchEndpoint } from '../api';

beforeEach(() => {
  document.body.innerHTML = '';
  vi.clearAllMocks();
});

describe('pill', () => {
  it('renders solid and ghost variants', () => {
    expect(pill('Get started', '/docs')).toContain('sw-pill--solid');
    expect(pill('MCP', '/docs/mcp', 'ghost')).toContain('sw-pill--ghost');
  });
});

describe('sectionLabel', () => {
  it('renders an uppercase-ready heading', () => {
    expect(sectionLabel('The resources')).toContain('sw-section-label');
  });
});

describe('indexRows', () => {
  it('renders one row per resource with href and endpoint', () => {
    const html = indexRows([
      { title: 'People', endpoint: '/api/people', href: '/resource/people' },
    ]);
    document.body.innerHTML = html;
    const row = document.querySelector('a.sw-row')!;
    expect(row.getAttribute('href')).toBe('/resource/people');
    expect(row.textContent).toContain('People');
    expect(row.textContent).toContain('/api/people');
  });
});

describe('terminal', () => {
  const opts = { idPrefix: 'home', suggestions: ['people/1', 'planets/1'] };

  it('renders prompt, EXEC button and chips', () => {
    document.body.innerHTML = terminalMarkup(opts);
    expect(document.querySelector('.sw-term-prompt input')).toBeTruthy();
    expect(document.querySelectorAll('.sw-term-chips button')).toHaveLength(2);
  });

  it('chip click fills input, calls the API and renders output', async () => {
    document.body.innerHTML = terminalMarkup(opts);
    initTerminal(document.body, opts);
    (document.querySelector('.sw-term-chips button') as HTMLButtonElement).click();
    await vi.waitFor(() => {
      expect(fetchEndpoint).toHaveBeenCalledWith('people/1');
      expect(document.querySelector('.sw-term-out')!.textContent).toContain('Luke Skywalker');
      expect(document.querySelector('.sw-term-out')!.textContent).toContain('200');
    });
    expect((document.querySelector('.sw-term-prompt input') as HTMLInputElement).value).toBe(
      'people/1',
    );
  });
});
