import { ApiError, fetchResources, searchResource, fetchResourceById, fetchRandom } from './api';
import { getResourceMeta, RESOURCES } from './constants';
import { renderResourceList, renderResourceDetail } from './pages/resource';

export const RESOURCE_NAMES = RESOURCES.map((resource) => resource.key.toUpperCase());
export type ResourceTool = 'sw_list' | 'sw_search' | 'sw_get' | 'sw_random';
export type Entity = Record<string, unknown>;
export type ActionResult =
  | {
      ok: true;
      resource: string;
      path: string;
      data: Entity | Entity[];
      count?: number;
      id?: number;
    }
  | { ok: false; error: { code: string; message: string } };

function failure(code: string, message: string): ActionResult {
  return { ok: false, error: { code, message } };
}

function validArgs(
  tool: ResourceTool,
  args: unknown,
): args is { resource: string; query?: string; id?: number } {
  if (!args || typeof args !== 'object' || Array.isArray(args)) return false;
  const value = args as Record<string, unknown>;
  const keys = [
    'resource',
    ...(tool === 'sw_search' ? ['query'] : tool === 'sw_get' ? ['id'] : []),
  ];
  return (
    ['sw_list', 'sw_search', 'sw_get', 'sw_random'].includes(tool) &&
    Object.keys(value).every((key) => keys.includes(key)) &&
    typeof value.resource === 'string' &&
    RESOURCE_NAMES.includes(value.resource) &&
    (tool !== 'sw_search' || typeof value.query === 'string') &&
    (tool !== 'sw_get' ||
      (typeof value.id === 'number' && Number.isSafeInteger(value.id) && value.id > 0))
  );
}

export function entityId(entity: Entity, type: string): number {
  const match =
    typeof entity.url === 'string' && entity.url.match(new RegExp(`/api/${type}/([1-9][0-9]*)/?$`));
  const id = match ? Number(match[1]) : NaN;
  if (!Number.isSafeInteger(id) || typeof entity[getResourceMeta(type).nameField] !== 'string') {
    throw new SyntaxError('Invalid resource record');
  }
  return id;
}

function validateEntity(value: unknown, type: string): Entity {
  if (!value || typeof value !== 'object' || Array.isArray(value))
    throw new SyntaxError('Invalid resource record');
  const entity = value as Entity;
  entityId(entity, type);
  return entity;
}

/** Owns the visible resource operation, shared by browser tools and human controls. */
export class ResourceExplorer {
  private active: AbortController | null = null;
  private generation = 0;
  private onInteraction = () => {
    this.interrupt();
    this.onHumanAction();
  };
  private onClick = (event: Event) => {
    if (
      event.target instanceof Element &&
      event.target.closest('button, input[type="submit"], input[type="button"], summary')
    ) {
      this.onInteraction();
    }
  };

  private onKeyDown = (event: KeyboardEvent) => {
    if (
      event.key === 'Enter' &&
      event.target instanceof Element &&
      event.target.closest('input, button, summary')
    ) {
      this.onInteraction();
    }
  };

  constructor(
    private container: HTMLElement,
    private commit: (path: string) => void,
    private onHumanAction: () => void = () => {},
  ) {
    container.addEventListener('input', this.onInteraction);
    // Capture before the control starts its own action; bubbling would cancel it.
    container.addEventListener('click', this.onClick, true);
    container.addEventListener('submit', this.onInteraction, true);
    container.addEventListener('keydown', this.onKeyDown, true);
  }

  interrupt(): void {
    this.generation++;
    this.active?.abort('SUPERSEDED');
    this.active = null;
    this.container.removeAttribute('aria-busy');
  }

  dispose(): void {
    this.interrupt();
    this.container.removeEventListener('input', this.onInteraction);
    this.container.removeEventListener('click', this.onClick, true);
    this.container.removeEventListener('submit', this.onInteraction, true);
    this.container.removeEventListener('keydown', this.onKeyDown, true);
  }

  async execute(
    tool: ResourceTool,
    args: unknown,
    options: { signal?: AbortSignal; human?: boolean } = {},
  ): Promise<ActionResult> {
    if (!validArgs(tool, args))
      return failure(
        'INVALID_ARGUMENT',
        'Use a supported resource and the required tool arguments.',
      );
    if (options.human) this.interrupt();
    if (this.active)
      return failure('BUSY', 'Another page action is running. Try again when it finishes.');
    if (options.signal?.aborted) return failure('CANCELLED', 'Action cancelled.');

    const controller = new AbortController();
    const cancel = () => controller.abort('CANCELLED');
    options.signal?.addEventListener('abort', cancel, { once: true });
    this.active = controller;
    const generation = ++this.generation;
    const type = args.resource.toLowerCase();
    this.container.setAttribute('aria-busy', 'true');
    this.container.querySelector('[data-resource-error]')?.remove();
    try {
      let data: Entity | Entity[];
      let id: number | undefined;
      let status: number;
      if (tool === 'sw_get' || tool === 'sw_random') {
        const response =
          tool === 'sw_get'
            ? await fetchResourceById(type, String(args.id), controller.signal)
            : await fetchRandom(type, controller.signal);
        data = validateEntity(response.data, type);
        id = entityId(data, type);
        if (tool === 'sw_get' && id !== args.id) throw new SyntaxError('Unexpected resource ID');
        status = response.status;
      } else {
        const response =
          tool === 'sw_search'
            ? await searchResource(type, args.query!, controller.signal)
            : await fetchResources(type, controller.signal);
        if (!Array.isArray(response.data)) throw new SyntaxError('Invalid resource list');
        data = response.data.map((entity) => validateEntity(entity, type));
        status = response.status;
      }
      controller.signal.throwIfAborted();
      if (generation !== this.generation)
        return failure('SUPERSEDED', 'The user changed the page.');
      const path = `/resource/${type}${id === undefined ? '' : `/${id}`}`;
      this.commit(path);
      if (Array.isArray(data)) {
        renderResourceList(
          this.container,
          type,
          data,
          tool === 'sw_search' ? args.query! : '',
          (action, query) => {
            void this.execute(
              action,
              { resource: args.resource, ...(action === 'sw_search' ? { query } : {}) },
              { human: true },
            );
          },
        );
      } else {
        renderResourceDetail(this.container, type, id!, data, status);
      }
      return {
        ok: true,
        resource: args.resource,
        path,
        data,
        ...(Array.isArray(data) ? { count: data.length } : { id }),
      };
    } catch (error) {
      if (controller.signal.aborted)
        return failure(String(controller.signal.reason), 'Action cancelled or superseded.');
      const code =
        error instanceof SyntaxError
          ? 'INVALID_RESPONSE'
          : error instanceof ApiError
            ? error.type === 'network'
              ? 'NETWORK_ERROR'
              : error.status === 404
                ? 'NOT_FOUND'
                : 'HTTP_ERROR'
            : 'INVALID_RESPONSE';
      const message =
        error instanceof ApiError
          ? error.message
          : 'The API returned an invalid resource response.';
      if (generation === this.generation) {
        const notice = document.createElement('p');
        notice.className = 'error-message';
        notice.dataset.resourceError = '';
        notice.setAttribute('role', 'alert');
        notice.textContent = message;
        this.container.appendChild(notice);
      }
      return failure(code, message);
    } finally {
      options.signal?.removeEventListener('abort', cancel);
      if (this.active === controller) {
        this.active = null;
        this.container.removeAttribute('aria-busy');
      }
    }
  }
}
