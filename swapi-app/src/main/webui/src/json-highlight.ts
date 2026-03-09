import { escapeHtml } from './utils';

export function highlightJson(data: unknown): string {
  const json = JSON.stringify(data, null, 2);
  return json.replace(
    /("(?:\\.|[^"\\])*")\s*(:)?|(\b(?:true|false)\b)|(\bnull\b)|(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)/g,
    (
      match,
      str: string | undefined,
      colon: string | undefined,
      bool: string | undefined,
      nil: string | undefined,
      num: string | undefined,
    ) => {
      if (str) {
        if (colon) {
          return `<span class="json-key">${escapeHtml(str)}</span>:`;
        }
        return `<span class="json-string">${escapeHtml(str)}</span>`;
      }
      if (bool) return `<span class="json-bool">${match}</span>`;
      if (nil) return `<span class="json-null">${match}</span>`;
      if (num) return `<span class="json-number">${match}</span>`;
      return match;
    },
  );
}
