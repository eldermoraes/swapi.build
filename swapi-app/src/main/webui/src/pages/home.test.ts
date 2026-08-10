import { describe, it, expect, vi } from 'vitest';

vi.mock('../api', () => ({
  fetchEndpoint: vi.fn().mockResolvedValue({ data: {}, status: 200 }),
  ApiError: class ApiError extends Error {},
}));

import { renderHome } from './home';

describe('home page (Holonet Terminal)', () => {
  it('renders greeting, display title, terminal and six resource rows', () => {
    const container = document.createElement('main');
    renderHome(container);
    expect(container.querySelector('.sw-greeting')!.textContent).toContain('A long time ago');
    expect(container.querySelector('.sw-hero h1')!.textContent).toBe('The Star Wars API');
    expect(container.querySelector('.sw-term')).toBeTruthy();
    expect(container.querySelectorAll('.sw-index .sw-row')).toHaveLength(6);
    expect(container.querySelector('.resource-grid')).toBeNull(); // old cards are gone
  });
});
