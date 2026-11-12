import { vi } from 'vitest';

/**
 * Time allowed for {@link loadAppModules}. The first load of the app in a test
 * worker transforms its whole module graph, `style.css` included (`css: true`
 * in vitest.config.ts). That takes well under a second on a quiet machine but
 * more than 10 s when several suites share the CPU (#109).
 */
export const APP_LOAD_TIMEOUT = 60_000;

// Every module of the app except main.ts, which starts the app when it is
// evaluated, and the tests. main.ts imports only modules from this list, plus
// style.css, so a module main.ts starts to import is warmed the day it lands.
const appModules = import.meta.glob([
  '../**/*.ts',
  '../style.css',
  '!../main.ts',
  '!../**/*.test.ts',
  '!../**/*.d.ts',
  '!./**',
]);

/**
 * Loads the app's modules once, then forgets them, so the first
 * `import('./main')` of a test file is not charged with transforming them.
 * Call it as `beforeAll(loadAppModules, APP_LOAD_TIMEOUT)` at the top of every
 * test file that imports `./main` (app-modules.test.ts enforces it).
 *
 * It changes nothing a test sees: `vi.resetModules()` drops the instances it
 * evaluated (mocks declared with `vi.mock` stay, as they do for any
 * `vi.resetModules()`), so `./main` and everything it imports are evaluated
 * again on their next import, and the `<style>` that `style.css` adds is taken
 * out again. Only the transformed code stays cached in the worker.
 */
export async function loadAppModules(): Promise<void> {
  const before = new Set<Node>([...document.head.childNodes, ...document.body.childNodes]);
  await Promise.all(Object.values(appModules).map((load) => load()));
  for (const node of [...document.head.childNodes, ...document.body.childNodes]) {
    if (!before.has(node)) node.parentNode?.removeChild(node);
  }
  vi.resetModules();
}
