import { describe, it, expect, vi, beforeEach } from 'vitest';
import homeMain from '../../../../test/resources/ssr/home.html?raw';
import pillFixture from '../../../../test/resources/ssr/pill.html?raw';

vi.mock('../api', () => ({
  fetchEndpoint: vi.fn().mockResolvedValue({ data: { name: 'Luke Skywalker' }, status: 200 }),
  ApiError: class ApiError extends Error {},
}));

import { pill, initTerminal } from './components';
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

  // The server draws the same pill with the Qute tag (templates/tags/pill.html;
  // the fixture comes from SsrFixturesTest).
  it('is the same markup the server tag draws', () => {
    const squash = (html: string) => html.replace(/\s+/g, '');
    expect(squash(pill('Get started', '/docs'))).toBe(squash(pillFixture));
  });
});

// The terminal the server drew on the home (fixture from SsrFixturesTest).
describe('terminal', () => {
  it('chip click fills input, calls the API and renders output', async () => {
    document.body.innerHTML = homeMain;
    initTerminal(document.body, 'home');
    expect(document.querySelectorAll('.sw-term-chips button')).toHaveLength(4);
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

  it('EXEC runs the typed path', async () => {
    document.body.innerHTML = homeMain;
    initTerminal(document.body, 'home');
    (document.querySelector('.sw-term-prompt input') as HTMLInputElement).value = 'films/2';
    (document.querySelector('.sw-term-exec') as HTMLButtonElement).click();
    await vi.waitFor(() => expect(fetchEndpoint).toHaveBeenCalledWith('films/2'));
  });
});
