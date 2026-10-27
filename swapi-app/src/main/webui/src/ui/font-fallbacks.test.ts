import { describe, it, expect } from 'vitest';
import tokens from '../styles/tokens.css?raw';

/**
 * Metric-matched fallback faces (2.11.0). The four pages drawn on the server
 * paint before the Google Fonts arrive (display=swap); a fallback with other
 * widths wraps the header menu onto two lines on a phone and the swap moves the
 * whole page (home CLS 0.0052 -> 0.1858 on 360 px before this). Each web font
 * is followed in its stack by a local face scaled to its widths and metrics.
 */
const FALLBACKS = [
  {
    token: '--sw-font-display',
    web: 'Pathway Gothic One',
    fallback: 'Pathway Gothic One Fallback',
  },
  { token: '--sw-font-body', web: 'Inter', fallback: 'Inter Fallback' },
  { token: '--sw-font-mono', web: 'JetBrains Mono', fallback: 'JetBrains Mono Fallback' },
];

function fontFace(family: string): string | undefined {
  const faces = tokens.match(/@font-face\s*{[^}]*}/g) ?? [];
  return faces.find((face) => face.includes(`font-family: '${family}';`));
}

describe('metric-matched fallback faces', () => {
  for (const { token, web, fallback } of FALLBACKS) {
    it(`${fallback} is a local face with size and metric overrides`, () => {
      const face = fontFace(fallback);
      expect(face, `@font-face for '${fallback}'`).toBeDefined();
      expect(face).toMatch(/src:\s*local\('[^']+'\)/);
      for (const descriptor of [
        'size-adjust',
        'ascent-override',
        'descent-override',
        'line-gap-override',
      ]) {
        expect(face).toMatch(new RegExp(`${descriptor}:\\s*\\d+(\\.\\d+)?%;`));
      }
      // A fallback face never downloads anything.
      expect(face).not.toMatch(/url\(/);
    });

    it(`${token} lists '${fallback}' right after '${web}'`, () => {
      const stack = tokens.match(new RegExp(`${token}:\\s*([^;]+);`))?.[1] ?? '';
      const families = stack.split(',').map((f) => f.trim().replace(/^'|'$/g, ''));
      expect(families[0]).toBe(web);
      expect(families[1]).toBe(fallback);
    });
  }
});
