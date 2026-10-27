import { defineConfig } from 'vite'

export default defineConfig({
  server: {
    port: 5173,
    // src/site.ts imports seo/site.json, the metadata file the server also reads.
    // It lives outside the Vite root, which the dev server refuses by default.
    fs: {
      allow: ['.', '../resources/seo'],
    },
  },
  build: {
    outDir: 'dist',
  },
})
