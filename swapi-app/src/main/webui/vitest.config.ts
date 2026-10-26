import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    environment: 'jsdom',
    // Without this, Vitest stubs CSS modules and even `?raw` imports resolve to
    // an empty string — tokens.test.ts would then assert against nothing.
    css: true,
  },
  // seo-parity.test.ts reads SeoMetadataRegistry.java with `?raw`. It lives
  // outside the Vite root, which Vite refuses by default ("Denied ID"). Only that
  // directory is opened, and only for the tests; it goes away with slice D.
  server: {
    fs: {
      allow: ['.', '../java/com/eldermoraes/seo'],
    },
  },
});
