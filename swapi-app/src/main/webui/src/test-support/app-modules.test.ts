import { describe, expect, it } from 'vitest';
import { APP_LOAD_TIMEOUT, loadAppModules } from './app-modules';

const testFiles = import.meta.glob(['../**/*.test.ts', '!./**'], {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>;

const IMPORTS_MAIN = /import\(\s*['"](\.\.?\/)+main(\.ts)?['"]\s*\)/;
const LOADS_APP = 'beforeAll(loadAppModules, APP_LOAD_TIMEOUT);';

// #109: the first `import('./main')` of a worker transforms the whole app. In a
// test, or in a hook without its own timeout, that cost was charged to a 5 s
// (10 s) budget, and the suite timed out when several ran at once.
describe('loading the app in a test', () => {
  it('every test file that imports ./main loads the app first, before its tests and hooks', () => {
    const files = Object.entries(testFiles).filter(([, source]) => IMPORTS_MAIN.test(source));
    expect(files.map(([path]) => path)).toContain('../main.test.ts');
    for (const [path, source] of files) {
      const load = source.indexOf(LOADS_APP);
      expect(load, `${path} has no top-level ${LOADS_APP}`).toBeGreaterThan(-1);
      expect(source.slice(0, load), `${path} runs ${LOADS_APP} after a describe`).not.toMatch(
        /^\s*describe\(/m,
      );
    }
  });

  it(
    'leaves no module instance and no element behind',
    async () => {
      const serverMain = await import('../server-main');
      const page = document.documentElement.outerHTML;

      await loadAppModules();

      expect(document.documentElement.outerHTML).toBe(page);
      expect(await import('../server-main')).not.toBe(serverMain);
    },
    APP_LOAD_TIMEOUT,
  );
});
