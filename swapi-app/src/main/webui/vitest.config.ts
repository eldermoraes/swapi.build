import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    environment: 'jsdom',
    // Without this, Vitest stubs CSS modules and even `?raw` imports resolve to
    // an empty string — tokens.test.ts would then assert against nothing.
    css: true,
  },
});
