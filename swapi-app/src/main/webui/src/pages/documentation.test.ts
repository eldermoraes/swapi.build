import { afterEach, describe, expect, it, vi } from 'vitest';
import docsMain from '../../../../test/resources/ssr/docs.html?raw';
import { bindDocumentation } from './documentation';

/** The /docs as the server drew it (fixture from SsrFixturesTest). */
function serverDocs(): HTMLElement {
  document.body.innerHTML = docsMain;
  return document.querySelector<HTMLElement>('main')!;
}

// 2.11.0: the server draws /docs from the OpenAPI document; the client only
// binds "Try it".
describe('documentation page binding', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('arrives with button-type "Try it" and turns them into submit buttons', () => {
    const container = serverDocs();
    const buttons = Array.from(container.querySelectorAll<HTMLButtonElement>('.try-button'));
    expect(buttons.length).toBe(container.querySelectorAll('.try-form').length);
    expect(buttons.every((b) => b.type === 'button')).toBe(true);
    bindDocumentation(container);
    expect(buttons.every((b) => b.type === 'submit')).toBe(true);
  });

  it('sends "Try it" of /api/people/{id} with the typed id and shows the answer', async () => {
    const fetchSpy = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValue(new Response(JSON.stringify({ name: 'C-3PO' }), { status: 200 }));
    const container = serverDocs();
    bindDocumentation(container);
    const block = container.querySelector<HTMLElement>('[data-path="/api/people/{id}"]')!;
    block.querySelector<HTMLInputElement>('input[name="id"]')!.value = '2';
    block.querySelector<HTMLButtonElement>('.try-button')!.click();
    await vi.waitFor(() => {
      expect(fetchSpy.mock.calls[0]?.[0]).toBe('/api/people/2');
      const result = block.querySelector<HTMLElement>('.try-result')!;
      expect(result.hidden).toBe(false);
      expect(result.textContent).toContain('HTTP 200');
      expect(result.textContent).toContain('C-3PO');
    });
  });

  it('adds the search to a list operation', async () => {
    const fetchSpy = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValue(new Response('[]', { status: 200 }));
    const container = serverDocs();
    bindDocumentation(container);
    const block = container.querySelector<HTMLElement>('[data-path="/api/people"]')!;
    block.querySelector<HTMLInputElement>('input[name="search"]')!.value = 'luke';
    block.querySelector<HTMLButtonElement>('.try-button')!.click();
    await vi.waitFor(() => expect(fetchSpy.mock.calls[0]?.[0]).toBe('/api/people?search=luke'));
  });

  // Issue #71: "Try it" asks /api too; a request that hangs ends with an error.
  it('a "Try it" that does not answer in 10 seconds says so instead of "Loading…"', async () => {
    const timer = new AbortController();
    const limits: number[] = [];
    vi.spyOn(AbortSignal, 'timeout').mockImplementation((ms) => {
      limits.push(ms);
      return timer.signal;
    });
    vi.spyOn(globalThis, 'fetch').mockImplementation(
      (_url, options) =>
        new Promise<Response>((_resolve, reject) => {
          options?.signal?.addEventListener('abort', () => reject(options.signal!.reason));
        }),
    );
    const container = serverDocs();
    bindDocumentation(container);
    const block = container.querySelector<HTMLElement>('[data-path="/api/people"]')!;
    block.querySelector<HTMLButtonElement>('.try-button')!.click();
    const result = block.querySelector<HTMLElement>('.try-result')!;
    await vi.waitFor(() => expect(result.textContent).toContain('Loading'));

    timer.abort(new DOMException('The operation timed out.', 'TimeoutError'));
    await vi.waitFor(() => expect(result.textContent).toContain('took too long'));
    expect(limits).toEqual([10_000]);
  });
});
