import { escapeHtml } from './utils';

/**
 * Colours a JSON text without changing it: the text content of the result is
 * the input, character for character. The record pages (2.12.0) colour the
 * text the server emitted, which is already JSON.stringify(record, null, 2).
 *
 * Until 2.11.0 the whitespace the regex takes after a string (to see whether a
 * colon follows) was dropped, so the last string of an array or object lost
 * its line break; it is kept now (json-highlight.test.ts).
 */
export function highlightJsonText(json: string): string {
  return json.replace(
    /("(?:\\.|[^"\\])*")(\s*)(:)?|(\b(?:true|false)\b)|(\bnull\b)|(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)/g,
    (
      match,
      str: string | undefined,
      space: string | undefined,
      colon: string | undefined,
      bool: string | undefined,
      nil: string | undefined,
      num: string | undefined,
    ) => {
      if (str) {
        const kind = colon ? 'json-key' : 'json-string';
        return `<span class="${kind}">${escapeHtml(str)}</span>${space ?? ''}${colon ?? ''}`;
      }
      if (bool) return `<span class="json-bool">${match}</span>`;
      if (nil) return `<span class="json-null">${match}</span>`;
      if (num) return `<span class="json-number">${match}</span>`;
      return match;
    },
  );
}

export function highlightJson(data: unknown): string {
  return highlightJsonText(JSON.stringify(data, null, 2));
}
