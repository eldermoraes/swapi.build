import siteJson from '../../resources/seo/site.json';

/**
 * seo/site.json, the one source of page metadata (2.10.0): the server reads it in
 * SeoSiteFile.java and validates it at startup (an invalid file stops the app);
 * the site reads the same file at build time. golden.json is checked on both
 * sides (SeoGoldenTest and seo-golden.test.ts).
 */
export interface SitePage {
  path: string;
  page: string;
  title: string;
  description: string;
}

export interface SiteResource {
  type: string;
  list: { title: string; description: string };
  detail: {
    indexable: boolean;
    fields: string[];
    title: string;
    description: string;
    fallbackTitle: string;
    fallbackDescription: string;
  };
}

export interface Site {
  brandSuffix: string;
  notFound: { title: string; description: string };
  pages: SitePage[];
  resources: SiteResource[];
}

export const site: Site = siteJson;

const PLACEHOLDER = /\{([a-z_]+)\}/g;

/**
 * The template with every {key} replaced by its value; undefined when a key has
 * no value or a blank one, so the caller falls back. Same rule as
 * SeoSiteFile.render on the server.
 */
export function renderTemplate(
  template: string,
  values: Record<string, string>,
): string | undefined {
  let missing = false;
  const out = template.replace(PLACEHOLDER, (_, key: string) => {
    const value = Object.prototype.hasOwnProperty.call(values, key) ? values[key] : undefined;
    if (value === undefined || value.trim() === '') {
      missing = true;
      return '';
    }
    return value;
  });
  return missing ? undefined : out;
}

/**
 * The values a template may use, read from a record: strings as they are,
 * integers in plain decimal (episode_id, as Integer.toString on the server).
 * Anything else is left out, so the template falls back.
 */
export function recordValues(
  record: Record<string, unknown>,
  fields: readonly string[],
): Record<string, string> {
  const values: Record<string, string> = {};
  for (const field of fields) {
    const value = record[field];
    if (typeof value === 'string') values[field] = value;
    else if (typeof value === 'number' && Number.isSafeInteger(value))
      values[field] = String(value);
  }
  return values;
}
