import { afterEach, describe, expect, it, vi } from 'vitest';
import mcpMain from '../../../../test/resources/ssr/mcp.html?raw';
import { bindMcp } from './mcp';

/** The /docs/mcp as the server drew it (fixture from SsrFixturesTest). */
function serverMcp(): HTMLElement {
  document.body.innerHTML = mcpMain;
  return document.querySelector<HTMLElement>('main')!;
}

function inactive(container: HTMLElement): boolean[] {
  return Array.from(container.querySelectorAll('.tab-panel'), (p) =>
    p.hasAttribute('data-inactive'),
  );
}

function key(target: Element, k: string): void {
  target.dispatchEvent(new KeyboardEvent('keydown', { key: k, bubbles: true }));
}

// 2.11.0: every panel arrives visible (no `hidden`), four marked `data-inactive`,
// which hide only under the `js` class of <html>.
describe('mcp page binding', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('arrives with five visible panels, four marked inactive', () => {
    const container = serverMcp();
    const panels = container.querySelectorAll<HTMLElement>('.tab-panel');
    expect(panels).toHaveLength(5);
    expect(Array.from(panels).some((p) => p.hidden)).toBe(false);
    expect(inactive(container)).toEqual([false, true, true, true, true]);
  });

  it('a click on a tab moves data-inactive and the selection', () => {
    const container = serverMcp();
    bindMcp(container);
    const tabs = container.querySelectorAll<HTMLButtonElement>('.tab-btn');
    tabs[2].click();
    expect(inactive(container)).toEqual([true, true, false, true, true]);
    expect(tabs[2].getAttribute('aria-selected')).toBe('true');
    expect(tabs[0].getAttribute('aria-selected')).toBe('false');
    expect(tabs[2].tabIndex).toBe(0);
    expect(document.activeElement).toBe(tabs[2]);
  });

  it('moves between tabs with the arrows, Home and End', () => {
    const container = serverMcp();
    bindMcp(container);
    const tabs = container.querySelectorAll<HTMLButtonElement>('.tab-btn');
    key(tabs[0], 'ArrowRight');
    expect(inactive(container)).toEqual([true, false, true, true, true]);
    key(tabs[1], 'ArrowLeft');
    expect(inactive(container)).toEqual([false, true, true, true, true]);
    key(tabs[0], 'ArrowLeft');
    expect(inactive(container)).toEqual([true, true, true, true, false]);
    key(tabs[4], 'Home');
    expect(inactive(container)).toEqual([false, true, true, true, true]);
    key(tabs[0], 'End');
    expect(inactive(container)).toEqual([true, true, true, true, false]);
  });

  it('copies the endpoint the server wrote', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    const container = serverMcp();
    bindMcp(container);
    const button = container.querySelector<HTMLButtonElement>('[data-copy-target="endpoint"]')!;
    button.click();
    await vi.waitFor(() => expect(button.textContent).toBe('Copied!'));
    expect(writeText).toHaveBeenCalledWith('https://preview.example/mcp');
  });
});
