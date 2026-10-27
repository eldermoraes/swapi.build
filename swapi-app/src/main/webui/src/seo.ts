import { getResourceMeta, RESOURCES } from './constants';
import { CANONICAL_ID } from './routes';

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

// Mirrored by hand in SeoMetadataRegistry.java until the single metadata file of
// slice D; seo-parity.test.ts compares the two. Intent words first, the brand
// "swapi.build" last; never "drop-in".
const BRAND_SUFFIX = ' | swapi.build';
const HOME_TITLE = `SWAPI alternative: free Star Wars API and MCP server${BRAND_SUFFIX}`;
const HOME_DESCRIPTION =
  'Free SWAPI alternative: Star Wars REST JSON API and remote MCP server with people, films, planets, species, starships and vehicles (Episodes I–VI). No API key.';
const OG_IMAGE_PATH = '/og-image.png';
const OG_IMAGE_ALT = 'swapi.build — Star Wars API and MCP server in a Holonet terminal style';
// NOT_FOUND_TITLE and NOT_FOUND_DESCRIPTION in SeoMetadataRegistry.java.
const NOT_FOUND_TITLE = `Page not found${BRAND_SUFFIX}`;
const NOT_FOUND_DESCRIPTION =
  'This page does not exist on swapi.build. Browse the Star Wars API, its docs or the MCP server instead.';

export const PUBLIC_SEO_ROUTES: PageSeoMetadata[] = [
  route('/', HOME_TITLE, HOME_DESCRIPTION),
  route(
    '/docs',
    `SWAPI docs: Star Wars REST API endpoints and OpenAPI${BRAND_SUFFIX}`,
    'Star Wars API reference for swapi.build: REST endpoints, JSON response schemas and live examples, generated from the OpenAPI contract.',
  ),
  route(
    '/docs/mcp',
    `Star Wars MCP server: remote, no API key${BRAND_SUFFIX}`,
    'Add the Star Wars MCP server to Claude, OpenAI Codex, GitHub Copilot or IBM Bob in one step. Remote Streamable HTTP at /mcp, read-only, no API key.',
  ),
  route(
    '/docs/webmcp',
    `Star Wars WebMCP tools in the browser${BRAND_SUFFIX}`,
    'Explore Star Wars together with a browser agent using experimental WebMCP tools.',
  ),
  route(
    '/about',
    `About: a SWAPI alternative built with Quarkus${BRAND_SUFFIX}`,
    'Learn why swapi.build exists, how it is built with Quarkus and GraalVM, and how to contribute.',
  ),
  ...RESOURCES.map((resource) =>
    route(
      `/resource/${resource.key}`,
      `Star Wars ${resource.title} API: JSON data${BRAND_SUFFIX}`,
      `Browse Star Wars ${recordNoun(resource.key)} records as JSON from the free swapi.build REST API and MCP server.`,
    ),
  ),
  route(
    '/stats',
    `Usage stats${BRAND_SUFFIX}`,
    'Anonymous request counts for the swapi.build REST API, MCP server and site, per route and per group, for the last 24 hours, 7 days or 30 days.',
  ),
  route(
    '/privacy',
    `Privacy Policy${BRAND_SUFFIX}`,
    'Plain-language privacy policy for swapi.build, the free public Star Wars API and MCP server.',
  ),
  route(
    '/terms',
    `Terms of Use${BRAND_SUFFIX}`,
    'Terms of use for swapi.build, the free public Star Wars API and MCP server.',
  ),
];

/** The title as a screen reader announces it on navigation: without the brand suffix. */
export function spokenTitle(title: string): string {
  return title.endsWith(BRAND_SUFFIX) ? title.slice(0, -BRAND_SUFFIX.length) : title;
}

export function getSeoMetadata(rawPath: string): SeoMetadata {
  const path = normalizePath(rawPath);
  const known = PUBLIC_SEO_ROUTES.find((route) => route.path === path);
  if (known) return known;

  const resourceDetail = resourceDetailMetadata(path);
  if (resourceDetail) return resourceDetail;

  return notFoundMetadata(path);
}

/**
 * Rewrites the head for a navigation. `notFound` is for a path that looks like a
 * page but is a 404 (the server marked it, or the API has no such id). On a 404
 * only the title, the description and robots are written; the canonical and
 * og:url are removed and no Open Graph or Twitter Card tag is written, as on the
 * server. The next navigation to a page recreates what is missing.
 */
export function applySeoMetadata(
  rawPath: string,
  origin: string,
  options: { notFound?: boolean } = {},
): SeoMetadata {
  const meta = options.notFound
    ? notFoundMetadata(normalizePath(rawPath))
    : getSeoMetadata(rawPath);

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

/**
 * The singular noun used as a modifier ("film records", not "films records").
 * "people" and "species" are already right. Same rule as SeoMetadataRegistry.java.
 */
function recordNoun(key: string): string {
  return key === 'people' || key === 'species' ? key : key.replace(/s$/, '');
}

function route(path: string, title: string, description: string): PageSeoMetadata {
  return { path, canonicalPath: path, title, description, robots: 'index,follow' };
}

function notFoundMetadata(path: string): SeoMetadata {
  return {
    path,
    canonicalPath: null,
    title: NOT_FOUND_TITLE,
    description: NOT_FOUND_DESCRIPTION,
    robots: 'noindex',
  };
}

/** Only one of the six types with a canonical id, like getRoute (routes.ts). */
function resourceDetailMetadata(path: string): SeoMetadata | undefined {
  const parts = path.split('/').filter(Boolean);
  if (parts.length !== 3 || parts[0] !== 'resource') return undefined;
  if (!RESOURCES.some((resource) => resource.key === parts[1])) return undefined;
  if (!CANONICAL_ID.test(parts[2])) return undefined;
  const meta = getResourceMeta(parts[1]);
  return {
    path,
    canonicalPath: `/resource/${meta.key}`,
    title: `${meta.title} #${parts[2]}${BRAND_SUFFIX}`,
    description: `Star Wars ${recordNoun(meta.key)} record #${parts[2]} from the swapi.build REST API.`,
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
