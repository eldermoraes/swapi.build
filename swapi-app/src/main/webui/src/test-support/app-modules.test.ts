import { describe, expect, it } from 'vitest';
import { APP_LOAD_TIMEOUT, appModulePaths, loadAppModules } from './app-modules';

// Every module under src/, tests and helpers alike, by absolute path
// (/src/main.ts). Not this file: its comments and cases name ./main on purpose.
const sources = Object.fromEntries(
  Object.entries(
    import.meta.glob(['../**/*.ts', '!../**/*.d.ts', '!./app-modules.test.ts'], {
      query: '?raw',
      import: 'default',
      eager: true,
    }) as Record<string, string>,
  ).map(([key, source]) => [new URL(key, 'file:///src/test-support/').pathname, source]),
);

const MAIN = '/src/main.ts';
const TEST_FILE = /\.(test|spec)\.ts$/;
const HOOK = 'beforeAll(loadAppModules, APP_LOAD_TIMEOUT);';
// What evaluates another module: import('./x'), vi.importActual('./x'), vi.importMock('./x'),
// import ... from './x', export ... from './x' and import './x'. Any quotes.
const IMPORT_SPECIFIER =
  /(?:\b(?:import|importActual|importMock)\(\s*|\bfrom\s*|^\s*import\s*)(['"`])(\.\.?\/[^'"`]*)\1/gm;
// At column 0: top level, and not in a comment.
const LOADS_APP = /^beforeAll\(loadAppModules, APP_LOAD_TIMEOUT\);$/m;
const TOP_LEVEL_SUITE_OR_HOOK = /^(describe|suite|it|test|beforeAll|beforeEach)\b/m;

/** The modules of `modules` that `path` imports (relative specifiers: .ts, .js, none, /index.ts). */
function importsOf(path: string, modules: Record<string, string>): string[] {
  const found: string[] = [];
  for (const [, , specifier] of modules[path].matchAll(IMPORT_SPECIFIER)) {
    if (specifier.includes('?')) continue; // ?raw, ?url: read as text, not evaluated
    const target = new URL(specifier, `file://${path}`).pathname;
    const candidates = [
      target,
      target.replace(/\.js$/, '.ts'),
      `${target}.ts`,
      `${target}/index.ts`,
    ];
    const resolved = candidates.find((candidate) => candidate in modules);
    if (resolved) found.push(resolved);
  }
  return found;
}

/**
 * The test files of `modules` from which main.ts is reachable, each with the
 * import chain that reaches it (test file first, main.ts last).
 */
function testFilesThatReachMain(modules: Record<string, string>): Map<string, string[]> {
  const loading = new Map<string, string[]>();
  for (const test of Object.keys(modules).filter((path) => TEST_FILE.test(path))) {
    const via = new Map<string, string>([[test, test]]);
    const queue = [test];
    while (queue.length > 0 && !via.has(MAIN)) {
      const path = queue.shift()!;
      for (const next of importsOf(path, modules)) {
        if (via.has(next)) continue;
        via.set(next, path);
        queue.push(next);
      }
    }
    if (!via.has(MAIN)) continue;
    const chain = [MAIN];
    while (chain[0] !== test) chain.unshift(via.get(chain[0])!);
    loading.set(test, chain);
  }
  return loading;
}

/** What each test file must fix: empty when every test file that reaches ./main loads the app first. */
function appLoadProblems(modules: Record<string, string>): string[] {
  const problems: string[] = [];
  for (const [path, chain] of testFilesThatReachMain(modules)) {
    const source = modules[path];
    const load = source.search(LOADS_APP);
    if (load !== -1 && !TOP_LEVEL_SUITE_OR_HOOK.test(source.slice(0, load))) continue;
    problems.push(
      `${path} loads the app (${chain.join(' -> ')}) ` +
        (load === -1 ? 'without loading it first' : 'after a suite, test or hook') +
        `: add "${HOOK}" at the top level, ` +
        'before its first describe, test or hook, importing both from src/test-support/app-modules.ts (#109)',
    );
  }
  return problems;
}

const OPENS = "it('opens', async () => {});\n";

/** Problems of an in-memory tree that holds main.ts and `files`. */
function problemsOf(files: Record<string, string>): string[] {
  return appLoadProblems({ [MAIN]: '', ...files });
}

// #109: the first `import('./main')` of a worker transforms the whole app. In a
// test, or in a hook without its own timeout, that cost was charged to a 5 s
// (10 s) budget, and the suite timed out when several ran at once.
describe('loading the app in a test', () => {
  it('every test file that reaches ./main loads the app first, before its tests and hooks', () => {
    expect([...testFilesThatReachMain(sources).keys()]).toContain('/src/main.test.ts');
    expect(appLoadProblems(sources)).toEqual([]);
  });

  describe('the files it holds to the hook (#116)', () => {
    const helper = "export const openApp = () => import('../main');\n";
    const usesHelper = `import { openApp } from './test-support/open-app';\n${OPENS}`;

    it('a test that reaches ./main through a helper', () => {
      expect(
        problemsOf({ '/src/test-support/open-app.ts': helper, '/src/a.test.ts': usesHelper }),
      ).toEqual([
        '/src/a.test.ts loads the app (/src/a.test.ts -> /src/test-support/open-app.ts -> /src/main.ts) ' +
          'without loading it first: add "beforeAll(loadAppModules, APP_LOAD_TIMEOUT);" at the top level, ' +
          'before its first describe, test or hook, importing both from src/test-support/app-modules.ts (#109)',
      ]);
      expect(
        problemsOf({
          '/src/test-support/open-app.ts': helper,
          '/src/a.test.ts': `${HOOK}\n${usesHelper}`,
        }),
      ).toEqual([]);
    });

    it('a test that reaches ./main through a re-export, or a chain of helpers', () => {
      expect(
        problemsOf({
          '/src/test-support/app.ts': "export * from '../main.js';\n",
          '/src/a.test.ts': `import { start } from './test-support/app';\n${OPENS}`,
        }),
      ).toEqual([
        expect.stringContaining('(/src/a.test.ts -> /src/test-support/app.ts -> /src/main.ts)'),
      ]);
      expect(
        problemsOf({
          '/src/test-support/open-app.ts': helper,
          '/src/test-support/index.ts': "export { openApp } from './open-app';\n",
          '/src/pages/b.test.ts': `import { openApp } from '../test-support';\n${OPENS}`,
        }),
      ).toEqual([
        expect.stringContaining('/src/pages/b.test.ts -> /src/test-support/index.ts -> '),
      ]);
    });

    it('a test that imports ./main itself, with any quotes, through vi.importActual or vi.importMock too', () => {
      for (const load of [
        "import('./main')",
        'import("./main.ts")',
        'vi.importActual(`./main`)',
        "vi.importMock('./main.js')",
      ]) {
        expect(problemsOf({ '/src/a.test.ts': `${OPENS}await ${load};\n` })).toHaveLength(1);
      }
    });

    it('a hook that comes after a suite, test or hook, or sits in a comment', () => {
      const test = "it('opens', () => import('./main'));\n";
      expect(
        problemsOf({ '/src/a.test.ts': `describe('app', () => {});\n${HOOK}\n${test}` }),
      ).toEqual([expect.stringContaining('after a suite, test or hook')]);
      expect(problemsOf({ '/src/a.test.ts': `// ${HOOK}\n${test}` })).toEqual([
        expect.stringContaining('without loading it first'),
      ]);
      expect(problemsOf({ '/src/a.test.ts': `${HOOK}\n${test}` })).toEqual([]);
    });

    it('not a test that only reads main.ts as text, nor a helper nobody imports', () => {
      expect(
        problemsOf({
          '/src/a.test.ts': `import main from './main.ts?raw';\n${OPENS}`,
          '/src/test-support/open-app.ts': helper,
        }),
      ).toEqual([]);
    });
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
