import { describe, it, expect } from 'vitest';

describe('vitest toolchain', () => {
  it('runs with a DOM', () => {
    document.body.innerHTML = '<p id="x">ok</p>';
    expect(document.getElementById('x')?.textContent).toBe('ok');
  });
});
