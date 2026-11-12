import { describe, expect, it } from 'vitest';
import { APP_LOAD_TIMEOUT, appModulePaths, loadAppModules } from './app-modules';

const testFiles = import.meta.glob(['../**/*.{test,spec}.{ts,tsx}', '!./**'], {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>;

// import('./main'), import("../main.ts"), vi.importActual(`./main`)...
const IMPORTS_MAIN = /(import|importActual)\(\s*['"`](\.\.?\/)+main(\.[jt]s)?['"`]/;
// At column 0: top level, and not in a comment.
const LOADS_APP = /^beforeAll\(loadAppModules, APP_LOAD_TIMEOUT\);$/m;
const TOP_LEVEL_SUITE_OR_HOOK = /^(describe|suite|it|test|beforeAll|beforeEach)\b/m;

// #109: the first `import('./main')` of a worker transforms the whole app. In a
// test, or in a hook without its own timeout, that cost was charged to a 5 s
// (10 s) budget, and the suite timed out when several ran at once.
describe('loading the app in a test', () => {
  it('every test file that imports ./main loads the app first, before its tests and hooks', () => {
    const files = Object.entries(testFiles).filter(([, source]) => IMPORTS_MAIN.test(source));
    expect(files.map(([path]) => path)).toContain('../main.test.ts');
    for (const [path, source] of files) {
      const load = source.search(LOADS_APP);
      expect(load, `${path} has no top-level beforeAll(loadAppModules, ...)`).toBeGreaterThan(-1);
      expect(
        source.slice(0, load),
        `${path} has a suite, test or hook before beforeAll(loadAppModules, ...)`,
      ).not.toMatch(TOP_LEVEL_SUITE_OR_HOOK);
    }
  });

  it('loads the app, without main.ts', () => {
    expect(appModulePaths).toEqual(
      expect.arrayContaining(['../server-main.ts', '../seo.ts', '../style.css']),
    );
    expect(appModulePaths).not.toContain('../main.ts');
    expect(appModulePaths.filter((path) => /\.test\.ts$|test-support/.test(path))).toEqual([]);
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
