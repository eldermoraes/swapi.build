import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    environment: 'jsdom',
    // Without this, Vitest stubs CSS modules and even `?raw` imports resolve to
    // an empty string — tokens.test.ts would then assert against nothing.
    css: true,
  },
  // Files outside the Vite root, which Vite refuses by default ("Denied ID"):
  // seo/site.json (src/site.ts), and for seo-golden.test.ts the data files and
  // the golden cases the server also checks (SeoGoldenTest).
  server: {
    fs: {
      allow: ['.', '../resources/seo', '../resources/data', '../../test/resources/seo'],
    },
  },
});
