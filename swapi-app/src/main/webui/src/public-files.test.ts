import { describe, expect, it } from 'vitest';

/**
 * IndexNow key file. Search engines on the IndexNow list fetch
 * https://<host>/<key>.txt and expect the key itself as the body, to prove that
 * whoever pings URLs controls the host. The key is public by design. Quinoa
 * serves it as a static file; nothing else in public/ may be a .txt, so the
 * file is unambiguous.
 */
const txtFiles = import.meta.glob('../public/*.txt', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>;

describe('IndexNow key file', () => {
  it('is exactly one public/*.txt whose name is its content', () => {
    const entries = Object.entries(txtFiles);
    expect(entries).toHaveLength(1);
    const [file, content] = entries[0];
    const key = file.replace(/^.*\//, '').replace(/\.txt$/, '');
    expect(key).toMatch(/^[a-zA-Z0-9-]{8,128}$/);
    expect(content.trim()).toBe(key);
  });
});
