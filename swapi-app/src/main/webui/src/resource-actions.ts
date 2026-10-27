import { ApiError, fetchResources, searchResource, fetchResourceById, fetchRandom } from './api';
import { getResourceMeta, RESOURCES } from './constants';
import { renderResourceList, renderResourceDetail, bindRecord, recordText } from './pages/resource';
import { fetchServerMain, forgetServerMain, showLoadFailure, swapServerMain } from './server-main';

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
    /** Makes `path` the page; `entity` is the record of a detail, undefined for a list. */
    private commit: (path: string, entity?: Entity) => void,
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
      if (tool === 'sw_get' || tool === 'sw_random') {
        // sw_random asks the API which record (one request to /api); then both
        // take the document of the record, which the server drew (2.12.0).
        const wanted =
          tool === 'sw_get'
            ? args.id!
            : entityId(
                validateEntity((await fetchRandom(type, controller.signal)).data, type),
                type,
              );
        return await this.openRecord(args.resource, type, wanted, controller, generation);
      }
      const response =
        tool === 'sw_search'
          ? await searchResource(type, args.query!, controller.signal)
          : await fetchResources(type, controller.signal);
      if (!Array.isArray(response.data)) throw new SyntaxError('Invalid resource list');
      const data = response.data.map((entity) => validateEntity(entity, type));
      controller.signal.throwIfAborted();
      if (generation !== this.generation)
        return failure('SUPERSEDED', 'The user changed the page.');
      const path = `/resource/${type}`;
      this.commit(path);
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
      return { ok: true, resource: args.resource, path, data, count: data.length };
    } catch (error) {
      if (controller.signal.aborted)
        return failure(String(controller.signal.reason), 'Action cancelled or superseded.');
      if (error instanceof RecordDocumentError && generation === this.generation) {
        // The document of a record did not come (5xx, network, timeout): the page
        // that was there stays, with an alert and a full load of the record.
        showLoadFailure(this.container, error.path);
        this.container.querySelector('.sw-load-failure')!.setAttribute('data-resource-error', '');
        return failure(
          error.code ?? (error.type === 'network' ? 'NETWORK_ERROR' : 'HTTP_ERROR'),
          error.message,
        );
      }
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

  /**
   * A record (2.12.0, slice E2): one request, the document the server drew. The
   * record comes from its <pre>, checked like an answer of the API, before any
   * effect; then the commit (head, history) and the swap, so `execute` resolves
   * with the record on the page. Errors are thrown to the catch of `execute`.
   */
  private async openRecord(
    resource: string,
    type: string,
    id: number,
    controller: AbortController,
    generation: number,
  ): Promise<ActionResult> {
    const path = `/resource/${type}/${id}`;
    const fetched = await fetchServerMain(
      path,
      { page: 'resource-detail', type, id: String(id) },
      { signal: controller.signal, isCurrent: () => generation === this.generation },
    );
    controller.signal.throwIfAborted();
    if (generation !== this.generation) return failure('SUPERSEDED', 'The user changed the page.');
    switch (fetched.result) {
      case 'ok': {
        let data: Entity;
        try {
          const text = recordText(fetched.main!);
          if (text === null) throw new SyntaxError('Invalid resource record');
          data = validateEntity(JSON.parse(text), type);
          if (entityId(data, type) !== id) throw new SyntaxError('Unexpected resource ID');
        } catch (error) {
          // The marker matched, so fetchServerMain kept this <main>; its record
          // did not hold. Drop it, so a new try asks again, and offer the full load.
          forgetServerMain(path);
          if (!(error instanceof SyntaxError)) throw error;
          throw new RecordDocumentError(
            'The page returned an invalid record.',
            200,
            'http',
            path,
            'INVALID_RESPONSE',
          );
        }
        this.commit(path, data);
        swapServerMain(this.container, fetched.main!);
        bindRecord(this.container);
        return { ok: true, resource, path, data, id };
      }
      case 'not-found':
        throw new ApiError('HTTP 404: Not Found', 404, 'http');
      case 'error':
        throw fetched.status
          ? new RecordDocumentError(`HTTP ${fetched.status}`, fetched.status, 'http', path)
          : new RecordDocumentError('Network error — check your connection', 0, 'network', path);
      case 'unavailable': {
        // The document came without its marker on the address this tab loaded:
        // no reload (that would loop), the minimal detail from the API instead.
        const response = await fetchResourceById(type, String(id), controller.signal);
        const data = validateEntity(response.data, type);
        if (entityId(data, type) !== id) throw new SyntaxError('Unexpected resource ID');
        controller.signal.throwIfAborted();
        if (generation !== this.generation)
          return failure('SUPERSEDED', 'The user changed the page.');
        this.commit(path, data);
        renderResourceDetail(this.container, type, id, data, response.status);
        return { ok: true, resource, path, data, id };
      }
      case 'reload':
        return failure('SUPERSEDED', 'A full page load of the record is on its way.');
      default:
        return failure('SUPERSEDED', 'The user changed the page.');
    }
  }
}

/**
 * A record document that did not come (5xx, network) or whose record did not
 * hold (INVALID_RESPONSE): the alert offers its full load.
 */
class RecordDocumentError extends ApiError {
  constructor(
    message: string,
    status: number,
    type: 'network' | 'http',
    readonly path: string,
    readonly code?: 'INVALID_RESPONSE',
  ) {
    super(message, status, type);
  }
}
