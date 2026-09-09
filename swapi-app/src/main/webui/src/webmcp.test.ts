import { afterEach, expect, it, vi } from 'vitest';
import { ResourceExplorer } from './resource-actions';
import { registerWebMcp, type BrowserTool } from './webmcp';

afterEach(() => {
  vi.unstubAllGlobals();
});

it('registers the four MCP operations and executes them through the real explorer', async () => {
  document.body.innerHTML = '<main></main>';
  const explorer = new ResourceExplorer(document.querySelector('main')!, () => {});
  const tools: BrowserTool[] = [];
  const registration = registerWebMcp(explorer, {
    registerTool: async (tool) => {
      tools.push(tool);
    },
  });
  await registration.ready;
  expect(tools.map((tool) => tool.name).sort()).toEqual([
    'sw_get',
    'sw_list',
    'sw_random',
    'sw_search',
  ]);
  vi.stubGlobal(
    'fetch',
    vi
      .fn()
      .mockResolvedValue(new Response(JSON.stringify([{ name: 'Luke', url: '/api/people/1' }]))),
  );
  const result = JSON.parse(
    await tools
      .find((tool) => tool.name === 'sw_list')!
      .execute({ resource: 'PEOPLE' }, { signal: new AbortController().signal }),
  );
  expect(result).toMatchObject({ ok: true, count: 1 });
  expect(document.querySelector('.item-name')!.textContent).toBe('Luke');
  registration.dispose();
  explorer.dispose();
});

it('stays optional when unsupported and removes partial registrations on failure', async () => {
  const explorer = new ResourceExplorer(document.createElement('main'), () => {});
  const unsupported = registerWebMcp(explorer);
  await unsupported.ready;
  expect(unsupported.status).toBe('unavailable');
  const signals: AbortSignal[] = [];
  const context = {
    registerTool: async (_tool: BrowserTool, options: { signal: AbortSignal }) => {
      signals.push(options.signal);
      if (signals.length === 2) throw new Error('unsupported schema');
    },
  };
  const registration = registerWebMcp(explorer, context);
  expect(registerWebMcp(explorer, context)).toBe(registration);
  await registration.ready;
  expect(registration.status).toBe('failed');
  expect(signals.every((signal) => signal.aborted)).toBe(true);
  registration.dispose();
  explorer.dispose();
});

it('does not register more tools after disposal during an asynchronous registration', async () => {
  const explorer = new ResourceExplorer(document.createElement('main'), () => {});
  let finish!: () => void;
  const registerTool = vi.fn(
    () =>
      new Promise<void>((resolve) => {
        finish = resolve;
      }),
  );
  const registration = registerWebMcp(explorer, { registerTool });
  registration.dispose();
  finish();
  await registration.ready;
  expect(registerTool).toHaveBeenCalledTimes(1);
  expect(registration.status).toBe('stopped');
  explorer.dispose();
});

it('supports browsers that invoke a tool without cancellation options', async () => {
  const explorer = new ResourceExplorer(document.createElement('main'), () => {});
  const tools: BrowserTool[] = [];
  const registration = registerWebMcp(explorer, {
    registerTool: async (tool) => {
      tools.push(tool);
    },
  });
  await registration.ready;
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('[]')));
  const result = JSON.parse(
    await tools.find((tool) => tool.name === 'sw_list')!.execute({ resource: 'PEOPLE' }),
  );
  expect(result).toMatchObject({ ok: true, data: [] });
  registration.dispose();
  explorer.dispose();
});
