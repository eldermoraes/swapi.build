import { RESOURCES } from './constants';
import { site } from './site';

export type Page =
  | 'home'
  | 'docs'
  | 'mcp'
  | 'webmcp'
  | 'about'
  | 'privacy'
  | 'terms'
  | 'stats'
  | 'resource-list'
  | 'resource-detail'
  | 'not-found';

export interface Route {
  page: Page;
  type?: string;
  id?: string;
}

/** A record id in its only accepted form: no leading zero, at most 9 digits (KnownEntities.java). */
export const CANONICAL_ID = /^[1-9]\d{0,8}$/;

/** The pages a site.json entry may name; the rest of Page is not a fixed path. */
const FIXED_PAGES: readonly Page[] = [
  'home',
  'docs',
  'mcp',
  'webmcp',
  'about',
  'privacy',
  'terms',
  'stats',
];

function isFixedPage(page: string): page is Page {
  return (FIXED_PAGES as readonly string[]).includes(page);
}

/** The fixed pages, from seo/site.json, the same file the server reads (2.10.0). */
const PAGES: Record<string, Page> = Object.fromEntries(
  site.pages.map(({ path, page }) => {
    if (!isFixedPage(page)) throw new Error(`seo/site.json: ${path}: unknown page "${page}"`);
    return [path, page];
  }),
);

/**
 * The page for a location.pathname. Exact, like SeoRoutes on the server, which
 * answers 404 to everything this calls not-found; both read the fixed pages from
 * seo/site.json. A trailing slash is accepted here: since 2.10.0 the server
 * answers it with a 301 to the bare path, and the client reads both the same way.
 */
export function getRoute(pathname: string): Route {
  const path = pathname.length > 1 && pathname.endsWith('/') ? pathname.slice(0, -1) : pathname;
  const fixed = PAGES[path];
  if (fixed) return { page: fixed };

  const parts = path.split('/');
  // "/resource/people/1" -> ["", "resource", "people", "1"]
  if (parts[1] === 'resource' && RESOURCES.some((resource) => resource.key === parts[2])) {
    if (parts.length === 3) return { page: 'resource-list', type: parts[2] };
    if (parts.length === 4 && CANONICAL_ID.test(parts[3]))
      return { page: 'resource-detail', type: parts[2], id: parts[3] };
  }
  return { page: 'not-found' };
}

/**
 * The status the server marked in the head (meta swapi:status, only on its 404
 * page), read once at load: the element is removed, so a later navigation never
 * sees it. Null without the marker.
 */
export function takeServerStatus(doc: Document = document): number | null {
  const meta = doc.querySelector<HTMLMetaElement>('meta[name="swapi:status"]');
  if (!meta) return null;
  meta.remove();
  return meta.content === '404' ? 404 : null;
}
