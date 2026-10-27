import { describe, expect, it } from 'vitest';
import apiBody from '../../../test/resources/ssr/record-films-1.api.json?raw';
import recordMain from '../../../test/resources/ssr/record-films-1.html?raw';
import { highlightJson, highlightJsonText } from './json-highlight';

/** The text of the <pre> the server drew for /resource/films/1 (fixture from SsrFixturesTest). */
function serverPre(): string {
  const doc = new DOMParser().parseFromString(recordMain, 'text/html');
  return doc.querySelector('pre.result-body')!.textContent!;
}

function textOf(html: string): string {
  const pre = document.createElement('pre');
  pre.innerHTML = html;
  return pre.textContent!;
}

// 2.12.0, slice E2: the server emits the JSON of a record with 2 spaces, the
// same text the client would make, so the client colours it without rewriting it.
describe('the JSON of a record page', () => {
  it('is JSON.stringify(apiBody, null, 2), byte for byte', () => {
    expect(serverPre()).toBe(JSON.stringify(JSON.parse(apiBody), null, 2));
  });

  it('keeps its text when coloured', () => {
    const text = serverPre();
    const html = highlightJsonText(text);
    expect(html).toContain('<span class="json-key">');
    expect(html).toContain('<span class="json-string">');
    expect(html).toContain('<span class="json-number">');
    expect(textOf(html)).toBe(text);
  });

  it('escapes what it colours', () => {
    const text = JSON.stringify({ name: '<img src=x onerror=alert(1)>&' }, null, 2);
    const html = highlightJsonText(text);
    expect(html).not.toContain('<img');
    expect(textOf(html)).toBe(text);
  });

  it('highlightJson is the same colouring of the stringified data', () => {
    const data = { a: [1, true, null, 'x'] };
    expect(highlightJson(data)).toBe(highlightJsonText(JSON.stringify(data, null, 2)));
  });
});
