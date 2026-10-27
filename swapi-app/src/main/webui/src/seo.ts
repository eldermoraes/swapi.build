import { CANONICAL_ID } from './routes';
import { recordValues, renderTemplate, site } from './site';

export interface SeoMetadata {
  path: string;
  /** Null on the 404 page, which has no canonical. */
  canonicalPath: string | null;
  title: string;
  description: string;
  robots: 'index,follow' | 'noindex,follow' | 'noindex';
}

/** A page that exists: it always has a canonical. */
export type PageSeoMetadata = SeoMetadata & { canonicalPath: string };

// Every title and description comes from seo/site.json, the file the server
// also reads (SeoMetadataRegistry.java); golden.json checks both sides. Intent
// words first, the brand "swapi.build" last; never "drop-in".
const BRAND_SUFFIX = site.brandSuffix;
const OG_IMAGE_PATH = '/og-image.png';
const OG_IMAGE_ALT = 'swapi.build — Star Wars API and MCP server in a Holonet terminal style';

export const PUBLIC_SEO_ROUTES: PageSeoMetadata[] = [
  ...site.pages.map((page) => route(page.path, page.title, page.description)),
  ...site.resources.map((resource) =>
    route(`/resource/${resource.type}`, resource.list.title, resource.list.description),
  ),
];

/** The title as a screen reader announces it on navigation: without the brand suffix. */
export function spokenTitle(title: string): string {
  return title.endsWith(BRAND_SUFFIX) ? title.slice(0, -BRAND_SUFFIX.length) : title;
}

/**
 * The metadata of a path. On a record address, `entity` is the record the API
 * returned: its fields fill the templates of its type. Without it (or with an
 * empty field) the fallback title and description of the type are used; robots
 * and the canonical depend only on the type, never on the record.
 */
export function getSeoMetadata(rawPath: string, entity?: Record<string, unknown>): SeoMetadata {
  const path = normalizePath(rawPath);
  const known = PUBLIC_SEO_ROUTES.find((route) => route.path === path);
  if (known) return known;

  const resourceDetail = resourceDetailMetadata(path, entity);
  if (resourceDetail) return resourceDetail;

  return notFoundMetadata(path);
}

/**
 * Rewrites the head for a navigation. `notFound` is for a path that looks like a
 * page but is a 404 (the server marked it, or the API has no such id). On a 404
 * only the title, the description and robots are written; the canonical and
 * og:url are removed and no Open Graph or Twitter Card tag is written, as on the
 * server. The next navigation to a page recreates what is missing. `entity` is
 * the record of a record address (getSeoMetadata).
 */
export function applySeoMetadata(
  rawPath: string,
  origin: string,
  options: { notFound?: boolean; entity?: Record<string, unknown> } = {},
): SeoMetadata {
  const meta = options.notFound
    ? notFoundMetadata(normalizePath(rawPath))
    : getSeoMetadata(rawPath, options.entity);

  document.title = meta.title;
  setMetaName('description', meta.description);
  setMetaName('robots', meta.robots);
  if (meta.canonicalPath === null) {
    document.querySelector('link[rel="canonical"]')?.remove();
    document.querySelector('meta[property="og:url"]')?.remove();
    return meta;
  }

  const canonical = absoluteUrl(origin, meta.canonicalPath);
  const image = absoluteUrl(origin, OG_IMAGE_PATH);
  setCanonical(canonical);

  setMetaProperty('og:type', 'website');
  setMetaProperty('og:site_name', 'swapi.build');
  setMetaProperty('og:locale', 'en_US');
  setMetaProperty('og:title', meta.title);
  setMetaProperty('og:description', meta.description);
  setMetaProperty('og:url', canonical);
  setMetaProperty('og:image', image);
  setMetaProperty('og:image:type', 'image/png');
  setMetaProperty('og:image:width', '1200');
  setMetaProperty('og:image:height', '630');
  setMetaProperty('og:image:alt', OG_IMAGE_ALT);

  setMetaName('twitter:card', 'summary_large_image');
  setMetaName('twitter:title', meta.title);
  setMetaName('twitter:description', meta.description);
  setMetaName('twitter:image', image);
  setMetaName('twitter:image:alt', OG_IMAGE_ALT);

  return meta;
}

function route(path: string, title: string, description: string): PageSeoMetadata {
  return { path, canonicalPath: path, title, description, robots: 'index,follow' };
}

function notFoundMetadata(path: string): SeoMetadata {
  return {
    path,
    canonicalPath: null,
    title: site.notFound.title,
    description: site.notFound.description,
    robots: 'noindex',
  };
}

/**
 * A record address: one of the six types with a canonical id, like getRoute
 * (routes.ts). Same rule as SeoMetadataRegistry.resourceDetail: an indexable
 * type gets index,follow and its own canonical; any other type noindex,follow
 * and the canonical of its list.
 */
function resourceDetailMetadata(
  path: string,
  entity: Record<string, unknown> | undefined,
): SeoMetadata | undefined {
  const parts = path.split('/').filter(Boolean);
  if (parts.length !== 3 || parts[0] !== 'resource') return undefined;
  const resource = site.resources.find((candidate) => candidate.type === parts[1]);
  if (!resource || !CANONICAL_ID.test(parts[2])) return undefined;
  const { detail } = resource;
  const values = { ...(entity ? recordValues(entity, detail.fields) : {}), id: parts[2] };
  const title =
    renderTemplate(detail.title, values) ?? renderTemplate(detail.fallbackTitle, values)!;
  const description =
    renderTemplate(detail.description, values) ??
    renderTemplate(detail.fallbackDescription, values)!;
  return detail.indexable
    ? { path, canonicalPath: path, title, description, robots: 'index,follow' }
    : {
        path,
        canonicalPath: `/resource/${resource.type}`,
        title,
        description,
        robots: 'noindex,follow',
      };
}

function normalizePath(rawPath: string): string {
  let path = rawPath.split(/[?#]/, 1)[0] || '/';
  if (!path.startsWith('/')) path = `/${path}`;
  return path.length > 1 && path.endsWith('/') ? path.slice(0, -1) : path;
}

function absoluteUrl(origin: string, path: string): string {
  return new URL(path, `${origin.replace(/\/$/, '')}/`).toString();
}

function setCanonical(href: string): void {
  let link = document.querySelector<HTMLLinkElement>('link[rel="canonical"]');
  if (!link) {
    link = document.createElement('link');
    link.rel = 'canonical';
    document.head.appendChild(link);
  }
  link.href = href;
}

function setMetaName(name: string, content: string): void {
  let meta = document.querySelector<HTMLMetaElement>(`meta[name="${cssEscape(name)}"]`);
  if (!meta) {
    meta = document.createElement('meta');
    meta.name = name;
    document.head.appendChild(meta);
  }
  meta.content = content;
}

function setMetaProperty(property: string, content: string): void {
  let meta = document.querySelector<HTMLMetaElement>(`meta[property="${cssEscape(property)}"]`);
  if (!meta) {
    meta = document.createElement('meta');
    meta.setAttribute('property', property);
    document.head.appendChild(meta);
  }
  meta.content = content;
}

function cssEscape(value: string): string {
  return value.replace(/\\/g, '\\\\').replace(/"/g, '\\"');
}
