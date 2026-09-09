import { RESOURCE_NAMES, type ResourceExplorer, type ResourceTool } from './resource-actions';

// Deliberately limited to the experimental browser API used by this adapter.
export interface BrowserTool {
  name: ResourceTool;
  description: string;
  inputSchema: Record<string, unknown>;
  annotations: { readOnlyHint: boolean; consequentialHint: boolean };
  execute: (args: unknown, options?: { signal?: AbortSignal }) => Promise<string>;
}
export interface ModelContext {
  registerTool(tool: BrowserTool, options: { signal: AbortSignal }): Promise<unknown>;
}
export type RegistrationStatus = 'unavailable' | 'registering' | 'ready' | 'failed' | 'stopped';
export interface Registration {
  ready: Promise<void>;
  status: RegistrationStatus;
  dispose(): void;
}
const registrations = new WeakMap<ModelContext, Registration>();
const descriptions: Record<ResourceTool, string> = {
  sw_list:
    'List all Star Wars records of a resource. Opens the list on this page and clears its search filter. Returns complete records.',
  sw_search:
    'Search Star Wars records by case-insensitive name fragment (title for FILMS). Opens and fills the search on this page. Returns complete matching records.',
  sw_get:
    'Get a Star Wars record by its URL record ID, not film episode number (FILMS 1 is A New Hope). Opens its details on this page.',
  sw_random:
    'Select one random Star Wars record and open its details on this page. Returns the same selected record. Each call may select a different record.',
};

export function browserModelContext(): ModelContext | undefined {
  const context = (document as Document & { modelContext?: ModelContext }).modelContext;
  return typeof context?.registerTool === 'function' ? context : undefined;
}

export function registerWebMcp(
  explorer: ResourceExplorer,
  context = browserModelContext(),
): Registration {
  const existing = context && registrations.get(context);
  if (existing) return existing;
  const controller = new AbortController();
  const registration: Registration = {
    ready: Promise.resolve(),
    status: context ? 'registering' : 'unavailable',
    dispose() {
      controller.abort();
      if (context) registrations.delete(context);
      registration.status = 'stopped';
    },
  };
  if (!context) return registration;
  registrations.set(context, registration);
  registration.ready = (async () => {
    try {
      for (const name of Object.keys(descriptions) as ResourceTool[]) {
        controller.signal.throwIfAborted();
        const properties: Record<string, unknown> = {
          resource: { type: 'string', enum: RESOURCE_NAMES },
        };
        if (name === 'sw_search')
          properties.query = {
            type: 'string',
            description: 'Name/title fragment; empty string matches all records.',
          };
        if (name === 'sw_get')
          properties.id = { type: 'integer', minimum: 1, maximum: Number.MAX_SAFE_INTEGER };
        await context.registerTool(
          {
            name,
            description: descriptions[name],
            inputSchema: {
              type: 'object',
              properties,
              required: Object.keys(properties),
              additionalProperties: false,
            },
            annotations: { readOnlyHint: false, consequentialHint: false },
            execute: async (args, { signal } = {}) =>
              JSON.stringify(await explorer.execute(name, args, { signal })),
          },
          { signal: controller.signal },
        );
      }
      if (!controller.signal.aborted) registration.status = 'ready';
    } catch {
      controller.abort();
      if (registration.status !== 'stopped') registration.status = 'failed';
    }
  })();
  return registration;
}
