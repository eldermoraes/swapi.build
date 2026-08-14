import { getResourceMeta, RESOURCES } from './constants';

export interface SeoMetadata {
  path: string;
  canonicalPath: string;
  title: string;
  description: string;
  robots: 'index,follow' | 'noindex,follow';
}

const HOME_DESCRIPTION =
  'Free Star Wars REST API and MCP server with data about people, films, planets, species, starships and vehicles.';
const OG_IMAGE_PATH = '/og-image.png';
const OG_IMAGE_ALT = 'SWAPI — Star Wars API and MCP server in a Holonet terminal style';

export const PUBLIC_SEO_ROUTES: SeoMetadata[] = [
  route('/', 'SWAPI - The Star Wars API', HOME_DESCRIPTION),
  route(
    '/docs',
    'Documentation - SWAPI',
    'OpenAPI documentation for the SWAPI REST API, including live examples and response schemas.',
  ),
  route(
    '/docs/mcp',
    'MCP Server - SWAPI',
    'Connect AI agents to the Star Wars API through the Streamable HTTP MCP server.',
  ),
  route(
    '/about',
    'About - SWAPI',
    'Learn why SWAPI exists, how it is built with Quarkus and GraalVM, and how to contribute.',
  ),
  ...RESOURCES.map((resource) =>
    route(
      `/resource/${resource.key}`,
      `${resource.title} - SWAPI`,
      `Browse Star Wars ${resource.title.toLowerCase()} records through the SWAPI REST API.`,
    ),
  ),
  route(
    '/privacy',
    'Privacy Policy - SWAPI',
    'Plain-language privacy policy for the public Star Wars API and MCP server.',
  ),
  route('/terms', 'Terms of Use - SWAPI', 'Terms of use for the free public Star Wars API and MCP server.'),
];

export function getSeoMetadata(rawPath: string): SeoMetadata {
  const path = normalizePath(rawPath);
  const known = PUBLIC_SEO_ROUTES.find((route) => route.path === path);
  if (known) return known;

  const resourceDetail = resourceDetailMetadata(path);
  if (resourceDetail) return resourceDetail;

  return {
    path,
    canonicalPath: '/',
    title: 'SWAPI - The Star Wars API',
    description: HOME_DESCRIPTION,
    robots: 'noindex,follow',
  };
}

export function applySeoMetadata(rawPath: string, origin: string): SeoMetadata {
  const meta = getSeoMetadata(rawPath);
  const canonical = absoluteUrl(origin, meta.canonicalPath);
  const image = absoluteUrl(origin, OG_IMAGE_PATH);

  document.title = meta.title;
  setMetaName('description', meta.description);
  setMetaName('robots', meta.robots);
  setCanonical(canonical);

  setMetaProperty('og:type', 'website');
  setMetaProperty('og:site_name', 'SWAPI');
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

function route(path: string, title: string, description: string): SeoMetadata {
  return { path, canonicalPath: path, title, description, robots: 'index,follow' };
}

function resourceDetailMetadata(path: string): SeoMetadata | undefined {
  const parts = path.split('/').filter(Boolean);
  if (parts.length !== 3 || parts[0] !== 'resource') return undefined;
  const meta = getResourceMeta(parts[1]);
  if (!RESOURCES.some((resource) => resource.key === meta.key)) return undefined;
  return {
    path,
    canonicalPath: `/resource/${meta.key}`,
    title: `${meta.title} #${parts[2]} - SWAPI`,
    description: `Star Wars ${meta.title.toLowerCase()} record #${parts[2]} from the SWAPI REST API.`,
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
